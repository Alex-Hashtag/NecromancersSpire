package org.alex_hashtag.necros;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class RitualBrazierSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    @Nullable
    private static RitualBrazierSystem INSTANCE;

    private static final String BRAZIER_ID = "Ritual_Brazier";
    private static final String PORTAL_KEY_ID = "PortalKey_Necros";
    // TODO: re-enable configurable cost/reward feature
    // private static final String DEFAULT_COST_ID = "Ingredient_Voidheart";
    private static final float RITUAL_PREVIEW_HEIGHT = 1.15f;
    private static final int RITUAL_BURN_TICKS = 60;
    private static final int SCAN_INTERVAL_TICKS = 6000; // 5 minutes

    private final ConcurrentHashMap<String, Ref<EntityStore>> hologramRefs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveRitual> activeRituals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RewardWatch> rewardWatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastProcessedWorldTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> initializedWorlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastScanTick = new ConcurrentHashMap<>();

    public RitualBrazierSystem() {
        super(UseBlockEvent.Pre.class);
        INSTANCE = this;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event
    ) {
        if (event.getBlockType() == null || !BRAZIER_ID.equals(event.getBlockType().getId())) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        Vector3i target = event.getTargetBlock();
        BlockKey key = new BlockKey(world.getName(), target.x, target.y, target.z);
        String keyString = key.toStorageKey();
        ItemStack held = event.getContext().getHeldItem();

    // TODO: configurable cost/reward feature disabled
        // boolean creative = player.getGameMode() == GameMode.Creative;
        // boolean crouching = isCrouching(playerRef, store);
        // if (creative && crouching && held != null && !held.isEmpty()) { ... }
        // String requiredItemId = RitualBrazierStorage.get().getCost(keyString); ...

        String requiredItemId = "Ingredient_Voidheart";
        String rewardItemId = PORTAL_KEY_ID;

        if (activeRituals.containsKey(keyString) || rewardWatches.containsKey(keyString)) {
            if (playerRefComponent != null) {
                playerRefComponent.sendMessage(Message.translation("server.necros.ritual.in_progress").color("#ff5555"));
            }
            event.setCancelled(true);
            return;
        }

        if (held == null || held.isEmpty() || !requiredItemId.equals(held.getItemId())) {
            if (playerRefComponent != null) {
                playerRefComponent.sendMessage(Message.translation("server.necros.ritual.requires_item")
                        .param("item", requiredItemId)
                        .color("#ff5555"));
            }
            return;
        }

        if (event.getContext().getHeldItemContainer() == null) {
            return;
        }

        ItemStackSlotTransaction consumeTx = event.getContext()
                .getHeldItemContainer()
                .removeItemStackFromSlot(event.getContext().getHeldItemSlot(), held, 1);

        if (!consumeTx.succeeded()) {
            return;
        }

        event.getContext().setHeldItem(consumeTx.getSlotAfter());
        event.setCancelled(true);

        removeHologram(keyString, commandBuffer);
        Ref<EntityStore> sacrificeRef = spawnSacrificeVisual(target, requiredItemId, commandBuffer, store);
        if (sacrificeRef == null) {
            LOGGER.atWarning().log("[RitualBrazier] Failed to spawn sacrifice visual for %s", keyString);
            upsertHologram(keyString, target, requiredItemId, commandBuffer, store);
            return;
        }

        LOGGER.atInfo().log("[RitualBrazier] Ritual started at %s using %s", keyString, requiredItemId);
        activeRituals.put(keyString, new ActiveRitual(target, requiredItemId, rewardItemId, sacrificeRef, RITUAL_BURN_TICKS));

        if (playerRefComponent != null) {
            playerRefComponent.sendMessage(Message.translation("server.necros.ritual.begun").color("#7fd9c8"));
        }
    }

    static void tickSequences(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        RitualBrazierSystem instance = INSTANCE;
        if (instance == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();
        long tick = world.getTick();
        Long previous = instance.lastProcessedWorldTick.put(worldName, tick);
        if (previous != null && previous == tick) {
            return;
        }

        if (!instance.initializedWorlds.containsKey(worldName)) {
            instance.initializedWorlds.put(worldName, Boolean.TRUE);
            instance.rebuildHolograms(worldName, store, commandBuffer);
        }

        // Periodic scan for prefab/command-placed braziers
        Long lastScan = instance.lastScanTick.get(worldName);
        if (lastScan == null || (tick - lastScan) >= SCAN_INTERVAL_TICKS) {
            instance.lastScanTick.put(worldName, tick);
            instance.scanForNewBraziers(worldName, world, store, commandBuffer);
        }

        instance.tickActiveRituals(worldName, store, commandBuffer);
        instance.tickRewardWatches(worldName, store, commandBuffer);
    }

    private static boolean isCrouching(@Nonnull Ref<EntityStore> playerRef, @Nonnull Store<EntityStore> store) {
        MovementStatesComponent movement = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        if (movement == null) {
            return false;
        }

        MovementStates states = movement.getMovementStates();
        return states != null && states.crouching;
    }

    private void upsertHologram(
            @Nonnull String key,
            @Nonnull Vector3i target,
            @Nonnull String itemId,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Store<EntityStore> store
    ) {
        Ref<EntityStore> existingRef = hologramRefs.get(key);
        if (existingRef != null && existingRef.isValid()) {
            ItemComponent itemComponent = store.getComponent(existingRef, ItemComponent.getComponentType());
            if (itemComponent != null) {
                itemComponent.setItemStack(new ItemStack(itemId, 1));
            }

            TransformComponent transform = store.getComponent(existingRef, TransformComponent.getComponentType());
            if (transform != null) {
                transform.setPosition(new Vector3d(target.x + 0.5, target.y + RITUAL_PREVIEW_HEIGHT, target.z + 0.5));
            }
            return;
        }

        Holder<EntityStore> previewHolder = ItemComponent.generateItemDrop(
                commandBuffer,
                new ItemStack(itemId, 1),
                new Vector3d(target.x + 0.5, target.y + RITUAL_PREVIEW_HEIGHT, target.z + 0.5),
                Rotation3f.ZERO,
                0.0f,
                0.0f,
                0.0f
        );

        if (previewHolder == null) {
            return;
        }

        previewHolder.tryRemoveComponent(Velocity.getComponentType());
        previewHolder.tryRemoveComponent(PhysicsValues.getComponentType());
        previewHolder.tryRemoveComponent(DespawnComponent.getComponentType());

        ItemComponent itemComponent = previewHolder.getComponent(ItemComponent.getComponentType());
        if (itemComponent != null) {
            itemComponent.setPickupDelay(999999f);
        }

        Ref<EntityStore> ref = commandBuffer.addEntity(previewHolder, AddReason.SPAWN);
        hologramRefs.put(key, ref);
    }

    private void removeHologram(@Nonnull String key, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = hologramRefs.remove(key);
        if (ref != null && ref.isValid()) {
            commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
        }
    }


    @Nullable
    private static Ref<EntityStore> spawnSacrificeVisual(
            @Nonnull Vector3i target,
            @Nonnull String itemId,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Store<EntityStore> store
    ) {
        Holder<EntityStore> sacrificeHolder = ItemComponent.generateItemDrop(
                commandBuffer,
                new ItemStack(itemId, 1),
                new Vector3d(target.x + 0.5, target.y + 1.05, target.z + 0.5),
                Rotation3f.ZERO,
                0.0f,
                0.0f,
                0.0f
        );

        if (sacrificeHolder == null) {
            return null;
        }

        sacrificeHolder.tryRemoveComponent(Velocity.getComponentType());
        sacrificeHolder.tryRemoveComponent(PhysicsValues.getComponentType());

        // Set DespawnComponent to auto-despawn after burn duration
        TimeResource timeResource = store.getResource(TimeResource.getResourceType());
        float burnSeconds = (RITUAL_BURN_TICKS * 0.05f) + 2.0f;
        DespawnComponent despawn = sacrificeHolder.getComponent(DespawnComponent.getComponentType());
        if (despawn != null) {
            despawn.setDespawn(timeResource.getNow().plusNanos((long) (burnSeconds * 1_000_000_000L)));
        }

        ItemComponent itemComponent = sacrificeHolder.getComponent(ItemComponent.getComponentType());
        if (itemComponent != null) {
            itemComponent.setPickupDelay(999999f);
        }

        return commandBuffer.addEntity(sacrificeHolder, AddReason.SPAWN);
    }

    @Nullable
    private static Ref<EntityStore> spawnRewardItem(@Nonnull Vector3i target, @Nonnull String rewardItemId, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Vector3d pos = new Vector3d(target.x + 0.5, target.y + 1.1, target.z + 0.5);
        Holder<EntityStore> rewardHolder = ItemComponent.generateItemDrop(
                commandBuffer,
                new ItemStack(rewardItemId, 1),
                pos,
                Rotation3f.ZERO,
                0.0f,
                0.2f,
                0.0f
        );

        if (rewardHolder != null) {
            return commandBuffer.addEntity(rewardHolder, AddReason.SPAWN);
        } else {
            LOGGER.atWarning().log("Failed to create ritual brazier key drop entity at %s", pos);
            return null;
        }
    }

    private void tickActiveRituals(
            @Nonnull String worldName,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        World world = store.getExternalData().getWorld();
        for (Map.Entry<String, ActiveRitual> entry : activeRituals.entrySet()) {
            String key = entry.getKey();
            if (!isForWorld(key, worldName)) {
                LOGGER.atInfo().log("[RitualBrazier] Skipping ritual %s — not in world %s", key, worldName);
                continue;
            }

            ActiveRitual ritual = entry.getValue();

            // Validate brazier block still exists
            if (world != null && !isBrazierBlockAt(world, ritual.target)) {
                LOGGER.atInfo().log("[RitualBrazier] Brazier block missing at %s during ritual, canceling", key);
                if (ritual.sacrificeRef != null && ritual.sacrificeRef.isValid()) {
                    commandBuffer.removeEntity(ritual.sacrificeRef, RemoveReason.REMOVE);
                }
                activeRituals.remove(key);
                continue;
            }

            // Burn tick
            ritual.ticksRemaining--;
            if (ritual.ticksRemaining > 0) {
                if (ritual.ticksRemaining % 6 == 0) {
                    LOGGER.atInfo().log("[RitualBrazier] Ritual %s burning: %d ticks left", key, ritual.ticksRemaining);
                }
                continue;
            }

            // Burn complete — remove sacrifice entity
            if (ritual.sacrificeRef != null && ritual.sacrificeRef.isValid()) {
                commandBuffer.removeEntity(ritual.sacrificeRef, RemoveReason.REMOVE);
            }

            Vector3i target = ritual.target;
            Ref<EntityStore> rewardRef = spawnRewardItem(target, ritual.rewardItemId, commandBuffer);
            if (rewardRef == null) {
                LOGGER.atWarning().log("[RitualBrazier] Reward spawn failed at %s", key);
                upsertHologram(key, target, ritual.costItemId, commandBuffer, store);
            } else {
                LOGGER.atInfo().log("[RitualBrazier] Burn complete at %s, reward spawned", key);
                rewardWatches.put(key, new RewardWatch(target, ritual.costItemId, rewardRef));
            }

            activeRituals.remove(key);
        }
    }

    private void tickRewardWatches(
            @Nonnull String worldName,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        World world = store.getExternalData().getWorld();
        for (Map.Entry<String, RewardWatch> entry : rewardWatches.entrySet()) {
            String key = entry.getKey();
            if (!isForWorld(key, worldName)) {
                continue;
            }

            RewardWatch watch = entry.getValue();

            // Validate brazier block still exists
            if (world != null && !isBrazierBlockAt(world, watch.target)) {
                LOGGER.atInfo().log("[RitualBrazier] Brazier block missing at %s during reward watch, cleaning up", key);
                if (watch.rewardRef.isValid()) {
                    commandBuffer.removeEntity(watch.rewardRef, RemoveReason.REMOVE);
                }
                rewardWatches.remove(key);
                continue;
            }

            if (watch.rewardRef.isValid()) {
                continue;
            }

            upsertHologram(key, watch.target, watch.costItemId, commandBuffer, store);
            LOGGER.atInfo().log("[RitualBrazier] Reward collected/removed at %s, hologram restored", key);
            rewardWatches.remove(key);
        }
    }

    static void cleanupBlock(@Nonnull String key, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        RitualBrazierSystem instance = INSTANCE;
        if (instance == null) {
            return;
        }

        LOGGER.atInfo().log("[RitualBrazier] cleanupBlock: key=%s hasHologram=%b hasRitual=%b hasReward=%b",
                key,
                instance.hologramRefs.containsKey(key),
                instance.activeRituals.containsKey(key),
                instance.rewardWatches.containsKey(key));

        // Remove hologram
        instance.removeHologram(key, commandBuffer);

        // Cancel active ritual — remove sacrifice entity
        ActiveRitual ritual = instance.activeRituals.remove(key);
        if (ritual != null && ritual.sacrificeRef != null && ritual.sacrificeRef.isValid()) {
            commandBuffer.removeEntity(ritual.sacrificeRef, RemoveReason.REMOVE);
        }

        // Remove reward entity
        RewardWatch watch = instance.rewardWatches.remove(key);
        if (watch != null && watch.rewardRef.isValid()) {
            commandBuffer.removeEntity(watch.rewardRef, RemoveReason.REMOVE);
        }

        // Remove stored cost and reward
        RitualBrazierStorage.get().removeCost(key);
        RitualBrazierStorage.get().removeReward(key);
        RitualBrazierStorage.get().save();

        LOGGER.atInfo().log("[RitualBrazier] Cleaned up block at %s", key);
    }

    static String makeKey(@Nonnull String worldName, @Nonnull Vector3i pos) {
        return worldName + ":" + pos.x + ":" + pos.y + ":" + pos.z;
    }

    private static boolean isForWorld(@Nonnull String storageKey, @Nonnull String worldName) {
        return storageKey.startsWith(worldName + ":");
    }

    private static boolean isBrazierBlockAt(@Nonnull World world, @Nonnull Vector3i pos) {
        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.x, pos.z);
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) {
                return false;
            }
            BlockType blockType = chunk.getBlockType(pos.x, pos.y, pos.z);
            if (blockType == null) {
                return false;
            }
            return BRAZIER_ID.equals(blockType.getId());
        } catch (Exception e) {
            return false;
        }
    }

    private void rebuildHolograms(
            @Nonnull String worldName,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        World world = store.getExternalData().getWorld();
        Map<String, String> allCosts = RitualBrazierStorage.get().getAllCosts();
        for (Map.Entry<String, String> entry : allCosts.entrySet()) {
            String key = entry.getKey();
            if (!isForWorld(key, worldName)) {
                continue;
            }
            if (hologramRefs.containsKey(key)) {
                continue;
            }
            Vector3i pos = parseKeyPosition(key);
            if (pos == null) {
                continue;
            }
            // Skip if brazier block no longer exists
            if (world != null && !isBrazierBlockAt(world, pos)) {
                LOGGER.atInfo().log("[RitualBrazier] Brazier block missing at %s during rebuild, cleaning up storage", key);
                RitualBrazierStorage.get().removeCost(key);
                RitualBrazierStorage.get().removeReward(key);
                continue;
            }
            upsertHologram(key, pos, entry.getValue(), commandBuffer, store);
            LOGGER.atInfo().log("[RitualBrazier] Restored hologram at %s for item %s", key, entry.getValue());
        }
        RitualBrazierStorage.get().save();
    }

    private static final int SCAN_Y_MIN = 32;
    private static final int SCAN_Y_MAX = 320;

    private void scanForNewBraziers(
            @Nonnull String worldName,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        var loadedChunks = world.getChunkStore().getChunkIndexes();
        int foundCount = 0;

        for (long chunkIndex : loadedChunks) {
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) {
                continue;
            }

            int chunkBaseX = ChunkUtil.xOfChunkIndex(chunkIndex) << 5;
            int chunkBaseZ = ChunkUtil.zOfChunkIndex(chunkIndex) << 5;

            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    for (int y = SCAN_Y_MAX; y >= SCAN_Y_MIN; y--) {
                        BlockType blockType = chunk.getBlockType(x, y, z);
                        if (blockType == null || !BRAZIER_ID.equals(blockType.getId())) {
                            continue;
                        }

                        int worldX = chunkBaseX + x;
                        int worldZ = chunkBaseZ + z;
                        String key = worldName + ":" + worldX + ":" + y + ":" + worldZ;

                        if (hologramRefs.containsKey(key) || activeRituals.containsKey(key) || rewardWatches.containsKey(key)) {
                            continue;
                        }

                        if (RitualBrazierStorage.get().getCost(key) != null) {
                            continue;
                        }

                        Vector3i pos = new Vector3i(worldX, y, worldZ);
                        String defaultCost = "Ingredient_Voidheart";

                        RitualBrazierStorage.get().setCost(key, defaultCost);
                        upsertHologram(key, pos, defaultCost, commandBuffer, store);
                        LOGGER.atInfo().log("[RitualBrazier] Discovered prefab-placed brazier at %s, created hologram", key);
                        foundCount++;
                    }
                }
            }
        }

        if (foundCount > 0) {
            RitualBrazierStorage.get().save();
            LOGGER.atInfo().log("[RitualBrazier] Scan found %d new brazier(s) in world %s", foundCount, worldName);
        }
    }

    @Nullable
    private static Vector3i parseKeyPosition(@Nonnull String key) {
        int lastColon = key.lastIndexOf(':');
        if (lastColon < 0) return null;
        int secondColon = key.lastIndexOf(':', lastColon - 1);
        if (secondColon < 0) return null;
        int thirdColon = key.lastIndexOf(':', secondColon - 1);
        if (thirdColon < 0) return null;
        try {
            int x = Integer.parseInt(key.substring(thirdColon + 1, secondColon));
            int y = Integer.parseInt(key.substring(secondColon + 1, lastColon));
            int z = Integer.parseInt(key.substring(lastColon + 1));
            return new Vector3i(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class ActiveRitual {
        private final Vector3i target;
        private final String costItemId;
        private final String rewardItemId;
        @Nullable
        private final Ref<EntityStore> sacrificeRef;
        private int ticksRemaining;

        private ActiveRitual(@Nonnull Vector3i target, @Nonnull String costItemId, @Nonnull String rewardItemId, @Nullable Ref<EntityStore> sacrificeRef, int ticksRemaining) {
            this.target = new Vector3i(target.x, target.y, target.z);
            this.costItemId = costItemId;
            this.rewardItemId = rewardItemId;
            this.sacrificeRef = sacrificeRef;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final class RewardWatch {
        private final Vector3i target;
        private final String costItemId;
        private final Ref<EntityStore> rewardRef;

        private RewardWatch(@Nonnull Vector3i target, @Nonnull String costItemId, @Nonnull Ref<EntityStore> rewardRef) {
            this.target = new Vector3i(target.x, target.y, target.z);
            this.costItemId = costItemId;
            this.rewardRef = rewardRef;
        }
    }

    private static final class BlockKey {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(@Nonnull String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Nonnull
        private String toStorageKey() {
            return worldName + ":" + x + ":" + y + ":" + z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BlockKey other)) {
                return false;
            }
            return x == other.x && y == other.y && z == other.z && Objects.equals(worldName, other.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldName, x, y, z);
        }
    }
}

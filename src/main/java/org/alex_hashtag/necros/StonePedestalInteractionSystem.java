package org.alex_hashtag.necros;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class StonePedestalInteractionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String SWORD_STONE_ID = "Sword_Stone_Necros";
    private static final String SWORD_STONE_EMPTY_ID = "Sword_Stone_Empty";
    private static final String NECROTIC_BLADE_ID = "Necrotic_Blade";

    private static final String THRONE_CROWN_ID = "Throne_Crown_Necros";
    private static final String NECROTIC_CROWN_ID = "Necrotic_Crown";

    public StonePedestalInteractionSystem() {
        super(UseBlockEvent.Pre.class);
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
        if (event.getBlockType() == null) {
            return;
        }

        String blockId = event.getBlockType().getId();

        if (SWORD_STONE_ID.equals(blockId)) {
            handleSwordStone(event, store, commandBuffer);
        } else if (THRONE_CROWN_ID.equals(blockId)) {
            handleThroneCrown(event, store, commandBuffer);
        }
    }

    private void handleSwordStone(
            @Nonnull UseBlockEvent.Pre event,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        Vector3i target = event.getTargetBlock();

        // Replace the sword-in-stone block with the empty pedestal
        setBlock(world, target, SWORD_STONE_EMPTY_ID);

        // Drop the Necrotic Blade at the block position
        spawnItem(commandBuffer, target, NECROTIC_BLADE_ID);

        event.setCancelled(true);
        LOGGER.atInfo().log("[StonePedestal] Player pulled Necrotic_Blade from pedestal at %s", target);
    }

    private void handleThroneCrown(
            @Nonnull UseBlockEvent.Pre event,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        Vector3i target = event.getTargetBlock();

        // Replace throne-with-crown with plain throne (Necromancer_Throne)
        setBlock(world, target, "Necromancer_Throne");

        // Drop the Necrotic Crown at the block position
        spawnItem(commandBuffer, target, NECROTIC_CROWN_ID);

        event.setCancelled(true);
        LOGGER.atInfo().log("[StonePedestal] Player claimed Necrotic_Crown from throne at %s", target);
    }

    private static void setBlock(@Nonnull World world, @Nonnull Vector3i pos, @Nonnull String blockId) {
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        int index = blockTypeMap.getIndex(blockId);
        BlockType type = blockTypeMap.getAsset(index);
        if (type == null) {
            LOGGER.atWarning().log("[StonePedestal] Unknown block type: %s", blockId);
            return;
        }
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x, pos.z));
        if (chunk == null) {
            LOGGER.atWarning().log("[StonePedestal] Chunk not loaded at %s, cannot set block", pos);
            return;
        }
        BlockChunk blockChunk = chunk.getBlockChunk();
        int rotation = (blockChunk != null) ? blockChunk.getSectionAtBlockY(pos.y).getRotationIndex(pos.x, pos.y, pos.z) : 0;
        chunk.setBlock(pos.x, pos.y, pos.z, index, type, rotation, 0, 0);
    }

    private static void spawnItem(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Vector3i blockPos,
            @Nonnull String itemId
    ) {
        Vector3d dropPos = new Vector3d(blockPos.x + 0.5, blockPos.y + 1.0, blockPos.z + 0.5);
        Holder<EntityStore> holder = ItemComponent.generateItemDrop(
                commandBuffer,
                new ItemStack(itemId, 1),
                dropPos,
                Vector3f.ZERO,
                0.0f,
                0.2f,
                0.0f
        );
        if (holder != null) {
            Ref<EntityStore> ref = commandBuffer.addEntity(holder, AddReason.SPAWN);
            if (ref == null || !ref.isValid()) {
                LOGGER.atWarning().log("[StonePedestal] Failed to spawn item entity for %s at %s", itemId, dropPos);
            }
        } else {
            LOGGER.atWarning().log("[StonePedestal] generateItemDrop returned null for %s", itemId);
        }
    }
}

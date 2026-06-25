package org.alex_hashtag.necros;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;

/// Detects when a player uses the Vortexstrike (signature ability) while holding the
/// Necrotic Blade and wearing the Necrotic Crown, then releases captured souls as NPC allies.
///
/// Because PlayerInteractEvent is deprecated and never fires for weapon abilities, this
/// system uses a tick-based approach: it monitors each player's SignatureEnergy stat and
/// detects when it drops from near-full to near-empty (which happens when Vortexstrike
/// consumes 100% energy). This reliably triggers soul release alongside the normal
/// Vortexstrike animation.
///
/// Static helper methods are package-visible so [SoulReleaseCommand] can reuse
/// the spawning logic for testing.
public final class SoulReleaseSystem extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Optional speed-boost effect for revived allies (no visuals) */
    static final String SPEED_BOOST_EFFECT_ID = "Speed_Boost_Small";

    /** Spawn radius around the player (blocks). Must be larger than Vortexstrike_Spin's
     *  3-block AoE so freshly-spawned souls aren't instantly killed by the triggering strike. */
    static final double SPAWN_RADIUS = 4.5;

    /** Previous-tick SignatureEnergy percentage per player UUID */
    private final ConcurrentHashMap<UUID, Float> previousEnergy = new ConcurrentHashMap<>();

    /** Cooldown timestamp (System.currentTimeMillis) per player UUID */
    private final ConcurrentHashMap<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    /** Minimum cooldown between soul releases (milliseconds) */
    private static final long COOLDOWN_MS = 3000;

    /** Minimum energy-percentage drop in one tick that signifies a signature ability spend.
     *  Works for both the vanilla 100% Vortexstrike and the reduced 33% Necrotic variant. */
    private static final float ENERGY_DROP_DELTA = 0.15f;

    /** Previous-tick energy must have been at least this high (i.e. the ability was actually
     *  charged enough to fire) to avoid false positives from idle decay. */
    private static final float ENERGY_MIN_BEFORE = 0.30f;

    /** Cached stat index for SignatureEnergy — resolved lazily */
    private int signatureEnergyIndex = Integer.MIN_VALUE;

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), UUIDComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) return;

        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        UUID playerUuid = uuidComponent != null ? uuidComponent.getUuid() : null;
        if (playerUuid == null) return;

        // ── Resolve SignatureEnergy stat index once ──
        if (signatureEnergyIndex == Integer.MIN_VALUE) {
            signatureEnergyIndex = EntityStatType.getAssetMap().getIndex("SignatureEnergy");
            if (signatureEnergyIndex == Integer.MIN_VALUE) return;  // stat doesn't exist
        }

        // ── Read current SignatureEnergy percentage ──
        EntityStatMap statMap = store.getComponent(playerRef, EntityStatMap.getComponentType());
        if (statMap == null) return;

        EntityStatValue sigEnergy = statMap.get(signatureEnergyIndex);
        if (sigEnergy == null) {
            previousEnergy.remove(playerUuid);
            return;
        }

        float currentPct = sigEnergy.asPercentage();
        float prevPct = previousEnergy.getOrDefault(playerUuid, currentPct);
        previousEnergy.put(playerUuid, currentPct);

        // ── Detect sharp energy drop (Vortexstrike used) ──
        // Any drop of at least ENERGY_DROP_DELTA in a single tick signals a signature
        // ability spend, so this works for 33% (Necrotic) and 100% (vanilla) variants alike.
        if (prevPct < ENERGY_MIN_BEFORE) return;
        if ((prevPct - currentPct) < ENERGY_DROP_DELTA) return;

        // Cooldown check
        long now = System.currentTimeMillis();
        Long cd = cooldownUntil.get(playerUuid);
        if (cd != null && now < cd) return;

        LOGGER.atInfo().log("[SoulRelease] SignatureEnergy drop detected for %s (%.0f%% -> %.0f%%)",
                playerUuid, prevPct * 100, currentPct * 100);

        // Check if the player is holding the Necrotic Blade
        ItemStack heldItem = InventoryComponent.getItemInHand(store, playerRef);
        if (heldItem == null || heldItem.isEmpty()) return;
        if (!SoulStorage.NECROTIC_BLADE_ID.equals(heldItem.getItemId())) return;

        // Check if the player is wearing the Necrotic Crown (Head = slot 0)
        InventoryComponent.Armor armorComponent = store.getComponent(playerRef, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) return;

        ItemStack headSlot = armorComponent.getInventory().getItemStack((short) ItemArmorSlot.Head.getValue());
        if (headSlot == null || headSlot.isEmpty()) return;
        if (!SoulStorage.NECROTIC_CROWN_ID.equals(headSlot.getItemId())) return;

        LOGGER.atInfo().log("[SoulRelease] All checks passed — releasing souls for %s", playerUuid);

        // Set cooldown
        cooldownUntil.put(playerUuid, now + COOLDOWN_MS);

        // Release souls
        releaseSouls(store, playerRef);
    }

    // ── Shared logic (used by both the event listener and the test command) ──

    /**
     * Consumes souls from the player's storage and spawns them as NPCs.
     */
    static void releaseSouls(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        if (!playerRef.isValid()) return;

        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) return;

        UUID playerUuid = playerRefComponent.getUuid();
        if (playerUuid == null) return;

        // Consume souls from the crown (3-10, or all if less than 3)
        List<SoulStorage.Soul> soulRoles = SoulStorage.get().consumeSouls(playerUuid);
        if (soulRoles.isEmpty()) {
            playerRefComponent.sendMessage(Message.translation("server.necros.no_souls").color("#888888"));
            return;
        }

        // Get player world to schedule spawning on the world thread
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        int soulCount = soulRoles.size();
        playerRefComponent.sendMessage(Message.translation("server.necros.releasing_souls")
                .param("count", soulCount)
                .color("#aa00aa")
                .bold(true));

        // Schedule NPC spawning incrementally over multiple ticks to avoid frame hitches
        world.execute(() -> {
            try {
                Store<EntityStore> worldStore = world.getEntityStore().getStore();
                TransformComponent playerTransform = worldStore.getComponent(playerRef, TransformComponent.getComponentType());
                if (playerTransform == null) return;

                AtomicInteger idx = new AtomicInteger(0);
                Runnable spawner = new Runnable() {
                    @Override
                    public void run() {
                        int i = idx.getAndIncrement();
                        if (i >= soulCount) return;
                        try {
                            if (!playerRef.isValid()) return;
                            TransformComponent pt = worldStore.getComponent(playerRef, TransformComponent.getComponentType());
                            if (pt == null) return;
                            Vector3d playerPosNow = pt.getPosition();
                            spawnSoulNPC(worldStore, playerPosNow, soulRoles.get(i), i, soulCount, playerRef);
                        } finally {
                            if (idx.get() < soulCount) {
                                world.execute(this);
                            }
                        }
                    }
                };
                // Kick off the chain next tick
                world.execute(spawner);
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Error spawning soul NPCs for player %s", playerUuid);
            }
        });
    }

    /**
     * Single generic soul-ally role used for every revived mob type.
     * We always swap appearance to the captured mob so visuals remain diverse,
     * while ally/follow/combat behavior stays reliable for all revived creatures.
     */
    static final String SOUL_ROLE = "Soul_Ally";

    /** Try to preserve each captured mob's native AI by spawning its original role first. */
    static final boolean USE_NATIVE_ROLE_WHEN_POSSIBLE = false;

    /**
     * Spawns a single soul NPC near the player using the generic {@code Soul_Ally} role
     * (which extends {@code Template_Summoned_Ally}), then swaps its appearance to match
     * the captured mob's model. The ally joins the player's flock on spawn, inherits the
     * Template's summoned-ally combat behavior (FlockLeader damage triggers, hostile
     * detection, leash/despawn), and never targets the summoner.
     */
    static void spawnSoulNPC(@Nonnull Store<EntityStore> store, @Nonnull Vector3d playerPos,
                             @Nonnull SoulStorage.Soul soul, int index, int total,
                             @Nonnull Ref<EntityStore> playerRef) {
        // Spread souls in a circle around the player
        double angle = (2.0 * Math.PI * index) / total;
        double offsetX = Math.cos(angle) * SPAWN_RADIUS;
        double offsetZ = Math.sin(angle) * SPAWN_RADIUS;

        // Simplify: spawn roughly at the player's current Y to avoid heavy surface scans
        int surfaceY = (int) Math.floor(playerPos.y);

        Vector3d spawnPos = new Vector3d(
                playerPos.x + offsetX,
                surfaceY,
                playerPos.z + offsetZ
        );

        // Face outward from player
        float yaw = (float) Math.toDegrees(Math.atan2(offsetX, offsetZ));
        Rotation3f rotation = new Rotation3f(yaw, 0.0f, 0.0f);

        try {
            Pair<Ref<EntityStore>, INonPlayerCharacter> result = null;

            // Prefer spawning the captured mob role to preserve natural attack patterns and abilities
            if (USE_NATIVE_ROLE_WHEN_POSSIBLE) {
                try {
                    result = NPCPlugin.get().spawnNPC(store, soul.roleName(), null, spawnPos, rotation);
                } catch (Exception ignored) {
                    result = null;
                }
            }

            // Fallback to generic ally role if the native role cannot spawn
            if (result == null) {
                result = NPCPlugin.get().spawnNPC(store, SOUL_ROLE, null, spawnPos, rotation);
            }

            if (result != null) {
                Ref<EntityStore> soulRef = result.first();

                // Mark this entity as a released soul so it won't be recaptured
                NetworkId networkIdComponent = store.getComponent(soulRef, NetworkId.getComponentType());
                if (networkIdComponent != null) {
                    SoulStorage.get().markAsReleasedSoul(networkIdComponent.getId(), playerRef);
                }

                // Make sure native hostile roles never immediately aggro their summoner
                try {
                    NPCEntity spawnedNpc = store.getComponent(soulRef, NPCEntity.getComponentType());
                    if (spawnedNpc != null && spawnedNpc.getRole() != null) {
                        WorldSupport worldSupport = spawnedNpc.getRole().getWorldSupport();
                        if (worldSupport != null) {
                            worldSupport.overrideAttitude(playerRef, Attitude.IGNORE, 60.0);
                        }
                    }
                } catch (Exception ignored) {
                }

                // Apply #6a9 (teal-green) glow via DynamicLight
                applySoulGlow(soulRef, store);

                // Swap the ally's appearance to match the captured mob
                try {
                    NPCEntity.setAppearance(soulRef, soul.appearanceName(), store);
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log(
                            "Could not set soul appearance '%s' for mob '%s'",
                            soul.appearanceName(), soul.roleName());
                }

                // Apply a small invisible 5% speed boost
                applyBoostEffect(soulRef, store);

                // Set health to ~50% of max
                setHealthToHalf(soulRef, store);

                LOGGER.atFine().log("Spawned soul NPC #%d (%s, appearance=%s) at (%.1f, %.1f, %.1f)",
                        index, soul.roleName(), soul.appearanceName(),
                        spawnPos.x, spawnPos.y, spawnPos.z);
            } else {
                LOGGER.atWarning().log("Failed to spawn soul NPC #%d — role '%s' may not exist", index, SOUL_ROLE);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Exception spawning soul NPC #%d (%s)",
                    index, soul.roleName());
        }
    }

    /**
     * Applies the "Revived" status effect to a spawned soul NPC for the necrotic visual glow.
     */
    static void applyBoostEffect(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        try {
            EffectControllerComponent effectController = store.getComponent(npcRef, EffectControllerComponent.getComponentType());
            if (effectController == null) return;

            EntityEffect boost = EntityEffect.getAssetMap().getAsset(SPEED_BOOST_EFFECT_ID);
            if (boost != null) {
                effectController.addEffect(npcRef, boost, store);
            }
        } catch (Exception e) {
            LOGGER.atFine().withCause(e).log("Could not apply speed boost to soul NPC");
        }
    }

    /**
     * Applies a #6a9 (teal-green) dynamic light glow to a spawned soul NPC.
     * #6a9 expands to #66aa99 → R=102, G=170, B=153
     */
    static void applySoulGlow(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        try {
            ColorLight glowColor = new ColorLight((byte) 8, (byte) 102, (byte) 170, (byte) 153);
            DynamicLight dynamicLight = store.getComponent(npcRef, DynamicLight.getComponentType());
            if (dynamicLight != null) {
                dynamicLight.setColorLight(glowColor);
            } else {
                store.putComponent(npcRef, DynamicLight.getComponentType(), new DynamicLight(glowColor));
            }
        } catch (Exception e) {
            LOGGER.atFine().withCause(e).log("Could not apply soul glow to NPC");
        }
    }

    /**
     * Sets an entity's health to 50% of its max health.
     */
    static void setHealthToHalf(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) return;

            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = statMap.get(healthIndex);
            if (healthStat == null) return;

            float maxHealth = healthStat.getMax();
            float halfHealth = maxHealth * 0.5f;
            statMap.setStatValue(healthIndex, halfHealth);
        } catch (Exception e) {
            LOGGER.atFine().withCause(e).log("Could not set soul health to 50%%");
        }
    }

}

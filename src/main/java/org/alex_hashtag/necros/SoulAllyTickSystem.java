package org.alex_hashtag.necros;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;

/**
 * ECS tick system that makes released soul NPCs fight nearby non-soul NPCs.
 * <p>
 * Uses a triple approach for maximum reliability:
 * <ol>
 *   <li>Override attitude toward nearby enemies → {@link Attitude#HOSTILE}</li>
 *   <li>Inject closest enemy into {@link TargetMemory#getKnownHostiles()} / {@link TargetMemory#setClosestHostile}</li>
 *   <li>Set closest enemy as the NPC's LockedTarget via {@link MarkedEntitySupport}</li>
 * </ol>
 * Together these ensure the NPC's behavior tree and combat evaluator recognize targets.
 */
public final class SoulAllyTickSystem extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Max range (blocks) to search for targets */
    private static final double TARGET_RANGE = 16.0;
    private static final double TARGET_RANGE_SQ = TARGET_RANGE * TARGET_RANGE;

    /** How long each attitude override lasts (seconds) */
    private static final double HOSTILE_OVERRIDE_DURATION = 30.0;

    /** How long hostiles are remembered in TargetMemory (seconds) */
    private static final float TARGET_REMEMBER_FOR = 30.0f;

    /** How often each soul recalculates target scans (seconds) */
    private static final float TARGET_SCAN_INTERVAL = 0.25f;

    /** If no enemy found, try to stay near summoner when farther than this (blocks) */
    private static final double FOLLOW_OWNER_RANGE = 12.0;
    private static final double FOLLOW_OWNER_RANGE_SQ = FOLLOW_OWNER_RANGE * FOLLOW_OWNER_RANGE;

    private final ConcurrentHashMap<Integer, Float> nextScanAt = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        // Registered at BootEvent time, so component types are available
        return Query.and(NPCEntity.getComponentType(), NetworkId.getComponentType(), TransformComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        NetworkId networkId = archetypeChunk.getComponent(index, NetworkId.getComponentType());
        if (networkId == null) return;

        // Only process released souls
        if (!SoulStorage.get().isReleasedSoul(networkId.getId())) return;

        NPCEntity npcEntity = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
        if (npcEntity == null) return;

        Role role = npcEntity.getRole();
        if (role == null) return;

        // Get this soul's position
        TransformComponent soulTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (soulTransform == null) return;
        Vector3d soulPos = soulTransform.getPosition();

        Ref<EntityStore> soulRef = archetypeChunk.getReferenceTo(index);

        // ── 1. Find nearest non-soul enemy and mark all nearby as hostile ──
        WorldSupport worldSupport = role.getWorldSupport();
        MarkedEntitySupport markedSupport = role.getMarkedEntitySupport();

        Ref<EntityStore> ownerRef = SoulStorage.get().getReleasedSoulOwner(networkId.getId());
        TransformComponent ownerTransform = (ownerRef != null && ownerRef.isValid())
                ? store.getComponent(ownerRef, TransformComponent.getComponentType())
                : null;

        // Keep released souls friendly to their summoner even when using native hostile roles
        if (worldSupport != null && ownerRef != null && ownerRef.isValid()) {
            try {
                worldSupport.overrideAttitude(ownerRef, Attitude.IGNORE, HOSTILE_OVERRIDE_DURATION);
            } catch (Exception ignored) {
            }
        }

        float now = nextScanAt.getOrDefault(networkId.getId(), 0.0f);
        now -= dt;
        if (now > 0.0f) {
            nextScanAt.put(networkId.getId(), now);
            return;
        }
        nextScanAt.put(networkId.getId(), TARGET_SCAN_INTERVAL);

        // ── Priority: if the owner player recently attacked something, target that first ──
        Ref<EntityStore> playerTarget = null;
        if (ownerRef != null && ownerRef.isValid()) {
            PlayerRef ownerPlayerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
            if (ownerPlayerRef != null && ownerPlayerRef.getUuid() != null) {
                Ref<EntityStore> pTarget = SoulStorage.get().getPlayerAttackTarget(ownerPlayerRef.getUuid());
                if (pTarget != null && pTarget.isValid() && !pTarget.equals(soulRef)) {
                    // Verify it's not a released soul (don't attack other souls)
                    NetworkId pTargetNetId = store.getComponent(pTarget, NetworkId.getComponentType());
                    if (pTargetNetId == null || !SoulStorage.get().isReleasedSoul(pTargetNetId.getId())) {
                        playerTarget = pTarget;
                    }
                }
            }
        }

        AtomicReference<Ref<EntityStore>> closestEnemy = new AtomicReference<>(playerTarget);
        final double[] closestDistSq = {playerTarget != null ? 0.0 : TARGET_RANGE_SQ};

        // If the player has a specific target, override attitude toward it
        if (playerTarget != null && worldSupport != null) {
            try {
                worldSupport.overrideAttitude(playerTarget, Attitude.HOSTILE, HOSTILE_OVERRIDE_DURATION);
            } catch (Exception ignored) {
            }
        }

        // Soul_Ally has DefaultPlayerAttitude: "Ignore" and DisableDamageGroups: ["Player"],
        // so souls never target or accept damage from the summoning player.

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cb) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> candidateRef = chunk.getReferenceTo(i);
                if (candidateRef.equals(soulRef)) continue;

                // Skip other released souls — don't infight
                NetworkId candidateNetId = chunk.getComponent(i, NetworkId.getComponentType());
                if (candidateNetId != null && SoulStorage.get().isReleasedSoul(candidateNetId.getId())) continue;

                // Prefer enemies that are generally hostile to players
                NPCEntity candidateNpc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (candidateNpc == null) continue;
                Role candidateRole = candidateNpc.getRole();
                if (candidateRole == null) continue;
                WorldSupport candidateWorldSupport = candidateRole.getWorldSupport();
                if (candidateWorldSupport == null) continue;
                boolean generallyHostile = candidateWorldSupport.getDefaultPlayerAttitude() == Attitude.HOSTILE;
                if (!generallyHostile) continue;

                TransformComponent candidateTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (candidateTransform == null) continue;

                Vector3d candidatePos = candidateTransform.getPosition();
                double dx = candidatePos.x - soulPos.x;
                double dy = candidatePos.y - soulPos.y;
                double dz = candidatePos.z - soulPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                double ownerDistSq = Double.MAX_VALUE;
                if (ownerTransform != null) {
                    Vector3d ownerPos = ownerTransform.getPosition();
                    double odx = candidatePos.x - ownerPos.x;
                    double ody = candidatePos.y - ownerPos.y;
                    double odz = candidatePos.z - ownerPos.z;
                    ownerDistSq = odx * odx + ody * ody + odz * odz;
                }

                if (distSq < TARGET_RANGE_SQ || ownerDistSq < TARGET_RANGE_SQ) {
                    // (A) Override attitude to HOSTILE so CombatTargetCollector picks them up
                    if (worldSupport != null) {
                        try {
                            worldSupport.overrideAttitude(candidateRef, Attitude.HOSTILE, HOSTILE_OVERRIDE_DURATION);
                        } catch (Exception ignored) {
                            // attitudeOverrideMemory may be null for some roles
                        }
                    }

                    // Track closest
                    if (distSq < closestDistSq[0]) {
                        closestDistSq[0] = distSq;
                        closestEnemy.set(candidateRef);
                    }
                }
            }
        });

        Ref<EntityStore> closestEnemyRef = closestEnemy.get();
        if (closestEnemyRef != null) {
            // ── 2. Inject into TargetMemory so the combat evaluator sees a hostile ──
            try {
                TargetMemory targetMemory = store.getComponent(soulRef, TargetMemory.getComponentType());
                if (targetMemory != null) {
                    Int2FloatOpenHashMap hostiles = targetMemory.getKnownHostiles();
                    if (hostiles.put(closestEnemyRef.getIndex(), TARGET_REMEMBER_FOR) <= 0.0f) {
                        targetMemory.getKnownHostilesList().add(closestEnemyRef);
                    }
                    targetMemory.setClosestHostile(closestEnemyRef);
                }
            } catch (Exception e) {
                // TargetMemory may not be present on all NPC types
            }

            // ── 3. Set LockedTarget so the behavior tree has a direct target ──
            if (markedSupport != null) {
                try {
                    markedSupport.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, closestEnemyRef);
                } catch (Exception ignored) {
                }
            }
        }

        // No immediate enemy found: keep ally moving near the summoner
        if (closestEnemyRef == null && markedSupport != null && ownerRef != null && ownerTransform != null) {
            Vector3d ownerPos = ownerTransform.getPosition();
            double ox = ownerPos.x - soulPos.x;
            double oy = ownerPos.y - soulPos.y;
            double oz = ownerPos.z - soulPos.z;
            double ownerDistSq = ox * ox + oy * oy + oz * oz;
            if (ownerDistSq > FOLLOW_OWNER_RANGE_SQ) {
                try {
                    markedSupport.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, ownerRef);
                } catch (Exception ignored) {
                }
            }
        }
    }
}

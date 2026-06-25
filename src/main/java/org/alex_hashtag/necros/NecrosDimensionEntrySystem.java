package org.alex_hashtag.necros;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants players a 5-minute Necrotic Resistance effect when they enter
 * the Necros dimension, allowing them to survive in the Necrotic waters.
 * The effect is applied once per dimension visit; leaving and re-entering
 * will grant it again. A delay ensures the player entity is fully loaded.
 */
public final class NecrosDimensionEntrySystem extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String NECROS_WORLD_PREFIX = "instance-Necros";
    private static final String NECROTIC_RESISTANCE_EFFECT_ID = "Necrotic_Resistance";

    private static final float APPLY_DELAY_SECONDS = 5.0f;

    private final ConcurrentHashMap<UUID, Float> pendingDelay = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> appliedThisVisit = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), EffectControllerComponent.getComponentType(), UUIDComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (uuidComponent == null) return;

        UUID playerUuid = uuidComponent.getUuid();
        if (playerUuid == null) return;

        World world = store.getExternalData().getWorld();
        if (world == null) return;

        String worldName = world.getName();

        if (!worldName.startsWith(NECROS_WORLD_PREFIX)) {
            appliedThisVisit.remove(playerUuid);
            pendingDelay.remove(playerUuid);
            return;
        }

        if (appliedThisVisit.containsKey(playerUuid)) {
            return;
        }

        float elapsed = pendingDelay.getOrDefault(playerUuid, 0.0f) + dt;
        pendingDelay.put(playerUuid, elapsed);

        if (elapsed < APPLY_DELAY_SECONDS) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) return;

        EffectControllerComponent effectController =
                archetypeChunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (effectController == null) return;

        EntityEffect resistance = EntityEffect.getAssetMap().getAsset(NECROTIC_RESISTANCE_EFFECT_ID);
        if (resistance == null) {
            LOGGER.atWarning().log("Necrotic_Resistance effect asset not found in asset map");
            appliedThisVisit.put(playerUuid, Boolean.TRUE);
            pendingDelay.remove(playerUuid);
            return;
        }

        boolean success = effectController.addEffect(playerRef, resistance, store);
        appliedThisVisit.put(playerUuid, Boolean.TRUE);
        pendingDelay.remove(playerUuid);

        if (!success) {
            LOGGER.atWarning().log("addEffect returned false for player %s", playerUuid);
        }
    }
}

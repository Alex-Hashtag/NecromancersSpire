package org.alex_hashtag.necros;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Listens for Damage events on entities with NetworkId.
 * When damage source is a Player, records damaged entity
 * as that player's attack target in {@link SoulStorage}, so soul
 * allies can prioritize it.
 */
public final class PlayerAttackTargetTracker extends EntityEventSystem<EntityStore, Damage> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public PlayerAttackTargetTracker() {
        super(Damage.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return NetworkId.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage event
    ) {
        Damage.Source source = event.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) return;

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (!attackerRef.isValid()) return;

        Player player = store.getComponent(attackerRef, Player.getComponentType());
        if (player == null) return;

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) return;

        // Don't target released souls
        NetworkId targetNetId = archetypeChunk.getComponent(index, NetworkId.getComponentType());
        if (targetNetId != null && SoulStorage.get().isReleasedSoul(targetNetId.getId())) return;

        SoulStorage.get().setPlayerAttackTarget(playerUuid, targetRef);
    }
}

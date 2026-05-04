package org.alex_hashtag.necros;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.event.KillFeedEvent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * ECS event system that captures souls when a player kills a hostile mob while:
 *  - Holding Necrotic Blade in active hand
 *  - Wearing Necrotic Crown in Head armor slot
 *
 * Released souls (tracked via {@link SoulStorage#isReleasedSoul(int)}) are NOT recaptured.
 *
 * Handles {@link KillFeedEvent.KillerMessage} events, dispatched via
 * {@code store.invoke(sourceRef, killerMessageEvent)} on KILLER entity's ref when
 * something dies. Query ensures it only fires when killer is a Player.
 */
public final class SoulCaptureListener extends EntityEventSystem<EntityStore, KillFeedEvent.KillerMessage> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Particle system played at killed mob's position when soul is absorbed */
    private static final String SOUL_ABSORB_PARTICLE = "Effect_Death";

    public SoulCaptureListener() {
        super(KillFeedEvent.KillerMessage.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        // Registered at BootEvent time, so Player.getComponentType() is available
        return Player.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull KillFeedEvent.KillerMessage event
    ) {
        Damage damage = event.getDamage();
        if (damage == null) return;

        Ref<EntityStore> targetRef = event.getTargetRef();
        if (targetRef == null || !targetRef.isValid()) return;

        // Query guarantees this archetype contains Player
        Player killerPlayer = archetypeChunk.getComponent(index, Player.getComponentType());
        if (killerPlayer == null) return;

        UUID playerUuid = killerPlayer.getUuid();
        if (playerUuid == null) return;

        // Check that the killed entity is not a released soul
        try {
            NetworkId targetNetworkId = store.getComponent(targetRef, NetworkId.getComponentType());
            if (targetNetworkId != null && SoulStorage.get().isReleasedSoul(targetNetworkId.getId())) {
                LOGGER.atFine().log("Killed entity %d is a released soul — not recapturing", targetNetworkId.getId());
                return;
            }
        } catch (Exception ignored) {
        }

        // Check if the player is holding the Necrotic Blade
        Inventory inventory = killerPlayer.getInventory();
        if (inventory == null) return;

        ItemStack heldItem = inventory.getItemInHand();
        if (heldItem == null || heldItem.isEmpty()) return;
        if (!SoulStorage.NECROTIC_BLADE_ID.equals(heldItem.getItemId())) return;

        // Check if the player is wearing the Necrotic Crown (Head = slot 0)
        ItemContainer armorContainer = inventory.getArmor();
        if (armorContainer == null) return;

        ItemStack headSlot = armorContainer.getItemStack((short) 0);
        if (headSlot == null || headSlot.isEmpty()) return;
        if (!SoulStorage.NECROTIC_CROWN_ID.equals(headSlot.getItemId())) return;

        // Get the killed entity's NPC role + appearance — only capture NPC souls
        String npcRoleName;
        String appearanceName;
        try {
            NPCEntity targetNpc = store.getComponent(targetRef, NPCEntity.getComponentType());
            if (targetNpc == null) return;
            npcRoleName = targetNpc.getRoleName();
            if (npcRoleName == null || npcRoleName.isEmpty()) return;

            // Capture the mob's appearance (model) name so the generic Soul_Ally
            // can be reskinned to look like the killed mob on release.
            String appearance = null;
            try {
                if (targetNpc.getRole() != null) {
                    appearance = targetNpc.getRole().getAppearanceName();
                }
            } catch (Exception ignored) {
            }
            appearanceName = (appearance == null || appearance.isEmpty()) ? npcRoleName : appearance;
        } catch (Exception e) {
            return;
        }

        // All conditions met — capture a soul!
        int totalSouls = SoulStorage.get().addSoul(playerUuid, npcRoleName, appearanceName);
        if (totalSouls > 0) {
            // Spawn absorption particle at the killed mob's position
            spawnAbsorptionEffect(targetRef, store);

            killerPlayer.sendMessage(
                    Message.translation("server.necros.soul_captured")
                            .param("mob", npcRoleName)
                            .param("count", totalSouls)
                            .param("max", SoulStorage.MAX_SOULS)
                            .color("#aa00aa")
                            .bold(true)
            );
        } else {
            killerPlayer.sendMessage(
                    Message.translation("server.necros.crown_full").color("#888888")
            );
        }
    }

    /**
     * Spawns a teal soul-absorption particle effect at the killed entity's position.
     */
    private void spawnAbsorptionEffect(@Nonnull Ref<EntityStore> targetRef, @Nonnull Store<EntityStore> store) {
        try {
            TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
            if (transform == null) return;

            Vector3d pos = transform.getPosition();
            // Offset upward slightly so the effect plays at body height
            Vector3d effectPos = new Vector3d(pos.getX(), pos.getY() + 0.5, pos.getZ());
            // Spatial lookup finds nearby players and sends them the particle packet
            ParticleUtil.spawnParticleEffect(SOUL_ABSORB_PARTICLE, effectPos, store);
        } catch (Exception e) {
            LOGGER.atFine().withCause(e).log("Could not spawn absorption particle");
        }
    }
}

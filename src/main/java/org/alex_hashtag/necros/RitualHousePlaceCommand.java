package org.alex_hashtag.necros;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class RitualHousePlaceCommand extends CommandBase {
    public RitualHousePlaceCommand() {
        super("ritualhouse_place", "Debug command: place ritual house prefab near you.");
        this.setPermissionGroups("hytale:WorldEditor");
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("This command can only be used by a player.").color("#ff5555"));
            return;
        }

        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> senderStore = playerRef.getStore();
        if (senderStore == null) {
            return;
        }

        World world = senderStore.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        world.execute(() -> {
            Store<EntityStore> worldStore = world.getEntityStore().getStore();
            TransformComponent transform = worldStore.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null) {
                return;
            }

            Vector3d pos = transform.getPosition();
            Vector3i center = new Vector3i((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));

            boolean placed = RitualHousePlacerSystem.tryPlaceNear(world, center, 60, 60);
            if (placed) {
                ctx.sendMessage(Message.raw("Ritual house placed.").color("#7fd9c8"));
            } else {
                ctx.sendMessage(Message.raw("Could not place ritual house nearby. Try moving and run again.").color("#ff5555"));
            }
        });
    }
}

package org.alex_hashtag.necros;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Command to teleport players to the Necros dimension.
 * Use: /necros
 * If in normal world, teleports to Necros instance.
 * If already in Necros instance, returns to normal world.
 */
public class NecrosCommand extends AbstractPlayerCommand {

    private static final String INSTANCE_NAME = "Necros";
    private static final String WORLD_NAME = "instance-necros";

    public NecrosCommand() {
        super("necros", "Teleports you to/from the Necros dimension.");
        this.setPermissionGroup(GameMode.Creative);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        String currentWorldName = world.getName();

        // If player is already in Necros dimension, send them back
        if (WORLD_NAME.equals(currentWorldName)) {
            context.sendMessage(Message.raw("Returning to the overworld..."));
            try {
                InstancesPlugin.exitInstance(ref, store);
            } catch (IllegalArgumentException ex) {
                // No return point stored (e.g. player persisted inside instance
                // across restarts). Fall back to server's default world spawn.
                World defaultWorld = Universe.get().getDefaultWorld();
                if (defaultWorld == null) {
                    context.sendMessage(Message.raw("No default world available to return to."));
                    return;
                }
                ISpawnProvider spawnProvider = defaultWorld.getWorldConfig().getSpawnProvider();
                Transform spawn = spawnProvider != null
                        ? spawnProvider.getSpawnPoint(defaultWorld, playerRef.getUuid())
                        : new Transform(new Vector3d(0, 100, 0));
                store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(defaultWorld, spawn));
            }
            return;
        }

        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        assert transformComponent != null;
        Vector3d position = transformComponent.getPosition();
        Transform returnLocation = new Transform(position.clone());

        // Check if Necros world already exists and reuse it
        World existingNecros = Universe.get().getWorld(WORLD_NAME);
        if (existingNecros != null && existingNecros.isAlive()) {
            context.sendMessage(Message.raw("Opening portal to the Necros dimension..."));
            InstancesPlugin.teleportPlayerToInstance(ref, store, existingNecros, returnLocation);
        } else {
            context.sendMessage(Message.raw("Opening portal to the Necros dimension..."));
            CompletableFuture<World> instanceWorld = InstancesPlugin.get()
                    .spawnInstance(INSTANCE_NAME, WORLD_NAME, world, returnLocation);
            InstancesPlugin.teleportPlayerToLoadingInstance(ref, store, instanceWorld, returnLocation);
        }
    }
}

package org.alex_hashtag.necros;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Test command to release captured souls without needing Ability1 charge.
 * <p>
 * Usage: /release_souls
 * <p>
 * Requires holding Necrotic Blade and wearing Necrotic Crown.
 * Delegates spawning logic to {@link SoulReleaseSystem}.
 */
public final class SoulReleaseCommand extends CommandBase {

    public SoulReleaseCommand() {
        super("release_souls", "Test command: releases captured souls from the Necrotic Crown.");
        this.setPermissionGroups("hytale:WorldEditor");
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.translation("server.necros.command.player_only").color("#ff5555"));
            return;
        }

        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) return;

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) return;

        // Reuse shared soul-release logic
        SoulReleaseSystem.releaseSouls(store, playerRef);
    }
}

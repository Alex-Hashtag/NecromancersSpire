package org.alex_hashtag.necros;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

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
        this.setPermissionGroup(GameMode.Creative);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.translation("server.necros.command.player_only").color("#ff5555"));
            return;
        }

        Player player = ctx.senderAs(Player.class);
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) return;

        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) return;

        // Reuse shared soul-release logic
        SoulReleaseSystem.releaseSouls(player, playerUuid, playerRef);
    }
}

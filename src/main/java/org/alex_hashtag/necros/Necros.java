package org.alex_hashtag.necros;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;

public class Necros extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public Necros(JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        // Initialize soul storage with persistence
        SoulStorage.get().init(this.getDataDirectory());
        RitualBrazierStorage.get().init(this.getDataDirectory());

        this.getCommandRegistry().registerCommand(new NecrosCommand());

        // Test command: /release_souls (bypasses charge requirement)
        this.getCommandRegistry().registerCommand(new SoulReleaseCommand());
        LOGGER.atInfo().log("Registered /release_souls test command");

        this.getCommandRegistry().registerCommand(new RitualHousePlaceCommand());
        LOGGER.atInfo().log("Registered /ritualhouse_place debug command");

        // ECS systems registered at boot time
        this.getEventRegistry().register(BootEvent.class, event -> {
            // Soul release: monitors SignatureEnergy drops
            this.getEntityStoreRegistry().registerSystem(new SoulReleaseSystem());
            LOGGER.atInfo().log("Registered SoulReleaseSystem (SignatureEnergy tick monitor)");

            // Soul capture: handles KillFeedEvent.KillerMessage
            this.getEntityStoreRegistry().registerSystem(new SoulCaptureListener());
            LOGGER.atInfo().log("Registered SoulCaptureListener (ECS event system)");

            // Player attack target tracking
            this.getEntityStoreRegistry().registerSystem(new PlayerAttackTargetTracker());
            LOGGER.atInfo().log("Registered PlayerAttackTargetTracker (player damage target)");

            // Soul ally AI: attitude override + target injection
            this.getEntityStoreRegistry().registerSystem(new SoulAllyTickSystem());
            LOGGER.atInfo().log("Registered SoulAllyTickSystem (soul NPC targeting)");

            // Ritual brazier: cost setup + ritual exchange
            this.getEntityStoreRegistry().registerSystem(new RitualBrazierSystem());
            LOGGER.atInfo().log("Registered RitualBrazierSystem (brazier ritual flow)");

            // Ritual brazier: cleanup on block break
            this.getEntityStoreRegistry().registerSystem(new RitualBrazierBreakSystem());
            LOGGER.atInfo().log("Registered RitualBrazierBreakSystem (brazier break cleanup)");

            // Ritual brazier sequence: burn/reward/hologram flow
            this.getEntityStoreRegistry().registerSystem(new RitualBrazierSequenceSystem());
            LOGGER.atInfo().log("Registered RitualBrazierSequenceSystem (ritual sequence tick)");

            // Runtime world placement: ritual house prefab placement
            this.getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class, RitualHousePlacerSystem::onChunkLoad);
            LOGGER.atInfo().log("Registered RitualHousePlacerSystem (chunk-load prefab placement)");

            // Stone pedestal: sword-in-stone pull + throne crown claim
            this.getEntityStoreRegistry().registerSystem(new StonePedestalInteractionSystem());
            LOGGER.atInfo().log("Registered StonePedestalInteractionSystem (pedestal interactions)");
        });

        // Save soul counts on shutdown
        this.getEventRegistry().register(ShutdownEvent.class, event -> {
            SoulStorage.get().save();
            RitualBrazierStorage.get().save();
        });
    }
}

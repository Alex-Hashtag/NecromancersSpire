package org.alex_hashtag.necros;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages captured souls per-player. Each soul remembers the NPC role name and appearance
 * of the killed mob so it can be respawned as the same creature type.
 * <p>
 * Thread-safe: uses ConcurrentHashMap with synchronized lists.
 * Soul data persists to disk and survives server restarts.
 */
public final class SoulStorage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SAVE_FILE_NAME = "soul_data.properties";

    public static final int MAX_SOULS = 64;
    public static final int MIN_RELEASE = 3;
    public static final int MAX_RELEASE = 10;
    public static final String NECROTIC_CROWN_ID = "Necrotic_Crown";
    public static final String NECROTIC_BLADE_ID = "Necrotic_Blade";

    private static final SoulStorage INSTANCE = new SoulStorage();

    /**
     * One captured soul: role name (display/logging) and appearance name
     * (applied to generic Soul_Ally on release).
     */
    public record Soul(@Nonnull String roleName, @Nonnull String appearanceName) {
        @Nonnull
        public String encode() {
            return roleName + "|" + appearanceName;
        }

        @Nonnull
        public static Soul decode(@Nonnull String s) {
            int pipe = s.indexOf('|');
            if (pipe < 0) {
                // Legacy format: role name only; use role name as appearance as best effort
                return new Soul(s, s);
            }
            return new Soul(s.substring(0, pipe), s.substring(pipe + 1));
        }
    }

    /** Per-player list of captured souls. */
    private final ConcurrentHashMap<UUID, List<Soul>> soulsByPlayer = new ConcurrentHashMap<>();

    /** Network IDs of released souls — killing these must NOT re-trap them. */
    private final Set<Integer> releasedSoulNetworkIds = ConcurrentHashMap.newKeySet();

    /** Last entity each player attacked — soul allies prioritize this target. */
    private final ConcurrentHashMap<UUID, Ref<EntityStore>> playerAttackTargets = new ConcurrentHashMap<>();

    /** Owner player refs for released souls (networkId -> owner player ref). */
    private final ConcurrentHashMap<Integer, Ref<EntityStore>> releasedSoulOwners = new ConcurrentHashMap<>();

    private Path dataDirectory;

    private SoulStorage() {}

    @Nonnull
    public static SoulStorage get() {
        return INSTANCE;
    }

    /**
     * Initialize with plugin's data directory and load persisted soul data.
     */
    public void init(@Nonnull Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        load();
    }

    /**
     * Adds one soul for a player, storing the killed mob's NPC role name and appearance name.
     * @return the new total, or -1 if already at cap
     */
    public int addSoul(@Nonnull UUID playerUuid, @Nonnull String npcRoleName, @Nonnull String appearanceName) {
        List<Soul> souls = soulsByPlayer.computeIfAbsent(playerUuid, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (souls) {
            if (souls.size() >= MAX_SOULS) return -1;
            souls.add(new Soul(npcRoleName, appearanceName));
            int total = souls.size();
            LOGGER.atInfo().log("Soul captured for %s (mob: %s, appearance: %s) — total: %d",
                    playerUuid, npcRoleName, appearanceName, total);
            save();
            return total;
        }
    }

    /**
     * Consumes a random number of souls between {@link #MIN_RELEASE} and {@link #MAX_RELEASE}.
     * @return a list of souls to spawn, or empty list if not enough souls
     */
    @Nonnull
    public List<Soul> consumeSouls(@Nonnull UUID playerUuid) {
        List<Soul> souls = soulsByPlayer.get(playerUuid);
        if (souls == null) return Collections.emptyList();

        synchronized (souls) {
            if (souls.isEmpty()) return Collections.emptyList();

            // If the crown holds fewer than MIN_RELEASE souls, release all of them;
            // otherwise release a random 3..MAX_RELEASE.
            int toRelease;
            if (souls.size() < MIN_RELEASE) {
                toRelease = souls.size();
            } else {
                toRelease = MIN_RELEASE + ThreadLocalRandom.current().nextInt(Math.min(souls.size(), MAX_RELEASE) - MIN_RELEASE + 1);
                toRelease = Math.min(toRelease, souls.size());
            }

            List<Soul> released = new ArrayList<>(toRelease);
            for (int i = 0; i < toRelease; i++) {
                released.add(souls.remove(souls.size() - 1));
            }

            LOGGER.atInfo().log("Releasing %d souls for %s — remaining: %d", toRelease, playerUuid, souls.size());
            save();
            return released;
        }
    }

    /**
     * Samples souls for temporary release without removing them from storage.
     * Returns min..max souls if available, otherwise returns all when stored souls < min.
     */
    @Nonnull
    public List<Soul> sampleSouls(@Nonnull UUID playerUuid, int min, int max) {
        List<Soul> souls = soulsByPlayer.get(playerUuid);
        if (souls == null || souls.isEmpty()) return Collections.emptyList();

        synchronized (souls) {
            int available = souls.size();
            int toRelease;
            if (available < min) {
                toRelease = available;
            } else {
                int span = Math.min(available, max) - min + 1;
                toRelease = min + ThreadLocalRandom.current().nextInt(span);
            }

            int start = Math.max(0, souls.size() - toRelease);
            return new ArrayList<>(souls.subList(start, souls.size()));
        }
    }

    public int getSoulCount(@Nonnull UUID playerUuid) {
        List<Soul> souls = soulsByPlayer.get(playerUuid);
        return souls != null ? souls.size() : 0;
    }

    /** Mark an entity network ID as a released soul so it cannot be recaptured. */
    public void markAsReleasedSoul(int networkId) {
        releasedSoulNetworkIds.add(networkId);
    }

    /** Mark an entity network ID as a released soul and remember the summoning owner. */
    public void markAsReleasedSoul(int networkId, @Nonnull Ref<EntityStore> ownerRef) {
        releasedSoulNetworkIds.add(networkId);
        releasedSoulOwners.put(networkId, ownerRef);
    }

    /** Unmark a released soul (e.g. when it despawns or is removed). */
    public void unmarkReleasedSoul(int networkId) {
        releasedSoulNetworkIds.remove(networkId);
        releasedSoulOwners.remove(networkId);
    }

    /** Check if an entity is a released soul (should not be recaptured). */
    public boolean isReleasedSoul(int networkId) {
        return releasedSoulNetworkIds.contains(networkId);
    }

    /** Returns the owning player ref for a released soul, if known. */
    @Nullable
    public Ref<EntityStore> getReleasedSoulOwner(int networkId) {
        return releasedSoulOwners.get(networkId);
    }

    /** Record the last entity a player attacked so soul allies can target it. */
    public void setPlayerAttackTarget(@Nonnull UUID playerUuid, @Nonnull Ref<EntityStore> targetRef) {
        playerAttackTargets.put(playerUuid, targetRef);
    }

    /** Get the last entity a player attacked. May be null or invalid. */
    @Nullable
    public Ref<EntityStore> getPlayerAttackTarget(@Nonnull UUID playerUuid) {
        return playerAttackTargets.get(playerUuid);
    }

    /** Clear stale attack targets (e.g. when the ref is no longer valid). */
    public void clearPlayerAttackTarget(@Nonnull UUID playerUuid) {
        playerAttackTargets.remove(playerUuid);
    }

    /**
     * Persists all soul data to disk.
     * Format per entry: playerUUID = role1,role2,role3,...
     */
    public void save() {
        if (dataDirectory == null) return;
        try {
            Files.createDirectories(dataDirectory);
            Path savePath = dataDirectory.resolve(SAVE_FILE_NAME);
            Properties props = new Properties();
            for (var entry : soulsByPlayer.entrySet()) {
                List<Soul> souls = entry.getValue();
                synchronized (souls) {
                    if (!souls.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < souls.size(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append(souls.get(i).encode());
                        }
                        props.setProperty(entry.getKey().toString(), sb.toString());
                    }
                }
            }
            try (Writer writer = Files.newBufferedWriter(savePath)) {
                props.store(writer, "Necrotic Crown soul data: UUID = comma-separated role|appearance pairs");
            }
            LOGGER.atInfo().log("Saved soul data for %d players to %s", props.size(), savePath);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to save soul data");
        }
    }

    /**
     * Loads persisted soul data from disk.
     */
    private void load() {
        if (dataDirectory == null) return;
        Path savePath = dataDirectory.resolve(SAVE_FILE_NAME);
        if (!Files.exists(savePath)) return;
        try (Reader reader = Files.newBufferedReader(savePath)) {
            Properties props = new Properties();
            props.load(reader);
            for (String key : props.stringPropertyNames()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String value = props.getProperty(key);
                    if (value != null && !value.isEmpty()) {
                        String[] entries = value.split(",");
                        List<Soul> souls = Collections.synchronizedList(new ArrayList<>());
                        for (String entry : entries) {
                            String trimmed = entry.trim();
                            if (!trimmed.isEmpty() && souls.size() < MAX_SOULS) {
                                souls.add(Soul.decode(trimmed));
                            }
                        }
                        if (!souls.isEmpty()) {
                            soulsByPlayer.put(uuid, souls);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            LOGGER.atInfo().log("Loaded soul data for %d players from %s", soulsByPlayer.size(), savePath);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to load soul data");
        }
    }
}

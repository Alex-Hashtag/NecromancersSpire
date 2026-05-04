package org.alex_hashtag.necros;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class RitualBrazierStorage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SAVE_FILE_NAME = "ritual_brazier_costs.properties";
    private static final String SAVE_REWARD_FILE_NAME = "ritual_brazier_rewards.properties";
    private static final RitualBrazierStorage INSTANCE = new RitualBrazierStorage();

    private final ConcurrentHashMap<String, String> ritualCosts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> ritualRewards = new ConcurrentHashMap<>();
    private Path dataDirectory;

    private RitualBrazierStorage() {
    }

    @Nonnull
    public static RitualBrazierStorage get() {
        return INSTANCE;
    }

    public void init(@Nonnull Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        load();
    }

    @Nullable
    public String getCost(@Nonnull String key) {
        return ritualCosts.get(key);
    }

    public void setCost(@Nonnull String key, @Nonnull String itemId) {
        ritualCosts.put(key, itemId);
    }

    public void removeCost(@Nonnull String key) {
        ritualCosts.remove(key);
    }

    @Nullable
    public String getReward(@Nonnull String key) {
        return ritualRewards.get(key);
    }

    public void setReward(@Nonnull String key, @Nonnull String itemId) {
        ritualRewards.put(key, itemId);
    }

    public void removeReward(@Nonnull String key) {
        ritualRewards.remove(key);
    }

    @Nonnull
    public Map<String, String> getAllCosts() {
        return Map.copyOf(ritualCosts);
    }

    @Nonnull
    public Map<String, String> getAllRewards() {
        return Map.copyOf(ritualRewards);
    }

    public void save() {
        if (dataDirectory == null) {
            return;
        }

        try {
            Files.createDirectories(dataDirectory);
            Path savePath = dataDirectory.resolve(SAVE_FILE_NAME);
            Properties props = new Properties();
            for (Map.Entry<String, String> entry : ritualCosts.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue());
            }

            try (Writer writer = Files.newBufferedWriter(savePath)) {
                props.store(writer, "Ritual brazier costs: world:x:y:z=itemId");
            }

            // Save rewards in a separate file for backward compatibility
            Path rewardPath = dataDirectory.resolve(SAVE_REWARD_FILE_NAME);
            Properties rewardProps = new Properties();
            for (Map.Entry<String, String> entry : ritualRewards.entrySet()) {
                rewardProps.setProperty(entry.getKey(), entry.getValue());
            }
            try (Writer writer = Files.newBufferedWriter(rewardPath)) {
                rewardProps.store(writer, "Ritual brazier rewards: world:x:y:z=itemId");
            }

            LOGGER.atInfo().log("Saved %d ritual brazier costs to %s and %d rewards to %s",
                    props.size(), savePath, rewardProps.size(), rewardPath);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to save ritual brazier costs");
        }
    }

    private void load() {
        if (dataDirectory == null) {
            return;
        }

        Path savePath = dataDirectory.resolve(SAVE_FILE_NAME);
        if (!Files.exists(savePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(savePath)) {
            Properties props = new Properties();
            props.load(reader);

            ritualCosts.clear();
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value != null && !value.isBlank()) {
                    ritualCosts.put(key, value);
                }
            }

            // Load rewards if present (backward compatible)
            ritualRewards.clear();
            Path rewardPath = dataDirectory.resolve(SAVE_REWARD_FILE_NAME);
            if (Files.exists(rewardPath)) {
                try (Reader r2 = Files.newBufferedReader(rewardPath)) {
                    Properties rewardProps = new Properties();
                    rewardProps.load(r2);
                    for (String key : rewardProps.stringPropertyNames()) {
                        String value = rewardProps.getProperty(key);
                        if (value != null && !value.isBlank()) {
                            ritualRewards.put(key, value);
                        }
                    }
                }
            }

            LOGGER.atInfo().log("Loaded %d ritual brazier costs from %s and %d rewards from %s",
                    ritualCosts.size(), savePath, ritualRewards.size(), rewardPath);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to load ritual brazier costs");
        }
    }
}

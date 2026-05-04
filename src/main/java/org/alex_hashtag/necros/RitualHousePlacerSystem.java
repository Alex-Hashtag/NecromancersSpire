package org.alex_hashtag.necros;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.worldgen.HytaleWorldGenProvider;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class RitualHousePlacerSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PREFAB_RELATIVE_PATH = "Server/World/Default/Prefabs/Custom/Ritual_House/Custom_Ritual_House_001.prefab.json";
    private static final int PLACE_CHANCE = 250;
    private static final int SEARCH_RADIUS = 80;
    private static final int ATTEMPTS_PER_CHUNK = 10;
    private static final Set<String> VALID_FLOOR_BLOCKS = Set.of(
            "Soil_Grass",
            "Soil_Grass_Burnt",
            "Soil_Grass_Full",
            "Soil_Grass_Sunny",
            "Soil_Grass_Deep"
    );

    private static final Random random = new Random();

    private RitualHousePlacerSystem() {}

    public static void onChunkLoad(@Nonnull ChunkPreLoadProcessEvent event) {
        if (!event.isNewlyGenerated()) {
            return;
        }

        World world = event.getChunk().getWorld();
        if (world == null || !(world.getWorldConfig().getWorldGenProvider() instanceof HytaleWorldGenProvider)) {
            return;
        }

        if (random.nextInt(PLACE_CHANCE) != 0) {
            return;
        }

        WorldChunk chunk = event.getChunk();
        int chunkX = ChunkUtil.minBlock(chunk.getX());
        int chunkZ = ChunkUtil.minBlock(chunk.getZ());
        Vector3i center = new Vector3i(chunkX + 8, 0, chunkZ + 8);

        try {
            world.execute(() -> tryPlaceNear(world, center, ATTEMPTS_PER_CHUNK, SEARCH_RADIUS));
        } catch (IllegalThreadStateException ignored) {
        }
    }

    public static boolean tryPlaceNear(@Nonnull World world, @Nonnull Vector3i center, int attempts) {
        return tryPlaceNear(world, center, attempts, SEARCH_RADIUS);
    }

    public static boolean tryPlaceNear(@Nonnull World world, @Nonnull Vector3i center, int attempts, int radius) {
        Path prefabPath = findPrefabPath();
        if (prefabPath == null) {
            LOGGER.atWarning().log("[RitualHouse] Prefab file not found in any asset pack for: %s", PREFAB_RELATIVE_PATH);
            return false;
        }

        PrefabBuffer prefabBuffer;
        try {
            prefabBuffer = PrefabBufferUtil.loadBuffer(prefabPath);
        } catch (Throwable e) {
            LOGGER.atWarning().withCause(e).log("[RitualHouse] Failed loading prefab from %s", prefabPath);
            return false;
        }

        LOGGER.atInfo().log("[RitualHouse] Attempting placement near %s, attempts=%d, radius=%d, world=%s",
                center, attempts, radius, world.getName());

        int noSurfaceCount = 0;
        for (int i = 0; i < attempts; i++) {
            int x = center.x + (random.nextInt((radius * 2) + 1) - radius);
            int z = center.z + (random.nextInt((radius * 2) + 1) - radius);

            int y = findSurfaceY(world, x, z);
            if (y < 0) {
                noSurfaceCount++;
                continue;
            }

            Vector3i placement = new Vector3i(x, y, z);
            PrefabBuffer.PrefabBufferAccessor accessor = prefabBuffer.newAccess();
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                PrefabUtil.paste(accessor, world, placement, Rotation.None, true, random, store);
                LOGGER.atInfo().log("[RitualHouse] Placed ritual house prefab at %s in world %s", placement, world.getName());
                return true;
            } catch (Throwable e) {
                LOGGER.atWarning().withCause(e).log("[RitualHouse] Paste failed at %s", placement);
            } finally {
                accessor.release();
            }
        }

        LOGGER.atInfo().log(
                "[RitualHouse] No valid placement near %s in world %s after %d attempts (noSurface=%d)",
                center, world.getName(), attempts, noSurfaceCount
        );
        return false;
    }

    @Nullable
    private static Path findPrefabPath() {
        List<AssetPack> packs = AssetModule.get().getAssetPacks();
        for (AssetPack pack : packs) {
            Path resolved = pack.getRoot().resolve(PREFAB_RELATIVE_PATH);
            if (Files.exists(resolved)) {
                LOGGER.atInfo().log("[RitualHouse] Found prefab in pack '%s' at %s", pack.getName(), resolved);
                return resolved;
            }
        }
        LOGGER.atWarning().log("[RitualHouse] Searched %d asset packs, none contain %s", packs.size(), PREFAB_RELATIVE_PATH);
        return null;
    }

    private static int findSurfaceY(@Nonnull World world, int x, int z) {
        Vector3i cursor = new Vector3i(x, 0, z);
        for (int y = 319; y >= 8; y--) {
            cursor.y = y;
            BlockType floor = world.getBlockType(cursor);
            if (floor == null) {
                continue;
            }

            if (!VALID_FLOOR_BLOCKS.contains(floor.getId())) {
                continue;
            }

            cursor.y = y + 1;
            BlockType above = world.getBlockType(cursor);
            if (above != null && "Empty".equals(above.getId())) {
                return y + 1;
            }
        }
        return -1;
    }
}

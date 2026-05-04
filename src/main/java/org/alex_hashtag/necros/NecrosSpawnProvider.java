package org.alex_hashtag.necros;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Custom spawn provider for the Necros dimension.
 * Players spawn in water/rivers (Y=100 area, central river).
 * NPCs spawn on actual surface (scanned upward from Y=140).
 */
public class NecrosSpawnProvider implements ISpawnProvider {

    private static final int SEARCH_START_Y = 200;
    private static final int SEARCH_MIN_Y = 100;
    private static final double PLAYER_SPAWN_Y = 100.5;

    @Nonnull
    @Override
    public Transform getSpawnPoint(@Nonnull World world, @Nonnull UUID playerUuid) {
        // Players spawn in water/river at Y=100 (sea level)
        Vector3d spawnPos = new Vector3d(0.5, PLAYER_SPAWN_Y, 0.5);
        return new Transform(spawnPos);
    }

    @Override
    public Transform[] getSpawnPoints() {
         // Legacy API — return default player spawn point
        return new Transform[] { new Transform(new Vector3d(0.5, PLAYER_SPAWN_Y, 0.5)) };
    }

    @Override
    public boolean isWithinSpawnDistance(@Nonnull Vector3d position, double distance) {
        // Accept any position within given horizontal distance from origin
        double dx = position.getX();
        double dz = position.getZ();
        double distSq = dx * dx + dz * dz;
        return distSq <= distance * distance;
    }

    /**
     * Scans downward from SEARCH_START_Y to find highest solid block,
     * returning Y coordinate above it (safe spawn height for NPCs).
     * World.getBlock(x, y, z) returns 0 for air/fluid; non-zero = solid block id.
     */
    public int findSurfaceHeight(@Nonnull World world, int x, int z) {
        for (int y = SEARCH_START_Y; y >= SEARCH_MIN_Y; y--) {
            int blockId = world.getBlock(x, y, z);
            if (blockId != 0) {
                return y + 1;
            }
        }
        return SEARCH_MIN_Y;
    }
}

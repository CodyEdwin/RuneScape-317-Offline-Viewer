import java.util.ArrayList;
import java.util.List;

/**
 * Owns the 104x104 scene window: the shared ground/height buffers, the
 * WorldController that renders it and the ObjectManager that loads it.
 *
 * The window is rebuilt around the camera whenever it drifts too close to the
 * edge. Every region overlapping the window is loaded at its correct offset,
 * with terrain noise keyed on absolute world coordinates so boundaries join
 * seamlessly.
 */
final class WorldScene {

    // The engine's WorldController is hardcoded to a 104x104 tile grid.
    static final int SIZE = 104;

    byte[][][] groundArray;
    int[][][] intGround;
    Class11[] tiles;
    WorldController controller;
    ObjectManager objectManager;

    // World tile coordinate of scene tile (0,0). The window covers
    // [baseX, baseX+104) x [baseY, baseY+104).
    int baseX = 0;
    int baseY = 0;
    boolean initialized = false;
    boolean forceRebuild = false;

    // Region containing the camera (and its index for navigation).
    MapRegion currentRegion;
    int regionIndex = 0;
    int objectCount = 0;

    // Scene-tile bounds that actually have terrain loaded, so the aim ray and
    // ghost never treat the unloaded (height 0) void as ground.
    int loadMinTX = 0, loadMaxTX = SIZE - 1, loadMinTY = 0, loadMaxTY = SIZE - 1;

    /** Allocates the scene buffers and the controller. */
    void init() {
        groundArray = new byte[4][SIZE][SIZE];
        intGround = new int[4][SIZE + 1][SIZE + 1];
        objectManager = new ObjectManager(groundArray, intGround);
        tiles = new Class11[4];
        for (int i = 0; i < 4; i++) {
            tiles[i] = new Class11();
        }
        controller = new WorldController(intGround);
        controller.method275(0);
    }

    /** Terrain height (scene units) at a scene-local point. */
    int terrainHeight(int x, int y) {
        return controller == null ? 0 : controller.terrainHeightAt(0, x, y);
    }

    /** Returns true if the tile at scene coordinates (x,y) has collision enabled.
     *  The clip array is zeroed by the ::noclip command, so any non-zero
     *  value means the tile is blocked. Scene x/y are 0..103 relative to the
     *  scene window origin. */
    boolean isBlocked(int x, int y) {
        if (tiles == null || tiles.length == 0 || tiles[0] == null) return true;
        int idxX = Math.max(0, Math.min(103, x));
        int idxY = Math.max(0, Math.min(103, y));
        return tiles[0].anIntArrayArray294[idxX][idxY] != 0;
    }

    /**
     * Rebuilds the window when the camera is too close to the edge, then
     * updates which region the camera is in.
     */
    void ensureAround(Camera cam, ViewerConfig cfg, RegionRepository repo, List<int[]> placed) {
        if (controller == null) return;

        int wtx = cam.worldX >> 7;
        int wty = cam.worldY >> 7;
        int sx = wtx - baseX;
        int sy = wty - baseY;
        int low = cfg.viewRadius + 1;
        int high = SIZE - 1 - cfg.viewRadius;
        boolean need = forceRebuild || !initialized
                || sx < low || sx > high || sy < low || sy > high;
        if (need) {
            // Place the camera exactly on SCENE_CENTER so we maximise how far
            // it can travel before the window has to be rebuilt.
            rebuild(wtx - ViewerConfig.SCENE_CENTER, wty - ViewerConfig.SCENE_CENTER, repo, placed);
            forceRebuild = false;
        }
        updateCurrentRegion(cam, repo);
    }

    private void updateCurrentRegion(Camera cam, RegionRepository repo) {
        MapRegion cur = repo.get((cam.worldX >> 7) >> 6, (cam.worldY >> 7) >> 6);
        if (cur != null && cur != currentRegion) {
            currentRegion = cur;
            int idx = repo.indexOf(cur);
            if (idx >= 0) regionIndex = idx;
        }
    }

    /** Rebuilds the whole 104x104 window around the given world-tile origin. */
    private void rebuild(int newBaseX, int newBaseY, RegionRepository repo, List<int[]> placed) {
        long started = System.nanoTime();

        // Reset shared buffers.
        for (int p = 0; p < 4; p++)
            for (int x = 0; x < SIZE; x++)
                for (int y = 0; y < SIZE; y++)
                    groundArray[p][x][y] = 0;
        for (int p = 0; p < 4; p++)
            for (int x = 0; x <= SIZE; x++)
                for (int y = 0; y <= SIZE; y++)
                    intGround[p][x][y] = 0;
        for (int i = 0; i < 4; i++)
            tiles[i].method210();
        controller.initToNull();
        objectManager = new ObjectManager(groundArray, intGround);

        int rx0 = newBaseX >> 6;
        int rx1 = (newBaseX + SIZE - 1) >> 6;
        int ry0 = newBaseY >> 6;
        int ry1 = (newBaseY + SIZE - 1) >> 6;

        List<MapRegion> loaded = new ArrayList<>();
        for (int ry = ry0; ry <= ry1; ry++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                MapRegion re = repo.get(rx, ry);
                if (re != null) loaded.add(re);
            }
        }

        // Record the scene tiles that actually have terrain.
        loadMinTX = SIZE - 1; loadMaxTX = 0; loadMinTY = SIZE - 1; loadMaxTY = 0;
        for (MapRegion re : loaded) {
            int ox = re.rx * 64 - newBaseX;
            int oy = re.ry * 64 - newBaseY;
            if (ox < loadMinTX) loadMinTX = ox;
            if (ox + 63 > loadMaxTX) loadMaxTX = ox + 63;
            if (oy < loadMinTY) loadMinTY = oy;
            if (oy + 63 > loadMaxTY) loadMaxTY = oy + 63;
        }
        if (loadMinTX > loadMaxTX) {
            loadMinTX = 0; loadMaxTX = SIZE - 1; loadMinTY = 0; loadMaxTY = SIZE - 1;
        }

        // Terrain first (all regions), then objects, then finalisation -
        // matching the order the real client uses in method22.
        for (MapRegion re : loaded) {
            byte[] gb = repo.bytes(re.groundFileId);
            if (gb == null) continue;
            int xOff = re.rx * 64 - newBaseX;
            int yOff = re.ry * 64 - newBaseY;
            try {
                // method180(data, yOffset, xOffset, baseX, baseY, tiles): the
                // height noise is a function of absolute world coords, so
                // adjacent regions line up at their shared boundary.
                objectManager.method180(gb, yOff, xOff, newBaseX, newBaseY, tiles);
            } catch (Exception e) {
                System.out.println("[Viewer] Skipped corrupt terrain in region " + re + ": " + e.getMessage());
            }
        }

        int objTotal = 0;
        for (MapRegion re : loaded) {
            byte[] ob = repo.bytes(re.objectFileId);
            if (ob == null) continue;
            objTotal += countObjects(ob);
            int xOff = re.rx * 64 - newBaseX;
            int yOff = re.ry * 64 - newBaseY;
            try {
                // method190(xOffset, tiles, yOffset, controller, data)
                objectManager.method190(xOff, tiles, yOff, controller, ob);
            } catch (Exception e) {
                System.out.println("[Viewer] Skipped corrupt objects in region " + re + ": " + e.getMessage());
            }
        }

        // Re-inject sandbox objects so they survive the rebuild exactly as if
        // they were part of the map.
        for (int[] p : placed) {
            int sx = p[0] - newBaseX;
            int sy = p[1] - newBaseY;
            if (sx < 1 || sx > SIZE - 2 || sy < 1 || sy > SIZE - 2) continue;
            try {
                objectManager.placeObject(sx, sy, p[2], p[3], p[4], p[5], tiles, controller);
            } catch (Exception e) {
                System.out.println("[Viewer] Failed to re-place object " + p[3] + ": " + e.getMessage());
            }
        }

        objectManager.method171(tiles, controller);
        controller.method275(0);

        baseX = newBaseX;
        baseY = newBaseY;
        initialized = true;
        objectCount = objTotal;

        System.out.println("[Viewer] Rebuilt scene base=(" + newBaseX + "," + newBaseY + ") regions="
                + loaded.size() + " objects=" + objTotal + " in "
                + ((System.nanoTime() - started) / 1000000) + "ms");
    }

    /** Adds an already-resolved object to the live scene (sandbox placement). */
    boolean placeObject(int worldTileX, int worldTileY, int plane, int id, int type, int rotation) {
        int sx = worldTileX - baseX;
        int sy = worldTileY - baseY;
        if (sx < 1 || sx > SIZE - 2 || sy < 1 || sy > SIZE - 2) return false;
        try {
            objectManager.placeObject(sx, sy, plane, id, type, rotation, tiles, controller);
            return true;
        } catch (Exception e) {
            System.out.println("[Viewer] place failed: " + e.getMessage());
            return false;
        }
    }

    void clearObjectCache() {
        if (controller != null) controller.clearObj5Cache();
    }

    /** Counts the objects stored in a map file (for the HUD counter). */
    static int countObjects(byte[] data) {
        if (data == null) return 0;
        Stream stream = new Stream(data);
        int count = 0;
        int lastId = -1;
        try {
            do {
                int delta = stream.method422();
                if (delta == 0) break;
                lastId += delta;
                count++;
                do {
                    int innerDelta = stream.method422();
                    if (innerDelta == 0) break;
                    stream.readUnsignedByte();
                    stream.readUnsignedByte();
                    stream.readUnsignedByte();
                    stream.readUnsignedByte();
                } while (true);
            } while (true);
        } catch (Exception ignore) {
        }
        return count;
    }
}

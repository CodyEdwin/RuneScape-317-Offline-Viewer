import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sandbox build mode: browse every object that has a model, preview the
 * selected one as a ghost where the crosshair points, and place it on the
 * ground.
 *
 * The ghost is drawn at the object's true footprint centre so the preview
 * matches the eventual placement exactly, including multi-tile objects. The
 * aim is published atomically each frame so a click on the EDT can never read
 * a half-updated tile.
 */
final class SandboxMode {

    boolean buildMode = false;
    boolean ghostEnabled = true;

    // Placed objects: {worldTileX, worldTileY, plane, objectId, type, rotation}.
    // Copy-on-write so the render thread can iterate it while the EDT edits it.
    final List<int[]> placedObjects = new CopyOnWriteArrayList<>();
    // Placements queued by the EDT, applied on the render thread.
    private final List<int[]> pendingPlacements = new ArrayList<>();

    // Every object id in the cache that has a model, i.e. browsable/placeable.
    int[] objectIdList = new int[0];
    int objectSel = 0;
    int placeRotation = 0;
    Model ghostModel;
    int ghostType = 10;
    String ghostName = "";

    // Object types tried in order until one yields a model.
    private static final int[] GHOST_TYPES = {10, 22, 0, 4, 1, 2, 3, 9};

    // Crosshair -> ground aim, recomputed every frame.
    boolean aimValid = false;
    int aimSceneX, aimSceneY, aimHeight;
    int aimWorldTileX, aimWorldTileY;
    // Atomic snapshot {valid, sceneX, sceneY, height, worldTileX, worldTileY}.
    volatile int[] aimSnapshot;
    // Screen-space polygon (4 x/y pairs) of the tile under the crosshair.
    volatile int[] highlightPoly;

    /** Collect every object id in the cache that actually has a model. */
    void buildList() {
        int total = ObjectDef.count();
        List<Integer> ids = new ArrayList<>(1024);
        for (int id = 0; id < total; id++) {
            try {
                ObjectDef def = ObjectDef.forID(id);
                if (def != null && def.hasModel()) ids.add(id);
            } catch (Throwable ignore) {
                // A broken definition shouldn't stop us browsing the rest.
            }
        }
        objectIdList = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) objectIdList[i] = ids.get(i);
        objectSel = 0;
        refreshGhostModel();
        System.out.println("[Viewer] Browsable objects: " + objectIdList.length + " of " + total);
    }

    /** Rebuild the ghost model for the selection, trying types in order. */
    void refreshGhostModel() {
        ghostModel = null;
        ghostType = 10;
        ghostName = "";
        if (objectIdList.length == 0) return;
        int id = objectIdList[objectSel];
        try {
            ObjectDef def = ObjectDef.forID(id);
            ghostName = def.name == null ? "(unnamed)" : def.name;
        } catch (Throwable ignore) {
        }
        for (int type : GHOST_TYPES) {
            try {
                Model m = ObjectDef.forID(id).method578(type, placeRotation, 0, 0, 0, 0, -1);
                if (m != null) {
                    ghostModel = m;
                    ghostType = type;
                    return;
                }
            } catch (Throwable ignore) {
            }
        }
    }

    void cycle(int step) {
        if (objectIdList.length == 0) return;
        objectSel = (objectSel + step) % objectIdList.length;
        if (objectSel < 0) objectSel += objectIdList.length;
        refreshGhostModel();
        System.out.println("[Viewer] Selected object " + objectIdList[objectSel] + " (" + ghostName
                + ") type=" + ghostType + " [" + (objectSel + 1) + "/" + objectIdList.length + "]");
    }

    void rotate(int delta) {
        placeRotation = (placeRotation + delta) & 3;
        refreshGhostModel();
    }

    /** March a ray from the camera until it hits terrain; that's the aim tile. */
    void computeAim(WorldScene scene, Camera cam, ViewerConfig cfg) {
        aimValid = false;
        if (scene.controller != null) {
            double phi = cam.yaw / 2048.0 * 2.0 * Math.PI;     // heading
            double theta = cam.pitch / 2048.0 * 2.0 * Math.PI; // positive = down
            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            double dx = -Math.sin(phi) * cosT;
            double dy = Math.cos(phi) * cosT;
            // "up" is more-negative, so descending to the ground means z
            // increases; looking down adds +sin(theta).
            double dz = sinT;
            int maxT = 128 * (cfg.viewRadius + 1); // never march past the scene
            int prevT = 250;
            // Start at least two tiles out so an object never lands on the
            // camera when you look steeply down.
            for (int t = 256; t <= maxT; t += 6) {
                int px = cam.sceneX + (int) (dx * t);
                int py = cam.sceneY + (int) (dy * t);
                int pz = cam.sceneZ + (int) (dz * t);
                int tx = px >> 7;
                int ty = py >> 7;
                // Only consider terrain we actually loaded.
                if (px < 0 || py < 0 || tx < scene.loadMinTX || tx > scene.loadMaxTX
                        || ty < scene.loadMinTY || ty > scene.loadMaxTY)
                    break;
                int h = scene.terrainHeight(px, py);
                if (pz >= h) {
                    // Refine the exact crossing between the last miss and hit.
                    int lo = prevT, hi = t;
                    for (int it = 0; it < 7; it++) {
                        int mt = (lo + hi) >> 1;
                        int qx = cam.sceneX + (int) (dx * mt);
                        int qy = cam.sceneY + (int) (dy * mt);
                        int qz = cam.sceneZ + (int) (dz * mt);
                        if (qz >= scene.terrainHeight(qx, qy)) hi = mt;
                        else lo = mt;
                    }
                    int fx = cam.sceneX + (int) (dx * hi);
                    int fy = cam.sceneY + (int) (dy * hi);
                    int sx = fx >> 7;
                    int sy = fy >> 7;
                    if (sx < scene.loadMinTX) sx = scene.loadMinTX;
                    else if (sx > scene.loadMaxTX) sx = scene.loadMaxTX;
                    if (sy < scene.loadMinTY) sy = scene.loadMinTY;
                    else if (sy > scene.loadMaxTY) sy = scene.loadMaxTY;
                    aimSceneX = sx * 128 + 64;
                    aimSceneY = sy * 128 + 64;
                    aimHeight = scene.terrainHeight(aimSceneX, aimSceneY);
                    aimWorldTileX = scene.baseX + sx;
                    aimWorldTileY = scene.baseY + sy;
                    aimValid = true;
                    break;
                }
                prevT = t;
            }
        }
        aimSnapshot = new int[]{
                aimValid ? 1 : 0, aimSceneX, aimSceneY, aimHeight, aimWorldTileX, aimWorldTileY
        };
    }

    /**
     * Footprint size {x, y} in tiles once the current rotation is applied.
     * Mirrors the map loader: odd rotations swap the two dimensions.
     */
    int[] selectedFootprint() {
        int sx = 1, sy = 1;
        if (objectIdList.length > 0) {
            try {
                ObjectDef def = ObjectDef.forID(objectIdList[objectSel]);
                sx = def.anInt744;
                sy = def.anInt761;
            } catch (Throwable ignore) {
            }
        }
        if (sx < 1) sx = 1;
        if (sy < 1) sy = 1;
        if ((placeRotation & 1) == 1) { int t = sx; sx = sy; sy = t; }
        return new int[]{sx, sy};
    }

    /** Low-corner scene tile of the object, centred on the aimed tile. */
    private int anchorSceneX(WorldScene scene, int[] fp) {
        return (aimWorldTileX - scene.baseX) - (fp[0] - 1) / 2;
    }

    private int anchorSceneY(WorldScene scene, int[] fp) {
        return (aimWorldTileY - scene.baseY) - (fp[1] - 1) / 2;
    }

    /**
     * Terrain height (scene units) at a scene point, falling back to the aim
     * height when the point is outside loaded terrain (where the height array
     * reads 0 and would misplace the ghost/highlight).
     */
    private int groundHeight(WorldScene scene, int sceneX, int sceneY) {
        int tx = sceneX >> 7;
        int ty = sceneY >> 7;
        if (tx < scene.loadMinTX || tx > scene.loadMaxTX || ty < scene.loadMinTY || ty > scene.loadMaxTY)
            return aimHeight;
        return scene.terrainHeight(sceneX, sceneY);
    }

    /**
     * Draw the selected object exactly where it will be placed. The engine
     * centres a multi-tile model on its footprint (centre = lowTile*128 +
     * 64*size), so the ghost lines up with the real object.
     */
    void drawGhost(WorldScene scene) {
        if (!buildMode || !ghostEnabled || !aimValid || ghostModel == null || scene.controller == null) return;
        int[] fp = selectedFootprint();
        int ax = anchorSceneX(scene, fp);
        int ay = anchorSceneY(scene, fp);
        int gx = ax * 128 + 64 * fp[0];
        int gy = ay * 128 + 64 * fp[1];
        // Height is taken from the low tile's corners, matching method175.
        int gh = groundHeight(scene, ax * 128 + 64, ay * 128 + 64);
        try {
            scene.controller.drawModelAtScene(ghostModel, gx, gh, gy, 0, 0);
        } catch (RuntimeException ignore) {
        }
    }

    /** Project the aim footprint to screen space for the overlay highlight. */
    void updateHighlight(WorldScene scene) {
        if (!buildMode || !aimValid || scene.controller == null) {
            highlightPoly = null;
            return;
        }
        try {
            int[] fp = selectedFootprint();
            int ax = anchorSceneX(scene, fp);
            int ay = anchorSceneY(scene, fp);
            int x0 = ax * 128, y0 = ay * 128;
            int x1 = x0 + fp[0] * 128, y1 = y0 + fp[1] * 128;
            int[] c0 = scene.controller.projectScenePoint(x0, groundHeight(scene, x0, y0), y0);
            int[] c1 = scene.controller.projectScenePoint(x1, groundHeight(scene, x1, y0), y0);
            int[] c2 = scene.controller.projectScenePoint(x1, groundHeight(scene, x1, y1), y1);
            int[] c3 = scene.controller.projectScenePoint(x0, groundHeight(scene, x0, y1), y1);
            if (c0 == null || c1 == null || c2 == null || c3 == null) {
                highlightPoly = null;
                return;
            }
            highlightPoly = new int[]{c0[0], c0[1], c1[0], c1[1], c2[0], c2[1], c3[0], c3[1]};
        } catch (RuntimeException e) {
            highlightPoly = null;
        }
    }

    /**
     * Commit the selected object at the aim tile. Reads the aim as one atomic
     * snapshot and anchors the object by the engine's low-corner rule.
     */
    void place() {
        if (!buildMode || objectIdList.length == 0) return;
        int[] snap = aimSnapshot;
        if (snap == null || snap[0] == 0) return;
        int id = objectIdList[objectSel];
        int[] fp = selectedFootprint();
        int ax = snap[4] - (fp[0] - 1) / 2;
        int ay = snap[5] - (fp[1] - 1) / 2;
        int[] p = new int[]{ax, ay, 0, id, ghostType, placeRotation};
        // The render thread applies this and records it, so placedObjects is
        // only ever modified on one thread (avoids duplicate injection).
        synchronized (pendingPlacements) {
            pendingPlacements.add(p);
        }
        System.out.println("[Viewer] Placed object " + id + " (" + ghostName + ") at tile ("
                + p[0] + "," + p[1] + ") type=" + ghostType + " rot=" + placeRotation
                + "  total=" + (placedObjects.size() + pendingPlacements.size()));
    }

    /** Apply queued placements on the render thread, straight into the scene. */
    void applyPending(WorldScene scene) {
        if (pendingPlacements.isEmpty()) return;
        List<int[]> batch;
        synchronized (pendingPlacements) {
            batch = new ArrayList<>(pendingPlacements);
            pendingPlacements.clear();
        }
        for (int[] p : batch) {
            placedObjects.add(p);
            scene.placeObject(p[0], p[1], p[2], p[3], p[4], p[5]);
        }
    }

    void undo(WorldScene scene) {
        int n = placedObjects.size();
        if (n == 0) return;
        int[] last = placedObjects.remove(n - 1);
        scene.forceRebuild = true;
        System.out.println("[Viewer] Removed object " + last[3] + " at (" + last[0] + "," + last[1] + "); "
                + placedObjects.size() + " left");
    }

    void clear(WorldScene scene) {
        int n = placedObjects.size();
        placedObjects.clear();
        synchronized (pendingPlacements) {
            pendingPlacements.clear();
        }
        scene.forceRebuild = true;
        System.out.println("[Viewer] Cleared " + n + " placed objects");
    }
}

/**
 * Headless verification: renders the default view, then walks east across a
 * region boundary and captures frames to /tmp/opencode so the seam can be
 * inspected without a display.
 */
final class HeadlessCapture {

    static void run(OfflineWorldViewer viewer) {
        WorldScene scene = viewer.scene;
        Camera cam = viewer.camera;
        if (!viewer.isSceneBuilt() || scene == null || scene.controller == null) {
            System.out.println("[Viewer] Scene not built; aborting shots.");
            return;
        }
        System.out.println("[Viewer] HEADLESS SHOT MODE, region " + scene.currentRegion
                + ", objects=" + scene.objectCount);

        // Default camera view.
        viewer.resetCamera();
        viewer.renderFrame();
        viewer.saveFrame("default_" + viewer.cfg.startRx + "_" + viewer.cfg.startRy);
        System.out.println("[Viewer] default region=" + scene.currentRegion + " objects=" + scene.objectCount
                + " non-black=" + viewer.countNonBlack());

        // Walk east across the region boundary and capture the transition.
        MapRegion start = scene.currentRegion;
        int centerX = start.centerTileX() * 128;
        int centerY = start.centerTileY() * 128;
        for (int step = 0; step <= 20; step++) {
            cam.worldX = centerX + step * 4 * 128; // 4 tiles per step, 80 tiles total
            cam.worldY = centerY;
            viewer.renderFrame();
            if (step % 5 == 0) {
                viewer.saveFrame("walk_east_" + step);
                System.out.println("[Viewer] walk step " + step + " (tile +" + (step * 4) + ") region="
                        + scene.currentRegion + " objects=" + scene.objectCount
                        + " non-black=" + viewer.countNonBlack());
            }
        }

        // Stand on the boundary and look in each direction.
        cam.worldX = centerX + 32 * 128;
        cam.worldY = centerY + 32 * 128;
        int[] yaws = {0, 512, 1024, 1536};
        for (int y : yaws) {
            cam.yaw = y;
            viewer.renderFrame();
            viewer.saveFrame("boundary_y" + y);
            System.out.println("[Viewer] boundary yaw=" + y + " region=" + scene.currentRegion
                    + " objects=" + scene.objectCount + " non-black=" + viewer.countNonBlack());
        }
    }
}

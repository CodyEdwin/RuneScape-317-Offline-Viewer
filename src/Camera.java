/**
 * First-person camera: world position, look angles, and vertical physics.
 *
 * World coordinates are global units (1/128 of a tile), like the client.
 * The camera is derived into scene-local coordinates every frame from the
 * scene's current origin, so the renderer always gets a position inside the
 * 104x104 window.
 */
final class Camera {

    // Global position in world units (1/128 of a tile).
    int worldX = 0;
    int worldY = 0;

    // Scene-local coordinates handed to WorldController.method313.
    // sceneZ must be NEGATIVE (camera above ground; the renderer computes
    // terrainHeight - sceneZ for depth).
    int sceneX = 4032;
    int sceneY = 4032;
    int sceneZ = -320;

    // Look angles. yaw wraps 0..2047; pitch runs from WorldController.pitchBase
    // (looking up) to +383 (looking straight down); pitch 0 is level.
    int yaw = 0;
    int pitch = 32;

    // Vertical motion.
    boolean flyMode = false;
    double jumpY = 0;    // walk: extra height above eye level (jump)
    double jumpVY = 0;   // walk: vertical velocity
    double flyY = 220;   // fly: camera height above the terrain
    int eyeTrim = 0;     // PgUp/PgDn offset (both modes)

    int pitchMin() {
        return WorldController.pitchBase;
    }

    int pitchMax() {
        return WorldController.pitchBase + WorldController.pitchLevels * 32 - 1;
    }

    /** Resets the camera to its defaults, centred on a region. */
    void reset(MapRegion region, ViewerConfig cfg) {
        yaw = cfg.defaultYaw;
        pitch = cfg.defaultPitch;
        eyeTrim = 0;
        jumpY = 0;
        jumpVY = 0;
        flyMode = false;
        if (region != null) {
            worldX = region.centerTileX() * 128;
            worldY = region.centerTileY() * 128;
        }
    }

    /** Teleports to a region centre (used by region jump). */
    void moveTo(MapRegion region) {
        worldX = region.centerTileX() * 128;
        worldY = region.centerTileY() * 128;
        jumpY = 0;
        jumpVY = 0;
    }

    void startJump() {
        if (!flyMode && jumpY <= 0) {
            jumpY = 0.001;
            jumpVY = ViewerConfig.JUMP_SPEED;
        }
    }

    void toggleFly(ViewerConfig cfg) {
        flyMode = !flyMode;
        if (flyMode) {
            flyY = cfg.eyeHeight + jumpY;
        } else {
            jumpY = 0;
            jumpVY = 0;
        }
    }

    /** Mouse look: right turns the view right (decreasing yaw); down looks down. */
    void look(int dx, int dy, ViewerConfig cfg) {
        yaw = (yaw - (int) (dx * cfg.mouseSens)) & 0x7ff;
        pitch = (int) (pitch + dy * cfg.mouseSens * ViewerConfig.MOUSE_PITCH_RATIO);
        clampPitch();
    }

    /** Applies held movement keys for this frame. */
    void applyInput(InputState in, int delta, ViewerConfig cfg) {
        double dt = delta;
        double step = delta * ViewerConfig.WALK_SPEED;
        if (in.sprint) step *= ViewerConfig.SPRINT_MULT;

        // The engine's yaw is 2048 units per full turn (see method313), so the
        // view/movement angle is yaw * 2*PI / 2048 - using any other scale makes
        // movement rotate at a different rate than the view.
        double angle = yaw / 2048.0 * 2 * Math.PI;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        // Arrow keys rotate the view (keyboard look fallback).
        if (in.lookLeft)  yaw += (int) (dt * ViewerConfig.LOOK_YAW_SPEED * 3);
        if (in.lookRight) yaw -= (int) (dt * ViewerConfig.LOOK_YAW_SPEED * 3);
        if (in.lookUp)    pitch -= (int) (dt * ViewerConfig.LOOK_PITCH_SPEED * 3);
        if (in.lookDown)  pitch += (int) (dt * ViewerConfig.LOOK_PITCH_SPEED * 3);

        // Movement must match the renderer's view axes (Model.method443):
        //   forward = (-sin, +cos),  screen-right = (+cos, +sin)
        if (in.forward)     { worldX -= (int) (sin * step); worldY += (int) (cos * step); }
        if (in.back)        { worldX += (int) (sin * step); worldY -= (int) (cos * step); }
        if (in.strafeRight) { worldX += (int) (cos * step); worldY += (int) (sin * step); }
        if (in.strafeLeft)  { worldX -= (int) (cos * step); worldY -= (int) (sin * step); }

        // Vertical motion.
        if (flyMode) {
            if (in.jump)   flyY += dt * ViewerConfig.FLY_SPEED;
            if (in.crouch) flyY -= dt * ViewerConfig.FLY_SPEED;
            if (flyY < 20) flyY = 20;
            if (flyY > 4000) flyY = 4000;
        } else {
            jumpVY -= ViewerConfig.GRAVITY * dt;
            jumpY += jumpVY * dt;
            if (jumpY <= 0) {
                jumpY = 0;
                jumpVY = 0;
            }
        }

        yaw &= 0x7ff;
        clampPitch();

        // Keep the camera inside the world (64 regions x 64 tiles = 4096 tiles).
        int maxWorld = 4096 * 128 - 1;
        if (worldX < 0) worldX = 0;
        if (worldY < 0) worldY = 0;
        if (worldX > maxWorld) worldX = maxWorld;
        if (worldY > maxWorld) worldY = maxWorld;
    }

    /**
     * Derives the scene-local position and eye height from the world position
     * and the terrain under the camera. Must be called after the scene window
     * has been rebuilt for this position.
     */
    void updateScenePosition(WorldScene scene, ViewerConfig cfg, boolean crouching) {
        sceneX = worldX - scene.baseX * 128;
        sceneY = worldY - scene.baseY * 128;

        double above;
        if (flyMode) {
            above = flyY + eyeTrim;
            if (above < 20) above = 20;
        } else {
            above = cfg.eyeHeight + jumpY + eyeTrim - (crouching ? ViewerConfig.CROUCH_DROP : 0);
            if (above < 40) above = 40;
        }
        sceneZ = scene.terrainHeight(sceneX, sceneY) - (int) above;
        if (sceneZ > -10) sceneZ = -10;
    }

    private void clampPitch() {
        if (pitch < pitchMin()) pitch = pitchMin();
        if (pitch > pitchMax()) pitch = pitchMax();
    }
}

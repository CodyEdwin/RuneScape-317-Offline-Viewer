/**
 * Process-wide tunables for the offline viewer: window size, start region,
 * camera defaults, view distance, and the movement/look constants. Keeping
 * them in one place means every module reads the same values instead of the
 * coordinator passing numbers around.
 */
final class ViewerConfig {

    // Window / viewport.
    static final int WIDTH = 765;
    static final int HEIGHT = 503;

    // Scene + renderer.
    int viewRadius = 40;          // tiles visible around the camera (stock 25)
    int farClip = 12000;          // projection far clip (auto-scaled if not set)
    boolean farExplicit = false;  // true when far= was given on the command line
    static final int SCENE_CENTER = 51; // scene tile the camera re-centres on

    // Where to start.
    int startRx = 54;
    int startRy = 54;

    // Camera defaults.
    int eyeHeight = 150;          // eye height above the terrain, in scene units
    int defaultPitch = 32;        // 0 = level with the horizon
    int defaultYaw = 0;

    // Mouse look.
    double mouseSens = 0.07;      // yaw units per pixel of mouse movement
    static final double MOUSE_PITCH_RATIO = 0.6;
    static final int MOUSE_DELTA_CLAMP = 300; // ignore warp/refocus spikes

    // Movement / vertical physics tuning (world units are 1/128 of a tile).
    static final double WALK_SPEED = 0.5;    // units per millisecond
    static final double SPRINT_MULT = 3.0;
    static final double LOOK_YAW_SPEED = 0.30;   // arrow-key look
    static final double LOOK_PITCH_SPEED = 0.20;
    static final double GRAVITY = 0.0024;    // units per ms^2
    static final double JUMP_SPEED = 0.62;   // units per ms, upward
    static final double FLY_SPEED = 0.9;     // units per ms
    static final int CROUCH_DROP = 70;

    // Headless capture mode (render to PNG instead of opening a window).
    boolean headless = false;

    // If true, the player can walk through objects and terrain.
    boolean noClip = false;

    /**
     * Applies command-line options:
     *   shot            headless capture mode
     *   rx,ry           starting region
     *   view=N          view distance in tiles
     *   height=N        starting eye height
     *   far=N           projection far clip
     *   pitch=N         starting look angle
     *   sens=N          mouse-look sensitivity
     *   noclip          enable no-clip mode
     */
    void parseArgs(String[] args) {
        for (String a : args) {
            if (a.equalsIgnoreCase("shot")) {
                headless = true;
            } else if (a.contains(",")) {
                String[] parts = a.split(",");
                try {
                    startRx = Integer.parseInt(parts[0].trim());
                    startRy = Integer.parseInt(parts[1].trim());
                } catch (Exception ignore) {
                }
            } else if (a.startsWith("view=")) {
                try { viewRadius = Integer.parseInt(a.substring(5).trim()); } catch (Exception ignore) {}
            } else if (a.startsWith("height=")) {
                try { eyeHeight = Integer.parseInt(a.substring(7).trim()); } catch (Exception ignore) {}
            } else if (a.startsWith("far=")) {
                try { farClip = Integer.parseInt(a.substring(4).trim()); farExplicit = true; } catch (Exception ignore) {}
            } else if (a.startsWith("pitch=")) {
                try { defaultPitch = Integer.parseInt(a.substring(6).trim()); } catch (Exception ignore) {}
            } else if (a.startsWith("sens=")) {
                try { mouseSens = Double.parseDouble(a.substring(5).trim()); } catch (Exception ignore) {}
            } else if (a.equals("noclip")) {
                noClip = true;
            }
        }
        // The model renderer has its own far clip; scale it with the view so
        // distant objects are not cut off before the terrain is.
        if (!farExplicit) farClip = viewRadius * 128 + 5000;
    }
}
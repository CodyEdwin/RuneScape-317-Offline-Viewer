import java.awt.Component;
import java.awt.Cursor;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;

/**
 * FPS mouse look: a blank cursor, endless pointer re-centring via Robot, and
 * calibration for HiDPI / Wayland setups where warping needs scaled
 * coordinates or doesn't work at all.
 *
 * Also runs the high-rate pointer-lock thread that pins the cursor to the
 * centre every few milliseconds, independent of rendering speed, so fast
 * flicks and busy frames cannot let the pointer escape the window.
 */
final class MouseLook {

    private final Component component;
    private Robot robot;
    private Cursor blankCursor;
    private Cursor normalCursor;

    volatile boolean looking = false;
    int lookCenterX = ViewerConfig.WIDTH / 2;
    int lookCenterY = ViewerConfig.HEIGHT / 2;
    private int lastMouseX = ViewerConfig.WIDTH / 2;
    private int lastMouseY = ViewerConfig.HEIGHT / 2;

    // Robot calibration: some systems need screen coordinates scaled by the
    // display scale factor before passing them to Robot.mouseMove.
    private boolean robotCalibrated = false;
    volatile boolean robotOk = false;
    private double robotScaleX = 1.0;
    private double robotScaleY = 1.0;
    private boolean recenterLoggedErr = false;

    private volatile boolean pointerLockRunning = false;

    // Cached screen coordinates of the panel centre, refreshed on move/resize.
    private volatile int screenCenterX = 0;
    private volatile int screenCenterY = 0;

    MouseLook(Component component) {
        this.component = component;
    }

    /**
     * Creates the Robot and blank cursor, then starts the pointer-lock thread.
     * The Robot is created independently of the blank cursor so a cursor
     * failure can never disable re-centring.
     */
    void install() {
        normalCursor = component.getCursor();
        try {
            robot = new Robot();
            System.out.println("[Viewer] Robot created for mouse-look");
        } catch (Throwable t) {
            robot = null;
            System.out.println("[Viewer] Robot UNAVAILABLE (" + t + ") - mouse-look will not re-centre");
        }
        try {
            blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");
            System.out.println("[Viewer] blank cursor created");
        } catch (Throwable t) {
            blankCursor = null;
            System.out.println("[Viewer] blank cursor UNAVAILABLE (" + t + ")");
        }

        pointerLockRunning = true;
        Thread lock = new Thread(() -> {
            while (pointerLockRunning) {
                if (looking) recenter();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "pointer-lock");
        lock.setDaemon(true);
        lock.start();
    }

    void updateScreenCenter() {
        try {
            Point p = component.getLocationOnScreen();
            screenCenterX = p.x + component.getWidth() / 2;
            screenCenterY = p.y + component.getHeight() / 2;
        } catch (Throwable ignore) {
        }
    }

    /** Called when the window resizes so the look centre tracks the panel. */
    void onResize() {
        lookCenterX = component.getWidth() / 2;
        lookCenterY = component.getHeight() / 2;
        updateScreenCenter();
    }

    void start() {
        if (looking) return;
        looking = true;
        lookCenterX = component.getWidth() / 2;
        lookCenterY = component.getHeight() / 2;
        lastMouseX = lookCenterX;
        lastMouseY = lookCenterY;
        if (blankCursor != null) component.setCursor(blankCursor);
        component.requestFocusInWindow();
        updateScreenCenter();
        calibrate();
        recenter();
        System.out.println("[Viewer] mouse-look ON (robot=" + (robot != null) + ", ok=" + robotOk
                + ", center=" + lookCenterX + "," + lookCenterY
                + ", screen=" + screenCenterX + "," + screenCenterY + ")");
    }

    void stop() {
        looking = false;
        if (normalCursor != null) component.setCursor(normalCursor);
    }

    /** Warp the cursor back to the centre so the mouse can move indefinitely. */
    void recenter() {
        if (robot == null || !robotOk) return;
        try {
            int tx = screenCenterX;
            int ty = screenCenterY;
            if (tx <= 0 || ty <= 0) {
                updateScreenCenter();
                tx = screenCenterX;
                ty = screenCenterY;
            }
            robot.mouseMove((int) Math.round(tx * robotScaleX), (int) Math.round(ty * robotScaleY));
        } catch (Throwable t) {
            if (!recenterLoggedErr) {
                recenterLoggedErr = true;
                System.out.println("[Viewer] recenterMouse FAILED: " + t);
            }
        }
    }

    /**
     * Works out whether Robot.mouseMove actually moves the pointer, and which
     * coordinate space it expects. Called lazily the first time look engages.
     */
    private void calibrate() {
        if (robot == null || robotCalibrated) return;
        robotCalibrated = true;
        try {
            Point base = component.getLocationOnScreen();
            int cx = base.x + component.getWidth() / 2;
            int cy = base.y + component.getHeight() / 2;
            Point p0 = MouseInfo.getPointerInfo().getLocation();

            // If the pointer already sits near the centre a no-op warp would
            // look like success, so probe a clearly different point.
            int tx = cx, ty = cy;
            if (Math.abs(p0.x - tx) <= 25 && Math.abs(p0.y - ty) <= 25) {
                tx = base.x + 40;
                ty = base.y + 40;
            }

            // 1) Plain, unscaled screen coordinates.
            if (tryWarp(tx, ty, 1.0, 1.0)) {
                robotOk = true;
                robotScaleX = robotScaleY = 1.0;
                System.out.println("[Viewer] Robot OK (unscaled)");
                return;
            }

            // 2) Coordinates scaled by the display transform (HiDPI).
            AffineTransform t = component.getGraphicsConfiguration().getDefaultTransform();
            double sx = t != null ? t.getScaleX() : 1.0;
            double sy = t != null ? t.getScaleY() : 1.0;
            if (tryWarp(tx, ty, sx, sy)) {
                robotOk = true;
                robotScaleX = sx;
                robotScaleY = sy;
                System.out.println("[Viewer] Robot OK (scaled x" + sx + ")");
                return;
            }

            robotOk = false;
            Point got = MouseInfo.getPointerInfo().getLocation();
            System.out.println("[Viewer] Robot NOT WORKING - the cursor cannot be warped on this system"
                    + " (target=" + tx + "," + ty + " got=" + got.x + "," + got.y
                    + "). This happens on Wayland/native-Wayland; mouse-look will use relative movement only.");
        } catch (Throwable t) {
            robotOk = false;
            System.out.println("[Viewer] Robot calibration failed: " + t);
        }
    }

    private boolean tryWarp(int tx, int ty, double sx, double sy) {
        try {
            robot.mouseMove((int) Math.round(tx * sx), (int) Math.round(ty * sy));
            robot.delay(40);
            Point got = MouseInfo.getPointerInfo().getLocation();
            return Math.abs(got.x - tx) <= 4 && Math.abs(got.y - ty) <= 4;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Applies a mouse-move event to the camera (only while captured). */
    void apply(MouseEvent e, Camera cam, ViewerConfig cfg) {
        if (!looking) return;
        // Measure movement since the previous event (not from the centre), so
        // queued/coalesced events can never multiply the rotation.
        int dx = e.getX() - lastMouseX;
        int dy = e.getY() - lastMouseY;
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        if (dx == 0 && dy == 0) return;

        int clamp = ViewerConfig.MOUSE_DELTA_CLAMP;
        if (dx > clamp) dx = clamp;
        if (dx < -clamp) dx = -clamp;
        if (dy > clamp) dy = clamp;
        if (dy < -clamp) dy = -clamp;

        cam.look(dx, dy, cfg);

        // Put the cursor back in the middle. Only treat it as centred if the
        // warp actually works.
        if (robotOk) {
            recenter();
            lastMouseX = lookCenterX;
            lastMouseY = lookCenterY;
        }
    }
}

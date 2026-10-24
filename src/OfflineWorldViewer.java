import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * EXPERIMENTAL offline scene viewer.
 *
 * Renders RS317 map regions using the client's own ObjectManager +
 * WorldController + Model/Texture/DrawingArea pipeline, and hosts a sandbox
 * for browsing and placing objects.
 *
 * This class is only the coordinator: it wires the modules together, owns the
 * render loop and reacts to input. The heavy lifting lives in:
 *
 *   ViewerConfig      window size, camera defaults, movement tuning, CLI
 *   GameCache         cache I/O (definitions, textures, models, map index)
 *   RegionRepository  the region catalogue + decompressed map bytes
 *   WorldScene        the 104x104 buffers, controller, object manager, rebuild
 *   Camera            position, look angles and vertical physics
 *   InputState        the movement keys currently held
 *   MouseLook         FPS mouse capture, re-centring and Robot calibration
 *   SandboxMode       object browsing, ghost preview, aim and placement
 *   LoadingScreen     the animated startup progress bar
 *   HudOverlay        the in-game status / controls overlay
 *   HeadlessCapture   render-to-PNG verification mode
 *
 * The WorldController is hardcoded to a 104x104 tile grid, so the scene is
 * rebuilt around the camera as it moves; crossing a region boundary therefore
 * looks just like walking in the real game. Regions can also be jumped to
 * directly with N/P.
 *
 * Controls (first-person):
 *   Click       - capture the mouse for mouse-look (Esc releases it)
 *   Mouse       - look around (yaw + pitch)
 *   W/S         - walk forward/back
 *   A/D         - strafe left/right
 *   Shift       - sprint
 *   Space       - jump (or ascend while flying)
 *   Ctrl        - crouch (or descend while flying)
 *   F           - toggle fly mode (free vertical movement)
 *   Arrows      - also rotate the camera (yaw/pitch)
 *   PgUp/PgDn   - raise/lower the eye height
 *   [ / ]       - decrease/increase mouse sensitivity
 *   N/P         - next/previous region (direct jump)
 *   R           - reset camera (re-centre on current region)
 *   O           - toggle object count overlay
 *   B           - sandbox build mode (browse & place objects)
 *
 * Headless capture mode (no window):
 *   java OfflineWorldViewer shot [rx,ry]
 *   Renders the default view (plus a few angles) to /tmp/opencode/*.png.
 */
public class OfflineWorldViewer extends JPanel
        implements Runnable, KeyListener, MouseListener, MouseMotionListener {

    // ---- Modules ----------------------------------------------------------
    final ViewerConfig cfg;
    private final GameCache cache;
    private final RegionRepository regions;
    final WorldScene scene = new WorldScene();
    final Camera camera = new Camera();
    final InputState input = new InputState();
    private final MouseLook mouse;
    private final SandboxMode sandbox = new SandboxMode();
    private final LoadingScreen loading = new LoadingScreen();

    // ---- Frame buffer -----------------------------------------------------
    private final BufferedImage image;
    private final int[] renderPixels;
    private volatile boolean running = true;

    // ---- Runtime state ----------------------------------------------------
    private volatile boolean sceneBuilt = false;
    private boolean visibilityInitialized = false;
    private String status = "Initialising cache...";
    private boolean showObjCount = false;

    public OfflineWorldViewer(ViewerConfig cfg) {
        this.cfg = cfg;
        this.cache = new GameCache();
        this.regions = new RegionRepository(cache);
        this.mouse = new MouseLook(this);

        image = new BufferedImage(ViewerConfig.WIDTH, ViewerConfig.HEIGHT, BufferedImage.TYPE_INT_RGB);
        renderPixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        DrawingArea.initDrawingArea(ViewerConfig.HEIGHT, ViewerConfig.WIDTH, renderPixels);

        if (!cfg.headless) {
            setPreferredSize(new Dimension(ViewerConfig.WIDTH, ViewerConfig.HEIGHT));
            setFocusable(true);
            requestFocusInWindow();
            addKeyListener(this);
            addMouseListener(this);
            addMouseMotionListener(this);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent e) {
                    mouse.updateScreenCenter();
                }

                @Override
                public void componentResized(ComponentEvent e) {
                    mouse.onResize();
                }
            });
            mouse.install();

            // Repaint the loading screen smoothly while the cache, textures and
            // models load, independent of how often the loader reports progress.
            Thread anim = new Thread(() -> {
                while (running && !sceneBuilt) {
                    repaint();
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                repaint();
            }, "load-anim");
            anim.setDaemon(true);
            anim.start();
        }

        new Thread(this, "offline-viewer").start();
    }

    @Override
    public void run() {
        try {
            initCacheAndScene();
        } catch (Throwable t) {
            String msg = "Failed to build scene: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            loading.fail(msg);
            status = msg;
            t.printStackTrace();
        }

        if (cfg.headless) {
            HeadlessCapture.run(this);
            return;
        }

        long last = System.currentTimeMillis();
        int frames = 0;
        while (running) {
            long now = System.currentTimeMillis();
            int delta = (int) (now - last);
            last = now;

            if (sceneBuilt) {
                // Advance the client's animation clock so animated objects play.
                client.loopCycle++;
                mouse.updateScreenCenter();
                int prevWorldX = camera.worldX;
                int prevWorldY = camera.worldY;
                camera.applyInput(input, delta, cfg);

                // If not no-clip, test whether the new tile has collision enabled
                // and revert to the previous position if it does.
                if (!cfg.noClip) {
                    int tx = (camera.worldX >> 7) - scene.baseX;
                    int ty = (camera.worldY >> 7) - scene.baseY;
                    if (tx < 0) tx = 0; if (tx > 103) tx = 103;
                    if (ty < 0) ty = 0; if (ty > 103) ty = 103;
                    boolean blocked = scene.isBlocked(tx, ty);
                    if (blocked) {
                        camera.worldX = prevWorldX;
                        camera.worldY = prevWorldY;
                        System.out.println("[COLLISION] Blocked at tile (" + tx + "," + ty + "), reverted");
                    }
                } else {
                    System.out.println("[DEBUG] noClip=true, skipping collision check");
                }
                // Warp before and after the (potentially slow) render so the
                // pointer spends as little time away from the centre as possible.
                if (mouse.looking) mouse.recenter();
                renderFrame();
                if (mouse.looking) mouse.recenter();
                frames++;
                if (frames % 50 == 0) {
                    System.out.println("[Viewer] Frame " + frames + ", cam=("
                            + camera.sceneX + "," + camera.sceneY + "," + camera.sceneZ
                            + "), region=" + scene.currentRegion + ", objects=" + scene.objectCount
                            + ", non-black=" + countNonBlack());
                }
            }
            repaint();

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                running = false;
            }
        }
    }

    /** Opens the cache, loads archives and builds the first scene. */
    private void initCacheAndScene() throws Exception {
        // Initialise the client's animation timing so animated objects play.
        if (client.anIntArray1232 == null) {
            client.anIntArray1232 = new int[32];
            int i = 2;
            for (int k = 0; k < 32; k++) {
                client.anIntArray1232[k] = i - 1;
                i += i;
            }
        }
        cache.open();
        setLoad(5, "Cache opened. Unpacking definitions...");

        // Increase the view distance and far clip so distant terrain is drawn
        // (mainly noticeable when the camera is high up).
        WorldController.viewRadius = cfg.viewRadius;
        WorldController.farClip = cfg.farClip;
        Model.farClip = cfg.farClip;

        cache.unpackConfigs();

        // Build the sandbox browser list: every object that has a model.
        sandbox.buildList();
        setLoad(12, "Definitions unpacked. Loading textures...");

        cache.loadTextures();

        setLoad(18, "Pre-loading all models from cache archive 2...");
        int modelsLoaded = cache.preloadModels(loading);
        System.out.println("[Viewer] Pre-loaded " + modelsLoaded + " models from archive 2");
        System.out.flush();
        setLoad(72, "Models loaded (" + modelsLoaded + "). Reading map index...");

        regions.load();
        System.out.println("[Viewer] Found " + regions.size() + " regions in cache");
        setLoad(80, "Building scene (" + regions.size() + " regions)...");

        scene.init();

        // Start at the requested region (default 54,54), falling back to the
        // first region in the catalogue.
        MapRegion startRegion = regions.get(cfg.startRx, cfg.startRy);
        if (startRegion == null) startRegion = regions.byIndex(0);
        scene.currentRegion = startRegion;
        scene.regionIndex = regions.indexOf(startRegion);
        camera.moveTo(startRegion);
        scene.forceRebuild = true;
        setLoad(90, "Building scene around region " + startRegion + "...");
        scene.ensureAround(camera, cfg, regions, sandbox.placedObjects);

        setLoad(100, "First person: click to capture mouse, WASD to walk, Space to jump.");
        sceneBuilt = true;
        requestFocusInWindow();
        System.out.println("[Viewer] Scene built centred on region " + scene.currentRegion);
        System.out.flush();
    }

    /** Renders one frame of the scene (and the build ghost) into the buffer. */
    void renderFrame() {
        if (scene.controller == null) return;
        scene.ensureAround(camera, cfg, regions, sandbox.placedObjects);
        camera.updateScenePosition(scene, cfg, input.crouch);
        sandbox.applyPending(scene);
        DrawingArea.setAllPixelsToZero();
        if (!visibilityInitialized) {
            Texture.method364();
            int[] ai = new int[9];
            for (int i8 = 0; i8 < 9; i8++) {
                int k8 = 128 + i8 * 32 + 15;
                int l8 = 600 + k8 * 3;
                int i9 = Texture.anIntArray1470[k8];
                ai[i8] = l8 * i9 >> 16;
            }
            WorldController.method310(500, 800, 512, 334, ai);
            visibilityInitialized = true;
        }
        scene.controller.method275(0);
        Model.aBoolean1684 = true;
        Model.anInt1687 = 0;
        try {
            scene.controller.method313(camera.sceneX, camera.sceneY, camera.yaw, camera.sceneZ, 0, camera.pitch);
        } catch (RuntimeException e) {
            System.out.println("[Viewer] Render error at " + scene.currentRegion + ": " + e.getMessage());
            return;
        }
        sandbox.computeAim(scene, camera, cfg);
        sandbox.drawGhost(scene);
        sandbox.updateHighlight(scene);
        scene.clearObjectCache();
    }

    boolean isSceneBuilt() {
        return sceneBuilt;
    }

    void resetCamera() {
        camera.reset(scene.currentRegion, cfg);
        scene.forceRebuild = true;
    }

    private void changeRegion(int delta) {
        if (!sceneBuilt || regions.isEmpty()) return;
        int next = scene.regionIndex + delta;
        if (next < 0) next = regions.size() - 1;
        if (next >= regions.size()) next = 0;
        scene.regionIndex = next;
        MapRegion re = regions.byIndex(next);
        scene.currentRegion = re;
        camera.moveTo(re);
        scene.forceRebuild = true;
    }

    private void adjustSens(double factor) {
        cfg.mouseSens *= factor;
        if (cfg.mouseSens < 0.01) cfg.mouseSens = 0.01;
        if (cfg.mouseSens > 4.0) cfg.mouseSens = 4.0;
    }

    int countNonBlack() {
        int n = 0;
        for (int p : renderPixels) {
            if ((p & 0xffffff) != 0) n++;
        }
        return n;
    }

    void saveFrame(String name) {
        try {
            javax.imageio.ImageIO.write(image, "png", new java.io.File("/tmp/opencode/" + name + ".png"));
        } catch (Exception e) {
            System.out.println("[Viewer] Failed to save frame " + name + ": " + e.getMessage());
        }
    }

    /** Publishes loading progress from the loader thread. */
    private void setLoad(int percent, String message) {
        loading.report(percent, message);
        status = message;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!sceneBuilt) {
            loading.draw(g, getWidth(), getHeight());
            return;
        }
        if (image != null) {
            g.drawImage(image, 0, 0, ViewerConfig.WIDTH, ViewerConfig.HEIGHT, this);
        }
        HudOverlay.draw(g, cfg, regions, scene, camera, sandbox, mouse, showObjCount, status);
    }

    // ---- Input ------------------------------------------------------------

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> input.forward = true;
            case KeyEvent.VK_S -> input.back = true;
            case KeyEvent.VK_A -> input.strafeLeft = true;
            case KeyEvent.VK_D -> input.strafeRight = true;
            case KeyEvent.VK_UP -> input.lookUp = true;
            case KeyEvent.VK_DOWN -> input.lookDown = true;
            case KeyEvent.VK_LEFT -> input.lookLeft = true;
            case KeyEvent.VK_RIGHT -> input.lookRight = true;
            case KeyEvent.VK_SPACE -> {
                input.jump = true;
                camera.startJump();
            }
            case KeyEvent.VK_CONTROL -> input.crouch = true;
            case KeyEvent.VK_PAGE_UP -> camera.eyeTrim += 40;
            case KeyEvent.VK_PAGE_DOWN -> camera.eyeTrim -= 40;
            case KeyEvent.VK_OPEN_BRACKET -> adjustSens(0.8);
            case KeyEvent.VK_CLOSE_BRACKET -> adjustSens(1.25);
            case KeyEvent.VK_F -> camera.toggleFly(cfg);
            case KeyEvent.VK_N -> changeRegion(+1);
            case KeyEvent.VK_P -> changeRegion(-1);
            case KeyEvent.VK_SHIFT -> input.sprint = true;
            case KeyEvent.VK_O -> showObjCount = !showObjCount;
            case KeyEvent.VK_R -> resetCamera();
            case KeyEvent.VK_ESCAPE -> mouse.stop();
            // ---- Sandbox object browsing / placement ----
            case KeyEvent.VK_B -> {
                sandbox.buildMode = !sandbox.buildMode;
                System.out.println("[Viewer] Build mode " + (sandbox.buildMode ? "ON" : "OFF"));
            }
            case KeyEvent.VK_COMMA -> sandbox.cycle(-1);
            case KeyEvent.VK_PERIOD -> sandbox.cycle(+1);
            case KeyEvent.VK_LESS -> sandbox.cycle(-10);
            case KeyEvent.VK_GREATER -> sandbox.cycle(+10);
            case KeyEvent.VK_SEMICOLON -> sandbox.rotate(3);
            case KeyEvent.VK_QUOTE -> sandbox.rotate(1);
            case KeyEvent.VK_ENTER -> sandbox.place();
            case KeyEvent.VK_BACK_SPACE -> sandbox.undo(scene);
            case KeyEvent.VK_DELETE -> sandbox.clear(scene);
            case KeyEvent.VK_G -> sandbox.ghostEnabled = !sandbox.ghostEnabled;
            case KeyEvent.VK_C -> toggleNoClip();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> input.forward = false;
            case KeyEvent.VK_S -> input.back = false;
            case KeyEvent.VK_A -> input.strafeLeft = false;
            case KeyEvent.VK_D -> input.strafeRight = false;
            case KeyEvent.VK_UP -> input.lookUp = false;
            case KeyEvent.VK_DOWN -> input.lookDown = false;
            case KeyEvent.VK_LEFT -> input.lookLeft = false;
            case KeyEvent.VK_RIGHT -> input.lookRight = false;
            case KeyEvent.VK_SPACE -> input.jump = false;
            case KeyEvent.VK_CONTROL -> input.crouch = false;
            case KeyEvent.VK_SHIFT -> input.sprint = false;
        }
    }

    private void toggleNoClip() {
        cfg.noClip = !cfg.noClip;
        System.out.println("[Viewer] noClip " + (cfg.noClip ? "ON" : "OFF"));
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1) {
            // In build mode, once the mouse is captured, clicking places the
            // selected object at the crosshair. Otherwise clicking captures.
            if (mouse.looking && sandbox.buildMode) sandbox.place();
            else mouse.start();
        } else if (e.getButton() == MouseEvent.BUTTON3 && mouse.looking && sandbox.buildMode) {
            sandbox.undo(scene);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
        // If the pointer slipped out of the panel, pull it straight back so the
        // cursor never visibly escapes the game window.
        if (mouse.looking) mouse.recenter();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        mouse.apply(e, camera, cfg);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouse.apply(e, camera, cfg);
    }

    public static void main(String[] args) {
        ViewerConfig cfg = new ViewerConfig();
        cfg.parseArgs(args);

        if (cfg.headless) {
            new java.io.File("/tmp/opencode").mkdirs();
            new OfflineWorldViewer(cfg);
            try {
                while (true) Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("RS317 Offline Scene Viewer (experimental)");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(new OfflineWorldViewer(cfg));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
        try {
            while (true) Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
    }
}

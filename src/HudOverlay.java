import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Draws the on-screen overlay over the rendered frame: the ground-tile
 * highlight (while placing), the first-person crosshair, and the status /
 * controls HUD. Reads the game state but owns none of it.
 */
final class HudOverlay {

    static void draw(Graphics g, ViewerConfig cfg, RegionRepository regions, WorldScene scene,
                     Camera cam, SandboxMode sandbox, MouseLook mouse,
                     boolean showObjCount, String status) {

        // Ground-tile highlight: the exact ground the crosshair aims at while
        // in build mode (before the object is placed).
        int[] poly = sandbox.highlightPoly;
        if (sandbox.buildMode && poly != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            int[] hx = {poly[0], poly[2], poly[4], poly[6]};
            int[] hy = {poly[1], poly[3], poly[5], poly[7]};
            g2.setColor(new Color(255, 235, 60, 90));
            g2.fillPolygon(hx, hy, 4);
            g2.setColor(new Color(255, 235, 60, 230));
            g2.setStroke(new BasicStroke(2f));
            g2.drawPolygon(hx, hy, 4);
            g2.dispose();
        }

        // First-person crosshair.
        int cx = ViewerConfig.WIDTH / 2;
        int cy = ViewerConfig.HEIGHT / 2;
        g.setColor(new Color(255, 255, 255, 180));
        g.drawLine(cx - 7, cy, cx + 7, cy);
        g.drawLine(cx, cy - 7, cx, cy + 7);

        g.setColor(Color.BLACK);
        g.drawRect(5, 5, 430, 210);
        g.setColor(Color.WHITE);
        g.drawString(status, 12, 20);
        // NOCLIP indicator.
        if (cfg.noClip) {
            Color oldColor = g.getColor();
            g.setColor(new Color(140, 17, 17));
            g.drawString("NOCLIP", 12, 36);
            g.setColor(oldColor);
        }
        g.drawString("world tile=(" + (cam.worldX / 128) + "," + (cam.worldY / 128) + ") scene=("
                + cam.sceneX + "," + cam.sceneY + ")", 12, 36);
        g.drawString("yaw=" + cam.yaw + " pitch=" + cam.pitch + "  "
                + (cam.flyMode ? "FLY alt=" + (int) cam.flyY
                : "walk eyeY=" + cfg.eyeHeight + " jump=" + (int) cam.jumpY), 12, 52);
        String cur = scene.currentRegion != null ? scene.currentRegion.toString() : "none";
        g.drawString("Region: " + cur + "  (" + (scene.regionIndex + 1) + "/" + regions.size() + ")", 12, 68);
        g.drawString("Objects in view: " + scene.objectCount, 12, 84);
        g.drawString("WASD=move  Mouse=look" + (mouse.looking ? "" : " (click to capture)") + "  Shift=sprint", 12, 100);
        g.drawString("Space=jump/up  Ctrl=crouch/down  F=fly  PgUp/Dn=eye height", 12, 116);
        g.drawString("[ / ] = mouse sensitivity (" + String.format("%.2f", cfg.mouseSens) + ")", 12, 132);
        g.drawString("N/P=jump region  R=reset  O=object count  Esc=release mouse", 12, 148);

        if (sandbox.buildMode) {
            if (sandbox.objectIdList.length > 0) {
                g.setColor(new Color(255, 220, 120));
                g.drawString("BUILD  obj " + (sandbox.objectSel + 1) + "/" + sandbox.objectIdList.length + "  id="
                        + sandbox.objectIdList[sandbox.objectSel] + "  \"" + sandbox.ghostName + "\"", 12, 172);
                g.drawString("type=" + sandbox.ghostType + "  rot=" + sandbox.placeRotation + "  ghost="
                        + (sandbox.ghostEnabled ? "on" : "off")
                        + "  aim=(" + sandbox.aimWorldTileX + "," + sandbox.aimWorldTileY + ")"
                        + (sandbox.aimValid ? "" : " n/a")
                        + "  placed=" + sandbox.placedObjects.size(), 12, 188);
                g.drawString(",/. obj  </> +-10  ;/' rotate  Enter/click place  Bksp undo  Del clear", 12, 204);
                g.setColor(Color.WHITE);
            } else {
                g.drawString("BUILD mode: no objects loaded yet", 12, 172);
            }
        } else {
            g.drawString("B = sandbox build mode (browse & place objects)", 12, 172);
        }
        if (showObjCount) {
            g.drawString("Object counter overlay enabled", 12, 204);
        }
    }
}

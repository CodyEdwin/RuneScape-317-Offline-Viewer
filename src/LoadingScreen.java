import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;

/**
 * The animated loading screen, modelled on the original client's red progress
 * bar (RSApplet.drawLoadingText): black screen, a red-outlined bar filled red,
 * phase text in white, and a light band that sweeps across the fill.
 *
 * The loader thread publishes the progress target/message; the bar eases
 * toward it on paint so it glides between phases instead of jumping. During a
 * long phase it creeps slowly past the reported value so it never looks
 * frozen.
 */
final class LoadingScreen implements GameCache.Progress {

    private volatile int progress = 0;                  // 0..100
    private volatile String message = "Starting up";
    private double shown = 0;                           // eased, EDT only
    private long lastPaintNanos = 0;

    @Override
    public void report(int percent, String message) {
        if (percent > progress) progress = percent;
        this.message = message;
    }

    /** Forces the phase text to an error message (progress is left as-is). */
    void fail(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }

    void draw(Graphics g, int w, int h) {
        if (w <= 0) w = ViewerConfig.WIDTH;
        if (h <= 0) h = ViewerConfig.HEIGHT;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, w, h);

        // Ease the shown progress toward the reported value.
        long nowNanos = System.nanoTime();
        if (lastPaintNanos == 0) lastPaintNanos = nowNanos;
        double dt = Math.min(0.25, (nowNanos - lastPaintNanos) / 1.0e9);
        lastPaintNanos = nowNanos;
        double target = progress;
        if (shown < target) {
            shown = Math.min(target, shown + 55.0 * dt);
        } else if (shown < target + 9.0) {
            shown = Math.min(target + 9.0, shown + 0.7 * dt);
        }

        Color red = new Color(140, 17, 17);
        int cx = w / 2;
        int barW = 304, barH = 34;
        int bx = cx - barW / 2;
        int by = h / 2 - 4;

        // Title.
        g2.setFont(new Font("Helvetica", Font.BOLD, 16));
        g2.setColor(Color.WHITE);
        drawCentered(g2, "RuneScape is loading - please wait...", cx, by - 44);

        // Bar frame in red with a black interior, exactly like the client.
        g2.setColor(red);
        g2.drawRect(bx, by, barW, barH);
        g2.setColor(Color.BLACK);
        g2.fillRect(bx + 1, by + 1, barW - 1, barH - 1);

        int innerW = barW - 2;
        int fill = (int) Math.round(innerW * (shown / 100.0));
        if (fill > 0) {
            g2.setColor(red);
            g2.fillRect(bx + 1, by + 1, fill, barH - 1);

            // Light band sweeping left-to-right across the filled part.
            Shape oldClip = g2.getClip();
            g2.clipRect(bx + 1, by + 1, fill, barH - 1);
            int sweepW = 70;
            int sweepX = bx + 1 - sweepW
                    + (int) ((System.currentTimeMillis() / 4) % (fill + sweepW));
            g2.setPaint(new GradientPaint(
                    sweepX + 0f, 0f, new Color(255, 150, 150, 0),
                    sweepX + sweepW, 0f, new Color(255, 200, 200, 160)));
            g2.fillRect(sweepX, by + 1, sweepW, barH - 1);
            g2.setClip(oldClip);
        }

        // Phase text centred inside the bar (white), like the client.
        g2.setFont(new Font("Helvetica", Font.PLAIN, 13));
        g2.setColor(Color.WHITE);
        drawCentered(g2, message == null ? "" : message, cx, by + 22);

        // Percentage with animated dots below the bar.
        int dots = (int) ((System.currentTimeMillis() / 450) % 4);
        StringBuilder tail = new StringBuilder(" ").append((int) Math.round(shown)).append('%');
        for (int i = 0; i < dots; i++) tail.append('.');
        g2.setFont(new Font("Helvetica", Font.PLAIN, 12));
        g2.setColor(new Color(190, 190, 190));
        drawCentered(g2, tail.toString(), cx, by + barH + 24);

        g2.dispose();
    }

    private void drawCentered(Graphics2D g, String s, int cx, int y) {
        int tw = g.getFontMetrics().stringWidth(s);
        g.drawString(s, cx - tw / 2, y);
    }
}

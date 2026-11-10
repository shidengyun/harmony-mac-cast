import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/** A transparent phone window must present complete frames even without Swing's native backbuffer. */
public final class CompactPaintTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static BufferedImage frame(Color color) {
        BufferedImage image = new BufferedImage(450, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color); g.fillRect(0, 0, image.getWidth(), image.getHeight()); g.dispose();
        return image;
    }
    static void run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CastAppearance.installDefaults();
            CastAppearance.Window window = new CastAppearance.Window();
            RepaintManager manager = RepaintManager.currentManager(window);
            boolean buffered = manager.isDoubleBufferingEnabled();
            try {
                ((Timer) DeviceRefreshTest.field(window, "statusTimer")).stop();
                var display = CastAppearance.field(window, "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
                BufferedImage blue = frame(new Color(0x234567)), red = frame(new Color(0xA83456));
                display.setImage(blue);
                window.setVisible(true);
                ((JButton) SidebarLayoutTest.named(window.getContentPane(), "sidebarToggle")).doClick();
                window.validate();
                JPanel stage = (JPanel) SidebarLayoutTest.named(window.getContentPane(), "phoneStage");
                stage.doLayout();
                JPanel shell = (JPanel) window.getContentPane();
                // Translucent windows can paint without Swing's platform backbuffer; guarantee atomic presentation here too.
                manager.setDoubleBufferingEnabled(false);
                for (int scale : new int[]{1, 2}) {
                    BufferedImage front = new BufferedImage(shell.getWidth() * scale, shell.getHeight() * scale,
                        BufferedImage.TYPE_INT_ARGB);
                    Graphics2D screen = front.createGraphics(); screen.scale(scale, scale);
                    shell.paint(screen);
                    int x = front.getWidth() / 2, y = front.getHeight() / 2;
                    AtomicInteger partialFrames = new AtomicInteger();
                    AtomicInteger probes = new AtomicInteger();
                    int[] previous = {front.getRGB(x, y)};
                    JComponent probe = new JComponent() {
                        @Override protected void paintComponent(Graphics graphics) {
                            probes.incrementAndGet();
                            if (front.getRGB(x, y) != previous[0]) partialFrames.incrementAndGet();
                        }
                    };
                    probe.setBounds(20, 20, 1, 1);
                    stage.add(probe); // Paint before the phone child, after the stage background.
                    for (int i = 0; i < 30; i++) {
                        previous[0] = front.getRGB(x, y);
                        BufferedImage next = i % 2 == 0 ? red : blue;
                        display.setImage(next);
                        shell.paint(screen);
                        check(front.getRGB(x, y) == next.getRGB(0, 0), "Complete phone frame must be presented");
                        check((front.getRGB(0, 0) >>> 24) == 0, "Rounded compact corner must remain transparent");
                    }
                    stage.remove(probe); screen.dispose();
                    System.out.println("COMPACT scale=" + scale + " partialFrames=" + partialFrames.get());
                    check(probes.get() == 30, "Probe must observe every frame's composition");
                    check(partialFrames.get() == 0, "Phone repaint must not expose a cleared background before the new frame");
                }
                System.out.println("PASS: complete compact frames, no intermediate background, transparent corners at 1x/2x");
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { manager.setDoubleBufferingEnabled(buffered); window.dispose(); }
        });
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

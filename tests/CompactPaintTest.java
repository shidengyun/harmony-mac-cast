import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/** An opaque compact window must show phone pixels across its whole surface. */
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
                // Keep platform buffering enabled: composition must bypass it and restore it afterward.
                manager.setDoubleBufferingEnabled(true);
                for (int scale : new int[]{1, 2}) {
                    BufferedImage front = new BufferedImage(shell.getWidth() * scale, shell.getHeight() * scale,
                        BufferedImage.TYPE_INT_ARGB);
                    Graphics2D screen = front.createGraphics(); screen.scale(scale, scale);
                    shell.paint(screen);
                    int x = front.getWidth() / 2, y = front.getHeight() / 2;
                    for (int i = 0; i < 30; i++) {
                        BufferedImage next = i % 2 == 0 ? red : blue;
                        display.setImage(next);
                        shell.paint(screen);
                        check(manager.isDoubleBufferingEnabled(), "Restore Swing buffering after composition");
                        check(front.getRGB(x, y) == next.getRGB(0, 0), "Complete phone frame must be presented");
                        check(front.getRGB(0, 0) == next.getRGB(0, 0), "Opaque corner must show the phone frame");
                    }
                    screen.dispose();
                }
                System.out.println("PASS: complete opaque compact frames and corners at 1x/2x");
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { manager.setDoubleBufferingEnabled(buffered); window.dispose(); }
        });
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

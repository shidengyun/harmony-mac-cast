import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;

/** Integer scaling must not expose the black stage around a compact phone frame. */
public final class CompactBorderTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CastAppearance.installDefaults();
            CastAppearance.Window window = new CastAppearance.Window();
            try {
                Field timer = CastAppearance.Window.class.getDeclaredField("statusTimer");
                timer.setAccessible(true); ((Timer) timer.get(window)).stop();
                var display = CastAppearance.field(window, "multiFunctionLabel",
                    CastAppearance.RotatingMultiFunctionLabel.class);
                window.setVisible(true);
                JButton toggle = (JButton) SidebarLayoutTest.named(window.getContentPane(), "sidebarToggle");
                int color = 0xFFC0E0F0;
                for (int[] size : new int[][]{{1276, 2848}, {450, 1000}, {2848, 1276}}) {
                    BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                    Graphics2D source = image.createGraphics();
                    source.setColor(new Color(color)); source.fillRect(0, 0, size[0], size[1]); source.dispose();
                    display.setImage(image);
                    for (int height : new int[]{301, 479, 872}) {
                        window.setBounds(window.getX(), window.getY(), 780, height);
                        toggle.doClick();
                        window.validate();
                        Container shell = window.getContentPane();
                        shell.doLayout(); display.getParent().doLayout();
                        for (int scale : new int[]{1, 2}) {
                            BufferedImage painted = new BufferedImage(shell.getWidth() * scale,
                                shell.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D graphics = painted.createGraphics(); graphics.scale(scale, scale);
                            shell.paint(graphics); graphics.dispose();
                            int w = painted.getWidth(), h = painted.getHeight();
                            for (int y : new int[]{0, 1, h - 2, h - 1})
                                check(painted.getRGB(w / 2, y) == color,
                                    "Black horizontal edge: " + size[0] + "x" + size[1] + " height=" + height + " y=" + y);
                            for (int x : new int[]{0, 1, w - 2, w - 1})
                                check(painted.getRGB(x, h / 4) == color, "Black vertical edge at x=" + x);
                            check(painted.getRGB(0, 0) == color, "Opaque compact corners must show the phone frame");
                        }
                        toggle.doClick();
                    }
                }
                System.out.println("PASS: no compact black edges at three frame ratios, three heights and 1x/2x scales");
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { window.dispose(); }
        });
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

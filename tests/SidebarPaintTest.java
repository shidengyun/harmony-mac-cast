import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Exercise real Swing double-buffered painting; printAll hides this regression. */
public final class SidebarPaintTest {
    static Component named(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container) {
                Component result = named((Container) component, name);
                if (result != null) return result;
            }
        }
        return null;
    }
    static boolean sameColor(int first, int second) {
        for (int shift : new int[]{0, 8, 16})
            if (Math.abs(((first >> shift) & 255) - ((second >> shift) & 255)) > 5) return false;
        return true;
    }
    public static void main(String[] args) {
        CastAppearance.Window[] window = {null};
        CastAppearance.RotatingMultiFunctionLabel[] display = {null};
        try {
            Path output = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            BufferedImage frame = new BufferedImage(450, 1000, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = frame.createGraphics();
            g.setColor(new Color(0x234567)); g.fillRect(0, 0, 450, 1000); g.dispose();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    CastAppearance.installDefaults();
                    window[0] = new CastAppearance.Window();
                    Field timer = CastAppearance.Window.class.getDeclaredField("statusTimer");
                    timer.setAccessible(true); ((Timer) timer.get(window[0])).stop();
                    display[0] = CastAppearance.field(window[0], "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
                    display[0].setImage(frame);
                    window[0].setTitle("SidebarPaintTest"); window[0].setLocation(80, 100);
                    window[0].setAlwaysOnTop(true); window[0].setVisible(true);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Thread.sleep(250);
            for (int i = 0; i < 3; i++) {
                SwingUtilities.invokeAndWait(() -> ((JButton) named(window[0].getContentPane(), "sidebarToggle")).doClick());
                Thread.sleep(200);
            }
            for (int i = 0; i < 20; i++) {
                SwingUtilities.invokeAndWait(() -> { display[0].setImage(frame); window[0].repaint(); });
                Thread.sleep(20);
            }
            Process lookup = new ProcessBuilder("swift", "-").start();
            String swift = "import CoreGraphics\n"
                + "if let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]], "
                + "let window = windows.first(where: { ($0[kCGWindowName as String] as? String) == \"SidebarPaintTest\" }), "
                + "let id = window[kCGWindowNumber as String] as? Int { print(id) }\n";
            lookup.getOutputStream().write(swift.getBytes(StandardCharsets.UTF_8)); lookup.getOutputStream().close();
            String id = new String(lookup.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (lookup.waitFor() != 0 || !id.matches("[0-9]+")) throw new AssertionError("Missing native window ID");
            Process capture = new ProcessBuilder("screencapture", "-x", "-o", "-l", id, output.toString()).inheritIO().start();
            if (capture.waitFor() != 0) throw new AssertionError("Native screenshot failed");
            BufferedImage screenshot = javax.imageio.ImageIO.read(output.toFile());
            double scale = (double) screenshot.getWidth() / window[0].getWidth();
            int middleY = (int) ((window[0].getInsets().top + window[0].getContentPane().getHeight() / 2.0) * scale);
            int center = screenshot.getRGB(screenshot.getWidth() / 2, middleY);
            boolean rightArrow = false;
            for (int y = middleY - (int) (7 * scale); y <= middleY + (int) (7 * scale); y++) {
                for (int x = (int) (4 * scale); x < (int) (14 * scale); x++)
                    if (!sameColor(screenshot.getRGB(x, y), center)) throw new AssertionError("Unexpected left arrow or black gutter");
                for (int x = screenshot.getWidth() - (int) (14 * scale); x < screenshot.getWidth() - (int) (2 * scale); x++)
                    rightArrow |= !sameColor(screenshot.getRGB(x, y), center);
            }
            if (!rightArrow) throw new AssertionError("Right expand arrow missing");
            System.out.println("PASS: real buffered repaint shows only the right arrow; left edge keeps phone pixels");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        finally {
            try { SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); }); }
            catch (Exception error) { error.printStackTrace(); }
        }
        System.exit(0);
    }
}

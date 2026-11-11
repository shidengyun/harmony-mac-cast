import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Capture repeated native presentations: a one-shot or offscreen test misses transparent frames.
 * Run with the packaged JDK and -Dsun.java2d.metal=true -Dsun.java2d.opengl=false. */
public final class NativeFlickerTest {
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
                    // Start compact before the first phone frame, as in the live app.
                    window[0].setTitle("NativeFlickerTest"); window[0].setLocation(80, 100);
                    window[0].setAlwaysOnTop(true); window[0].setVisible(true);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Thread.sleep(250);
            for (int i = 0; i < 3; i++) {
                SwingUtilities.invokeAndWait(() -> ((JButton) named(window[0].getContentPane(), "sidebarToggle")).doClick());
                Thread.sleep(200);
            }
            for (int i = 0; i < 20; i++) {
                SwingUtilities.invokeAndWait(() -> display[0].setImage(frame));
                Thread.sleep(20);
            }
            SwingUtilities.invokeAndWait(() -> {
                // AWT can refresh the native background after setting up its drawing surface.
                window[0].setBackground(Color.WHITE);
                window[0].setBackground(Color.BLACK);
            });
            Thread.sleep(500);
            Process lookup = new ProcessBuilder("swift", "-").start();
            String swift = "import CoreGraphics\n"
                + "if let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]], "
                + "let window = windows.first(where: { ($0[kCGWindowOwnerPID as String] as? Int) == " + ProcessHandle.current().pid() + " }), "
                + "let id = window[kCGWindowNumber as String] as? Int { print(id) }\n";
            lookup.getOutputStream().write(swift.getBytes(StandardCharsets.UTF_8)); lookup.getOutputStream().close();
            String id = new String(lookup.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (lookup.waitFor() != 0 || !id.matches("[0-9]+")) throw new AssertionError("Missing native window ID");
            Timer[] stream = {null};
            SwingUtilities.invokeAndWait(() -> { stream[0] = new Timer(200, e -> display[0].setImage(frame)); stream[0].start(); });
            if (window[0].getBackground().getAlpha() != 255) throw new AssertionError("AWT surface must remain opaque");
            int blanks = 0;
            for (int i = 0; i < (args.length > 1 ? Integer.parseInt(args[1]) : 100); i++) {
                Process capture = new ProcessBuilder("screencapture", "-x", "-l", id, output.toString()).start();
                if (capture.waitFor() != 0) throw new AssertionError("Native screenshot failed");
                BufferedImage sample = javax.imageio.ImageIO.read(output.toFile());
                if ((sample.getRGB(sample.getWidth()/2, sample.getHeight()/2) >>> 24) != 255) blanks++;
            }
            SwingUtilities.invokeAndWait(() -> stream[0].stop());
            System.out.println("NATIVE_BLANKS=" + blanks);
            if (blanks > 0) throw new AssertionError("Native transparent frames: " + blanks);
            BufferedImage screenshot = javax.imageio.ImageIO.read(output.toFile());
            double nativeScale = window[0].getGraphicsConfiguration().getDefaultTransform().getScaleX();
            if (screenshot.getWidth() != Math.round(window[0].getWidth() * nativeScale)
                    || screenshot.getHeight() != Math.round(window[0].getHeight() * nativeScale))
                throw new AssertionError("Compact native window must have no surrounding shadow");
            for (int x : new int[]{0, screenshot.getWidth() - 1}) {
                for (int y : new int[]{0, screenshot.getHeight() - 1}) {
                    if ((screenshot.getRGB(x, y) >>> 24) != 0)
                        throw new AssertionError("Native rounded corners must reveal the desktop");
                }
            }
            double scale = (double) screenshot.getWidth() / window[0].getWidth();
            int middleY = (int) ((window[0].getInsets().top + window[0].getContentPane().getHeight() / 2.0) * scale);
            int center = screenshot.getRGB(screenshot.getWidth() / 2, middleY);
            if ((center >>> 24) != 255) throw new AssertionError("Phone pixels must remain opaque");
            for (int y : new int[]{0, screenshot.getHeight() - 1})
                if (!sameColor(screenshot.getRGB(screenshot.getWidth() / 2, y), center))
                    throw new AssertionError("Black gutter along compact phone top or bottom");
            boolean rightArrow = false;
            for (int y = middleY - (int) (7 * scale); y <= middleY + (int) (7 * scale); y++) {
                for (int x = (int) (4 * scale); x < (int) (14 * scale); x++)
                    if (!sameColor(screenshot.getRGB(x, y), center)) throw new AssertionError("Unexpected left arrow or black gutter");
                for (int x = screenshot.getWidth() - (int) (14 * scale); x < screenshot.getWidth() - (int) (2 * scale); x++)
                    rightArrow |= !sameColor(screenshot.getRGB(x, y), center);
            }
            if (!rightArrow) throw new AssertionError("Right expand arrow missing");
            System.out.println("PASS: native compact window has no black gutters or shadow, native transparent corners and only the right arrow");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        finally {
            try { SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); }); }
            catch (Exception error) { error.printStackTrace(); }
        }
        System.exit(0);
    }
}

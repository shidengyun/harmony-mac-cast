import com.huawei.hosscrcpy.api.HosRemoteDevice;
import utils.swing.MultiFunctionLabel;
import javax.swing.*;
import java.lang.reflect.Field;

/** Live native rounding regression. Set CAST_HDC_PATH and put its directory on PATH. */
public final class LiveCornersTest {
    static Object session(CastAppearance.Window window) throws Exception {
        Field field = CastAppearance.Window.class.getDeclaredField("captureSession");
        field.setAccessible(true); return field.get(window);
    }
    static long token(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("token");
        field.setAccessible(true); return field.getLong(session);
    }
    static void run(String sn, String argsOutput) throws Exception {
        CastAppearance.Window[] window = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.installDefaults();
                window[0] = new CastAppearance.Window();
                window[0].setVisible(true);
            });
            Thread.sleep(1200);
            SwingUtilities.invokeAndWait(() -> {
                @SuppressWarnings("unchecked") JComboBox<utils.entity.Device> devices =
                    (JComboBox<utils.entity.Device>) CastAppearance.field(window[0], "cmbSn", JComboBox.class);
                devices.removeAllItems(); devices.addItem(new utils.entity.Device(sn));
                CastAppearance.field(window[0], "btnEnterScreenCapture", JButton.class).doClick();
            });
            MultiFunctionLabel display = CastAppearance.field(window[0], "multiFunctionLabel", MultiFunctionLabel.class);
            for (int i = 0; i < 120 && display.getImage() == null; i++) Thread.sleep(250);
            if (display.getImage() == null) throw new AssertionError("No live frame");
            long first = token(session(window[0]));
            System.out.println("LIVE_FRAME=" + display.getImage().getWidth() + "x" + display.getImage().getHeight());
            SwingUtilities.invokeAndWait(() -> ((JButton) SidebarLayoutTest.named(window[0].getContentPane(), "sidebarToggle")).doClick());
            System.out.println("LIVE_PID=" + ProcessHandle.current().pid());
            Thread.sleep(1500);
            Process lookup = new ProcessBuilder("swift", "-").start();
            String swift = "import CoreGraphics\nif let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String:Any]], let w = windows.first(where: { ($0[kCGWindowOwnerPID as String] as? Int) == " + ProcessHandle.current().pid() + " }), let id = w[kCGWindowNumber as String] as? Int { print(id) }\n";
            lookup.getOutputStream().write(swift.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            lookup.getOutputStream().close();
            String id = new String(lookup.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (lookup.waitFor() != 0 || !id.matches("[0-9]+")) throw new AssertionError("Missing native window ID");
            java.nio.file.Path output = java.nio.file.Path.of(argsOutput).toAbsolutePath();
            java.nio.file.Files.createDirectories(output.getParent());
            for (int i = 0; i < 60; i++) {
                Process capture = new ProcessBuilder("screencapture", "-x", "-l", id, output.toString()).start();
                if (capture.waitFor() != 0) throw new AssertionError("Native screenshot failed");
                var image = javax.imageio.ImageIO.read(output.toFile());
                if ((image.getRGB(image.getWidth()/2, image.getHeight()/2) >>> 24) != 255)
                    throw new AssertionError("Phone frame disappeared at sample " + i);
                for (int x : new int[]{0, image.getWidth()-1})
                    for (int y : new int[]{0, image.getHeight()-1})
                        if ((image.getRGB(x,y) >>> 24) != 0) throw new AssertionError("Native corner lost at sample " + i);
                if (window[0].getBackground().getAlpha() != 255) throw new AssertionError("AWT must stay opaque");
            }
            System.out.println("PASS: 60 live native screenshots retain rounded corners and opaque phone pixels");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); });
        }
    }
    public static void main(String[] args) {
        try { run(args[0], args.length > 1 ? args[1] : "build/live-corners.png"); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

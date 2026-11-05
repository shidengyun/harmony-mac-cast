import com.huawei.hosscrcpy.api.HosRemoteDevice;
import utils.swing.MultiFunctionLabel;
import javax.swing.*;
import java.lang.reflect.Field;

/** Live USB test: stable idle screen, then stop this viewer's capture service and recover. */
public final class CaptureLiveTest {
    static Object session(CastAppearance.Window window) throws Exception {
        Field field = CastAppearance.Window.class.getDeclaredField("captureSession");
        field.setAccessible(true); return field.get(window);
    }
    static long token(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("token");
        field.setAccessible(true); return field.getLong(session);
    }
    static void run(String sn) throws Exception {
        CastAppearance.Window[] window = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.installDefaults();
                window[0] = new CastAppearance.Window();
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
            Thread.sleep(20000);
            if (token(session(window[0])) != first) throw new AssertionError("Unexpected restart during idle screen");
            System.out.println("PASS: live idle screen stays in the same capture session");
            Object old = session(window[0]);
            Field remoteField = old.getClass().getDeclaredField("remote");
            remoteField.setAccessible(true);
            ((HosRemoteDevice) remoteField.get(old)).stopCaptureScreen();
            System.out.println("Stopped this test session's capture service; waiting for recovery");
            boolean recovered = false;
            for (int i = 0; i < 160; i++) {
                Thread.sleep(250);
                Object current = session(window[0]);
                if (current != null && token(current) > first && display.getImage() != null && display.isScreenCapturingMode()) {
                    HosRemoteDevice remote = (HosRemoteDevice) remoteField.get(current);
                    if (!remote.getSn().equals(sn)) throw new AssertionError("Recovered a different phone");
                    recovered = true; break;
                }
            }
            if (!recovered) throw new AssertionError("Live capture service failed to recover");
            SwingUtilities.invokeAndWait(() -> CastAppearance.field(window[0], "btnEnterScreenCapture", JButton.class).doClick());
            Thread.sleep(4500);
            if (session(window[0]) != null) throw new AssertionError("Stopped session revived");
            System.out.println("PASS: live capture interruption recovers the same phone; manual stop stays stopped");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); });
        }
    }
    public static void main(String[] args) {
        try { run(args[0]); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with a known disconnected device serial to verify real UI responsiveness. */
public final class CaptureWindowTest {
    static JButton findStop(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && ((JButton) component).getText().equals("停止投屏")
                    && component.isShowing()) return (JButton) component;
            if (component instanceof Container) {
                JButton found = findStop((Container) component);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void run(String[] args) throws Exception {
        CastAppearance.Window[] window = {null};
        AtomicInteger beats = new AtomicInteger();
        Timer[] heartbeat = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.installDefaults();
                window[0] = new CastAppearance.Window();
                window[0].setVisible(true);
                heartbeat[0] = new Timer(100, event -> beats.incrementAndGet());
                heartbeat[0].start();
            });
            Thread.sleep(1500);
            SwingUtilities.invokeAndWait(() -> {
                @SuppressWarnings("unchecked") JComboBox<utils.entity.Device> devices =
                    (JComboBox<utils.entity.Device>) CastAppearance.field(window[0], "cmbSn", JComboBox.class);
                devices.removeAllItems(); devices.addItem(new utils.entity.Device(args[0]));
                CastAppearance.field(window[0], "btnEnterScreenCapture", JButton.class).doClick();
            });
            Thread.sleep(10500);
            Field field = CastAppearance.Window.class.getDeclaredField("recovery");
            field.setAccessible(true);
            CaptureRecovery recovery = (CaptureRecovery) field.get(window[0]);
            if (recovery.state() != CaptureRecovery.State.OFFLINE) throw new AssertionError("Expected offline: " + recovery.state());
            if (beats.get() < 60) throw new AssertionError("EDT stalled during disconnected capture");
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.field(window[0], "cmbSn", JComboBox.class).removeAllItems();
            });
            Thread.sleep(600);
            SwingUtilities.invokeAndWait(() -> {
                JButton stop = findStop(window[0]);
                if (stop == null || !stop.isEnabled()) throw new AssertionError("Stop unavailable without devices");
                stop.doClick();
            });
            Thread.sleep(4500);
            if (recovery.active() || recovery.state() != CaptureRecovery.State.IDLE) throw new AssertionError("Stopped session revived");
            System.out.println("PASS: real window stays responsive offline; stop works with empty device list; stopped session stays stopped");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (heartbeat[0] != null) heartbeat[0].stop();
                if (window[0] != null) window[0].dispose();
            });
        }
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

}

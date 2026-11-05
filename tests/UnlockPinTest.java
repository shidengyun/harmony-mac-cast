import com.huawei.hosscrcpy.api.HosRemoteConfig;
import com.huawei.hosscrcpy.api.HosRemoteDevice;
import java.awt.Point;
import java.lang.reflect.*;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

/** Never sends a real password or touches a real phone. */
public final class UnlockPinTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Device extends HosRemoteDevice {
        final java.util.List<String> commands = new ArrayList<>();
        String reply = "No Error";
        Device() { super(new HosRemoteConfig("unlock-test")); }
        @Override public String executeShellCommand(String command, int timeout) {
            commands.add(command); return reply;
        }
    }
    static void send(Device device, Map<String, Point> keys, String pin) throws Exception {
        Method method = CastAppearance.class.getDeclaredMethod("unlockWithPin", HosRemoteDevice.class, Map.class, String.class);
        method.setAccessible(true);
        try { method.invoke(null, device, keys, pin); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }
    static void run() throws Exception {
        Map<String, Point> keys = new HashMap<>();
        for (int i = 0; i < 10; i++) keys.put("" + i, new Point(100 + i * 10, 2000));
        Device device = new Device();
        send(device, keys, "112039");
        check(device.commands.size() == 6, "all six digits including repeats and zero must be sent");
        check(device.commands.get(0).equals(device.commands.get(1)), "repeated digit must be tapped twice");
        keys.remove("3"); device.commands.clear();
        boolean rejected = false;
        try { send(device, keys, "112039"); } catch (Exception expected) { rejected = true; }
        check(rejected && device.commands.isEmpty(), "missing coordinates must reject the whole PIN before sending any digits");
        keys.put("3", new Point(130, 2000)); device.reply = "[Fail] Error: touch rejected";
        rejected = false;
        try { send(device, keys, "112039"); } catch (Exception expected) { rejected = true; }
        check(rejected && device.commands.size() == 1, "failed command must stop input immediately");
        device.reply = "No Error"; device.commands.clear();
        Method cancellable = CastAppearance.class.getDeclaredMethod("unlockWithPin", HosRemoteDevice.class,
            Map.class, String.class, java.util.function.BooleanSupplier.class);
        cancellable.setAccessible(true);
        try {
            cancellable.invoke(null, device, keys, "112039",
                (java.util.function.BooleanSupplier) () -> device.commands.size() < 2);
            throw new AssertionError("cancelled session accepted");
        } catch (InvocationTargetException expected) {
            check(device.commands.size() == 2, "session change must cancel remaining digits");
        }
        testTimer();
        testProtectedKeypad();
        System.out.println("PASS: six digits, repeated digits, zero, missing-key preflight, rejected-click stops input");
    }
    static JButton unlockButton(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton && "解锁".equals(child.getAccessibleContext().getAccessibleName()))
                return (JButton) child;
            if (child instanceof Container) {
                JButton found = unlockButton((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }
    static void testTimer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CastAppearance.installDefaults();
            CastAppearance.Window window = new CastAppearance.Window();
            try {
                Field recoveryField = CastAppearance.Window.class.getDeclaredField("recovery");
                recoveryField.setAccessible(true);
                CaptureRecovery recovery = new CaptureRecovery(new CaptureRecovery.Backend() {
                    public CaptureRecovery.Health probe(CaptureRecovery.Target target, long token, boolean check) {
                        return CaptureRecovery.Health.HEALTHY;
                    }
                    public void restart(CaptureRecovery.Target target, long token) { }
                }, Runnable::run);
                ((CaptureRecovery) recoveryField.get(window)).close();
                recoveryField.set(window, recovery);
                long token = recovery.start(new CaptureRecovery.Target("fake", "127.0.0.1"), CaptureRecovery.now());
                recovery.frame(token, CaptureRecovery.now());
                Field busy = CastAppearance.Window.class.getDeclaredField("unlockBusy"); busy.setAccessible(true);
                Field timerField = CastAppearance.Window.class.getDeclaredField("statusTimer"); timerField.setAccessible(true);
                javax.swing.Timer timer = (javax.swing.Timer) timerField.get(window); timer.stop();
                JButton unlock = unlockButton(window);
                check(unlock != null, "unlock button missing");
                busy.setBoolean(window, true); unlock.setEnabled(false);
                for (var action : timer.getActionListeners()) action.actionPerformed(null);
                check(!unlock.isEnabled(), "status timer must not re-enable unlock while input is pending");
                busy.setBoolean(window, false);
                for (var action : timer.getActionListeners()) action.actionPerformed(null);
                check(unlock.isEnabled(), "unlock button must recover after completion");
                recovery.close();
                System.out.println("PASS: real Swing status timer preserves unlock busy state and restores button afterward");
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { window.dispose(); }
        });
    }
    @SuppressWarnings("unchecked")
    static void testProtectedKeypad() throws Exception {
        Method find = CastAppearance.class.getDeclaredMethod("findPinKeypad", String.class); find.setAccessible(true);
        for (int scale : new int[]{1, 2}) {
            StringBuilder children = new StringBuilder();
            for (int row = 0; row < 4; row++) for (int col = 0; col < 3; col++) {
                int x = (183 + col * 336) * scale, y = (1350 + row * 308) * scale, side = 238 * scale;
                if (!children.isEmpty()) children.append(',');
                children.append("{\"attributes\":{\"type\":\"Stack\",\"clickable\":\"true\",\"bounds\":\"[")
                    .append(x).append(',').append(y).append("][").append(x + side).append(',').append(y + side)
                    .append("]\"},\"children\":[{\"attributes\":{\"id\":\"numKeyBoard\"}}]}");
            }
            String json = "{\"attributes\":{\"id\":\"Digital_PSD_Input_Tip\"},\"children\":[{\"attributes\":{\"type\":\"RelativeContainer\"},\"children\":[" + children + "]}]}";
            Map<String, Point> keys = (Map<String, Point>) find.invoke(null, json);
            check(keys.size() == 10, "redacted digit text must not prevent keypad detection");
            check(keys.get("1").equals(new Point(302 * scale, 1469 * scale)), "first row must use real bounds above screen midpoint");
            check(keys.get("8").equals(new Point(638 * scale, 2085 * scale)), "eight must use the third row");
            check(keys.get("0").equals(new Point(638 * scale, 2393 * scale)), "zero must use bottom center, excluding delete");
            String unrelated = json.replace("Digital_PSD_Input_Tip", "ordinary-app");
            check(((Map<?, ?>) find.invoke(null, unrelated)).isEmpty(), "ordinary grids must not be mistaken for a password keypad");
        }
        System.out.println("PASS: redacted HarmonyOS keypad, actual row/column bounds, two sizes, delete excluded, unrelated grid rejected");
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}

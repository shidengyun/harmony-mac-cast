import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.api.ScreenCapCallback;
import utils.swing.MultiFunctionLabel;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Verify real frame callbacks remember only successful, current Wi-Fi sessions. */
public final class WirelessMemoryTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static Field member(String name) throws Exception {
        Field field = CastAppearance.Window.class.getDeclaredField(name);
        field.setAccessible(true); return field;
    }
    static ScreenCapCallback callback(CastAppearance.Window window, CaptureRecovery recovery,
                                     CaptureRecovery.Target target) throws Exception {
        long token = recovery.start(target, CaptureRecovery.now());
        Class<?> sessionType = Class.forName("CastAppearance$CaptureSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(long.class, HosRemoteDevice.class, CaptureRecovery.Target.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(token, null, target);
        Method callback = CastAppearance.class.getDeclaredMethod("sessionCallback", CastAppearance.Window.class,
            sessionType, MultiFunctionLabel.class, boolean.class);
        callback.setAccessible(true);
        return (ScreenCapCallback) callback.invoke(null, window, session,
            CastAppearance.field(window, "multiFunctionLabel", MultiFunctionLabel.class), true);
    }
    static void drain(CastAppearance.Window window) throws Exception {
        ((ExecutorService) member("captureTasks").get(window)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    public static void main(String[] args) {
        CastAppearance.Window[] window = {null};
        try {
            Path directory = Path.of(args[0]).toAbsolutePath();
            check(directory.equals(Path.of(System.getenv("CAST_STATE_DIR")).toAbsolutePath()), "test must use isolated state directory");
            Path remembered = directory.resolve("wireless-device.txt");
            Files.createDirectories(directory);
            Files.writeString(remembered, "192.0.2.24:8710\n");
            CaptureRecovery recovery = new CaptureRecovery(new CaptureRecovery.Backend() {
                public CaptureRecovery.Health probe(CaptureRecovery.Target target, long token, boolean service) {
                    return CaptureRecovery.Health.HEALTHY;
                }
                public void restart(CaptureRecovery.Target target, long token) { }
            }, Runnable::run);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    CastAppearance.installDefaults();
                    window[0] = new CastAppearance.Window();
                    ((Timer) member("statusTimer").get(window[0])).stop();
                    ((CaptureRecovery) member("recovery").get(window[0])).close();
                    member("recovery").set(window[0], recovery);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "png", encoded);
            byte[] frame = encoded.toByteArray();
            ScreenCapCallback first = callback(window[0], recovery, new CaptureRecovery.Target("192.0.2.25:8710", "127.0.0.1"));
            first.onData(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            drain(window[0]);
            check(Files.readString(remembered).trim().equals("192.0.2.24:8710"), "no decoded frame must preserve previous phone");
            first.onData(ByteBuffer.wrap(frame)); drain(window[0]);
            check(Files.readString(remembered).trim().equals("192.0.2.25:8710"), "successful Wi-Fi frame must be remembered");
            ScreenCapCallback stale = callback(window[0], recovery, new CaptureRecovery.Target("192.0.2.27:8710", "127.0.0.1"));
            ScreenCapCallback second = callback(window[0], recovery, new CaptureRecovery.Target("192.0.2.26:8710", "127.0.0.1"));
            second.onData(ByteBuffer.wrap(frame)); drain(window[0]);
            stale.onData(ByteBuffer.wrap(frame)); drain(window[0]);
            first.onData(ByteBuffer.wrap(frame)); drain(window[0]);
            check(Files.readString(remembered).trim().equals("192.0.2.26:8710"), "switch must remember newest phone; stale callbacks must not overwrite");
            ScreenCapCallback usb = callback(window[0], recovery, new CaptureRecovery.Target("USB-TEST", "127.0.0.1"));
            usb.onData(ByteBuffer.wrap(frame)); drain(window[0]);
            CastAppearance.rememberWirelessTarget(new CaptureRecovery.Target("999.0.2.27:8710", "127.0.0.1"));
            CastAppearance.rememberWirelessTarget(new CaptureRecovery.Target("192.0.2.27:8710", "192.0.2.99"));
            check(Files.readString(remembered).trim().equals("192.0.2.26:8710"), "USB, invalid address and remote hdc server must preserve Wi-Fi memory");
            System.out.println("PASS: successful frame persistence, switching Wi-Fi phones, stale callbacks, failed frames and USB preserve memory");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        finally {
            try { SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); }); }
            catch (Exception error) { error.printStackTrace(); }
        }
        System.exit(0);
    }
}

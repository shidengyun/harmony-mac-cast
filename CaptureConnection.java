import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.protocol.Scrcpy;
import com.huawei.hosscrcpy.protocol.ScrcpyServiceGrpc;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.ClientCalls;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.regex.Pattern;
import java.nio.file.*;
import java.io.IOException;
import utils.entity.Device;

/** Bounded transport and capture-service checks; no input is injected into the phone. */
final class CaptureConnection {
    private final String hdc;
    private final Map<String, Long> wirelessAttempts = new HashMap<>();
    private final Map<String, String> usbWireless = new ConcurrentHashMap<>();
    private volatile Map<String, String> connectedUsb = Map.of();
    private final Set<String> configuredWireless = ConcurrentHashMap.newKeySet();
    private final Path stateDirectory;
    private final java.util.function.Predicate<String> wirelessPortReady;
    CaptureConnection(String hdc) {
        this(hdc, Path.of(System.getenv("CAST_STATE_DIR") == null
            ? System.getProperty("user.dir") : System.getenv("CAST_STATE_DIR")));
    }
    CaptureConnection(String hdc, Path stateDirectory) {
        this(hdc, stateDirectory, CaptureConnection::wirelessPortReady);
    }
    CaptureConnection(String hdc, Path stateDirectory, java.util.function.Predicate<String> wirelessPortReady) {
        this.hdc = hdc;
        this.wirelessPortReady = wirelessPortReady;
        this.stateDirectory = stateDirectory.toAbsolutePath();
        Path saved = this.stateDirectory.resolve("usb-wireless-devices.properties");
        if (Files.exists(saved)) {
            try (var reader = Files.newBufferedReader(saved)) {
                Properties values = new Properties();
                values.load(reader);
                for (String serial : values.stringPropertyNames()) {
                    String address = values.getProperty(serial);
                    if (validWirelessAddress(address)) usbWireless.put(serial, address);
                }
            } catch (IOException | IllegalArgumentException error) {
                System.err.println("读取 USB 手机无线地址失败: " + error.getClass().getSimpleName());
            }
        }
    }

    /** Discover USB/Wi-Fi targets and keep USB phones' WLAN addresses selectable after unplugging. */
    List<Device> discoverLocalDevices() throws Exception {
        Map<String, Device> found = new LinkedHashMap<>();
        Map<String, String> liveUsb = new HashMap<>();
        boolean changed = false;
        for (String line : run("-s", "127.0.0.1:8710", "list", "targets", "-v").split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !parts[2].equals("Connected")) continue;
            String serial = parts[0];
            found.put(serial, new Device(serial));
            if (!parts[1].equals("USB")) continue;
            String address;
            try { address = wlanAddress(run("-s", "127.0.0.1:8710", "-t", serial, "shell", "ifconfig", "wlan0")); }
            catch (Exception error) { continue; }
            if (address == null) continue;
            liveUsb.put(address, serial);
            try { prepareWireless(serial, address); }
            catch (Exception error) { System.err.println("准备手机无线调试失败: " + error.getClass().getSimpleName()); }
            String previous = usbWireless.put(serial, address);
            changed |= !address.equals(previous);
        }
        connectedUsb = Map.copyOf(liveUsb);
        configuredWireless.removeIf(key -> liveUsb.entrySet().stream()
            .noneMatch(entry -> key.equals(entry.getValue() + "|" + entry.getKey())));
        if (changed) {
            try { saveUsbWireless(); }
            catch (IOException error) { System.err.println("保存 USB 手机无线地址失败: " + error.getClass().getSimpleName()); }
        }
        // Keep remembered addresses visible even before their TCP connection is established.
        for (String address : new java.util.TreeSet<>(usbWireless.values()))
            found.putIfAbsent(address, new Device(address));
        Path remembered = stateDirectory.resolve("wireless-device.txt");
        if (Files.exists(remembered)) {
            try {
                String address = Files.readString(remembered).trim();
                if (validWirelessAddress(address)) found.putIfAbsent(address, new Device(address));
            } catch (IOException error) { /* Device discovery can continue without this file. */ }
        }
        return new ArrayList<>(found.values());
    }

    static String wlanAddress(String output) {
        var addresses = Pattern.compile("\\binet\\s+(?:addr:)?((?:[0-9]{1,3}\\.){3}[0-9]{1,3})\\b").matcher(output);
        while (addresses.find()) {
            String ip = addresses.group(1);
            String address = ip + ":8710";
            if (validWirelessAddress(address) && !ip.startsWith("127.") && !ip.startsWith("169.254.")
                    && !ip.equals("0.0.0.0")) return address;
        }
        return null;
    }
    static boolean validWirelessAddress(String address) {
        if (address == null || !address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:8710")) return false;
        for (String octet : address.substring(0, address.indexOf(':')).split("\\."))
            if (Integer.parseInt(octet) > 255) return false;
        return true;
    }
    private void saveUsbWireless() throws IOException {
        Files.createDirectories(stateDirectory);
        Properties saved = new Properties();
        saved.putAll(usbWireless);
        Path temporary = Files.createTempFile(stateDirectory, "usb-wireless-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { saved.store(writer, "HarmonyCast USB WLAN addresses"); }
            Path destination = stateDirectory.resolve("usb-wireless-devices.properties");
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private void prepareWireless(String usb, String address) throws Exception {
        String key = usb + "|" + address;
        if (!configuredWireless.add(key)) return;
        try {
            if (wirelessPortReady.test(address)) return;
            String result = run("-s", "127.0.0.1:8710", "-t", usb, "tmode", "port", "8710");
            if (result.isBlank() || result.contains("[Fail]") || result.contains("Error")) configuredWireless.remove(key);
        } catch (Exception error) { configuredWireless.remove(key); throw error; }
    }
    private static boolean wirelessPortReady(String address) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(address.substring(0, address.indexOf(':')), 8710), 500);
            return true;
        } catch (IOException error) { return false; }
    }

    CaptureRecovery.Health probe(CaptureRecovery.Target target, HosRemoteDevice device,
                                  boolean imageMode, boolean checkService) throws Exception {
        if (!transport(target)) {
            long now = CaptureRecovery.now();
            if (target.sn.matches("[0-9.]+:[0-9]+")
                    && claimWirelessAttempt(target.sn, now)) {
                String usb = "127.0.0.1".equals(target.ip) ? connectedUsb.get(target.sn) : null;
                if (usb != null) prepareWireless(usb, target.sn);
                String listed = run("-s", target.ip + ":8710", "list", "targets", "-v");
                for (String line : listed.split("\\R")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3 && parts[0].equals(target.sn) && parts[2].equals("Offline")) {
                        run("-s", target.ip + ":8710", "tconn", target.sn, "-remove");
                        break;
                    }
                }
                run("-s", target.ip + ":8710", "tconn", target.sn);
                if (!transport(target)) return CaptureRecovery.Health.TRANSPORT_DOWN;
            } else return CaptureRecovery.Health.TRANSPORT_DOWN;
        }
        if (!checkService || imageMode || device == null) return CaptureRecovery.Health.TRANSPORT_ONLY;
        return keyFrame(device) ? CaptureRecovery.Health.HEALTHY : CaptureRecovery.Health.SERVICE_DOWN;
    }
    private synchronized boolean claimWirelessAttempt(String sn, long now) {
        if (now - wirelessAttempts.getOrDefault(sn, 0L) < 20000) return false;
        wirelessAttempts.put(sn, now);
        return true;
    }
    private boolean transport(CaptureRecovery.Target target) throws Exception {
        String output = run("-s", target.ip + ":8710", "-t", target.sn,
            "shell", "echo", "HARMONY_CAST_ALIVE");
        return output.lines().anyMatch(line -> line.trim().equals("HARMONY_CAST_ALIVE"));
    }
    private String run(String... arguments) throws Exception {
        if (hdc == null) return "";
        String[] command = new String[arguments.length + 1];
        command[0] = hdc;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
    static boolean keyFrame(HosRemoteDevice device) {
        try {
            // Use a per-call deadline, preserving the SDK's long-lived streaming call options.
            for (Field field : HosRemoteDevice.class.getDeclaredFields()) {
                if (!AbstractStub.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                AbstractStub<?> stub = (AbstractStub<?>) field.get(device);
                if (stub == null) return false;
                ClientCalls.blockingUnaryCall(stub.getChannel(), ScrcpyServiceGrpc.c(),
                    stub.getCallOptions().withDeadlineAfter(2, TimeUnit.SECONDS), Scrcpy.Empty.getDefaultInstance());
                return true;
            }
        } catch (Exception error) {
            System.err.println("采集服务检查失败: " + error.getClass().getSimpleName());
        }
        return false;
    }
}

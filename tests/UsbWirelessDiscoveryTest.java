import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import utils.entity.Device;

/** Real hdc process calls against a fixture; never contacts or controls a phone. */
public final class UsbWirelessDiscoveryTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static boolean contains(List<Device> devices, String sn) {
        return devices.stream().anyMatch(device -> device.getSn().equals(sn));
    }
    public static void main(String[] args) {
        try {
            Path root = Files.createTempDirectory("cast-usb-wifi-");
            Path directory = Files.createDirectory(root.resolve("fixture with spaces"));
            Path executable = directory.resolve("hdc");
            Files.writeString(executable, "#!/bin/sh\n"
                + "fixture='" + directory + "'\n"
                + "printf '%s\\n' \"$*\" >> \"$fixture/commands\"\n"
                + "case \"$*\" in\n"
                + "*'list targets -v'*) cat \"$fixture/targets\";;\n"
                + "*'-t USB-A shell ifconfig wlan0'*) if [ -f \"$fixture/slow\" ]; then sleep 10; fi; cat \"$fixture/wlanA\";;\n"
                + "*'-t USB-B shell ifconfig wlan0'*) cat \"$fixture/wlanB\";;\n"
                + "*'tmode port 8710'*) echo 'Set device run mode successful';;\n"
                + "*'tconn '*' -remove'*) echo removed;;\n"
                + "*'tconn '*) touch \"$fixture/tcpAlive\"; echo connected;;\n"
                + "*'shell echo HARMONY_CAST_ALIVE'*) if [ -f \"$fixture/tcpAlive\" ]; then echo HARMONY_CAST_ALIVE; fi;;\n"
                + "esac\n");
            executable.toFile().setExecutable(true);
            Files.writeString(directory.resolve("targets"), "USB-A USB Connected localhost\nUSB-B USB Connected localhost\n"
                + "192.0.2.25:8710 TCP Connected localhost\n192.0.2.26:8710 TCP Offline localhost\nUSB-OFF USB Offline localhost\n");
            Files.writeString(directory.resolve("wlanA"), "wlan0: inet 192.0.2.25 netmask 255.255.255.0\n");
            Files.writeString(directory.resolve("wlanB"), "wlan0: inet addr:192.0.2.26 Mask:255.255.255.0\n");
            CaptureConnection connection = new CaptureConnection(executable.toString(), directory, address -> false);
            List<Device> devices = connection.discoverLocalDevices();
            check(devices.size() == 4 && contains(devices, "USB-A") && contains(devices, "USB-B")
                && contains(devices, "192.0.2.25:8710") && contains(devices, "192.0.2.26:8710"),
                "each USB phone must expose its Wi-Fi address without duplicates or offline USB targets");
            connection.discoverLocalDevices();
            String commands = Files.readString(directory.resolve("commands"));
            check(commands.lines().filter(line -> line.contains("tmode port")).count() == 2,
                "wireless debugging must be prepared once per attached USB phone");
            check(!commands.contains("tconn"), "discovery must not connect or switch the active phone");
            check(connection.probe(new CaptureRecovery.Target("192.0.2.26:8710", "127.0.0.1"), null, false, false)
                == CaptureRecovery.Health.TRANSPORT_ONLY, "choosing discovered Wi-Fi address must establish TCP connection");
            commands = Files.readString(directory.resolve("commands"));
            check(commands.contains("tconn 192.0.2.26:8710"), "selection must connect exact Wi-Fi address");
            check(commands.contains("tconn 192.0.2.26:8710 -remove") && !commands.contains("tconn USB"),
                "reconnect must clear only the requested offline Wi-Fi record");
            Files.writeString(directory.resolve("targets"), "");
            devices = new CaptureConnection(executable.toString(), directory, address -> false).discoverLocalDevices();
            check(devices.size() == 2 && contains(devices, "192.0.2.25:8710") && contains(devices, "192.0.2.26:8710"),
                "Wi-Fi addresses must remain selectable after USB unplugging and restart");
            Files.writeString(directory.resolve("targets"), "USB-A USB Connected localhost\n");
            Files.writeString(directory.resolve("wlanA"), "inet 192.0.2.27 netmask 255.255.255.0\n");
            devices = connection.discoverLocalDevices();
            check(contains(devices, "192.0.2.27:8710") && !contains(devices, "192.0.2.25:8710"),
                "new WLAN IP must replace the same phone's stale address");
            Files.writeString(directory.resolve("wireless-device.txt"), "192.0.2.28:8710\n");
            devices = connection.discoverLocalDevices();
            check(contains(devices, "192.0.2.28:8710"), "last successful Wi-Fi phone must also remain selectable");
            long modesBefore = Files.readString(directory.resolve("commands")).lines()
                .filter(line -> line.contains("tmode port")).count();
            new CaptureConnection(executable.toString(), directory, address -> true).discoverLocalDevices();
            check(Files.readString(directory.resolve("commands")).lines().filter(line -> line.contains("tmode port")).count() == modesBefore,
                "an open wireless port must not reset USB debugging mode");
            check(CaptureConnection.wlanAddress("inet6 fe80::1\ninet 999.1.2.3\ninet 127.0.0.1") == null,
                "invalid, IPv6-only and loopback addresses must not be shown as Wi-Fi targets");
            check(CaptureConnection.wlanAddress("inet 169.254.1.2\ninet 192.0.2.30").equals("192.0.2.30:8710"),
                "link-local address must be skipped");
            Files.createFile(directory.resolve("slow"));
            long start = System.nanoTime();
            devices = connection.discoverLocalDevices();
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "WLAN query must have a bounded timeout");
            check(contains(devices, "USB-A") && contains(devices, "192.0.2.27:8710"),
                "failed WLAN reads must keep USB and remembered Wi-Fi usable");
            try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(directory); Files.delete(root);
            System.out.println("PASS: USB WLAN discovery, deduplication, wireless preparation, explicit selection, persisted addresses, IP changes and bounded failures");
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

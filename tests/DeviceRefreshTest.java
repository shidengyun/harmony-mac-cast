import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the actual polling timer with a fake hdc, without starting a phone session. */
public final class DeviceRefreshTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Object field(CastAppearance.Window window, String name) throws Exception {
        Field field = CastAppearance.Window.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(window);
    }
    static void awaitDiscovery(CastAppearance.Window window) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] busy = {true};
            SwingUtilities.invokeAndWait(() -> {
                try { busy[0] = (boolean) field(window, "refreshingDevices"); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            if (!busy[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Device discovery did not finish");
    }
    static void poll(CastAppearance.Window window, Timer timer) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field ticks = CastAppearance.Window.class.getDeclaredField("refreshTicks");
                ticks.setAccessible(true); ticks.setInt(window, 0);
                for (int i = 0; i < 8; i++)
                    for (var listener : timer.getActionListeners())
                        listener.actionPerformed(new ActionEvent(timer, 0, "poll"));
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        awaitDiscovery(window);
    }
    static void run(Path fixture) throws Exception {
        CastAppearance.Window[] window = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.installDefaults();
                window[0] = new CastAppearance.Window();
                try { ((Timer) field(window[0], "statusTimer")).stop(); }
                catch (Exception error) { throw new RuntimeException(error); }
                window[0].setVisible(true);
            });
            // Let the upstream constructor's asynchronous initial discovery finish.
            Thread.sleep(600);
            Timer timer = (Timer) field(window[0], "statusTimer");
            poll(window[0], timer);
            @SuppressWarnings("unchecked") JComboBox<utils.entity.Device> devices =
                (JComboBox<utils.entity.Device>) CastAppearance.field(window[0], "cmbSn", JComboBox.class);
            JButton refresh = CastAppearance.field(window[0], "btnRefreshDevice", JButton.class);
            AtomicInteger presses = new AtomicInteger(), enabledChanges = new AtomicInteger();
            AtomicInteger modelEvents = new AtomicInteger(), selectionEvents = new AtomicInteger();
            BufferedImage image = new BufferedImage(450, 1000, BufferedImage.TYPE_INT_RGB);
            var display = CastAppearance.field(window[0], "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
            SwingUtilities.invokeAndWait(() -> {
                display.setImage(image);
                devices.setSelectedIndex(1);
                refresh.getModel().addChangeListener(event -> {
                    if (refresh.getModel().isPressed()) presses.incrementAndGet();
                });
                refresh.addPropertyChangeListener("enabled", event -> enabledChanges.incrementAndGet());
                devices.addItemListener(event -> selectionEvents.incrementAndGet());
                devices.addPropertyChangeListener("model", event -> modelEvents.incrementAndGet());
                devices.getModel().addListDataListener(new ListDataListener() {
                    public void intervalAdded(ListDataEvent event) { modelEvents.incrementAndGet(); }
                    public void intervalRemoved(ListDataEvent event) { modelEvents.incrementAndGet(); }
                    public void contentsChanged(ListDataEvent event) { modelEvents.incrementAndGet(); }
                });
            });
            for (boolean collapsed : new boolean[]{false, true}) {
                if (collapsed) SwingUtilities.invokeAndWait(() ->
                    ((JButton) SidebarLayoutTest.named(window[0].getContentPane(), "sidebarToggle")).doClick());
                Rectangle bounds = window[0].getBounds();
                Object selected = devices.getSelectedItem();
                int queriesBefore = Files.readAllLines(fixture.resolve("queries")).size();
                poll(window[0], timer);
                System.out.println("POLL collapsed=" + collapsed + " presses=" + presses.get()
                    + " enabledChanges=" + enabledChanges.get() + " modelEvents=" + modelEvents.get()
                    + " selectionEvents=" + selectionEvents.get());
                check(Files.readAllLines(fixture.resolve("queries")).size() > queriesBefore,
                    "Automatic discovery must still query hdc");
                check(presses.get() == 0, "Automatic discovery must not simulate a button press");
                check(enabledChanges.get() == 0, "Automatic discovery must not blink the refresh button");
                check(modelEvents.get() == 0 && selectionEvents.get() == 0,
                    "Unchanged devices must not rebuild the model or change selection");
                check(devices.getSelectedItem() == selected, "Keep the selected device object on unchanged discovery");
                check(window[0].getBounds().equals(bounds), "Polling must preserve window bounds");
                check(display.getSourceImage() == image, "Polling must preserve the current frame");
            }
            Files.writeString(fixture.resolve("devices"), "phone-two TCP Connected localhost\nphone-three TCP Connected localhost\n");
            poll(window[0], timer);
            check(devices.getItemCount() == 2 && ((utils.entity.Device) devices.getSelectedItem()).getSn().equals("phone-two"),
                "Changed discovery must update devices and retain the selected phone");
            Files.writeString(fixture.resolve("devices"), "");
            poll(window[0], timer);
            check(devices.getItemCount() == 0, "Disconnected devices must disappear when no capture is active");
            int enabledBefore = enabledChanges.get();
            SwingUtilities.invokeAndWait(() -> refresh.doClick(0));
            awaitDiscovery(window[0]);
            check(enabledChanges.get() > enabledBefore && refresh.isEnabled(),
                "Manual refresh must remain usable and restore its enabled state");
            System.out.println("PASS: silent polling expanded/collapsed, unchanged selection/frame/bounds, device changes and manual refresh");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); });
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try { run(Path.of(args[0])); System.exit(0); }
            catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        }
        Path fixture = Files.createTempDirectory("cast-device-refresh-");
        try {
            Files.writeString(fixture.resolve("devices"), "phone-one TCP Connected localhost\nphone-two TCP Connected localhost\n");
            Files.writeString(fixture.resolve("queries"), "");
            Path hdc = fixture.resolve("hdc");
            Files.writeString(hdc, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$CAST_STATE_DIR/queries\"\ncat \"$CAST_STATE_DIR/devices\"\n");
            hdc.toFile().setExecutable(true);
            ProcessBuilder child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), DeviceRefreshTest.class.getName(), fixture.toString());
            child.environment().put("CAST_STATE_DIR", fixture.toString());
            child.environment().put("CAST_HDC_PATH", hdc.toString());
            child.environment().put("PATH", fixture + ":" + System.getenv("PATH"));
            int result = child.inheritIO().start().waitFor();
            check(result == 0, "Device refresh regression failed");
        } finally {
            try (var files = Files.list(fixture)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.delete(fixture);
        }
    }
}

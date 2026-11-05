import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import forms.MainForm;
import utils.swing.MultiFunctionLabel;

/** Starts the upstream viewer and connects when exactly one device is available. */
public class AutoStart {
    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = MainForm.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CastAppearance.installDefaults();
            final MainForm viewer = new CastAppearance.Window();
            viewer.setVisible(true);
            final long started = System.nanoTime();
            final boolean[] connected = {false};
            Timer timer = new Timer(500, event -> {
                try {
                    JComboBox<?> devices = field(viewer, "cmbSn", JComboBox.class);
                    JButton start = field(viewer, "btnEnterScreenCapture", JButton.class);
                    int selectedIndex = devices.getItemCount() == 1 ? 0 : -1;
                    String preferredDevice = System.getProperty("cast.device");
                    if (preferredDevice != null) {
                        selectedIndex = -1;
                        for (int i = 0; i < devices.getItemCount(); i++) {
                            Object candidate = devices.getItemAt(i);
                            if (candidate instanceof utils.entity.Device && preferredDevice.equals(
                                    ((utils.entity.Device) candidate).getSn())) selectedIndex = i;
                        }
                    }
                    if (!connected[0] && selectedIndex >= 0 && start.isEnabled()) {
                        devices.setSelectedIndex(selectedIndex);
                        start.doClick();
                        connected[0] = true;
                        viewer.toFront();
                    }
                    MultiFunctionLabel display = field(viewer, "multiFunctionLabel", MultiFunctionLabel.class);
                    BufferedImage image = display.getImage();
                    if (connected[0] && image != null) {
                        display.requestFocusInWindow();
                        System.out.println("VIEWER_FRAME=" + image.getWidth() + "x" + image.getHeight());
                        String preview = System.getProperty("cast.preview");
                        if (preview != null) {
                            BufferedImage screenshot = new BufferedImage(viewer.getWidth() * 2,
                                viewer.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
                            Graphics2D graphics = screenshot.createGraphics();
                            graphics.scale(2, 2);
                            viewer.printAll(graphics);
                            graphics.dispose();
                            javax.imageio.ImageIO.write(screenshot, "png", new java.io.File(preview));
                            System.out.println("VIEWER_PREVIEW=" + preview);
                        }
                        ((Timer) event.getSource()).stop();
                    } else if (System.nanoTime() - started > 60_000_000_000L) {
                        System.out.println("Auto-connect timed out; use the viewer controls to retry.");
                        ((Timer) event.getSource()).stop();
                    }
                } catch (Exception error) {
                    error.printStackTrace();
                    ((Timer) event.getSource()).stop();
                }
            });
            timer.start();
        });
    }
}

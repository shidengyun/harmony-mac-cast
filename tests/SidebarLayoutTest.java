import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real Swing layout checks with a synthetic frame; never starts a phone session. */
public final class SidebarLayoutTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Component named(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container) {
                Component found = named((Container) component, name);
                if (found != null) return found;
            }
        }
        return null;
    }
    static void buttons(Container root, List<JButton> found) {
        for (Component component : root.getComponents()) {
            if (component instanceof JScrollBar || component instanceof JComboBox) continue;
            if (component instanceof JButton) found.add((JButton) component);
            if (component instanceof Container) buttons((Container) component, found);
        }
    }
    static JButton button(Container root, String text) {
        List<JButton> found = new ArrayList<>();
        buttons(root, found);
        return found.stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }
    static void layout(CastAppearance.Window window, JPanel stage, JPanel sidebar,
                       CastAppearance.RotatingMultiFunctionLabel display) {
        window.validate();
        stage.doLayout();
        check(stage.getX() == 0 && stage.getY() == 0, "screen must occupy the full left side");
        check(stage.getHeight() == sidebar.getHeight(), "screen and sidebar must have the same height");
        check(stage.getX() + stage.getWidth() == sidebar.getX(), "screen must end at the right sidebar");
        check(sidebar.getWidth() == 300, "sidebar must keep its width when resizing");
        Rectangle frame = display.getBounds();
        check(frame.width > 0 && frame.height > 0, "frame must stay visible");
        check(stage.getBounds().width >= frame.x + frame.width && stage.getHeight() >= frame.y + frame.height,
            "frame must fit within the screen area");
        double ratio = (double) display.getImage().getWidth() / display.getImage().getHeight();
        check(Math.abs(frame.height - frame.width / ratio) <= 1, "frame aspect ratio must be preserved to the nearest pixel");
        check(frame.width >= stage.getWidth() - 18 || frame.height >= stage.getHeight() - 18,
            "frame must use the available space with small margins");
        List<JButton> controls = new ArrayList<>();
        buttons(window.getContentPane(), controls);
        int count = 0;
        for (JButton control : controls) {
            if (!control.isVisible()) continue; // Fullscreen's exit button is hidden normally.
            check(SwingUtilities.isDescendingFrom(control, sidebar), "button left in screen area: " + control.getText());
            check(!control.getText().isEmpty(), "sidebar buttons must have text labels");
            check(control.getWidth() > 0 && control.getHeight() > 0, "button clipped: " + control.getText());
            count++;
        }
        check(count == 33, "expected all 33 controls in sidebar, found " + count);
    }
    static void screenshot(CastAppearance.Window window, Path path) throws Exception {
        BufferedImage image = new BufferedImage(window.getWidth() * 2, window.getHeight() * 2,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.scale(2, 2);
        window.printAll(graphics);
        graphics.dispose();
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }
    static void run(Path output) throws Exception {
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            CastAppearance.installDefaults();
            CastAppearance.Window window = new CastAppearance.Window();
            try {
                Field timer = CastAppearance.Window.class.getDeclaredField("statusTimer");
                timer.setAccessible(true);
                ((Timer) timer.get(window)).stop();
                CastAppearance.RotatingMultiFunctionLabel display = CastAppearance.field(window,
                    "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
                BufferedImage frame = new BufferedImage(450, 1000, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setPaint(new GradientPaint(0, 0, new Color(0x142339), 450, 1000, new Color(0x3A6874)));
                g.fillRect(0, 0, 450, 1000);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Dialog", Font.PLAIN, 25));
                g.drawString("测试画面", 170, 490);
                g.dispose();
                display.setImage(frame);
                window.setVisible(true);
                JPanel stage = (JPanel) named(window.getContentPane(), "phoneStage");
                JPanel sidebar = (JPanel) named(window.getContentPane(), "controlSidebar");
                layout(window, stage, sidebar, display);
                screenshot(window, output.resolve("sidebar-default.png"));
                Rectangle original = window.getBounds();
                window.setBounds(original.x, original.y, 640, 480);
                layout(window, stage, sidebar, display);
                JButton playback = button(sidebar, "回放宏");
                playback.scrollRectToVisible(new Rectangle(0, 0, playback.getWidth(), playback.getHeight()));
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, playback);
                check(scroll.getVerticalScrollBar().getValue() > 0, "small window must scroll to lower controls");
                check(scroll.getHorizontalScrollBarPolicy() == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                    "sidebar must fit without horizontal scrolling");
                screenshot(window, output.resolve("sidebar-small-bottom.png"));
                window.setBounds(original);
                JButton rotate = button(sidebar, "旋转画面");
                rotate.setEnabled(true);
                rotate.doClick();
                layout(window, stage, sidebar, display);
                check(display.getImage().getWidth() == 1000, "rotation button must show landscape frame");
                check(window.getBounds().equals(original), "rotation must preserve sidebar window bounds");
                scroll.getVerticalScrollBar().setValue(0);
                screenshot(window, output.resolve("sidebar-landscape.png"));
                button(sidebar, "关灯模式").doClick();
                check(!sidebar.isVisible(), "fullscreen must hide the sidebar");
                List<JButton> stageButtons = new ArrayList<>();
                buttons(stage, stageButtons);
                JButton light = stageButtons.stream().filter(b -> "开灯".equals(b.getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow();
                check(light.isVisible(), "fullscreen exit control must remain available");
                light.doClick();
                check(sidebar.isVisible(), "fullscreen exit must restore sidebar");
                check(window.getBounds().equals(original), "fullscreen exit must restore window bounds");
                layout(window, stage, sidebar, display);
                System.out.println("PASS: all 33 buttons on right, full-height screen, small-window scrolling, portrait/landscape aspect ratio, rotation and fullscreen restoration");
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                window.dispose();
            }
        });
    }
    public static void main(String[] args) {
        try { run(Path.of(args[0])); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

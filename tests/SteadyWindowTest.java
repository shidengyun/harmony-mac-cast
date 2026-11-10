import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** A connected, unchanged frame must not be relaid out or repainted by status polling. */
public final class SteadyWindowTest {
    static final class Window extends CastAppearance.Window {
        int backgroundWrites;
        @Override public void setBackground(Color color) {
            backgroundWrites++;
            super.setBackground(color);
        }
    }
    static final class Paints extends RepaintManager {
        JComponent stage;
        boolean tracking;
        final AtomicInteger repaints = new AtomicInteger(), layouts = new AtomicInteger();
        @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            if (tracking && component == stage) repaints.incrementAndGet();
            super.addDirtyRegion(component, x, y, width, height);
        }
        @Override public void addInvalidComponent(JComponent component) {
            if (tracking && component == stage) layouts.incrementAndGet();
            super.addInvalidComponent(component);
        }
    }
    static Field member(String name) throws Exception {
        Field field = CastAppearance.Window.class.getDeclaredField(name);
        field.setAccessible(true); return field;
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void tick(Timer timer) {
        for (var listener : timer.getActionListeners()) listener.actionPerformed(null);
    }
    static void run() throws Exception {
        Window[] window = {null};
        Timer[] timer = {null};
        Paints paints = new Paints();
        RepaintManager previous = RepaintManager.currentManager(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    CastAppearance.installDefaults();
                    window[0] = new Window();
                    timer[0] = (Timer) member("statusTimer").get(window[0]);
                    timer[0].stop();
                    ((CaptureRecovery) member("recovery").get(window[0])).close();
                    CaptureRecovery recovery = new CaptureRecovery(new CaptureRecovery.Backend() {
                        public CaptureRecovery.Health probe(CaptureRecovery.Target target, long token, boolean service) {
                            return CaptureRecovery.Health.HEALTHY;
                        }
                        public void restart(CaptureRecovery.Target target, long token) { }
                    }, Runnable::run);
                    member("recovery").set(window[0], recovery);
                    long token = recovery.start(new CaptureRecovery.Target("static-test", "127.0.0.1"), CaptureRecovery.now());
                    recovery.frame(token, CaptureRecovery.now());
                    var display = CastAppearance.field(window[0], "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
                    // This connected fixture has no remote device; desktop mouse motion must not invoke SDK input.
                    for (var listener : display.getMouseMotionListeners()) display.removeMouseMotionListener(listener);
                    for (var listener : display.getMouseListeners()) display.removeMouseListener(listener);
                    display.setImage(new BufferedImage(450, 1000, BufferedImage.TYPE_INT_RGB));
                    window[0].setVisible(true);
                    paints.stage = (JComponent) SidebarLayoutTest.named(window[0].getContentPane(), "phoneStage");
                    tick(timer[0]);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Thread.sleep(600);
            SwingUtilities.invokeAndWait(() -> RepaintManager.setCurrentManager(paints));
            for (boolean collapsed : new boolean[]{false, true}) {
                if (collapsed) SwingUtilities.invokeAndWait(() -> {
                    ((JButton) SidebarLayoutTest.named(window[0].getContentPane(), "sidebarToggle")).doClick();
                    tick(timer[0]);
                });
                Thread.sleep(200);
                SwingUtilities.invokeAndWait(() -> {
                    paints.repaints.set(0); paints.layouts.set(0); paints.tracking = true;
                    window[0].backgroundWrites = 0;
                    Rectangle before = window[0].getBounds();
                    for (int i = 0; i < 20; i++) tick(timer[0]);
                    paints.tracking = false;
                    System.out.println("STEADY collapsed=" + collapsed + " stageRepaints=" + paints.repaints.get()
                        + " stageLayouts=" + paints.layouts.get() + " backgroundWrites=" + window[0].backgroundWrites);
                    check(paints.repaints.get() == 0 && paints.layouts.get() == 0,
                        "Unchanged status ticks must not repaint or relayout the phone stage");
                    check(window[0].backgroundWrites == 0, "Unchanged compact window must not reset its native background");
                    check(window[0].getBounds().equals(before), "Steady polling must preserve native window bounds");
                });
                // Let the real timer run too: queued repaint coalescing can hide periodic flashing.
                SwingUtilities.invokeAndWait(() -> {
                    paints.repaints.set(0); paints.layouts.set(0); paints.tracking = true;
                    window[0].backgroundWrites = 0;
                    timer[0].start();
                });
                Thread.sleep(5200);
                SwingUtilities.invokeAndWait(() -> {
                    timer[0].stop(); paints.tracking = false;
                    check(paints.repaints.get() == 0 && paints.layouts.get() == 0 && window[0].backgroundWrites == 0,
                        "Real periodic polling must not redraw an unchanged phone frame");
                });
            }
            var display = CastAppearance.field(window[0], "multiFunctionLabel", CastAppearance.RotatingMultiFunctionLabel.class);
            SwingUtilities.invokeAndWait(() -> display.setImage(new BufferedImage(1000, 450, BufferedImage.TYPE_INT_RGB)));
            Thread.sleep(200);
            SwingUtilities.invokeAndWait(() -> {
                window[0].validate();
                check(window[0].getWidth() > window[0].getHeight(), "New landscape frame must resize the compact window");
                check(display.getWidth() > display.getHeight(), "Frame aspect changes must still update layout without polling");
            });
            System.out.println("PASS: steady connected polling expanded/collapsed, no native background resets, live aspect changes");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                RepaintManager.setCurrentManager(previous);
                if (window[0] != null) window[0].dispose();
            });
        }
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

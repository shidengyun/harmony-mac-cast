import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Shortcut persistence and input isolation; never sends anything to a phone. */
public final class SidebarShortcutsTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static final class FocusManager extends DefaultKeyboardFocusManager {
        Window focused;
        @Override public Window getFocusedWindow() { return focused; }
    }
    static KeyEvent event(Component source, int kind, int code, int mods) {
        return new KeyEvent(source, kind, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED);
    }
    public static void main(String[] args) {
        try {
            Path directory = Files.createTempDirectory("cast-shortcuts-test-");
            Path path = directory.resolve("shortcuts.properties");
            KeyStroke shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.META_DOWN_MASK);
            check(SidebarShortcuts.valid(shortcut), "Cmd+B should be configurable");
            check(!SidebarShortcuts.valid(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0)), "plain typing must stay with phone");
            check(!SidebarShortcuts.valid(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)), "plain arrows must remain swipes");
            check(!SidebarShortcuts.valid(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)), "paste must remain reserved");
            check(!SidebarShortcuts.valid(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.ALT_DOWN_MASK)), "ESC must remain available");
            check(SidebarShortcuts.display(shortcut).equals("⌘B"), "Mac shortcut display");
            KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            FocusManager focus = new FocusManager();
            KeyboardFocusManager.setCurrentKeyboardFocusManager(focus);
            JFrame[] window = {null};
            SidebarShortcuts[] controller = {null};
            AtomicInteger clicks = new AtomicInteger();
            JButton hidden = new JButton("测试操作");
            hidden.setVisible(false);
            hidden.addActionListener(e -> clicks.incrementAndGet());
            try {
                SwingUtilities.invokeAndWait(() -> {
                    window[0] = new JFrame();
                    window[0].add(hidden);
                    window[0].addNotify();
                    focus.focused = window[0];
                    controller[0] = new SidebarShortcuts(window[0], path);
                    controller[0].register("action", hidden);
                    controller[0].register("other", new JButton("另一个操作"));
                    try {
                        controller[0].save(Map.of("action", shortcut));
                        try {
                            controller[0].save(Map.of("action", shortcut, "other", shortcut));
                            throw new AssertionError("duplicate shortcut accepted");
                        } catch (IllegalArgumentException expected) { }
                    } catch (java.io.IOException error) { throw new RuntimeException(error); }
                    controller[0].close();
                    controller[0] = new SidebarShortcuts(window[0], path);
                    controller[0].register("action", hidden);
                    controller[0].load();
                    check(controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_B, InputEvent.META_DOWN_MASK)), "saved shortcut must consume press");
                    check(controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_B, InputEvent.META_DOWN_MASK)), "repeat must be consumed");
                    check(controller[0].dispatch(new KeyEvent(hidden, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'b')), "shortcut must not type into phone");
                    check(controller[0].dispatch(event(hidden, KeyEvent.KEY_RELEASED, KeyEvent.VK_B, 0)), "release must be consumed after modifier release");
                });
                SwingUtilities.invokeAndWait(() -> {
                    check(clicks.get() == 1, "hidden action must fire once, without repeat");
                    hidden.setEnabled(false);
                    controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_B, InputEvent.META_DOWN_MASK));
                    controller[0].dispatch(event(hidden, KeyEvent.KEY_RELEASED, KeyEvent.VK_B, 0));
                    check(!controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0)), "ordinary input must pass through");
                    JDialog dialog = new JDialog(window[0]);
                    focus.focused = dialog;
                    check(!controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_B, InputEvent.META_DOWN_MASK)), "dialog must not trigger cast shortcut");
                    dialog.dispose();
                    focus.focused = window[0];
                });
                SwingUtilities.invokeAndWait(() -> {
                    check(clicks.get() == 1, "disabled action must not fire");
                    try { controller[0].save(Map.of()); }
                    catch (java.io.IOException error) { throw new RuntimeException(error); }
                    check(!controller[0].dispatch(event(hidden, KeyEvent.KEY_PRESSED, KeyEvent.VK_B, InputEvent.META_DOWN_MASK)), "clearing binding must restore input");
                });
                System.out.println("PASS: saved shortcuts, duplicate rejection, hidden/disabled actions, no repeat, phone input and dialog isolation");
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (controller[0] != null) controller[0].close();
                    if (window[0] != null) window[0].dispose();
                });
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
                Files.deleteIfExists(path);
                Files.deleteIfExists(directory);
            }
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

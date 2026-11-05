import java.awt.event.*;
import java.util.*;
import javax.swing.SwingUtilities;

public final class ArrowSwipeTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static KeyEvent key(CastAppearance.RotatingMultiFunctionLabel display, int id, int code, int modifiers) {
        KeyEvent event = new KeyEvent(display, id, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED);
        display.processKeyEvent(event);
        return event;
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CastAppearance.RotatingMultiFunctionLabel display = new CastAppearance.RotatingMultiFunctionLabel();
            List<Integer> swipes = new ArrayList<>();
            List<Integer> forwarded = new ArrayList<>();
            display.setArrowSwipe(swipes::add);
            display.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent event) {
                    forwarded.add(event.getKeyCode());
                    event.consume(); // No realized text field/caret is needed for this routing test.
                }
            });
            display.setKeyBoardCallBack(new utils.callbacks.KeyBoardCallBack() {
                public void onKeyBoardDown(KeyEvent event, int code, boolean shift) { }
                public void onPressPaste() { }
                public void onChineseCharInput(String text) { }
            });
            display.setScreenCapturingMode(true);
            int[] codes = {KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT};
            for (int code : codes) {
                check(key(display, KeyEvent.KEY_PRESSED, code, 0).isConsumed(), "arrow press must be consumed");
                key(display, KeyEvent.KEY_PRESSED, code, 0);
                check(key(display, KeyEvent.KEY_RELEASED, code, 0).isConsumed(), "arrow release must be consumed");
            }
            check(swipes.equals(List.of(2, 3, 0, 1)), "arrows must swipe in order without auto-repeat");
            check(forwarded.isEmpty(), "swipes must not also send phone direction keys");
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP, 0);
            display.processFocusEvent(new FocusEvent(display, FocusEvent.FOCUS_LOST));
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP, 0);
            check(swipes.size() == 6, "focus loss must clear held keys");
            display.setScreenCapturingMode(false);
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP, 0);
            check(swipes.size() == 6, "offline display must not swipe");
            display.setScreenCapturingMode(true);
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP, 0);
            key(display, KeyEvent.KEY_RELEASED, KeyEvent.VK_UP, 0);
            check(swipes.size() == 7, "reconnect must clear held keys");
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK);
            key(display, KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 0);
            check(swipes.size() == 7 && forwarded.contains(KeyEvent.VK_A) && forwarded.contains(KeyEvent.VK_LEFT),
                "typing and modified arrows must retain existing keyboard handling");
        });
        System.out.println("PASS: four arrow swipes, input consumption, auto-repeat suppression, focus loss, offline/reconnect, typing and modifiers");
        System.exit(0);
    }
}

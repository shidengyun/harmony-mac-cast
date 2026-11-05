import com.huawei.hosscrcpy.api.HosRemoteDevice;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Preserve input order while amortizing hdc/uinput startup across a typing burst. */
final class PhoneKeyboard implements AutoCloseable {
    static final class Binding {
        final HosRemoteDevice remote;
        final long token;
        final BooleanSupplier current;
        Binding(HosRemoteDevice remote, long token, BooleanSupplier current) {
            this.remote = remote; this.token = token; this.current = current;
        }
        boolean same(Binding other) { return remote == other.remote && token == other.token; }
    }
    private static final class Input {
        final Binding binding;
        final String keys, text;
        Input(Binding binding, String keys, String text) { this.binding = binding; this.keys = keys; this.text = text; }
    }
    private final Supplier<Binding> target;
    private final ArrayDeque<Input> pending = new ArrayDeque<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "phone-keyboard"); thread.setDaemon(true); return thread;
    });
    private boolean scheduled, closed;
    PhoneKeyboard(Supplier<Binding> target) { this.target = target; }

    void key(KeyEvent event, int code, boolean shift) {
        char character = event == null ? KeyEvent.CHAR_UNDEFINED : event.getKeyChar();
        if ("<>?.".indexOf(character) >= 0) { text(String.valueOf(character)); return; }
        boolean upper = Character.isLetter(character) ? Character.isUpperCase(character) : shift;
        String keys = upper ? "-d 2047 -d " + code + " -u " + code + " -u 2047"
                            : "-d " + code + " -u " + code;
        enqueue(keys, null);
    }
    void text(String text) {
        if (text == null || text.isEmpty()) return;
        enqueue(null, text.replace('\n', ' ').replace('\r', ' '));
    }
    private synchronized void enqueue(String keys, String text) {
        Binding binding = target.get();
        if (closed || binding == null || !binding.current.getAsBoolean()) return;
        pending.addLast(new Input(binding, keys, text));
        if (!scheduled) {
            scheduled = true;
            // A fixed window, not a debounce: continuous typing cannot postpone delivery.
            worker.schedule(this::drain, 20, TimeUnit.MILLISECONDS);
        }
    }
    private void drain() {
        while (true) {
            Input first;
            StringBuilder payload;
            boolean keys;
            synchronized (this) {
                first = pending.pollFirst();
                if (first == null || closed) { scheduled = false; return; }
                if (!first.binding.current.getAsBoolean()) continue;
                keys = first.keys != null;
                payload = new StringBuilder(keys ? first.keys : first.text);
                int count = 1;
                while (!pending.isEmpty() && count < 32 && payload.length() < 4096) {
                    Input next = pending.peekFirst();
                    if (!first.binding.same(next.binding) || keys != (next.keys != null)) break;
                    pending.removeFirst();
                    if (keys) payload.append(' ');
                    payload.append(keys ? next.keys : next.text);
                    count++;
                }
            }
            if (!first.binding.current.getAsBoolean()) continue;
            try {
                String command = keys ? "uinput -K " + payload : textCommand(payload.toString());
                String result = first.binding.remote.executeShellCommand(command, keys ? 5 : 10);
                if (result == null || result.contains("[Fail]") || result.contains("Error:") || result.contains("error:"))
                    throw new IOException("input rejected");
            } catch (Exception error) {
                // Never replay an uncertain input: it could duplicate text or delete twice.
                synchronized (this) { pending.removeIf(input -> first.binding.same(input.binding)); }
                System.err.println("手机键盘输入中断: " + error.getClass().getSimpleName());
            }
        }
    }
    static String textCommand(String text) {
        return "uitest uiInput text '" + text.replace("'", "'\\''") + "'";
    }
    synchronized void clear() { pending.clear(); }
    @Override public synchronized void close() {
        closed = true; pending.clear(); worker.shutdownNow();
    }
}

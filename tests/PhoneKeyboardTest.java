import com.huawei.hosscrcpy.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.nio.file.*;

public final class PhoneKeyboardTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Device extends HosRemoteDevice {
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        volatile String reply = "you raised the key";
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        Device() { super(new HosRemoteConfig("keyboard-test")); }
        @Override public String executeShellCommand(String command, int timeout) {
            int running = active.incrementAndGet(); maxActive.accumulateAndGet(running, Math::max);
            try { commands.add(command); Thread.sleep(100); return reply; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            finally { active.decrementAndGet(); }
        }
    }
    static void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.get(), message);
    }
    interface BooleanSupplier { boolean get(); }
    static List<Integer> releases(Device device) {
        List<Integer> result = new ArrayList<>();
        synchronized (device.commands) {
            for (String command : device.commands) {
                Matcher matcher = Pattern.compile("-u (\\d+)").matcher(command);
                while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
            }
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        Device device = new Device(); AtomicLong generation = new AtomicLong(1);
        try (PhoneKeyboard keyboard = new PhoneKeyboard(() -> {
            long token = generation.get(); return new PhoneKeyboard.Binding(device, token, () -> generation.get() == token);
        })) {
            long began = System.nanoTime();
            for (int i = 0; i < 80; i++) keyboard.key(null, 2017 + i % 26, false);
            check(System.nanoTime() - began < TimeUnit.MILLISECONDS.toNanos(100), "keyboard callback must not wait for phone commands");
            keyboard.text("中文 '引号' $literal"); keyboard.key(null, 2055, false); keyboard.key(null, 2119, false);
            await(() -> releases(device).size() == 82 && device.active.get() == 0, "input burst did not complete");
            List<Integer> result = releases(device);
            for (int i = 0; i < 80; i++) check(result.get(i) == 2017 + i % 26, "typed characters reordered or lost at " + i);
            check(result.get(80) == 2055 && result.get(81) == 2119, "backspace and Enter must follow text");
            check(device.commands.size() <= 5, "80 keys should be batched, not start 80 remote processes");
            check(device.commands.get(device.commands.size() - 2).startsWith("uitest uiInput text "), "Chinese commit must precede following keys");
            check(device.maxActive.get() == 1, "phone input must never overlap");
        }
        Device cancelled = new Device(); AtomicBoolean current = new AtomicBoolean(true);
        try (PhoneKeyboard keyboard = new PhoneKeyboard(() -> new PhoneKeyboard.Binding(cancelled, 1, current::get))) {
            keyboard.key(null, 2017, false);
            await(() -> cancelled.active.get() == 1, "first command did not start");
            keyboard.key(null, 2018, false); current.set(false);
            await(() -> cancelled.active.get() == 0, "first command did not finish");
            Thread.sleep(60); check(cancelled.commands.size() == 1, "stale session must not receive queued keys");
        }
        Device rejected = new Device(); rejected.reply = "[Fail] Error: connection lost";
        try (PhoneKeyboard keyboard = new PhoneKeyboard(() -> new PhoneKeyboard.Binding(rejected, 1, () -> true))) {
            keyboard.key(null, 2017, false);
            await(() -> rejected.active.get() == 1, "rejected command did not start");
            for (int i = 0; i < 50; i++) keyboard.key(null, 2018, false);
            await(() -> rejected.active.get() == 0, "rejected command did not finish");
            Thread.sleep(60); check(rejected.commands.size() == 1, "failed input must not be retried or drain remaining keys");
        }
        Path sentinel = Files.createTempDirectory("keyboard-quote-test-").resolve("sentinel");
        String payload = "中文 'quoted' \\ $HOME $(touch " + sentinel + ") `echo unintended`";
        Process process = new ProcessBuilder("/bin/sh", "-c", "set -- " + PhoneKeyboard.textCommand(payload) + "; printf %s \"$4\"").start();
        String received = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor() == 0 && received.equals(payload) && !Files.exists(sentinel), "text must remain literal, including shell metacharacters");
        Files.delete(sentinel.getParent());
        System.out.println("PASS: nonblocking keyboard, 80 keys in <=5 commands, exact order, Chinese before edit keys, stale session cancellation, failed-input discard, literal text escaping");
        System.exit(0);
    }
}

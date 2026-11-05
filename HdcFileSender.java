import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** Send into an isolated batch folder, publishing the final name only after upload succeeds. */
final class HdcFileSender implements FileDropTransfer.Sender {
    private final String hdc;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private static final Pattern PERCENT = Pattern.compile("(?<!\\d)(\\d{1,3})%");
    HdcFileSender(String hdc) { this.hdc = hdc; }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    public void mkdir(CaptureRecovery.Target target, String directory, BooleanSupplier cancelled) throws Exception {
        shell(target, "mkdir -p " + quote(directory), cancelled);
    }
    public void send(CaptureRecovery.Target target, File local, String temporary, String destination,
                     BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        try {
            String output = run(target, new String[]{"file", "send", local.getAbsolutePath(), temporary},
                Math.min(3600, Math.max(120, local.length() / 65536 + 60)), cancelled, progress);
            if (!output.contains("FileTransfer finish")) throw new IOException("传输未完成：" + output.trim());
            shell(target, "mv -- " + quote(temporary) + " " + quote(destination), cancelled);
            progress.accept(100);
        } finally {
            // This generated .part name can never refer to a pre-existing user file.
            try { shell(target, "rm -f -- " + quote(temporary), () -> false); }
            catch (Exception ignored) { }
        }
    }
    private void shell(CaptureRecovery.Target target, String command, BooleanSupplier cancelled) throws Exception {
        String output = run(target, new String[]{"shell", command + " && echo HARMONY_TRANSFER_OK"},
            8, cancelled, ignored -> { });
        if (!output.lines().anyMatch(line -> line.trim().equals("HARMONY_TRANSFER_OK"))) {
            throw new IOException(output.isBlank() ? "手机未确认保存操作" : output.trim());
        }
    }
    private String run(CaptureRecovery.Target target, String[] arguments, long seconds,
                       BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        if (hdc == null) throw new IOException("未找到连接工具");
        String[] command = new String[arguments.length + 5];
        command[0] = hdc; command[1] = "-s"; command[2] = target.ip + ":8710";
        command[3] = "-t"; command[4] = target.sn;
        System.arraycopy(arguments, 0, command, 5, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        current.set(process);
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 65536) output.append(line).append('\n');
                    }
                    var matcher = PERCENT.matcher(line);
                    if (matcher.find()) progress.accept(Math.min(100, Integer.parseInt(matcher.group(1))));
                }
            } catch (IOException ignored) { }
        }, "file-transfer-output");
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                if (System.nanoTime() >= deadline) throw new IOException("传输超时，可重新拖入文件重试");
            }
            reader.join(1000);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            String result;
            synchronized (output) { result = output.toString(); }
            String lower = result.toLowerCase(Locale.ROOT);
            if (process.exitValue() != 0 || lower.contains("[fail]") || lower.contains("failed")
                    || lower.contains("permission denied")) {
                throw new IOException(result.isBlank() ? "连接或传输失败" : result.trim());
            }
            return result;
        } finally {
            if (process.isAlive()) kill(process);
            current.compareAndSet(process, null);
        }
    }
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
    public void cancel() {
        Process process = current.get();
        if (process != null) kill(process);
    }
}

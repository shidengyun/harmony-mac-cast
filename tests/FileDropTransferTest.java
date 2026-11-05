import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public final class FileDropTransferTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static final class Sender implements FileDropTransfer.Sender {
        int mkdirCalls, sends;
        boolean failDirectory;
        FileDropTransfer.Batch cancelBatch;
        public void mkdir(CaptureRecovery.Target target, String directory, BooleanSupplier cancelled) throws Exception {
            mkdirCalls++; if (failDirectory) throw new IOException("directory failed");
        }
        public void send(CaptureRecovery.Target target, File local, String temporary, String remote,
                         BooleanSupplier cancelled, IntConsumer progress) throws Exception {
            sends++;
            if (cancelBatch != null) { cancelBatch.cancelled.set(true); throw new java.util.concurrent.CancellationException(); }
            if (local.getName().equals("failure.txt")) throw new IOException("file failed");
            progress.accept(100);
        }
        public void cancel() { }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("file-drop-unit-");
        Path a = Files.createDirectory(directory.resolve("a")), b = Files.createDirectory(directory.resolve("b"));
        File first = Files.writeString(a.resolve("同名.txt"), "first").toFile();
        File second = Files.writeString(b.resolve("同名.txt"), "second").toFile();
        File failure = Files.writeString(directory.resolve("failure.txt"), "failure").toFile();
        CaptureRecovery.Target target = new CaptureRecovery.Target("original-phone", "127.0.0.1");
        FileDropTransfer.Batch batch = new FileDropTransfer.Batch(target, List.of(first, second, failure, a.toFile()));
        check(!batch.entries.get(0).remote.equals(batch.entries.get(1).remote), "duplicate names must not overwrite");
        check(batch.entries.get(1).remote.endsWith("同名 (2).txt"), "duplicate filename keeps extension");
        check(batch.entries.get(3).state == FileDropTransfer.State.SKIPPED, "folder produces a clear skipped result");
        check(!batch.directory.equals(new FileDropTransfer.Batch(target, List.of(first)).directory), "batches need isolated folders");
        Sender sender = new Sender();
        FileDropTransfer transfer = new FileDropTransfer(null, () -> null, sender, ignored -> { });
        transfer.transfer(batch);
        check(sender.mkdirCalls == 1 && sender.sends == 3, "one batch directory and one send per readable file");
        check(batch.entries.get(0).state == FileDropTransfer.State.SENT && batch.entries.get(1).state == FileDropTransfer.State.SENT,
            "valid files complete even when another fails");
        check(batch.entries.get(2).state == FileDropTransfer.State.FAILED, "file failure is reported");
        FileDropTransfer.Batch cancelled = new FileDropTransfer.Batch(target, List.of(first, second));
        sender.cancelBatch = cancelled;
        transfer.transfer(cancelled);
        check(cancelled.entries.stream().allMatch(entry -> entry.state == FileDropTransfer.State.CANCELLED), "cancel stops remaining files");
        sender.cancelBatch = null; sender.failDirectory = true;
        int before = sender.mkdirCalls;
        FileDropTransfer.Batch denied = new FileDropTransfer.Batch(target, List.of(first, second));
        transfer.transfer(denied);
        check(sender.mkdirCalls == before + 1 && denied.entries.stream().allMatch(entry -> entry.state == FileDropTransfer.State.FAILED),
            "inaccessible directory fails the batch without retrying every file");
        transfer.close();
        System.out.println("PASS: duplicate names, isolated batches, skipped folders, partial failure, cancellation, directory failure");
        System.exit(0);
    }
}

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the installed TransferHandler path and verifies real file contents by reading them back. */
public final class FileDropLiveTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String command(String hdc, String sn, String... tail) throws Exception {
        List<String> args = new ArrayList<>(List.of(hdc, "-t", sn)); args.addAll(List.of(tail));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("test hdc timeout"); }
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    static void run(String hdc, String sn) throws Exception {
        Path dir = Files.createTempDirectory("file-drop-live-");
        Path a = Files.createDirectory(dir.resolve("a")), b = Files.createDirectory(dir.resolve("b"));
        List<File> files = List.of(
            Files.writeString(dir.resolve("中文 空格's $(echo safe).txt"), "中文内容\nHarmonyCast\n").toFile(),
            Files.writeString(a.resolve("同名.txt"), "first duplicate").toFile(),
            Files.writeString(b.resolve("同名.txt"), "second duplicate").toFile(),
            Files.createFile(dir.resolve("empty.bin")).toFile(), a.toFile());
        AtomicReference<CaptureRecovery.Target> selected = new AtomicReference<>(new CaptureRecovery.Target(sn, "127.0.0.1"));
        JFrame[] owner = {null}; FileDropTransfer[] manager = {null}; JPanel[] surface = {null};
        FileDropTransfer.Batch[] batch = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                CastAppearance.installDefaults(); owner[0] = new JFrame("文件传输验证");
                surface[0] = new JPanel();
                manager[0] = new FileDropTransfer(owner[0], selected::get, hdc, ignored -> { });
                manager[0].install(surface[0]);
                Transferable payload = new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.javaFileListFlavor.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return files; }
                };
                check(surface[0].getTransferHandler().importData(new TransferHandler.TransferSupport(surface[0], payload)), "file drop rejected");
                try {
                    Field field = FileDropTransfer.class.getDeclaredField("batches"); field.setAccessible(true);
                    batch[0] = (FileDropTransfer.Batch) ((List<?>) field.get(manager[0])).get(0);
                } catch (Exception error) { throw new RuntimeException(error); }
                selected.set(new CaptureRecovery.Target("different-phone", "127.0.0.1"));
            });
            for (int tick = 0; tick < 600; tick++) {
                if (batch[0].entries.stream().noneMatch(entry -> entry.state == FileDropTransfer.State.WAITING || entry.state == FileDropTransfer.State.SENDING)) break;
                Thread.sleep(100);
            }
            int count = 0;
            for (FileDropTransfer.Entry entry : batch[0].entries) {
                if (entry.local.isDirectory()) { check(entry.state == FileDropTransfer.State.SKIPPED, "folder not skipped"); continue; }
                check(entry.state == FileDropTransfer.State.SENT, "send failed: " + entry.local.getName() + " " + entry.detail);
                Path received = dir.resolve("received-" + count++);
                String output = command(hdc, sn, "file", "recv", entry.remote, received.toString());
                check(Files.exists(received), "readback failed: " + output);
                check(Arrays.equals(Files.readAllBytes(received), Files.readAllBytes(entry.local.toPath())), "readback content mismatch");
            }
            check(batch[0].target.sn.equals(sn), "drop target changed with current selection");
            SwingUtilities.invokeAndWait(() -> {
                selected.set(null);
                check(!manager[0].handler().canImport(new TransferHandler.TransferSupport(surface[0], new StringSelection("text"))), "text drop accepted");
                for (Window window : Window.getWindows()) if (window instanceof JDialog && ((JDialog) window).getTitle().equals("拖拽文件传输")) {
                    try {
                        var image = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = image.createGraphics(); window.printAll(graphics); graphics.dispose();
                        javax.imageio.ImageIO.write(image, "png", dir.resolve("dialog.png").toFile());
                    } catch (Exception error) { throw new RuntimeException(error); }
                }
            });
            System.out.println("PASS: drag import, 4 real file roundtrips, Chinese/spaces/quotes, duplicate names, empty file, target binding, skipped folder");
            System.out.println("Transfer UI preview: " + dir.resolve("dialog.png"));
        } finally {
            if (batch[0] != null) {
                for (FileDropTransfer.Entry entry : batch[0].entries) {
                    command(hdc, sn, "shell", "rm -f -- " + HdcFileSender.quote(entry.remote) + " " + HdcFileSender.quote(entry.temporary));
                }
                command(hdc, sn, "shell", "rmdir " + HdcFileSender.quote(batch[0].directory));
            }
            SwingUtilities.invokeAndWait(() -> { if (manager[0] != null) manager[0].close(); if (owner[0] != null) owner[0].dispose(); });
        }
    }
    public static void main(String[] args) {
        try { run(args[0], args[1]); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}

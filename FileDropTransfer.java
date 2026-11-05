import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Native Finder file drops and a serial queue bound to the device selected at drop time. */
final class FileDropTransfer implements AutoCloseable {
    interface Sender {
        void mkdir(CaptureRecovery.Target target, String directory, BooleanSupplier cancelled) throws Exception;
        void send(CaptureRecovery.Target target, File local, String temporary, String destination,
                  BooleanSupplier cancelled, IntConsumer progress) throws Exception;
        void cancel();
    }
    enum State { WAITING, SENDING, SENT, FAILED, CANCELLED, SKIPPED }
    static final class Entry {
        final File local;
        final long bytes;
        final String remote, temporary;
        final Batch batch;
        volatile State state = State.WAITING;
        volatile int percent;
        volatile String detail = "";
        Entry(File local, String remote, String temporary, Batch batch) {
            this.local = local; this.remote = remote; this.temporary = temporary; this.batch = batch;
            bytes = local.length();
            if (!local.isFile() || !local.canRead()) {
                state = State.SKIPPED;
                detail = local.isDirectory() ? "暂不支持文件夹" : "文件不存在或不可读取";
            }
        }
    }
    static final class Batch {
        final CaptureRecovery.Target target;
        final String directory;
        final List<Entry> entries = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        Batch(CaptureRecovery.Target target, List<File> files) {
            this.target = new CaptureRecovery.Target(target.sn, target.ip);
            directory = "/data/local/tmp/HarmonyCast/" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date()) + "-" + UUID.randomUUID();
            Set<String> names = new HashSet<>();
            for (File file : files) {
                String original = file.getName(), name = original;
                for (int suffix = 2; !names.add(name); suffix++) {
                    int dot = original.lastIndexOf('.');
                    name = dot > 0 ? original.substring(0, dot) + " (" + suffix + ")" + original.substring(dot)
                        : original + " (" + suffix + ")";
                }
                entries.add(new Entry(file, directory + "/" + name,
                    directory + "/.upload-" + entries.size() + ".part", this));
            }
        }
    }
    private final JFrame owner;
    private final Supplier<CaptureRecovery.Target> selected;
    private final Consumer<String> status;
    private final Sender sender;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "drag-file-transfer"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Batch> batches = new CopyOnWriteArrayList<>();
    private final List<Entry> rows = new ArrayList<>();
    private JDialog dialog;
    private Timer timer;
    private JTable table;
    private JLabel summary, location;
    private JProgressBar progress;
    private JButton cancel;
    private AbstractTableModel model;
    private String lastStatus = "";
    private final TransferHandler handler = new TransferHandler() {
        public boolean canImport(TransferSupport support) {
            boolean allowed = !closed.get() && selected.get() != null
                && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            if (allowed && support.isDrop()) {
                if ((support.getSourceDropActions() & COPY) == 0) return false;
                support.setDropAction(COPY);
            }
            return allowed;
        }
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) { status.accept("请先选择已连接的手机，再拖入文件"); return false; }
            try {
                Object value = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (!(value instanceof List<?>)) return false;
                List<File> files = new ArrayList<>();
                for (Object item : (List<?>) value) if (item instanceof File) files.add((File) item);
                if (files.isEmpty()) return false;
                enqueue(new Batch(selected.get(), files));
                return true;
            } catch (Exception error) {
                status.accept("无法读取拖入的文件"); return false;
            }
        }
    };

    FileDropTransfer(JFrame owner, Supplier<CaptureRecovery.Target> selected, String hdc, Consumer<String> status) {
        this(owner, selected, new HdcFileSender(hdc), status);
    }
    FileDropTransfer(JFrame owner, Supplier<CaptureRecovery.Target> selected, Sender sender, Consumer<String> status) {
        this.owner = owner; this.selected = selected; this.sender = sender; this.status = status;
    }
    TransferHandler handler() { return handler; }
    void install(Component component) {
        if (component instanceof JComponent) ((JComponent) component).setTransferHandler(handler);
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) install(child);
    }
    void enqueue(Batch batch) {
        if (closed.get()) return;
        int first = rows.size();
        boolean existingModel = model != null;
        batches.add(batch); rows.addAll(batch.entries);
        showDialog();
        if (existingModel && rows.size() > first) model.fireTableRowsInserted(first, rows.size() - 1);
        refresh();
        worker.execute(() -> transfer(batch));
    }
    void showTransfers() { if (!closed.get()) { showDialog(); refresh(); } }
    void transfer(Batch batch) {
        BooleanSupplier cancelled = () -> closed.get() || batch.cancelled.get();
        try {
            if (batch.entries.stream().anyMatch(entry -> entry.state == State.WAITING)) sender.mkdir(batch.target, batch.directory, cancelled);
        } catch (Exception error) {
            for (Entry entry : batch.entries) if (entry.state == State.WAITING) {
                entry.state = cancelled.getAsBoolean() ? State.CANCELLED : State.FAILED;
                entry.detail = error.getMessage() == null ? "手机目录不可用" : error.getMessage();
            }
            SwingUtilities.invokeLater(() -> { if (!closed.get()) refresh(); });
            return;
        }
        for (Entry entry : batch.entries) {
            if (entry.state == State.SKIPPED) continue;
            if (cancelled.getAsBoolean()) { entry.state = State.CANCELLED; continue; }
            entry.state = State.SENDING;
            try {
                sender.send(batch.target, entry.local, entry.temporary, entry.remote, cancelled, value -> entry.percent = value);
                entry.state = State.SENT;
            } catch (Exception error) {
                entry.state = cancelled.getAsBoolean() || error instanceof CancellationException ? State.CANCELLED : State.FAILED;
                entry.detail = error.getMessage() == null ? "传输未完成" : error.getMessage();
            }
        }
        SwingUtilities.invokeLater(() -> { if (!closed.get()) refresh(); });
    }
    private void cancelAll() {
        for (Batch batch : batches) batch.cancelled.set(true);
        sender.cancel();
        refresh();
    }
    private void showDialog() {
        if (dialog == null) {
            dialog = new JDialog(owner, "拖拽文件传输", false);
            dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            JPanel panel = new JPanel(new BorderLayout(8, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            JLabel hint = new JLabel("可继续拖入多个文件；每批单独保存到拖入时选中的手机。");
            panel.add(hint, BorderLayout.NORTH);
            model = new AbstractTableModel() {
                public int getRowCount() { return rows.size(); }
                public int getColumnCount() { return 4; }
                public String getColumnName(int column) { return new String[]{"文件", "大小", "手机", "状态"}[column]; }
                public Object getValueAt(int row, int column) {
                    Entry entry = rows.get(row);
                    if (column == 0) return entry.remote.substring(entry.remote.lastIndexOf('/') + 1);
                    if (column == 1) return size(entry.bytes);
                    if (column == 2) {
                        String sn = entry.batch.target.sn;
                        return sn.contains(":") ? "Wi-Fi · " + sn : "USB · " + sn.substring(Math.max(0, sn.length() - 4));
                    }
                    switch (entry.state) {
                        case SENDING: return entry.percent > 0 ? "发送中 " + entry.percent + "%" : "正在发送";
                        case SENT: return "已发送";
                        case FAILED: return "失败：" + entry.detail;
                        case CANCELLED: return "已取消";
                        case SKIPPED: return "跳过：" + entry.detail;
                        default: return "等待发送";
                    }
                }
            };
            table = new JTable(model) {
                public String getToolTipText(java.awt.event.MouseEvent event) {
                    int row = rowAtPoint(event.getPoint());
                    if (row < 0) return null;
                    Entry entry = rows.get(row);
                    return entry.detail.isEmpty() ? entry.remote : entry.detail;
                }
            };
            table.setRowHeight(28);
            table.getColumnModel().getColumn(0).setPreferredWidth(240);
            table.getColumnModel().getColumn(1).setPreferredWidth(80);
            table.getColumnModel().getColumn(2).setPreferredWidth(150);
            table.getColumnModel().getColumn(3).setPreferredWidth(230);
            table.getSelectionModel().addListSelectionListener(event -> updateLocation());
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel bottom = new JPanel(new BorderLayout(6, 8));
            summary = new JLabel("等待发送");
            progress = new JProgressBar(); progress.setStringPainted(true);
            JPanel counts = new JPanel(new BorderLayout(8, 0));
            counts.add(summary, BorderLayout.WEST); counts.add(progress, BorderLayout.CENTER);
            bottom.add(counts, BorderLayout.NORTH);
            location = new JLabel();
            location.setToolTipText("文件保存到手机的调试目录；选中一行可查看对应批次位置");
            bottom.add(location, BorderLayout.CENTER);
            JButton copy = new JButton("复制保存位置");
            copy.addActionListener(event -> {
                if (selectedDirectory().isBlank()) { summary.setText("尚无传输记录"); return; }
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selectedDirectory()), null);
                    summary.setText("保存位置已复制");
                } catch (IllegalStateException error) { summary.setText("剪贴板暂不可用，请重试"); }
            });
            cancel = new JButton("取消未完成"); cancel.addActionListener(event -> cancelAll());
            JButton hide = new JButton("隐藏（继续传输）"); hide.addActionListener(event -> dialog.setVisible(false));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            for (JButton button : new JButton[]{copy, cancel, hide}) { button.setFocusable(false); buttons.add(button); }
            bottom.add(buttons, BorderLayout.SOUTH);
            panel.add(bottom, BorderLayout.SOUTH);
            dialog.setContentPane(panel);
            dialog.setSize(800, 420); dialog.setLocationRelativeTo(owner);
            install(panel);
            timer = new Timer(300, event -> refresh()); timer.start();
        }
        dialog.setVisible(true);
        dialog.toFront();
    }
    private String selectedDirectory() {
        int row = table == null ? -1 : table.getSelectedRow();
        return row >= 0 && row < rows.size() ? rows.get(row).batch.directory
            : batches.isEmpty() ? "" : batches.get(batches.size() - 1).directory;
    }
    private void updateLocation() {
        if (location == null) return;
        location.setText("保存位置：" + selectedDirectory()); location.setToolTipText(selectedDirectory());
    }
    private void refresh() {
        if (model == null) return;
        int sent = 0, failed = 0, ended = 0, cancelled = 0;
        for (Entry entry : rows) {
            if (entry.state == State.SENT) sent++;
            if (entry.state == State.FAILED || entry.state == State.SKIPPED) failed++;
            if (entry.state == State.CANCELLED) cancelled++;
            if (entry.state != State.WAITING && entry.state != State.SENDING) ended++;
        }
        if (!rows.isEmpty()) model.fireTableRowsUpdated(0, rows.size() - 1);
        updateLocation();
        progress.setMaximum(Math.max(1, rows.size())); progress.setValue(ended);
        progress.setString(ended + " / " + rows.size());
        summary.setText("已发送 " + sent + "，失败/跳过 " + failed
            + (cancelled > 0 ? "，取消 " + cancelled : "") + (ended < rows.size() ? "，传输中" : "，已结束"));
        cancel.setEnabled(ended < rows.size());
        String message = ended < rows.size() ? "文件传输中 " + ended + "/" + rows.size() : "文件传输结束：" + sent + " 个成功";
        if (!message.equals(lastStatus)) { lastStatus = message; status.accept(message); }
        if (ended == rows.size()) timer.stop();
        else if (!timer.isRunning()) timer.start();
    }
    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Batch batch : batches) batch.cancelled.set(true);
        sender.cancel(); worker.shutdownNow();
        SwingUtilities.invokeLater(() -> {
            if (timer != null) timer.stop();
            if (dialog != null) dialog.dispose();
        });
    }
}

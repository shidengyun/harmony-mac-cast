import com.formdev.flatlaf.FlatIntelliJLaf;
import com.google.gson.GsonBuilder;
import com.huawei.hosscrcpy.api.HosRemoteConfig;
import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.api.ScreenCapCallback;
import com.huawei.hosscrcpy.api.Size;
import forms.MainForm;
import utils.TreeUtil;
import utils.entity.JsonStructure;
import utils.swing.MultiFunctionLabel;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/** A compact Mac-oriented presentation around the existing capture and input widgets. */
public final class CastAppearance {
    static final Color SURFACE = new Color(0xF3F5F8);
    static final Color INK = new Color(0x182536);
    static final Color MUTED = new Color(0x738195);
    static final Color LINE = new Color(0xE0E5ED);
    static final Color BLUE = new Color(0x4267D5);
    static final int PHONE_CORNER_ARC = 25;
    static final String DEFAULT_UNLOCK_PIN = "880925";

    /** User stream settings applied when a capture session starts; 0/unset keeps SDK defaults. */
    static final class CastSettings {
        int scale;
        int bitRateMbps;
        int frameRate;
        int iFrameIntervalMs;
        int port;
        int screenId = -1;
    }
    private static final CastSettings castSettings = new CastSettings();

    /** Renew a short screen-off timeout while casting, without changing saved phone settings. */
    static final class KeepAwake {
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cast-keep-awake");
            thread.setDaemon(true);
            return thread;
        });
        private String activeSn; // Accessed only by worker.
        private long generation;

        KeepAwake() {
            worker.scheduleWithFixedDelay(() -> {
                if (activeSn != null) command(activeSn, false);
            }, 15, 15, TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "cast-restore-screen-timeout"));
        }

        synchronized long acquire(String sn) {
            long token = ++generation;
            worker.execute(() -> {
                if (activeSn != null && !activeSn.equals(sn)) command(activeSn, true);
                activeSn = sn;
                command(sn, false);
            });
            return token;
        }

        synchronized void release() {
            ++generation;
            worker.execute(this::restore);
        }

        synchronized void release(long token) {
            if (token == generation) release();
        }

        private void restore() {
            if (activeSn != null) {
                command(activeSn, true);
                activeSn = null;
            }
        }

        void close() {
            if (worker.isShutdown()) return;
            try {
                worker.submit(this::restore).get(8, TimeUnit.SECONDS);
            } catch (Exception error) {
                System.err.println("恢复手机息屏时间失败: " + error);
            } finally {
                worker.shutdownNow();
            }
        }

        private void command(String sn, boolean restore) {
            Process process = null;
            try {
                String hdc = hdcPath();
                if (hdc == null) throw new IOException("未找到 hdc 工具");
                String[] args = restore
                    ? new String[]{hdc, "-t", sn, "shell", "power-shell", "timeout", "-r"}
                    : new String[]{hdc, "-t", sn, "shell", "power-shell", "timeout", "-o", "60000"};
                process = new ProcessBuilder(args).redirectErrorStream(true).start();
                if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("hdc 超时");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String expected = restore ? "Restore screen off time" : "Override screen off time to 60000";
                if (process.exitValue() != 0 || !output.contains(expected)
                        || output.toLowerCase(java.util.Locale.ROOT).contains("failed")) {
                    throw new IOException(output.trim());
                }
            } catch (Exception error) {
                System.err.println((restore ? "恢复息屏时间失败: " : "保持手机常亮失败: ") + sn + " " + error);
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }
    }

    static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = MainForm.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(name, error); }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field field = MainForm.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(name, error); }
    }

    /** Picks a local file and sends it to the phone, or pulls a phone file to the Mac. */
    private static void transferFiles(Window window, JComboBox<?> devices, JLabel status, JButton files) {
        String[] directions = {"发送到手机", "从手机接收", "拖拽传输记录"};
        int direction = JOptionPane.showOptionDialog(window, "选择文件传输方向", "文件传输",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, directions, directions[0]);
        if (direction == 2) { if (window.fileDrops != null) window.fileDrops.showTransfers(); return; }
        if (direction < 0) return;
        String[] hdcSn = hdcAndDevice(devices, status);
        if (hdcSn == null) return;
        String hdc = hdcSn[0];
        String sn = hdcSn[1];
        if (direction == 0) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择要发送到手机的文件");
            if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
            File local = chooser.getSelectedFile();
            if (local == null) return;
            String remote = JOptionPane.showInputDialog(window,
                "手机端保存路径：", "/data/local/tmp/" + local.getName());
            if (remote == null || remote.trim().isEmpty()) return;
            runFileTransfer(status, files, hdc, sn, "send", local.getAbsolutePath(), remote.trim());
        } else if (direction == 1) {
            String remote = JOptionPane.showInputDialog(window,
                "手机端文件路径（如 /data/local/tmp/xxx.jpg）：", "/data/local/tmp/");
            if (remote == null || remote.trim().isEmpty()) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择保存到电脑的位置");
            String name = remote.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            chooser.setSelectedFile(new File(System.getProperty("user.home") + "/Desktop", name));
            if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return;
            File local = chooser.getSelectedFile();
            if (local == null) return;
            runFileTransfer(status, files, hdc, sn, "recv", remote.trim(), local.getAbsolutePath());
        }
    }

    /** Resolves the hdc binary and the device selected in the dropdown, or shows a status hint. */
    private static String[] hdcAndDevice(JComboBox<?> devices, JLabel status) {
        Object selected = devices.getSelectedItem();
        if (!(selected instanceof utils.entity.Device)) {
            status.setText("请先在列表中选择手机设备");
            return null;
        }
        String hdc = hdcPath();
        if (hdc == null) {
            status.setText("未找到 hdc 工具");
            return null;
        }
        return new String[]{hdc, ((utils.entity.Device) selected).getSn()};
    }

    private static String hdcPath() {
        String env = System.getenv("CAST_HDC_PATH");
        if (env != null && new File(env).canExecute()) return env;
        String root = System.getenv("CAST_PROJECT_DIR");
        if (root == null) root = System.getProperty("user.dir");
        String arch = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "intel";
        File local = new File(root, "vendor/" + arch + "/toolchains/hdc");
        if (local.canExecute()) return local.getAbsolutePath();
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(File.pathSeparator)) {
            File candidate = new File(directory, "hdc");
            if (candidate.canExecute()) return candidate.getAbsolutePath();
        }
        return null;
    }

    private static void runFileTransfer(JLabel status, JButton files, String hdc, String sn,
            String op, String first, String second) {
        status.setText("发送".equals(op) ? "正在发送文件…" : "正在接收文件…");
        files.setEnabled(false);
        runHdcAsync(new String[]{hdc, "-t", sn, "file", op, first, second}, output -> {
            files.setEnabled(true);
            boolean ok = !output.startsWith("[exit=") && !output.contains("[Fail]")
                    && !output.toLowerCase().contains("failed");
            status.setText(ok ? "文件传输完成：" + new File(first).getName() : "文件传输失败");
            if (!ok) System.err.println("hdc file " + op + " 失败\n" + output);
        });
    }

    /** Runs an hdc command in the background and hands its combined output to the callback on the EDT. */
    private static void runHdcAsync(String[] command, Consumer<String> done) {
        new Thread(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = process.waitFor();
                SwingUtilities.invokeLater(() -> done.accept((code == 0 ? "" : "[exit=" + code + "]\n") + output));
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> done.accept("[异常] " + error));
            }
        }, "hdc-task").start();
    }

    /** Port-forward dialog: map a Mac port to a phone port over hdc. */
    private static void portForward(Window window, JComboBox<?> devices, JLabel status, JButton button) {
        String[] hdcSn = hdcAndDevice(devices, status);
        if (hdcSn == null) return;
        String hdc = hdcSn[0];
        String sn = hdcSn[1];
        JDialog dialog = new JDialog((JFrame) window, "端口转发", true);
        JPanel panel = new JPanel(new BorderLayout(8, 10));
        panel.setBorder(new EmptyBorder(14, 14, 14, 14));
        panel.setBackground(Color.WHITE);
        JTextField localPort = new JTextField(6);
        JTextField remotePort = new JTextField(6);
        JButton addButton = new JButton("添加转发");
        JComboBox<String> rules = new JComboBox<>();
        rules.setPreferredSize(new Dimension(220, 28));
        JButton refreshButton = new JButton("刷新列表");
        JButton removeButton = new JButton("删除选中");
        JTextArea output = new JTextArea(10, 52);
        output.setEditable(false);
        output.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(output);
        JButton closeButton = new JButton("关闭");
        for (JButton b : new JButton[]{addButton, refreshButton, removeButton, closeButton}) b.setFocusable(false);
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        inputRow.setOpaque(false);
        inputRow.add(label("本地端口", 12, Font.PLAIN, MUTED));
        inputRow.add(localPort);
        inputRow.add(label("→ 手机端口", 12, Font.PLAIN, MUTED));
        inputRow.add(remotePort);
        inputRow.add(addButton);
        JPanel ruleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ruleRow.setOpaque(false);
        ruleRow.add(label("当前转发", 12, Font.PLAIN, MUTED));
        ruleRow.add(rules);
        ruleRow.add(refreshButton);
        ruleRow.add(removeButton);
        JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
        top.setOpaque(false);
        top.add(inputRow);
        top.add(ruleRow);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.setOpaque(false);
        bottom.add(closeButton);
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        dialog.getContentPane().add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        Runnable refreshList = () -> runHdcAsync(new String[]{hdc, "-t", sn, "fport", "ls"}, raw -> {
            StringBuilder visible = new StringBuilder();
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (String line : raw.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3 && parts[0].equals(sn)
                        && parts[1].startsWith("tcp:") && parts[2].startsWith("tcp:")) {
                    model.addElement(parts[1] + " " + parts[2]);
                    visible.append(line.trim()).append('\n');
                }
            }
            rules.setModel(model);
            output.append(visible.length() == 0 ? "该设备没有 tcp→tcp 转发规则\n" : visible.toString());
        });
        addButton.addActionListener(event -> {
            int local = parsePort(localPort.getText());
            int remote = parsePort(remotePort.getText());
            if (local < 0 || remote < 0) {
                output.append("端口无效（1-65535）\n");
                return;
            }
            addButton.setEnabled(false);
            runHdcAsync(new String[]{hdc, "-t", sn, "fport", "tcp:" + local, "tcp:" + remote}, raw -> {
                addButton.setEnabled(true);
                output.append(raw.trim() + "\n");
                refreshList.run();
            });
        });
        refreshButton.addActionListener(event -> {
            output.append("—— 刷新转发列表 ——\n");
            refreshList.run();
        });
        removeButton.addActionListener(event -> {
            String rule = (String) rules.getSelectedItem();
            if (rule == null) {
                output.append("列表中没有可删除的规则\n");
                return;
            }
            removeButton.setEnabled(false);
            runHdcAsync(new String[]{hdc, "-t", sn, "fport", "rm", rule}, raw -> {
                removeButton.setEnabled(true);
                output.append(raw.trim() + "\n");
                if (raw.contains("is not exist")) {
                    output.append("提示：当前 hdc 版本删除可能不生效，重启 App 或拔插设备可清除转发。\n");
                }
            });
        });
        closeButton.addActionListener(event -> dialog.dispose());
        refreshList.run();
        dialog.setVisible(true);
    }

    /** Installs a HarmonyOS package (.hap/.hsp/.app) on the selected device. */
    private static void installApp(Window window, JComboBox<?> devices, JLabel status, JButton button) {
        String[] hdcSn = hdcAndDevice(devices, status);
        if (hdcSn == null) return;
        String hdc = hdcSn[0];
        String sn = hdcSn[1];
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择鸿蒙应用包（.hap / .hsp / .app）");
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
        File pkg = chooser.getSelectedFile();
        if (pkg == null) return;
        int replace = JOptionPane.showConfirmDialog(window,
            "安装 " + pkg.getName() + "\n\n覆盖安装同名应用？", "安装选项",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (replace == JOptionPane.CANCEL_OPTION) return;
        boolean replaceInstall = replace == JOptionPane.YES_OPTION;
        status.setText("正在安装 " + pkg.getName() + " …");
        button.setEnabled(false);
        String[] command = replaceInstall
            ? new String[]{hdc, "-t", sn, "install", "-r", pkg.getAbsolutePath()}
            : new String[]{hdc, "-t", sn, "install", pkg.getAbsolutePath()};
        runHdcAsync(command, raw -> {
            button.setEnabled(true);
            String lower = raw.toLowerCase();
            boolean ok = raw.contains("successfully") || (raw.contains("[Info]") && !lower.contains("error:"));
            status.setText(ok ? "安装完成" : "安装失败");
            JTextArea detail = new JTextArea(raw.trim());
            detail.setEditable(false);
            detail.setFont(new Font("Monospaced", Font.PLAIN, 12));
            JOptionPane.showMessageDialog(window, new JScrollPane(detail),
                ok ? "安装结果" : "安装失败",
                ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
        });
    }

    private static int parsePort(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= 65535 ? value : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    public static void installDefaults() {
        FlatIntelliJLaf.setup();
        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 13));
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("Component.focusColor", BLUE);
        UIManager.put("Panel.background", SURFACE);
        UIManager.put("Label.foreground", INK);
        UIManager.put("Button.background", Color.WHITE);
        UIManager.put("Button.foreground", INK);
        UIManager.put("ComboBox.background", Color.WHITE);
        UIManager.put("ComboBox.buttonBackground", Color.WHITE);
    }

    /** Suppress upstream's fixed image-size window jumps; native user resizing still works. */
    public static class Window extends MainForm {
        private final KeepAwake keepAwake = new KeepAwake();
        private CaptureRecovery recovery;
        private CaptureConnection connection;
        private FileDropTransfer fileDrops;
        private volatile CaptureSession captureSession;
        private PhoneKeyboard keyboard;
        private SidebarShortcuts shortcuts;
        private Runnable fitCollapsedWindow;
        private boolean unlockBusy;
        private final ExecutorService captureTasks = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "cast-session");
            thread.setDaemon(true);
            return thread;
        });
        private boolean styled;
        private Timer statusTimer;
        private int refreshTicks;
        private boolean keepTimerOnDispose;
        private boolean refreshingDevices;
        private boolean updatingDevices;
        public Window() {
            super();
            apply(this);
            Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
            super.setMinimumSize(new Dimension(640, 480));
            super.setSize(Math.min(780, desktop.width - 80), Math.min(900, desktop.height - 100));
            setLocationRelativeTo(null);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                recovery.close();
                captureTasks.shutdownNow();
                if (fileDrops != null) fileDrops.close();
                if (keyboard != null) keyboard.close();
            }, "cast-close-session"));
            styled = true;
        }
        @Override public void setSize(int width, int height) {
            if (!styled) super.setSize(width, height);
        }
        @Override public void setSize(Dimension size) {
            if (!styled) super.setSize(size);
        }
        @Override public void dispose() {
            if (!keepTimerOnDispose) {
                if (statusTimer != null) statusTimer.stop();
                if (recovery != null) recovery.close();
                stopCaptureSession(this);
                keepAwake.release();
                if (fileDrops != null) fileDrops.close();
                if (shortcuts != null) shortcuts.close();
            }
            super.dispose();
        }
    }

    private static JLabel label(String text, int size, int weight, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Dialog", weight, size));
        label.setForeground(color);
        return label;
    }

    private static JPanel buttonGrid(JButton... buttons) {
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
        grid.setOpaque(false);
        for (JButton button : buttons) {
            button.setPreferredSize(new Dimension(124, 36));
            button.setFont(new Font("Dialog", Font.PLAIN, 12));
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setMargin(new Insets(6, 8, 6, 8));
            grid.add(button);
        }
        return grid;
    }

    private static void addControlSection(JPanel panel, String title, JButton... buttons) {
        JPanel section = new JPanel(new BorderLayout(0, 8));
        section.setOpaque(false);
        section.setBorder(new EmptyBorder(0, 0, 18, 0));
        section.add(label(title, 12, Font.BOLD, MUTED), BorderLayout.NORTH);
        section.add(buttonGrid(buttons), BorderLayout.CENTER);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(section);
    }

    /** Keep connection, recording and macro status readable within the fixed sidebar. */
    private static final class StatusLabel extends JLabel {
        StatusLabel() {
            setFont(new Font("Dialog", Font.PLAIN, 11));
            setForeground(MUTED);
            setVerticalAlignment(SwingConstants.TOP);
            setText("等待连接");
        }
        @Override public void setText(String text) {
            String value = text == null ? "" : text;
            super.setText("<html><div style='width:236px'>"
                + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</div></html>");
            setToolTipText(value);
            getAccessibleContext().setAccessibleName(value);
        }
    }

    private static void apply(Window window) {
        MultiFunctionLabel original = field(window, "multiFunctionLabel", MultiFunctionLabel.class);
        RotatingMultiFunctionLabel rotating = new RotatingMultiFunctionLabel();
        try {
            Field[] callbacks = {
                MultiFunctionLabel.class.getDeclaredField("actualTimeControlCallBack"),
                MultiFunctionLabel.class.getDeclaredField("mouseWheelCallBack"),
                MultiFunctionLabel.class.getDeclaredField("dumpLayoutCallBack"),
                MultiFunctionLabel.class.getDeclaredField("keyBoardCallBack"),
            };
            java.util.List<java.lang.reflect.Method> setters = java.util.Arrays.asList(
                MultiFunctionLabel.class.getMethod("setActualTimeControlCallBack", utils.callbacks.ActualTimeControlCallBack.class),
                MultiFunctionLabel.class.getMethod("setMouseWheelCallBack", utils.callbacks.MouseWheelCallBack.class),
                MultiFunctionLabel.class.getMethod("setDumpLayoutCallBack", utils.callbacks.DumpLayoutCallBack.class),
                MultiFunctionLabel.class.getMethod("setKeyBoardCallBack", utils.callbacks.KeyBoardCallBack.class));
            for (int i = 0; i < callbacks.length; i++) {
                callbacks[i].setAccessible(true);
                Object callback = callbacks[i].get(original);
                if (callback != null) setters.get(i).invoke(rotating, callback);
            }
        } catch (ReflectiveOperationException error) {
            System.err.println("复制输入回调失败: " + error);
        }
        rotating.setScreenCapturingMode(original.isScreenCapturingMode());
        setField(window, "multiFunctionLabel", rotating);
        MultiFunctionLabel display = rotating;
        CaptureConnection connectionCheck = new CaptureConnection(hdcPath());
        window.connection = connectionCheck;
        window.recovery = new CaptureRecovery(new CaptureRecovery.Backend() {
            public CaptureRecovery.Health probe(CaptureRecovery.Target target, long token, boolean checkService) throws Exception {
                CaptureSession session = window.captureSession;
                return connectionCheck.probe(target,
                    session != null && session.token == token ? session.remote : null,
                    session != null && session.imageMode, checkService);
            }
            public void restart(CaptureRecovery.Target target, long token) {
                SwingUtilities.invokeLater(() -> {
                    if (window.recovery.isCurrent(token)) startCaptureAttempt(window, target, token);
                });
            }
        });
        window.keyboard = new PhoneKeyboard(() -> {
            CaptureSession session = window.captureSession;
            if (session == null || window.recovery.state() != CaptureRecovery.State.CONNECTED) return null;
            return new PhoneKeyboard.Binding(session.remote, session.token,
                () -> window.recovery.isCurrent(session.token)
                    && window.recovery.state() == CaptureRecovery.State.CONNECTED);
        });
        rotating.setKeyBoardCallBack(new utils.callbacks.KeyBoardCallBack() {
            public void onKeyBoardDown(KeyEvent event, int code, boolean shift) {
                if (event != null && event.isMetaDown() && event.getKeyCode() == KeyEvent.VK_V) {
                    onPressPaste();
                } else window.keyboard.key(event, code, shift);
            }
            public void onPressPaste() {
                try {
                    Object text = Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    if (text != null) window.keyboard.text(text.toString());
                } catch (Exception error) {
                    System.err.println("读取剪贴板失败: " + error.getClass().getSimpleName());
                }
            }
            public void onChineseCharInput(String text) { window.keyboard.text(text); }
        });
        // A Mac trackpad delivers wheel events as a rapid burst, plus momentum events after
        // the fingers lift. The upstream callback sent a wheel command with an immediate stop
        // per event and treated every rotation other than +1 as "up", so a light swipe made
        // the phone scroll endlessly and jitter. Coalesce the rotation into per-unit
        // dispatches and send the stop only once the gesture goes idle.
        for (MouseWheelListener listener : rotating.getMouseWheelListeners()) {
            rotating.removeMouseWheelListener(listener);
        }
        final Point[] lastWheelPoint = {null};
        WheelCoalescer wheel = new WheelCoalescer(new WheelCoalescer.Sender() {
            @Override public boolean capturing() { return rotating.isScreenCapturingMode(); }
            @Override public void scroll(boolean up) {
                Object remote = field(window, "hosRemoteDevice", Object.class);
                if (remote instanceof HosRemoteDevice) {
                    sendWheelScroll((HosRemoteDevice) remote, rotating, lastWheelPoint[0], up, window);
                }
            }
            @Override public void stop() {
                Object remote = field(window, "hosRemoteDevice", Object.class);
                if (remote instanceof HosRemoteDevice && canUseCompleteMouse(window)) {
                    Point point = wheelPoint(rotating, lastWheelPoint[0]);
                    if (point != null) ((HosRemoteDevice) remote).onMouseWheelStop(point.x, point.y);
                }
            }
        });
        rotating.addMouseWheelListener(event -> {
            if (!rotating.isScreenCapturingMode()) return;
            lastWheelPoint[0] = event.getPoint();
            if (event.isControlDown()) {
                // Mac pinch zoom arrives as Ctrl+wheel; send a two-finger gesture.
                pinchAt(window, rotating, event.getPoint(), event.getPreciseWheelRotation());
                return;
            }
            wheel.onWheel(event.getPreciseWheelRotation());
        });
        final java.util.List<long[]> macroEvents = new java.util.ArrayList<>();
        final boolean[] macroRecording = {false};
        final boolean[] macroReplaying = {false};
        final long[] macroStart = {0};
        rotating.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                Point point = wheelPoint(rotating, e.getPoint());
                if (point != null) recordMacro(macroEvents, macroRecording, macroReplaying, macroStart, 2, point.x, point.y);
            }
        });
        installTouchHandler(window, rotating, macroEvents, macroRecording, macroReplaying, macroStart);
        JComboBox<?> devices = field(window, "cmbSn", JComboBox.class);
        JButton originalStart = field(window, "btnEnterScreenCapture", JButton.class);
        JButton refresh = field(window, "btnRefreshDevice", JButton.class);
        for (ActionListener listener : refresh.getActionListeners()) refresh.removeActionListener(listener);
        refresh.addActionListener(event -> {
            if (window.refreshingDevices) return;
            window.refreshingDevices = true;
            refresh.setEnabled(false);
            new SwingWorker<java.util.List<utils.entity.Device>, Void>() {
                protected java.util.List<utils.entity.Device> doInBackground() throws Exception {
                    java.util.List<utils.entity.Device> found = connectionCheck.discoverLocalDevices();
                    found.addAll(utils.HdcUtil.getRemoteDevice());
                    return found;
                }
                @SuppressWarnings("unchecked")
                protected void done() {
                    try {
                        java.util.List<utils.entity.Device> found = get();
                        Object selection = devices.getSelectedItem();
                        CaptureRecovery.Target activeTarget = window.recovery.target();
                        JComboBox<utils.entity.Device> list = (JComboBox<utils.entity.Device>) devices;
                        window.updatingDevices = true;
                        list.removeAllItems();
                        utils.entity.Device keep = null;
                        for (utils.entity.Device device : found) {
                            list.addItem(device);
                            if (activeTarget != null && activeTarget.sn.equals(device.getSn()) && activeTarget.ip.equals(device.getIp())) keep = device;
                            if (activeTarget == null && selection instanceof utils.entity.Device
                                    && ((utils.entity.Device) selection).getSn().equals(device.getSn())
                                    && ((utils.entity.Device) selection).getIp().equals(device.getIp())) keep = device;
                        }
                        if (keep == null && activeTarget != null && window.recovery.active()) {
                            keep = new utils.entity.Device(activeTarget.ip, activeTarget.sn);
                            list.addItem(keep);
                        }
                        if (keep != null) list.setSelectedItem(keep);
                    } catch (Exception error) {
                        System.err.println("刷新设备失败: " + error.getClass().getSimpleName());
                    } finally {
                        window.updatingDevices = false;
                        window.refreshingDevices = false;
                        refresh.setEnabled(true);
                    }
                }
            }.execute();
        });
        JPanel shell = new JPanel(new BorderLayout()) {
            @Override protected boolean isPaintingOrigin() {
                // Incoming frames repaint a child; keep them inside the window's corner clip.
                return window.isUndecorated() && window.getBackground().getAlpha() == 0;
            }
            @Override public void paint(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                if (window.isUndecorated() && window.getBackground().getAlpha() == 0) {
                    g.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(),
                        PHONE_CORNER_ARC, PHONE_CORNER_ARC));
                }
                super.paint(g);
                g.dispose();
            }
        };
        shell.setBackground(SURFACE);
        JPanel header = new JPanel(new BorderLayout(12, 12));
        header.setBackground(Color.WHITE);
        header.setBorder(new EmptyBorder(18, 16, 16, 16));

        JPanel identity = new JPanel(new BorderLayout(10, 0));
        identity.setOpaque(false);
        JLabel mark = new JLabel(new LineIcon("phone", BLUE, 26));
        JPanel words = new JPanel(new GridLayout(2, 1, 0, 3));
        words.setOpaque(false);
        words.add(label("鸿蒙投屏", 18, Font.BOLD, INK));
        words.add(label("手机画面，实时同步", 11, Font.PLAIN, MUTED));
        identity.add(mark, BorderLayout.WEST);
        identity.add(words, BorderLayout.CENTER);
        JLabel status = new StatusLabel();
        rotating.setArrowSwipe(direction -> swipeDirection(window, status, direction));
        status.setIcon(new StatusDot(MUTED));
        header.add(identity, BorderLayout.NORTH);
        for (ActionListener listener : originalStart.getActionListeners()) {
            originalStart.removeActionListener(listener);
        }
        originalStart.addActionListener(event -> toggleCapture(window, devices, status, originalStart));

        JPanel connection = new JPanel(new BorderLayout(8, 0));
        connection.setOpaque(false);
        devices.setPreferredSize(new Dimension(160, 34));
        devices.setToolTipText("选择 USB 手机或 Wi-Fi 地址；USB 接入后自动读取无线地址");
        devices.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                String text = value == null ? "连接手机后点击刷新" : value.toString();
                if (value instanceof utils.entity.Device) {
                    String sn = ((utils.entity.Device) value).getSn();
                    text = sn.contains(":") ? "Wi-Fi · " + sn.replace(":8710", "")
                                            : "USB 设备 · " + sn.substring(Math.max(0, sn.length() - 4));
                }
                JLabel result = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focused);
                result.setBorder(new EmptyBorder(5, 10, 5, 10));
                return result;
            }
        });
        connection.add(label("手机设备", 12, Font.PLAIN, MUTED), BorderLayout.NORTH);
        connection.add(devices, BorderLayout.CENTER);
        devices.addItemListener(event -> {
            if (window.updatingDevices || event.getStateChange() != ItemEvent.SELECTED) return;
            Object selected = devices.getSelectedItem();
            Object remote = field(window, "hosRemoteDevice", Object.class);
            boolean capturing = window.recovery.active();
            if (selected instanceof utils.entity.Device && remote instanceof HosRemoteDevice && capturing) {
                String selectedSn = ((utils.entity.Device) selected).getSn();
                String currentSn = ((HosRemoteDevice) remote).getSn();
                if (!selectedSn.equals(currentSn)) {
                    System.out.println("切换到设备: " + selectedSn);
                    originalStart.setText("停止投屏");
                    startScreenCaptureWithSettings(window, (utils.entity.Device) selected);
                }
            }
        });
        refresh.setText("");
        refresh.setIcon(new LineIcon("refresh", MUTED, 18));
        refresh.setToolTipText("刷新设备");
        refresh.getAccessibleContext().setAccessibleName("刷新设备");
        refresh.setPreferredSize(new Dimension(36, 36));
        refresh.setFocusable(false);
        final ScreenRecorder recorder = new ScreenRecorder();
        JButton shot = new JButton(new LineIcon("shot", INK, 18));
        shot.setToolTipText("保存截图");
        shot.getAccessibleContext().setAccessibleName("保存截图");
        shot.setPreferredSize(new Dimension(36, 36));
        shot.setFocusable(false);
        shot.addActionListener(event -> saveScreenshot(window, rotating, status));
        JButton rec = new JButton(new LineIcon("record", INK, 18));
        rec.setToolTipText("开始录制");
        rec.getAccessibleContext().setAccessibleName("开始或停止录制");
        rec.setPreferredSize(new Dimension(36, 36));
        rec.setFocusable(false);
        rec.addActionListener(event -> {
            if (recorder.isRecording()) {
                File file = recorder.stop();
                rec.setIcon(new LineIcon("record", INK, 18));
                rec.setToolTipText("开始录制");
                status.setText(file != null ? "录制已保存: " + file.getName() : "录制停止");
            } else {
                BufferedImage image = display.getImage();
                if (image == null) return;
                try {
                    File file = recorder.start(image);
                    rec.setIcon(new LineIcon("record", new Color(0xC0392B), 18));
                    rec.setToolTipText("停止录制");
                    status.setText("正在录制: " + file.getName());
                } catch (Exception error) {
                    System.err.println("启动录制失败: " + error);
                    status.setText("录制启动失败");
                }
            }
        });
        JButton pin = new JButton(new LineIcon("pin", INK, 18));
        pin.setName("pinWindow");
        pin.setToolTipText("窗口置顶");
        pin.getAccessibleContext().setAccessibleName("窗口置顶或取消");
        pin.setPreferredSize(new Dimension(36, 36));
        pin.setFocusable(false);
        Runnable updatePin = () -> {
            boolean on = window.isAlwaysOnTop();
            pin.setSelected(on);
            pin.setIcon(new LineIcon("pin", on ? BLUE : INK, 18));
            pin.setText(on ? "已置顶" : "窗口置顶");
            pin.setForeground(on ? BLUE : INK);
            pin.setToolTipText(on ? "取消置顶" : "窗口置顶");
            pin.getAccessibleContext().setAccessibleName(on ? "取消窗口置顶" : "窗口置顶");
        };
        window.addPropertyChangeListener("alwaysOnTop", event -> updatePin.run());
        pin.addActionListener(event -> window.setAlwaysOnTop(!window.isAlwaysOnTop()));
        JButton start = new JButton("开始投屏");
        start.setBackground(BLUE);
        start.setForeground(Color.WHITE);
        start.setPreferredSize(new Dimension(96, 36));
        start.setFocusable(false);
        start.addActionListener(event -> originalStart.doClick());
        JButton files = new JButton(new LineIcon("transfer", INK, 18));
        files.setToolTipText("文件传输");
        files.getAccessibleContext().setAccessibleName("文件传输");
        files.setPreferredSize(new Dimension(36, 36));
        files.setFocusable(false);
        files.addActionListener(event -> transferFiles(window, devices, status, files));
        JButton forward = new JButton(new LineIcon("forward", INK, 18));
        forward.setToolTipText("把手机上的端口映射到电脑，如 localhost:8080 → 手机 80");
        forward.getAccessibleContext().setAccessibleName("端口转发");
        forward.setPreferredSize(new Dimension(36, 36));
        forward.setFocusable(false);
        forward.addActionListener(event -> portForward(window, devices, status, forward));
        JButton install = new JButton(new LineIcon("install", INK, 18));
        install.setToolTipText("安装鸿蒙应用包（.hap / .hsp / .app）到手机");
        install.getAccessibleContext().setAccessibleName("安装应用");
        install.setPreferredSize(new Dimension(36, 36));
        install.setFocusable(false);
        install.addActionListener(event -> installApp(window, devices, status, install));
        JButton settings = new JButton(new LineIcon("settings", INK, 18));
        settings.setToolTipText("投屏设置（分辨率/码率/帧率）");
        settings.getAccessibleContext().setAccessibleName("投屏设置");
        settings.setPreferredSize(new Dimension(36, 36));
        settings.setFocusable(false);
        settings.addActionListener(event -> showDeviceSettings(window, devices, status));
        JButton layout = new JButton(new LineIcon("layout", INK, 18));
        layout.setToolTipText("控件树（查看/点击/导入导出布局）");
        layout.getAccessibleContext().setAccessibleName("控件树");
        layout.setPreferredSize(new Dimension(36, 36));
        layout.setFocusable(false);
        layout.addActionListener(event -> showLayoutTree(window, rotating, status));
        JButton type = new JButton(new LineIcon("type", INK, 18));
        type.setToolTipText("发送文本到手机（先点击手机上的输入框）");
        type.getAccessibleContext().setAccessibleName("发送文本");
        type.setPreferredSize(new Dimension(36, 36));
        type.setFocusable(false);
        type.addActionListener(event -> sendText(window, status));
        JButton paste = new JButton(new LineIcon("paste", INK, 18));
        paste.setToolTipText("粘贴 Mac 剪贴板文本到手机（先点击手机上的输入框）");
        paste.getAccessibleContext().setAccessibleName("粘贴剪贴板");
        paste.setPreferredSize(new Dimension(36, 36));
        paste.setFocusable(false);
        paste.addActionListener(event -> pasteClipboard(window, status));
        JButton copyText = new JButton(new LineIcon("ocr", INK, 18));
        copyText.setToolTipText("复制画面文字到 Mac（支持框选识别）");
        copyText.getAccessibleContext().setAccessibleName("复制画面文字");
        copyText.setPreferredSize(new Dimension(36, 36));
        copyText.setFocusable(false);
        copyText.addActionListener(event -> {
            BufferedImage image = rotating.getImage();
            Object remote = field(window, "hosRemoteDevice", Object.class);
            if (image != null && remote instanceof HosRemoteDevice) {
                HosRemoteDevice device = (HosRemoteDevice) remote;
                ScreenText.show(window, image, device::getLayout);
            }
        });
        final boolean[] timedOn = {false};
        final Timer[] timedTimer = {null};
        final int[] timedCount = {0};
        JButton timed = new JButton(new LineIcon("timer", INK, 18));
        timed.setToolTipText("定时截图");
        timed.getAccessibleContext().setAccessibleName("定时截图");
        timed.setPreferredSize(new Dimension(36, 36));
        timed.setFocusable(false);
        timed.addActionListener(event -> {
            if (timedOn[0]) {
                timedOn[0] = false;
                timedTimer[0].stop();
                timed.setIcon(new LineIcon("timer", INK, 18));
                timed.setToolTipText("定时截图");
                status.setText("定时截图已停止（共 " + timedCount[0] + " 张）");
                return;
            }
            String input = JOptionPane.showInputDialog(window,
                "间隔秒数（5–3600，默认 30）：", "定时截图",
                JOptionPane.PLAIN_MESSAGE);
            if (input == null) return;
            int seconds = 30;
            try { seconds = Integer.parseInt(input.trim()); } catch (NumberFormatException ignored) { }
            seconds = Math.max(5, Math.min(3600, seconds));
            timedOn[0] = true;
            timedCount[0] = 0;
            timed.setIcon(new LineIcon("timer", BLUE, 18));
            timed.setToolTipText("停止定时截图");
            status.setText("定时截图中，每 " + seconds + " 秒一张");
            timedTimer[0] = new Timer(seconds * 1000, ev -> {
                if (saveScreenshot(window, rotating, status) != null) timedCount[0]++;
            });
            timedTimer[0].setInitialDelay(seconds * 1000);
            timedTimer[0].start();
        });
        JButton frame = new JButton(new LineIcon("frame", INK, 18));
        frame.setToolTipText("刷新画面（请求关键帧）");
        frame.getAccessibleContext().setAccessibleName("刷新画面");
        frame.setPreferredSize(new Dimension(36, 36));
        frame.setFocusable(false);
        frame.addActionListener(event -> {
            Object remote = field(window, "hosRemoteDevice", Object.class);
            if (remote instanceof HosRemoteDevice) {
                try {
                    ((HosRemoteDevice) remote).requestIDRFrame();
                    status.setText("已请求刷新画面");
                } catch (Throwable error) {
                    System.err.println("请求关键帧失败: " + error);
                    status.setText("刷新画面失败");
                }
            }
        });
        JButton macroRec = new JButton(new LineIcon("record", INK, 18));
        macroRec.setToolTipText("录制宏（在手机画面上点击/拖动操作，可回放）");
        macroRec.getAccessibleContext().setAccessibleName("录制宏");
        macroRec.setPreferredSize(new Dimension(36, 36));
        macroRec.setFocusable(false);
        macroRec.addActionListener(event -> {
            if (macroRecording[0]) {
                macroRecording[0] = false;
                macroRec.setIcon(new LineIcon("record", INK, 18));
                macroRec.setToolTipText("录制宏（在手机画面上点击/拖动操作，可回放）");
                status.setText(macroEvents.isEmpty() ? "宏录制已停止（无操作）"
                    : "宏录制已保存 " + macroEvents.size() + " 个事件");
            } else {
                macroEvents.clear();
                macroStart[0] = 0;
                macroRecording[0] = true;
                macroRec.setIcon(new LineIcon("record", new Color(0xC0392B), 18));
                macroRec.setToolTipText("停止宏录制");
                status.setText("正在录制宏…（点击/拖动操作会被记录）");
            }
        });
        JButton macroPlay = new JButton(new LineIcon("replay", INK, 18));
        macroPlay.setToolTipText("回放录制的宏");
        macroPlay.getAccessibleContext().setAccessibleName("回放宏");
        macroPlay.setPreferredSize(new Dimension(36, 36));
        macroPlay.setFocusable(false);
        macroPlay.addActionListener(event -> {
            macroRecording[0] = false;
            macroRec.setIcon(new LineIcon("record", INK, 18));
            replayMacro(window, macroEvents, macroReplaying, status);
        });
        JButton lights = new JButton(new LineIcon("moon", INK, 18));
        lights.setToolTipText("关灯模式");
        lights.getAccessibleContext().setAccessibleName("关灯模式");
        lights.setPreferredSize(new Dimension(36, 36));
        lights.setFocusable(false);
        JButton light = new JButton(new LineIcon("sun", Color.WHITE, 18));
        light.setToolTipText("开灯");
        light.getAccessibleContext().setAccessibleName("开灯");
        light.setFocusable(false);
        light.setPreferredSize(new Dimension(44, 44));
        light.setBackground(BLUE);
        light.setOpaque(true);
        light.setBorder(BorderFactory.createCompoundBorder(
            new javax.swing.border.LineBorder(new Color(0xFFFFFF), 1),
            new EmptyBorder(0, 0, 0, 0)));
        light.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        light.setVisible(false);
        refresh.setText("刷新设备");
        JPanel deviceActions = buttonGrid(refresh, start);
        connection.add(deviceActions, BorderLayout.SOUTH);
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setMaximumSize(new Dimension(268, 64));
        rows.add(status);
        rows.add(Box.createVerticalStrut(12));
        connection.setAlignmentX(Component.LEFT_ALIGNMENT);
        connection.setMaximumSize(new Dimension(268, 94));
        rows.add(connection);
        header.add(rows, BorderLayout.CENTER);

        PhoneStage stage = new PhoneStage(display, light);
        stage.setName("phoneStage");
        shell.add(stage, BorderLayout.CENTER);
        String[] fields = {"btnBack", "btnVolumeDown", "btnVolumeUp", "btnPower"};
        String[] icons = {"back", "volumeDown", "volumeUp", "power"};
        String[] titles = {"返回", "降低音量", "提高音量", "锁屏 / 唤醒"};
        JButton[] controlButtons = new JButton[fields.length];
        for (int i = 0; i < fields.length; i++) {
            JButton button = field(window, fields[i], JButton.class);
            button.setText("");
            button.setIcon(new LineIcon(icons[i], INK, 20));
            button.setToolTipText(titles[i]);
            button.getAccessibleContext().setAccessibleName(titles[i]);
            button.setPreferredSize(new Dimension(48, 36));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setFocusable(false);
            controlButtons[i] = button;
        }
        JButton home = new JButton(new LineIcon("home", INK, 20));
        home.setPreferredSize(new Dimension(48, 36));
        home.setToolTipText("回桌面");
        home.getAccessibleContext().setAccessibleName("回桌面");
        home.setFocusable(false);
        home.addActionListener(event -> phoneKey(window, status, "Home"));
        JButton recents = new JButton(new LineIcon("recents", INK, 20));
        recents.setPreferredSize(new Dimension(48, 36));
        recents.setToolTipText("最近任务");
        recents.getAccessibleContext().setAccessibleName("最近任务");
        recents.setFocusable(false);
        recents.addActionListener(event -> openRecents(window, status));
        JButton unlock = new JButton(new LineIcon("unlock", INK, 20));
        unlock.setPreferredSize(new Dimension(48, 36));
        unlock.setToolTipText("解锁（唤醒 + 上滑，检测到密码键盘时自动输入）");
        unlock.getAccessibleContext().setAccessibleName("解锁");
        unlock.setFocusable(false);
        unlock.addActionListener(event -> unlockPhone(window, rotating, status, unlock));
        JButton enlarge = new JButton(new LineIcon("expand", INK, 20));
        enlarge.setPreferredSize(new Dimension(48, 36));
        enlarge.setToolTipText("放大 / 还原窗口");
        enlarge.getAccessibleContext().setAccessibleName("放大或还原窗口");
        enlarge.setFocusable(false);
        enlarge.addActionListener(event -> window.setExtendedState(
            (window.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0 ? JFrame.NORMAL : JFrame.MAXIMIZED_BOTH));
        JButton rotate = new JButton(new LineIcon("rotate", INK, 20));
        rotate.setPreferredSize(new Dimension(48, 36));
        rotate.setToolTipText("旋转画面");
        rotate.getAccessibleContext().setAccessibleName("旋转画面");
        rotate.setFocusable(false);
        final int[] viewRotation = {0};
        rotate.addActionListener(event -> {
            viewRotation[0] = (viewRotation[0] + 90) % 360;
            rotating.setViewRotation(viewRotation[0]);
            if (window.fitCollapsedWindow != null) window.fitCollapsedWindow.run();
            stage.revalidate();
            stage.repaint();
        });
        JButton deviceH = new JButton(new LineIcon("landscape", INK, 20));
        deviceH.setPreferredSize(new Dimension(48, 36));
        deviceH.setToolTipText("设备端旋转为横屏");
        deviceH.getAccessibleContext().setAccessibleName("设备端横屏");
        deviceH.setFocusable(false);
        deviceH.addActionListener(event -> deviceRotation(window, status, true));
        JButton deviceV = new JButton(new LineIcon("portrait", INK, 20));
        deviceV.setPreferredSize(new Dimension(48, 36));
        deviceV.setToolTipText("设备端旋转为竖屏");
        deviceV.getAccessibleContext().setAccessibleName("设备端竖屏");
        deviceV.setFocusable(false);
        deviceV.addActionListener(event -> deviceRotation(window, status, false));
        String[] gestureIcons = {"up", "down", "left", "right"};
        String[] gestureTips = {"快速上滑（↑ 方向键）", "快速下滑（↓ 方向键）", "快速左滑（← 方向键）", "快速右滑（→ 方向键）"};
        int[] gestureDirs = {2, 3, 0, 1};
        final JButton[] gestureButtons = new JButton[4];
        for (int i = 0; i < 4; i++) {
            final int dir = gestureDirs[i];
            JButton button = new JButton(new LineIcon(gestureIcons[i], MUTED, 15));
            button.setToolTipText(gestureTips[i]);
            button.getAccessibleContext().setAccessibleName(gestureTips[i]);
            button.setPreferredSize(new Dimension(30, 28));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setFocusable(false);
            button.addActionListener(event -> swipeDirection(window, status, dir));
            gestureButtons[i] = button;
        }
        JButton[] labeledButtons = {home, recents, controlButtons[0], unlock, controlButtons[1],
            controlButtons[2], controlButtons[3], rotate, enlarge, pin, lights, deviceH, deviceV,
            shot, rec, frame, timed, copyText, type, paste, layout, files, forward, install,
            settings, macroRec, macroPlay, gestureButtons[0], gestureButtons[1], gestureButtons[2], gestureButtons[3]};
        String[] buttonTitles = {"回桌面", "最近任务", "返回", "解锁", "降低音量", "提高音量", "锁屏 / 唤醒",
            "旋转画面", "放大 / 还原", "窗口置顶", "关灯模式", "设备横屏", "设备竖屏",
            "保存截图", "屏幕录制", "刷新画面", "定时截图", "复制画面文字", "发送文本", "粘贴", "控件树",
            "文件传输", "端口转发", "安装应用", "投屏设置", "录制宏", "回放宏", "上滑", "下滑", "左滑", "右滑"};
        for (int i = 0; i < labeledButtons.length; i++) labeledButtons[i].setText(buttonTitles[i]);
        JButton shortcutSettings = new JButton("快捷键设置", new LineIcon("settings", INK, 16));
        shortcutSettings.setName("shortcutSettings");
        shortcutSettings.setFocusable(false);
        shortcutSettings.addActionListener(event -> window.shortcuts.showSettings());
        JPanel tools = new JPanel();
        tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));
        tools.setBackground(Color.WHITE);
        tools.setBorder(new EmptyBorder(4, 16, 0, 16));
        addControlSection(tools, "导航与按键", home, recents, controlButtons[0], unlock,
            controlButtons[1], controlButtons[2], controlButtons[3]);
        addControlSection(tools, "滑动手势", gestureButtons);
        addControlSection(tools, "画面与窗口", pin, lights, rotate, enlarge, deviceH, deviceV);
        updatePin.run();
        addControlSection(tools, "截图与录屏", shot, rec, frame, timed);
        addControlSection(tools, "文字与控件", copyText, type, paste, layout);
        addControlSection(tools, "文件与工具", files, forward, install, settings, macroRec, macroPlay, shortcutSettings);
        JPanel scrollingTools = new JPanel(new BorderLayout());
        scrollingTools.setBackground(Color.WHITE);
        scrollingTools.add(tools, BorderLayout.NORTH);
        JScrollPane toolScroll = new JScrollPane(scrollingTools,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolScroll.setBorder(BorderFactory.createEmptyBorder());
        toolScroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setName("controlSidebar");
        sidebar.setBackground(Color.WHITE);
        sidebar.setPreferredSize(new Dimension(300, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, LINE));
        sidebar.add(header, BorderLayout.NORTH);
        sidebar.add(toolScroll, BorderLayout.CENTER);
        JButton sidebarToggle = new SidebarToggle();
        sidebarToggle.setName("sidebarToggle");
        sidebarToggle.setPreferredSize(new Dimension(16, 32));
        sidebarToggle.setMargin(new Insets(0, 0, 0, 0));
        sidebarToggle.setFocusable(false);
        sidebarToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel sidebarRail = new JPanel(new GridBagLayout()) {
            @Override public boolean contains(int x, int y) {
                return isOpaque() || sidebarToggle.getBounds().contains(x, y);
            }
        };
        sidebarRail.setName("sidebarRail");
        sidebarRail.setBackground(SURFACE);
        sidebarRail.setPreferredSize(new Dimension(16, 0));
        sidebarRail.add(sidebarToggle);
        JPanel controlDock = new JPanel(new BorderLayout());
        controlDock.setName("controlDock");
        controlDock.add(sidebarRail, BorderLayout.WEST);
        controlDock.add(sidebar, BorderLayout.CENTER);
        shell.add(controlDock, BorderLayout.EAST);
        window.setContentPane(shell);
        window.fileDrops = new FileDropTransfer(window, () -> {
            CaptureRecovery.Target active = window.recovery.target();
            if (active != null) return active;
            Object selected = devices.getSelectedItem();
            return selected instanceof utils.entity.Device
                ? new CaptureRecovery.Target(((utils.entity.Device) selected).getSn(), ((utils.entity.Device) selected).getIp())
                : null;
        }, hdcPath(), status::setText);
        window.fileDrops.install(shell);
        window.setTitle("鸿蒙投屏");
        final GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        final boolean[] darkMode = {false};
        final boolean[] sidebarCollapsed = {false};
        SidebarWindowSize windowSizing = new SidebarWindowSize(window, display);
        window.fitCollapsedWindow = () -> windowSizing.update(sidebarCollapsed[0], darkMode[0]);
        Runnable updateSidebar = () -> {
            sidebar.setVisible(!darkMode[0] && !sidebarCollapsed[0]);
            controlDock.setVisible(!darkMode[0] && !sidebarCollapsed[0]);
            if (sidebarCollapsed[0]) {
                stage.setOverlayRail(sidebarRail);
            } else {
                stage.setOverlayRail(null);
                if (sidebarRail.getParent() != controlDock) controlDock.add(sidebarRail, BorderLayout.WEST);
            }
            sidebarRail.setOpaque(!sidebarCollapsed[0]);
            sidebarRail.setVisible(!darkMode[0]);
            stage.setSidebarCollapsed(sidebarCollapsed[0]);
            sidebarRail.setBackground(stage.getBackground());
            shell.setBackground(stage.getBackground());
            sidebarToggle.setIcon(new LineIcon(sidebarCollapsed[0] ? "chevronLeft" : "chevronRight",
                Color.BLACK.equals(stage.getBackground()) ? new Color(0xA0ABB9) : MUTED, 12));
            String title = sidebarCollapsed[0] ? "展开右侧菜单" : "收起右侧菜单";
            sidebarToggle.setToolTipText(title);
            sidebarToggle.getAccessibleContext().setAccessibleName(title);
            shell.revalidate();
            shell.repaint();
        };
        sidebarToggle.addActionListener(event -> {
            sidebarCollapsed[0] = !sidebarCollapsed[0];
            updateSidebar.run();
            window.fitCollapsedWindow.run();
            display.requestFocusInWindow();
        });
        updateSidebar.run();
        final int[] lastWindowState = {JFrame.NORMAL};
        final Rectangle[] lastWindowBounds = {null};
        Consumer<Boolean> setLights = on -> {
            darkMode[0] = !on;
            stage.setDark(!on);
            updateSidebar.run();
            window.setTitle(on ? "鸿蒙投屏" : "鸿蒙投屏 · 关灯模式（按 ESC 开灯）");
            if (!on) {
                lastWindowState[0] = window.getExtendedState();
                lastWindowBounds[0] = window.getBounds();
                window.setBackground(Color.BLACK);
                try {
                    setUndecoratedQuietly(window, true, screen.getDefaultConfiguration().getBounds());
                } catch (RuntimeException error) {
                    System.err.println("关灯模式无边框切换失败，改用最大化窗口: " + error);
                    window.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
            } else {
                try {
                    setUndecoratedQuietly(window, sidebarCollapsed[0], lastWindowBounds[0]);
                } catch (RuntimeException error) {
                    System.err.println("退出关灯模式失败: " + error);
                }
                window.setExtendedState(lastWindowState[0]);
                if (lastWindowState[0] == JFrame.NORMAL) window.setBounds(lastWindowBounds[0]);
            }
            window.revalidate();
            window.fitCollapsedWindow.run();
        };
        lights.addActionListener(event -> setLights.accept(darkMode[0]));
        light.addActionListener(event -> setLights.accept(true));
        JPopupMenu windowMenu = new JPopupMenu();
        JMenuItem expandWindow = new JMenuItem("展开菜单");
        expandWindow.addActionListener(event -> {
            if (darkMode[0]) setLights.accept(true);
            if (sidebarCollapsed[0]) sidebarToggle.doClick();
        });
        windowMenu.add(expandWindow);
        JMenuItem pinWindow = new JMenuItem("窗口置顶 / 取消");
        pinWindow.addActionListener(event -> pin.doClick());
        windowMenu.add(pinWindow);
        JMenuItem minimizeWindow = new JMenuItem("缩小窗口");
        minimizeWindow.addActionListener(event -> window.setExtendedState(JFrame.ICONIFIED));
        windowMenu.add(minimizeWindow);
        JMenuItem closeWindow = new JMenuItem("关闭投屏");
        closeWindow.addActionListener(event -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
        windowMenu.add(closeWindow);
        windowMenu.addSeparator();
        JMenuItem dragHint = new JMenuItem("⌘ 拖动画面可移动窗口");
        dragHint.setEnabled(false);
        windowMenu.add(dragHint);
        rotating.setWindowControls(window, windowMenu, () -> sidebarCollapsed[0] && !darkMode[0]);
        window.getRootPane().registerKeyboardAction(event -> {
            if (darkMode[0]) setLights.accept(true);
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.shortcuts = new SidebarShortcuts(window, SidebarShortcuts.configPath());
        for (int i = 0; i < labeledButtons.length; i++)
            window.shortcuts.register(iconsForShortcut(i), labeledButtons[i]);
        window.shortcuts.register("refreshDevices", refresh);
        window.shortcuts.register("toggleCapture", start);
        // One binding toggles the entire menu, including when it is hidden.
        window.shortcuts.register("toggleSidebar", sidebarToggle);
        window.shortcuts.register("shortcutSettings", shortcutSettings);
        window.shortcuts.load();
        final long[] frameLogAt = {0};
        window.statusTimer = new Timer(500, event -> {
            window.recovery.tick(CaptureRecovery.now());
            boolean capturing = window.recovery.active();
            CaptureRecovery.State connectionState = window.recovery.state();
            boolean ready = connectionState == CaptureRecovery.State.CONNECTED && display.getImage() != null;
            display.setScreenCapturingMode(ready);
            long now = System.currentTimeMillis();
            if (++window.refreshTicks % 8 == 0) refresh.doClick();
            Object selected = devices.getSelectedItem();
            boolean wireless = selected instanceof utils.entity.Device &&
                ((utils.entity.Device) selected).getSn().contains(":");
            BufferedImage shown = display.getImage();
            String resolution = "";
            if (ready) {
                BufferedImage source = rotating.getSourceImage();
                BufferedImage sizeImage = source != null ? source : shown;
                if (sizeImage != null) resolution = " · " + sizeImage.getWidth() + "×" + sizeImage.getHeight();
            }
            if (!window.unlockBusy) status.setText((ready ? (wireless ? "Wi-Fi · 已连接" : "USB · 已连接")
                                  : connectionState == CaptureRecovery.State.OFFLINE ? "连接已断开 · 等待恢复"
                                  : connectionState == CaptureRecovery.State.RECOVERING ? "正在恢复投屏"
                                  : connectionState == CaptureRecovery.State.CHECKING ? "正在检查连接"
                                  : connectionState == CaptureRecovery.State.FAILED ? "投屏失败 · 请停止后重试"
                                  : capturing ? "正在连接" : "等待连接")
                + (recorder.isRecording() ? " · 录制中" : "")
                + (macroRecording[0] ? " · 宏录制中" : "")
                + (macroReplaying[0] ? " · 宏回放中" : "") + resolution);
            status.setIcon(new StatusDot(ready ? new Color(0x289E7B) : MUTED));
            start.setText("停止投屏".equals(originalStart.getText()) ? "停止投屏" : "开始投屏");
            start.setEnabled(originalStart.isEnabled() && (capturing || devices.getItemCount() > 0));
            for (JButton button : controlButtons) button.setEnabled(ready);
            rotate.setEnabled(capturing);
            deviceH.setEnabled(ready);
            deviceV.setEnabled(ready);
            home.setEnabled(ready);
            recents.setEnabled(ready);
            type.setEnabled(ready);
            paste.setEnabled(ready);
            copyText.setEnabled(ready);
            timed.setEnabled(ready);
            for (JButton button : gestureButtons) button.setEnabled(ready);
            unlock.setEnabled(capturing && connectionState != CaptureRecovery.State.OFFLINE && !window.unlockBusy);
            shot.setEnabled(ready);
            rec.setEnabled(ready || recorder.isRecording());
            pin.setEnabled(true);
            forward.setEnabled(devices.getItemCount() > 0);
            install.setEnabled(devices.getItemCount() > 0);
            settings.setEnabled(devices.getItemCount() > 0);
            layout.setEnabled(ready);
            frame.setEnabled(ready);
            if (!ready) {
                macroRecording[0] = false;
                macroReplaying[0] = false;
            }
            macroRec.setEnabled(ready);
            macroPlay.setEnabled(ready && !macroEvents.isEmpty());
            if (!ready) macroRec.setIcon(new LineIcon("record", INK, 18));
            if (!capturing && timedOn[0]) {
                timedOn[0] = false;
                if (timedTimer[0] != null) timedTimer[0].stop();
                timed.setIcon(new LineIcon("timer", INK, 18));
                timed.setToolTipText("定时截图");
            }
            if (timedOn[0] && timedTimer[0] != null) {
                if (!ready) timedTimer[0].stop();
                else if (!timedTimer[0].isRunning()) timedTimer[0].start();
            }
            // The upstream device-selection action changes the window title.
            if (!darkMode[0] && !"鸿蒙投屏".equals(window.getTitle())) window.setTitle("鸿蒙投屏");
            if (!ready) display.setTipMessage(connectionState == CaptureRecovery.State.OFFLINE
                ? "连接已断开，重新连接后自动恢复"
                : capturing ? "正在连接手机…" : "连接手机，开始投屏");
            Object remote = field(window, "hosRemoteDevice", Object.class);
            if (remote instanceof HosRemoteDevice && !(remote instanceof RotatingDevice)) {
                applyDeviceSettings((HosRemoteDevice) remote);
                setField(window, "hosRemoteDevice", new RotatingDevice((HosRemoteDevice) remote, rotating));
            }
            if (ready && window.isFocused() && !display.hasFocus()) display.requestFocusInWindow();
            if (ready && now - frameLogAt[0] > 5000) {
                frameLogAt[0] = now;
                System.out.println(rotating.logFrameStats()
                    + " size=" + (display.getImage() == null ? "none" : display.getImage().getWidth() + "x" + display.getImage().getHeight()));
            }
            stage.revalidate();
            stage.repaint();
            window.fitCollapsedWindow.run();
        });
        window.statusTimer.start();
    }

    private static String iconsForShortcut(int index) {
        // Stable IDs keep user bindings intact when button labels change.
        return new String[]{"home", "recents", "back", "unlock", "volumeDown", "volumeUp", "power",
            "rotate", "enlarge", "pin", "lights", "deviceHorizontal", "deviceVertical", "screenshot",
            "recordScreen", "refreshFrame", "timedScreenshot", "copyScreenText", "sendText", "paste",
            "layoutTree", "files", "forwardPort", "installApp", "castSettings", "recordMacro", "playMacro",
            "swipeUp", "swipeDown", "swipeLeft", "swipeRight"}[index];
    }

    /** Whether the connected device supports the complete mouse API used for smooth wheel input. */
    private static boolean canUseCompleteMouse(Window window) {
        try {
            Field field = MainForm.class.getDeclaredField("canUseCompleteMouse");
            field.setAccessible(true);
            return field.getBoolean(window);
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    /** Applies user stream settings to a fresh config before a capture session starts. */
    private static void applyDeviceSettings(HosRemoteConfig config) {
        if (castSettings.scale > 0) config.setScale(castSettings.scale);
        if (castSettings.bitRateMbps > 0) config.setBitRate(castSettings.bitRateMbps);
        if (castSettings.frameRate > 0) config.setFrameRate(castSettings.frameRate);
        if (castSettings.iFrameIntervalMs > 0) config.setIFrameInterval(castSettings.iFrameIntervalMs);
        if (castSettings.port > 0) config.setPort(castSettings.port);
        if (castSettings.screenId >= 0) config.setScreenId(castSettings.screenId);
    }

    /** Best-effort application of user stream settings to an already-created device. */
    private static void applyDeviceSettings(HosRemoteDevice device) {
        try {
            for (Field field : HosRemoteDevice.class.getDeclaredFields()) {
                if (HosRemoteConfig.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object config = field.get(device);
                    if (config instanceof HosRemoteConfig) applyDeviceSettings((HosRemoteConfig) config);
                    return;
                }
            }
        } catch (IllegalAccessException | RuntimeException error) {
            System.err.println("应用投屏设置失败: " + error);
        }
    }

    /** A decoder belongs to one capture attempt, so late callbacks cannot corrupt a new session. */
    private static final class CaptureSession {
        final long token;
        final HosRemoteDevice remote;
        final CaptureRecovery.Target target;
        volatile boolean imageMode;
        boolean wirelessRemembered; // Accessed under the session lock.
        CastDecoder decoder;
        CaptureSession(long token, HosRemoteDevice remote, CaptureRecovery.Target target) {
            this.token = token; this.remote = remote; this.target = target;
        }
        synchronized void closeDecoder() {
            if (decoder != null) { decoder.close(); decoder = null; }
        }
    }

    private static void startScreenCaptureWithSettings(Window window, utils.entity.Device device) {
        window.recovery.start(new CaptureRecovery.Target(device.getSn(), device.getIp()), CaptureRecovery.now());
    }

    private static void stopCaptureSession(Window window) {
        if (window.keyboard != null) window.keyboard.clear();
        CaptureSession old = window.captureSession;
        window.captureSession = null;
        if (old != null) window.captureTasks.execute(() -> closeCaptureSession(old));
    }

    private static void closeCaptureSession(CaptureSession session) {
        try { session.remote.stopCaptureScreen(); }
        finally { session.closeDecoder(); }
    }

    /** Runs all SDK start/stop work off the EDT and in order. */
    private static void startCaptureAttempt(Window window, CaptureRecovery.Target target, long token) {
        if (window.keyboard != null) window.keyboard.clear();
        CaptureSession previous = window.captureSession;
        HosRemoteConfig config = new HosRemoteConfig(target.sn);
        config.setIp(target.ip);
        applyDeviceSettings(config);
        HosRemoteDevice remote = new HosRemoteDevice(config);
        CaptureSession session = new CaptureSession(token, remote, target);
        window.captureSession = session;
        setField(window, "hosRemoteDevice", remote);
        window.keepAwake.acquire(target.sn);
        try {
            Field field = MainForm.class.getDeclaredField("canUseCompleteMouse");
            field.setAccessible(true);
            field.setBoolean(window, false);
        } catch (ReflectiveOperationException error) {
            System.err.println("重置鼠标能力失败: " + error);
        }
        MultiFunctionLabel label = field(window, "multiFunctionLabel", MultiFunctionLabel.class);
        label.setRectangles(Collections.emptyList());
        label.setImage(null);
        label.setScreenCapturingMode(true);
        label.setTipMessage(MultiFunctionLabel.STREAM_INIT_TIP_MESSAGE);
        System.out.println("启动投屏会话: " + token + " " + target.sn);
        ScreenCapCallback video = sessionCallback(window, session, label, false);
        window.captureTasks.execute(() -> {
            if (previous != null) closeCaptureSession(previous);
            try {
                if (!window.recovery.isCurrent(token)) return;
                if (window.connection.probe(target, null, false, false) == CaptureRecovery.Health.TRANSPORT_DOWN) {
                    window.recovery.failed(token, false, CaptureRecovery.now());
                    return;
                }
                remote.stopCaptureScreen();
                if (window.recovery.isCurrent(token)) remote.startCaptureScreen(video);
            } catch (Throwable error) { video.onException(error); }
            finally {
                if (!window.recovery.isCurrent(token)) closeCaptureSession(session);
            }
        });
    }

    private static ScreenCapCallback sessionCallback(Window window, CaptureSession session,
                                                     MultiFunctionLabel label, boolean imageMode) {
        return new ScreenCapCallback() {
            public void onData(ByteBuffer data) {
                synchronized (session) {
                    if (!window.recovery.isCurrent(session.token)) return;
                    try {
                        ByteBuffer payload = data.duplicate();
                        payload.rewind();
                        byte[] bytes = new byte[payload.remaining()];
                        payload.get(bytes);
                        BufferedImage image;
                        if (imageMode) {
                            image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                        } else {
                            if (session.decoder == null) session.decoder = new CastDecoder();
                            image = session.decoder.feed(bytes);
                        }
                        if (image != null && window.recovery.frame(session.token, CaptureRecovery.now())) {
                            label.setImage(image);
                            if (!session.wirelessRemembered) {
                                session.wirelessRemembered = true;
                                window.captureTasks.execute(() -> {
                                    if (!window.recovery.isCurrent(session.token)) return;
                                    try { rememberWirelessTarget(session.target); }
                                    catch (IOException error) {
                                        System.err.println("保存手机 Wi-Fi 地址失败: " + error.getClass().getSimpleName());
                                    }
                                });
                            }
                        }
                    } catch (Throwable error) { onException(error); }
                }
            }
            public void onReady() {
                if (!window.recovery.isCurrent(session.token)) return;
                window.recovery.ready(session.token);
                if (!imageMode) window.captureTasks.execute(() -> {
                    if (window.recovery.isCurrent(session.token)) CaptureConnection.keyFrame(session.remote);
                });
            }
            public void onException(Throwable error) {
                if (!window.recovery.isCurrent(session.token)) return;
                String message = String.valueOf(error.getMessage());
                System.err.println("投屏会话异常: " + session.token + " " + message);
                if (!imageMode && message.contains("can not find scrcpy pid") && !session.imageMode) {
                    session.imageMode = true;
                    window.captureTasks.execute(() -> {
                        if (!window.recovery.isCurrent(session.token)) return;
                        try {
                            session.remote.stopImageScreenCapture();
                            session.remote.startImageScreenCapture(sessionCallback(window, session, label, true));
                        } catch (Throwable failure) {
                            window.recovery.failed(session.token, false, CaptureRecovery.now());
                        }
                    });
                    return;
                }
                if (message.contains("system version is not supported")) {
                    // Loss of transport can also make the SDK's version command return invalid text.
                    window.captureTasks.execute(() -> {
                        try {
                            boolean online = window.connection.probe(session.target, null, false, false)
                                != CaptureRecovery.Health.TRANSPORT_DOWN;
                            window.recovery.failed(session.token, online, CaptureRecovery.now());
                        } catch (Exception failure) {
                            window.recovery.failed(session.token, false, CaptureRecovery.now());
                        }
                    });
                } else window.recovery.failed(session.token, false, CaptureRecovery.now());
            }
        };
    }

    /** Only a successful local Wi-Fi session replaces the last remembered phone. */
    static void rememberWirelessTarget(CaptureRecovery.Target target) throws IOException {
        if (target == null || target.sn == null || !target.sn.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}:8710")
                || !("127.0.0.1".equals(target.ip) || "localhost".equals(target.ip))) return;
        for (String octet : target.sn.substring(0, target.sn.indexOf(':')).split("\\.")) {
            if (Integer.parseInt(octet) > 255) return;
        }
        String configured = System.getenv("CAST_STATE_DIR");
        java.nio.file.Path directory = java.nio.file.Path.of(configured == null || configured.isBlank()
            ? System.getProperty("user.dir") : configured).toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Path destination = directory.resolve("wireless-device.txt");
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(directory, "wireless-", ".tmp");
        try {
            java.nio.file.Files.writeString(temporary, target.sn + "\n", StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }

    /** Replaces the upstream start-button toggle: start with settings, or stop cleanly. */
    private static void toggleCapture(Window window, JComboBox<?> devices, JLabel status, JButton originalStart) {
        if ("停止投屏".equals(originalStart.getText())) {
            window.recovery.stop();
            window.keepAwake.release();
            stopCaptureSession(window);
            MultiFunctionLabel label = field(window, "multiFunctionLabel", MultiFunctionLabel.class);
            label.setScreenCapturingMode(false);
            label.setImage(null);
            label.setTipMessage(MultiFunctionLabel.DEFAULT_TIP_MESSAGE);
            label.setRectangles(Collections.emptyList());
            originalStart.setText("进入投屏");
            setField(window, "hosRemoteDevice", null);
            return;
        }
        Object selected = devices.getSelectedItem();
        if (!(selected instanceof utils.entity.Device)) {
            status.setText("请先在列表中选择手机设备");
            return;
        }
        originalStart.setText("停止投屏");
        startScreenCaptureWithSettings(window, (utils.entity.Device) selected);
    }

    /** Asks the device to rotate its own screen; some apps may ignore this. */
    private static void deviceRotation(Window window, JLabel status, boolean horizontal) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) return;
        try {
            if (horizontal) ((HosRemoteDevice) remote).setRotationHorizontal();
            else ((HosRemoteDevice) remote).setRotationVertical();
            status.setText(horizontal ? "已请求设备横屏" : "已请求设备竖屏");
        } catch (Throwable error) {
            System.err.println("设备旋转失败: " + error);
            status.setText("当前应用不支持强制旋转");
        }
    }

    /** Sends a system key event (Home / Back / Power / key ID) through the uitest channel. */
    private static void phoneKey(Window window, JLabel status, String key) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先连接手机");
            return;
        }
        try {
            ((HosRemoteDevice) remote).executeShellCommand("uitest uiInput keyEvent " + key, 5);
            status.setText("已发送按键");
        } catch (Throwable error) {
            System.err.println("按键失败: " + error);
            status.setText("按键失败");
        }
    }

    /**
     * Opens the recent-tasks view. KeyEvent 187 is not recognized on HarmonyOS, so this
     * replays the gesture-nav gesture: swipe up from the bottom edge and hold.
     */
    private static void openRecents(Window window, JLabel status) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先连接手机");
            return;
        }
        HosRemoteDevice device = (HosRemoteDevice) remote;
        status.setText("正在打开最近任务…");
        new Thread(() -> {
            try {
                Size size = null;
                try { size = device.getScreenSize(false); } catch (Throwable ignored) { }
                int width = size != null ? size.width : 720;
                int height = size != null ? size.height : 1280;
                int centerX = width / 2;
                int fromY = Math.max(0, height - 60);
                int toY = height / 2;
                device.executeShellCommand(String.format("uinput -T -d %d %d", centerX, fromY), 5);
                device.executeShellCommand(String.format("uinput -T -m %d %d %d %d -k 800 150",
                    centerX, fromY, centerX, toY), 5);
                device.executeShellCommand(String.format("uinput -T -u %d %d", centerX, toY), 5);
                SwingUtilities.invokeLater(() -> status.setText("已打开最近任务"));
            } catch (Throwable error) {
                System.err.println("打开最近任务失败: " + error);
                SwingUtilities.invokeLater(() -> status.setText("打开最近任务失败"));
            }
        }, "open-recents").start();
    }

    /** Asks for text and injects it into the focused phone input field through the uitest channel. */
    private static void sendText(Window window, JLabel status) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先连接手机");
            return;
        }
        String text = JOptionPane.showInputDialog(window,
            "输入要发送到手机的文字（请先点击手机上的输入框使其聚焦）", "发送文本",
            JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.trim().isEmpty()) return;
        window.keyboard.text(text);
        status.setText("正在发送文字…");
    }

    /** Reads the Mac clipboard and pastes its text into the focused phone input field. */
    private static void pasteClipboard(Window window, JLabel status) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先连接手机");
            return;
        }
        String text;
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = data == null ? null : data.toString();
        } catch (Throwable error) {
            System.err.println("读取剪贴板失败: " + error);
            status.setText("读取剪贴板失败");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            status.setText("剪贴板中没有文本");
            return;
        }
        status.setText("正在粘贴…");
        window.keyboard.text(text);
    }

    /** Sends a quick swipe in the given direction (0=left, 1=right, 2=up, 3=down). */
    private static void swipeDirection(Window window, JLabel status, int direction) {
        CaptureSession session = window.captureSession;
        if (session == null || window.recovery.state() != CaptureRecovery.State.CONNECTED) {
            status.setText("请先连接手机");
            return;
        }
        RotatingMultiFunctionLabel display = field(window, "multiFunctionLabel", RotatingMultiFunctionLabel.class);
        int rotation = display.getViewRotation();
        BufferedImage source = display.getSourceImage();
        boolean landscape = source != null && source.getWidth() > source.getHeight();
        String[] names = {"已左滑", "已右滑", "已上滑", "已下滑"};
        status.setText("正在滑动…");
        window.captureTasks.execute(() -> {
            try {
                if (!window.recovery.isCurrent(session.token)) return;
                Size size = session.remote.getScreenSize(false);
                if (source != null && landscape != (size.width > size.height)) size = session.remote.getScreenSize(true);
                String command = swipeCommand(direction, rotation, size.width, size.height);
                if (!window.recovery.isCurrent(session.token)) return;
                String output = session.remote.executeShellCommand(command, 5);
                boolean ok = output != null && output.contains("No Error")
                    && !output.contains("[Fail]") && !output.toLowerCase(java.util.Locale.ROOT).contains("error:");
                SwingUtilities.invokeLater(() -> {
                    if (window.recovery.isCurrent(session.token)) status.setText(ok ? names[direction] : "滑动失败，请确认手机已连接并解锁");
                });
                if (!ok) System.err.println("滑动命令失败: " + output);
            } catch (Throwable error) {
                System.err.println("滑动失败: " + error);
                SwingUtilities.invokeLater(() -> {
                    if (window.recovery.isCurrent(session.token)) status.setText("滑动失败");
                });
            }
        });
    }

    /** Coordinates are physical screen pixels; button directions follow the displayed rotation. */
    static String swipeCommand(int direction, int rotation, int width, int height) {
        if (direction < 0 || direction > 3 || width < 10 || height < 10
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("滑动参数无效");
        }
        int shownWidth = rotation == 90 || rotation == 270 ? height : width;
        int shownHeight = rotation == 90 || rotation == 270 ? width : height;
        Point from = new Point(shownWidth / 2, shownHeight / 2);
        Point to = new Point(from);
        if (direction <= 1) {
            from.x = shownWidth * (direction == 0 ? 4 : 1) / 5;
            to.x = shownWidth * (direction == 0 ? 1 : 4) / 5;
        } else {
            from.y = shownHeight * (direction == 2 ? 4 : 1) / 5;
            to.y = shownHeight * (direction == 2 ? 1 : 4) / 5;
        }
        Point start = unrotateSwipe(from, rotation, width, height);
        Point end = unrotateSwipe(to, rotation, width, height);
        return String.format(java.util.Locale.ROOT, "uitest uiInput swipe %d %d %d %d 1800",
            start.x, start.y, end.x, end.y);
    }

    private static Point unrotateSwipe(Point point, int rotation, int width, int height) {
        switch (rotation) {
            case 90: return new Point(point.y, height - 1 - point.x);
            case 180: return new Point(width - 1 - point.x, height - 1 - point.y);
            case 270: return new Point(width - 1 - point.y, point.x);
            default: return point;
        }
    }

    /** Saves the current projected frame to the Desktop; returns the file, or null on failure. */
    private static File saveScreenshot(Window window, RotatingMultiFunctionLabel rotating, JLabel status) {
        BufferedImage image = rotating.getImage();
        if (image == null) {
            status.setText("暂无画面");
            return null;
        }
        try {
            File dir = new File(System.getProperty("user.home"), "Desktop");
            File file = new File(dir, "鸿蒙投屏-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".png");
            javax.imageio.ImageIO.write(image, "png", file);
            status.setText("截图已保存: " + file.getName());
            return file;
        } catch (IOException error) {
            System.err.println("截图失败: " + error);
            status.setText("截图失败");
            return null;
        }
    }

    /** Appends a line to the on-disk unlock diagnostic log under the user's home directory. */
    private static void logUnlock(String message) {
        try {
            java.nio.file.Files.write(
                java.nio.file.Paths.get(System.getProperty("user.home"), "鸿蒙投屏-unlock.log").toAbsolutePath(),
                (new SimpleDateFormat("HH:mm:ss.SSS ").format(new Date()) + message + "\n")
                    .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Wakes the screen, flings the lock screen away, and taps a digit PIN if the keypad shows. */
    private static void unlockPhone(Window window, RotatingMultiFunctionLabel rotating, JLabel status, JButton unlock) {
        if (window.unlockBusy) return;
        CaptureSession session = window.captureSession;
        if (session == null || window.recovery.state() != CaptureRecovery.State.CONNECTED) {
            status.setText("请先连接手机");
            return;
        }
        HosRemoteDevice device = session.remote;
        window.unlockBusy = true;
        status.setText("正在唤醒并解锁…");
        unlock.setEnabled(false);
        new Thread(() -> {
            try {
                logUnlock("== 解锁开始 ==");
                device.executeShellCommand("power-shell wakeup", 3);
                Thread.sleep(900);
                if (!window.recovery.isCurrent(session.token)) return;
                Size size = device.getScreenSize(true);
                int width = size.width, height = size.height;
                device.executeShellCommand(String.format(java.util.Locale.ROOT,
                    "uitest uiInput fling %d %d %d %d 30000",
                    width / 2, height * 85 / 100, width / 2, height * 15 / 100), 10);
                String layout = null;
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline) {
                    if (!window.recovery.isCurrent(session.token)) return;
                    layout = device.getLayout();
                    if (!findPinKeypad(layout).isEmpty() || looksUnlocked(layout)) break;
                    Thread.sleep(500);
                }
                if (looksUnlocked(layout)) {
                    SwingUtilities.invokeLater(() -> status.setText("手机已解锁"));
                    return;
                }
                final String[] pin = {null};
                SwingUtilities.invokeAndWait(() -> {
                    if (!window.recovery.isCurrent(session.token)) return;
                    JPasswordField input = new JPasswordField(16);
                    input.setText(DEFAULT_UNLOCK_PIN);
                    int choice = JOptionPane.showConfirmDialog(window,
                        new Object[]{"请确认手机已显示数字键盘，再输入解锁密码", input},
                        "解锁", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.OK_OPTION) {
                        char[] value = input.getPassword();
                        pin[0] = new String(value);
                        java.util.Arrays.fill(value, '\0');
                    }
                    input.setText("");
                });
                if (pin[0] == null || pin[0].isEmpty()) {
                    SwingUtilities.invokeLater(() -> status.setText("已取消输入密码"));
                    return;
                }
                if (!pin[0].matches("[0-9]{4,16}")) throw new IOException("请输入 4–16 位数字密码");
                if (!window.recovery.isCurrent(session.token)) return;
                SwingUtilities.invokeLater(() -> status.setText("正在识别数字键盘…"));
                // Refresh after the dialog: the lock screen may have moved while waiting.
                Map<String, Point> positions = findPinKeypad(device.getLayout());
                if (positions.size() != 10) throw new IOException("无法识别完整数字键盘，请在手机上显示密码键盘后重试");
                SwingUtilities.invokeLater(() -> status.setText("正在输入密码…"));
                unlockWithPin(device, positions, pin[0], () -> window.recovery.isCurrent(session.token));
                pin[0] = null;
                Thread.sleep(1500);
                if (!window.recovery.isCurrent(session.token)) return;
                String after = device.getLayout();
                boolean unlocked = looksUnlocked(after);
                logUnlock("输入后复核: 已解锁=" + unlocked);
                SwingUtilities.invokeLater(() -> status.setText(unlocked
                    ? "手机已解锁" : "密码已发送，请确认手机是否解锁"));
            } catch (Throwable error) {
                logUnlock("解锁失败: " + error.getClass().getSimpleName());
                SwingUtilities.invokeLater(() -> status.setText(error instanceof IOException
                    ? error.getMessage() : "解锁失败，请确认连接和手机状态"));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    window.unlockBusy = false;
                    unlock.setEnabled(window.captureSession != null
                        && window.recovery.state() == CaptureRecovery.State.CONNECTED);
                });
            }
        }, "unlock-task").start();
    }

    /** Counts the visible text nodes in a layout dump (used for diagnostics and home-screen heuristics). */
    private static int textCount(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            JsonStructure root = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                .fromJson(json, JsonStructure.class);
            final int[] count = {0};
            countTexts(root, count);
            return count[0];
        } catch (Throwable error) {
            return -1;
        }
    }

    private static void unlockWithPin(HosRemoteDevice device, Map<String, Point> keypad, String pin) throws Exception {
        unlockWithPin(device, keypad, pin, () -> true);
    }

    /** Validate the entire PIN first; never silently skip digits or retry an uncertain click. */
    private static void unlockWithPin(HosRemoteDevice device, Map<String, Point> keypad, String pin,
                                     java.util.function.BooleanSupplier current) throws Exception {
        if (!pin.matches("[0-9]{4,16}")) throw new IOException("请输入 4–16 位数字密码");
        for (char digit : pin.toCharArray()) {
            if (keypad.get(String.valueOf(digit)) == null) throw new IOException("数字键盘坐标不完整，请重试");
        }
        int sent = 0;
        for (char digit : pin.toCharArray()) {
            if (!current.getAsBoolean()) throw new IOException("连接已变化，已停止输入密码");
            Point tap = keypad.get(String.valueOf(digit));
            String output = device.executeShellCommand(String.format(java.util.Locale.ROOT,
                "uitest uiInput click %d %d", tap.x, tap.y), 5);
            if (output == null || !output.contains("No Error") || output.contains("[Fail]")
                    || output.toLowerCase(java.util.Locale.ROOT).contains("error:"))
                throw new IOException("密码输入中断，请先清空手机上已输入的数字再重试");
            sent++;
            Thread.sleep(300);
        }
        logUnlock("密码点击完成: " + sent + " 次");
    }

    /** Scans a layout dump for single-digit keypad buttons and returns their centers in device coordinates. */
    private static Map<String, Point> findPinKeypad(String json) {
        Map<String, Point> taps = new HashMap<>();
        if (json == null || json.isEmpty()) return taps;
        try {
            JsonStructure root = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                .fromJson(json, JsonStructure.class);
            Rectangle screen = parseBounds(root.getBounds());
            int height = screen != null && screen.height > 0 ? screen.height : 0;
            JsonStructure protectedKeypad = findNodeById(root, "Digital_PSD_Input_Tip");
            if (protectedKeypad != null) {
                collectDigits(protectedKeypad, taps, 0);
                if (taps.size() != 10) taps = protectedKeypadButtons(protectedKeypad);
            } else collectDigits(root, taps, height);
            // A real keypad exposes at least a few distinct digits; status-bar clocks can show one.
            if (taps.size() < 4) taps.clear();
        } catch (Throwable ignored) {
        }
        return taps;
    }

    private static JsonStructure findNodeById(JsonStructure node, String id) {
        if (id.equals(node.getAttributes().get("id"))) return node;
        for (JsonStructure child : node.getChildren()) {
            JsonStructure found = findNodeById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    /** HarmonyOS hides digit text, but exposes the standard keypad's twelve button bounds. */
    private static Map<String, Point> protectedKeypadButtons(JsonStructure node) {
        if ("RelativeContainer".equals(node.getType()) && findNodeById(node, "numKeyBoard") != null) {
            java.util.List<Rectangle> buttons = new java.util.ArrayList<>();
            for (JsonStructure child : node.getChildren()) {
                if (!"true".equals(String.valueOf(child.getAttributes().get("clickable")))) continue;
                Rectangle bounds = parseBounds(child.getBounds());
                if (bounds != null && bounds.width > 0 && bounds.height > 0) buttons.add(bounds);
            }
            if (buttons.size() == 12) {
                buttons.sort(java.util.Comparator.comparingInt((Rectangle r) -> r.y).thenComparingInt(r -> r.x));
                Rectangle first = buttons.get(0);
                boolean grid = true;
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 3; col++) {
                        Rectangle rect = buttons.get(row * 3 + col);
                        Rectangle top = buttons.get(col), left = buttons.get(row * 3);
                        grid &= Math.abs(rect.x - top.x) <= 2 && Math.abs(rect.y - left.y) <= 2
                            && Math.abs(rect.width - first.width) <= 2 && Math.abs(rect.height - first.height) <= 2;
                        if (col > 0) grid &= rect.x >= buttons.get(row * 3 + col - 1).x + first.width;
                        if (row > 0) grid &= rect.y >= buttons.get((row - 1) * 3 + col).y + first.height;
                    }
                }
                if (grid) {
                    Map<String, Point> keys = new HashMap<>();
                    for (int digit = 0; digit < 10; digit++) {
                        Rectangle rect = buttons.get(digit == 0 ? 10 : digit - 1);
                        keys.put(String.valueOf(digit), new Point(rect.x + rect.width / 2, rect.y + rect.height / 2));
                    }
                    return keys;
                }
            }
        }
        for (JsonStructure child : node.getChildren()) {
            Map<String, Point> keys = protectedKeypadButtons(child);
            if (!keys.isEmpty()) return keys;
        }
        return Collections.emptyMap();
    }

    private static void collectDigits(JsonStructure node, Map<String, Point> taps, int screenHeight) {
        String text = node.getText();
        if (text == null || !text.matches("[0-9]")) text = node.getDesc();
        if (text != null && text.length() == 1 && text.charAt(0) >= '0' && text.charAt(0) <= '9') {
            Rectangle rect = parseBounds(node.getBounds());
            if (rect != null && rect.width > 0 && rect.height > 0
                && (screenHeight <= 0 || rect.y >= screenHeight * 0.5)) {
                taps.putIfAbsent(text, new Point(rect.x + rect.width / 2, rect.y + rect.height / 2));
            }
        }
        for (JsonStructure child : node.getChildren()) collectDigits(child, taps, screenHeight);
    }

    private static Rectangle parseBounds(String bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        try {
            String flat = bounds.replace("][", " ").replace("[", "").replace("]", "");
            String[] parts = flat.split(" ");
            String[] a = parts[0].split(",");
            String[] b = parts[1].split(",");
            int x = Integer.parseInt(a[0].trim());
            int y = Integer.parseInt(a[1].trim());
            int w = Integer.parseInt(b[0].trim()) - x;
            int h = Integer.parseInt(b[1].trim()) - y;
            return new Rectangle(x, y, w, h);
        } catch (Throwable error) {
            return null;
        }
    }

    /** A rough "home screen showing" check: many distinct labels and no lock-screen keypad. */
    private static boolean looksUnlocked(String json) {
        if (json == null || json.isEmpty() || !findPinKeypad(json).isEmpty()) return false;
        try {
            JsonStructure root = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                .fromJson(json, JsonStructure.class);
            if (findNodeById(root, "ScreenLockRootComponent") != null) return false;
            final int[] texts = {0};
            countTexts(root, texts);
            return texts[0] >= 12;
        } catch (Throwable error) {
            return false;
        }
    }

    private static void countTexts(JsonStructure node, int[] count) {
        if (node.getText() != null && !node.getText().isEmpty()) count[0]++;
        for (JsonStructure child : node.getChildren()) countTexts(child, count);
    }

    /** Converts a layout rectangle from device coordinates into the rotated display space. */
    private static Rectangle toDisplayRect(RotatingMultiFunctionLabel rotating, Rectangle rect) {
        if (rect == null) return null;
        if (rotating.getViewRotation() == 0) return new Rectangle(rect);
        BufferedImage source = rotating.getSourceImage();
        if (source == null) return new Rectangle(rect);
        int w = source.getWidth(), h = source.getHeight();
        Point a = rotatePoint(rotating.getViewRotation(), w, h, rect.x, rect.y);
        Point b = rotatePoint(rotating.getViewRotation(), w, h, rect.x + rect.width, rect.y + rect.height);
        return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y),
            Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    }

    /** Converts a layout point from device coordinates into the rotated display space. */
    private static Point toDisplayPoint(RotatingMultiFunctionLabel rotating, Point point) {
        if (rotating.getViewRotation() == 0) return new Point(point);
        BufferedImage source = rotating.getSourceImage();
        if (source == null) return new Point(point);
        return rotatePoint(rotating.getViewRotation(), source.getWidth(), source.getHeight(), point.x, point.y);
    }

    private static Point rotatePoint(int rotation, int width, int height, int x, int y) {
        switch (rotation) {
            case 90: return new Point(height - y, x);
            case 180: return new Point(width - x, height - y);
            case 270: return new Point(y, width - x);
        }
        return new Point(x, y);
    }

    private static int parseSetting(String text, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static JTextField settingInput(String value) {
        JTextField field = new JTextField(value, 8);
        field.setPreferredSize(new Dimension(150, 28));
        return field;
    }

    private static int countNodes(DefaultMutableTreeNode node) {
        int count = 1;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countNodes((DefaultMutableTreeNode) node.getChildAt(i));
        }
        return count;
    }

    /** Stream-parameter dialog backed by HosRemoteConfig; applies on the next capture start. */
    private static void showDeviceSettings(Window window, JComboBox<?> devices, JLabel status) {
        JDialog dialog = new JDialog((JFrame) window, "投屏设置", true);
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 8, 5, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        JTextField scale = settingInput(castSettings.scale > 0 ? String.valueOf(castSettings.scale) : "");
        JTextField bitRate = settingInput(castSettings.bitRateMbps > 0 ? String.valueOf(castSettings.bitRateMbps) : "");
        JTextField frameRate = settingInput(castSettings.frameRate > 0 ? String.valueOf(castSettings.frameRate) : "");
        JTextField iFrame = settingInput(castSettings.iFrameIntervalMs > 0 ? String.valueOf(castSettings.iFrameIntervalMs) : "");
        JTextField port = settingInput(castSettings.port > 0 ? String.valueOf(castSettings.port) : "");
        JTextField screenId = settingInput(castSettings.screenId >= 0 ? String.valueOf(castSettings.screenId) : "");
        String[][] rows = {
            {"分辨率倍率（2=二分之一，最大 5）", "scale"},
            {"码率 Mbps（默认 30）", "bitRate"},
            {"帧率（默认 120）", "frameRate"},
            {"I 帧间隔 ms（默认 2000）", "iFrame"},
            {"设备端口（默认 5000）", "port"},
            {"屏幕 ID（多屏场景，默认 0）", "screenId"},
        };
        for (String[] row : rows) {
            gbc.gridx = 0;
            panel.add(label(row[0], 12, Font.PLAIN, INK), gbc);
            gbc.gridx = 1;
            JTextField input = switch (row[1]) {
                case "scale" -> scale;
                case "bitRate" -> bitRate;
                case "frameRate" -> frameRate;
                case "iFrame" -> iFrame;
                case "port" -> port;
                default -> screenId;
            };
            panel.add(input, gbc);
            gbc.gridy++;
        }
        JLabel info = label("", 11, Font.PLAIN, MUTED);
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (remote instanceof HosRemoteDevice) {
            boolean online = false;
            Size size = null;
            try { online = ((HosRemoteDevice) remote).isOnline(); } catch (Throwable ignored) { }
            try { size = ((HosRemoteDevice) remote).getScreenSize(true); } catch (Throwable ignored) { }
            info.setText("设备 " + ((HosRemoteDevice) remote).getSn()
                + " · " + (online ? "在线" : "离线")
                + (size != null ? " · " + size.width + "×" + size.height : ""));
        }
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(info, gbc);
        gbc.gridy++;
        JButton apply = new JButton("保存并重启投屏");
        JButton cancel = new JButton("取消");
        for (JButton b : new JButton[]{apply, cancel}) b.setFocusable(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(apply);
        buttons.add(cancel);
        panel.add(buttons, gbc);
        apply.addActionListener(event -> {
            castSettings.scale = parseSetting(scale.getText(), 1, 5, 0);
            castSettings.bitRateMbps = parseSetting(bitRate.getText(), 1, 200, 0);
            castSettings.frameRate = parseSetting(frameRate.getText(), 1, 240, 0);
            castSettings.iFrameIntervalMs = parseSetting(iFrame.getText(), 100, 10000, 0);
            castSettings.port = parseSetting(port.getText(), 1, 65535, 0);
            castSettings.screenId = parseSetting(screenId.getText(), 0, 100, -1);
            Object current = field(window, "hosRemoteDevice", Object.class);
            if (current instanceof HosRemoteDevice) applyDeviceSettings((HosRemoteDevice) current);
            Object selected = devices.getSelectedItem();
            if (selected instanceof utils.entity.Device) {
                try {
                    JButton original = field(window, "btnEnterScreenCapture", JButton.class);
                    original.setText("停止投屏");
                    startScreenCaptureWithSettings(window, (utils.entity.Device) selected);
                    status.setText("设置已应用，已重启投屏");
                } catch (Throwable error) {
                    System.err.println("重启投屏失败: " + error);
                    status.setText("设置已保存，下次启动投屏生效");
                }
            } else {
                status.setText("设置已保存，下次启动投屏生效");
            }
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(window);
        dialog.setVisible(true);
    }

    /** Control-tree viewer backed by getLayout/invokeGenerateLayout. */
    private static void showLayoutTree(Window window, RotatingMultiFunctionLabel rotating, JLabel status) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先进入投屏");
            return;
        }
        JDialog dialog = new JDialog((JFrame) window, "控件树", false);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("布局");
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        JScrollPane scroll = new JScrollPane(tree);
        JTextArea attributes = new JTextArea(6, 44);
        attributes.setEditable(false);
        attributes.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane attrScroll = new JScrollPane(attributes);
        JButton refresh = new JButton("刷新布局");
        JButton tap = new JButton("点击控件");
        JButton export = new JButton("导出 JSON");
        JButton importBtn = new JButton("导入布局");
        for (JButton b : new JButton[]{refresh, tap, export, importBtn}) b.setFocusable(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        top.add(refresh);
        top.add(tap);
        top.add(export);
        top.add(importBtn);
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(attrScroll, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setSize(560, 460);
        dialog.setLocationRelativeTo(window);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                rotating.setRectangles(Collections.emptyList());
            }
        });
        final String[] currentJson = {null};
        refresh.addActionListener(event -> {
            Object device = field(window, "hosRemoteDevice", Object.class);
            if (!(device instanceof HosRemoteDevice)) {
                attributes.setText("尚未投屏");
                return;
            }
            try {
                String json = ((HosRemoteDevice) device).getLayout();
                if (json == null || json.isEmpty()) {
                    attributes.setText("获取布局失败，请确认已投屏且界面有内容");
                    return;
                }
                JsonStructure structure = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                    .fromJson(json, JsonStructure.class);
                if (structure == null) {
                    attributes.setText("布局解析失败");
                    return;
                }
                if (structure.getType().isEmpty()) structure.getAttributes().put("type", "Root");
                root.removeAllChildren();
                root.setUserObject(structure);
                root.add(TreeUtil.convertJsonStructureToJTreeNode(structure));
                ((DefaultTreeModel) tree.getModel()).nodeChanged(root);
                tree.setRootVisible(true);
                tree.expandRow(0);
                tree.setRootVisible(false);
                currentJson[0] = json;
                attributes.setText("已加载 " + countNodes(root) + " 个控件，点击节点查看范围");
            } catch (Throwable error) {
                System.err.println("获取布局失败: " + error);
                attributes.setText("获取布局失败: " + error);
            }
        });
        tree.addTreeSelectionListener(event -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || !(node.getUserObject() instanceof JsonStructure)) return;
            JsonStructure structure = (JsonStructure) node.getUserObject();
            Rectangle rect = structure.getRectangle();
            if (rect != null) rotating.setRectangles(Collections.singletonList(toDisplayRect(rotating, rect)));
            attributes.setText("类型: " + structure.getType()
                + "\n文本: " + structure.getText()
                + "\n描述: " + structure.getDesc()
                + "\n范围: " + structure.getBounds()
                + "\n中心: " + structure.getCenterPosition());
        });
        tap.addActionListener(event -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || !(node.getUserObject() instanceof JsonStructure)) {
                attributes.setText("请先在树中选择控件");
                return;
            }
            JsonStructure structure = (JsonStructure) node.getUserObject();
            Rectangle rect = structure.getRectangle();
            if (rect == null) {
                attributes.setText("该控件没有范围信息");
                return;
            }
            Point center = new Point(rect.x + rect.width / 2, rect.y + rect.height / 2);
            Object device = field(window, "hosRemoteDevice", Object.class);
            if (!(device instanceof HosRemoteDevice)) return;
            Point inject = device instanceof RotatingDevice ? toDisplayPoint(rotating, center) : center;
            try {
                ((HosRemoteDevice) device).onTouchDown(inject.x, inject.y);
                ((HosRemoteDevice) device).onTouchUp(inject.x, inject.y);
                attributes.setText("已点击 " + structure.getType() + " @ " + inject.x + "," + inject.y);
            } catch (Throwable error) {
                System.err.println("点击控件失败: " + error);
                attributes.setText("点击控件失败: " + error);
            }
        });
        export.addActionListener(event -> {
            if (currentJson[0] == null) {
                attributes.setText("请先刷新布局");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File(System.getProperty("user.home") + "/Desktop", "layout.json"));
            if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            File file = chooser.getSelectedFile();
            try {
                java.nio.file.Files.write(file.toPath(), currentJson[0].getBytes(StandardCharsets.UTF_8));
                attributes.setText("已导出: " + file.getName());
            } catch (IOException error) {
                attributes.setText("导出失败: " + error.getMessage());
            }
        });
        importBtn.addActionListener(event -> {
            Object device = field(window, "hosRemoteDevice", Object.class);
            if (!(device instanceof HosRemoteDevice)) {
                attributes.setText("尚未投屏");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            File file = chooser.getSelectedFile();
            try {
                String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                ((HosRemoteDevice) device).invokeGenerateLayout(json);
                attributes.setText("已导入布局: " + file.getName());
            } catch (Throwable error) {
                System.err.println("导入布局失败: " + error);
                attributes.setText("导入布局失败: " + error.getMessage());
            }
        });
        refresh.doClick();
        dialog.setVisible(true);
    }

    /** Maps a wheel event point on the label to phone screen coordinates, mirroring MainForm. */
    private static Point wheelPoint(MultiFunctionLabel label, Point point) {
        if (point == null || label.getImage() == null) return null;
        int iconWidth = label.getCurrentSize().width;
        int iconHeight = label.getCurrentSize().height;
        int offsetX = (label.getWidth() - iconWidth) / 2;
        int offsetY = (label.getHeight() - iconHeight) / 2;
        int x = point.x, y = point.y;
        if (x < offsetX || x > offsetX + iconWidth || y < offsetY || y > offsetY + iconHeight) {
            if (x < offsetX) {
                x = offsetX + 2;
                y = offsetY + iconHeight / 2;
            } else if (x > offsetX + iconWidth) {
                x = offsetX + iconWidth / 2;
                y = offsetY + iconHeight / 2;
            } else if (y < offsetY) {
                x = offsetX + iconWidth / 2;
                y = offsetY + 2;
            } else {
                x = offsetX + iconWidth / 2;
                y = offsetY + iconHeight - 2;
            }
        }
        double widthScaleRate = 1.0 * iconWidth / label.getImage().getWidth();
        double heightScaleRate = 1.0 * iconHeight / label.getImage().getHeight();
        return new Point((int) ((x - offsetX) / widthScaleRate), (int) ((y - offsetY) / heightScaleRate));
    }

    /** Sends one wheel unit in the given direction, using the device's supported input path. */
    private static void sendWheelScroll(HosRemoteDevice device, MultiFunctionLabel label, Point raw, boolean up, Window window) {
        Point point = wheelPoint(label, raw);
        if (point == null) return;
        if (canUseCompleteMouse(window)) {
            if (up) device.onMouseWheelUp(point.x, point.y);
            else device.onMouseWheelDown(point.x, point.y);
        } else {
            device.executeShellCommand(String.format("uinput -M -m %d %d -s %s", point.x, point.y, up ? "-500" : "500"), 5);
        }
    }

    /** Pinch-gesture state shared by the Ctrl+wheel handler (single-window app). */
    private static final boolean[] pinchActive = {false};
    private static final double[] pinchPending = {0};
    private static final Point[] pinchFingers = {null, null};
    private static final Timer[] pinchIdle = {null};
    private static Object pinchRemote;

    /**
     * Mac trackpad pinch arrives as Ctrl+wheel. Sends a real two-finger gesture to the
     * phone through the device's uinput tool (-T touch), coalescing rotation into
     * discrete spread/close steps and lifting both fingers once the gesture goes idle.
     */
    private static void pinchAt(Window window, RotatingMultiFunctionLabel rotating, Point raw, double rotation) {
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice) || !rotating.isScreenCapturingMode()) return;
        BufferedImage source = rotating.getSourceImage();
        Point center = wheelPoint(rotating, raw);
        if (center == null || source == null) return;
        center = devicePoint(rotating, center);
        int maxW = source.getWidth(), maxH = source.getHeight();
        center.x = Math.max(40, Math.min(maxW - 40, center.x));
        center.y = Math.max(40, Math.min(maxH - 40, center.y));
        HosRemoteDevice device = (HosRemoteDevice) remote;
        if (!pinchActive[0]) {
            pinchActive[0] = true;
            pinchPending[0] = 0;
            pinchRemote = remote;
            int gap = Math.max(60, Math.min(150, Math.min(maxW, maxH) / 4));
            pinchFingers[0] = new Point(Math.max(10, center.x - gap), center.y);
            pinchFingers[1] = new Point(Math.min(maxW - 10, center.x + gap), center.y);
            device.executeShellCommand(String.format("uinput -T -d %d %d", pinchFingers[0].x, pinchFingers[0].y), 5);
            device.executeShellCommand(String.format("uinput -T -d %d %d", pinchFingers[1].x, pinchFingers[1].y), 5);
            System.out.println("捏合手势开始 @ " + center.x + "," + center.y);
        }
        pinchPending[0] += rotation;
        if (pinchPending[0] >= 1 || pinchPending[0] <= -1) {
            int step = (int) (Math.min(150, Math.max(60, Math.min(maxW, maxH) / 4)) * Math.signum(pinchPending[0]) * 1.0);
            if (Math.abs(step) < 30) step = 30 * (int) Math.signum(pinchPending[0]);
            movePinchFingers(device, step, maxW, maxH);
            pinchPending[0] -= Math.signum(pinchPending[0]);
        }
        if (pinchIdle[0] == null) {
            pinchIdle[0] = new Timer(300, event -> endPinch());
            pinchIdle[0].setRepeats(false);
        }
        pinchIdle[0].restart();
    }

    private static void movePinchFingers(HosRemoteDevice device, int step, int maxW, int maxH) {
        Point[] next = new Point[2];
        for (int i = 0; i < 2; i++) {
            int dx = i == 0 ? -step : step;
            next[i] = new Point(
                Math.max(10, Math.min(maxW - 10, pinchFingers[i].x + dx)),
                pinchFingers[i].y);
        }
        if (next[0].x >= next[1].x - 30) {
            int mid = (pinchFingers[0].x + pinchFingers[1].x) / 2;
            next[0].x = Math.max(10, mid - 15);
            next[1].x = Math.min(maxW - 10, mid + 15);
        }
        String command = String.format("uinput -T -m %d %d %d %d %d %d %d %d -k 60 120",
            pinchFingers[0].x, pinchFingers[0].y, pinchFingers[1].x, pinchFingers[1].y,
            next[0].x, next[0].y, next[1].x, next[1].y);
        device.executeShellCommand(command, 5);
        pinchFingers[0] = next[0];
        pinchFingers[1] = next[1];
    }

    /** Lifts both pinch fingers and resets the gesture state. */
    private static void endPinch() {
        if (!pinchActive[0]) return;
        pinchActive[0] = false;
        pinchPending[0] = 0;
        Object remote = pinchRemote;
        if (remote instanceof HosRemoteDevice) {
            for (Point finger : pinchFingers) {
                if (finger != null) {
                    ((HosRemoteDevice) remote).executeShellCommand(
                        String.format("uinput -T -u %d %d", finger.x, finger.y), 5);
                }
            }
        }
        pinchRemote = null;
        pinchFingers[0] = null;
        pinchFingers[1] = null;
        System.out.println("捏合手势结束");
    }

    /** Converts a point from the displayed (rotated) space into the device's physical coordinates. */
    private static Point devicePoint(RotatingMultiFunctionLabel rotating, Point point) {
        int rotation = rotating.getViewRotation();
        if (point == null) return null;
        if (rotation == 0) return new Point(point);
        BufferedImage source = rotating.getSourceImage();
        int w = source == null ? 1 : source.getWidth();
        int h = source == null ? 1 : source.getHeight();
        switch (rotation) {
            case 90: return new Point(point.y, h - point.x);
            case 180: return new Point(w - point.x, h - point.y);
            case 270: return new Point(w - point.y, point.x);
        }
        return new Point(point);
    }

    /**
     * Owns mouse press/release on the projected screen: records macros, injects touch
     * events, and turns an ⌥Option click into a guaranteed long-press (hold 650 ms even
     * for a quick click). Holding a normal click already long-presses natively.
     */
    private static void installTouchHandler(Window window, RotatingMultiFunctionLabel rotating,
            java.util.List<long[]> macroEvents, boolean[] macroRecording, boolean[] macroReplaying,
            long[] macroStart) {
        for (java.awt.event.MouseListener listener : rotating.getMouseListeners()) {
            rotating.removeMouseListener(listener);
        }
        final long[] altPressAt = {0};
        final Point[] altPoint = {null};
        rotating.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                rotating.requestFocus();
                if (!rotating.isScreenCapturingMode()) return;
                Point p = wheelPoint(rotating, e.getPoint());
                if (p == null) return;
                recordMacro(macroEvents, macroRecording, macroReplaying, macroStart, 0, p.x, p.y);
                Object remote = field(window, "hosRemoteDevice", Object.class);
                if (!(remote instanceof HosRemoteDevice)) return;
                ((HosRemoteDevice) remote).onTouchDown(p.x, p.y);
                if (e.isAltDown()) {
                    altPoint[0] = p;
                    altPressAt[0] = System.currentTimeMillis();
                }
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!rotating.isScreenCapturingMode()) return;
                Point p = wheelPoint(rotating, e.getPoint());
                if (p == null) return;
                recordMacro(macroEvents, macroRecording, macroReplaying, macroStart, 1, p.x, p.y);
                Object remote = field(window, "hosRemoteDevice", Object.class);
                if (!(remote instanceof HosRemoteDevice)) return;
                if (e.isAltDown() && altPoint[0] != null) {
                    long held = System.currentTimeMillis() - altPressAt[0];
                    final Point up = altPoint[0];
                    altPoint[0] = null;
                    long delay = 650 - held;
                    if (delay <= 0) {
                        ((HosRemoteDevice) remote).onTouchUp(up.x, up.y);
                    } else {
                        CaptureSession heldSession = window.captureSession;
                        Timer finish = new Timer((int) delay, ev -> {
                            if (heldSession == null || !window.recovery.isCurrent(heldSession.token)) return;
                            Object dev = field(window, "hosRemoteDevice", Object.class);
                            if (dev instanceof HosRemoteDevice) ((HosRemoteDevice) dev).onTouchUp(up.x, up.y);
                        });
                        finish.setRepeats(false);
                        finish.start();
                    }
                } else {
                    ((HosRemoteDevice) remote).onTouchUp(p.x, p.y);
                }
            }
        });
    }

    /**
     * Records press (0), release (1) and drag (2) events while a macro recording is active.
     * Drag samples are throttled so replays stay smooth without flooding the list.
     */
    private static void recordMacro(java.util.List<long[]> events, boolean[] recording,
            boolean[] replaying, long[] start, int type, int x, int y) {
        if (!recording[0] || replaying[0]) return;
        long now = System.currentTimeMillis();
        if (start[0] == 0) start[0] = now;
        if (type == 2 && !events.isEmpty()) {
            long[] last = events.get(events.size() - 1);
            if (last[0] == 2 && now - start[0] - last[1] < 40) return;
            if (last[0] == 2 && Math.abs(last[2] - x) + Math.abs(last[3] - y) < 12) return;
        }
        events.add(new long[]{type, now - start[0], x, y});
    }

    /** Replays a recorded macro on a worker thread using the same touch-injection path as live input. */
    private static void replayMacro(Window window, java.util.List<long[]> events,
            boolean[] replaying, JLabel status) {
        if (events.isEmpty() || replaying[0]) return;
        Object remote = field(window, "hosRemoteDevice", Object.class);
        if (!(remote instanceof HosRemoteDevice)) {
            status.setText("请先连接手机");
            return;
        }
        replaying[0] = true;
        status.setText("正在回放宏…");
        CaptureSession replaySession = window.captureSession;
        new Thread(() -> {
            long base = System.currentTimeMillis();
            for (long[] event : events) {
                if (!replaying[0] || replaySession == null || !window.recovery.isCurrent(replaySession.token)
                        || window.recovery.state() != CaptureRecovery.State.CONNECTED) break;
                long wait = base + event[1] - System.currentTimeMillis();
                if (wait > 0) {
                    try { Thread.sleep(wait); } catch (InterruptedException ignored) { break; }
                }
                if (!replaying[0] || !window.recovery.isCurrent(replaySession.token)
                        || window.recovery.state() != CaptureRecovery.State.CONNECTED) break;
                try {
                    switch ((int) event[0]) {
                        case 0: ((HosRemoteDevice) remote).onTouchDown((int) event[2], (int) event[3]); break;
                        case 1: ((HosRemoteDevice) remote).onTouchUp((int) event[2], (int) event[3]); break;
                        default: ((HosRemoteDevice) remote).onTouchMove((int) event[2], (int) event[3]); break;
                    }
                } catch (Throwable error) {
                    System.err.println("宏回放事件失败: " + error);
                }
            }
            if (replaySession != null && window.recovery.isCurrent(replaySession.token)) replaying[0] = false;
            SwingUtilities.invokeLater(() -> status.setText("宏回放完成"));
        }, "macro-replay").start();
    }

    /**
     * Coalesces Mac trackpad wheel bursts (including momentum events) into bounded,
     * unit-sized scroll dispatches, sending the stop only after the gesture goes idle.
     */
    static final class WheelCoalescer {
        interface Sender {
            boolean capturing();
            void scroll(boolean up);
            void stop();
        }
        private final Sender sender;
        private final double[] pending = {0};
        private final long[] lastWheelAt = {System.currentTimeMillis()};
        private final boolean[] stopped = {true};
        private final Timer[] timer = {null};
        WheelCoalescer(Sender sender) {
            this.sender = sender;
            timer[0] = new Timer(40, event -> {
                if (!sender.capturing()) return;
                double value = pending[0];
                if (value <= -1) {
                    pending[0] = value + 1;
                    stopped[0] = false;
                    sender.scroll(true);
                } else if (value >= 1) {
                    pending[0] = value - 1;
                    stopped[0] = false;
                    sender.scroll(false);
                } else if (!stopped[0] && System.currentTimeMillis() - lastWheelAt[0] > 250) {
                    stopped[0] = true;
                    sender.stop();
                }
                if (Math.abs(pending[0]) < 0.001 && System.currentTimeMillis() - lastWheelAt[0] > 400) {
                    timer[0].stop();
                }
            });
        }
        void onWheel(double rotation) {
            pending[0] += rotation;
            lastWheelAt[0] = System.currentTimeMillis();
            stopped[0] = false;
            if (!timer[0].isRunning()) timer[0].start();
        }
        void dispose() {
            timer[0].stop();
        }
    }

    /** Toggle decorations without stopping capture, keyboard bindings or status updates. */
    private static void setUndecoratedQuietly(Window window, boolean undecorated, Rectangle bounds) {
        if (!undecorated && window.getBackground().getAlpha() != 255) window.setBackground(SURFACE);
        if (window.isUndecorated() == undecorated) {
            if (bounds != null) window.setBounds(bounds);
            return;
        }
        boolean visible = window.isVisible();
        window.keepTimerOnDispose = true;
        try {
            if (visible) window.setVisible(false);
            window.dispose();
            window.setUndecorated(undecorated);
            if (bounds != null) window.setBounds(bounds);
            if (visible) window.setVisible(true);
            window.toFront();
        } finally {
            window.keepTimerOnDispose = false;
        }
    }

    /** Fit a compact window to the current phone aspect ratio and restore the expanded window. */
    private static final class SidebarWindowSize {
        private final Window window;
        private final MultiFunctionLabel display;
        private Rectangle expandedBounds;
        private Dimension expandedMinimum;
        private int expandedState;
        private double ratio = 0.45;
        SidebarWindowSize(Window window, MultiFunctionLabel display) {
            this.window = window;
            this.display = display;
        }
        void update(boolean collapsed, boolean dark) {
            if (dark || (window.getExtendedState() & JFrame.ICONIFIED) != 0) return;
            if (!collapsed) {
                if (expandedBounds != null) {
                    window.setMinimumSize(expandedMinimum);
                    window.setExtendedState(JFrame.NORMAL);
                    setUndecoratedQuietly(window, false, expandedBounds);
                    window.setExtendedState(expandedState);
                    expandedBounds = null;
                }
                return;
            }
            if (expandedBounds == null) {
                expandedBounds = window.getBounds();
                expandedMinimum = window.getMinimumSize();
                expandedState = window.getExtendedState();
                Insets decoration = window.getInsets();
                Rectangle contentBounds = new Rectangle(expandedBounds);
                contentBounds.x += decoration.left;
                contentBounds.y += decoration.top;
                contentBounds.width -= decoration.left + decoration.right;
                contentBounds.height -= decoration.top + decoration.bottom;
                window.setMinimumSize(new Dimension(160, 240));
                window.setExtendedState(JFrame.NORMAL);
                setUndecoratedQuietly(window, true, contentBounds);
            }
            if (!window.isUndecorated()) setUndecoratedQuietly(window, true, window.getBounds());
            // macOS supports transparent windows, but not AWT's setShape API.
            // Clip the entire content hierarchy so the desktop shows through the corners.
            window.setBackground(new Color(0, 0, 0, 0));
            if (window.getExtendedState() != JFrame.NORMAL) window.setExtendedState(JFrame.NORMAL);
            BufferedImage image = display.getImage();
            if (image != null) ratio = (double) image.getWidth() / image.getHeight();
            Insets frame = window.getInsets();
            GraphicsConfiguration config = window.getGraphicsConfiguration();
            Rectangle usable = new Rectangle(config.getBounds());
            Insets screen = Toolkit.getDefaultToolkit().getScreenInsets(config);
            usable.x += screen.left; usable.y += screen.top;
            usable.width -= screen.left + screen.right;
            usable.height -= screen.top + screen.bottom;
            int borderWidth = frame.left + frame.right;
            int borderHeight = frame.top + frame.bottom;
            int minimumHeight = Math.min(usable.height, Math.max(240, (int) Math.ceil(160 / ratio) + borderHeight));
            Dimension minimum = new Dimension(160, minimumHeight);
            if (!window.getMinimumSize().equals(minimum)) window.setMinimumSize(minimum);
            int height = Math.max(minimumHeight, Math.min(window.getHeight(), usable.height));
            int width = (int) ((height - borderHeight) * ratio) + borderWidth;
            if (width > usable.width) {
                width = usable.width;
                height = (int) Math.ceil((width - borderWidth) / ratio) + borderHeight;
            }
            int x = Math.max(usable.x, Math.min(window.getX(), usable.x + usable.width - width));
            int y = Math.max(usable.y, Math.min(window.getY(), usable.y + usable.height - height));
            Rectangle compact = new Rectangle(x, y, width, height);
            if (!window.getBounds().equals(compact)) window.setBounds(compact);
        }
    }

    private static final class PhoneStage extends JPanel {
        private final MultiFunctionLabel display;
        private final JButton lightButton;
        private boolean dark;
        private boolean sidebarCollapsed;
        private JPanel overlayRail;
        private double imageRatio = 0.45;
        PhoneStage(MultiFunctionLabel display, JButton lightButton) {
            super(null);
            this.display = display;
            this.lightButton = lightButton;
            setBackground(SURFACE);
            display.setBackground(SURFACE);
            display.setBorder(null);
            add(display);
            add(lightButton);
            setComponentZOrder(lightButton, 0);
            updateBackground();
        }
        void setDark(boolean dark) {
            this.dark = dark;
            updateBackground();
            lightButton.setVisible(dark);
            revalidate();
            repaint();
        }
        void setSidebarCollapsed(boolean collapsed) {
            sidebarCollapsed = collapsed;
            updateBackground();
        }
        void setOverlayRail(JPanel rail) {
            if (overlayRail == rail) return;
            if (overlayRail != null) remove(overlayRail);
            overlayRail = rail;
            if (rail != null) {
                add(rail);
                setComponentZOrder(rail, 0);
            }
            revalidate();
            repaint();
        }
        private void updateBackground() {
            boolean lightsOut = dark || sidebarCollapsed;
            setBackground(lightsOut ? Color.BLACK : SURFACE);
            display.setBackground(getBackground());
            display.setForeground(lightsOut ? Color.WHITE : INK);
            repaint();
            display.repaint();
        }
        @Override public void doLayout() {
            BufferedImage image = display.getImage();
            if (image != null) imageRatio = (double) image.getWidth() / image.getHeight();
            double ratio = imageRatio;
            int topInset = 0;
            if (dark) {
                GraphicsConfiguration config = getGraphicsConfiguration();
                if (config != null) topInset = Toolkit.getDefaultToolkit().getScreenInsets(config).top;
            }
            int availableWidth = Math.max(1, getWidth() - (dark ? 20 : 0));
            int availableHeight = Math.max(1, getHeight() - (dark ? 20 + topInset : 0));
            int width = Math.max(1, (int) Math.min(availableWidth, availableHeight * ratio));
            int height = Math.max(1, (int) (width / ratio));
            display.setBounds((getWidth() - width) / 2, (getHeight() - height) / 2, width, height);
            if (lightButton.isVisible()) {
                lightButton.setBounds(getWidth() - 54, getHeight() - 54, 44, 44);
            }
            if (overlayRail != null) overlayRail.setBounds(Math.max(0, getWidth() - 16), 0, 16, getHeight());
        }
    }

    /** A quiet edge chevron, like a web sidebar rail, with a small hover target. */
    private static final class SidebarToggle extends JButton {
        SidebarToggle() {
            setBorder(null);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isRollover() || getModel().isPressed()) {
                boolean dark = getParent() != null && Color.BLACK.equals(getParent().getBackground());
                g.setColor(dark ? new Color(getModel().isPressed() ? 0x303640 : 0x20242A)
                    : getModel().isPressed() ? LINE : new Color(0xE9EEF5));
                g.fillRoundRect(1, 4, getWidth() - 2, getHeight() - 8, 6, 6);
            }
            Icon icon = getIcon();
            if (icon != null) icon.paintIcon(this, g, (getWidth() - icon.getIconWidth()) / 2,
                (getHeight() - icon.getIconHeight()) / 2);
            g.dispose();
        }
    }

    private static final class StatusDot implements Icon {
        private final Color color;
        StatusDot(Color color) { this.color = color; }
        public int getIconWidth() { return 8; }
        public int getIconHeight() { return 8; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color); g.fillOval(x + 1, y + 1, 6, 6); g.dispose();
        }
    }

    /** Rotates every incoming frame for a landscape display; input coordinates are un-rotated by RotatingDevice. */
    static final class RotatingMultiFunctionLabel extends MultiFunctionLabel {
        interface FrameSink { void onFrame(BufferedImage image); }
        private Consumer<Integer> arrowSwipe;
        private Window controlledWindow;
        private JPopupMenu windowMenu;
        private java.util.function.BooleanSupplier windowDraggable;
        private Point windowDragStart;
        private Point windowDragOrigin;
        private boolean windowPopupGesture;
        private final boolean[] arrowsHeld = new boolean[4];
        private BufferedImage source;
        private BufferedImage display;
        private String ownTip = "";
        private int viewRotation;
        private FrameSink frameSink;
        private long lastFrameAt;
        private long maxGapMs;
        private int framesInWindow;
        private long windowStart = System.currentTimeMillis();

        void setArrowSwipe(Consumer<Integer> swipe) { arrowSwipe = swipe; }
        void setWindowControls(Window window, JPopupMenu menu, java.util.function.BooleanSupplier draggable) {
            controlledWindow = window;
            windowMenu = menu;
            setComponentPopupMenu(menu);
            windowDraggable = draggable;
        }
        @Override public JPopupMenu getComponentPopupMenu() {
            return controlledWindow != null && controlledWindow.isUndecorated() ? windowMenu : null;
        }
        @Override protected void processMouseEvent(java.awt.event.MouseEvent event) {
            if (event.getID() == java.awt.event.MouseEvent.MOUSE_RELEASED
                    && (windowDragStart != null || windowPopupGesture)) {
                windowDragStart = windowDragOrigin = null;
                windowPopupGesture = false;
                event.consume();
                return;
            }
            if (event.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                windowPopupGesture = false;
                if (controlledWindow != null && controlledWindow.isUndecorated()) {
                    if (event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event)) {
                        windowPopupGesture = true;
                        windowMenu.show(this, event.getX(), event.getY());
                        event.consume();
                        return;
                    }
                    if (event.isMetaDown() && SwingUtilities.isLeftMouseButton(event) && windowDraggable.getAsBoolean()) {
                        windowDragStart = event.getLocationOnScreen();
                        windowDragOrigin = controlledWindow.getLocation();
                        event.consume();
                        return;
                    }
                }
            }
            super.processMouseEvent(event);
        }
        @Override protected void processMouseMotionEvent(java.awt.event.MouseEvent event) {
            if (event.getID() == java.awt.event.MouseEvent.MOUSE_DRAGGED && windowDragStart != null) {
                Point current = event.getLocationOnScreen();
                controlledWindow.setLocation(windowDragOrigin.x + current.x - windowDragStart.x,
                    windowDragOrigin.y + current.y - windowDragStart.y);
                event.consume();
                return;
            }
            super.processMouseMotionEvent(event);
        }
        @Override protected void processKeyEvent(KeyEvent event) {
            int direction = switch (event.getKeyCode()) {
                case KeyEvent.VK_LEFT -> 0;
                case KeyEvent.VK_RIGHT -> 1;
                case KeyEvent.VK_UP -> 2;
                case KeyEvent.VK_DOWN -> 3;
                default -> -1;
            };
            if (direction >= 0) {
                if (event.getID() == KeyEvent.KEY_RELEASED && arrowsHeld[direction]) {
                    arrowsHeld[direction] = false;
                    event.consume();
                    return;
                }
                if (event.getID() == KeyEvent.KEY_PRESSED && event.getModifiersEx() == 0
                        && isScreenCapturingMode() && arrowSwipe != null) {
                    // One swipe per press; auto-repeat must not queue a long trail of gestures.
                    if (!arrowsHeld[direction]) {
                        arrowsHeld[direction] = true;
                        arrowSwipe.accept(direction);
                    }
                    event.consume();
                    return;
                }
            }
            super.processKeyEvent(event);
        }
        @Override protected void processFocusEvent(java.awt.event.FocusEvent event) {
            if (event.getID() == java.awt.event.FocusEvent.FOCUS_LOST) java.util.Arrays.fill(arrowsHeld, false);
            super.processFocusEvent(event);
        }
        @Override public void setScreenCapturingMode(boolean capturing) {
            if (!capturing && arrowsHeld != null) java.util.Arrays.fill(arrowsHeld, false);
            super.setScreenCapturingMode(capturing);
        }
        void setViewRotation(int rotation) {
            viewRotation = rotation;
            if (source != null) setImage(source);
        }
        int getViewRotation() { return viewRotation; }
        BufferedImage getSourceImage() { return source; }
        long lastFrameAt() { return lastFrameAt; }
        void setFrameSink(FrameSink sink) { this.frameSink = sink; }
        @Override public void setTipMessage(String tipMessage) {
            ownTip = tipMessage;
            super.setTipMessage(tipMessage);
        }
        @Override public void setImage(BufferedImage image) {
            source = image;
            if (image != null) ownTip = "";
            BufferedImage shown = rotate(image, viewRotation);
            display = shown;
            long now = System.currentTimeMillis();
            if (lastFrameAt != 0) maxGapMs = Math.max(maxGapMs, now - lastFrameAt);
            lastFrameAt = now;
            framesInWindow++;
            super.setImage(shown);
            if (frameSink != null && shown != null) frameSink.onFrame(shown);
        }
        /** Frame pacing diagnostics: fps and worst frame gap since the last call. */
        String logFrameStats() {
            double secs = (System.currentTimeMillis() - windowStart) / 1000.0;
            double fps = secs > 0 ? framesInWindow / secs : 0;
            String stats = String.format("FRAME_LOG fps=%.1f maxGapMs=%d", fps, maxGapMs);
            framesInWindow = 0;
            maxGapMs = 0;
            windowStart = System.currentTimeMillis();
            return stats;
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), PHONE_CORNER_ARC, PHONE_CORNER_ARC));
            BufferedImage shown = display != null ? display : getImage();
            if (shown != null) {
                double aspect = (double) shown.getWidth() / shown.getHeight();
                int w = Math.max(1, (int) Math.min(getWidth(), aspect * getHeight()));
                int h = Math.max(1, (int) (w / aspect));
                int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                g.drawImage(shown, x, y, w, h, null);
            }
            if (ownTip != null && !ownTip.isEmpty()) {
                FontMetrics metrics = g.getFontMetrics();
                int stringWidth = metrics.stringWidth(ownTip);
                int x = (getWidth() - stringWidth) / 4;
                int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.setFont(new Font(null, Font.PLAIN, 20));
                g.setColor(getForeground());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawString(ownTip, x, y);
            }
            g.dispose();
        }
        private static BufferedImage rotate(BufferedImage image, int rotation) {
            if (image == null || rotation == 0) return image;
            int w = image.getWidth(), h = image.getHeight();
            boolean swapped = rotation == 90 || rotation == 270;
            BufferedImage out = new BufferedImage(swapped ? h : w, swapped ? w : h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            switch (rotation) {
                case 90: g.translate(h, 0); g.rotate(Math.PI / 2); break;
                case 180: g.translate(w, h); g.rotate(Math.PI); break;
                case 270: g.translate(0, w); g.rotate(-Math.PI / 2); break;
            }
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return out;
        }
    }

    /**
     * Low-latency H.264 decoder: single-threaded decode with full-resolution RGB output.
     * Replaces the upstream decode so frames render off the EDT without accumulating latency.
     */
    static final class CastDecoder {
        private final AVCodecContext codecCtx;
        private final AVCodecParserContext parser;
        private final AVFrame frame = av_frame_alloc();
        private final AVFrame frameRGB = av_frame_alloc();
        private AVPacket packet = av_packet_alloc();
        private SwsContext sws;
        private BufferedImage output;
        private int inW = -1, inH = -1, inFormat = -1;

        CastDecoder() {
            AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_H264);
            codecCtx = avcodec_alloc_context3(codec);
            parser = av_parser_init(AV_CODEC_ID_H264);
            avcodec_open2(codecCtx, codec, (PointerPointer<Pointer>) null);
            av_new_packet(packet, 3000);
        }

        /** Decodes one H.264 chunk and returns the newest frame, or null when none was ready. */
        BufferedImage feed(byte[] h264) {
            ByteBuffer data = ByteBuffer.allocate(h264.length).put(h264);
            int curSize = h264.length;
            IntPointer pktSize = new IntPointer(packet.size());
            BytePointer tempBp = new BytePointer();
            BufferedImage result = null;
            while (curSize > 0) {
                data.flip();
                BytePointer dataPointer = new BytePointer(data);
                int len = av_parser_parse2(parser, codecCtx, tempBp, pktSize, dataPointer, curSize,
                    AV_NOPTS_VALUE, AV_NOPTS_VALUE, AV_NOPTS_VALUE);
                packet = packet.size(pktSize.get());
                data.position(len);
                data = data.compact();
                curSize -= len;
                if (pktSize.get() == 0) continue;
                packet = packet.data(tempBp);
                avcodec_send_packet(codecCtx, packet);
                while (avcodec_receive_frame(codecCtx, frame) == 0) {
                    BufferedImage img = convert(frame);
                    if (img != null) result = img;
                }
            }
            return result;
        }

        private BufferedImage convert(AVFrame f) {
            int w = f.width(), h = f.height(), format = f.format();
            if (w <= 0 || h <= 0 || format < 0) return null;
            if (sws == null || w != inW || h != inH || format != inFormat) {
                inW = w; inH = h; inFormat = format;
                if (sws != null) sws_freeContext(sws);
                if (output != null) av_free(frameRGB.data(0));
                frameRGB.format(AV_PIX_FMT_BGR24);
                frameRGB.width(w);
                frameRGB.height(h);
                av_image_alloc(frameRGB.data(), frameRGB.linesize(), w, h, AV_PIX_FMT_BGR24, 1);
                output = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                sws = sws_getCachedContext(null, w, h, format, w, h, AV_PIX_FMT_BGR24,
                    SWS_FAST_BILINEAR, null, null, (DoublePointer) null);
            }
            sws_scale(sws, f.data(), f.linesize(), 0, h, frameRGB.data(), frameRGB.linesize());
            DataBufferByte buffer = (DataBufferByte) output.getRaster().getDataBuffer();
            frameRGB.data(0).get(buffer.getData());
            return output;
        }
        void close() {
            av_packet_free(packet);
            av_parser_close(parser);
            avcodec_free_context(codecCtx);
            av_frame_free(frame);
            if (output != null) av_free(frameRGB.data(0));
            av_frame_free(frameRGB);
            if (sws != null) sws_freeContext(sws);
        }
    }

    /** Records the displayed frames to an MP4 file using the bundled FFmpeg. */
    static final class ScreenRecorder {
        private FFmpegFrameRecorder recorder;
        private Java2DFrameConverter converter;
        private int width;
        private int height;
        private File output;

        boolean isRecording() { return recorder != null; }
        File start(BufferedImage frame) throws Exception {
            File dir = new File(System.getProperty("user.home"), "Movies/鸿蒙投屏");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            output = new File(dir, "鸿蒙投屏-录制-" + stamp + ".mp4");
            width = frame.getWidth();
            height = frame.getHeight();
            recorder = new FFmpegFrameRecorder(output.getAbsolutePath(), width, height);
            recorder.setFormat("mp4");
            recorder.setVideoCodecName("libx264");
            recorder.setFrameRate(30);
            recorder.setVideoBitrate(4000000);
            recorder.start();
            converter = new Java2DFrameConverter();
            return output;
        }
        void record(BufferedImage image) {
            if (recorder == null || image == null) return;
            try {
                if (image.getWidth() != width || image.getHeight() != height) {
                    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image, 0, 0, width, height, null);
                    g.dispose();
                    image = scaled;
                }
                recorder.record(converter.convert(image));
            } catch (Exception error) {
                System.err.println("录制帧失败: " + error);
            }
        }
        File stop() {
            if (recorder == null) return null;
            try { recorder.stop(); } catch (Exception error) {
                System.err.println("停止录制失败: " + error);
            }
            try { recorder.release(); } catch (Exception error) {
                System.err.println("释放录制器失败: " + error);
            }
            recorder = null;
            converter = null;
            return output;
        }
    }

    /** Wraps the active device so touch/mouse coordinates match the rotated display. */
    static final class RotatingDevice extends HosRemoteDevice {
        private final HosRemoteDevice delegate;
        private final RotatingMultiFunctionLabel label;

        RotatingDevice(HosRemoteDevice delegate, RotatingMultiFunctionLabel label) {
            super(new HosRemoteConfig("view-rotation-proxy"));
            this.delegate = delegate;
            this.label = label;
        }
        private Point unrotate(int x, int y) {
            int rotation = label.getViewRotation();
            if (rotation == 0) return new Point(x, y);
            BufferedImage source = label.getSourceImage();
            int w = source == null ? 1 : source.getWidth();
            int h = source == null ? 1 : source.getHeight();
            switch (rotation) {
                case 90: return new Point(y, h - x);
                case 180: return new Point(w - x, h - y);
                case 270: return new Point(w - y, x);
            }
            return new Point(x, y);
        }
        @Override public void onTouchDown(int x, int y) { Point p = unrotate(x, y); delegate.onTouchDown(p.x, p.y); }
        @Override public void onTouchUp(int x, int y) { Point p = unrotate(x, y); delegate.onTouchUp(p.x, p.y); }
        @Override public void onTouchMove(int x, int y) { Point p = unrotate(x, y); delegate.onTouchMove(p.x, p.y); }
        @Override public void onMouseDown(String button, int x, int y) { Point p = unrotate(x, y); delegate.onMouseDown(button, p.x, p.y); }
        @Override public void onMouseMove(String button, int x, int y) { Point p = unrotate(x, y); delegate.onMouseMove(button, p.x, p.y); }
        @Override public void onMouseUp(String button, int x, int y) { Point p = unrotate(x, y); delegate.onMouseUp(button, p.x, p.y); }
        @Override public void onMouseWheelUp(int x, int y) { Point p = unrotate(x, y); delegate.onMouseWheelUp(p.x, p.y); }
        @Override public void onMouseWheelDown(int x, int y) { Point p = unrotate(x, y); delegate.onMouseWheelDown(p.x, p.y); }
        @Override public void onMouseWheelStop(int x, int y) { Point p = unrotate(x, y); delegate.onMouseWheelStop(p.x, p.y); }
        @Override public void startCaptureScreen(ScreenCapCallback callback) { delegate.startCaptureScreen(callback); }
        @Override public void stopCaptureScreen() { delegate.stopCaptureScreen(); }
        @Override public void stopCaptureScreen(boolean force) { delegate.stopCaptureScreen(force); }
        @Override public String executeShellCommand(String command, int timeoutSeconds) { return delegate.executeShellCommand(command, timeoutSeconds); }
        @Override public Size getScreenSize(boolean refresh) { return delegate.getScreenSize(refresh); }
        @Override public boolean isOnline() { return delegate.isOnline(); }
        @Override public String getLayout() { return delegate.getLayout(); }
        @Override public void invokeGenerateLayout(String json) { delegate.invokeGenerateLayout(json); }
        @Override public void setRotationHorizontal() { delegate.setRotationHorizontal(); }
        @Override public void setRotationVertical() { delegate.setRotationVertical(); }
        @Override public String getSn() { return delegate != null ? delegate.getSn() : super.getSn(); }
        @Override public String getIp() { return delegate != null ? delegate.getIp() : super.getIp(); }
        @Override public String getHdcPath() { return delegate != null ? delegate.getHdcPath() : super.getHdcPath(); }
        @Override public String getHdcPort() { return delegate != null ? delegate.getHdcPort() : super.getHdcPort(); }
        @Override public void requestIDRFrame() { delegate.requestIDRFrame(); }
        @Override public void stopImageScreenCapture() { delegate.stopImageScreenCapture(); }
        @Override public void startImageScreenCapture(ScreenCapCallback callback) { delegate.startImageScreenCapture(callback); }
        @Override public int getScreenId() { return delegate != null ? delegate.getScreenId() : super.getScreenId(); }
        @Override public float getImageScaleSize() { return delegate != null ? delegate.getImageScaleSize() : super.getImageScaleSize(); }
    }

    private static final class LineIcon implements Icon {
        private final String name;
        private final Color color;
        private final int size;
        LineIcon(String name, Color color, int size) { this.name = name; this.color = color; this.size = size; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y); g.scale(size / 24.0, size / 24.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean enabled = c.isEnabled();
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(enabled ? color : MUTED);
            switch (name) {
                case "chevronLeft": g.drawPolyline(new int[]{15, 9, 15}, new int[]{6, 12, 18}, 3); break;
                case "chevronRight": g.drawPolyline(new int[]{9, 15, 9}, new int[]{6, 12, 18}, 3); break;
                case "phone": g.drawRoundRect(7, 2, 10, 20, 4, 4); g.drawLine(10, 5, 14, 5); g.drawLine(10, 17, 14, 17); break;
                case "back": g.drawPolyline(new int[]{14, 8, 14}, new int[]{6, 12, 18}, 3); break;
                case "power": g.drawArc(5, 4, 14, 14, 95, 265); g.drawLine(12, 2, 12, 10); break;
                case "refresh":
                    g.drawArc(4, 4, 16, 16, 90, -270);
                    g.drawLine(11, 4, 17, 4);
                    g.drawPolyline(new int[]{17, 13, 17}, new int[]{4, 1, 7}, 3);
                    break;
                case "rotate":
                    g.drawArc(4, 4, 16, 16, 90, -250);
                    g.drawLine(11, 4, 16, 4);
                    g.drawPolyline(new int[]{16, 12, 16}, new int[]{4, 1, 7}, 3);
                    g.setColor(enabled ? BLUE : MUTED);
                    g.drawArc(8, 8, 8, 8, 90, -250);
                    g.drawPolyline(new int[]{14, 10, 14}, new int[]{8, 5, 11}, 3);
                    break;
                case "shot":
                    g.drawRoundRect(3, 7, 18, 12, 4, 4);
                    g.drawOval(8, 9, 8, 8);
                    g.setColor(enabled ? BLUE : MUTED);
                    g.fillOval(17, 8, 3, 3);
                    break;
                case "record": g.drawOval(4, 4, 16, 16); g.fillOval(9, 9, 6, 6); break;
                case "pin": g.drawOval(8, 2, 8, 8); g.drawLine(12, 10, 12, 20); g.drawLine(7, 20, 17, 20); break;
                case "forward":
                    g.drawRoundRect(2, 9, 7, 6, 2, 2);
                    g.drawLine(9, 12, 19, 12);
                    g.drawPolyline(new int[]{19, 14, 19}, new int[]{12, 7, 12}, 3);
                    break;
                case "install":
                    g.drawRoundRect(4, 10, 16, 10, 3, 3);
                    g.drawLine(12, 3, 12, 12);
                    g.drawPolyline(new int[]{8, 12, 16}, new int[]{8, 12, 8}, 3);
                    break;
                case "transfer":
                    g.drawLine(5, 17, 5, 8);
                    g.drawPolyline(new int[]{5, 1, 5}, new int[]{8, 12, 12}, 3);
                    g.drawPolyline(new int[]{5, 9, 5}, new int[]{8, 12, 12}, 3);
                    g.drawLine(19, 7, 19, 16);
                    g.drawPolyline(new int[]{19, 15, 19}, new int[]{16, 12, 12}, 3);
                    g.drawPolyline(new int[]{19, 23, 19}, new int[]{16, 12, 12}, 3);
                    break;
                case "volumeDown":
                    g.drawPolyline(new int[]{3, 8, 12, 12, 8, 3}, new int[]{10, 10, 6, 18, 14, 14}, 6);
                    g.drawArc(14, 9, 5, 6, -75, 150);
                    break;
                case "volumeUp":
                    g.drawPolyline(new int[]{3, 8, 12, 12, 8, 3}, new int[]{10, 10, 6, 18, 14, 14}, 6);
                    g.drawArc(14, 7, 5, 10, -75, 150);
                    g.drawArc(18, 7, 5, 10, -75, 150);
                    break;
                case "expand":
                    g.drawPolyline(new int[]{5, 5, 9}, new int[]{9, 5, 5}, 3);
                    g.drawPolyline(new int[]{15, 19, 19}, new int[]{5, 5, 9}, 3);
                    g.drawPolyline(new int[]{5, 5, 9}, new int[]{15, 19, 19}, 3);
                    g.drawPolyline(new int[]{15, 19, 19}, new int[]{19, 19, 15}, 3);
                    break;
                case "layout":
                    g.drawRect(5, 3, 7, 7);
                    g.drawRect(5, 13, 7, 7);
                    g.drawRect(14, 13, 6, 7);
                    g.drawLine(8, 10, 8, 13);
                    g.drawLine(11, 10, 11, 13);
                    g.drawLine(11, 17, 14, 17);
                    break;
                case "settings":
                    g.drawOval(7, 7, 10, 10);
                    g.drawOval(10, 10, 4, 4);
                    g.drawLine(12, 2, 12, 5);
                    g.drawLine(12, 19, 12, 22);
                    g.drawLine(2, 12, 5, 12);
                    g.drawLine(19, 12, 22, 12);
                    g.drawLine(5, 5, 7, 7);
                    g.drawLine(17, 17, 19, 19);
                    g.drawLine(19, 5, 17, 7);
                    g.drawLine(5, 19, 7, 17);
                    break;
                case "frame":
                    g.drawRoundRect(3, 5, 18, 14, 4, 4);
                    g.drawLine(7, 12, 17, 12);
                    g.drawPolyline(new int[]{13, 17, 13}, new int[]{7, 12, 17}, 3);
                    break;
                case "landscape":
                    g.drawRoundRect(2, 7, 20, 11, 3, 3);
                    g.drawLine(5, 10, 5, 15);
                    g.drawLine(19, 10, 19, 15);
                    break;
                case "portrait":
                    g.drawRoundRect(7, 2, 10, 20, 3, 3);
                    g.drawLine(10, 5, 10, 19);
                    break;
                case "unlock":
                    g.drawRoundRect(5, 11, 14, 9, 3, 3);
                    g.drawArc(8, 6, 8, 8, 180, 180);
                    g.drawLine(12, 15, 12, 18);
                    break;
                case "moon":
                    g.drawArc(4, 3, 15, 15, 95, 250);
                    g.drawArc(9, 6, 11, 11, -50, 230);
                    break;
                case "sun":
                    g.drawOval(8, 8, 8, 8);
                    g.drawLine(12, 2, 12, 6);
                    g.drawLine(12, 18, 12, 22);
                    g.drawLine(2, 12, 6, 12);
                    g.drawLine(18, 12, 22, 12);
                    g.drawLine(5, 5, 8, 8);
                    g.drawLine(16, 16, 19, 19);
                    g.drawLine(16, 8, 19, 5);
                    g.drawLine(5, 19, 8, 16);
                    break;
                case "home":
                    g.drawPolyline(new int[]{4, 12, 20}, new int[]{12, 4, 12}, 3);
                    g.drawLine(7, 10, 7, 20);
                    g.drawLine(17, 10, 17, 20);
                    g.drawLine(7, 20, 17, 20);
                    break;
                case "recents":
                    g.drawRoundRect(3, 5, 8, 14, 2, 2);
                    g.drawRoundRect(13, 5, 8, 14, 2, 2);
                    break;
                case "notice":
                    g.drawArc(5, 5, 14, 12, 180, 180);
                    g.drawLine(12, 19, 12, 20);
                    g.drawLine(3, 12, 21, 12);
                    break;
                case "send":
                    g.drawLine(3, 13, 20, 3);
                    g.drawLine(3, 13, 20, 21);
                    g.drawLine(3, 13, 11, 16);
                    g.drawLine(11, 16, 20, 21);
                    break;
                case "ocr":
                    g.drawOval(4, 4, 12, 12);
                    g.drawLine(14, 14, 20, 20);
                    g.drawPolyline(new int[]{9, 7, 11}, new int[]{9, 13, 13}, 3);
                    g.drawLine(9, 10, 9, 13);
                    break;
                case "apps":
                    g.drawRoundRect(3, 3, 8, 8, 2, 2);
                    g.drawRoundRect(13, 3, 8, 8, 2, 2);
                    g.drawRoundRect(3, 13, 8, 8, 2, 2);
                    g.drawRoundRect(13, 13, 8, 8, 2, 2);
                    break;
                case "timer":
                    g.drawOval(4, 4, 16, 16);
                    g.drawLine(12, 12, 12, 7);
                    g.drawLine(12, 12, 16, 13);
                    break;
                case "replay":
                    g.drawArc(5, 5, 14, 14, 60, -270);
                    g.drawPolyline(new int[]{13, 17, 13}, new int[]{3, 1, 7}, 3);
                    g.drawPolyline(new int[]{13, 17, 13}, new int[]{20, 18, 14}, 3);
                    break;
                case "type":
                    g.drawRoundRect(3, 7, 12, 10, 3, 3);
                    g.drawLine(9, 7, 9, 17);
                    g.drawLine(15, 4, 20, 4);
                    g.drawLine(17, 2, 17, 7);
                    break;
                case "paste":
                    g.drawRoundRect(5, 8, 14, 12, 3, 3);
                    g.drawPolyline(new int[]{8, 8, 12}, new int[]{4, 8, 8}, 3);
                    g.drawLine(9, 13, 15, 13);
                    g.drawLine(9, 16, 15, 16);
                    break;
                case "up":
                    g.drawLine(12, 4, 12, 18);
                    g.drawPolyline(new int[]{5, 12, 19}, new int[]{11, 4, 11}, 3);
                    break;
                case "down":
                    g.drawLine(12, 20, 12, 6);
                    g.drawPolyline(new int[]{5, 12, 19}, new int[]{13, 20, 13}, 3);
                    break;
                case "left":
                    g.drawLine(20, 12, 4, 12);
                    g.drawPolyline(new int[]{11, 4, 11}, new int[]{5, 12, 19}, 3);
                    break;
                case "right":
                    g.drawLine(4, 12, 20, 12);
                    g.drawPolyline(new int[]{13, 20, 13}, new int[]{5, 12, 19}, 3);
                    break;
                default:
                    g.drawPolyline(new int[]{3, 7, 12, 12, 7, 3, 3}, new int[]{9, 9, 5, 19, 15, 15, 9}, 7);
                    g.drawLine(16, 12, 22, 12);
            }
            g.dispose();
        }
    }
}

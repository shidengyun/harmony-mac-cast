import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import utils.entity.JsonStructure;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Copy text from a frozen phone frame; selection never reaches the phone input channel. */
public final class ScreenText {
    static String layoutText(String json) {
        JsonStructure root = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
            .fromJson(json, JsonStructure.class);
        List<String> lines = new ArrayList<>();
        collect(root, lines);
        return String.join("\n", lines);
    }

    private static void collect(JsonStructure node, List<String> lines) {
        if (node == null || node.getAttributes() == null) return;
        Object visible = node.getAttributes().get("visible");
        if (Boolean.FALSE.equals(visible) || "false".equalsIgnoreCase(String.valueOf(visible))) return;
        Object value = node.getAttributes().get("text");
        String text = value instanceof String ? ((String) value).trim() : "";
        List<JsonStructure> children = node.getChildren();
        boolean repeatedByChild = children != null && children.stream().anyMatch(child ->
            child != null && child.getAttributes() != null
                && text.equals(child.getAttributes().get("text")));
        if (!text.isEmpty() && !repeatedByChild) lines.add(text);
        if (children != null) for (JsonStructure child : children) collect(child, lines);
    }

    static String recognize(BufferedImage image) throws Exception {
        String configured = System.getenv("CAST_OCR_PATH");
        File helper = configured == null ? null : new File(configured);
        if (helper == null) {
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                File candidate = new File(entry, "ScreenTextOCR");
                if (candidate.canExecute()) { helper = candidate; break; }
            }
        }
        if (helper == null || !helper.canExecute()) throw new IOException("未找到文字识别组件，请使用新版 App");
        Path dir = Files.createTempDirectory("harmony-screen-text-");
        Path input = dir.resolve("frame.png"), output = dir.resolve("result.json"), errors = dir.resolve("errors.txt");
        Process process = null;
        try {
            if (!javax.imageio.ImageIO.write(image, "png", input.toFile())) throw new IOException("无法读取画面");
            process = new ProcessBuilder(helper.getAbsolutePath(), input.toString())
                .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("识别超时，请缩小框选区域后重试");
            if (process.exitValue() != 0) {
                throw new IOException(Files.readString(errors, StandardCharsets.UTF_8).trim());
            }
            if (Files.size(output) > 4_000_000) throw new IOException("识别内容过长，请缩小框选区域");
            JsonObject result = new GsonBuilder().create().fromJson(Files.readString(output), JsonObject.class);
            if (result == null || !result.has("text")) throw new IOException("识别结果无效");
            return result.get("text").getAsString();
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(errors);
            Files.deleteIfExists(dir);
        }
    }

    public static void show(JFrame owner, BufferedImage frame, Supplier<String> layout) {
        BufferedImage frozen = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frozen.createGraphics();
        graphics.drawImage(frame, 0, 0, null);
        graphics.dispose();
        JDialog dialog = new JDialog(owner, "复制画面文字", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel hint = new JLabel("框选左侧截图可识别局部文字；结果可编辑后复制。重新打开可获取最新画面。");
        content.add(hint, BorderLayout.NORTH);
        SelectionPanel picture = new SelectionPanel(frozen);
        JTextArea text = new JTextArea();
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font("Dialog", Font.PLAIN, 14));
        text.getAccessibleContext().setAccessibleName("可编辑的画面文字");
        JScrollPane textScroll = new JScrollPane(text);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, picture, textScroll);
        split.setResizeWeight(0.42);
        content.add(split, BorderLayout.CENTER);
        JLabel message = new JLabel("正在读取界面文字…");
        JButton readLayout = new JButton("提取界面文字");
        JButton recognize = new JButton("识别画面 / 框选区域");
        JButton reset = new JButton("清除框选");
        JButton copySelected = new JButton("复制选中");
        JButton copyAll = new JButton("复制全部");
        JButton close = new JButton("关闭");
        copySelected.setEnabled(false);
        copyAll.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JButton button : new JButton[]{readLayout, recognize, reset, copySelected, copyAll, close}) {
            button.setFocusable(false);
            buttons.add(button);
        }
        JPanel footer = new JPanel(new BorderLayout(6, 8));
        footer.add(message, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        dialog.setSize(Math.min(980, screen.width - 80), Math.min(760, screen.height - 100));
        dialog.setLocationRelativeTo(owner);
        split.setDividerLocation(0.42);
        reset.addActionListener(event -> picture.clear());
        close.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Runnable updateCopy = () -> {
            String value = text.getText();
            copyAll.setEnabled(!value.isBlank());
            // Document removal fires before the caret has moved into the new document.
            int start = text.getSelectionStart(), end = text.getSelectionEnd();
            copySelected.setEnabled(start >= 0 && start < end && end <= value.length()
                && !value.substring(start, end).isBlank());
        };
        text.addCaretListener(event -> updateCopy.run());
        text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { updateCopy.run(); }
            public void removeUpdate(DocumentEvent event) { updateCopy.run(); }
            public void changedUpdate(DocumentEvent event) { updateCopy.run(); }
        });
        copyAll.addActionListener(event -> copy(text.getText(), message));
        copySelected.addActionListener(event -> copy(text.getSelectedText(), message));
        final SwingWorker<?, ?>[] pending = {null};
        java.util.function.BiConsumer<Boolean, Boolean> load = (fromLayout, fallback) -> {
            BufferedImage input = picture.selectedImage();
            readLayout.setEnabled(false);
            recognize.setEnabled(false);
            message.setText(fromLayout ? "正在读取手机界面文字…" : "正在识别画面文字…");
            SwingWorker<String, Void> worker = new SwingWorker<>() {
                boolean usedRecognition = !fromLayout;
                protected String doInBackground() throws Exception {
                    if (fromLayout) {
                        try {
                            String result = layoutText(layout.get());
                            if (!result.isBlank() || !fallback) return result;
                        } catch (Exception error) {
                            if (!fallback) throw error;
                        }
                        if (isCancelled()) return "";
                        usedRecognition = true;
                        return recognize(frozen);
                    }
                    return recognize(input);
                }
                protected void done() {
                    if (!dialog.isDisplayable() || isCancelled()) return;
                    readLayout.setEnabled(true);
                    recognize.setEnabled(true);
                    try {
                        String result = get();
                        text.setText(result);
                        text.setCaretPosition(0);
                        message.setText(result.isBlank() ? "没有找到文字，可框选其他区域再试"
                            : usedRecognition ? "识别完成，请检查文字后复制" : "已提取界面文字；图片里的文字可框选识别");
                    } catch (Exception error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        message.setText("读取失败：" + cause.getMessage());
                    }
                }
            };
            pending[0] = worker;
            worker.execute();
        };
        readLayout.addActionListener(event -> load.accept(true, false));
        recognize.addActionListener(event -> load.accept(false, false));
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                if (pending[0] != null) pending[0].cancel(true);
            }
        });
        dialog.setVisible(true);
        load.accept(true, true);
    }

    private static void copy(String text, JLabel message) {
        if (text == null || text.isBlank()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            message.setText("已复制到 Mac 剪贴板");
        } catch (IllegalStateException error) {
            message.setText("剪贴板暂不可用，请重试");
        }
    }

    static final class SelectionPanel extends JPanel {
        private final BufferedImage image;
        private Point anchor;
        private Rectangle selection;
        SelectionPanel(BufferedImage image) {
            this.image = image;
            setBackground(new Color(0xE9EDF2));
            setPreferredSize(new Dimension(340, 540));
            setMinimumSize(new Dimension(160, 160));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            getAccessibleContext().setAccessibleName("画面框选区域；也可直接识别整屏");
            MouseAdapter mouse = new MouseAdapter() {
                public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || !imageBounds().contains(event.getPoint())) return;
                    anchor = toImage(event.getPoint());
                    selection = null;
                    repaint();
                }
                public void mouseDragged(MouseEvent event) {
                    if (anchor == null) return;
                    Point point = toImage(event.getPoint());
                    selection = new Rectangle(Math.min(anchor.x, point.x), Math.min(anchor.y, point.y),
                        Math.abs(point.x - anchor.x), Math.abs(point.y - anchor.y));
                    repaint();
                }
                public void mouseReleased(MouseEvent event) {
                    anchor = null;
                    if (selection != null && (selection.width < 3 || selection.height < 3)) selection = null;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }
        void clear() { anchor = null; selection = null; repaint(); }
        BufferedImage selectedImage() {
            return selection == null || selection.width < 3 || selection.height < 3 ? image
                : image.getSubimage(selection.x, selection.y, selection.width, selection.height);
        }
        Rectangle imageBounds() {
            double scale = Math.min((double) Math.max(1, getWidth()) / image.getWidth(),
                                    (double) Math.max(1, getHeight()) / image.getHeight());
            int width = Math.max(1, (int) (image.getWidth() * scale));
            int height = Math.max(1, (int) (image.getHeight() * scale));
            return new Rectangle((getWidth() - width) / 2, (getHeight() - height) / 2, width, height);
        }
        private Point toImage(Point point) {
            Rectangle bounds = imageBounds();
            int x = (int) ((long) (point.x - bounds.x) * image.getWidth() / bounds.width);
            int y = (int) ((long) (point.y - bounds.y) * image.getHeight() / bounds.height);
            return new Point(Math.max(0, Math.min(image.getWidth(), x)),
                             Math.max(0, Math.min(image.getHeight(), y)));
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            Rectangle bounds = imageBounds();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
            if (selection != null) {
                int x = bounds.x + selection.x * bounds.width / image.getWidth();
                int y = bounds.y + selection.y * bounds.height / image.getHeight();
                int width = selection.width * bounds.width / image.getWidth();
                int height = selection.height * bounds.height / image.getHeight();
                g.setColor(new Color(66, 103, 213, 55));
                g.fillRect(x, y, width, height);
                g.setColor(new Color(0x4267D5));
                g.setStroke(new BasicStroke(2));
                g.drawRect(x, y, width, height);
            }
            g.dispose();
        }
    }
}

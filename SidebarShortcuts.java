import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;

/** Local, configurable shortcuts scoped to the cast window, ahead of phone input. */
final class SidebarShortcuts implements AutoCloseable {
    private final JFrame window;
    private final Path path;
    private final Map<String, JButton> actions = new LinkedHashMap<>();
    private final Map<String, KeyStroke> bindings = new LinkedHashMap<>();
    private final Set<Integer> held = new HashSet<>();
    private final KeyEventDispatcher dispatcher = this::dispatch;
    private JDialog dialog;

    SidebarShortcuts(JFrame window, Path path) {
        this.window = window;
        this.path = path;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    static Path configPath() {
        String configured = System.getenv("CAST_STATE_DIR");
        Path directory = configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), "Library", "Application Support", "HarmonyCast")
            : Path.of(configured).toAbsolutePath();
        return directory.resolve("keyboard-shortcuts.properties");
    }

    void register(String id, JButton button) { actions.put(id, button); }

    private String actionTitle(String id) {
        if (id.equals("pin")) return "窗口置顶 / 取消";
        return id.equals("toggleSidebar") ? "收起 / 展开菜单" : actions.get(id).getText();
    }

    void load() {
        Properties saved = new Properties();
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path)) {
            saved.load(reader);
            for (String id : actions.keySet()) {
                KeyStroke key = KeyStroke.getKeyStroke(saved.getProperty(id, ""));
                if (valid(key) && !bindings.containsValue(key)) bindings.put(id, key);
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("读取快捷键设置失败: " + error.getClass().getSimpleName());
        }
    }

    static boolean valid(KeyStroke key) {
        if (key == null || key.isOnKeyRelease() || key.getKeyCode() == KeyEvent.VK_UNDEFINED) return false;
        int code = key.getKeyCode(), modifiers = key.getModifiers();
        if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_ESCAPE) return false;
        if ((modifiers & (InputEvent.META_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0)
            return false;
        // Preserve clipboard/editing commands, app/window commands and system navigation.
        if ((modifiers & (InputEvent.META_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)) != 0
                && Set.of(KeyEvent.VK_A, KeyEvent.VK_C, KeyEvent.VK_X, KeyEvent.VK_V,
                    KeyEvent.VK_Z, KeyEvent.VK_Y, KeyEvent.VK_Q, KeyEvent.VK_W,
                    KeyEvent.VK_H, KeyEvent.VK_M, KeyEvent.VK_COMMA).contains(code)) return false;
        return code != KeyEvent.VK_TAB && code != KeyEvent.VK_SPACE;
    }

    static String display(KeyStroke key) {
        if (key == null) return "未设置";
        int mods = key.getModifiers();
        return ((mods & InputEvent.CTRL_DOWN_MASK) != 0 ? "⌃" : "")
            + ((mods & InputEvent.ALT_DOWN_MASK) != 0 ? "⌥" : "")
            + ((mods & InputEvent.SHIFT_DOWN_MASK) != 0 ? "⇧" : "")
            + ((mods & InputEvent.META_DOWN_MASK) != 0 ? "⌘" : "")
            + KeyEvent.getKeyText(key.getKeyCode());
    }

    boolean dispatch(KeyEvent event) {
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() != window) {
            held.clear();
            return false;
        }
        int code = event.getKeyCode();
        if (event.getID() == KeyEvent.KEY_RELEASED && held.remove(code)) {
            event.consume();
            return true;
        }
        if (event.getID() == KeyEvent.KEY_TYPED && !held.isEmpty()) {
            event.consume();
            return true;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED) return false;
        if (held.contains(code)) { event.consume(); return true; }
        KeyStroke key = KeyStroke.getKeyStrokeForEvent(event);
        for (Map.Entry<String, KeyStroke> entry : bindings.entrySet()) {
            if (!entry.getValue().equals(key)) continue;
            JButton button = actions.get(entry.getKey());
            held.add(code);
            event.consume();
            // Hidden menus remain operable; disabled phone actions stay disabled.
            if (button.isEnabled()) SwingUtilities.invokeLater(() -> {
                if (window.isDisplayable() && button.isEnabled()) button.doClick(0);
            });
            return true;
        }
        return false;
    }

    void save(Map<String, KeyStroke> draft) throws IOException {
        Set<KeyStroke> used = new HashSet<>();
        Properties saved = new Properties();
        for (Map.Entry<String, KeyStroke> entry : draft.entrySet()) {
            if (!actions.containsKey(entry.getKey()) || !valid(entry.getValue()) || !used.add(entry.getValue()))
                throw new IllegalArgumentException("快捷键无效或重复");
            saved.setProperty(entry.getKey(), entry.getValue().toString());
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "shortcuts-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { saved.store(writer, "HarmonyCast keyboard shortcuts"); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            bindings.clear();
            bindings.putAll(draft);
            held.clear();
        } finally { Files.deleteIfExists(temporary); }
    }

    void showSettings() {
        if (dialog != null && dialog.isDisplayable()) { dialog.toFront(); return; }
        Map<String, KeyStroke> draft = new LinkedHashMap<>(bindings);
        java.util.List<String> ids = new ArrayList<>(actions.keySet());
        DefaultTableModel model = new DefaultTableModel(new String[]{"菜单操作", "快捷键"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (String id : ids) model.addRow(new Object[]{actionTitle(id), display(draft.get(id))});
        JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionInterval(0, 0);
        JTextField capture = new JTextField("点击这里录入快捷键");
        capture.setEditable(false);
        capture.setToolTipText("点击此处，然后按下快捷键组合");
        capture.getAccessibleContext().setAccessibleName("录入快捷键");
        JLabel notice = new JLabel("选择操作，点击输入框后按 ⌘ / ⌃ / ⌥ 加按键。");
        KeyStroke[] pending = {null};
        JButton assign = new JButton("设置"), clear = new JButton("清除"), save = new JButton("保存"), cancel = new JButton("取消");
        assign.setEnabled(false);
        capture.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                event.consume();
                KeyStroke key = KeyStroke.getKeyStrokeForEvent(event);
                pending[0] = valid(key) ? key : null;
                assign.setEnabled(pending[0] != null);
                capture.setText(pending[0] == null ? "" : display(key));
                notice.setText(pending[0] == null ? "请使用 ⌘ / ⌃ / ⌥ 加按键；编辑和系统常用组合保留。" : "点击「设置」绑定到选中的菜单操作。");
            }
            @Override public void keyTyped(KeyEvent event) { event.consume(); }
            @Override public void keyReleased(KeyEvent event) { event.consume(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            pending[0] = null;
            capture.setText("点击这里录入快捷键");
            assign.setEnabled(false);
        });
        assign.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0 || pending[0] == null) return;
            String id = ids.get(row);
            for (String other : ids) {
                if (!other.equals(id) && pending[0].equals(draft.get(other))) {
                    notice.setText("已用于「" + actionTitle(other) + "」，请更换或先清除原绑定。");
                    return;
                }
            }
            draft.put(id, pending[0]);
            model.setValueAt(display(pending[0]), row, 1);
            notice.setText("已设置，点击「保存」后生效。");
        });
        clear.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) { draft.remove(ids.get(row)); model.setValueAt("未设置", row, 1); }
            notice.setText("已清除，点击「保存」后生效。");
        });
        dialog = new JDialog(window, "快捷键设置", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        JPanel entry = new JPanel(new BorderLayout(8, 0));
        entry.add(capture, BorderLayout.CENTER);
        JPanel changes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        changes.add(assign); changes.add(clear);
        entry.add(changes, BorderLayout.EAST);
        bottom.add(entry, BorderLayout.NORTH);
        bottom.add(notice, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(cancel); controls.add(save);
        bottom.add(controls, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setSize(620, 600);
        dialog.setLocationRelativeTo(window);
        save.addActionListener(event -> {
            try { save(draft); dialog.dispose(); }
            catch (IOException | IllegalArgumentException error) { notice.setText("保存失败，请检查配置目录是否可写后重试。"); }
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { held.clear(); }
        });
        dialog.setVisible(true);
    }

    @Override public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        held.clear();
        if (dialog != null) dialog.dispose();
    }
}

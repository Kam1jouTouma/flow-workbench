package course.workflow;

import java.awt.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;

/**
 * 流程库浏览器；“打开”切换当前流程，“加入当前画布”复用编辑器的导入逻辑。
 *
 * <p>Library browser separates replacing the current flow from importing nodes into its canvas.
 */
@SuppressWarnings("serial")
final class FlowLibraryPanel extends JPanel {
    final WorkflowApp.Editor editor;
    final FlowLibrary library;
    final JLabel location = new JLabel(), message = new JLabel();
    final JTextField search = new JTextField();
    final DefaultTableModel model =
            new DefaultTableModel(new String[] {"图名称", "类别", "节点数", "修改时间", "文件名"}, 0) {
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
    final JTable table = new JTable(model);
    final java.util.List<Path> shown = new ArrayList<>();
    final JButton open = new JButton("打开"), insert = new JButton("加入当前画布");
    JDialog dialog;

    FlowLibraryPanel(WorkflowApp.Editor editor) {
        super(new BorderLayout(0, 12));
        this.editor = editor;
        library = new FlowLibrary(editor.folderSettings);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setPreferredSize(new Dimension(890, 520));
        JPanel top = new JPanel(new BorderLayout(8, 8));
        JLabel heading = new JLabel("我的流程库");
        heading.setFont(WorkflowApp.BODY.deriveFont(Font.BOLD, 20));
        top.add(heading, BorderLayout.NORTH);
        JPanel path = new JPanel(new BorderLayout(10, 6));
        path.add(location, BorderLayout.CENTER);
        JButton folder = new JButton("更改文件夹");
        folder.addActionListener(e -> changeFolder());
        path.add(folder, BorderLayout.EAST);
        top.add(path, BorderLayout.CENTER);
        JPanel lookup = new JPanel(new BorderLayout(8, 0));
        lookup.add(new JLabel("搜索图"), BorderLayout.WEST);
        lookup.add(search);
        JButton refresh = new JButton("刷新");
        refresh.addActionListener(e -> reload());
        lookup.add(refresh, BorderLayout.EAST);
        top.add(lookup, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table.setFont(WorkflowApp.BODY);
        table.setRowHeight(34);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(message, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton external = new JButton("导入到流程库");
        external.addActionListener(
                e -> {
                    editor.importToLibrary();
                    reload();
                });
        buttons.add(external);
        buttons.add(insert);
        buttons.add(open);
        JButton cancel = new JButton("关闭");
        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(cancel);
        footer.add(buttons, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        open.addActionListener(e -> use(false));
        insert.addActionListener(e -> use(true));
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            boolean enabled = table.getSelectedRow() >= 0;
                            open.setEnabled(enabled);
                            insert.setEnabled(enabled);
                        });
        table.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (e.getClickCount() == 2 && e.getButton() == 1) use(false);
                    }
                });
        search.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                reload();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                reload();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                reload();
                            }
                        });
        reload();
    }

    /** 重建筛选后的显示列表，并保留每行对应的真实路径。 Rebuilds rows with their backing file paths. */
    void reload() {
        shown.clear();
        model.setRowCount(0);
        open.setEnabled(false);
        insert.setEnabled(false);
        try {
            Path dir = library.directory();
            location.setText(dir.toString());
            location.setToolTipText(dir.toString());
            String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
            for (Path file : library.files()) {
                String name = file.getFileName().toString(), kind = "无法读取", count = "—";
                try {
                    var flow = WorkflowApp.Flow.load(file);
                    name = flow.title;
                    count = String.valueOf(flow.nodes.size());
                    kind =
                            flow.nodes.stream().anyMatch(n -> Subgraphs.boundary(n.kind))
                                    ? "子图"
                                    : "流程";
                } catch (java.io.IOException ignored) {
                }
                if (!query.isBlank()
                        && !(name + " " + file.getFileName())
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains(query)) continue;
                String modified =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .format(
                                        Files.getLastModifiedTime(file)
                                                .toInstant()
                                                .atZone(ZoneId.systemDefault()));
                shown.add(file);
                model.addRow(
                        new Object[] {name, kind, count, modified, file.getFileName().toString()});
            }
            message.setText(
                    shown.isEmpty()
                            ? "流程库暂无匹配的图。保存新流程或从其他文件夹导入即可加入。"
                            : "共 " + shown.size() + " 个图 · 双击打开，或选中后加入当前画布");
            if (!shown.isEmpty()) table.setRowSelectionInterval(0, 0);
        } catch (Exception ex) {
            message.setText("读取流程库失败：" + ex.getMessage());
        }
    }

    void changeFolder() {
        SystemSettingsPanel.show(editor);
        reload();
    }

    /** 明确分派打开或追加，避免把两种文件操作混在列表逻辑中。 Dispatches open versus append. */
    void use(boolean append) {
        int index = table.getSelectedRow();
        if (index < 0) return;
        Path path = shown.get(index);
        try {
            if (append) {
                editor.importFlowFile(path);
                dialog.dispose();
            } else if (editor.openFlowFile(path)) dialog.dispose();
        } catch (Exception ex) {
            editor.error(append ? "导入失败" : "打开失败", ex);
        }
    }

    static void show(WorkflowApp.Editor editor) {
        var panel = new FlowLibraryPanel(editor);
        JDialog dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(editor),
                        "我的流程库",
                        Dialog.ModalityType.APPLICATION_MODAL);
        panel.dialog = dialog;
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(editor);
        dialog.setVisible(true);
    }
}

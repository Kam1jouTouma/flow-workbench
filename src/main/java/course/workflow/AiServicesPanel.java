package course.workflow;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * AI 服务列表的编辑副本；父级设置界面保存后才写盘，表格只显示 Key 是否已保护。
 *
 * <p>Edits a local service list; the parent persists it, and the table shows only key protection
 * status.
 */
@SuppressWarnings("serial")
final class AiServicesPanel extends JPanel {
    final List<AiServices.Service> services = new ArrayList<>();
    final DefaultTableModel model =
            new DefaultTableModel(new String[] {"服务名称", "完整 URL", "Model", "Key"}, 0) {
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
    final JTable table = new JTable(model);

    AiServicesPanel(List<AiServices.Service> initial) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setBackground(Color.WHITE);
        services.addAll(initial);
        table.setFont(WorkflowApp.BODY);
        table.setRowHeight(32);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 9));
        footer.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        JButton add = new JButton("添加服务"), edit = new JButton("编辑"), delete = new JButton("删除");
        actions.add(add);
        actions.add(edit);
        actions.add(delete);
        footer.add(actions, BorderLayout.NORTH);
        JLabel note =
                new JLabel(
                        "<html>URL按填写内容完整保留；推荐HTTPS。Key隐藏并绑定当前Windows账户加密。<br>更换电脑或账户后需重新填写Key；列表修改在点击“保存设置”后生效。</html>");
        note.setFont(WorkflowApp.BODY);
        note.setForeground(WorkflowApp.MUTED);
        footer.add(note, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        add.addActionListener(e -> edit(-1));
        edit.addActionListener(
                e -> {
                    int row = table.getSelectedRow();
                    if (row >= 0) edit(row);
                });
        delete.addActionListener(
                e -> {
                    int row = table.getSelectedRow();
                    if (row >= 0
                            && JOptionPane.showConfirmDialog(
                                            this,
                                            "删除选中的AI服务？",
                                            "删除服务",
                                            JOptionPane.OK_CANCEL_OPTION)
                                    == JOptionPane.OK_OPTION) {
                        services.remove(row);
                        reload();
                    }
                });
        table.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (e.getClickCount() == 2
                                && e.getButton() == 1
                                && table.getSelectedRow() >= 0) edit(table.getSelectedRow());
                    }
                });
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            boolean selected = table.getSelectedRow() >= 0;
                            edit.setEnabled(selected);
                            delete.setEnabled(selected);
                        });
        edit.setEnabled(false);
        delete.setEnabled(false);
        reload();
    }

    void reload() {
        model.setRowCount(0);
        for (var s : services)
            model.addRow(new Object[] {s.name(), s.url(), s.model(), s.hasKey() ? "已保护" : "未配置"});
    }

    /**
     * 空白密码保留旧密文，清除选项显式移除 Key；对话框结束后清空密码控件。
     *
     * <p>A blank password preserves ciphertext; explicit clearing removes it, and the field is
     * cleared.
     */
    void edit(int index) {
        var old = index < 0 ? null : services.get(index);
        JTextField name = new JTextField(old == null ? "" : old.name()),
                url = new JTextField(old == null ? "" : old.url()),
                modelName = new JTextField(old == null ? "" : old.model());
        JPasswordField key = new JPasswordField();
        key.setEchoChar('●');
        JCheckBox clear = new JCheckBox("清除已保存的Key");
        clear.setEnabled(old != null && old.hasKey());
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.setPreferredSize(new Dimension(510, 330));
        form.add(new JLabel("服务名称"));
        form.add(name);
        form.add(new JLabel("完整 URL（包含请求路径，不自动补全）"));
        form.add(url);
        form.add(new JLabel("Model"));
        form.add(modelName);
        form.add(new JLabel(old != null && old.hasKey() ? "新Key（留空保持现有Key）" : "Key（本地服务可留空）"));
        form.add(key);
        form.add(clear);
        try {
            while (JOptionPane.showConfirmDialog(
                            this,
                            form,
                            index < 0 ? "添加AI服务" : "编辑AI服务",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.PLAIN_MESSAGE)
                    == JOptionPane.OK_OPTION) {
                char[] secret = key.getPassword();
                try {
                    String protectedKey =
                            old == null || clear.isSelected() ? "" : old.protectedKey();
                    var draft =
                            new AiServices.Service(
                                    old == null ? UUID.randomUUID().toString() : old.id(),
                                    name.getText().strip(),
                                    url.getText().strip(),
                                    modelName.getText().strip(),
                                    protectedKey);
                    AiServices.validate(draft);
                    if (secret.length > 0) protectedKey = AiServices.protect(secret);
                    var saved =
                            new AiServices.Service(
                                    draft.id(),
                                    draft.name(),
                                    draft.url(),
                                    draft.model(),
                                    protectedKey);
                    if (index < 0) services.add(saved);
                    else services.set(index, saved);
                    reload();
                    table.setRowSelectionInterval(
                            index < 0 ? services.size() - 1 : index,
                            index < 0 ? services.size() - 1 : index);
                    return;
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(
                            this, ex.getMessage(), "配置未保存", JOptionPane.ERROR_MESSAGE);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }
        } finally {
            key.setText("");
        }
    }
}

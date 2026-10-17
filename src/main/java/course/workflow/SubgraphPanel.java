package course.workflow;

import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;
import javax.swing.event.*;

/**
 * 内置虚拟端口与子图实例的侧边栏；虚拟节点描述边界，不启动外部脚本。
 *
 * <p>Inspects built-in boundary ports and module instances; virtual nodes do not launch scripts.
 */
@SuppressWarnings("serial")
final class SubgraphPanel extends JPanel {
    final WorkflowApp.Editor editor;
    final CardLayout cards = new CardLayout();
    final JPanel boundary = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            module = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final Map<String, JComponent> controls = new LinkedHashMap<>();
    final JTextArea interfaces = WorkflowApp.text("", 245);
    final JCheckBox required = new JCheckBox("父图必须提供此输入");
    final JTextField fallback = new JTextField();

    SubgraphPanel(WorkflowApp.Editor editor) {
        this.editor = editor;
        setLayout(cards);
        setBackground(WorkflowApp.CONFIG_BG);
        field("interfaceName", "父图端口名称", new JTextField());
        choice("dataType", "数据类型", new String[] {"FILE", "TEXT", "JSON", "ANY"});
        choice("cardinality", "数量", new String[] {"COLLECTION", "SINGLE"});
        field("formats", "文件格式（*为任意，或pdf,csv等）", new JTextField());
        required.setBackground(WorkflowApp.CONFIG_BG);
        required.setFont(WorkflowApp.BODY);
        required.addActionListener(e -> store("required", required.isSelected()));
        boundary.add(required);
        field("defaultValue", "默认值（FILE填绝对路径，JSON填JSON）", fallback);
        boundary.add(WorkflowApp.text("内置边界节点，不调用插件。\n名称决定父图的对应端口。\n可选输入没有数据时，仅可接可选下游。", 245));
        for (var item :
                java.util.List.of(
                        WorkflowApp.button("编辑内部步骤", true, editor::editSubgraph),
                        WorkflowApp.button("从文件载入子图", false, () -> file(false)),
                        WorkflowApp.button("导出子图定义", false, () -> file(true)))) {
            item.setAlignmentX(LEFT_ALIGNMENT);
            module.add(item);
            module.add(Box.createVerticalStrut(12));
        }
        module.add(interfaces);
        module.add(
                WorkflowApp.text(
                        "接口版本1，最多嵌套8层。\n从虚拟输入到虚拟输出形成闭合处理步骤。\n每个实例独立编辑和运行。\n修改端口名称后须重新连接父图。", 245));
        add(boundary, "boundary");
        add(module, "module");
    }

    /**
     * 导入和导出的是独立定义快照；校验接口后载入，不与原文件保持实时绑定。
     *
     * <p>Imports or exports standalone definition snapshots after interface checks, without live
     * linkage.
     */
    void file(boolean export) {
        var node = editor.selected;
        if (node == null || node.kind != WorkflowApp.Kind.SUBGRAPH) return;
        JFileChooser chooser = editor.chooser();
        try {
            if (export) {
                chooser.setSelectedFile(new java.io.File("subgraph.flow.xml"));
                if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                Path path = chooser.getSelectedFile().toPath();
                if (Files.exists(path)
                        && JOptionPane.showConfirmDialog(
                                        this, "文件已存在，是否覆盖？", "导出子图", JOptionPane.YES_NO_OPTION)
                                != JOptionPane.YES_OPTION) return;
                Subgraphs.validateInterfaces(node.child);
                node.child.save(path);
                editor.log("已导出子图：" + path);
            } else {
                if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                var loaded = WorkflowApp.Flow.load(chooser.getSelectedFile().toPath());
                Subgraphs.validateInterfaces(loaded);
                node.child = Subgraphs.copy(loaded);
                node.settings.put("originFile", chooser.getSelectedFile().getAbsolutePath());
                editor.changed();
                editor.select(node);
                editor.log("载入子图快照；实例独立，不会自动修改原文件。");
            }
        } catch (IOException | IllegalArgumentException ex) {
            editor.error("子图文件操作失败", ex);
        }
    }

    void load(WorkflowApp.Node n) {
        if (n.kind == WorkflowApp.Kind.SUBGRAPH) {
            StringBuilder text = new StringBuilder("父图输入：\n");
            for (var p : n.inputPorts())
                text.append(p.name())
                        .append(" · ")
                        .append(p.type())
                        .append(" · ")
                        .append(p.required() ? "必需" : "可选")
                        .append('\n');
            text.append("\n父图输出：\n");
            for (var p : n.outputPorts())
                text.append(p.name()).append(" · ").append(p.type()).append('\n');
            interfaces.setText(text.toString());
            cards.show(this, "module");
            setPreferredSize(module.getPreferredSize());
        } else {
            for (var entry : controls.entrySet())
                if (entry.getValue() instanceof JTextField field)
                    field.setText((String) n.settings.get(entry.getKey()));
                else if (entry.getValue() instanceof JComboBox<?> combo)
                    combo.setSelectedItem(n.settings.get(entry.getKey()));
            required.setSelected((Boolean) n.settings.get("required"));
            required.setVisible(n.kind == WorkflowApp.Kind.VIRTUAL_IN);
            fallback.getParent().setVisible(n.kind == WorkflowApp.Kind.VIRTUAL_IN);
            cards.show(this, "boundary");
            setPreferredSize(boundary.getPreferredSize());
        }
    }

    /** 虚拟接口更改后刷新父图端口描述，已有连线名称不会自动改写。 Refreshes interface descriptions. */
    void store(String key, Object value) {
        if (editor.updating || editor.selected == null || !Subgraphs.boundary(editor.selected.kind))
            return;
        editor.selected.settings.put(key, value);
        editor.updatePortLabel();
        editor.changed();
    }

    void field(String key, String title, JTextField field) {
        field.setFont(WorkflowApp.BODY);
        controls.put(key, field);
        field.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            void edit() {
                                store(key, field.getText());
                            }

                            public void insertUpdate(DocumentEvent e) {
                                edit();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                edit();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                edit();
                            }
                        });
        block(title, field);
    }

    void choice(String key, String title, String[] values) {
        JComboBox<String> choice = new JComboBox<>(values);
        choice.setFont(WorkflowApp.BODY);
        choice.addActionListener(e -> store(key, choice.getSelectedItem()));
        controls.put(key, choice);
        block(title, choice);
    }

    void block(String title, JComponent control) {
        JPanel row = WorkflowApp.column(WorkflowApp.CONFIG_BG);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(WorkflowApp.label(title, 12, WorkflowApp.MUTED, true));
        row.add(Box.createVerticalStrut(7));
        control.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        control.setAlignmentX(LEFT_ALIGNMENT);
        row.add(control);
        row.add(Box.createVerticalStrut(16));
        boundary.add(row);
    }
}

package course.workflow;

import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * 文件输入与输出的侧边栏控件；修改写入选中节点，文件处理由执行层完成。
 *
 * <p>Typed input/output inspectors update the selected node while execution remains in the runner.
 */
@SuppressWarnings("serial")
final class IoConfigPanel extends JPanel {
    final WorkflowApp.Editor editor;
    final Map<String, JComponent> inputControls = new LinkedHashMap<>(),
            outputControls = new LinkedHashMap<>();
    final CardLayout cards = new CardLayout();
    final JPanel input = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            output = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final JPanel textSection = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            jsonSection = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            csvSection = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    JPanel inputEncoding, outputEncoding, outputCsv, outputJson;
    JPanel extensionSection, recursionSection;
    JPanel supportedSection;

    IoConfigPanel(WorkflowApp.Editor editor) {
        this.editor = editor;
        setLayout(cards);
        setBackground(WorkflowApp.CONFIG_BG);
        choice(
                input,
                inputControls,
                "selectionMode",
                "选取方式",
                new String[] {"SINGLE", "TYPES", "SUPPORTED"},
                new String[] {"指定单个文件", "目录中指定类型", "目录中所有支持类型"});
        path(input, inputControls, "sourcePath", "源文件 / 目录", false);
        extensionSection = WorkflowApp.column(WorkflowApp.CONFIG_BG);
        field(extensionSection, inputControls, "extensions", "指定后缀（逗号分隔，如 txt,csv）");
        input.add(extensionSection);
        recursionSection = toggle(input, inputControls, "recursive", "包含子目录（保留目录结构）");
        supportedSection = WorkflowApp.column(WorkflowApp.CONFIG_BG);
        supportedSection.add(
                WorkflowApp.text(
                        "办公：Word、PDF、Excel、PowerPoint\n开放文档：ODT、ODS、ODP\n另含文本、数据和常见图片\n完整后缀："
                                + String.join(", ", FileCli.SUPPORTED),
                        245));
        input.add(supportedSection);
        choice(
                input,
                inputControls,
                "mode",
                "读取模式",
                new String[] {"TEXT", "JSON", "CSV", "FILE"},
                new String[] {"文本", "JSON 结构", "CSV 表格", "保留原文件"});
        inputEncoding =
                choice(
                        input,
                        inputControls,
                        "encoding",
                        "源文件编码",
                        new String[] {"UTF-8", "UTF-8-SIG", "GB18030"},
                        new String[] {"UTF-8", "UTF-8（含 BOM）", "GB18030"});
        number(textSection, inputControls, "startLine", "起始行", 1);
        number(textSection, inputControls, "endLine", "截止行（0 表示到末尾）", 0);
        field(jsonSection, inputControls, "jsonPointer", "JSON 值位置（空表示全部）");
        number(csvSection, inputControls, "headerRow", "表头记录号", 1);
        choice(
                csvSection,
                inputControls,
                "delimiter",
                "CSV 分隔符",
                new String[] {",", ";", "\t"},
                new String[] {"逗号", "分号", "制表符"});
        JTextArea columns = new JTextArea(3, 18);
        columns.setLineWrap(true);
        columns.setFont(WorkflowApp.BODY);
        inputControls.put("columns", columns);
        watch(columns, "columns");
        block(csvSection, "保留列名（每行一个，空为全部）", new JScrollPane(columns), 86);
        input.add(textSection);
        input.add(jsonSection);
        input.add(csvSection);
        JButton inspect = WorkflowApp.button("检测文件类型与下游兼容性", false, editor::inspectSourceTypes);
        inspect.setAlignmentX(LEFT_ALIGNMENT);
        input.add(inspect);
        input.add(Box.createVerticalStrut(12));
        input.add(WorkflowApp.text("先选文件，再选择需要的信息。\n保留原文件不会提取正文。", 245));

        choice(
                output,
                outputControls,
                "format",
                "输出形式",
                new String[] {"KEEP", "TXT", "JSON", "CSV"},
                new String[] {"保留收到的内容", "TXT 文本", "JSON 文件", "CSV 表格"});
        outputEncoding =
                choice(
                        output,
                        outputControls,
                        "encoding",
                        "输出编码",
                        new String[] {"UTF-8", "UTF-8-SIG", "GB18030"},
                        new String[] {"UTF-8", "UTF-8（含 BOM）", "GB18030"});
        outputCsv =
                choice(
                        output,
                        outputControls,
                        "delimiter",
                        "CSV 分隔符",
                        new String[] {",", ";", "\t"},
                        new String[] {"逗号", "分号", "制表符"});
        outputJson = toggle(output, outputControls, "prettyPrint", "JSON 缩进排版");
        choice(
                output,
                outputControls,
                "operation",
                "原文件处理",
                new String[] {"COPY", "MOVE"},
                new String[] {"保留原文件", "全部输出成功后删除原文件"});
        choice(
                output,
                outputControls,
                "location",
                "结果位置",
                new String[] {"DIRECTORY", "SOURCE"},
                new String[] {"指定保存目录", "各自原文件所在目录"});
        toggle(output, outputControls, "preserveNames", "保留原文件名（批量始终保留）");
        path(output, outputControls, "directory", "保存目录", true);
        field(output, outputControls, "fileName", "文件名（含后缀）");
        choice(
                output,
                outputControls,
                "conflictPolicy",
                "已有同名文件时",
                new String[] {"ERROR", "RENAME", "OVERWRITE"},
                new String[] {"报错并保留原文件", "自动改名", "覆盖"});
        toggle(output, outputControls, "createDirectories", "允许创建不存在的目录");
        toggle(output, outputControls, "openFolderAfterSuccess", "完成后打开目标文件夹");
        output.add(WorkflowApp.text("PDF/改名的结果名称由工作元素生成。\nCSV规则文件始终保留。\n原文件删除前会验证所有输出与来源。", 245));
        add(input, "input");
        add(output, "output");
    }

    /** 由编辑器在 updating 保护期间填充控件，避免触发回写。 Populate controls under editor.updating. */
    void load(WorkflowApp.Node node) {
        Map<String, JComponent> controls =
                node.kind == WorkflowApp.Kind.FILE ? inputControls : outputControls;
        for (Map.Entry<String, JComponent> entry : controls.entrySet()) {
            Object value = node.settings.get(entry.getKey());
            JComponent control = entry.getValue();
            if (control instanceof JTextComponent text) text.setText(value.toString());
            else if (control instanceof JComboBox<?> combo) combo.setSelectedItem(value);
            else if (control instanceof JSpinner spinner) spinner.setValue(value);
            else if (control instanceof JCheckBox check) check.setSelected((Boolean) value);
        }
        cards.show(this, node.kind == WorkflowApp.Kind.FILE ? "input" : "output");
        sections(node);
    }

    /** 区分用户输入和程序刷新；批量选择同步切回 FILE 模式。 Ignores programmatic refresh events. */
    void store(String key, Object value) {
        if (editor.updating || editor.selected == null) return;
        WorkflowApp.Node node = editor.selected;
        if (node.kind != WorkflowApp.Kind.FILE && node.kind != WorkflowApp.Kind.SAVE) return;
        if (Objects.equals(node.settings.get(key), value)) return;
        node.settings.put(key, value);
        sections(node);
        editor.updatePortLabel();
        editor.changed();
        if (key.equals("selectionMode") && !value.equals("SINGLE"))
            ((JComboBox<?>) inputControls.get("mode")).setSelectedItem("FILE");
    }

    /** 按当前读取/输出模式显示相关配置区域。 Shows only sections relevant to the selected mode. */
    void sections(WorkflowApp.Node node) {
        if (node.kind == WorkflowApp.Kind.FILE) {
            String mode = (String) node.settings.get("mode");
            textSection.setVisible(mode.equals("TEXT"));
            jsonSection.setVisible(mode.equals("JSON"));
            csvSection.setVisible(mode.equals("CSV"));
            inputEncoding.setVisible(mode.equals("TEXT") || mode.equals("CSV"));
            extensionSection.setVisible(node.settings.get("selectionMode").equals("TYPES"));
            recursionSection.setVisible(!node.settings.get("selectionMode").equals("SINGLE"));
            supportedSection.setVisible(node.settings.get("selectionMode").equals("SUPPORTED"));
        } else {
            String format = (String) node.settings.get("format");
            outputEncoding.setVisible(format.equals("TXT") || format.equals("CSV"));
            outputCsv.setVisible(format.equals("CSV"));
            outputJson.setVisible(format.equals("JSON"));
            outputControls
                    .get("directory")
                    .getParent()
                    .getParent()
                    .setVisible(node.settings.get("location").equals("DIRECTORY"));
            boolean derived =
                    editor.flow.nodes.stream()
                            .anyMatch(
                                    n ->
                                            ExecutableTools.WORK.contains(n.kind)
                                                    || n.kind == WorkflowApp.Kind.SUBGRAPH);
            outputControls.get("fileName").getParent().setVisible(!derived);
            outputControls.get("preserveNames").getParent().setVisible(!derived);
        }
        JPanel shown = node.kind == WorkflowApp.Kind.FILE ? input : output;
        setPreferredSize(shown.getPreferredSize());
        editor.parameterBody.setPreferredSize(shown.getPreferredSize());
        revalidate();
        editor.parameterBody.revalidate();
    }

    void watch(JTextComponent component, String key) {
        component
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            void edit() {
                                store(key, component.getText());
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
    }

    void field(JPanel parent, Map<String, JComponent> controls, String key, String title) {
        JTextField field = new JTextField();
        field.setFont(WorkflowApp.BODY);
        controls.put(key, field);
        watch(field, key);
        block(parent, title, field, 35);
    }

    void path(
            JPanel parent,
            Map<String, JComponent> controls,
            String key,
            String title,
            boolean directory) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setBackground(WorkflowApp.CONFIG_BG);
        JTextField field = new JTextField();
        field.setFont(WorkflowApp.BODY);
        controls.put(key, field);
        watch(field, key);
        row.add(field, BorderLayout.CENTER);
        JButton browse =
                WorkflowApp.button(
                        "选择",
                        false,
                        () -> {
                            JFileChooser chooser = new JFileChooser();
                            chooser.setDialogTitle(directory ? "选择保存目录" : "选择源文件");
                            boolean chooseDirectory =
                                    directory
                                            || key.equals("sourcePath")
                                                    && editor.selected != null
                                                    && !editor.selected
                                                            .settings
                                                            .get("selectionMode")
                                                            .equals("SINGLE");
                            chooser.setFileSelectionMode(
                                    chooseDirectory
                                            ? JFileChooser.DIRECTORIES_ONLY
                                            : JFileChooser.FILES_ONLY);
                            if (!field.getText().isBlank())
                                chooser.setSelectedFile(new File(field.getText()));
                            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                                field.setText(chooser.getSelectedFile().getAbsolutePath());
                        });
        row.add(browse, BorderLayout.EAST);
        block(parent, title, row, 36);
    }

    JPanel choice(
            JPanel parent,
            Map<String, JComponent> controls,
            String key,
            String title,
            String[] values,
            String[] labels) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setFont(WorkflowApp.BODY);
        combo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        super.getListCellRendererComponent(list, value, index, selected, focus);
                        int position = List.of(values).indexOf(value);
                        if (position >= 0) setText(labels[position]);
                        return this;
                    }
                });
        controls.put(key, combo);
        combo.addActionListener(e -> store(key, combo.getSelectedItem()));
        return block(parent, title, combo, 35);
    }

    void number(
            JPanel parent,
            Map<String, JComponent> controls,
            String key,
            String title,
            int minimum) {
        JSpinner spinner =
                new JSpinner(new SpinnerNumberModel(minimum, minimum, Integer.MAX_VALUE, 1));
        controls.put(key, spinner);
        spinner.addChangeListener(e -> store(key, spinner.getValue()));
        block(parent, title, spinner, 34);
    }

    JPanel toggle(JPanel parent, Map<String, JComponent> controls, String key, String title) {
        JCheckBox check = new JCheckBox(title);
        check.setBackground(WorkflowApp.CONFIG_BG);
        check.setFont(WorkflowApp.BODY);
        check.setForeground(WorkflowApp.INK);
        controls.put(key, check);
        check.addActionListener(e -> store(key, check.isSelected()));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(WorkflowApp.CONFIG_BG);
        panel.add(check);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        panel.setPreferredSize(new Dimension(250, 36));
        parent.add(panel);
        return panel;
    }

    JPanel block(JPanel parent, String title, JComponent component, int height) {
        JPanel panel = WorkflowApp.column(WorkflowApp.CONFIG_BG);
        panel.setBorder(new EmptyBorder(0, 0, 15, 0));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(WorkflowApp.label(title, 12, WorkflowApp.MUTED, true));
        panel.add(Box.createVerticalStrut(7));
        component.setAlignmentX(LEFT_ALIGNMENT);
        component.setPreferredSize(new Dimension(250, height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        panel.add(component);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height + 40));
        parent.add(panel);
        return panel;
    }
}

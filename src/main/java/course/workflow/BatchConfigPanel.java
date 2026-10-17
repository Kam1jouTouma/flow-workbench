package course.workflow;

import java.awt.*;
import java.util.*;

import javax.swing.*;
import javax.swing.event.*;

/**
 * 工作元素共享的侧边栏：按节点类型切换配置卡片，AI 节点保存服务 ID 而非密钥。
 *
 * <p>Switches inspector cards by tool kind; AI nodes store a service ID instead of credentials.
 */
@SuppressWarnings("serial")
final class BatchConfigPanel extends JPanel {
    final WorkflowApp.Editor editor;
    final CardLayout cards = new CardLayout();
    final JPanel script = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final Map<String, JComponent> scriptControls = new LinkedHashMap<>();
    final JPanel ai = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final Map<String, JComponent> aiControls = new LinkedHashMap<>();
    final JComboBox<String> services = new JComboBox<>();
    final Map<String, String> serviceNames = new LinkedHashMap<>();
    final JPanel pdf = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            rename = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final JPanel merge = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final Map<String, JComponent> mergeControls = new LinkedHashMap<>();
    final JPanel build = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            audio = WorkflowApp.column(WorkflowApp.CONFIG_BG),
            excel = WorkflowApp.column(WorkflowApp.CONFIG_BG);
    final Map<String, JComponent> pdfControls = new LinkedHashMap<>(),
            renameControls = new LinkedHashMap<>();
    final Map<String, JComponent> buildControls = new LinkedHashMap<>(),
            audioControls = new LinkedHashMap<>(),
            excelControls = new LinkedHashMap<>();

    BatchConfigPanel(WorkflowApp.Editor editor) {
        this.editor = editor;
        setLayout(cards);
        setBackground(WorkflowApp.CONFIG_BG);
        field(script, scriptControls, "scriptPath", "脚本路径（修改后请重新导入）");
        JTextArea json = new JTextArea(8, 18);
        json.setFont(WorkflowApp.BODY);
        json.setLineWrap(true);
        scriptControls.put("parametersJSON", json);
        json.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            void edit() {
                                store("parametersJSON", json.getText());
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
        block(script, "脚本参数（JSON对象）", new JScrollPane(json));
        JButton help = new JButton("脚本格式与示例");
        help.addActionListener(e -> ScriptTools.help(editor));
        script.add(help);
        script.add(WorkflowApp.text("脚本在原位置运行。依赖须保留。\n参数键和类型必须匹配工具声明。\n脚本修改后请重新导入。", 245));
        add(script, "script");
        services.setFont(WorkflowApp.BODY);
        services.setRenderer(
                new DefaultListCellRenderer() {
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        super.getListCellRendererComponent(list, value, index, selected, focus);
                        setText(
                                serviceNames.getOrDefault(
                                        value, value == null ? "请选择服务" : "服务已删除"));
                        return this;
                    }
                });
        services.addActionListener(
                e -> {
                    if (services.getSelectedItem() != null)
                        store("serviceId", services.getSelectedItem());
                });
        aiControls.put("serviceId", services);
        block(ai, "AI服务", services);
        JButton manage = new JButton("管理AI服务");
        manage.addActionListener(
                e -> {
                    SystemSettingsPanel.show(editor);
                    if (editor.selected != null && editor.selected.kind == WorkflowApp.Kind.AI) {
                        editor.updating = true;
                        try {
                            load(editor.selected);
                        } finally {
                            editor.updating = false;
                        }
                    }
                });
        ai.add(manage);
        JTextArea prompt = new JTextArea(5, 18);
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setFont(WorkflowApp.BODY);
        prompt.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            void edit() {
                                store("prompt", prompt.getText());
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
        aiControls.put("prompt", prompt);
        block(ai, "Prompt", new JScrollPane(prompt));
        for (String key : java.util.List.of("maxTokens", "timeoutSeconds")) {
            JSpinner spinner =
                    new JSpinner(
                            new SpinnerNumberModel(
                                    key.equals("maxTokens") ? 2048 : 120,
                                    1,
                                    key.equals("maxTokens") ? 1000000 : 600,
                                    1));
            aiControls.put(key, spinner);
            spinner.addChangeListener(e -> store(key, spinner.getValue()));
            block(ai, key.equals("maxTokens") ? "最大输出Token" : "最大响应时间（秒）", spinner);
        }
        field(ai, aiControls, "temperature", "Temperature（0–2，留空默认）");
        choice(
                ai,
                aiControls,
                "tokenParameter",
                "Token参数兼容",
                new String[] {"max_completion_tokens", "max_tokens"},
                new String[] {"max_completion_tokens", "max_tokens（兼容旧服务）"});
        ai.add(
                WorkflowApp.text(
                        "单个文本输入 → AI文本 → 文件输出。\nPrompt作为系统指令，输入作为用户内容。\n超时包含接收完整响应，不自动重试。\n仅支持Chat Completions兼容服务。",
                        245));
        add(ai, "ai");
        choice(
                pdf,
                pdfControls,
                "pdfMode",
                "生成方式",
                new String[] {"EACH", "MERGE"},
                new String[] {"每个文件生成对应PDF", "按默认排序合并为一个PDF"});
        choice(
                pdf,
                pdfControls,
                "encoding",
                "文本文件编码",
                new String[] {"UTF-8", "UTF-8-SIG", "GB18030"},
                new String[] {"UTF-8", "UTF-8（含BOM）", "GB18030"});
        pdf.add(
                WorkflowApp.text(
                        "排序：相对路径自然排序，数字按数值。\n单独生成：原名称.pdf。\n合并生成：合并结果.pdf。\n文件位置和原文件保留由输出决定。",
                        245));
        JTextField template = new JTextField();
        template.setFont(WorkflowApp.BODY);
        renameControls.put("template", template);
        template.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            void edit() {
                                store("template", template.getText());
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
        block(rename, "命名模板（自动保留扩展名）", template);
        choice(
                rename,
                renameControls,
                "csvEncoding",
                "可选CSV编码",
                new String[] {"UTF-8", "UTF-8-SIG", "GB18030"},
                new String[] {"UTF-8", "UTF-8（含BOM）", "GB18030"});
        choice(
                rename,
                renameControls,
                "csvDelimiter",
                "可选CSV分隔符",
                new String[] {",", ";", "\t"},
                new String[] {"逗号", "分号", "制表符"});
        rename.add(
                WorkflowApp.text(
                        "{x} → 1、2、3…\n{createtime}_photo → 2026.10.13_photo\n{姓名}_{x} → CSV姓名列 + 序号\n\nCSV数据行依次对应排序后的文件。\n字段名须与表头完全一致。缺少值留空并提示，可选“本次不再提示”。\n\n左侧上端口：待改名文件，必需。\n左侧下端口：单个CSV，可选。\n两个输入均用“保留原文件”模式。",
                        245));
        field(build, buildControls, "mainClass", "主类（如Main或包名.Main，空为库JAR）");
        choice(
                build,
                buildControls,
                "targetRelease",
                "目标Java版本",
                new String[] {"17", "21", "25"},
                new String[] {"Java 17", "Java 21", "Java 25"});
        field(build, buildControls, "jdkPath", "JDK目录（空使用界面运行的JDK）");
        build.add(
                WorkflowApp.text(
                        "BAT依次调用javac、jar。\n选择目录中的.java文件，并包含子目录。\n固定输出project.jar。\n暂不解析Maven/Gradle依赖。",
                        245));
        choice(
                audio,
                audioControls,
                "bitrate",
                "MP3音质",
                new String[] {"128", "192", "256", "320"},
                new String[] {"128 kbps", "192 kbps", "256 kbps", "320 kbps"});
        audio.add(
                WorkflowApp.text(
                        "EXE调用FFmpeg处理实际媒体。\n每个文件生成同名.mp3，选择第一条音轨。\n无音轨或损坏媒体会报错。\n可连接批量改名和文件输出。",
                        245));
        field(excel, excelControls, "sourceColumn", "新增来源文件列名");
        excel.add(
                WorkflowApp.text(
                        "Python按自然排序合并XLSX/XLSM。\n读取第一个工作表，按表头名称对齐。\n输出合并结果.xlsx。\n只保留值，不复制样式、图表或宏。\n公式需要Excel保存的计算结果。\n可继续连接PDF或批量改名。",
                        245));
        JSpinner inputs = new JSpinner(new SpinnerNumberModel(2, 2, 8, 1));
        mergeControls.put("inputCount", inputs);
        inputs.addChangeListener(
                e -> {
                    store("inputCount", inputs.getValue());
                    editor.updatePortLabel();
                });
        block(merge, "文件输入端口数量（2至8）", inputs);
        merge.add(
                WorkflowApp.text(
                        "每个端口接入一个文件或文件集合。\n按input1、input2…顺序合并，每一路保留自身排序。\n重复的相同来源/名称/内容自动去重。\n不同来源的同名文件交给输出同名策略。\n可连接PDF、改名或多个文件输出。",
                        245));
        add(merge, "merge");
        add(pdf, "pdf");
        add(rename, "rename");
        add(build, "build");
        add(audio, "audio");
        add(excel, "excel");
    }

    /**
     * 装入节点参数并刷新服务名称；已失效的服务 ID 保留供用户识别和重新选择。
     *
     * <p>Loads parameters and service labels while retaining missing service IDs for correction.
     */
    void load(WorkflowApp.Node node) {
        if (node.kind == WorkflowApp.Kind.AI) {
            serviceNames.clear();
            services.removeAllItems();
            try {
                for (var service :
                        AiServices.load(new SystemSettings(editor.folderSettings).load())) {
                    serviceNames.put(service.id(), service.name() + " · " + service.model());
                    services.addItem(service.id());
                }
            } catch (java.io.IOException ex) {
                editor.log("读取AI服务失败。");
            }
            String id = (String) node.settings.get("serviceId");
            if (!id.isBlank() && !serviceNames.containsKey(id)) services.addItem(id);
            services.setSelectedItem(id.isBlank() ? null : id);
        }
        Map<String, JComponent> controls =
                switch (node.kind) {
                    case SCRIPT -> scriptControls;
                    case AI -> aiControls;
                    case FILE_MERGE -> mergeControls;
                    case PDF -> pdfControls;
                    case RENAME -> renameControls;
                    case JAVA_BUILD -> buildControls;
                    case AUDIO -> audioControls;
                    case EXCEL_MERGE -> excelControls;
                    default -> throw new IllegalArgumentException();
                };
        for (var item : controls.entrySet()) {
            Object value = node.settings.get(item.getKey());
            if (item.getValue() instanceof JTextArea area) area.setText((String) value);
            else if (item.getValue() instanceof JTextField text) text.setText((String) value);
            else if (item.getValue() instanceof JSpinner spinner) spinner.setValue(value);
            else if (item.getValue() instanceof JComboBox<?> combo) combo.setSelectedItem(value);
        }
        JPanel shown =
                switch (node.kind) {
                    case SCRIPT -> script;
                    case AI -> ai;
                    case FILE_MERGE -> merge;
                    case PDF -> pdf;
                    case RENAME -> rename;
                    case JAVA_BUILD -> build;
                    case AUDIO -> audio;
                    case EXCEL_MERGE -> excel;
                    default -> throw new IllegalArgumentException();
                };
        cards.show(
                this,
                switch (node.kind) {
                    case SCRIPT -> "script";
                    case AI -> "ai";
                    case FILE_MERGE -> "merge";
                    case PDF -> "pdf";
                    case RENAME -> "rename";
                    case JAVA_BUILD -> "build";
                    case AUDIO -> "audio";
                    case EXCEL_MERGE -> "excel";
                    default -> "";
                });
        setPreferredSize(shown.getPreferredSize());
    }

    void field(JPanel parent, Map<String, JComponent> controls, String key, String title) {
        JTextField field = new JTextField();
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
        block(parent, title, field);
    }

    /** 忽略控件装载事件，只回写节点已有参数。 Skips refresh events and writes existing setting keys. */
    void store(String key, Object value) {
        if (editor.updating
                || editor.selected == null
                || !editor.selected.settings.containsKey(key)) return;
        editor.selected.settings.put(key, value);
        editor.changed();
    }

    void choice(
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
                        int position = Arrays.asList(values).indexOf(value);
                        if (position >= 0) setText(labels[position]);
                        return this;
                    }
                });
        controls.put(key, combo);
        combo.addActionListener(e -> store(key, combo.getSelectedItem()));
        block(parent, title, combo);
    }

    void block(JPanel parent, String title, JComponent field) {
        JPanel block = WorkflowApp.column(WorkflowApp.CONFIG_BG);
        block.setAlignmentX(LEFT_ALIGNMENT);
        block.add(WorkflowApp.label(title, 12, WorkflowApp.MUTED, true));
        block.add(Box.createVerticalStrut(7));
        int height = field instanceof JScrollPane ? 130 : 35;
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        field.setPreferredSize(new Dimension(250, height));
        field.setAlignmentX(LEFT_ALIGNMENT);
        block.add(field);
        block.add(Box.createVerticalStrut(16));
        parent.add(block);
    }
}

package course.workflow;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;

/**
 * 已发现能力的可读视图，提供名称和后缀搜索；能力声明与实际执行校验分开呈现。
 *
 * <p>A searchable view of discovered capabilities; declarations remain distinct from runtime
 * checks.
 */
@SuppressWarnings("serial")
final class ToolCatalogPanel extends JPanel {
    final List<Map<String, Object>> tools;
    final List<Map<String, Object>> visible = new ArrayList<>();
    final JTextField search = new JTextField();
    final JLabel results = new JLabel();
    final DefaultTableModel model =
            new DefaultTableModel(new String[] {"工具名称", "状态", "形式", "文件接收方式", "结果格式", "版本"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    final JTable table = new JTable(model);
    final JEditorPane details = new JEditorPane("text/html", "");
    final JTextArea protocol = new JTextArea();

    ToolCatalogPanel(List<Map<String, Object>> tools) {
        super(new BorderLayout(0, 12));
        this.tools = List.copyOf(tools);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setBackground(new Color(245, 247, 251));
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        JLabel heading = new JLabel("工具与支持格式");
        heading.setFont(WorkflowApp.BODY.deriveFont(Font.BOLD, 20));
        top.add(heading, BorderLayout.NORTH);
        JPanel lookup = new JPanel(new BorderLayout(10, 6));
        lookup.setOpaque(false);
        lookup.add(new JLabel("搜索"), BorderLayout.WEST);
        search.setFont(WorkflowApp.BODY);
        search.setToolTipText("输入工具名称或文件扩展名，例如 pdf、.docx、xlsx");
        lookup.add(search, BorderLayout.CENTER);
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> search.setText(""));
        lookup.add(clear, BorderLayout.EAST);
        lookup.add(
                new JLabel("按工具名称或文件类型查找，例如 pdf、docx、xlsx；原样传递表示复制或改名，不解析文件内容。"),
                BorderLayout.SOUTH);
        top.add(lookup, BorderLayout.CENTER);
        top.add(results, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table.setFont(WorkflowApp.BODY);
        table.setRowHeight(36);
        table.getTableHeader().setFont(WorkflowApp.BODY.deriveFont(Font.BOLD));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setSelectionBackground(new Color(221, 230, 255));
        table.setSelectionForeground(new Color(25, 36, 57));
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(210);
        table.getColumnModel().getColumn(3).setPreferredWidth(140);
        details.setEditable(false);
        protocol.setEditable(false);
        protocol.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("功能说明", new JScrollPane(details));
        tabs.addTab("技术信息", new JScrollPane(protocol));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), tabs);
        split.setResizeWeight(.48);
        split.setDividerLocation(240);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        setPreferredSize(new Dimension(960, 650));
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            if (!e.getValueIsAdjusting()) showSelection();
                        });
        search.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                filter();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                filter();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                filter();
                            }
                        });
        filter();
    }

    static String name(Map<String, Object> t) {
        return switch (String.valueOf(t.get("toolId"))) {
            case "ai.text" -> "AI 文本处理";
            case "io.file_select" -> "文件输入";
            case "io.file_export" -> "文件输出";
            case "batch.pdf" -> "转换为 PDF";
            case "batch.rename" -> "批量改名";
            case "build.java_jar" -> "Java 编译打包";
            case "media.extract_audio" -> "提取 MP3 音频";
            case "table.merge_excel" -> "合并 Excel 表格";
            default -> String.valueOf(t.get("toolId"));
        };
    }

    static String runtime(Map<String, Object> t) {
        return switch (String.valueOf(t.get("toolId"))) {
            case "build.java_jar" -> "BAT";
            case "media.extract_audio" -> "EXE";
            case "table.merge_excel" -> "Python";
            default -> "JAR";
        };
    }

    static String purpose(Map<String, Object> t) {
        return switch (String.valueOf(t.get("toolId"))) {
            case "ai.text" -> "调用系统设置中的 AI 服务处理单项文本；配置 Prompt、输出 Token 和响应超时，生成文本结果。";
            case "io.file_select" -> "选择单个文件或文件夹中的文件，按类型筛选，形成文件数据流。";
            case "io.file_export" -> "将结果保存到指定目录或原文件位置；支持复制、移动和原文件保留策略。";
            case "batch.pdf" -> "每个文件生成对应 PDF，或按默认排序合并成一个 PDF。办公格式转换需要本机 Office 或 LibreOffice。";
            case "batch.rename" -> "按模板批量改名，保留原扩展名。可选接入 CSV，使用表头作为模板字段。";
            case "build.java_jar" -> "将 Java 源码编译并打包为 JAR，需要 JDK；不解析 Maven / Gradle 依赖。";
            case "media.extract_audio" -> "使用 FFmpeg 提取第一条音轨并生成 MP3；没有音轨的文件会报错。";
            case "table.merge_excel" -> "按表头对齐合并第一张工作表并添加来源列，输出 XLSX。保存单元格值，不复制格式；公式需要缓存结果。";
            default -> "支持范围来自工具自身的声明，运行时继续校验输入。";
        };
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> ports(Map<String, Object> t, String side) {
        Object raw = t.get(side);
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(v -> (Map<String, Object>) v)
                .filter(v -> !"source".equals(v.get("name")))
                .toList();
    }

    static Set<String> formats(Map<String, Object> t, String side) {
        Set<String> f = new TreeSet<>();
        for (var p : ports(t, side))
            if (p.get("formats") instanceof List<?> list)
                for (Object v : list) f.add(String.valueOf(v));
        return f;
    }

    static String formatsLabel(Set<String> f) {
        return f.isEmpty() ? "无需上游输入" : f.contains("*") ? "任意可读文件（原样传递）" : String.join(", ", f);
    }

    /**
     * 匹配名称、用途、运行形式和输入输出后缀；通配符表示可传递，不代表可转换。
     *
     * <p>Matches labels and port formats; wildcard pass-through does not imply format conversion.
     */
    static boolean matches(Map<String, Object> t, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (name(t).toLowerCase(Locale.ROOT).contains(q)
                || purpose(t).toLowerCase(Locale.ROOT).contains(q)
                || runtime(t).toLowerCase(Locale.ROOT).contains(q)) return true;
        String ext = q.startsWith(".") ? q.substring(1) : q;
        if (!ext.matches("[a-z0-9]{1,10}")) return false;
        Set<String> in = formats(t, "inputs"), out = formats(t, "outputs");
        return in.contains(ext) || out.contains(ext) || in.contains("*") || out.contains("*");
    }

    void filter() {
        String q = search.getText();
        visible.clear();
        model.setRowCount(0);
        for (var t : tools)
            if (matches(t, q)) {
                visible.add(t);
                String incoming =
                        "io.file_select".equals(t.get("toolId"))
                                ? "任意可读文件（选择原文件）"
                                : formatsLabel(formats(t, "inputs"));
                model.addRow(
                        new Object[] {
                            name(t),
                            "AVAILABLE".equals(t.get("implementation")) ? "可用" : "缺少依赖",
                            runtime(t),
                            incoming,
                            formatsLabel(formats(t, "outputs")),
                            t.get("toolVersion")
                        });
            }
        results.setText(
                q.isBlank()
                        ? "已就绪：" + visible.size() + " 个工具 · 选择一项查看详情"
                        : "找到 " + visible.size() + " 个工具 · 接收与生成格式请分别查看，原样传递不代表可转换");
        if (!visible.isEmpty()) table.setRowSelectionInterval(0, 0);
        else {
            details.setText(
                    "<html><body style='font-family:Microsoft YaHei;padding:16px'>没有匹配的工具。可尝试扩展名，如 pdf、docx、xlsx。</body></html>");
            protocol.setText("");
        }
    }

    static String esc(Object v) {
        return String.valueOf(v)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 展示格式方向、识别策略与原始协议，动态文本在拼接 HTML 前转义。 Escapes dynamic HTML fields. */
    void showSelection() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= visible.size()) return;
        var t = visible.get(i);
        StringBuilder html =
                new StringBuilder(
                        "<html><body style='font-family:Microsoft YaHei;color:#192439;padding:12px;font-size:12px'>");
        if (!"AVAILABLE".equals(t.get("implementation")))
            html.append("<p style=\"color:#a05a00\">当前缺少运行依赖。音频工具请运行安装音频组件；其他工具查看使用说明。</p>");
        html.append("<h2>")
                .append(esc(name(t)))
                .append(" <span style='color:#60718b;font-size:12px'>")
                .append(esc(runtime(t)))
                .append(" · ")
                .append(esc(t.get("toolVersion")))
                .append("</span></h2><p>")
                .append(esc(purpose(t)))
                .append("</p>");
        for (String side : List.of("inputs", "outputs")) {
            html.append("<h3>").append(side.equals("inputs") ? "输入要求" : "输出结果").append("</h3>");
            if (ports(t, side).isEmpty()) html.append("<p>从配置中选择文件，无需上游节点。</p>");
            for (var p : ports(t, side)) {
                String pn = String.valueOf(p.get("name"));
                html.append("<p><b>")
                        .append(pn.equals("csv") ? "CSV 模板数据（可选）" : "文件数据")
                        .append("</b> · ")
                        .append("SINGLE".equals(p.get("cardinality")) ? "单个文件" : "支持批量文件");
                Set<String> f = new TreeSet<>();
                if (p.get("formats") instanceof List<?> list)
                    for (Object v : list) f.add(String.valueOf(v));
                html.append("<br>")
                        .append(esc(formatsLabel(f)))
                        .append("<br><span style='color:#60718b'>")
                        .append(
                                "IDENTIFY".equals(p.get("validation"))
                                        ? "检查实际文件内容与类型；后缀符合仍需运行校验。"
                                        : "保留原文件内容，不解析正文。")
                        .append("</span></p>");
            }
        }
        html.append("</body></html>");
        details.setText(html.toString());
        details.setCaretPosition(0);
        protocol.setText(pretty(t, 0));
        protocol.setCaretPosition(0);
    }

    static String pretty(Object v, int depth) {
        String pad = "  ".repeat(depth), child = "  ".repeat(depth + 1);
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            for (var e : m.entrySet())
                parts.add(
                        child
                                + Json.stringify(String.valueOf(e.getKey()))
                                + ": "
                                + pretty(e.getValue(), depth + 1));
            return "{\n" + String.join(",\n", parts) + "\n" + pad + "}";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object item : l) parts.add(child + pretty(item, depth + 1));
            return "[\n" + String.join(",\n", parts) + "\n" + pad + "]";
        }
        return Json.stringify(v);
    }

    static void verify(java.nio.file.Path destination) throws Exception {
        java.nio.file.Files.createDirectories(destination);
        List<Map<String, Object>> found = new ArrayList<>();
        for (String tool : ExecutableTools.all())
            found.add(ElementCapabilities.discover(FileTransferRunner.toolPath(tool)));
        SwingUtilities.invokeAndWait(
                () -> {
                    ToolCatalogPanel panel = new ToolCatalogPanel(found);
                    if (panel.visible.size() != 8) throw new AssertionError("all tools listed");
                    panel.search.setText(".DOCX");
                    Set<String> ids = new HashSet<>();
                    for (var t : panel.visible) ids.add(String.valueOf(t.get("toolId")));
                    if (!ids.equals(
                            Set.of(
                                    "io.file_select",
                                    "io.file_export",
                                    "batch.rename",
                                    "batch.pdf")))
                        throw new AssertionError("DOCX actual format search: " + ids);
                    panel.search.setText("xlsx");
                    if (panel.visible.stream()
                            .noneMatch(t -> "table.merge_excel".equals(t.get("toolId"))))
                        throw new AssertionError("xlsx merge search");
                    panel.search.setText("mp3");
                    if (panel.visible.stream()
                            .noneMatch(t -> "media.extract_audio".equals(t.get("toolId"))))
                        throw new AssertionError("mp3 output search");
                    panel.search.setText("zzcustom");
                    if (panel.visible.size() != 3
                            || panel.visible.stream()
                                    .anyMatch(t -> !formats(t, "outputs").contains("*")))
                        throw new AssertionError("unknown type only preservation tools");
                    panel.search.setText("合并 Excel");
                    if (panel.visible.size() != 1 || !panel.details.getText().contains("XLSX"))
                        throw new AssertionError("name search and details");
                    panel.search.setText("不存在的工具");
                    if (!panel.visible.isEmpty()) throw new AssertionError("empty search state");
                    panel.search.setText("AI 文本");
                    if (panel.visible.size() != 1 || !name(panel.visible.get(0)).equals("AI 文本处理"))
                        throw new AssertionError("AI name search");
                    panel.search.setText("");
                    if (panel.visible.size() != 8) throw new AssertionError("clear restores all");
                    for (var t : found)
                        try {
                            if (!Json.object(Json.parse(pretty(t, 0))).equals(t))
                                throw new AssertionError("protocol JSON");
                        } catch (java.io.IOException ex) {
                            throw new RuntimeException(ex);
                        }
                });
        java.nio.file.Files.writeString(
                destination.resolve("result.txt"),
                "PASS: 8 real tool declarations; .DOCX case normalization; input/output format search; wildcard preservation; AI name search; empty/clear; readable details; valid formatted JSON.\n");
        System.out.println("PASS: tool catalog and file type search");
    }

    static void show(Component owner, List<Map<String, Object>> tools) {
        JDialog dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(owner),
                        "工具与支持格式",
                        Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout());
        body.add(new ToolCatalogPanel(tools));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dialog.dispose());
        footer.add(close);
        body.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        dialog.setSize(
                Math.min(dialog.getWidth(), screen.width - 80),
                Math.min(dialog.getHeight(), screen.height - 80));
        dialog.setMinimumSize(new Dimension(680, 480));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}

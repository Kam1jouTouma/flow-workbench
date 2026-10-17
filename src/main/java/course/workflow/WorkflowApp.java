package course.workflow;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 流程编辑器入口：集中定义画布模型与 Swing 交互，实际文件处理交给执行层。
 *
 * <p>Application entry point and Swing editor; execution is delegated to staged CLI tools.
 */
public final class WorkflowApp {
    static final Color INK = new Color(31, 43, 65), MUTED = new Color(89, 106, 133);
    static final Color BLUE = new Color(68, 108, 238), BG = new Color(245, 247, 251);
    static final Color LINE = new Color(225, 231, 240), GREEN = new Color(29, 153, 130);
    static final Color BRAND_BG = new Color(25, 36, 57), HEADER_BG = new Color(225, 234, 250);
    static final Color CONFIG_BG = new Color(237, 232, 247), LOG_BG = new Color(225, 241, 236);
    static final Color TOOLBAR_BG = new Color(237, 242, 250);
    static final Font BODY = new Font("Microsoft YaHei", Font.PLAIN, 13);

    /**
     * 元素的显示信息和基础类型；动态端口由节点配置及子图接口决定。
     *
     * <p>Display metadata and base types; configured nodes and subgraphs determine actual ports.
     */
    enum Kind {
        TEXT("文本输入", "输入", "T", GREEN, null, "TEXT", "输入内容", "在这里填写需要处理的文本。"),
        FILE("文件输入", "输入", "F", GREEN, null, "FILE", "文件路径 / 匹配规则", ""),
        VIRTUAL_IN("虚拟输入", "输入", "IN", GREEN, null, "ANY", "子图接口名称", "input"),
        READ("读取正文", "工作", "R", BLUE, "FILE", "TEXT", "读取说明", "提取文件正文"),
        AI("AI 文本处理", "工作", "AI", BLUE, "TEXT", "TEXT", "Prompt", "提取主要事项，保留关键事实。"),
        SCRIPT("自定义脚本", "工作", "CLI", BLUE, "FILE", "FILE", "脚本参数", ""),
        PDF("批量 PDF", "工作", "P", BLUE, "FILE", "FILE", "转换设置", "逐文件生成 / 默认排序合并"),
        RENAME("批量改名", "工作", "N", BLUE, "FILE", "FILE", "命名模板", "{x}"),
        FILE_MERGE("文件集合汇合", "工作", "JOIN", BLUE, "FILE", "FILE", "输入数量", "按输入端口顺序汇合文件"),
        SUBGRAPH("子图模块", "工作", "SUB", BLUE, "ANY", "ANY", "子图定义", "具名接口与内部步骤"),
        JAVA_BUILD("Java 编译打包", "工作", "BAT", BLUE, "FILE", "FILE", "编译设置", "BAT · 编译并生成JAR"),
        AUDIO("提取 MP3", "工作", "EXE", BLUE, "FILE", "FILE", "音质设置", "EXE · 提取音频"),
        EXCEL_MERGE("Excel 表格合并", "工作", "PY", BLUE, "FILE", "FILE", "来源列名", "PY · 按表头对齐合并"),
        PREVIEW("结果预览", "输出", "V", new Color(160, 105, 211), "ANY", null, "展示标题", "处理结果"),
        SAVE("文件输出", "输出", "S", new Color(160, 105, 211), "ANY", null, "输出路径 / 命名规则", ""),
        VIRTUAL_OUT("虚拟输出", "输出", "OUT", new Color(160, 105, 211), "ANY", null, "子图接口名称", "output");
        final String label, group, badge, input, output, parameter, initial;
        final Color color;

        Kind(
                String label,
                String group,
                String badge,
                Color color,
                String input,
                String output,
                String parameter,
                String initial) {
            this.label = label;
            this.group = group;
            this.badge = badge;
            this.color = color;
            this.input = input;
            this.output = output;
            this.parameter = parameter;
            this.initial = initial;
        }
    }

    /**
     * 可编辑节点；settings 保存元素参数，child 保存该子图实例的内部流程。
     *
     * <p>Mutable node state; settings belong to the element and child belongs to this module
     * instance.
     */
    static final class Node {
        final String id;
        final Kind kind;
        String name, value;
        double x, y;
        final Map<String, Object> settings;
        Flow child;

        Node(String id, Kind kind, double x, double y) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
            name = kind.label;
            value = kind.initial;
            settings = IoConfig.defaults(kind);
            if (kind == Kind.SUBGRAPH) child = Subgraphs.defaultModule();
        }

        String outputType() {
            if (kind == Kind.SUBGRAPH || Subgraphs.boundary(kind))
                return outputPorts().isEmpty() ? null : outputPorts().get(0).type();
            return kind == Kind.TEXT
                    ? (String) settings.get("literalType")
                    : kind == Kind.FILE
                            ? switch ((String) settings.get("mode")) {
                                case "TEXT" -> "TEXT";
                                case "JSON", "CSV" -> "JSON";
                                default -> "FILE";
                            }
                            : kind.output;
        }

        List<Subgraphs.Port> outputPorts() {
            return Subgraphs.ports(this, true);
        }

        List<Subgraphs.Port> inputPorts() {
            return Subgraphs.ports(this, false);
        }

        String summary() {
            return kind == Kind.FILE
                    ? (String) settings.get("sourcePath")
                    : kind == Kind.SAVE
                            ? settings.get("directory") + " / " + settings.get("fileName")
                            : value;
        }
    }

    /** 端口按名称关联，port 指目标输入。 Named-port edge; port denotes the destination input. */
    record Edge(String from, String to, String port, String fromPort) {
        Edge(String from, String to) {
            this(from, to, "data", "data");
        }

        Edge(String from, String to, String port) {
            this(from, to, port, "data");
        }
    }

    // 剪贴板持有值快照，原节点后续编辑不会影响副本。 Snapshot clipboard content isolates later edits.
    record NodeCopy(
            Kind kind,
            String name,
            String value,
            double x,
            double y,
            Map<String, Object> settings,
            Flow child) {
        static NodeCopy of(Node n) {
            return new NodeCopy(
                    n.kind,
                    n.name,
                    n.value,
                    n.x,
                    n.y,
                    Map.copyOf(n.settings),
                    Subgraphs.copy(n.child));
        }
    }

    /**
     * 单层流程模型及 XML 存储；嵌套层级由 SUBGRAPH 节点的 child 递归表示。
     *
     * <p>One graph level and its XML persistence; SUBGRAPH child flows represent nested levels.
     */
    static final class Flow {
        String title = "我的第一个工作流";
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        Node add(Kind kind, double x, double y) {
            Node n = new Node(UUID.randomUUID().toString(), kind, x, y);
            if (Subgraphs.boundary(kind)) {
                String base = kind == Kind.VIRTUAL_IN ? "input" : "output", name = base;
                int index = 1;
                while (nodes.stream()
                        .anyMatch(
                                v ->
                                        v.kind == kind
                                                && v.settings
                                                        .get("interfaceName")
                                                        .equals(n.settings.get("interfaceName"))))
                    n.settings.put("interfaceName", base + (++index));
            }
            nodes.add(n);
            return n;
        }

        Node node(String id) {
            return nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElse(null);
        }

        String connect(Node from, Node to) {
            return connect(from, to, true);
        }

        String connect(Node from, Node to, boolean checkTypes) {
            return connect(from, to, "data", checkTypes);
        }

        String connect(Node from, Node to, String port) {
            return connect(from, to, port, true);
        }

        String connect(Node from, Node to, String port, boolean checkTypes) {
            return connect(from, "data", to, port, checkTypes);
        }

        /**
         * 每个输入最多一条连线，且不允许环路；读取旧图时可暂缓类型检查。
         *
         * <p>Enforces one source per input and an acyclic graph, with optional type checks on
         * loading.
         */
        String connect(Node from, String fromPort, Node to, String port, boolean checkTypes) {
            if (from == null || to == null) return "请选择有效的节点端口。";
            if (from == to) return "不能连接节点自身。";
            if (checkTypes
                    && (Subgraphs.find(from, true, fromPort) == null
                            || Subgraphs.find(to, false, port) == null)) return "端口不存在，请检查子图接口名称。";
            if (edges.contains(new Edge(from.id, to.id, port, fromPort))) return "这条连线已经存在。";
            if (edges.stream().anyMatch(e -> e.to.equals(to.id) && e.port.equals(port)))
                return "当前每个输入端口只接受一个来源。";
            if (checkTypes && incompatible(from, fromPort, to, port)) {
                String capability = ElementCapabilities.connectionError(from, to);
                return capability != null
                        ? capability
                        : Subgraphs.connectionDescription(from, fromPort, to, port);
            }
            if (reaches(to.id, from.id, new HashSet<>())) return "第一版不允许环路。";
            edges.add(new Edge(from.id, to.id, port, fromPort));
            return null;
        }

        static boolean incompatible(Node from, Node to) {
            return incompatible(from, "data", to, "data");
        }

        static boolean incompatible(Node from, String fromPort, Node to, String port) {
            return Subgraphs.incompatible(from, fromPort, to, port);
        }

        static boolean incompatible(Node from, Node to, String port) {
            return incompatible(from, "data", to, port);
        }

        boolean reaches(String start, String end, Set<String> visited) {
            if (start.equals(end)) return true;
            if (!visited.add(start)) return false;
            for (Edge e : edges)
                if (e.from.equals(start) && reaches(e.to, end, visited)) return true;
            return false;
        }

        void remove(Node n) {
            nodes.remove(n);
            edges.removeIf(e -> e.from.equals(n.id) || e.to.equals(n.id));
        }

        /**
         * 先生成完整 XML，再用同目录临时文件替换目标；支持时使用原子移动。
         *
         * <p>Serializes fully before replacing the destination from a sibling temporary file.
         */
        void save(Path path) throws IOException {
            byte[] bytes = xml(0, Collections.newSetFromMap(new IdentityHashMap<>()));
            Path absolute = path.toAbsolutePath();
            Path tmp = Files.createTempFile(absolute.getParent(), "workflow-", ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(
                            tmp,
                            absolute,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        /**
         * 子图嵌入父文件；身份集合检查对象递归，depth 限制嵌套深度。
         *
         * <p>Embeds child XML; identity tracking rejects recursive references and depth limits
         * nesting.
         */
        byte[] xml(int depth, Set<Flow> stack) throws IOException {
            if (depth > Subgraphs.MAX_DEPTH || !stack.add(this))
                throw new IOException("子图递归或嵌套过深。");
            Properties p = new Properties();
            p.setProperty("version", "4");
            p.setProperty("title", title);
            p.setProperty("node.count", "" + nodes.size());
            p.setProperty("edge.count", "" + edges.size());
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                String k = "node." + i + ".";
                p.setProperty(k + "id", n.id);
                p.setProperty(k + "kind", n.kind.name());
                p.setProperty(k + "name", n.name);
                p.setProperty(k + "value", n.value);
                p.setProperty(k + "x", "" + n.x);
                p.setProperty(k + "y", "" + n.y);
                if (n.kind == Kind.SUBGRAPH) {
                    if (n.child == null) throw new IOException("子图定义缺失。");
                    p.setProperty(
                            k + "child",
                            Base64.getEncoder().encodeToString(n.child.xml(depth + 1, stack)));
                }
                p.setProperty(k + "setting.count", "" + n.settings.size());
                int settingIndex = 0;
                for (Map.Entry<String, Object> setting : n.settings.entrySet()) {
                    String prefix = k + "setting." + settingIndex++ + ".";
                    Object v = setting.getValue();
                    p.setProperty(prefix + "key", setting.getKey());
                    p.setProperty(prefix + "value", v.toString());
                    p.setProperty(
                            prefix + "type",
                            v instanceof Boolean
                                    ? "boolean"
                                    : v instanceof Integer ? "integer" : "string");
                }
            }
            for (int i = 0; i < edges.size(); i++) {
                p.setProperty("edge." + i + ".from", edges.get(i).from);
                p.setProperty("edge." + i + ".to", edges.get(i).to);
                p.setProperty("edge." + i + ".port", edges.get(i).port);
                p.setProperty("edge." + i + ".fromPort", edges.get(i).fromPort);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.storeToXML(out, "Workflow / embedded subgraphs", "UTF-8");
            stack.remove(this);
            return out.toByteArray();
        }

        /** 文件大小限制只作用于顶层入口；解析时继续检查子图。 Bounds the file before recursive parsing. */
        static Flow load(Path path) throws IOException {
            if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("流程文件过大。");
            try (InputStream in = Files.newInputStream(path)) {
                return parse(in, 0);
            }
        }

        /**
         * 兼容历史版本的字段布局，并重建节点、配置和具名连线；不运行图中工具。
         *
         * <p>Rebuilds nodes, typed settings and named edges from supported versions without
         * execution.
         */
        static Flow parse(InputStream in, int depth) throws IOException {
            try {
                if (depth > Subgraphs.MAX_DEPTH) throw new IOException("子图嵌套超过8层。");
                Properties p = new Properties();
                p.loadFromXML(in);
                String version = p.getProperty("version");
                if (version == null || !List.of("1", "2", "3", "4").contains(version))
                    throw new IOException("流程版本不受支持。");
                Flow f = new Flow();
                f.title = required(p, "title");
                Set<String> ids = new HashSet<>();
                int count = Integer.parseInt(required(p, "node.count"));
                int edges = Integer.parseInt(required(p, "edge.count"));
                if (count < 0 || count > 1000 || edges < 0 || edges > 10000)
                    throw new IOException("流程规模超出读取范围。");
                for (int i = 0; i < count; i++) {
                    String k = "node." + i + ".";
                    double x = Double.parseDouble(required(p, k + "x")),
                            y = Double.parseDouble(required(p, k + "y"));
                    if (!Double.isFinite(x)
                            || !Double.isFinite(y)
                            || x < 0
                            || y < 0
                            || x > 100000
                            || y > 100000) throw new IOException("节点坐标无效。");
                    Node n =
                            new Node(
                                    required(p, k + "id"),
                                    Kind.valueOf(required(p, k + "kind")),
                                    x,
                                    y);
                    if (!ids.add(n.id)) throw new IOException("节点标识重复。");
                    n.name = required(p, k + "name");
                    n.value = required(p, k + "value");
                    if (n.kind == Kind.AI && !n.value.isBlank()) n.settings.put("prompt", n.value);
                    if (n.kind == Kind.SUBGRAPH) {
                        byte[] child = Base64.getDecoder().decode(required(p, k + "child"));
                        if (child.length > 16 * 1024 * 1024) throw new IOException("子图过大。");
                        n.child = parse(new ByteArrayInputStream(child), depth + 1);
                    }
                    if (!"1".equals(version)) {
                        int settingsCount = Integer.parseInt(required(p, k + "setting.count"));
                        if (settingsCount < 0 || settingsCount > 64)
                            throw new IOException("配置数量无效。");
                        Set<String> keys = new HashSet<>();
                        for (int s = 0; s < settingsCount; s++) {
                            String prefix = k + "setting." + s + ".",
                                    key = required(p, prefix + "key"),
                                    raw = required(p, prefix + "value");
                            if (!keys.add(key)) throw new IOException("配置键重复。");
                            Object setting =
                                    switch (required(p, prefix + "type")) {
                                        case "string" -> raw;
                                        case "integer" -> Integer.valueOf(raw);
                                        case "boolean" -> {
                                            if (!raw.equals("true") && !raw.equals("false"))
                                                throw new IOException("布尔配置无效。");
                                            yield Boolean.valueOf(raw);
                                        }
                                        default -> throw new IOException("配置类型无效。");
                                    };
                            IoConfig.requireSetting(n.kind, key, setting);
                            n.settings.put(key, setting);
                        }
                    } else if (n.kind == Kind.FILE) {
                        n.settings.put("sourcePath", n.value);
                        n.settings.put("mode", "FILE");
                    } else if (n.kind == Kind.SAVE && !n.value.isBlank())
                        n.settings.put("directory", n.value);
                    if (!version.equals("4")
                            && Subgraphs.boundary(n.kind)
                            && n.value.matches("[\\p{L}_][\\p{L}\\p{N}_-]{0,31}"))
                        n.settings.put("interfaceName", n.value);
                    f.nodes.add(n);
                }
                for (int i = 0; i < edges; i++) {
                    String error =
                            f.connect(
                                    f.node(required(p, "edge." + i + ".from")),
                                    version.equals("4")
                                            ? required(p, "edge." + i + ".fromPort")
                                            : "data",
                                    f.node(required(p, "edge." + i + ".to")),
                                    List.of("3", "4").contains(version)
                                            ? required(p, "edge." + i + ".port")
                                            : "data",
                                    false);
                    if (error != null) throw new IOException("无效连线：" + error);
                }
                return f;
            } catch (IllegalArgumentException ex) {
                throw new IOException("流程内容无效：" + ex.getMessage(), ex);
            }
        }

        static String required(Properties p, String key) throws IOException {
            String value = p.getProperty(key);
            if (value == null) throw new IOException("缺少字段：" + key);
            return value;
        }
    }

    /**
     * 画布、选择状态、侧边栏和文件操作的协调层；Swing 界面更新在事件线程执行。
     *
     * <p>Coordinates canvas state, inspectors and file actions; Swing updates belong on the EDT.
     */
    @SuppressWarnings("serial") // 流程使用 XML 存储。 Swing serialization is not the storage format.
    static final class Editor extends JPanel {
        private static final long serialVersionUID = 1L;
        Flow flow = new Flow();
        Path currentFile;
        boolean dirty, updating;
        Node selected;
        Edge selectedEdge;
        NodeCopy clipboard;
        int pasteCount;
        final Canvas canvas = new Canvas(this);
        final JLabel title = label("我的第一个工作流", 20, INK, true);
        final JLabel count = label("", 12, MUTED, false), status = label("就绪", 12, MUTED, false);
        final JLabel zoomLabel = label("100%", 12, MUTED, false);
        final JTextArea events = new JTextArea();
        final JPanel inspector = new JPanel(new BorderLayout());
        final JTextField nodeName = new JTextField();
        final JTextArea parameter = new JTextArea(7, 20);
        final JLabel parameterLabel = label("参数", 12, MUTED, true);
        final JLabel typeLabel = label("", 12, MUTED, false);
        final JLabel inspectorTitle = label("节点配置", 16, INK, true);
        final JTextArea capabilityInfo = text("", 252);
        final CardLayout inspectorCards = new CardLayout();
        final JPanel inspectorBody = new JPanel(inspectorCards);
        final JScrollPane canvasScroll;
        boolean executing;
        final JButton runButton = button("运行", true, this::runFileFlow);
        final IoConfigPanel ioPanel = new IoConfigPanel(this);
        final BatchConfigPanel batchPanel = new BatchConfigPanel(this);
        final SubgraphPanel subgraphPanel = new SubgraphPanel(this);
        boolean moduleEditor;

        /** 返回父画布所需的视图状态。 Parent graph and viewport state for returning from a child. */
        record CanvasLevel(
                Flow parent, Node owner, double zoom, double panX, double panY, Point scroll) {}

        final List<CanvasLevel> levels = new ArrayList<>();
        final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));

        /** 保存始终以整张根图为单位。 Saving always starts from the root graph. */
        Flow rootFlow() {
            return levels.isEmpty() ? flow : levels.get(0).parent();
        }

        void clearNavigation() {
            levels.clear();
            moduleEditor = false;
            clearCanvasInteraction();
        }

        void clearCanvasInteraction() {
            canvas.pending = null;
            canvas.pendingPort = "data";
            canvas.dragging = null;
            canvas.panning = false;
            if (canvas.lastPopup != null) canvas.lastPopup.setVisible(false);
            selectedEdge = null;
        }

        void refreshNavigation() {
            navigation.removeAll();
            navigation.setBackground(HEADER_BG);
            JButton back = button("返回上一级", false, this::leaveSubgraph);
            back.setEnabled(!levels.isEmpty());
            navigation.add(back);
            if (levels.isEmpty()) navigation.add(label("主画布", 12, INK, true));
            else {
                navigation.add(button("主画布", false, () -> navigateTo(0)));
                for (int i = 0; i < levels.size(); i++) {
                    navigation.add(label("›", 12, MUTED, false));
                    int depth = i + 1;
                    String name = levels.get(i).owner().name;
                    if (depth == levels.size()) navigation.add(label(name, 12, INK, true));
                    else navigation.add(button(name, false, () -> navigateTo(depth)));
                }
                navigation.add(label("编辑自动保留 · 保存包含整个流程", 11, MUTED, false));
            }
            runButton.setEnabled(!executing && levels.isEmpty());
            navigation.revalidate();
            navigation.repaint();
        }

        void navigateTo(int depth) {
            while (levels.size() > depth) leaveSubgraph();
        }

        /**
         * 恢复父图视图；内部编辑直接作用于 child，因此不需要额外提交。
         *
         * <p>Restores the parent viewport; child edits already live in the owned child flow.
         */
        void leaveSubgraph() {
            if (levels.isEmpty()) return;
            CanvasLevel parent = levels.remove(levels.size() - 1);
            flow = parent.parent();
            moduleEditor = !levels.isEmpty();
            clearCanvasInteraction();
            select(parent.owner());
            canvas.zoom = parent.zoom();
            canvas.panX = parent.panX();
            canvas.panY = parent.panY();
            zoomLabel.setText((int) Math.round(canvas.zoom * 100) + "%");
            refresh();
            canvasScroll.getViewport().setViewPosition(parent.scroll());
            hint("已返回上一级画布，子图修改已保留。");
        }

        final CardLayout parameterCards = new CardLayout();
        final JPanel parameterBody = new JPanel(parameterCards);

        Editor() {
            super(new BorderLayout());
            setBackground(BG);
            add(header(), BorderLayout.NORTH);
            add(library(), BorderLayout.WEST);
            add(inspector(), BorderLayout.EAST);
            JPanel center = new JPanel(new BorderLayout());
            center.setBackground(BG);
            canvasScroll = new JScrollPane(canvas);
            canvasScroll.setBorder(BorderFactory.createEmptyBorder());
            canvasScroll.getViewport().setBackground(BG);
            canvasScroll.getVerticalScrollBar().setUnitIncrement(30);
            canvasScroll.getHorizontalScrollBar().setUnitIncrement(30);
            JPanel canvasHeader = new JPanel(new BorderLayout());
            canvasHeader.add(toolbar(), BorderLayout.NORTH);
            canvasHeader.add(navigation, BorderLayout.SOUTH);
            center.add(canvasHeader, BorderLayout.NORTH);
            center.add(canvasScroll, BorderLayout.CENTER);
            center.add(runtimePanel(), BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);
            installKeys();
            demo();
            listen(
                    nodeName,
                    () -> {
                        if (!updating && selected != null) {
                            selected.name = nodeName.getText();
                            changed();
                        }
                    });
            parameter
                    .getDocument()
                    .addDocumentListener(
                            new DocumentListener() {
                                public void insertUpdate(DocumentEvent e) {
                                    edit();
                                }

                                public void removeUpdate(DocumentEvent e) {
                                    edit();
                                }

                                public void changedUpdate(DocumentEvent e) {
                                    edit();
                                }

                                void edit() {
                                    if (!updating && selected != null) {
                                        selected.value = parameter.getText();
                                        changed();
                                    }
                                }
                            });
        }

        JPanel header() {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(HEADER_BG);
            JPanel brand = column(BRAND_BG);
            brand.setPreferredSize(new Dimension(238, 92));
            brand.setBorder(new EmptyBorder(20, 24, 18, 15));
            brand.add(label("FLOW / 工作流", 19, Color.WHITE, true));
            brand.add(Box.createVerticalStrut(5));
            brand.add(label("JAVA 课程项目 · 界面原型", 11, new Color(171, 191, 222), false));
            p.add(brand, BorderLayout.WEST);
            JPanel names = column(HEADER_BG);
            names.setBorder(new EmptyBorder(20, 24, 18, 15));
            names.add(title);
            names.add(Box.createVerticalStrut(5));
            names.add(label("把处理步骤连接起来，让每一段流程都可复用。", 12, MUTED, false));
            p.add(names, BorderLayout.CENTER);
            JPanel actions = row(HEADER_BG);
            actions.setBorder(new EmptyBorder(20, 10, 18, 24));
            actions.add(button("示例", false, this::chooseExample));
            actions.add(button("设置", false, () -> SystemSettingsPanel.show(this)));
            actions.add(button("流程助手", false, () -> AgentDialog.show(this)));
            actions.add(button("封装步骤", false, this::encapsulateSteps));
            actions.add(button("重命名", false, this::rename));
            actions.add(button("打开", false, this::open));
            JButton importButton = button("导入", false, this::importFlow);
            importButton.setToolTipText("从其他文件夹添加图到流程库");
            actions.add(importButton);
            actions.add(button("保存流程", true, this::save));
            p.add(actions, BorderLayout.EAST);
            return p;
        }

        JPanel library() {
            Color dark = new Color(25, 36, 57);
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(dark);
            p.setPreferredSize(new Dimension(238, 600));
            p.setBorder(new EmptyBorder(23, 18, 16, 18));
            JPanel heading = column(dark);
            heading.add(label("元素库", 17, Color.WHITE, true));
            heading.add(Box.createVerticalStrut(7));
            heading.add(label("点击添加到画布", 12, new Color(157, 174, 200), false));
            heading.add(Box.createVerticalStrut(20));
            p.add(heading, BorderLayout.NORTH);
            JPanel list = column(dark);
            for (String group : List.of("输入", "工作", "输出")) {
                list.add(label(group + "元素", 12, new Color(157, 174, 200), true));
                list.add(Box.createVerticalStrut(9));
                for (Kind kind : Kind.values())
                    if (kind.group.equals(group)) {
                        JButton b = new JButton("  " + kind.badge + "   " + kind.label);
                        b.setFont(BODY);
                        b.setHorizontalAlignment(SwingConstants.LEFT);
                        b.setForeground(Color.WHITE);
                        b.setBackground(new Color(39, 53, 77));
                        b.setOpaque(true);
                        b.setFocusPainted(false);
                        b.setBorder(new EmptyBorder(11, 10, 11, 8));
                        b.setAlignmentX(LEFT_ALIGNMENT);
                        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 43));
                        b.setToolTipText(
                                "添加"
                                        + kind.label
                                        + " · "
                                        + (kind.input == null ? "源节点" : kind.input + " 输入"));
                        b.addActionListener(e -> addNode(kind));
                        list.add(b);
                        list.add(Box.createVerticalStrut(7));
                    }
                list.add(Box.createVerticalStrut(16));
            }
            JScrollPane sc = new JScrollPane(list);
            sc.setBorder(null);
            sc.getViewport().setBackground(dark);
            sc.getVerticalScrollBar().setUnitIncrement(20);
            p.add(sc, BorderLayout.CENTER);
            JLabel foot = label("第 01 步 / 流程界面", 11, new Color(157, 174, 200), false);
            foot.setText("第 02 步 / 画布交互");
            foot.setBorder(new EmptyBorder(10, 0, 0, 0));
            p.add(foot, BorderLayout.SOUTH);
            return p;
        }

        JPanel toolbar() {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(TOOLBAR_BG);
            p.setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(203, 216, 235)),
                            new EmptyBorder(10, 18, 10, 18)));
            JPanel left = row(TOOLBAR_BG);
            left.add(button("新建", false, this::fresh));
            left.add(
                    button(
                            "添加连线",
                            false,
                            () -> {
                                if (selected != null && !selected.outputPorts().isEmpty()) {
                                    canvas.pending = selected;
                                    canvas.pendingPort = selected.outputPorts().get(0).name();
                                    hint("点击目标节点的左侧输入端口；Esc 取消。");
                                } else hint("先选中带输出端口的节点，再添加连线。");
                                canvas.repaint();
                            }));
            left.add(button("删除", false, this::deleteSelection));
            left.add(button("导入脚本", false, () -> ScriptTools.choose(this)));
            left.add(button("脚本教程", false, () -> ScriptTools.help(this)));
            left.add(count);
            p.add(left, BorderLayout.WEST);
            JPanel right = row(TOOLBAR_BG);
            right.add(button("−", false, () -> zoom(-.1)));
            right.add(zoomLabel);
            right.add(button("+", false, () -> zoom(.1)));
            right.add(button("复位", false, this::resetView));
            right.add(button("检查", false, this::check));
            right.add(runButton);
            p.add(right, BorderLayout.EAST);
            return p;
        }

        JPanel inspector() {
            inspector.setBackground(CONFIG_BG);
            inspector.setPreferredSize(new Dimension(310, 600));
            inspector.setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(178, 156, 211)),
                            new EmptyBorder(24, 20, 20, 20)));
            inspectorTitle.setForeground(new Color(87, 60, 133));
            inspector.add(inspectorTitle, BorderLayout.NORTH);
            inspectorBody.setBackground(CONFIG_BG);
            JPanel empty = column(CONFIG_BG);
            empty.setBorder(new EmptyBorder(28, 0, 0, 0));
            empty.add(label("选择一个节点", 14, INK, true));
            empty.add(Box.createVerticalStrut(12));
            empty.add(text("在画布上点击节点，查看端口并编辑参数。\n\n从右侧输出端口连接到另一个节点的左侧输入端口。", 220));
            inspectorBody.add(empty, "empty");
            JPanel form = column(CONFIG_BG);
            form.setBorder(new EmptyBorder(25, 0, 0, 0));
            form.add(label("节点名称", 12, MUTED, true));
            form.add(Box.createVerticalStrut(8));
            nodeName.setFont(BODY);
            nodeName.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            nodeName.setAlignmentX(LEFT_ALIGNMENT);
            form.add(nodeName);
            form.add(Box.createVerticalStrut(22));
            JPanel generic = column(CONFIG_BG);
            generic.add(parameterLabel);
            generic.add(Box.createVerticalStrut(8));
            parameter.setFont(BODY);
            parameter.setLineWrap(true);
            parameter.setWrapStyleWord(true);
            parameter.setMargin(new Insets(10, 10, 10, 10));
            JScrollPane sc = new JScrollPane(parameter);
            sc.setAlignmentX(LEFT_ALIGNMENT);
            sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 172));
            generic.add(sc);
            parameterBody.setBackground(CONFIG_BG);
            parameterBody.setAlignmentX(LEFT_ALIGNMENT);
            parameterBody.add(generic, "generic");
            parameterBody.add(ioPanel, "io");
            parameterBody.add(batchPanel, "batch");
            parameterBody.add(subgraphPanel, "subgraph");
            form.add(parameterBody);
            form.add(Box.createVerticalStrut(22));
            form.add(label("端口约定", 12, MUTED, true));
            form.add(Box.createVerticalStrut(8));
            form.add(typeLabel);
            form.add(Box.createVerticalStrut(24));
            form.add(label("支持文件与处理能力", 12, MUTED, true));
            form.add(Box.createVerticalStrut(8));
            form.add(capabilityInfo);
            form.add(Box.createVerticalStrut(16));
            JButton discover = button("工具与支持格式", false, this::discoverCapabilities);
            discover.setAlignmentX(LEFT_ALIGNMENT);
            form.add(discover);
            form.add(Box.createVerticalStrut(16));
            form.add(text("配置会随流程保存。\n文件传递、批量PDF、批量改名可运行。", 252));
            JScrollPane formScroll = new JScrollPane(form);
            formScroll.setBorder(null);
            formScroll.getViewport().setBackground(CONFIG_BG);
            formScroll.getVerticalScrollBar().setUnitIncrement(24);
            inspectorBody.add(formScroll, "form");
            inspector.add(inspectorBody, BorderLayout.CENTER);
            inspector.add(label("配置修改自动保留在当前画布", 11, MUTED, false), BorderLayout.SOUTH);
            return inspector;
        }

        JPanel runtimePanel() {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(LOG_BG);
            p.setPreferredSize(new Dimension(400, 172));
            p.setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(3, 0, 0, 0, new Color(139, 188, 171)),
                            new EmptyBorder(12, 18, 9, 18)));
            JPanel top = new JPanel(new BorderLayout());
            top.setBackground(LOG_BG);
            top.add(label("操作记录", 13, new Color(35, 99, 78), true), BorderLayout.WEST);
            top.add(label("文件传递 / PDF / 批量改名", 11, MUTED, false), BorderLayout.EAST);
            p.add(top, BorderLayout.NORTH);
            events.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            events.setEditable(false);
            events.setForeground(MUTED);
            events.setBackground(LOG_BG);
            events.setLineWrap(true);
            events.setWrapStyleWord(true);
            events.setBorder(new EmptyBorder(8, 0, 5, 0));
            JScrollPane sc = new JScrollPane(events);
            sc.setBorder(null);
            sc.getViewport().setBackground(LOG_BG);
            p.add(sc, BorderLayout.CENTER);
            p.add(status, BorderLayout.SOUTH);
            return p;
        }

        void demo() {
            clearNavigation();
            flow = new Flow();
            Node a = flow.add(Kind.TEXT, 62, 135),
                    b = flow.add(Kind.AI, 322, 135),
                    c = flow.add(Kind.PREVIEW, 582, 135);
            a.value = "会议记录：本周先完成 Java 工作流界面，再验证信息传递。";
            flow.connect(a, b);
            flow.connect(b, c);
            dirty = false;
            select(b);
            refresh();
            log("已载入示例流程：文本输入 → AI 文本总结 → 结果预览（仅示例配置）。");
            hint("中键拖动画布 · Shift+滚轮缩放 · 右键节点显示菜单。");
        }

        void fileDemo() {
            clearNavigation();
            flow = new Flow();
            flow.title = "文件输入与输出";
            Node input = flow.add(Kind.FILE, 62, 135), output = flow.add(Kind.SAVE, 382, 135);
            input.settings.put("mode", "FILE");
            flow.connect(input, output);
            dirty = false;
            currentFile = null;
            events.setText("");
            select(input);
            refresh();
            log("已载入文件输入与输出配置示例，请选择源文件和目标目录。");
            hint("点击文件输入或文件输出节点，编辑对应配置。");
        }

        void chooseExample() {
            String[] choices = {
                "文件复制",
                "逐文件PDF",
                "合并PDF",
                "批量改名（可选CSV）",
                "Java编译打包（BAT）",
                "提取MP3（EXE）",
                "Excel合并（Python）",
                "子图复用",
                "文件汇合与多输出",
                "AI文本处理"
            };
            Object selectedExample =
                    JOptionPane.showInputDialog(
                            this,
                            "选择示例，随后配置源文件和保存位置。",
                            "流程示例",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            choices,
                            choices[1]);
            if (selectedExample == null || !discard()) return;
            if (selectedExample.equals(choices[0])) {
                fileDemo();
                return;
            }
            if (selectedExample.equals(choices[7])) {
                subgraphDemo();
                return;
            }
            if (selectedExample.equals(choices[8])) {
                mergeDemo();
                return;
            }
            if (selectedExample.equals(choices[9])) {
                aiDemo();
                return;
            }
            int exampleIndex = List.of(choices).indexOf(selectedExample);
            batchDemo(
                    switch (exampleIndex) {
                        case 3 -> Kind.RENAME;
                        case 4 -> Kind.JAVA_BUILD;
                        case 5 -> Kind.AUDIO;
                        case 6 -> Kind.EXCEL_MERGE;
                        default -> Kind.PDF;
                    },
                    exampleIndex == 2);
        }

        void aiDemo() {
            clearNavigation();
            flow = new Flow();
            flow.title = "AI文本处理与输出";
            Node input = flow.add(Kind.TEXT, 60, 130),
                    ai = flow.add(Kind.AI, 340, 130),
                    out = flow.add(Kind.SAVE, 620, 130);
            input.value = "在这里填写需要AI处理的内容。";
            flow.connect(input, ai);
            flow.connect(ai, out);
            dirty = false;
            currentFile = null;
            events.setText("");
            select(ai);
            refresh();
            hint("选择AI服务、填写Prompt，再设置文件输出目录。");
            log("AI示例：文本输入 → AI文本处理 → 文件输出。文件输入也可设置为文本读取后接入AI。");
        }

        void batchDemo(Kind kind, boolean merge) {
            clearNavigation();
            flow = new Flow();
            flow.title =
                    switch (kind) {
                        case RENAME -> "批量改名与CSV模板";
                        case JAVA_BUILD -> "Java源码编译打包";
                        case AUDIO -> "提取MP3音频";
                        case EXCEL_MERGE -> "Excel表格合并";
                        default -> merge ? "文件合并为PDF" : "逐文件生成PDF";
                    };
            Node data = flow.add(Kind.FILE, 50, 110),
                    work = flow.add(kind, 340, 110),
                    out = flow.add(Kind.SAVE, 630, 110);
            data.name = "待处理文件";
            data.settings.put("selectionMode", "TYPES");
            data.settings.put(
                    "extensions",
                    switch (kind) {
                        case PDF -> "txt,doc,docx,xls,xlsx,ppt,pptx,pdf,png,jpg";
                        case JAVA_BUILD -> "java";
                        case AUDIO -> String.join(",", new TreeSet<>(ExecutableTools.MEDIA));
                        case EXCEL_MERGE -> "xlsx,xlsm";
                        default -> "txt,docx,pdf,jpg,png";
                    });
            data.settings.put("recursive", kind == Kind.JAVA_BUILD);
            flow.connect(data, work);
            flow.connect(work, out);
            if (kind == Kind.PDF) work.settings.put("pdfMode", merge ? "MERGE" : "EACH");
            if (kind == Kind.RENAME) {
                Node csv = flow.add(Kind.FILE, 50, 340);
                csv.name = "CSV模板数据（可选）";
                flow.connect(csv, work, "csv");
            }
            dirty = false;
            currentFile = null;
            events.setText("");
            select(data);
            refresh();
            log(
                    "已载入"
                            + flow.title
                            + "，请配置源文件与结果位置。"
                            + (kind == Kind.RENAME ? "CSV无需使用时可删除对应输入节点和连线。" : ""));
        }

        void mergeDemo() {
            clearNavigation();
            flow = new Flow();
            flow.title = "文件汇合与多输出";
            Node a = flow.add(Kind.FILE, 50, 90), b = flow.add(Kind.FILE, 50, 300);
            a.name = "输入文件A";
            b.name = "输入文件B";
            Node join = flow.add(Kind.FILE_MERGE, 340, 180),
                    copy = flow.add(Kind.SAVE, 650, 70),
                    pdf = flow.add(Kind.PDF, 650, 330),
                    savePdf = flow.add(Kind.SAVE, 940, 330);
            copy.name = "原文件保存";
            savePdf.name = "PDF保存";
            pdf.settings.put("pdfMode", "MERGE");
            flow.connect(a, "data", join, "input1", true);
            flow.connect(b, "data", join, "input2", true);
            flow.connect(join, copy);
            flow.connect(join, pdf);
            flow.connect(pdf, savePdf);
            dirty = false;
            currentFile = null;
            select(a);
            resetView();
            refresh();
            log("配置两个输入文件和两处保存目录；汇合后分别原样保存、合并PDF。");
        }

        void subgraphDemo() {
            clearNavigation();
            flow = new Flow();
            flow.title = "内置子图与虚拟接口";
            Node in = flow.add(Kind.FILE, 50, 130),
                    module = flow.add(Kind.SUBGRAPH, 340, 130),
                    out = flow.add(Kind.SAVE, 650, 130);
            in.settings.put("selectionMode", "TYPES");
            in.settings.put("extensions", "txt,pdf,jpg");
            flow.connect(in, "data", module, "input", true);
            flow.connect(module, "output", out, "data", true);
            dirty = false;
            currentFile = null;
            select(module);
            refresh();
            log("子图示例：虚拟输入→改名→虚拟输出，双击模块编辑内部步骤。");
        }

        void addNode(Kind kind) {
            if (kind == Kind.SCRIPT) {
                ScriptTools.choose(this);
                return;
            }
            Rectangle visible = canvas.getVisibleRect();
            double x =
                    Math.max(
                            25,
                            (visible.x - canvas.panX) / canvas.zoom
                                    + 70
                                    + (flow.nodes.size() % 3) * 45);
            double y =
                    Math.max(
                            60,
                            (visible.y - canvas.panY) / canvas.zoom
                                    + 70
                                    + (flow.nodes.size() % 5) * 48);
            Node n = flow.add(kind, x, y);
            select(n);
            changed();
            log("添加节点：" + kind.label);
        }

        void select(Node n) {
            selected = n;
            selectedEdge = null;
            updating = true;
            if (n == null) {
                inspectorTitle.setText("节点配置");
                inspectorCards.show(inspectorBody, "empty");
            } else {
                inspectorTitle.setText(n.kind.group + "元素配置");
                nodeName.setText(n.name);
                parameter.setText(n.value);
                parameterLabel.setText(n.kind.parameter);
                if (n.kind == Kind.FILE || n.kind == Kind.SAVE) {
                    ioPanel.load(n);
                    parameterCards.show(parameterBody, "io");
                    parameterBody.setPreferredSize(ioPanel.getPreferredSize());
                } else if (n.kind == Kind.SUBGRAPH || Subgraphs.boundary(n.kind)) {
                    subgraphPanel.load(n);
                    parameterCards.show(parameterBody, "subgraph");
                    parameterBody.setPreferredSize(subgraphPanel.getPreferredSize());
                } else if (ExecutableTools.WORK.contains(n.kind)) {
                    batchPanel.load(n);
                    parameterCards.show(parameterBody, "batch");
                    parameterBody.setPreferredSize(batchPanel.getPreferredSize());
                } else {
                    parameterCards.show(parameterBody, "generic");
                    parameterBody.setPreferredSize(new Dimension(250, 202));
                }
                updatePortLabel();
                capabilityInfo.setText(ElementCapabilities.summary(n));
                inspectorCards.show(inspectorBody, "form");
            }
            updating = false;
            canvas.repaint();
        }

        void updatePortLabel() {
            if (selected != null)
                typeLabel.setText(
                        (selected.kind.input == null ? "无输入" : "输入 " + selected.kind.input)
                                + "   /   "
                                + (selected.outputType() == null
                                        ? "无输出"
                                        : "输出 " + selected.outputType()));
            if (selected != null) capabilityInfo.setText(ElementCapabilities.summary(selected));
            if (selected != null && selected.kind == Kind.RENAME)
                typeLabel.setText(
                        "<html>文件 FILE（必需） / CSV FILE（可选）<br>输出 FILE（数量随输入，运行时检查）</html>");
            if (selected != null
                    && (selected.kind == Kind.FILE_MERGE
                            || selected.kind == Kind.SUBGRAPH
                            || Subgraphs.boundary(selected.kind)))
                typeLabel.setText(
                        "<html>输入："
                                + selected.inputPorts().stream()
                                        .map(p -> p.name() + " " + p.type())
                                        .reduce((a, b) -> a + ", " + b)
                                        .orElse("无")
                                + "<br>输出："
                                + selected.outputPorts().stream()
                                        .map(p -> p.name() + " " + p.type())
                                        .reduce((a, b) -> a + ", " + b)
                                        .orElse("无")
                                + "</html>");
        }

        void inspectSourceTypes() {
            if (selected == null || selected.kind != Kind.FILE) return;
            Map<String, Object> settings = new LinkedHashMap<>(selected.settings);
            String sourceName = selected.name;
            Map<String, ElementCapabilities.Contract> targets = new LinkedHashMap<>();
            for (Edge e : flow.edges)
                if (e.from.equals(selected.id)) {
                    Node target = flow.node(e.to);
                    targets.put(
                            target.name + " / " + e.port,
                            ElementCapabilities.inputContract(target, e.port));
                }
            log("开始检测文件类型：" + sourceName);
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception {
                    Set<String> extensions = new LinkedHashSet<>();
                    for (String extension : ((String) settings.get("extensions")).split("[,;\\s]+"))
                        if (!extension.isBlank())
                            extensions.add(
                                    extension.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT));
                    List<Path> paths =
                            FileInputTool.selectFiles(
                                    Path.of((String) settings.get("sourcePath")),
                                    (String) settings.get("selectionMode"),
                                    extensions,
                                    (Boolean) settings.get("recursive"));
                    StringBuilder report = new StringBuilder();
                    int mismatches = 0, unresolved = 0, index = 0;
                    for (Path file : paths) {
                        FileTypeDetector.Detection detection = FileTypeDetector.detect(file);
                        if (detection.state().equals("MISMATCH")) mismatches++;
                        if (detection.state().equals("UNRESOLVED")
                                || detection.state().equals("UNREADABLE")) unresolved++;
                        if (index++ < 50) {
                            report.append(file.getFileName())
                                    .append("  →  ")
                                    .append(detection.format())
                                    .append(" / ")
                                    .append(detection.state())
                                    .append('\n')
                                    .append(detection.message())
                                    .append('\n');
                            for (var target : targets.entrySet()) {
                                String reason =
                                        ElementCapabilities.rejection(target.getValue(), detection);
                                report.append("  ")
                                        .append(target.getKey())
                                        .append(": ")
                                        .append(reason == null ? "文件类型可接受" : reason)
                                        .append(target.getValue().implemented() ? "" : "（处理器尚未实现）")
                                        .append('\n');
                            }
                            report.append('\n');
                        }
                    }
                    return "检测 "
                            + paths.size()
                            + " 个文件；后缀不一致 "
                            + mismatches
                            + " 个，待确认 "
                            + unresolved
                            + " 个。\n检测是类型识别，不是完整格式或版式验证。\n预览最多 50 项。\n\n"
                            + report;
                }

                protected void done() {
                    try {
                        String report = get();
                        JTextArea area = new JTextArea(report, 22, 62);
                        area.setEditable(false);
                        area.setFont(BODY);
                        area.setLineWrap(true);
                        area.setWrapStyleWord(true);
                        area.setCaretPosition(0);
                        JOptionPane.showMessageDialog(
                                Editor.this,
                                new JScrollPane(area),
                                "文件类型检测",
                                JOptionPane.INFORMATION_MESSAGE);
                        log(report.substring(0, report.indexOf('\n')));
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        log("类型检测失败：" + cause.getMessage());
                        JOptionPane.showMessageDialog(
                                Editor.this,
                                cause.getMessage(),
                                "类型检测失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }

        void discoverCapabilities() {
            log("查询独立工具的 --capabilities 声明。");
            new SwingWorker<List<Map<String, Object>>, Void>() {
                protected List<Map<String, Object>> doInBackground() throws Exception {
                    List<Map<String, Object>> found = new ArrayList<>();
                    for (String tool : ExecutableTools.all())
                        found.add(ElementCapabilities.discover(FileTransferRunner.toolPath(tool)));
                    return found;
                }

                protected void done() {
                    try {
                        List<Map<String, Object>> found = get();
                        updatePortLabel();
                        ToolCatalogPanel.show(Editor.this, found);
                        log("工具列表已更新，支持格式声明已用于运行校验。");
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        log("能力发现失败：" + cause.getMessage());
                        JOptionPane.showMessageDialog(
                                Editor.this,
                                cause.getMessage(),
                                "工具列表更新失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }

        void importTool() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择脚本可执行工具进行初步校验");
            chooser.setFileFilter(
                    new javax.swing.filechooser.FileNameExtensionFilter(
                            "工具 (*.exe, *.py, *.bat, *.cmd, *.jar)",
                            "exe",
                            "py",
                            "bat",
                            "cmd",
                            "jar"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath();
            log("查询工具帮助、描述与能力：" + path);
            new SwingWorker<Map<String, Object>, Void>() {
                protected Map<String, Object> doInBackground() throws Exception {
                    return ToolLauncher.probe(path);
                }

                protected void done() {
                    try {
                        Map<String, Object> result = get();
                        Path registry = Path.of("tool-registry");
                        Files.createDirectories(registry);
                        Path record = registry.resolve(UUID.randomUUID() + ".json");
                        Json.write(record, result);
                        JTextArea area = new JTextArea(Json.stringify(result), 18, 60);
                        area.setEditable(false);
                        area.setFont(BODY);
                        area.setLineWrap(true);
                        area.setWrapStyleWord(true);
                        JOptionPane.showMessageDialog(
                                Editor.this,
                                new JScrollPane(area),
                                "工具初步校验通过",
                                JOptionPane.INFORMATION_MESSAGE);
                        log("工具查询协议通过，记录：" + record + "；尚未代表全部业务行为通过测试。");
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        error("工具校验失败", new IOException(cause.getMessage(), cause));
                    }
                }
            }.execute();
        }

        /**
         * 先复制整图再后台运行，隔离运行中对画布的编辑；完成通知返回事件线程。
         *
         * <p>Runs a graph snapshot in a worker so editor changes do not alter the active run.
         */
        void runFileFlow() {
            if (executing || moduleEditor) return;
            Flow snapshot = new Flow();
            snapshot.title = flow.title;
            for (Node n : flow.nodes) {
                Node copy = new Node(n.id, n.kind, n.x, n.y);
                copy.name = n.name;
                copy.value = n.value;
                copy.settings.clear();
                copy.settings.putAll(n.settings);
                snapshot.nodes.add(copy);
                copy.child = Subgraphs.copy(n.child);
            }
            snapshot.edges.addAll(flow.edges);
            executing = true;
            runButton.setEnabled(false);
            log("开始批处理，请以运行记录确认最终结果。");
            new SwingWorker<FileTransferRunner.Result, Void>() {
                boolean suppressWarnings;

                protected FileTransferRunner.Result doInBackground() throws Exception {
                    return BatchRunner.execute(
                            snapshot,
                            new SystemSettings(folderSettings).runs(),
                            messages -> {
                                if (suppressWarnings) return;
                                java.util.concurrent.FutureTask<Boolean> notice =
                                        new java.util.concurrent.FutureTask<>(
                                                () -> {
                                                    JTextArea area =
                                                            new JTextArea(
                                                                    String.join(
                                                                            "\n",
                                                                            messages.subList(
                                                                                    0,
                                                                                    Math.min(
                                                                                            30,
                                                                                            messages
                                                                                                    .size()))),
                                                                    10,
                                                                    45);
                                                    area.setEditable(false);
                                                    area.setFont(BODY);
                                                    JCheckBox silence = new JCheckBox("本次不再提示");
                                                    JPanel panel =
                                                            new JPanel(new BorderLayout(0, 12));
                                                    panel.add(
                                                            new JScrollPane(area),
                                                            BorderLayout.CENTER);
                                                    panel.add(silence, BorderLayout.SOUTH);
                                                    JOptionPane.showMessageDialog(
                                                            Editor.this,
                                                            panel,
                                                            "处理提示",
                                                            JOptionPane.INFORMATION_MESSAGE);
                                                    return silence.isSelected();
                                                });
                                // 后台任务等待用户提示，但弹窗只能在事件线程显示。 Show notices on the EDT.
                                SwingUtilities.invokeLater(notice);
                                try {
                                    suppressWarnings = notice.get();
                                } catch (Exception ex) {
                                    throw new IOException("字段提示失败", ex);
                                }
                            });
                }

                protected void done() {
                    executing = false;
                    runButton.setEnabled(true);
                    try {
                        FileTransferRunner.Result result = get();
                        log(
                                "成功："
                                        + (result.operation().equals("MIXED")
                                                ? "复制/移动混合"
                                                : result.operation())
                                        + " "
                                        + result.files().size()
                                        + " 个文件。");
                        for (FileTransferRunner.Published p : result.files())
                            log("已保存：" + p.destination());
                        log("运行记录：" + result.record());
                        hint("批处理成功。");
                        openOutputFolders(result.foldersToOpen());
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        log("执行失败：" + cause.getMessage());
                        JOptionPane.showMessageDialog(
                                Editor.this,
                                cause.getMessage(),
                                "批处理失败",
                                JOptionPane.ERROR_MESSAGE);
                        hint("执行失败，详见操作记录。");
                    }
                }
            }.execute();
        }

        void changed() {
            dirty = true;
            refresh();
        }

        /** 发布成功后打开目录；打开失败不改变处理结果。 Folder opening does not change run success. */
        void openOutputFolders(List<Path> folders) {
            if (folders.isEmpty()) return;
            new SwingWorker<Void, String>() {
                protected Void doInBackground() {
                    for (Path folder : folders)
                        try {
                            if (!Desktop.isDesktopSupported()
                                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                                throw new IOException("当前环境不支持打开文件夹");
                            Desktop.getDesktop().open(folder.toFile());
                            publish("已打开目标文件夹：" + folder);
                        } catch (Exception ex) {
                            publish("文件已保存，打开目标文件夹失败：" + folder + " · " + ex.getMessage());
                        }
                    return null;
                }

                protected void process(List<String> messages) {
                    for (String message : messages) log(message);
                }
            }.execute();
        }

        /**
         * 在同一画布进入选中节点的 child，并把父图视图压入导航栈。
         *
         * <p>Enters the selected child in the same canvas, pushing the parent view onto the stack.
         */
        void editSubgraph() {
            if (selected == null || selected.kind != Kind.SUBGRAPH || executing) return;
            if (levels.size() >= 8) {
                hint("子图最多支持8层嵌套。");
                return;
            }
            Node owner = selected;
            levels.add(
                    new CanvasLevel(
                            flow,
                            owner,
                            canvas.zoom,
                            canvas.panX,
                            canvas.panY,
                            new Point(canvasScroll.getViewport().getViewPosition())));
            flow = owner.child;
            moduleEditor = true;
            clearCanvasInteraction();
            select(null);
            resetView();
            refresh();
            log("进入子图画布：" + owner.name);
            hint("子图编辑自动保留；返回上一级继续连接，主画布运行整个流程。");
        }

        /** 由子图层重接边界连线并生成虚拟接口。 Delegates boundary rewiring to the subgraph layer. */
        void encapsulateSteps() {
            var candidates =
                    flow.nodes.stream()
                            .filter(
                                    n ->
                                            ExecutableTools.WORK.contains(n.kind)
                                                    || n.kind == Kind.SUBGRAPH)
                            .toList();
            if (candidates.isEmpty()) {
                hint("先添加并连接要封装的工作步骤。");
                return;
            }
            JList<String> list =
                    new JList<>(
                            candidates.stream()
                                    .map(n -> n.name + " · " + n.kind.label)
                                    .toArray(String[]::new));
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            if (selected != null && candidates.contains(selected))
                list.setSelectedIndex(candidates.indexOf(selected));
            if (JOptionPane.showConfirmDialog(
                            this,
                            new JScrollPane(list),
                            "选择要封装的步骤（Ctrl/Shift多选）",
                            JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) return;
            Set<String> ids = new HashSet<>();
            for (int i : list.getSelectedIndices()) ids.add(candidates.get(i).id);
            try {
                Node module = Subgraphs.encapsulate(flow, ids);
                select(module);
                changed();
                log("已封装" + ids.size() + "个步骤，自动生成并绑定虚拟接口。");
            } catch (IOException ex) {
                error("封装失败", ex);
            }
        }

        void refresh() {
            refreshNavigation();
            title.setText(flow.title + (dirty ? " *" : ""));
            count.setText("  " + flow.nodes.size() + " 节点 / " + flow.edges.size() + " 连线");
            canvas.updateSize();
            canvas.repaint();
        }

        void log(String value) {
            events.append(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                            + "  "
                            + value
                            + "\n");
            events.setCaretPosition(events.getDocument().getLength());
        }

        void hint(String value) {
            status.setText(value);
        }

        void deleteSelection() {
            if (selected != null) {
                String name = selected.name;
                flow.remove(selected);
                select(null);
                canvas.pending = null;
                changed();
                log("删除节点及关联连线：" + name);
            } else if (selectedEdge != null) {
                flow.edges.remove(selectedEdge);
                selectedEdge = null;
                changed();
                log("删除连线。");
            } else hint("请先选中节点或连线。");
        }

        void copySelection() {
            if (selected == null) {
                hint("请先选中要复制的节点。");
                return;
            }
            clipboard = NodeCopy.of(selected);
            pasteCount = 0;
            log("复制节点：" + selected.name);
            hint("节点已复制，Ctrl+V 粘贴；连线不随单节点复制。");
        }

        void cutSelection() {
            if (selected == null) {
                hint("请先选中要剪切的节点。");
                return;
            }
            Node n = selected;
            clipboard = NodeCopy.of(n);
            pasteCount = 0;
            flow.remove(n);
            select(null);
            canvas.pending = null;
            changed();
            log("剪切节点并移除关联连线：" + n.name);
            hint("节点已剪切，Ctrl+V 粘贴。");
        }

        void paste(Point2D location) {
            if (clipboard == null) {
                hint("尚未复制或剪切节点。");
                return;
            }
            pasteCount++;
            double x = location == null ? clipboard.x + 32 * pasteCount : location.getX();
            double y = location == null ? clipboard.y + 32 * pasteCount : location.getY();
            if (location == null && !canvas.getVisibleRect().intersects(canvas.screenRect(x, y))) {
                Rectangle view = canvas.getVisibleRect();
                x = (view.getCenterX() - canvas.panX) / canvas.zoom - Canvas.W / 2.0;
                y = (view.getCenterY() - canvas.panY) / canvas.zoom - Canvas.H / 2.0;
            }
            insertCopy(clipboard, x, y);
            log("粘贴节点：" + clipboard.name);
        }

        void duplicateSelection() {
            if (selected == null) {
                hint("请先选中要创建副本的节点。");
                return;
            }
            NodeCopy copy = NodeCopy.of(selected);
            insertCopy(copy, copy.x + 32, copy.y + 32);
            log("创建节点副本：" + copy.name);
        }

        void insertCopy(NodeCopy copy, double x, double y) {
            Node n = flow.add(copy.kind, Math.max(25, x), Math.max(60, y));
            n.name = copy.name;
            n.value = copy.value;
            n.settings.clear();
            n.settings.putAll(copy.settings);
            select(n);
            changed();
            canvas.requestFocusInWindow();
            n.child = Subgraphs.copy(copy.child);
            select(n);
        }

        boolean discard() {
            return !dirty
                    || JOptionPane.showConfirmDialog(
                                    this,
                                    "当前流程有未保存修改，是否放弃这些修改？",
                                    "未保存修改",
                                    JOptionPane.YES_NO_OPTION)
                            == JOptionPane.YES_OPTION;
        }

        void fresh() {
            if (!discard()) return;
            clearNavigation();
            flow = new Flow();
            currentFile = null;
            dirty = false;
            canvas.pending = null;
            select(null);
            resetView();
            refresh();
            log("创建空白工作流。");
        }

        void rename() {
            String name = JOptionPane.showInputDialog(this, "工作流名称", flow.title);
            if (name != null && !name.isBlank()) {
                flow.title = name.strip();
                changed();
                log("重命名工作流：" + flow.title);
            }
        }

        Path folderSettings = Path.of("user-settings.properties");

        File workflowDirectory() {
            Properties settings = new Properties();
            if (Files.isRegularFile(folderSettings))
                try (InputStream in = Files.newInputStream(folderSettings)) {
                    settings.load(in);
                } catch (IOException ignored) {
                }
            String last = settings.getProperty("workflow.directory", "");
            if (!last.isBlank()) {
                File folder = new File(last);
                if (folder.isDirectory()) return folder;
            }
            if (currentFile != null
                    && currentFile.toAbsolutePath().getParent().toFile().isDirectory())
                return currentFile.toAbsolutePath().getParent().toFile();
            return javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory();
        }

        void rememberDirectory(Path file) {
            Properties settings = new Properties();
            if (Files.isRegularFile(folderSettings))
                try (InputStream in = Files.newInputStream(folderSettings)) {
                    settings.load(in);
                } catch (IOException ignored) {
                }
            settings.setProperty(
                    "workflow.directory", file.toAbsolutePath().getParent().toString());
            try (OutputStream out = Files.newOutputStream(folderSettings)) {
                settings.store(out, "Workflow file chooser location");
            } catch (IOException ex) {
                log("无法记住文件夹：" + ex.getMessage());
            }
        }

        JFileChooser chooser() {
            JFileChooser fc = new JFileChooser(workflowDirectory());
            fc.setFileFilter(
                    new javax.swing.filechooser.FileNameExtensionFilter(
                            "流程或子图文件 (*.flow.xml)", "xml"));
            return fc;
        }

        void importFlow() {
            importToLibrary();
        }

        void importToLibrary() {
            if (executing) return;
            JFileChooser fc = chooser();
            fc.setDialogTitle("导入到流程库（复制文件，保留当前画布）");
            fc.setApproveButtonText("导入");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path source = fc.getSelectedFile().toPath();
            try {
                Path target = new FlowLibrary(folderSettings).importFile(source);
                rememberDirectory(source);
                log("已加入流程库：" + target);
                hint("导入完成，点击打开查看流程库。");
            } catch (IOException ex) {
                error("导入流程库失败", ex);
            }
        }

        void importIntoCanvas() {
            if (executing) return;
            JFileChooser fc = chooser();
            fc.setDialogTitle("导入流程或子图（保留当前画布）");
            fc.setApproveButtonText("导入");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = fc.getSelectedFile().toPath();
            try {
                importFlowFile(path);
                rememberDirectory(path);
            } catch (IOException | IllegalArgumentException ex) {
                error("导入失败，当前画布已保留", ex);
            }
        }

        /**
         * 导入在候选副本上重映射标识；通过嵌套检查后再把新增节点加入当前画布。
         *
         * <p>Remaps imported IDs in a candidate copy before appending the validated additions.
         */
        void importFlowFile(Path path) throws IOException {
            Flow loaded = Flow.load(path);
            boolean module = loaded.nodes.stream().anyMatch(n -> Subgraphs.boundary(n.kind));
            if (module) Subgraphs.validateInterfaces(loaded);
            else if (moduleEditor && loaded.nodes.stream().anyMatch(n -> n.kind == Kind.SAVE))
                throw new IOException("完整流程含文件输出，请返回主画布导入。子图内部请使用虚拟输出。");
            if (loaded.nodes.isEmpty()) throw new IOException("文件中没有可导入节点。");
            Rectangle visible = canvas.getVisibleRect();
            double x = Math.max(25, (visible.x - canvas.panX) / canvas.zoom + 70),
                    y = Math.max(60, (visible.y - canvas.panY) / canvas.zoom + 70);
            Flow candidate = Subgraphs.copy(flow);
            Node focus;
            if (module) {
                focus = candidate.add(Kind.SUBGRAPH, x, y);
                focus.name = loaded.title;
                focus.child = Subgraphs.copy(loaded);
                focus.settings.put("originFile", path.toAbsolutePath().toString());
            } else {
                Map<String, String> ids = new HashMap<>();
                focus = null;
                double minX = loaded.nodes.stream().mapToDouble(n -> n.x).min().orElse(0),
                        minY = loaded.nodes.stream().mapToDouble(n -> n.y).min().orElse(0);
                for (Node original : loaded.nodes) {
                    Node n =
                            new Node(
                                    UUID.randomUUID().toString(),
                                    original.kind,
                                    x + original.x - minX,
                                    y + original.y - minY);
                    n.name = original.name;
                    n.value = original.value;
                    n.settings.clear();
                    n.settings.putAll(original.settings);
                    n.child = Subgraphs.copy(original.child);
                    candidate.nodes.add(n);
                    if (ids.put(original.id, n.id) != null) throw new IOException("导入文件包含重复节点标识。");
                    if (focus == null) focus = n;
                }
                for (Edge e : loaded.edges) {
                    if (!ids.containsKey(e.from) || !ids.containsKey(e.to))
                        throw new IOException("导入文件包含失效连线。");
                    candidate.edges.add(
                            new Edge(ids.get(e.from), ids.get(e.to), e.port, e.fromPort));
                }
            }
            // 将候选图接回祖先链后检查总深度。 Validate nesting with the current ancestors before adding.
            Flow whole = candidate;
            for (int i = levels.size() - 1; i >= 0; i--) {
                CanvasLevel level = levels.get(i);
                Flow parent = Subgraphs.copy(level.parent());
                parent.node(level.owner().id).child = whole;
                whole = parent;
            }
            Subgraphs.copy(whole);
            int oldCount = flow.nodes.size();
            flow.nodes.addAll(candidate.nodes.subList(oldCount, candidate.nodes.size()));
            flow.edges.addAll(candidate.edges.subList(flow.edges.size(), candidate.edges.size()));
            clearCanvasInteraction();
            select(focus);
            changed();
            log("已导入" + (module ? "子图实例" : "流程节点") + "：" + path.toAbsolutePath() + "；原文件不受修改。");
            hint("导入完成，原有节点保留；保存仍写入当前流程。");
        }

        /** 当前显示子图时也保存根图，currentFile 对应完整流程。 Persists the root even from a child view. */
        void save() {
            Path destination = currentFile;
            if (destination == null) {
                JFileChooser fc = chooser();
                try {
                    fc.setCurrentDirectory(new FlowLibrary(folderSettings).directory().toFile());
                } catch (IOException ex) {
                    error("无法访问流程库", ex);
                    return;
                }
                fc.setDialogTitle("保存流程到流程库");
                fc.setSelectedFile(new File(fc.getCurrentDirectory(), "workflow.flow.xml"));
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                destination = fc.getSelectedFile().toPath();
                if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    destination = Path.of(destination + ".flow.xml");
                if (Files.exists(destination)
                        && JOptionPane.showConfirmDialog(
                                        this, "文件已存在，是否覆盖？", "保存流程", JOptionPane.YES_NO_OPTION)
                                != JOptionPane.YES_OPTION) return;
            }
            try {
                rootFlow().save(destination);
                currentFile = destination;
                rememberDirectory(destination);
                dirty = false;
                refresh();
                log("流程已保存：" + destination.toAbsolutePath());
                hint("保存成功，重新打开可恢复节点、连线和参数。");
            } catch (IOException ex) {
                error("保存失败", ex);
            }
        }

        void open() {
            if (!executing) FlowLibraryPanel.show(this);
        }

        /**
         * 先读取文件，再询问是否放弃当前编辑，随后切换根画布。
         *
         * <p>Loads the file before the discard prompt, then switches the editor to its root graph.
         */
        boolean openFlowFile(Path path) throws IOException {
            Flow loaded = Flow.load(path);
            if (!discard()) return false;
            clearNavigation();
            flow = loaded;
            currentFile = path;
            dirty = false;
            select(null);
            resetView();
            refresh();
            log("流程已打开：" + path.toAbsolutePath());
            return true;
        }

        void error(String name, Exception ex) {
            log(name + "：" + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(), name, JOptionPane.ERROR_MESSAGE);
        }

        void zoom(double step) {
            Rectangle view = canvas.getVisibleRect();
            zoomAt(step, new Point2D.Double(view.getCenterX(), view.getCenterY()));
        }

        /** 保持鼠标锚点对应的图坐标不变。 Keeps the world point beneath the zoom anchor fixed. */
        void zoomAt(double step, Point2D anchor) {
            if (canvas.panning || canvas.dragging != null) return;
            double previous = canvas.zoom;
            double next = Math.max(.5, Math.min(1.5, Math.round((previous + step) * 10) / 10.0));
            if (next == previous) return;
            canvas.panX = anchor.getX() - (anchor.getX() - canvas.panX) * next / previous;
            canvas.panY = anchor.getY() - (anchor.getY() - canvas.panY) * next / previous;
            canvas.zoom = next;
            zoomLabel.setText((int) Math.round(next * 100) + "%");
            canvas.updateSize();
            canvas.repaint();
        }

        void resetView() {
            canvas.zoom = 1;
            canvas.panX = 0;
            canvas.panY = 0;
            zoomLabel.setText("100%");
            canvasScroll.getViewport().setViewPosition(new Point());
            canvas.updateSize();
            canvas.repaint();
        }

        void check() {
            List<String> problems = new ArrayList<>();
            if (flow.nodes.isEmpty()) problems.add("画布中没有节点。");
            for (Node n : flow.nodes) {
                if (n.name.isBlank()) problems.add(n.kind.label + "：名称为空。");
                for (var port : n.inputPorts())
                    if (port.required()
                            && flow.edges.stream()
                                    .noneMatch(e -> e.to.equals(n.id) && e.port.equals(port.name()))
                            && !(n.kind == Kind.SUBGRAPH
                                    && n.child.nodes.stream()
                                            .anyMatch(
                                                    v ->
                                                            v.kind == Kind.VIRTUAL_IN
                                                                    && v.settings
                                                                            .get("interfaceName")
                                                                            .equals(port.name())
                                                                    && !((String)
                                                                                    v.settings.get(
                                                                                            "defaultValue"))
                                                                            .isBlank())))
                        problems.add(n.name + "：必需输入 " + port.name() + " 未连接。");
                if (n.kind.output != null
                        && flow.edges.stream().noneMatch(e -> e.from.equals(n.id)))
                    problems.add(n.name + "：输出端口未连接。");
                if (n.kind == Kind.FILE
                        || n.kind == Kind.SAVE
                        || ExecutableTools.WORK.contains(n.kind))
                    problems.addAll(IoConfig.diagnostics(n));
                else if (n.value.isBlank()) problems.add(n.name + "：参数尚未填写。");
                if (Subgraphs.boundary(n.kind) && !moduleEditor)
                    problems.add(n.name + "：虚拟接口应放在子图内部。");
                if (Subgraphs.boundary(n.kind)) problems.addAll(IoConfig.diagnostics(n));
                if (n.kind == Kind.SUBGRAPH)
                    try {
                        Subgraphs.validateInterfaces(n.child);
                    } catch (IOException ex) {
                        problems.add(n.name + "：" + ex.getMessage());
                    }
                if ((n.kind == Kind.READ || n.kind == Kind.PDF)
                        && !ElementCapabilities.inputContract(n).implemented())
                    problems.add(n.name + "：仅有格式声明，处理器尚未实现。");
            }
            for (Edge edge : flow.edges) {
                Node from = flow.node(edge.from), to = flow.node(edge.to);
                if (Flow.incompatible(from, edge.fromPort, to, edge.port)) {
                    String error = ElementCapabilities.connectionError(from, to);
                    problems.add(
                            from.name
                                    + " → "
                                    + to.name
                                    + "："
                                    + (error == null ? "数据类型不兼容。" : error));
                }
                if (to.kind == Kind.SAVE
                        && !IoConfig.accepts(from.outputType(), (String) to.settings.get("format")))
                    problems.add(to.name + "：当前数据类型 " + from.outputType() + " 不支持所选输出形式。");
            }
            if (moduleEditor)
                try {
                    Subgraphs.validateInterfaces(flow);
                } catch (IOException ex) {
                    problems.add(ex.getMessage());
                }
            else if (flow.nodes.stream().anyMatch(n -> n.kind == Kind.SUBGRAPH))
                try {
                    Subgraphs.expand(flow);
                } catch (IOException ex) {
                    problems.add(ex.getMessage());
                }
            if (problems.isEmpty()) {
                log("结构与配置检查通过；运行时继续检查实际内容和转换器。");
                hint("结构检查通过。");
            } else {
                log("检查发现 " + problems.size() + " 项待完善内容。");
                JOptionPane.showMessageDialog(
                        this, String.join("\n", problems), "流程检查", JOptionPane.INFORMATION_MESSAGE);
            }
        }

        void installKeys() {
            bind("control S", "save", this::save);
            bind("control O", "open", this::open);
            bind("control shift O", "import-flow", this::importFlow);
            bind("control N", "new", this::fresh);
            bind(
                    "ESCAPE",
                    "cancel",
                    () -> {
                        canvas.pending = null;
                        canvas.repaint();
                        hint("连线操作已取消。");
                    });
            bind("control 0", "reset", this::resetView);
            bind("alt LEFT", "parent-canvas", this::leaveSubgraph);
            canvasKey("DELETE", "delete", this::deleteSelection);
            canvasKey("control X", "cut", this::cutSelection);
            canvasKey("control C", "copy", this::copySelection);
            canvasKey("control V", "paste", () -> paste(null));
            canvasKey("control D", "duplicate", this::duplicateSelection);
        }

        void canvasKey(String key, String name, Runnable action) {
            canvas.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
            canvas.getActionMap()
                    .put(
                            name,
                            new AbstractAction() {
                                public void actionPerformed(ActionEvent e) {
                                    action.run();
                                }
                            });
        }

        void bind(String key, String name, Runnable action) {
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(KeyStroke.getKeyStroke(key), name);
            getActionMap()
                    .put(
                            name,
                            new AbstractAction() {
                                public void actionPerformed(ActionEvent e) {
                                    action.run();
                                }
                            });
        }
    }

    /**
     * 负责绘制与命中测试；节点使用图坐标，平移和缩放只改变视图变换。
     *
     * <p>Rendering and hit testing use world coordinates; pan and zoom affect only the view
     * transform.
     */
    @SuppressWarnings("serial")
    static final class Canvas extends JPanel {
        private static final long serialVersionUID = 1L;
        static final int W = 202, H = 146;
        final Editor editor;
        double zoom = 1, panX, panY;
        Node pending, dragging;
        String pendingPort = "data";
        boolean panning, popupOnPress;
        Point panStart;
        double panStartX, panStartY;
        JPopupMenu lastPopup;
        Point2D cursor = new Point2D.Double();
        double dx, dy;
        boolean moved;

        Canvas(Editor editor) {
            this.editor = editor;
            setBackground(BG);
            setFocusable(true);
            MouseAdapter mouse =
                    new MouseAdapter() {
                        public void mousePressed(MouseEvent e) {
                            requestFocusInWindow();
                            Point2D p = point(e);
                            cursor = p;
                            if (SwingUtilities.isMiddleMouseButton(e)) {
                                panning = true;
                                dragging = null;
                                panStart = e.getPoint();
                                panStartX = panX;
                                panStartY = panY;
                                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                                return;
                            }
                            if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                                dragging = null;
                                pending = null;
                                editor.select(hit(p));
                                popupOnPress = e.isPopupTrigger();
                                if (popupOnPress) showContext(e);
                                repaint();
                                return;
                            }
                            if (!SwingUtilities.isLeftMouseButton(e)) return;
                            Node input = port(p, false), output = port(p, true);
                            if (input != null && pending != null) {
                                connect(input);
                                return;
                            }
                            if (output != null) {
                                pending = output;
                                pendingPort = portName(output, p, true);
                                editor.select(output);
                                editor.hint("点击或拖到目标的左侧输入端口；Esc 取消。");
                                repaint();
                                return;
                            }
                            Node n = hit(p);
                            editor.select(n);
                            if (n != null && n.kind == Kind.SUBGRAPH && e.getClickCount() == 2) {
                                editor.editSubgraph();
                                return;
                            }
                            if (n != null) {
                                dragging = n;
                                dx = p.getX() - n.x;
                                dy = p.getY() - n.y;
                                moved = false;
                            } else {
                                editor.selectedEdge = hitEdge(p);
                                if (editor.selectedEdge != null)
                                    editor.hint("已选中连线，按 Delete 或点击删除。");
                                repaint();
                            }
                        }

                        public void mouseDragged(MouseEvent e) {
                            if (panning) {
                                panX = panStartX + e.getX() - panStart.x;
                                panY = panStartY + e.getY() - panStart.y;
                                cursor = point(e);
                                repaint();
                                return;
                            }
                            cursor = point(e);
                            if (dragging != null) {
                                dragging.x = Math.max(25, cursor.getX() - dx);
                                dragging.y = Math.max(60, cursor.getY() - dy);
                                moved = true;
                                editor.changed();
                            }
                            repaint();
                        }

                        public void mouseReleased(MouseEvent e) {
                            if (SwingUtilities.isMiddleMouseButton(e)) {
                                panning = false;
                                setCursor(Cursor.getDefaultCursor());
                                editor.hint("中键拖动画布 · Shift+滚轮缩放 · 右键节点显示菜单。");
                                return;
                            }
                            if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                                if (!popupOnPress) showContext(e);
                                popupOnPress = false;
                                return;
                            }
                            if (!SwingUtilities.isLeftMouseButton(e)) return;
                            if (dragging != null && moved) editor.log("调整节点位置：" + dragging.name);
                            dragging = null;
                            if (pending != null) {
                                cursor = point(e);
                                Node target = port(cursor, false);
                                if (target != null) connect(target);
                            }
                        }

                        public void mouseMoved(MouseEvent e) {
                            cursor = point(e);
                            if (pending != null) repaint();
                        }
                    };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(
                    e -> {
                        if (e.isShiftDown()) {
                            editor.zoomAt(-e.getPreciseWheelRotation() * .1, e.getPoint());
                            e.consume();
                        } else {
                            JScrollBar bar = editor.canvasScroll.getVerticalScrollBar();
                            bar.setValue(
                                    bar.getValue() + e.getUnitsToScroll() * bar.getUnitIncrement());
                            e.consume();
                        }
                    });
        }

        void showContext(MouseEvent e) {
            Point2D location = point(e);
            editor.select(hit(location));
            lastPopup = contextMenu(location);
            if (isShowing()) lastPopup.show(this, e.getX(), e.getY());
        }

        JPopupMenu contextMenu(Point2D location) {
            JPopupMenu menu = new JPopupMenu();
            if (editor.selected != null) {
                menuItem(menu, "剪切", "control X", true, editor::cutSelection);
                menuItem(menu, "复制", "control C", true, editor::copySelection);
                menuItem(menu, "创建副本", "control D", true, editor::duplicateSelection);
                if (editor.selected.kind == Kind.SUBGRAPH)
                    menuItem(menu, "编辑子图", null, true, editor::editSubgraph);
                menu.addSeparator();
            }
            menuItem(
                    menu,
                    "粘贴",
                    "control V",
                    editor.clipboard != null,
                    () -> editor.paste(location));
            if (editor.selected != null) {
                menu.addSeparator();
                menuItem(menu, "删除", "DELETE", true, editor::deleteSelection);
            }
            return menu;
        }

        void menuItem(JPopupMenu menu, String name, String key, boolean enabled, Runnable action) {
            JMenuItem item = new JMenuItem(name);
            item.setAccelerator(KeyStroke.getKeyStroke(key));
            item.setEnabled(enabled);
            item.addActionListener(e -> action.run());
            menu.add(item);
        }

        void connect(Node target) {
            String targetPort = portName(target, cursor, false);
            String error = editor.flow.connect(pending, pendingPort, target, targetPort, true);
            if (error != null) {
                editor.hint(error);
                editor.log("连线未创建：" + error);
            } else {
                editor.log("连接：" + pending.name + " → " + target.name);
                editor.changed();
                editor.hint("连线已创建。");
            }
            pending = null;
            repaint();
        }

        Point2D point(MouseEvent e) {
            return world(e.getPoint());
        }

        /** 把屏幕交互位置映射回节点坐标系。 Inverts the view transform for pointer interactions. */
        Point2D world(Point2D p) {
            return new Point2D.Double((p.getX() - panX) / zoom, (p.getY() - panY) / zoom);
        }

        Rectangle screenRect(double x, double y) {
            return new Rectangle(
                    (int) (x * zoom + panX),
                    (int) (y * zoom + panY),
                    (int) (W * zoom),
                    (int) (H * zoom));
        }

        Node port(Point2D p, boolean out) {
            List<Node> nodes = editor.flow.nodes;
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (portName(n, p, out) != null) return n;
            }
            return null;
        }

        String portName(Node n, Point2D p, boolean out) {
            var ports = out ? n.outputPorts() : n.inputPorts();
            for (int i = 0; i < ports.size(); i++)
                if (p.distance(n.x + (out ? W : 0), n.y + 84 + i * 24) <= 11)
                    return ports.get(i).name();
            return null;
        }

        static double portY(Node n, boolean out, String name) {
            var ports = out ? n.outputPorts() : n.inputPorts();
            for (int i = 0; i < ports.size(); i++)
                if (ports.get(i).name().equals(name)) return n.y + 84 + i * 24;
            return n.y + 84;
        }

        static int height(Node n) {
            return Math.max(H, 98 + 24 * Math.max(n.inputPorts().size(), n.outputPorts().size()));
        }

        Node hit(Point2D p) {
            List<Node> nodes = editor.flow.nodes;
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (new Rectangle2D.Double(n.x, n.y, W, height(n)).contains(p)) return n;
            }
            return null;
        }

        CubicCurve2D curve(Node a, Node b) {
            return curve(a, b, "data");
        }

        CubicCurve2D curve(Node a, Node b, String port) {
            return curve(a, "data", b, port);
        }

        CubicCurve2D curve(Node a, String fromPort, Node b, String port) {
            double x = a.x + W, y = portY(a, true, fromPort), x2 = b.x, y2 = portY(b, false, port);
            double bend = Math.max(45, Math.abs(x2 - x) * .5);
            return new CubicCurve2D.Double(x, y, x + bend, y, x2 - bend, y2, x2, y2);
        }

        Edge hitEdge(Point2D p) {
            for (Edge edge : editor.flow.edges) {
                Shape stroke =
                        new BasicStroke(14)
                                .createStrokedShape(
                                        curve(
                                                editor.flow.node(edge.from),
                                                edge.fromPort,
                                                editor.flow.node(edge.to),
                                                edge.port));
                if (stroke.contains(p)) return edge;
            }
            return null;
        }

        void updateSize() {
            double width = 860, height = 470;
            for (Node n : editor.flow.nodes) {
                width = Math.max(width, n.x + W + 80);
                height = Math.max(height, n.y + height(n) + 80);
            }
            int screenWidth = (int) (width * zoom), screenHeight = (int) (height * zoom);
            if (editor.canvasScroll != null) {
                JViewport view = editor.canvasScroll.getViewport();
                Point position = view.getViewPosition();
                Dimension extent = view.getExtentSize();
                screenWidth = Math.max(screenWidth, position.x + extent.width);
                screenHeight = Math.max(screenHeight, position.y + extent.height);
            }
            setPreferredSize(new Dimension(screenWidth, screenHeight));
            revalidate();
        }

        protected void paintComponent(Graphics raw) {
            super.paintComponent(raw);
            Graphics2D g = (Graphics2D) raw.create();
            quality(g);
            g.translate(panX, panY);
            g.scale(zoom, zoom);
            // 只绘制可见网格，线宽保持屏幕像素大小。 Render the visible grid with fixed pixel strokes.
            Rectangle clip = raw.getClipBounds();
            if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
            double minX = (clip.x - panX) / zoom, minY = (clip.y - panY) / zoom;
            double maxX = (clip.x + clip.width - panX) / zoom,
                    maxY = (clip.y + clip.height - panY) / zoom;
            int startX = (int) Math.floor(minX / 24) * 24,
                    startY = (int) Math.floor(minY / 24) * 24;
            g.setStroke(new BasicStroke((float) (1 / zoom)));
            for (int x = startX; x <= maxX; x += 24) {
                g.setColor(
                        Math.floorMod(x, 120) == 0
                                ? new Color(208, 218, 232)
                                : new Color(229, 234, 243));
                g.draw(new Line2D.Double(x, minY, x, maxY));
            }
            for (int y = startY; y <= maxY; y += 24) {
                g.setColor(
                        Math.floorMod(y, 120) == 0
                                ? new Color(208, 218, 232)
                                : new Color(229, 234, 243));
                g.draw(new Line2D.Double(minX, y, maxX, y));
            }
            for (Edge e : editor.flow.edges) {
                Node a = editor.flow.node(e.from), b = editor.flow.node(e.to);
                g.setColor(
                        Flow.incompatible(a, e.fromPort, b, e.port)
                                ? new Color(205, 82, 82)
                                : e.equals(editor.selectedEdge) ? BLUE : new Color(152, 172, 204));
                g.setStroke(new BasicStroke(e.equals(editor.selectedEdge) ? 3 : 2));
                g.draw(curve(a, e.fromPort, b, e.port));
                double x = b.x - 9, y = portY(b, false, e.port);
                Path2D arrow = new Path2D.Double();
                arrow.moveTo(x, y);
                arrow.lineTo(x - 7, y - 4);
                arrow.lineTo(x - 7, y + 4);
                arrow.closePath();
                g.fill(arrow);
            }
            if (pending != null) {
                g.setColor(BLUE);
                g.setStroke(
                        new BasicStroke(
                                2,
                                BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND,
                                1,
                                new float[] {6, 5},
                                0));
                g.draw(
                        new Line2D.Double(
                                pending.x + W,
                                portY(pending, true, pendingPort),
                                cursor.getX(),
                                cursor.getY()));
            }
            for (Node n : editor.flow.nodes) drawNode(g, n);
            if (editor.flow.nodes.isEmpty()) {
                g.setColor(INK);
                g.setFont(BODY.deriveFont(Font.BOLD, 22f));
                g.drawString("从一个输入开始", 240, 190);
                g.setColor(MUTED);
                g.setFont(BODY);
                g.drawString("点击左侧元素，再连接输入、工作和输出。", 240, 222);
            }
            g.dispose();
            Graphics2D overlay = (Graphics2D) raw.create();
            quality(overlay);
            overlay.setFont(BODY.deriveFont(11f));
            overlay.setColor(MUTED);
            Rectangle view = getVisibleRect();
            overlay.drawString("流程画布 / 中键平移 · Shift+滚轮缩放 · 右键菜单", view.x + 30, view.y + 30);
            overlay.dispose();
        }

        void drawNode(Graphics2D g, Node n) {
            double x = n.x, y = n.y;
            int nodeHeight = height(n);
            g.setColor(new Color(27, 46, 80, 14));
            g.fill(new RoundRectangle2D.Double(x + 1, y + 5, W, nodeHeight, 16, 16));
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(x, y, W, nodeHeight, 16, 16));
            g.setColor(n == editor.selected ? BLUE : LINE);
            g.setStroke(new BasicStroke(n == editor.selected ? 2 : 1));
            g.draw(new RoundRectangle2D.Double(x, y, W, nodeHeight, 16, 16));
            g.setColor(
                    new Color(
                            n.kind.color.getRed(),
                            n.kind.color.getGreen(),
                            n.kind.color.getBlue(),
                            22));
            g.fill(new RoundRectangle2D.Double(x + 15, y + 15, 34, 34, 9, 9));
            g.setColor(n.kind.color);
            g.setFont(BODY.deriveFont(Font.BOLD, n.kind.badge.length() > 2 ? 10f : 12f));
            centerString(g, n.kind.badge, x + 32, y + 37);
            g.setFont(BODY.deriveFont(Font.BOLD, 13f));
            g.setColor(INK);
            g.drawString(fit(g, n.name, 133), (float) x + 59, (float) y + 31);
            g.setFont(BODY.deriveFont(10f));
            g.setColor(MUTED);
            g.drawString(n.kind.group + "元素 · " + n.kind.label, (float) x + 59, (float) y + 48);
            g.setColor(LINE);
            g.draw(new Line2D.Double(x + 15, y + 62, x + W - 15, y + 62));
            g.setFont(BODY.deriveFont(10f));
            g.setColor(MUTED);
            for (boolean out : List.of(false, true))
                for (var port : out ? n.outputPorts() : n.inputPorts()) {
                    double py = portY(n, out, port.name());
                    portDot(g, out ? x + W : x, py, n.kind.color);
                    String text =
                            fit(
                                    g,
                                    (n.kind == Kind.SUBGRAPH
                                                    ? port.name() + " "
                                                    : port.name().equals("csv") ? "CSV 可选 " : "")
                                            + port.type(),
                                    80);
                    g.drawString(
                            text,
                            (float)
                                    (out
                                            ? x + W - 14 - g.getFontMetrics().stringWidth(text)
                                            : x + 14),
                            (float) py + 4);
                }
            g.setFont(BODY.deriveFont(10f));
            g.setColor(MUTED);
            String hint = n.summary().isBlank() ? "待配置参数" : n.summary().replace('\n', ' ');
            g.drawString(fit(g, hint, W - 30), (float) x + 15, (float) y + nodeHeight - 11);
        }

        void portDot(Graphics2D g, double x, double y, Color color) {
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            g.draw(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
        }
    }

    static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel l = new JLabel(text);
        l.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    static JPanel column(Color color) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(color);
        return p;
    }

    static JPanel row(Color color) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        p.setBackground(color);
        return p;
    }

    static JButton button(String text, boolean primary, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(BODY.deriveFont(12f));
        b.setFocusPainted(false);
        b.setForeground(primary ? Color.WHITE : INK);
        b.setBackground(primary ? BLUE : new Color(241, 244, 250));
        b.setOpaque(true);
        b.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(primary ? BLUE : new Color(200, 211, 229)),
                        new EmptyBorder(8, 11, 8, 11)));
        b.addActionListener(e -> action.run());
        return b;
    }

    static JTextArea text(String content, int width) {
        JTextArea a = new JTextArea(content);
        a.setEditable(false);
        a.setFocusable(false);
        a.setFont(BODY.deriveFont(12f));
        a.setForeground(MUTED);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        a.setMaximumSize(new Dimension(width, 200));
        a.setOpaque(false);
        return a;
    }

    static void listen(JTextField field, Runnable action) {
        field.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                action.run();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                action.run();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                action.run();
                            }
                        });
    }

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static String fit(Graphics2D g, String text, int width) {
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        int n = text.length();
        while (n > 0 && g.getFontMetrics().stringWidth(text.substring(0, n) + "…") > width) n--;
        return text.substring(0, n) + "…";
    }

    static void centerString(Graphics2D g, String s, double x, double y) {
        g.drawString(s, (float) (x - g.getFontMetrics().stringWidth(s) / 2.0), (float) y);
    }

    static void layoutTree(Container c) {
        c.doLayout();
        for (Component child : c.getComponents())
            if (child instanceof Container nested) layoutTree(nested);
    }

    /**
     * 同一入口分派桌面界面、独立 MCP 会话、初始化和本地验证命令。
     *
     * <p>Dispatches the desktop UI, standalone MCP session, package initialization and local
     * checks.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--mcp-stdio")) {
            System.setOut(
                    new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            WorkflowMcp.stdio();
            return;
        }
        for (Object key : UIManager.getDefaults().keySet().toArray())
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
                UIManager.put(key, BODY);
        if (args.length > 0 && args[0].equals("--verify")) {
            verify(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-file-transfer")) {
            FileTransferTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-file-types")) {
            FileTypeTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-batch")) {
            BatchTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-batch-native")) {
            BatchTests.run(Path.of(args[1]), false);
            return;
        }
        if (args.length > 0 && args[0].equals("--test-tool-launchers")) {
            ToolAdapterTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-executable-tools")) {
            ExecutableToolTests.run(Path.of(args[1]));
            return;
        }
        if (args.length == 3 && args[0].equals("--initialize-package")) {
            PackageExamples.initialize(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-graph-execution")) {
            GraphExecutionTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-graph-native")) {
            GraphExecutionTests.run(Path.of(args[1]), false);
            return;
        }
        if (args.length > 0 && args[0].equals("--test-flow-library")) {
            FlowLibrary.verify(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-workflow-import")) {
            WorkflowImportTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-tool-catalog")) {
            ToolCatalogPanel.verify(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-subgraph-navigation")) {
            SubgraphNavigationTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--test-subgraphs")) {
            SubgraphTests.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0
                && (args[0].equals("--snapshot") || args[0].equals("--snapshot-menu"))) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        try {
                            Editor editor = new Editor();
                            editor.setSize(1440, 900);
                            layoutTree(editor);
                            BufferedImage image =
                                    new BufferedImage(1440, 900, BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = image.createGraphics();
                            editor.printAll(g);
                            if (args[0].equals("--snapshot-menu")) {
                                JPopupMenu menu =
                                        editor.canvas.contextMenu(new Point2D.Double(400, 180));
                                menu.setSize(menu.getPreferredSize());
                                layoutTree(menu);
                                Point origin =
                                        SwingUtilities.convertPoint(
                                                editor.canvas, 480, 200, editor);
                                g.translate(origin.x, origin.y);
                                menu.printAll(g);
                            }
                            g.dispose();
                            ImageIO.write(image, "png", new File(args[1]));
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    JFrame frame = new JFrame("Flow 工作流 v0.16.1");
                    Editor editor = new Editor();
                    if (args.length > 0 && args[0].equals("--ai-demo")) editor.aiDemo();
                    else editor.subgraphDemo();
                    frame.setContentPane(editor);
                    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                    frame.addWindowListener(
                            new WindowAdapter() {
                                public void windowClosing(WindowEvent e) {
                                    if (editor.executing) {
                                        JOptionPane.showMessageDialog(frame, "文件传递进行中，请等待完成后关闭。");
                                        return;
                                    }
                                    if (editor.discard()) frame.dispose();
                                }
                            });
                    frame.setSize(1440, 900);
                    frame.setMinimumSize(new Dimension(1220, 760));
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                    if (args.length > 0 && args[0].equals("--agent"))
                        SwingUtilities.invokeLater(() -> AgentDialog.show(editor));
                });
    }

    // 内置验证入口供构建与验收调用。 Built-in verification entry points used by build and smoke checks.
    static void verify(Path directory) throws Exception {
        Files.createDirectories(directory);
        Flow f = new Flow();
        Node a = f.add(Kind.TEXT, 40, 100),
                b = f.add(Kind.AI, 300, 100),
                c = f.add(Kind.PREVIEW, 560, 100);
        a.value = "中文 & <内容>\n第二行";
        require(f.connect(a, b) == null, "valid edge");
        require(f.connect(b, c) == null, "valid output");
        require(f.connect(a, a) != null, "reject self edge");
        require(f.connect(a, b) != null, "reject duplicate");
        Node ai2 = f.add(Kind.AI, 300, 300);
        require(f.connect(b, ai2) == null, "second AI");
        require(f.connect(ai2, b) != null, "reject occupied input");
        Flow cycle = new Flow();
        Node x = cycle.add(Kind.AI, 1, 1), y = cycle.add(Kind.AI, 2, 2);
        require(cycle.connect(x, y) == null, "cycle setup");
        require(cycle.connect(y, x) != null, "reject cycle");
        Node file = f.add(Kind.FILE, 1, 1);
        file.settings.put("mode", "FILE");
        require(f.connect(file, ai2) != null, "occupied target");
        Node ai3 = f.add(Kind.AI, 1, 1);
        require(f.connect(file, ai3) != null, "reject incompatible type");
        Path saved = directory.resolve("roundtrip.flow.xml");
        f.save(saved);
        Flow loaded = Flow.load(saved);
        require(
                loaded.nodes.size() == f.nodes.size() && loaded.edges.equals(f.edges),
                "roundtrip graph");
        require(
                loaded.node(a.id).value.equals(a.value),
                "roundtrip Unicode, newlines and XML characters");
        require(loaded.node(b.id).x == 300, "roundtrip coordinates");
        loaded.remove(loaded.node(b.id));
        require(loaded.edges.isEmpty(), "remove incident edges");
        Path broken = directory.resolve("invalid.flow.xml");
        Files.writeString(broken, "not XML");
        boolean rejected = false;
        try {
            Flow.load(broken);
        } catch (IOException ex) {
            rejected = true;
        }
        require(rejected, "reject corrupt document");
        SwingUtilities.invokeAndWait(
                () -> {
                    Editor editor = new Editor();
                    editor.setSize(1440, 900);
                    layoutTree(editor);
                    require(editor.selected.kind == Kind.AI, "default selection");
                    editor.nodeName.setText("修改后的节点");
                    require(
                            editor.selected.name.equals("修改后的节点") && editor.dirty,
                            "inspector updates model");
                    editor.parameter.setText("新参数");
                    require(editor.selected.value.equals("新参数"), "parameter editing");
                    Node node = editor.flow.nodes.get(0);
                    int sx = (int) node.x + 80, sy = (int) node.y + 30;
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_PRESSED,
                                    1,
                                    0,
                                    sx,
                                    sy,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_DRAGGED,
                                    2,
                                    MouseEvent.BUTTON1_DOWN_MASK,
                                    sx + 50,
                                    sy + 35,
                                    0,
                                    false,
                                    MouseEvent.BUTTON1));
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_RELEASED,
                                    3,
                                    0,
                                    sx + 50,
                                    sy + 35,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    require(node.x == 112 && node.y == 170, "mouse dragging changes coordinates");
                    editor.flow.edges.clear();
                    Node target = editor.flow.nodes.get(1);
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_PRESSED,
                                    4,
                                    0,
                                    (int) node.x + Canvas.W,
                                    (int) node.y + 84,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_RELEASED,
                                    5,
                                    0,
                                    (int) target.x,
                                    (int) target.y + 84,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    require(editor.flow.edges.size() == 1, "drag ports creates edge");
                    editor.select(target);
                    editor.deleteSelection();
                    require(
                            editor.flow.nodes.size() == 2 && editor.flow.edges.isEmpty(),
                            "UI deletion cleans edges");
                    editor.zoom(.2);
                    require(editor.canvas.zoom == 1.2, "zoom");
                    verifyCanvasInteractions(directory);
                    verifyIoConfiguration(directory);
                });
        System.out.println(
                "PASS: previous graph and UI checks; typed file input/output controls, conditional forms, dynamic port types, independent configuration copies, v2 persistence, v1 migration and configuration validation.");
    }

    static void verifyIoConfiguration(Path directory) {
        try {
            Path source = directory.resolve("源文件 空格.txt").toAbsolutePath();
            Files.writeString(source, "第一行\n第二行\n");
            Editor editor = new Editor();
            editor.setSize(1440, 900);
            editor.fileDemo();
            layoutTree(editor);
            Node input = editor.selected, output = editor.flow.nodes.get(1);
            require(
                    input.kind == Kind.FILE && input.outputType().equals("FILE"),
                    "file demo and default input mode");
            ((JTextField) editor.ioPanel.inputControls.get("sourcePath"))
                    .setText(source.toString());
            ((JComboBox<?>) editor.ioPanel.inputControls.get("mode")).setSelectedItem("CSV");
            ((JSpinner) editor.ioPanel.inputControls.get("headerRow")).setValue(3);
            ((JTextArea) editor.ioPanel.inputControls.get("columns")).setText("工号\n姓名");
            require(
                    input.settings.get("headerRow").equals(3) && input.outputType().equals("JSON"),
                    "typed numeric input and dynamic port");
            require(
                    editor.ioPanel.csvSection.isVisible()
                            && !editor.ioPanel.textSection.isVisible(),
                    "CSV conditional controls");
            ((JComboBox<?>) editor.ioPanel.inputControls.get("mode")).setSelectedItem("JSON");
            ((JTextField) editor.ioPanel.inputControls.get("jsonPointer")).setText("/人员/0");
            require(
                    editor.ioPanel.jsonSection.isVisible()
                            && !editor.ioPanel.inputEncoding.isVisible(),
                    "JSON conditional controls");
            editor.select(output);
            ((JComboBox<?>) editor.ioPanel.outputControls.get("format")).setSelectedItem("JSON");
            ((JTextField) editor.ioPanel.outputControls.get("directory"))
                    .setText(directory.resolve("尚未创建").toAbsolutePath().toString());
            ((JTextField) editor.ioPanel.outputControls.get("fileName")).setText("结果.json");
            ((JComboBox<?>) editor.ioPanel.outputControls.get("conflictPolicy"))
                    .setSelectedItem("RENAME");
            ((JCheckBox) editor.ioPanel.outputControls.get("prettyPrint")).doClick();
            ((JCheckBox) editor.ioPanel.outputControls.get("openFolderAfterSuccess")).doClick();
            require(
                    Boolean.TRUE.equals(output.settings.get("openFolderAfterSuccess")),
                    "open output folder checkbox stores configuration");
            require(
                    output.settings.get("prettyPrint").equals(false)
                            && editor.ioPanel.outputJson.isVisible(),
                    "typed checkbox and output controls");
            require(IoConfig.diagnostics(output).isEmpty(), "valid output configuration");
            require(
                    !Files.exists(directory.resolve("尚未创建")),
                    "configuring output does not create destination");
            editor.copySelection();
            output.settings.put("fileName", "后续修改.json");
            editor.paste(null);
            require(
                    editor.selected.settings.get("fileName").equals("结果.json"),
                    "copy snapshots typed settings");
            editor.selected.settings.put("fileName", "副本.json");
            require(
                    output.settings.get("fileName").equals("后续修改.json"),
                    "pasted settings independent");
            Path saved = directory.resolve("configured-v2.flow.xml");
            editor.flow.save(saved);
            Flow loaded = Flow.load(saved);
            for (Node node : editor.flow.nodes)
                require(
                        loaded.node(node.id).settings.equals(node.settings),
                        "typed config roundtrip");
            editor.select(input);
            ((JComboBox<?>) editor.ioPanel.inputControls.get("mode")).setSelectedItem("FILE");
            require(
                    input.outputType().equals("FILE")
                            && !editor.ioPanel.textSection.isVisible()
                            && !editor.ioPanel.csvSection.isVisible(),
                    "raw file controls");
            input.settings.put("mode", "TEXT");
            input.settings.put("startLine", 5);
            input.settings.put("endLine", 2);
            require(!IoConfig.diagnostics(input).isEmpty(), "reject reversed line range");
            output.settings.put("fileName", "../outside.json");
            require(!IoConfig.diagnostics(output).isEmpty(), "reject path as filename");
            require(
                    !IoConfig.accepts("FILE", "TXT") && IoConfig.accepts("TEXT", "TXT"),
                    "format type compatibility");
            Flow legacy = new Flow();
            Node legacyFile = legacy.add(Kind.FILE, 20, 80);
            legacyFile.value = source.toString();
            Path old = directory.resolve("legacy-v1.flow.xml");
            legacy.save(old);
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(old)) {
                props.loadFromXML(in);
            }
            props.setProperty("version", "1");
            try (OutputStream out = Files.newOutputStream(old)) {
                props.storeToXML(out, "Legacy fixture", "UTF-8");
            }
            Node migrated = Flow.load(old).nodes.get(0);
            require(
                    migrated.settings.get("sourcePath").equals(source.toString())
                            && migrated.outputType().equals("FILE"),
                    "legacy path and type migration");
            props.setProperty("version", "2");
            props.setProperty("node.0.setting.0.type", "boolean");
            props.setProperty("node.0.setting.0.value", "true");
            try (OutputStream out = Files.newOutputStream(old)) {
                props.storeToXML(out, "Invalid config fixture", "UTF-8");
            }
            boolean rejected = false;
            try {
                Flow.load(old);
            } catch (IOException ex) {
                rejected = true;
            }
            require(rejected, "reject wrong persisted setting type");
            layoutTree(editor);
            require(editor.ioPanel.getWidth() > 180, "configuration panel has usable width");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void verifyCanvasInteractions(Path directory) {
        Editor editor = new Editor();
        editor.setSize(1440, 900);
        layoutTree(editor);
        Canvas canvas = editor.canvas;
        Node original = editor.flow.nodes.get(0), selection = editor.selected;
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_PRESSED,
                        10,
                        0,
                        100,
                        165,
                        1,
                        false,
                        MouseEvent.BUTTON2));
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_DRAGGED,
                        11,
                        MouseEvent.BUTTON2_DOWN_MASK,
                        180,
                        225,
                        0,
                        false,
                        MouseEvent.NOBUTTON));
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_RELEASED,
                        12,
                        0,
                        180,
                        225,
                        1,
                        false,
                        MouseEvent.BUTTON2));
        require(
                canvas.panX == 80 && canvas.panY == 60 && !canvas.panning,
                "middle-button pan and release");
        require(
                original.x == 62
                        && original.y == 135
                        && editor.selected == selection
                        && !editor.dirty,
                "pan preserves graph, selection and clean state");
        Point anchor = new Point(400, 200);
        Point2D before = canvas.world(anchor);
        MouseWheelEvent wheel =
                new MouseWheelEvent(
                        canvas,
                        MouseEvent.MOUSE_WHEEL,
                        13,
                        InputEvent.SHIFT_DOWN_MASK,
                        anchor.x,
                        anchor.y,
                        0,
                        false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        3,
                        -1);
        canvas.dispatchEvent(wheel);
        require(
                canvas.zoom == 1.1 && wheel.isConsumed(),
                "Shift-wheel changes zoom and consumes event");
        require(
                canvas.world(anchor).distance(before) < .00001 && !editor.dirty,
                "zoom preserves mouse anchor and clean state");
        canvas.dispatchEvent(
                new MouseWheelEvent(
                        canvas,
                        MouseEvent.MOUSE_WHEEL,
                        14,
                        0,
                        400,
                        200,
                        0,
                        false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        3,
                        1));
        require(canvas.zoom == 1.1, "unmodified wheel does not zoom");
        Rectangle rectangle = canvas.screenRect(original.x, original.y);
        int px = rectangle.x + 80, py = rectangle.y + 30;
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_PRESSED,
                        15,
                        0,
                        px,
                        py,
                        1,
                        false,
                        MouseEvent.BUTTON1));
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_RELEASED,
                        16,
                        0,
                        px,
                        py,
                        1,
                        false,
                        MouseEvent.BUTTON1));
        require(editor.selected == original, "node hit-testing after pan and zoom");
        canvas.pending = original;
        Rectangle second = canvas.screenRect(selection.x, selection.y);
        int rightX = second.x + 70, rightY = second.y + 30;
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_PRESSED,
                        17,
                        InputEvent.BUTTON3_DOWN_MASK,
                        rightX,
                        rightY,
                        1,
                        false,
                        MouseEvent.BUTTON3));
        canvas.dispatchEvent(
                new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_RELEASED,
                        18,
                        0,
                        rightX,
                        rightY,
                        1,
                        true,
                        MouseEvent.BUTTON3));
        require(
                editor.selected == selection && canvas.pending == null && canvas.dragging == null,
                "right-click targets node without starting drag or connection");
        require(
                canvas.lastPopup != null && canvas.lastPopup.getComponentCount() == 7,
                "right-click builds node menu");
        require(
                !menuItem(canvas.lastPopup, "粘贴").isEnabled(),
                "paste disabled with empty clipboard");
        menuItem(canvas.lastPopup, "复制").doClick();
        require(
                editor.clipboard.kind == selection.kind && !editor.dirty,
                "menu copies selected node without changing graph");
        String copiedName = selection.name, copiedValue = selection.value;
        selection.name = "后来修改的原节点";
        selection.value = "后来修改的参数";
        invokeCanvasKey(canvas, "control V");
        Node pasted = editor.selected;
        require(
                !pasted.id.equals(selection.id)
                        && pasted.name.equals(copiedName)
                        && pasted.value.equals(copiedValue),
                "paste uses immutable snapshot and new identity");
        require(editor.flow.edges.size() == 2, "copy paste does not duplicate external edges");
        NodeCopy previousClipboard = editor.clipboard;
        invokeCanvasKey(canvas, "control D");
        require(editor.flow.nodes.size() == 5, "duplicate shortcut");
        require(editor.clipboard == previousClipboard, "duplicate preserves clipboard");
        editor.select(selection);
        invokeCanvasKey(canvas, "control X");
        require(
                editor.flow.node(selection.id) == null && editor.flow.edges.isEmpty(),
                "cut removes node and incident edges");
        invokeCanvasKey(canvas, "control V");
        require(
                editor.selected.name.equals("后来修改的原节点") && editor.selected.value.equals("后来修改的参数"),
                "cut paste preserves latest parameters");
        editor.select(null);
        JPopupMenu blank = canvas.contextMenu(new Point2D.Double(430, 350));
        require(
                blank.getComponentCount() == 1 && menuItem(blank, "粘贴").isEnabled(),
                "blank canvas offers paste");
        menuItem(blank, "粘贴").doClick();
        require(
                editor.selected.x == 430 && editor.selected.y == 350,
                "context paste uses clicked location");
        int count = editor.flow.nodes.size();
        invokeCanvasKey(canvas, "DELETE");
        require(editor.flow.nodes.size() == count - 1, "Delete shortcut");
        require(
                !Objects.equals(
                        editor.nodeName.getInputMap().get(KeyStroke.getKeyStroke("control C")),
                        "copy"),
                "text fields retain standard text copy binding");
        editor.zoomAt(100, anchor);
        require(canvas.zoom == 1.5, "maximum zoom");
        editor.zoomAt(-100, anchor);
        require(canvas.zoom == .5, "minimum zoom");
        editor.resetView();
        require(
                canvas.zoom == 1 && canvas.panX == 0 && canvas.panY == 0,
                "reset clears pan and zoom");
        try {
            Path saved = directory.resolve("stage2.flow.xml");
            editor.flow.save(saved);
            Flow loaded = Flow.load(saved);
            require(loaded.nodes.size() == editor.flow.nodes.size(), "stage2 edits roundtrip");
            for (Node n : editor.flow.nodes)
                require(
                        loaded.node(n.id).name.equals(n.name)
                                && loaded.node(n.id).value.equals(n.value),
                        "pasted values persist");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static JMenuItem menuItem(JPopupMenu menu, String name) {
        for (Component component : menu.getComponents())
            if (component instanceof JMenuItem item && item.getText().equals(name)) return item;
        throw new AssertionError("Missing menu item: " + name);
    }

    static void invokeCanvasKey(Canvas canvas, String key) {
        Object name = canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
        Action action = canvas.getActionMap().get(name);
        require(action != null, "shortcut registered: " + key);
        action.actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "verify"));
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package course.workflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Agent 的受限工具层：通过 MCP 查询元素目录、校验和替换独立草稿，不负责模型调用或文件执行。
 *
 * <p>The agent harness tool layer exposes MCP draft operations, separate from model calls and
 * execution.
 */
final class WorkflowMcp {
    WorkflowApp.Flow draft;
    long revision;
    final Path settingsFile;
    // 可创建的元素白名单；当前不包含子图或自定义脚本。 Creation allowlist excludes subgraphs and scripts.
    static final Set<WorkflowApp.Kind> ALLOWED =
            Set.of(
                    WorkflowApp.Kind.TEXT,
                    WorkflowApp.Kind.FILE,
                    WorkflowApp.Kind.AI,
                    WorkflowApp.Kind.PDF,
                    WorkflowApp.Kind.RENAME,
                    WorkflowApp.Kind.FILE_MERGE,
                    WorkflowApp.Kind.SAVE,
                    WorkflowApp.Kind.JAVA_BUILD,
                    WorkflowApp.Kind.AUDIO,
                    WorkflowApp.Kind.EXCEL_MERGE);

    WorkflowMcp(WorkflowApp.Flow initial, Path settingsFile) {
        draft = Subgraphs.copy(initial);
        this.settingsFile = settingsFile;
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false);
    }

    static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    /**
     * 同一份工具描述用于 MCP 发现及模型的函数声明，避免两套工具接口漂移。
     *
     * <p>One schema catalog serves MCP discovery and the model's function definitions.
     */
    List<Map<String, Object>> tools() {
        var empty = schema(Map.of(), List.of());
        var str = Map.of("type", "string");
        var node =
                schema(
                        Map.of(
                                "id",
                                str,
                                "kind",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        ALLOWED.stream().map(Enum::name).sorted().toList()),
                                "name",
                                str,
                                "value",
                                str,
                                "x",
                                Map.of("type", "number"),
                                "y",
                                Map.of("type", "number"),
                                "settings",
                                Map.of("type", "object")),
                        List.of("id", "kind"));
        var edge =
                schema(
                        Map.of("from", str, "to", str, "fromPort", str, "toPort", str),
                        List.of("from", "to"));
        var flow =
                schema(
                        Map.of(
                                "title",
                                str,
                                "nodes",
                                Map.of("type", "array", "items", node, "maxItems", 100),
                                "edges",
                                Map.of("type", "array", "items", edge, "maxItems", 300)),
                        List.of("title", "nodes", "edges"));
        return List.of(
                tool("catalog_list", "查询可执行元素、默认配置、端口和格式。创建前必须先查询。", empty),
                tool("ai_services_list", "列出可选择的AI服务ID、名称、Model，不返回Key。", empty),
                tool("workflow_get", "读取草稿流程、revision和诊断，不读取文件内容。", empty),
                tool(
                        "workflow_replace",
                        "原子替换助手草稿；不改用户画布，不保存文件，不执行。需当前revision。只支持catalog提供的元素；不指定路径时保留空字符串并说明待用户配置。",
                        schema(
                                Map.of("expectedRevision", Map.of("type", "integer"), "flow", flow),
                                List.of("expectedRevision", "flow"))),
                tool("workflow_validate", "验证草稿的端口、必需输入、路径及配置，并返回待处理项。", empty));
    }

    /**
     * 串行处理会话请求；协议错误与工具业务错误使用不同的响应层级。
     *
     * <p>Serializes session requests and separates JSON-RPC errors from tool-level failures.
     */
    synchronized Map<String, Object> request(Map<String, Object> request) {
        Object id = request.get("id");
        Map<String, Object> reply = new LinkedHashMap<>(Map.of("jsonrpc", "2.0"));
        if (id != null) reply.put("id", id);
        try {
            if (!"2.0".equals(request.get("jsonrpc"))) throw new IOException("JSON-RPC版本无效。");
            String method = Json.string(request, "method");
            Map<String, Object> params = Json.object(request.getOrDefault("params", Map.of()));
            Object result;
            switch (method) {
                case "initialize" ->
                        result =
                                Map.of(
                                        "protocolVersion",
                                        "2025-06-18",
                                        "capabilities",
                                        Map.of("tools", Map.of("listChanged", false)),
                                        "serverInfo",
                                        Map.of("name", "flow-draft-mcp", "version", "1.0.0"));
                case "notifications/initialized" -> {
                    return Map.of();
                }
                case "ping" -> result = Map.of();
                case "tools/list" -> result = Map.of("tools", tools());
                case "tools/call" -> {
                    String name = Json.string(params, "name");
                    if (tools().stream().noneMatch(t -> t.get("name").equals(name)))
                        throw new IOException("未知MCP工具。");
                    try {
                        var data =
                                call(name, Json.object(params.getOrDefault("arguments", Map.of())));
                        result =
                                Map.of(
                                        "content",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "text",
                                                        "text",
                                                        Json.stringify(data))),
                                        "structuredContent",
                                        data,
                                        "isError",
                                        false);
                    } catch (Exception ex) {
                        result =
                                Map.of(
                                        "content",
                                        List.of(Map.of("type", "text", "text", safeError(ex))),
                                        "isError",
                                        true);
                    }
                }
                default -> {
                    reply.put("error", Map.of("code", -32601, "message", "未实现的MCP方法。"));
                    return reply;
                }
            }
            reply.put("result", result);
        } catch (Exception ex) {
            reply.put("error", Map.of("code", -32602, "message", safeError(ex)));
        }
        return reply;
    }

    static String safeError(Exception ex) {
        return ex instanceof IOException ? ex.getMessage() : "工具参数不符合规范，草稿未修改。";
    }

    /**
     * 工具处理只触及草稿和服务元数据；应用到画布与运行流程由用户界面另行触发。
     *
     * <p>Handlers access drafts and service metadata; applying and executing remain separate UI
     * actions.
     */
    Map<String, Object> call(String name, Map<String, Object> args) throws IOException {
        if (!name.equals("workflow_replace") && !args.isEmpty()) throw new IOException("此工具不接受参数。");
        return switch (name) {
            case "catalog_list" -> {
                List<Object> entries = new ArrayList<>();
                for (var kind : ALLOWED.stream().sorted().toList()) {
                    var n = new WorkflowApp.Node("catalog", kind, 0, 0);
                    entries.add(
                            Map.of(
                                    "kind",
                                    kind.name(),
                                    "name",
                                    kind.label,
                                    "defaults",
                                    n.settings,
                                    "inputs",
                                    ports(n, false),
                                    "outputs",
                                    ports(n, true)));
                }
                yield Map.of(
                        "elements",
                        entries,
                        "notes",
                        List.of(
                                "FILE mode=TEXT读取单文件正文给AI；PDF/改名使用mode=FILE。",
                                "SAVE使用format=KEEP与operation=COPY，保存AI文本为txt。",
                                "FILE_MERGE inputCount配置2至8，连接input1、input2等。",
                                "不提供执行、脚本、任意文件读取、密钥或系统设置修改工具。"));
            }
            case "ai_services_list" ->
                    Map.of(
                            "services",
                            AiServices.load(new SystemSettings(settingsFile).load()).stream()
                                    .map(
                                            s ->
                                                    Map.of(
                                                            "id",
                                                            s.id(),
                                                            "name",
                                                            s.name(),
                                                            "model",
                                                            s.model(),
                                                            "keyConfigured",
                                                            s.hasKey()))
                                    .toList());
            case "workflow_get", "workflow_validate" -> state();
            case "workflow_replace" -> {
                if (!args.keySet().equals(Set.of("expectedRevision", "flow")))
                    throw new IOException("替换草稿参数不符合规范。");
                if (number(args.get("expectedRevision")) != revision)
                    throw new IOException("草稿版本已变化，请重新读取workflow_get。");
                // 先检查版本并构造候选图，再替换会话草稿。 Parse the candidate before updating the draft.
                var parsed = parse(Json.object(args.get("flow")));
                draft = parsed;
                revision++;
                yield state();
            }
            default -> throw new IOException("未知工具。");
        };
    }

    static List<Object> ports(WorkflowApp.Node n, boolean out) {
        return n.inputPorts() == null
                ? List.of()
                : (out ? n.outputPorts() : n.inputPorts())
                        .stream()
                                .map(
                                        p ->
                                                (Object)
                                                        Map.of(
                                                                "name",
                                                                p.name(),
                                                                "type",
                                                                p.type(),
                                                                "cardinality",
                                                                p.cardinality(),
                                                                "required",
                                                                p.required(),
                                                                "formats",
                                                                p.formats()))
                                .toList();
    }

    static Map<String, Object> encode(WorkflowApp.Flow flow) {
        return Map.of(
                "title",
                flow.title,
                "nodes",
                flow.nodes.stream()
                        .map(
                                n ->
                                        Map.of(
                                                "id",
                                                n.id,
                                                "kind",
                                                n.kind.name(),
                                                "name",
                                                n.name,
                                                "value",
                                                n.kind == WorkflowApp.Kind.AI
                                                        ? n.settings.get("prompt")
                                                        : n.value,
                                                "x",
                                                n.x,
                                                "y",
                                                n.y,
                                                "settings",
                                                new LinkedHashMap<>(n.settings)))
                        .toList(),
                "edges",
                flow.edges.stream()
                        .map(
                                e ->
                                        Map.of(
                                                "from",
                                                e.from(),
                                                "to",
                                                e.to(),
                                                "fromPort",
                                                e.fromPort(),
                                                "toPort",
                                                e.port()))
                        .toList());
    }

    Map<String, Object> state() {
        return Map.of("revision", revision, "flow", encode(draft), "issues", issues(draft));
    }

    static long number(Object value) throws IOException {
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (Exception ex) {
            throw new IOException("整数参数无效。");
        }
    }

    /**
     * 把模型参数转换为白名单内的图模型，并限制规模、标识、坐标和配置键。
     *
     * <p>Converts model arguments into allowed nodes with bounded size and validated setting keys.
     */
    static WorkflowApp.Flow parse(Map<String, Object> data) throws IOException {
        if (!data.keySet().equals(Set.of("title", "nodes", "edges")))
            throw new IOException("流程字段无效。");
        var flow = new WorkflowApp.Flow();
        flow.title = Json.string(data, "title");
        if (flow.title.isBlank() || flow.title.length() > 200) throw new IOException("流程名称无效。");
        var nodes = Json.list(data.get("nodes"));
        var edges = Json.list(data.get("edges"));
        if (nodes.size() > 100 || edges.size() > 300) throw new IOException("助手草稿最多100节点、300连线。");
        Set<String> ids = new HashSet<>();
        for (Object raw : nodes) {
            var item = Json.object(raw);
            if (!Set.of("id", "kind", "name", "value", "x", "y", "settings")
                    .containsAll(item.keySet())) throw new IOException("节点包含未知字段。");
            String id = Json.string(item, "id");
            if (!id.matches("[a-zA-Z0-9_-]{1,80}") || !ids.add(id))
                throw new IOException("节点ID无效或重复。");
            WorkflowApp.Kind kind;
            try {
                kind = WorkflowApp.Kind.valueOf(Json.string(item, "kind"));
            } catch (Exception ex) {
                throw new IOException("节点类型无效。");
            }
            if (!ALLOWED.contains(kind)) throw new IOException("该元素尚不支持由助手创建。");
            double x = coordinate(item.getOrDefault("x", 60 + flow.nodes.size() % 3 * 300)),
                    y = coordinate(item.getOrDefault("y", 100 + flow.nodes.size() / 3 * 220));
            var n = new WorkflowApp.Node(id, kind, x, y);
            if (item.containsKey("name")) n.name = Json.string(item, "name");
            if (item.containsKey("value")) n.value = Json.string(item, "value");
            for (var entry : Json.object(item.getOrDefault("settings", Map.of())).entrySet()) {
                Object value = entry.getValue();
                if (n.settings.get(entry.getKey()) instanceof Integer)
                    value = Math.toIntExact(number(value));
                IoConfig.requireSetting(kind, entry.getKey(), value);
                n.settings.put(entry.getKey(), value);
            }
            if (kind == WorkflowApp.Kind.AI) {
                if (item.containsKey("value")
                        && !Json.object(item.getOrDefault("settings", Map.of()))
                                .containsKey("prompt")) n.settings.put("prompt", n.value);
                n.value = (String) n.settings.get("prompt");
            }
            flow.nodes.add(n);
        }
        for (Object raw : edges) {
            var e = Json.object(raw);
            if (!Set.of("from", "to", "fromPort", "toPort").containsAll(e.keySet()))
                throw new IOException("连线包含未知字段。");
            var a = flow.node(Json.string(e, "from"));
            var b = flow.node(Json.string(e, "to"));
            if (a == null || b == null) throw new IOException("连线节点不存在。");
            String error =
                    flow.connect(
                            a,
                            (String) e.getOrDefault("fromPort", "data"),
                            b,
                            (String) e.getOrDefault("toPort", "data"),
                            false);
            if (error != null) throw new IOException(error);
        }
        return flow;
    }

    static double coordinate(Object value) throws IOException {
        try {
            double n = Double.parseDouble(value.toString());
            if (Double.isFinite(n) && n >= 0 && n <= 100000) return n;
        } catch (Exception ignored) {
        }
        throw new IOException("节点位置无效。");
    }

    /**
     * 返回配置与连接诊断；路径检查不读取输入正文，也不等同于试运行。
     *
     * <p>Reports configuration and wiring issues without reading input contents or executing tools.
     */
    static List<String> issues(WorkflowApp.Flow flow) {
        List<String> issues = new ArrayList<>();
        for (var n : flow.nodes) {
            issues.addAll(IoConfig.diagnostics(n));
            for (var p : n.inputPorts())
                if (p.required()
                        && flow.edges.stream()
                                .noneMatch(e -> e.to().equals(n.id) && e.port().equals(p.name())))
                    issues.add(n.name + "：缺少输入端口 " + p.name());
            if (!n.outputPorts().isEmpty()
                    && flow.edges.stream().noneMatch(e -> e.from().equals(n.id)))
                issues.add(n.name + "：未连接输出。");
        }
        if (flow.nodes.stream().noneMatch(n -> n.kind == WorkflowApp.Kind.SAVE))
            issues.add("请连接文件输出。");
        return issues;
    }

    /**
     * 外部客户端使用逐行 JSON 的独立草稿会话，不连接已经打开的桌面画布。
     *
     * <p>Line-delimited JSON transport owns a standalone draft, not an existing desktop editor.
     */
    static void stdio() throws Exception {
        var server = new WorkflowMcp(new WorkflowApp.Flow(), SystemSettings.current().file);
        try (var in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > 1024 * 1024) {
                    System.out.println(
                            Json.stringify(
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            0,
                                            "error",
                                            Map.of("code", -32600, "message", "请求过大。"))));
                    continue;
                }
                try {
                    var result = server.request(Json.object(Json.parse(line)));
                    if (!result.isEmpty()) System.out.println(Json.stringify(result));
                } catch (Exception ex) {
                    System.out.println(
                            Json.stringify(
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            0,
                                            "error",
                                            Map.of("code", -32700, "message", "JSON格式错误。"))));
                }
                System.out.flush();
            }
        }
    }
}

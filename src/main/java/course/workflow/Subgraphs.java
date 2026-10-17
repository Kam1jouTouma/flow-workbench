package course.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 子图属于宿主内置结构：通过具名端口连接父子画布，执行时展开为带边界检查的普通 DAG。
 *
 * <p>Expands named subgraph interfaces into isolated call scopes and runtime validation gates.
 */
final class Subgraphs {
    static final int MAX_DEPTH = 8, MAX_PORTS = 8;

    record Port(
            String name, String type, String cardinality, boolean required, Set<String> formats) {}

    record Expansion(WorkflowApp.Flow flow, List<Object> contexts) {}

    record Scope(WorkflowApp.Flow flow, String path, boolean module) {}

    record Endpoint(WorkflowApp.Node node, String port) {}

    static boolean boundary(WorkflowApp.Kind k) {
        return k == WorkflowApp.Kind.VIRTUAL_IN || k == WorkflowApp.Kind.VIRTUAL_OUT;
    }

    static Port port(WorkflowApp.Node n) {
        return new Port(
                (String) n.settings.get("interfaceName"),
                (String) n.settings.get("dataType"),
                (String) n.settings.get("cardinality"),
                n.kind == WorkflowApp.Kind.VIRTUAL_OUT || (Boolean) n.settings.get("required"),
                formats((String) n.settings.get("formats")));
    }

    static Set<String> formats(String raw) {
        Set<String> f = new LinkedHashSet<>();
        for (String item : raw.split("[,;\\s]+"))
            if (!item.isBlank()) f.add(item.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
        return f.isEmpty() ? Set.of("*") : Set.copyOf(f);
    }

    static List<Port> interfaces(WorkflowApp.Flow f, boolean out) {
        if (f == null) return List.of();
        return f.nodes.stream()
                .filter(
                        n ->
                                n.kind
                                        == (out
                                                ? WorkflowApp.Kind.VIRTUAL_OUT
                                                : WorkflowApp.Kind.VIRTUAL_IN))
                .map(Subgraphs::port)
                .toList();
    }

    /** 统一描述普通节点、脚本和子图的端口，供连线校验与画布使用。 / Describe visible node ports. */
    static List<Port> ports(WorkflowApp.Node n, boolean out) {
        if (n.kind == WorkflowApp.Kind.SCRIPT) return ScriptTools.ports(n, out);
        if (n.kind == WorkflowApp.Kind.AI)
            return List.of(new Port("data", "TEXT", "SINGLE", true, Set.of("txt")));
        if (n.kind == WorkflowApp.Kind.FILE_MERGE) {
            if (out) return List.of(new Port("data", "FILE", "COLLECTION", true, Set.of("*")));
            List<Port> inputs = new ArrayList<>();
            for (int i = 1; i <= (Integer) n.settings.get("inputCount"); i++)
                inputs.add(new Port("input" + i, "FILE", "COLLECTION", true, Set.of("*")));
            return inputs;
        }
        if (n.kind == WorkflowApp.Kind.SUBGRAPH) return interfaces(n.child, out);
        if (boundary(n.kind)) {
            if (Boolean.TRUE.equals(n.settings.get("internalGate")))
                return List.of(
                        new Port("data", port(n).type, port(n).cardinality, true, port(n).formats));
            return (out
                            ? n.kind == WorkflowApp.Kind.VIRTUAL_IN
                            : n.kind == WorkflowApp.Kind.VIRTUAL_OUT)
                    ? List.of(
                            new Port(
                                    "data",
                                    port(n).type,
                                    port(n).cardinality,
                                    true,
                                    port(n).formats))
                    : List.of();
        }
        if (out && n.kind.output == null || !out && n.kind.input == null) return List.of();
        String type = out ? n.outputType() : n.kind.input;
        String count =
                out
                                        && n.kind == WorkflowApp.Kind.FILE
                                        && n.settings.get("selectionMode").equals("SINGLE")
                                || n.kind == WorkflowApp.Kind.TEXT
                        ? "SINGLE"
                        : "COLLECTION";
        if (out
                && (n.kind == WorkflowApp.Kind.RENAME
                        || n.kind == WorkflowApp.Kind.AUDIO
                        || n.kind == WorkflowApp.Kind.PDF))
            count =
                    n.kind == WorkflowApp.Kind.PDF && n.settings.get("pdfMode").equals("MERGE")
                            ? "SINGLE"
                            : "DYNAMIC";
        if (out
                && (n.kind == WorkflowApp.Kind.JAVA_BUILD
                        || n.kind == WorkflowApp.Kind.EXCEL_MERGE)) count = "SINGLE";
        Set<String> f = Set.of("*");
        if (out)
            f =
                    switch (n.kind) {
                        case PDF -> Set.of("pdf");
                        case JAVA_BUILD -> Set.of("jar");
                        case AUDIO -> Set.of("mp3");
                        case EXCEL_MERGE -> Set.of("xlsx");
                        case FILE -> {
                            String path = (String) n.settings.get("sourcePath");
                            try {
                                yield n.settings.get("selectionMode").equals("SINGLE")
                                                && !path.isBlank()
                                        ? Set.of(FileCli.extension(java.nio.file.Path.of(path)))
                                        : n.settings.get("selectionMode").equals("TYPES")
                                                ? formats((String) n.settings.get("extensions"))
                                                : Set.of("*");
                            } catch (java.nio.file.InvalidPathException ex) {
                                yield Set.of("*");
                            }
                        }
                        default -> Set.of("*");
                    };
        List<Port> result = new ArrayList<>();
        result.add(new Port("data", type, count, true, f));
        if (!out && n.kind == WorkflowApp.Kind.RENAME)
            result.add(new Port("csv", "FILE", "SINGLE", false, Set.of("csv")));
        return result;
    }

    static Port find(WorkflowApp.Node n, boolean out, String name) {
        return ports(n, out).stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
    }

    static boolean incompatible(
            WorkflowApp.Node from, String fromPort, WorkflowApp.Node to, String toPort) {
        Port a = find(from, true, fromPort), b = find(to, false, toPort);
        if (a == null || b == null) return true;
        if (!a.type.equals("ANY") && !b.type.equals("ANY") && !a.type.equals(b.type)) return true;
        if (b.cardinality.equals("SINGLE") && a.cardinality.equals("COLLECTION")) return true;
        if (a.type.equals("FILE")
                && !a.formats.contains("*")
                && !b.formats.contains("*")
                && Collections.disjoint(a.formats, b.formats)) return true;
        return to.kind != WorkflowApp.Kind.SUBGRAPH
                && !boundary(to.kind)
                && toPort.equals("data")
                && ElementCapabilities.connectionError(from, to) != null;
    }

    static String connectionDescription(
            WorkflowApp.Node from, String fromPort, WorkflowApp.Node to, String toPort) {
        Port a = find(from, true, fromPort), b = find(to, false, toPort);
        return "端口不兼容："
                + a.type
                + "（"
                + quantity(a.cardinality)
                + "） → "
                + b.type
                + "（"
                + quantity(b.cardinality)
                + "）；请检查类型、文件格式和数量。";
    }

    static String quantity(String value) {
        return switch (value) {
            case "SINGLE" -> "单项";
            case "DYNAMIC" -> "数量随输入，运行时检查";
            default -> "集合";
        };
    }

    static WorkflowApp.Flow defaultModule() {
        var f = new WorkflowApp.Flow();
        f.title = "可复用改名步骤";
        var in = f.add(WorkflowApp.Kind.VIRTUAL_IN, 30, 120);
        var rename = f.add(WorkflowApp.Kind.RENAME, 300, 120);
        var out = f.add(WorkflowApp.Kind.VIRTUAL_OUT, 580, 120);
        f.connect(in, rename);
        f.connect(rename, out);
        return f;
    }

    /** 复制各层子图实例，避免编辑或粘贴后共享同一份子图对象。 / Deep-copy child flow instances. */
    static WorkflowApp.Flow copy(WorkflowApp.Flow f) {
        return copy(f, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static WorkflowApp.Flow copy(WorkflowApp.Flow f, int depth, Set<WorkflowApp.Flow> stack) {
        if (f == null) return null;
        if (depth > MAX_DEPTH || !stack.add(f))
            throw new IllegalArgumentException("子图存在递归或超过8层嵌套。");
        var result = new WorkflowApp.Flow();
        result.title = f.title;
        for (var n : f.nodes) {
            var c = new WorkflowApp.Node(n.id, n.kind, n.x, n.y);
            c.name = n.name;
            c.value = n.value;
            c.settings.clear();
            c.settings.putAll(n.settings);
            c.child = copy(n.child, depth + 1, stack);
            result.nodes.add(c);
        }
        result.edges.addAll(f.edges);
        stack.remove(f);
        return result;
    }

    static void validateInterfaces(WorkflowApp.Flow f) throws IOException {
        if (interfaces(f, true).isEmpty()) throw new IOException("子图需要至少一个虚拟输出。");
        for (boolean out : List.of(false, true)) {
            Set<String> names = new HashSet<>();
            var ports = interfaces(f, out);
            if (ports.size() > MAX_PORTS) throw new IOException("每侧最多8个子图接口。");
            for (var p : ports) {
                if (!p.name.matches("[\\p{L}_][\\p{L}\\p{N}_-]{0,31}") || !names.add(p.name))
                    throw new IOException("接口名称须唯一且合法：" + p.name);
                if (!Set.of("FILE", "TEXT", "JSON", "ANY").contains(p.type)
                        || !Set.of("SINGLE", "COLLECTION").contains(p.cardinality))
                    throw new IOException("接口类型或数量无效。");
                if (p.formats.contains("*") && p.formats.size() != 1
                        || p.formats.stream()
                                .anyMatch(x -> !x.equals("*") && !x.matches("[a-z0-9]+")))
                    throw new IOException("接口格式声明无效。");
            }
        }
    }

    /**
     * 将具名接口改写为内部边界节点，补入默认值，并裁去未通向最终输出的分支。
     *
     * <p>Flattens interface bindings, inserts defaults and retains dependencies of real outputs.
     */
    static Expansion expand(WorkflowApp.Flow root) throws IOException {
        Set<String> rootNeeded = new HashSet<>();
        for (var n : root.nodes)
            if (n.kind == WorkflowApp.Kind.SAVE) visit(root, n.id, rootNeeded, new HashSet<>());
        if (rootNeeded.size() != root.nodes.size())
            throw new IOException("顶层存在未接入实际输出的元素，或当前是子图定义。");
        WorkflowApp.Flow flat = new WorkflowApp.Flow();
        flat.title = root.title;
        List<Object> contexts = new ArrayList<>();
        Map<String, Scope> scopes = new LinkedHashMap<>();
        Map<String, WorkflowApp.Node> nodes = new HashMap<>();
        collect(
                new Scope(root, "root", false),
                flat,
                contexts,
                scopes,
                nodes,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                0);
        for (Scope scope : scopes.values())
            for (var edge : scope.flow.edges) {
                var from = scope.flow.node(edge.from());
                var to = scope.flow.node(edge.to());
                if (from == null
                        || to == null
                        || incompatible(from, edge.fromPort(), to, edge.port()))
                    throw new IOException(scope.path + "：端口绑定不兼容或已失效。");
                Endpoint a = endpoint(scope, from, true, edge.fromPort(), scopes, nodes),
                        b = endpoint(scope, to, false, edge.port(), scopes, nodes);
                flat.edges.add(new WorkflowApp.Edge(a.node.id, b.node.id, b.port, a.port));
            }
        for (Scope scope : scopes.values())
            if (scope.module)
                for (var n : scope.flow.nodes)
                    if (n.kind == WorkflowApp.Kind.VIRTUAL_IN) {
                        var gate = nodes.get(scope.path + "/" + n.id);
                        boolean bound = flat.edges.stream().anyMatch(e -> e.to().equals(gate.id));
                        if (!bound && !((String) n.settings.get("defaultValue")).isBlank()) {
                            var value =
                                    flat.add(
                                            port(n).type.equals("FILE")
                                                    ? WorkflowApp.Kind.FILE
                                                    : WorkflowApp.Kind.TEXT,
                                            20,
                                            60);
                            value.name = "默认值：" + n.name;
                            if (value.kind == WorkflowApp.Kind.FILE) {
                                value.settings.put("sourcePath", n.settings.get("defaultValue"));
                                value.settings.put("mode", "FILE");
                            } else {
                                value.value = (String) n.settings.get("defaultValue");
                                value.settings.put(
                                        "literalType",
                                        port(n).type.equals("JSON") ? "JSON" : "TEXT");
                            }
                            flat.edges.add(new WorkflowApp.Edge(value.id, gate.id));
                            bound = true;
                        }
                        if (!bound && (Boolean) n.settings.get("required"))
                            throw new IOException(
                                    scope.path + "：必需接口 " + port(n).name + " 没有父图绑定或默认值。");
                    }
        // 接口约束检查完成后，未使用的具名输出不参与实际计算。
        // After interface validation, only dependencies of real root outputs remain executable.
        Set<String> needed = new HashSet<>();
        for (var n : flat.nodes)
            if (n.kind == WorkflowApp.Kind.SAVE) visit(flat, n.id, needed, new HashSet<>());
        flat.nodes.removeIf(n -> !needed.contains(n.id));
        flat.edges.removeIf(e -> !needed.contains(e.from()) || !needed.contains(e.to()));
        return new Expansion(flat, List.copyOf(contexts));
    }

    static void visit(WorkflowApp.Flow f, String id, Set<String> done, Set<String> stack)
            throws IOException {
        if (stack.contains(id)) throw new IOException("展开后的子图形成环路。");
        if (!done.add(id)) return;
        stack.add(id);
        for (var e : f.edges) if (e.to().equals(id)) visit(f, e.from(), done, stack);
        stack.remove(id);
    }

    /** 用调用作用域生成运行时 ID，隔离同一子图的不同实例。 / Scope runtime identities per call. */
    static void collect(
            Scope scope,
            WorkflowApp.Flow flat,
            List<Object> contexts,
            Map<String, Scope> scopes,
            Map<String, WorkflowApp.Node> nodes,
            Set<WorkflowApp.Flow> stack,
            int depth)
            throws IOException {
        if (depth > MAX_DEPTH || !stack.add(scope.flow)) throw new IOException("子图存在递归引用或超过8层嵌套。");
        scopes.put(scope.path, scope);
        if (scope.module) validateInterfaces(scope.flow);
        Set<String> dagNodes = new HashSet<>();
        for (var n : scope.flow.nodes) visit(scope.flow, n.id, dagNodes, new HashSet<>());
        for (var n : scope.flow.nodes)
            if (n.kind == WorkflowApp.Kind.VIRTUAL_OUT
                    && scope.flow.edges.stream()
                            .noneMatch(e -> e.to().equals(n.id) && e.port().equals("data")))
                throw new IOException(scope.path + "：虚拟输出 " + port(n).name + " 没有内部来源。");
        if (scope.module) {
            Set<String> closed = new HashSet<>();
            for (var n : scope.flow.nodes)
                if (n.kind == WorkflowApp.Kind.VIRTUAL_OUT)
                    visit(scope.flow, n.id, closed, new HashSet<>());
            for (var n : scope.flow.nodes)
                if (n.kind != WorkflowApp.Kind.VIRTUAL_IN && !closed.contains(n.id))
                    throw new IOException(scope.path + "：内部步骤没有接入虚拟输出，" + n.name);
        }
        Set<String> originalIds = new HashSet<>();
        for (var n : scope.flow.nodes)
            if (n.id == null
                    || n.id.isBlank()
                    || n.id.contains("/")
                    || n.id.contains("\\")
                    || !originalIds.add(n.id)) throw new IOException("节点标识重复或含非法作用域分隔符。");
        for (var n : scope.flow.nodes) {
            if (scope.module && n.kind == WorkflowApp.Kind.SAVE)
                throw new IOException(scope.path + "：子图内部请使用虚拟输出，实际保存由父图决定。");
            if (!scope.module && boundary(n.kind)) throw new IOException("虚拟接口不能作为顶层输入输出运行，请放入子图。");
            if (n.kind == WorkflowApp.Kind.SUBGRAPH) {
                if (n.child == null || !n.settings.get("interfaceVersion").equals(1))
                    throw new IOException("子图定义或接口版本无效。");
                collect(
                        new Scope(n.child, scope.path + "/" + n.id, true),
                        flat,
                        contexts,
                        scopes,
                        nodes,
                        stack,
                        depth + 1);
                continue;
            }
            String key = scope.path + "/" + n.id;
            var c =
                    new WorkflowApp.Node(
                            UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),
                            n.kind,
                            n.x,
                            n.y);
            c.name = n.name;
            c.value = n.value;
            c.settings.clear();
            c.settings.putAll(n.settings);
            if (boundary(n.kind)) {
                if (Boolean.TRUE.equals(n.settings.get("internalGate")))
                    throw new IOException("文件不可声明内部执行标记。");
                c.settings.put("internalGate", true);
            }
            flat.nodes.add(c);
            nodes.put(key, c);
            contexts.add(
                    Map.of(
                            "runtimeNodeId",
                            c.id,
                            "scope",
                            scope.path,
                            "originalNodeId",
                            n.id,
                            "name",
                            n.name));
            if (flat.nodes.size() > 5000) throw new IOException("展开后节点超过5000个。");
        }
        stack.remove(scope.flow);
    }

    static Endpoint endpoint(
            Scope scope,
            WorkflowApp.Node n,
            boolean out,
            String port,
            Map<String, Scope> scopes,
            Map<String, WorkflowApp.Node> nodes)
            throws IOException {
        if (n.kind != WorkflowApp.Kind.SUBGRAPH)
            return new Endpoint(nodes.get(scope.path + "/" + n.id), port);
        Scope child = scopes.get(scope.path + "/" + n.id);
        var match =
                child.flow.nodes.stream()
                        .filter(
                                x ->
                                        x.kind
                                                        == (out
                                                                ? WorkflowApp.Kind.VIRTUAL_OUT
                                                                : WorkflowApp.Kind.VIRTUAL_IN)
                                                && port(x).name.equals(port))
                        .toList();
        if (match.size() != 1) throw new IOException("子图接口不存在或重复：" + port);
        return new Endpoint(nodes.get(child.path + "/" + match.get(0).id), "data");
    }

    /**
     * 为选中步骤生成边界端口；先检查候选图，成功后才替换原图内容。
     *
     * <p>Builds interface nodes around a selection and validates the candidate before replacing it.
     */
    static WorkflowApp.Node encapsulate(WorkflowApp.Flow f, Set<String> selected)
            throws IOException {
        if (selected.isEmpty()) throw new IOException("请选择工作步骤。");
        var chosen = f.nodes.stream().filter(n -> selected.contains(n.id)).toList();
        if (chosen.size() != selected.size()
                || chosen.stream()
                        .anyMatch(
                                n ->
                                        !ExecutableTools.WORK.contains(n.kind)
                                                && n.kind != WorkflowApp.Kind.SUBGRAPH))
            throw new IOException("只能封装工作元素或已有子图。");
        for (var edge : f.edges)
            if (f.node(edge.from()) == null
                    || f.node(edge.to()) == null
                    || incompatible(
                            f.node(edge.from()), edge.fromPort(), f.node(edge.to()), edge.port()))
                throw new IOException("请先修正无效连线，再封装步骤。");
        var child = new WorkflowApp.Flow();
        child.title = "封装的处理步骤";
        double x = chosen.stream().mapToDouble(n -> n.x).min().orElse(50),
                y = chosen.stream().mapToDouble(n -> n.y).min().orElse(100);
        for (var n : chosen) {
            var c = new WorkflowApp.Node(n.id, n.kind, n.x - x + 250, n.y - y + 100);
            c.name = n.name;
            c.value = n.value;
            c.settings.clear();
            c.settings.putAll(n.settings);
            c.child = copy(n.child);
            child.nodes.add(c);
        }
        List<WorkflowApp.Edge> external = new ArrayList<>();
        var module =
                new WorkflowApp.Node(UUID.randomUUID().toString(), WorkflowApp.Kind.SUBGRAPH, x, y);
        Map<String, String> ins = new LinkedHashMap<>(), outs = new LinkedHashMap<>();
        for (var e : f.edges) {
            boolean a = selected.contains(e.from()), b = selected.contains(e.to());
            if (a && b) {
                child.edges.add(e);
                continue;
            }
            if (!a && !b) continue;
            if (b) {
                String key = e.from() + "/" + e.fromPort();
                String name = ins.get(key);
                if (name == null) {
                    name = ins.isEmpty() ? "input" : "input" + (ins.size() + 1);
                    ins.put(key, name);
                    var v = child.add(WorkflowApp.Kind.VIRTUAL_IN, 25, 80 + ins.size() * 160);
                    v.settings.put("interfaceName", name);
                    var sourcePort = find(f.node(e.from()), true, e.fromPort());
                    v.settings.put("dataType", sourcePort.type);
                    v.settings.put(
                            "cardinality",
                            sourcePort.cardinality.equals("DYNAMIC")
                                    ? "COLLECTION"
                                    : sourcePort.cardinality);
                    v.settings.put("required", find(f.node(e.to()), false, e.port()).required);
                    external.add(new WorkflowApp.Edge(e.from(), module.id, name, e.fromPort()));
                }
                var v =
                        child.nodes.stream()
                                .filter(
                                        n ->
                                                n.kind == WorkflowApp.Kind.VIRTUAL_IN
                                                        && port(n).name.equals(ins.get(key)))
                                .findFirst()
                                .orElseThrow();
                child.edges.add(new WorkflowApp.Edge(v.id, e.to(), e.port(), "data"));
            } else {
                String key = e.from() + "/" + e.fromPort();
                String name = outs.get(key);
                if (name == null) {
                    name = outs.isEmpty() ? "output" : "output" + (outs.size() + 1);
                    outs.put(key, name);
                    var v = child.add(WorkflowApp.Kind.VIRTUAL_OUT, 800, 80 + outs.size() * 160);
                    v.settings.put("interfaceName", name);
                    var sourcePort = find(f.node(e.from()), true, e.fromPort());
                    v.settings.put("dataType", sourcePort.type);
                    v.settings.put(
                            "cardinality",
                            sourcePort.cardinality.equals("DYNAMIC")
                                    ? "COLLECTION"
                                    : sourcePort.cardinality);
                    child.edges.add(new WorkflowApp.Edge(e.from(), v.id, "data", e.fromPort()));
                }
                external.add(new WorkflowApp.Edge(module.id, e.to(), e.port(), name));
            }
        }
        // 动态数量连接到单项端口时，把限制保留为运行时边界断言。
        // Preserve a single-item consumer's constraint as an explicit boundary assertion.
        for (var e : f.edges) {
            if (!selected.contains(e.from())
                    && selected.contains(e.to())
                    && find(f.node(e.from()), true, e.fromPort()).cardinality.equals("DYNAMIC")
                    && find(f.node(e.to()), false, e.port()).cardinality.equals("SINGLE")) {
                String name = ins.get(e.from() + "/" + e.fromPort());
                child.nodes.stream()
                        .filter(
                                n ->
                                        n.kind == WorkflowApp.Kind.VIRTUAL_IN
                                                && port(n).name.equals(name))
                        .forEach(n -> n.settings.put("cardinality", "SINGLE"));
            }
            if (selected.contains(e.from())
                    && !selected.contains(e.to())
                    && find(f.node(e.from()), true, e.fromPort()).cardinality.equals("DYNAMIC")
                    && find(f.node(e.to()), false, e.port()).cardinality.equals("SINGLE")) {
                String name = outs.get(e.from() + "/" + e.fromPort());
                child.nodes.stream()
                        .filter(
                                n ->
                                        n.kind == WorkflowApp.Kind.VIRTUAL_OUT
                                                && port(n).name.equals(name))
                        .forEach(n -> n.settings.put("cardinality", "SINGLE"));
            }
        }
        for (var v : child.nodes)
            if (v.kind == WorkflowApp.Kind.VIRTUAL_IN
                    && child.edges.stream()
                            .filter(e -> e.from().equals(v.id))
                            .anyMatch(e -> find(child.node(e.to()), false, e.port()).required))
                v.settings.put("required", true);
        if (outs.isEmpty()) throw new IOException("封装步骤需要至少一条通向外部的输出连线。");
        validateInterfaces(child);
        module.child = child;
        var candidate = new WorkflowApp.Flow();
        candidate.title = f.title;
        for (var n : f.nodes) if (!selected.contains(n.id)) candidate.nodes.add(n);
        candidate.nodes.add(module);
        for (var e : f.edges)
            if (!selected.contains(e.from()) && !selected.contains(e.to())) candidate.edges.add(e);
        candidate.edges.addAll(external);
        Set<String> dag = new HashSet<>();
        try {
            for (var n : candidate.nodes) visit(candidate, n.id, dag, new HashSet<>());
            copy(candidate);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IOException("封装会产生环路或超过嵌套限制，请选择连续的处理段。", ex);
        }
        f.nodes.clear();
        f.nodes.addAll(candidate.nodes);
        f.edges.clear();
        f.edges.addAll(candidate.edges);
        return module;
    }
}

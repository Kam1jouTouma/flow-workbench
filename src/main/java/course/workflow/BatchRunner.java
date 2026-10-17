package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流执行入口：先展开子图，再并行计算依赖，最后统一发布文件。
 *
 * <p>Runs an expanded DAG with shared results; publication starts only after all tasks succeed.
 */
final class BatchRunner {
    interface WarningHandler {
        void show(List<String> warnings) throws IOException;
    }

    interface CommitObserver {
        void afterCommit(int count) throws IOException;
    }

    CommitObserver commitObserver = count -> {};
    final Set<Path> foldersToOpen = new LinkedHashSet<>();

    /** 端口工件与逐项来源保持对应。 / Port artifacts and per-item provenance travel together. */
    record Bundle(Map<String, Object> ports, List<Object> sources) {}

    record Plan(Path payload, Path destination, String hash, Path representative) {}

    final WorkflowApp.Flow flow;
    final Path run;
    final String runId;
    final Map<String, Object> record = Collections.synchronizedMap(new LinkedHashMap<>());
    final Map<String, Bundle> memo = Collections.synchronizedMap(new HashMap<>());
    final WarningHandler warnings;

    private BatchRunner(WorkflowApp.Flow flow, Path runs, WarningHandler warnings)
            throws IOException {
        this.flow = flow;
        this.warnings = warnings;
        runId = UUID.randomUUID().toString();
        run = runs.toAbsolutePath().resolve(runId);
        Files.createDirectories(run);
        record.put("runId", runId);
        record.put("state", "RUNNING");
        record.put("startedAt", Instant.now().toString());
        record.put(
                "nodes",
                flow.nodes.stream()
                        .map(n -> Map.of("id", n.id, "kind", n.kind.name(), "settings", n.settings))
                        .toList());
        save();
    }

    /** 对外执行入口，警告交由调用者呈现。 / Entry point with caller-owned warning presentation. */
    static FileTransferRunner.Result execute(
            WorkflowApp.Flow flow, Path runs, WarningHandler warnings) throws IOException {
        return execute(flow, runs, warnings, count -> {});
    }

    static FileTransferRunner.Result execute(
            WorkflowApp.Flow flow, Path runs, WarningHandler warnings, CommitObserver observer)
            throws IOException {
        Subgraphs.Expansion expansion = Subgraphs.expand(flow);
        flow = expansion.flow();
        List<WorkflowApp.Node> outputs =
                flow.nodes.stream().filter(n -> n.kind == WorkflowApp.Kind.SAVE).toList();
        if (outputs.isEmpty()) throw new IOException("请至少连接一个文件输出。");
        if (flow.nodes.stream()
                .anyMatch(
                        n ->
                                n.kind != WorkflowApp.Kind.FILE
                                        && n.kind != WorkflowApp.Kind.TEXT
                                        && n.kind != WorkflowApp.Kind.SAVE
                                        && !Subgraphs.boundary(n.kind)
                                        && !ExecutableTools.WORK.contains(n.kind)))
            throw new IOException("存在尚未接入执行的元素。");
        List<String> issues = new ArrayList<>();
        for (var node : flow.nodes) issues.addAll(IoConfig.diagnostics(node));
        for (var edge : flow.edges)
            if (WorkflowApp.Flow.incompatible(
                    flow.node(edge.from()), edge.fromPort(), flow.node(edge.to()), edge.port()))
                issues.add("连线不兼容：" + edge.port());
        if (!issues.isEmpty()) throw new IOException(String.join("\n", issues));
        for (WorkflowApp.Node output : outputs) {
            if (!output.settings.get("format").equals("KEEP"))
                throw new IOException("批处理输出请选择保留收到的内容。");
            if (output.settings.get("location").equals("DIRECTORY")) {
                Path destination =
                        FileTransferRunner.realPath(
                                Path.of((String) output.settings.get("directory"))
                                        .toAbsolutePath()
                                        .normalize());
                for (var node : flow.nodes)
                    if (node.kind == WorkflowApp.Kind.FILE
                            && !node.settings.get("selectionMode").equals("SINGLE")
                            && destination.startsWith(
                                    Path.of((String) node.settings.get("sourcePath")).toRealPath()))
                        throw new IOException("指定保存目录不能位于批量源目录内部；如需原地输出，请选择各自原文件所在目录。");
            }
        }
        Set<String> visited = new HashSet<>();
        for (var output : outputs) upstream(flow, output, visited);
        if (visited.size() != flow.nodes.size()) throw new IOException("存在没有接入最终输出的节点。");
        BatchRunner runner = new BatchRunner(flow, runs, warnings);
        runner.commitObserver = observer;
        runner.record.put("subgraphContexts", expansion.contexts());
        runner.save();
        try {
            runner.schedule();
            runner.record.put(
                    "operation",
                    outputs.stream()
                                            .map(n -> (String) n.settings.get("operation"))
                                            .distinct()
                                            .count()
                                    == 1
                            ? outputs.get(0).settings.get("operation")
                            : "MIXED");
            runner.save();
            List<FileTransferRunner.Published> published =
                    BatchPublication.publish(runner, outputs);
            runner.record.put("state", "SUCCEEDED");
            runner.record.put("finishedAt", Instant.now().toString());
            runner.save();
            String operation =
                    outputs.stream()
                                            .map(n -> (String) n.settings.get("operation"))
                                            .distinct()
                                            .count()
                                    == 1
                            ? (String) outputs.get(0).settings.get("operation")
                            : "MIXED";
            return new FileTransferRunner.Result(
                    runner.runId,
                    runner.run.resolve("run.json"),
                    published,
                    operation,
                    List.copyOf(runner.foldersToOpen));
        } catch (IOException | RuntimeException ex) {
            runner.record.put("state", "FAILED");
            runner.record.put("error", ex.toString());
            runner.record.put("finishedAt", Instant.now().toString());
            runner.save();
            throw new IOException(ex.getMessage() + "\n运行记录：" + runner.run.resolve("run.json"), ex);
        }
    }

    static void upstream(WorkflowApp.Flow flow, WorkflowApp.Node node, Set<String> visited)
            throws IOException {
        if (!visited.add(node.id)) return;
        for (var edge : flow.edges)
            if (edge.to().equals(node.id)) upstream(flow, flow.node(edge.from()), visited);
    }

    /** 每个输入端口最多一个上游；可选端口可缺省。 / Resolve one producer per input port. */
    WorkflowApp.Node source(WorkflowApp.Node target, String port, boolean required)
            throws IOException {
        List<WorkflowApp.Edge> edges =
                flow.edges.stream()
                        .filter(e -> e.to().equals(target.id) && e.port().equals(port))
                        .toList();
        if (edges.size() != 1) {
            if (!required && edges.isEmpty()) return null;
            throw new IOException(target.name + " 的 " + port + " 端口需要一个来源。");
        }
        return flow.node(edges.get(0).from());
    }

    /**
     * 在节点专属目录生成工件；已完成上游从缓存取得，不直接写入用户的输出目录。
     *
     * <p>Evaluates a node in its staging directory and reuses completed upstream bundles.
     */
    Bundle evaluate(WorkflowApp.Node node) throws IOException {
        if (memo.containsKey(node.id)) return memo.get(node.id);
        if (Subgraphs.boundary(node.kind)) {
            var upstream =
                    source(
                            node,
                            "data",
                            node.kind == WorkflowApp.Kind.VIRTUAL_OUT
                                    || (Boolean) node.settings.get("required"));
            Bundle value = upstream == null ? null : evaluate(upstream);
            InternalData.validate(node, value);
            memo.put(node.id, value);
            append(
                    "boundaryChecks",
                    Map.of(
                            "runtimeNodeId",
                            node.id,
                            "interface",
                            node.settings.get("interfaceName"),
                            "type",
                            value == null
                                    ? "ABSENT"
                                    : Json.object(value.ports.get("data")).get("type"),
                            "items",
                            value == null
                                    ? 0
                                    : Json.list(Json.object(value.ports.get("data")).get("items"))
                                            .size()));
            return value;
        }
        Path stage = run.resolve(node.id);
        Files.createDirectories(stage);
        String jar, expected;
        Map<String, Object> incoming = new LinkedHashMap<>(), parameters = new LinkedHashMap<>();
        if (node.kind == WorkflowApp.Kind.FILE_MERGE) {
            Bundle merged = merge(node, stage);
            memo.put(node.id, merged);
            return merged;
        }
        if (node.kind == WorkflowApp.Kind.TEXT) {
            Bundle value = InternalData.literal(node, stage);
            memo.put(node.id, value);
            return value;
        }
        if (node.kind == WorkflowApp.Kind.FILE) {
            jar = "file-input-tool-v1.jar";
            expected = "io.file_select";
            for (String key : List.of("sourcePath", "selectionMode", "extensions", "recursive"))
                parameters.put(key, node.settings.get(key));
        } else {
            Bundle upstream = evaluate(source(node, "data", true));
            if (upstream == null) throw new IOException(node.name + "：必需数据输入缺失。");
            incoming.putAll(upstream.ports);
            if (node.kind == WorkflowApp.Kind.SCRIPT) {
                ScriptTools.validate(node);
                ScriptTools.checkData(node, Json.object(incoming.get("data")), false);
                jar = (String) node.settings.get("scriptPath");
                expected = (String) node.settings.get("toolId");
                parameters.putAll(ScriptTools.parameters(node));
            } else if (node.kind == WorkflowApp.Kind.PDF) {
                jar = "pdf-tool-v1.jar";
                expected = "batch.pdf";
                parameters.putAll(node.settings);
            } else if (node.kind == WorkflowApp.Kind.RENAME) {
                jar = "rename-tool-v1.jar";
                expected = "batch.rename";
                parameters.putAll(node.settings);
                WorkflowApp.Node csv = source(node, "csv", false);
                if (csv != null) {
                    Bundle table = evaluate(csv);
                    if (table != null) {
                        Map<String, Object> data = Json.object(table.ports.get("data"));
                        if (Json.list(data.get("items")).size() != 1)
                            throw new IOException("CSV输入要求单个文件。");
                        incoming.put("csv", data);
                    }
                }
            } else if (ExecutableTools.WORK.contains(node.kind)) {
                jar = ExecutableTools.file(node.kind);
                expected = ExecutableTools.id(node.kind);
                parameters.putAll(node.settings);
                if (node.kind == WorkflowApp.Kind.JAVA_BUILD
                        && ((String) parameters.get("jdkPath")).isBlank())
                    parameters.put("jdkPath", System.getProperty("java.home"));
            } else throw new IOException("不支持的工作元素。");
        }
        Map<String, Object> capability =
                ElementCapabilities.discover(FileTransferRunner.toolPath(jar));
        if (!expected.equals(capability.get("toolId"))) throw new IOException("工具身份不匹配。");
        if (!"AVAILABLE".equals(capability.get("implementation")))
            throw new IOException(node.name + "：缺少工具依赖，请查看安装包使用说明。音频转换需要安装音频组件。");
        append("toolCapabilities", capability);
        Path request = run.resolve(node.id + "-request.json"),
                config = run.resolve(node.id + "-config.json");
        Json.write(
                request,
                Map.of(
                        "protocolVersion",
                        1,
                        "runId",
                        runId,
                        "nodeId",
                        node.id,
                        "inputs",
                        incoming));
        Json.write(config, Map.of("protocolVersion", 1, "parameters", parameters));
        FileTransferRunner.invoke(jar, request, config, stage, run.resolve(node.id + "-log.txt"));
        Map<String, Object> ports =
                Json.object(FileTransferRunner.checkedResult(stage, runId, node.id).get("outputs"));
        Map<String, Object> absolute = new LinkedHashMap<>();
        for (String port : List.of("data", "source")) {
            Map<String, Object> value = Json.object(ports.get(port));
            List<Object> items = new ArrayList<>();
            for (Object raw : Json.list(value.get("items"))) {
                Map<String, Object> item = new LinkedHashMap<>(Json.object(raw));
                item.put(
                        "path",
                        FileTransferRunner.artifact(stage, Json.string(item, "path")).toString());
                items.add(item);
            }
            absolute.put(port, Map.of("type", value.get("type"), "items", items));
        }
        if (node.kind == WorkflowApp.Kind.SCRIPT)
            ScriptTools.checkData(node, Json.object(absolute.get("data")), true);
        List<Object> sources =
                Json.list(Json.object(Json.read(stage.resolve("source.json"))).get("files"));
        if (Files.isRegularFile(stage.resolve("warnings.json"))) {
            List<String> messages = new ArrayList<>();
            for (Object warning :
                    Json.list(
                            Json.object(Json.read(stage.resolve("warnings.json"))).get("warnings")))
                messages.add((String) warning);
            if (!messages.isEmpty()) {
                record.put(node.id + "Warnings", messages);
                save();
                synchronized (warnings) {
                    warnings.show(messages);
                }
            }
        }
        Bundle result = new Bundle(absolute, sources);
        if (node.kind == WorkflowApp.Kind.FILE) result = InternalData.readMode(node, result, stage);
        memo.put(node.id, result);
        return result;
    }

    void append(String key, Object value) throws IOException {
        synchronized (record) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) record.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(value);
            save();
        }
    }

    /** 并行节点共用运行记录，写入时持有同一把锁。 / Serialize writes to the shared run record. */
    void save() throws IOException {
        synchronized (record) {
            Json.write(run.resolve("run.json"), record);
        }
    }

    /** 按端口顺序汇合，以来源、相对名和哈希去重。 / Merge in port order using provenance identity. */
    Bundle merge(WorkflowApp.Node node, Path stage) throws IOException {
        List<Object> items = new ArrayList<>(), sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var port : node.inputPorts()) {
            Bundle b = evaluate(source(node, port.name(), true));
            if (b == null) throw new IOException("汇合输入缺失：" + port.name());
            Map<String, Object> data = Json.object(b.ports.get("data"));
            if (!"FILE".equals(data.get("type"))) throw new IOException("集合汇合只接受文件数据。");
            List<Object> incoming = Json.list(data.get("items"));
            if (incoming.size() != b.sources.size()) throw new IOException("汇合来源数量不匹配。");
            for (int i = 0; i < incoming.size(); i++) {
                Map<String, Object> metadata = Json.object(b.sources.get(i));
                String key =
                        metadata.get("originalPath")
                                + "|"
                                + metadata.get("relativePath")
                                + "|"
                                + metadata.get("sha256");
                if (seen.add(key)) {
                    items.add(incoming.get(i));
                    sources.add(metadata);
                }
            }
        }
        Path manifest = stage.resolve("source.json");
        Json.write(manifest, Map.of("files", sources));
        return new Bundle(
                Map.of(
                        "data",
                        FileCli.value("FILE", items),
                        "source",
                        FileCli.value("JSON", List.of(FileCli.item(manifest.toString())))),
                List.copyOf(sources));
    }

    /**
     * 仅调度依赖已完成的节点；失败后停止派发，并等候在途任务结束，再交还错误。
     *
     * <p>Schedules ready nodes; after a task failure, drains running work without publishing.
     */
    void schedule() throws IOException {
        List<WorkflowApp.Node> tasks =
                flow.nodes.stream().filter(n -> n.kind != WorkflowApp.Kind.SAVE).toList();
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (var node : tasks) {
            Set<String> parents = new HashSet<>();
            for (var edge : flow.edges) if (edge.to().equals(node.id)) parents.add(edge.from());
            remaining.put(node.id, parents.size());
            for (String parent : parents)
                dependents.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(node.id);
        }
        int workers = SystemSettings.current().workers();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (var node : tasks) if (remaining.get(node.id) == 0) ready.add(node.id);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        int running = 0, completed = 0;
        IOException failure = null;
        record.put(
                "scheduler",
                Map.of(
                        "workers",
                        workers,
                        "mode",
                        "DEPENDENCY_PARALLEL",
                        "failurePolicy",
                        "NO_PUBLICATION_ON_FAILURE"));
        save();
        try {
            while (completed < tasks.size()) {
                while (failure == null && running < workers && !ready.isEmpty()) {
                    String id = ready.remove();
                    WorkflowApp.Node node = flow.node(id);
                    running++;
                    completion.submit(
                            () -> {
                                Instant start = Instant.now();
                                int current = active.incrementAndGet();
                                peak.accumulateAndGet(current, Math::max);
                                String state = "SUCCEEDED", error = "";
                                try {
                                    evaluate(node);
                                    return id;
                                } catch (Exception ex) {
                                    state = "FAILED";
                                    error = ex.toString();
                                    throw ex;
                                } finally {
                                    active.decrementAndGet();
                                    append(
                                            "nodeExecutions",
                                            Map.of(
                                                    "nodeId",
                                                    id,
                                                    "kind",
                                                    node.kind.name(),
                                                    "startedAt",
                                                    start.toString(),
                                                    "finishedAt",
                                                    Instant.now().toString(),
                                                    "thread",
                                                    Thread.currentThread().getName(),
                                                    "state",
                                                    state,
                                                    "error",
                                                    error));
                                }
                            });
                }
                if (running == 0) {
                    if (failure != null) throw failure;
                    throw new IOException("任务依赖包含环路或无法调度。");
                }
                String finished = null;
                try {
                    finished = completion.take().get();
                } catch (ExecutionException ex) {
                    if (failure == null)
                        failure = new IOException(ex.getCause().getMessage(), ex.getCause());
                }
                running--;
                completed++;
                if (finished != null && failure == null)
                    for (String next : dependents.getOrDefault(finished, Set.of()))
                        if (remaining.merge(next, -1, Integer::sum) == 0) ready.add(next);
                if (failure != null && running == 0) throw failure;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("运行被中断，未发布输出。", ex);
        } finally {
            pool.shutdown();
            record.put("maxParallelNodes", peak.get());
            save();
        }
    }
}

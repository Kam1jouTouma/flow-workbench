package course.workflow;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 保留早期“文件输入→文件输出”执行路径及其回归测试，同时提供通用 CLI 与路径辅助方法。
 *
 * <p>The legacy two-node runner remains for regression tests; the editor uses {@link BatchRunner}.
 * Shared helpers below are also used by the current execution pipeline.
 */
final class FileTransferRunner {
    record Published(Path source, Path destination, String sha256) {}

    record Result(
            String runId,
            Path record,
            List<Published> files,
            String operation,
            List<Path> foldersToOpen) {
        Result(String runId, Path record, List<Published> files, String operation) {
            this(runId, record, files, operation, List.of());
        }
    }

    private FileTransferRunner() {}

    /** 早期双节点执行路径，不具备 BatchPublication 的多输出补偿流程。 / Legacy two-node path. */
    static Result execute(WorkflowApp.Flow flow, Path runs) throws IOException {
        if (flow.nodes.size() != 2 || flow.edges.size() != 1)
            throw new IOException("当前执行支持：一个文件输入直接连接一个文件输出。");
        WorkflowApp.Edge edge = flow.edges.get(0);
        WorkflowApp.Node input = flow.node(edge.from()), output = flow.node(edge.to());
        if (input.kind != WorkflowApp.Kind.FILE || output.kind != WorkflowApp.Kind.SAVE)
            throw new IOException("请使用文件输入 → 文件输出。");
        if (!input.settings.get("mode").equals("FILE")
                || !output.settings.get("format").equals("KEEP"))
            throw new IOException("文件复制/搬移请将读取模式设为保留原文件，输出形式设为保留收到的内容。");
        List<String> issues = new ArrayList<>(IoConfig.diagnostics(input));
        issues.addAll(IoConfig.diagnostics(output));
        if (!issues.isEmpty()) throw new IOException(String.join("\n", issues));
        Path original = Path.of((String) input.settings.get("sourcePath")).toRealPath();
        Path destination =
                Path.of((String) output.settings.get("directory")).toAbsolutePath().normalize();
        String selection = (String) input.settings.get("selectionMode"),
                action = (String) output.settings.get("operation");
        if (!selection.equals("SINGLE") && action.equals("MOVE"))
            throw new IOException("首版批量操作只支持复制；搬移请选择单个文件。");
        if (!selection.equals("SINGLE") && realPath(destination).startsWith(original))
            throw new IOException("目标目录不能位于批量源目录内部。");
        String runId = UUID.randomUUID().toString();
        Path run = runs.toAbsolutePath().resolve(runId);
        Files.createDirectories(run);
        Path recordPath = run.resolve("run.json");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("runId", runId);
        record.put("state", "RUNNING");
        record.put("startedAt", Instant.now().toString());
        record.put("operation", action);
        record.put("inputSettings", new LinkedHashMap<>(input.settings));
        record.put("outputSettings", new LinkedHashMap<>(output.settings));
        Json.write(recordPath, record);
        List<Published> published = new ArrayList<>();
        try {
            Map<String, Object> inputCapability =
                    ElementCapabilities.discover(toolPath("file-input-tool-v1.jar"));
            Map<String, Object> outputCapability =
                    ElementCapabilities.discover(toolPath("file-output-tool-v1.jar"));
            if (!inputCapability.get("toolId").equals("io.file_select")
                    || !outputCapability.get("toolId").equals("io.file_export"))
                throw new IOException("发现的工具身份不匹配。");
            record.put("toolCapabilities", List.of(inputCapability, outputCapability));
            Json.write(recordPath, record);
            Path inStage = run.resolve("input"), outStage = run.resolve("output");
            Files.createDirectories(inStage);
            Files.createDirectories(outStage);
            Path inInput = run.resolve("input-request.json"),
                    inConfig = run.resolve("input-config.json");
            Json.write(
                    inInput,
                    Map.of(
                            "protocolVersion",
                            1,
                            "runId",
                            runId,
                            "nodeId",
                            input.id,
                            "inputs",
                            Map.of()));
            Json.write(
                    inConfig,
                    Map.of(
                            "protocolVersion",
                            1,
                            "parameters",
                            Map.of(
                                    "sourcePath",
                                    original.toString(),
                                    "selectionMode",
                                    selection,
                                    "extensions",
                                    input.settings.get("extensions"),
                                    "recursive",
                                    input.settings.get("recursive"))));
            invoke(
                    "file-input-tool-v1.jar",
                    inInput,
                    inConfig,
                    inStage,
                    run.resolve("input-log.txt"));
            Map<String, Object> inResult = checkedResult(inStage, runId, input.id),
                    ports = Json.object(inResult.get("outputs"));
            List<Object> snapshots = Json.list(Json.object(ports.get("data")).get("items"));
            List<Object> sourceEntries =
                    Json.list(Json.object(Json.read(inStage.resolve("source.json"))).get("files"));
            ElementCapabilities.Contract outputContract = ElementCapabilities.inputContract(output);
            if (outputContract.cardinality().equals("SINGLE") && sourceEntries.size() != 1)
                throw new IOException("输出工具声明只接受单项文件，当前输入为文件集合。");
            List<Object> typeChecks = new ArrayList<>();
            for (Object raw : sourceEntries) {
                Map<String, Object> source = Json.object(raw);
                Path snapshot =
                        artifact(inStage, "payloads/" + Json.string(source, "relativePath"));
                FileTypeDetector.Detection detection = FileTypeDetector.detect(snapshot);
                String rejection = ElementCapabilities.rejection(outputContract, detection);
                typeChecks.add(
                        Map.of(
                                "path",
                                source.get("originalPath"),
                                "detection",
                                detection.json(),
                                "accepted",
                                rejection == null));
                if (rejection != null)
                    throw new IOException(
                            "文件类型不兼容：" + source.get("originalPath") + " / " + rejection);
            }
            record.put("fileTypeChecks", typeChecks);
            Json.write(recordPath, record);
            if (snapshots.size() != sourceEntries.size() || snapshots.isEmpty())
                throw new IOException("输入项目或来源数量错误。");
            Map<String, Object> incoming = new LinkedHashMap<>();
            for (String name : List.of("data", "source")) {
                Map<String, Object> port = Json.object(ports.get(name));
                List<Object> absoluteItems = new ArrayList<>();
                for (Object raw : Json.list(port.get("items"))) {
                    Map<String, Object> item = Json.object(raw);
                    Map<String, Object> abs = new LinkedHashMap<>(item);
                    abs.put("path", artifact(inStage, Json.string(item, "path")).toString());
                    absoluteItems.add(abs);
                }
                incoming.put(name, Map.of("type", port.get("type"), "items", absoluteItems));
            }
            Path outInput = run.resolve("output-request.json"),
                    outConfig = run.resolve("output-config.json");
            Json.write(
                    outInput,
                    Map.of(
                            "protocolVersion",
                            1,
                            "runId",
                            runId,
                            "nodeId",
                            output.id,
                            "inputs",
                            incoming));
            Json.write(outConfig, Map.of("protocolVersion", 1, "parameters", Map.of()));
            invoke(
                    "file-output-tool-v1.jar",
                    outInput,
                    outConfig,
                    outStage,
                    run.resolve("output-log.txt"));
            Map<String, Object> outResult = checkedResult(outStage, runId, output.id);
            List<Object> artifacts =
                    Json.list(
                            Json.object(Json.object(outResult.get("outputs")).get("data"))
                                    .get("items"));
            if (artifacts.size() != sourceEntries.size()) throw new IOException("输出数量不一致。");
            String policy = (String) output.settings.get("conflictPolicy");
            boolean originalNames =
                    (Boolean) output.settings.get("preserveNames") || !selection.equals("SINGLE");
            List<Path> planned = new ArrayList<>();
            Set<Path> unique = new HashSet<>();
            for (int i = 0; i < sourceEntries.size(); i++) {
                Map<String, Object> source = Json.object(sourceEntries.get(i));
                String relative =
                        originalNames
                                ? Json.string(source, "relativePath")
                                : (String) output.settings.get("fileName");
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination) || !unique.add(target))
                    throw new IOException("目标文件路径或名称冲突。");
                Path sourcePath = Path.of(Json.string(source, "originalPath"));
                if (realPath(target).equals(sourcePath.toRealPath()))
                    throw new IOException("目标不能覆盖本次原始输入。");
                if (!realPath(target).startsWith(realPath(destination)))
                    throw new IOException("目标真实路径越界。");
                if (policy.equals("ERROR") && Files.exists(target))
                    throw new IOException("目标已存在：" + target);
                if (Files.isDirectory(target)) throw new IOException("目标文件名已被目录占用：" + target);
                Path payload =
                        artifact(outStage, Json.string(Json.object(artifacts.get(i)), "path"));
                if (!FileCli.sha256(payload).equals(source.get("sha256")))
                    throw new IOException("输出内容哈希不一致。");
                planned.add(target);
            }
            for (int i = 0; i < artifacts.size(); i++) {
                Path target = planned.get(i);
                Files.createDirectories(target.getParent());
                Path payload =
                        artifact(outStage, Json.string(Json.object(artifacts.get(i)), "path"));
                Path temporary = Files.createTempFile(target.getParent(), ".workflow-", ".tmp");
                try {
                    Files.copy(payload, temporary, StandardCopyOption.REPLACE_EXISTING);
                    if (policy.equals("RENAME")) {
                        int suffix = 0;
                        Path requested = target;
                        while (true) {
                            target = suffix == 0 ? requested : numbered(requested, suffix);
                            try {
                                Files.move(temporary, target);
                                break;
                            } catch (FileAlreadyExistsException ex) {
                                suffix++;
                            }
                        }
                    } else if (policy.equals("OVERWRITE")) {
                        try {
                            Files.move(
                                    temporary,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException ex) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else Files.move(temporary, target);
                    Map<String, Object> source = Json.object(sourceEntries.get(i));
                    String hash = Json.string(source, "sha256");
                    if (!FileCli.sha256(target).equals(hash)) throw new IOException("最终文件哈希不一致。");
                    published.add(
                            new Published(
                                    Path.of(Json.string(source, "originalPath")), target, hash));
                    record.put(
                            "files",
                            published.stream()
                                    .map(
                                            p ->
                                                    Map.of(
                                                            "source",
                                                            p.source.toString(),
                                                            "destination",
                                                            p.destination.toString(),
                                                            "sha256",
                                                            p.sha256))
                                    .toList());
                    Json.write(recordPath, record);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (action.equals("MOVE")) {
                Published item = published.get(0);
                if (!FileCli.sha256(item.source).equals(item.sha256))
                    throw new IOException("源文件已变化，保留源文件且不执行删除。");
                Files.delete(item.source);
                record.put("sourceRemoved", true);
            } else record.put("sourceRemoved", false);
            record.put("state", "SUCCEEDED");
            record.put("finishedAt", Instant.now().toString());
            Json.write(recordPath, record);
            List<Path> folders =
                    Boolean.TRUE.equals(output.settings.get("openFolderAfterSuccess"))
                            ? published.stream()
                                    .map(
                                            p ->
                                                    p.destination()
                                                            .toAbsolutePath()
                                                            .normalize()
                                                            .getParent())
                                    .distinct()
                                    .toList()
                            : List.of();
            return new Result(runId, recordPath, List.copyOf(published), action, folders);
        } catch (IOException | RuntimeException ex) {
            record.put("state", "FAILED");
            record.put("error", ex.toString());
            record.put("finishedAt", Instant.now().toString());
            Json.write(recordPath, record);
            throw new IOException(ex.getMessage() + "\n运行记录：" + recordPath, ex);
        }
    }

    /** 解析已有祖先的真实路径，保留尚未创建的尾部。 / Resolve existing ancestors of a future path. */
    static Path realPath(Path path) throws IOException {
        if (Files.exists(path)) return path.toRealPath();
        Path parent = path.getParent();
        return parent == null
                ? path.toAbsolutePath().normalize()
                : realPath(parent).resolve(path.getFileName()).normalize();
    }

    static Path numbered(Path path, int number) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return path.resolveSibling(
                (dot > 0 ? name.substring(0, dot) : name)
                        + " ("
                        + number
                        + ")"
                        + (dot > 0 ? name.substring(dot) : ""));
    }

    /** 相对工件必须解析到暂存目录内的普通文件。 / Require an artifact inside its staging root. */
    static Path artifact(Path root, String relative) throws IOException {
        if (relative.contains("\\")
                || relative.contains(":")
                || relative.startsWith("/")
                || Arrays.stream(relative.split("/", -1))
                        .anyMatch(s -> s.isBlank() || s.equals(".") || s.equals("..")))
            throw new IOException("非法工件路径。");
        Path path = root.resolve(relative).toRealPath();
        if (!path.startsWith(root.toRealPath()) || !Files.isRegularFile(path))
            throw new IOException("工件路径越界或不可读取。");
        return path;
    }

    static Map<String, Object> checkedResult(Path output, String runId, String nodeId)
            throws IOException {
        Map<String, Object> result = Json.object(Json.read(output.resolve("result.json")));
        if (!result.get("runId").equals(runId) || !result.get("nodeId").equals(nodeId))
            throw new IOException("结果身份不匹配。");
        return result;
    }

    /**
     * 启动独立工具，收集双路日志并将超时或非零退出转为运行失败。
     *
     * <p>Invokes the CLI tool and translates process failures; result files are checked separately.
     */
    static void invoke(String tool, Path input, Path config, Path output, Path log)
            throws IOException {
        Path jar = toolPath(tool);
        if (!Files.isRegularFile(jar)) throw new IOException("工具未构建：" + jar);
        Path javaExecutable =
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command =
                ToolLauncher.command(
                        jar,
                        List.of(
                                "--input",
                                input.toString(),
                                "--config",
                                config.toString(),
                                "--output",
                                output.toString()));
        if (FileCli.extension(jar).equals("jar"))
            command.add(1, "-Djava.io.tmpdir=" + output.toAbsolutePath());
        Process process = ToolLauncher.start(command, output);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> stdout =
                readers.submit(
                        () ->
                                new String(
                                        process.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8));
        Future<String> stderr =
                readers.submit(
                        () ->
                                new String(
                                        process.getErrorStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8));
        try {
            int waitSeconds = 600;
            if (tool.equals("ai-text-tool-v1.jar")) {
                var parameters = Json.object(Json.object(Json.read(config)).get("parameters"));
                waitSeconds = AiTextTool.integer(parameters, "timeoutSeconds", 120, 1, 600) + 30;
            }
            if (!process.waitFor(waitSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
                throw new IOException("工具运行超时。");
            }
            String out = stdout.get(10, TimeUnit.SECONDS), err = stderr.get(10, TimeUnit.SECONDS);
            Files.writeString(
                    log, "stdout:\n" + out + "\nstderr:\n" + err + "\nexit=" + process.exitValue());
            if (process.exitValue() != 0)
                throw new IOException("工具失败：" + tool + " / " + err.strip());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(ex);
        } catch (ExecutionException | TimeoutException ex) {
            process.destroyForcibly();
            throw new IOException(ex);
        } finally {
            readers.shutdownNow();
        }
    }

    static Path toolPath(String tool) throws IOException {
        Path project;
        try {
            project =
                    Path.of(
                                    FileTransferRunner.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .getParent();
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
        return project.resolve(tool);
    }
}

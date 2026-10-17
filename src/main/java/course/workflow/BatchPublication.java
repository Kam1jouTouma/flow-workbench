package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 集中管理最终文件写入、源文件删除和失败补偿，工作插件只负责生成暂存工件。
 *
 * <p>Coordinates output preflight, publication and recovery; this is not a filesystem transaction.
 */
final class BatchPublication {
    private BatchPublication() {}

    record Plan(
            String outputId, Path payload, Path target, String hash, Path origin, String policy) {}

    /** 转换后的一个工件可追溯到多个原件；合成数据没有待删原件。 / Resolve original-file lineage. */
    static List<Map<String, Object>> ancestors(Object raw) throws IOException {
        Map<String, Object> entry = Json.object(raw);
        if (Boolean.TRUE.equals(entry.get("synthetic"))) return List.of();
        Object originals = entry.get("originals");
        List<Map<String, Object>> result = new ArrayList<>();
        if (originals instanceof List<?> list) {
            for (Object v : list) result.add(Json.object(v));
        } else result.add(Map.of("path", entry.get("originalPath"), "sha256", entry.get("sha256")));
        return result;
    }

    /** 所有输出共用预检和恢复计划。 / Build one publication and recovery plan for all outputs. */
    static List<FileTransferRunner.Published> publish(BatchRunner r, List<WorkflowApp.Node> outputs)
            throws IOException {
        // 汇总跨分支来源；任何 COPY 分支和 CSV 规则输入都会阻止对应原件被删除。
        // A COPY consumer or a CSV-rule input retains a source shared with MOVE consumers.
        Map<Path, String> allOriginals = new LinkedHashMap<>();
        Set<Path> kept = new HashSet<>(), csvFiles = new HashSet<>();
        Map<Path, String> moves = new LinkedHashMap<>();
        Map<String, BatchRunner.Bundle> bundles = new LinkedHashMap<>();
        for (var output : outputs) {
            BatchRunner.Bundle bundle = r.memo.get(r.source(output, "data", true).id);
            if (bundle == null) throw new IOException(output.name + "：没有收到数据。");
            bundles.put(output.id, bundle);
            for (Object raw : bundle.sources())
                for (var ancestor : ancestors(raw)) {
                    Path path = Path.of(Json.string(ancestor, "path")).toRealPath();
                    String hash = Json.string(ancestor, "sha256");
                    if (allOriginals.containsKey(path) && !allOriginals.get(path).equals(hash))
                        throw new IOException("不同分支的来源身份冲突。");
                    allOriginals.put(path, hash);
                    if (output.settings.get("operation").equals("MOVE")) moves.put(path, hash);
                    else kept.add(path);
                }
        }
        for (var edge : r.flow.edges)
            if (edge.port().equals("csv")) {
                var bundle = r.memo.get(edge.from());
                if (bundle != null)
                    for (Object raw : bundle.sources())
                        for (var a : ancestors(raw))
                            csvFiles.add(Path.of(Json.string(a, "path")).toRealPath());
            }
        moves.keySet().removeAll(kept);
        moves.keySet().removeAll(csvFiles);
        // 在实际发布前预留全部目标名，同时检查路径、类型、冲突和工件内容。
        // Reserve every destination before publication and validate paths, conflicts and payloads.
        Set<Path> reserved = new HashSet<>();
        List<Plan> plans = new ArrayList<>();
        List<Object> typeChecks = new ArrayList<>();
        var cap =
                ElementCapabilities.discover(
                        FileTransferRunner.toolPath("file-output-tool-v1.jar"));
        if (!"io.file_export".equals(cap.get("toolId"))) throw new IOException("输出工具身份不匹配。");
        var contract = ElementCapabilities.discoveredInput(cap);
        for (var output : outputs) {
            var originalBundle = bundles.get(output.id);
            Map<String, Object> ports = new LinkedHashMap<>(originalBundle.ports());
            Map<String, Object> dataValue = new LinkedHashMap<>(Json.object(ports.get("data")));
            dataValue.put("type", "FILE");
            ports.put("data", dataValue);
            List<Object> data = Json.list(dataValue.get("items"));
            if (data.isEmpty() || data.size() != originalBundle.sources().size())
                throw new IOException("输出工件数量不匹配。");
            for (Object raw : data) {
                Path path = Path.of(Json.string(Json.object(raw), "path"));
                var detected = FileTypeDetector.detect(path);
                String reason = ElementCapabilities.rejection(contract, detected);
                typeChecks.add(
                        Map.of(
                                "outputNodeId",
                                output.id,
                                "path",
                                path.toString(),
                                "detection",
                                detected.json(),
                                "accepted",
                                reason == null));
                if (reason != null) throw new IOException(reason);
            }
            Path stage = r.run.resolve("output-" + output.id);
            Files.createDirectories(stage);
            Path request = r.run.resolve(output.id + "-output-request.json"),
                    config = r.run.resolve(output.id + "-output-config.json");
            Json.write(
                    request,
                    Map.of(
                            "protocolVersion",
                            1,
                            "runId",
                            r.runId,
                            "nodeId",
                            output.id,
                            "inputs",
                            ports));
            Json.write(config, Map.of("protocolVersion", 1, "parameters", Map.of()));
            FileTransferRunner.invoke(
                    "file-output-tool-v1.jar",
                    request,
                    config,
                    stage,
                    r.run.resolve(output.id + "-output-log.txt"));
            List<Object> artifacts =
                    Json.list(
                            Json.object(
                                            Json.object(
                                                            FileTransferRunner.checkedResult(
                                                                            stage, r.runId,
                                                                            output.id)
                                                                    .get("outputs"))
                                                    .get("data"))
                                    .get("items"));
            if (artifacts.size() != data.size()) throw new IOException("输出结果数量不匹配。");
            Set<String> upstream = new HashSet<>();
            BatchRunner.upstream(r.flow, output, upstream);
            boolean derived =
                    r.flow.nodes.stream()
                            .anyMatch(
                                    n ->
                                            upstream.contains(n.id)
                                                    && ExecutableTools.WORK.contains(n.kind)
                                                    && n.kind != WorkflowApp.Kind.FILE_MERGE);
            String policy = (String) output.settings.get("conflictPolicy");
            boolean sameFolder = output.settings.get("location").equals("SOURCE");
            for (int i = 0; i < artifacts.size(); i++) {
                var metadata = Json.object(originalBundle.sources().get(i));
                Path origin = Path.of(Json.string(metadata, "originalPath")).toRealPath();
                if (sameFolder && Boolean.TRUE.equals(metadata.get("synthetic")))
                    throw new IOException("默认或手动数据没有原文件目录，请指定保存目录。");
                Path directory =
                        sameFolder
                                ? origin.getParent()
                                : Path.of((String) output.settings.get("directory"))
                                        .toAbsolutePath()
                                        .normalize();
                if (!Files.exists(directory) && !(Boolean) output.settings.get("createDirectories"))
                    throw new IOException("输出目录不存在。");
                String relative = Json.string(metadata, "relativePath");
                if (!derived && data.size() == 1 && !(Boolean) output.settings.get("preserveNames"))
                    relative = (String) output.settings.get("fileName");
                Path target =
                        directory
                                .resolve(
                                        sameFolder
                                                ? Path.of(relative).getFileName()
                                                : Path.of(relative))
                                .normalize();
                Path actual = FileTransferRunner.realPath(target);
                if (!actual.startsWith(FileTransferRunner.realPath(directory)))
                    throw new IOException("输出路径越界。");
                if (allOriginals.containsKey(actual) || csvFiles.contains(actual))
                    throw new IOException("输出不得覆盖本次任何分支的原始文件或CSV规则：" + target);
                if (Files.isDirectory(target)) throw new IOException("输出位置被目录占用。");
                if (policy.equals("RENAME")) {
                    Path wanted = target;
                    int index = 0;
                    while (Files.exists(target)
                            || reserved.contains(FileTransferRunner.realPath(target)))
                        target = FileTransferRunner.numbered(wanted, ++index);
                    actual = FileTransferRunner.realPath(target);
                }
                if (!reserved.add(actual)) throw new IOException("不同输出或工件使用同一个目标，无法覆盖彼此：" + target);
                if (policy.equals("ERROR") && Files.exists(target))
                    throw new IOException("目标已存在：" + target);
                Path payload =
                        FileTransferRunner.artifact(
                                stage, Json.string(Json.object(artifacts.get(i)), "path"));
                String hash = FileCli.sha256(payload);
                if (!hash.equals(metadata.get("sha256"))) throw new IOException("输出工件内容校验失败。");
                plans.add(new Plan(output.id, payload, target, hash, origin, policy));
                if (Boolean.TRUE.equals(output.settings.get("openFolderAfterSuccess")))
                    r.foldersToOpen.add(FileTransferRunner.realPath(target.getParent()));
            }
        }
        r.record.put("fileTypeChecks", typeChecks);
        r.record.put(
                "outputNodes",
                outputs.stream()
                        .map(n -> Map.of("nodeId", n.id, "operation", n.settings.get("operation")))
                        .toList());
        r.record.put(
                "publicationPlan",
                plans.stream()
                        .map(
                                p ->
                                        Map.of(
                                                "outputNodeId",
                                                p.outputId,
                                                "destination",
                                                p.target.toString(),
                                                "sha256",
                                                p.hash))
                        .toList());
        r.record.put(
                "originals",
                allOriginals.entrySet().stream()
                        .map(e -> Map.of("path", e.getKey().toString(), "sha256", e.getValue()))
                        .toList());
        r.record.put("retainedSharedSources", kept.stream().map(Path::toString).toList());
        r.record.put("publicationState", "PREPARED");
        r.save();
        Map<Plan, Path> temps = new LinkedHashMap<>(), backups = new LinkedHashMap<>();
        Map<Plan, String> previousHashes = new HashMap<>();
        Map<Path, Path> sourceBackups = new LinkedHashMap<>();
        List<Plan> committed = new ArrayList<>();
        List<Path> removed = new ArrayList<>();
        List<FileTransferRunner.Published> published = new ArrayList<>();
        try {
            // 先备份将删除的原件，为后续删除失败保留恢复材料。
            // Back up sources before deletion so a later failure can attempt restoration.
            int sourceIndex = 0;
            for (var original : moves.entrySet()) {
                if (!FileCli.sha256(original.getKey()).equals(original.getValue()))
                    throw new IOException("源文件变化，拒绝移动删除。");
                Path backup = r.run.resolve("source-backups/" + (sourceIndex++));
                Files.createDirectories(backup.getParent());
                Files.copy(original.getKey(), backup);
                if (!FileCli.sha256(backup).equals(original.getValue()))
                    throw new IOException("源文件备份校验失败。");
                sourceBackups.put(original.getKey(), backup);
            }
            // 临时文件放在目标目录，覆盖前备份旧目标；此阶段尚未发布新内容。
            // Stage beside each destination and back up overwritten files before committing.
            int backupIndex = 0;
            for (Plan plan : plans) {
                Files.createDirectories(plan.target.getParent());
                Path tmp = Files.createTempFile(plan.target.getParent(), ".workflow-", ".tmp");
                temps.put(plan, tmp);
                Files.copy(plan.payload, tmp, StandardCopyOption.REPLACE_EXISTING);
                writableTemporary(tmp);
                if (!FileCli.sha256(tmp).equals(plan.hash)) throw new IOException("暂存输出校验失败。");
                if (Files.exists(plan.target)) {
                    if (!plan.policy.equals("OVERWRITE"))
                        throw new IOException("目标在预检查后已被占用，请重试：" + plan.target);
                    String previous = FileCli.sha256(plan.target);
                    Path backup = r.run.resolve("publication-backups/" + (backupIndex++));
                    Files.createDirectories(backup.getParent());
                    Files.copy(plan.target, backup);
                    if (!FileCli.sha256(backup).equals(previous))
                        throw new IOException("旧目标在备份时变化。");
                    backups.put(plan, backup);
                    previousHashes.put(plan, previous);
                }
            }
            // 发布前再次检查目标，缩小预检后被其他进程修改的风险窗口。
            // Recheck destinations immediately before publishing each staged file.
            for (Plan plan : plans) {
                if (backups.containsKey(plan)) {
                    if (!Files.exists(plan.target)
                            || !FileCli.sha256(plan.target).equals(previousHashes.get(plan)))
                        throw new IOException("旧目标在发布前变化。");
                } else if (Files.exists(plan.target)) throw new IOException("目标在发布前被占用。");
                if (plan.policy.equals("OVERWRITE") && backups.containsKey(plan))
                    replace(temps.get(plan), plan.target);
                else Files.move(temps.get(plan), plan.target);
                committed.add(plan);
                if (!FileCli.sha256(plan.target).equals(plan.hash))
                    throw new IOException("发布结果校验失败。");
                published.add(
                        new FileTransferRunner.Published(plan.origin, plan.target, plan.hash));
                r.commitObserver.afterCommit(committed.size());
            }
            r.record.put("publicationState", "COMMITTED");
            r.record.put(
                    "files",
                    published.stream()
                            .map(
                                    p ->
                                            Map.of(
                                                    "destination",
                                                    p.destination().toString(),
                                                    "sha256",
                                                    p.sha256()))
                            .toList());
            r.save();
            // 全部输出已写入并校验后才删源，删除前仍需确认原件内容未变化。
            // Delete sources only after all outputs are verified, rechecking source hashes first.
            for (var original : moves.entrySet())
                if (!FileCli.sha256(original.getKey()).equals(original.getValue()))
                    throw new IOException("发布后源文件变化，保留原文件并回滚输出。");
            for (var original : moves.entrySet()) {
                if (!FileCli.sha256(original.getKey()).equals(original.getValue()))
                    throw new IOException("删除前源文件变化。");
                Files.delete(original.getKey());
                removed.add(original.getKey());
            }
            r.record.put("removedSources", removed.stream().map(Path::toString).toList());
            r.record.put("publicationState", "SUCCEEDED");
            r.save();
            return List.copyOf(published);
        } catch (IOException | RuntimeException failure) {
            // 恢复已删原件并逆序撤销发布；外部改动或恢复失败会保留记录供人工处理。
            // Compensate completed steps; preserve external changes and record recovery failures.
            List<String> recoveryErrors = new ArrayList<>();
            for (Path source : removed)
                try {
                    if (Files.exists(source)) throw new IOException("原路径已被其他文件占用");
                    Files.copy(sourceBackups.get(source), source);
                } catch (IOException ex) {
                    recoveryErrors.add("恢复来源 " + source + "：" + ex.getMessage());
                }
            Collections.reverse(committed);
            for (Plan plan : committed)
                try {
                    if (Files.exists(plan.target) && !FileCli.sha256(plan.target).equals(plan.hash))
                        throw new IOException("目标被其他进程更改，保留现场");
                    if (backups.containsKey(plan)) {
                        Path restore =
                                Files.createTempFile(
                                        plan.target.getParent(), ".workflow-restore-", ".tmp");
                        try {
                            Files.copy(
                                    backups.get(plan),
                                    restore,
                                    StandardCopyOption.REPLACE_EXISTING);
                            replace(restore, plan.target);
                        } finally {
                            Files.deleteIfExists(restore);
                        }
                    } else Files.deleteIfExists(plan.target);
                } catch (IOException ex) {
                    recoveryErrors.add("回滚输出 " + plan.target + "：" + ex.getMessage());
                }
            r.record.put(
                    "publicationState",
                    recoveryErrors.isEmpty() ? "ROLLED_BACK" : "RECOVERY_REQUIRED");
            r.record.put("recoveryErrors", recoveryErrors);
            r.record.put(
                    "removedSources",
                    removed.stream().filter(p -> !Files.exists(p)).map(Path::toString).toList());
            r.save();
            if (!recoveryErrors.isEmpty())
                failure.addSuppressed(new IOException(String.join("; ", recoveryErrors)));
            throw failure;
        } finally {
            for (Path tmp : temps.values()) Files.deleteIfExists(tmp);
        }
    }

    static void writableTemporary(Path path) throws IOException {
        if (System.getProperty("os.name").startsWith("Windows"))
            Files.setAttribute(path, "dos:readonly", false);
    }

    /** 优先原子替换单个文件，文件系统不支持时降级。 / Prefer atomic replacement of one file. */
    static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

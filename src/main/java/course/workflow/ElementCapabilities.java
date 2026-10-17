package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护元素能力声明与 CLI 发现结果，将“可以传递文件”与“能够识别并处理内容”分开判断。
 *
 * <p>Maps declared tool capabilities to connection checks and runtime file-acceptance policies.
 */
final class ElementCapabilities {
    record Contract(
            String operation,
            Set<String> formats,
            boolean requireIdentity,
            String cardinality,
            boolean implemented) {}

    static final Map<String, Map<String, Object>> DISCOVERED = new ConcurrentHashMap<>();

    private ElementCapabilities() {}

    /** 获取当前元素的输入约束，包含类型范围、数量和识别要求。 / Resolve the input contract. */
    static Contract inputContract(WorkflowApp.Node node) {
        return switch (node.kind) {
            case SCRIPT -> discoveredInput(ScriptTools.capability(node));
            case AI -> new Contract("CONVERT", Set.of("txt"), true, "SINGLE", true);
            case FILE_MERGE -> new Contract("PRESERVE", Set.of("*"), false, "COLLECTION", true);
            case SUBGRAPH -> {
                var ports = node.inputPorts();
                yield ports.isEmpty()
                        ? new Contract("VALUE", Set.of("*"), false, "SINGLE", true)
                        : new Contract(
                                "PRESERVE",
                                ports.get(0).formats(),
                                !ports.get(0).formats().contains("*"),
                                ports.get(0).cardinality(),
                                true);
            }
            case VIRTUAL_IN, VIRTUAL_OUT ->
                    new Contract(
                            "PRESERVE",
                            Subgraphs.port(node).formats(),
                            !Subgraphs.port(node).formats().contains("*"),
                            Subgraphs.port(node).cardinality(),
                            true);
            case SAVE ->
                    node.settings.get("format").equals("KEEP")
                                    && DISCOVERED.containsKey("io.file_export")
                            ? discoveredInput(DISCOVERED.get("io.file_export"))
                            : new Contract(
                                    node.settings.get("format").equals("KEEP")
                                            ? "PRESERVE"
                                            : "CONVERT",
                                    Set.of("*"),
                                    !node.settings.get("format").equals("KEEP"),
                                    "COLLECTION",
                                    node.settings.get("format").equals("KEEP"));
            case READ ->
                    new Contract(
                            "READ",
                            Set.of("txt", "md", "csv", "tsv", "json"),
                            true,
                            "SINGLE",
                            false);
            case PDF -> new Contract("CONVERT", PdfTool.supported(), true, "COLLECTION", true);
            case RENAME -> new Contract("PRESERVE", Set.of("*"), false, "COLLECTION", true);
            case JAVA_BUILD -> new Contract("CONVERT", Set.of("java"), true, "COLLECTION", true);
            case AUDIO -> new Contract("CONVERT", ExecutableTools.MEDIA, true, "COLLECTION", true);
            case EXCEL_MERGE ->
                    new Contract("CONVERT", Set.of("xlsx", "xlsm"), true, "COLLECTION", true);
            default -> new Contract("VALUE", Set.of(), false, "SINGLE", false);
        };
    }

    static Contract inputContract(WorkflowApp.Node node, String port) {
        if (node.kind == WorkflowApp.Kind.SUBGRAPH) {
            var found = Subgraphs.find(node, false, port);
            return found == null
                    ? new Contract("READ", Set.of(), true, "SINGLE", true)
                    : new Contract(
                            "PRESERVE",
                            found.formats(),
                            !found.formats().contains("*"),
                            found.cardinality(),
                            true);
        }
        if (node.kind == WorkflowApp.Kind.RENAME && port.equals("csv"))
            return new Contract("READ", Set.of("csv"), true, "SINGLE", true);
        return inputContract(node);
    }

    static Contract discoveredInput(Map<String, Object> value) {
        try {
            for (Object raw : Json.list(value.get("inputs"))) {
                Map<String, Object> port = Json.object(raw);
                if (port.get("name").equals("data")) {
                    Set<String> formats = new LinkedHashSet<>();
                    for (Object f : Json.list(port.get("formats"))) formats.add((String) f);
                    return new Contract(
                            Json.string(value, "operation"),
                            Set.copyOf(formats),
                            port.get("validation").equals("IDENTIFY"),
                            Json.string(port, "cardinality"),
                            true);
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
        throw new IllegalArgumentException("Missing data input capability");
    }

    /** 根据配置提前发现明显不兼容；未知的实际文件仍须运行时检测。 / Check declared compatibility. */
    static String connectionError(WorkflowApp.Node source, WorkflowApp.Node target) {
        if (!Objects.equals(source.outputType(), "FILE")
                || target.kind != WorkflowApp.Kind.READ
                        && !ExecutableTools.WORK.contains(target.kind)
                || target.kind == WorkflowApp.Kind.RENAME) return null;
        Contract contract = inputContract(target);
        if (source.kind == WorkflowApp.Kind.FILE
                && !source.settings.get("selectionMode").equals("SINGLE")
                && contract.cardinality.equals("SINGLE")) return "目标元素只支持单项文件，请先逐项处理集合。";
        if (source.kind == WorkflowApp.Kind.FILE
                && source.settings.get("selectionMode").equals("SINGLE")) {
            String path = (String) source.settings.get("sourcePath");
            if (!path.isBlank())
                try {
                    String extension = FileCli.extension(Path.of(path));
                    if (!extension.isEmpty()
                            && !contract.formats.contains("*")
                            && !contract.formats.contains(extension))
                        return target.name
                                + " 声明支持 "
                                + String.join(",", new TreeSet<>(contract.formats))
                                + "，不支持 ."
                                + extension;
                } catch (InvalidPathException ex) {
                    return "源路径无效。";
                }
        }
        return null;
    }

    /** 返回拒绝原因，null 表示当前声明允许接收。 / Return a rejection reason or null. */
    static String rejection(Contract contract, FileTypeDetector.Detection detection) {
        if (detection.state().equals("UNREADABLE")) return "文件不可读取";
        if (contract.operation.equals("PRESERVE") && contract.formats.contains("*")) return null;
        if (!contract.formats.contains("*") && !contract.formats.contains(detection.format()))
            return "实际识别类型 " + detection.format() + " 不在元素支持范围内";
        if (contract.requireIdentity && !detection.state().equals("IDENTIFIED"))
            return "类型未确定或后缀与内容不一致：" + detection.message();
        return null;
    }

    static Map<String, Object> declaration(String toolId) {
        return ToolCapabilities.declaration(toolId);
    }

    /**
     * 调用工具的 --capabilities 并校验结构；缓存仅用于已成功发现的可用能力。
     *
     * <p>Queries and validates CLI capability metadata, updating the available-tool cache.
     */
    static Map<String, Object> discover(Path executableJar) throws IOException {
        Path javaExecutable =
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process =
                ToolLauncher.start(
                        ToolLauncher.command(executableJar, List.of("--capabilities")), null);
        java.util.concurrent.ExecutorService readers =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        var out = readers.submit(() -> process.getInputStream().readNBytes(1024 * 1024 + 1));
        var err = readers.submit(() -> process.getErrorStream().readNBytes(8192));
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("能力发现超时");
            }
            byte[] bytes = out.get(2, java.util.concurrent.TimeUnit.SECONDS);
            err.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 1024 * 1024)
                throw new IOException("工具未提供有效能力描述");
            Object raw = Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> value = Json.object(raw);
            if (!(value.get("capabilityVersion") instanceof java.math.BigDecimal version)
                    || version.compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IOException("能力版本不支持");
            if (!value.keySet()
                    .equals(
                            Set.of(
                                    "capabilityVersion",
                                    "toolId",
                                    "toolVersion",
                                    "implementation",
                                    "operation",
                                    "inputs",
                                    "outputs",
                                    "knownExtensions"))) throw new IOException("能力字段不完整或未知");
            String toolId = Json.string(value, "toolId");
            Json.string(value, "toolVersion");
            if (!Set.of("PRESERVE", "READ", "CONVERT").contains(value.get("operation")))
                throw new IOException("处理方式无效");
            if (!Set.of("AVAILABLE", "UNAVAILABLE").contains(value.get("implementation")))
                throw new IOException("工具可用状态无效");
            for (Object port : Json.list(value.get("inputs"))) validatePort(Json.object(port));
            for (Object port : Json.list(value.get("outputs"))) validatePort(Json.object(port));
            if (value.get("implementation").equals("AVAILABLE")) DISCOVERED.put(toolId, value);
            else DISCOVERED.remove(toolId);
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(ex);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            process.destroyForcibly();
            throw new IOException(ex);
        } finally {
            readers.shutdownNow();
        }
    }

    static void validatePort(Map<String, Object> port) throws IOException {
        Json.string(port, "name");
        List<Object> formats = Json.list(port.get("formats"));
        if (formats.isEmpty() || formats.stream().anyMatch(f -> !(f instanceof String)))
            throw new IOException("文件类型声明无效");
        if (!Set.of("SINGLE", "COLLECTION").contains(port.get("cardinality"))
                || !Set.of("OPAQUE", "IDENTIFY").contains(port.get("validation")))
            throw new IOException("端口能力声明无效");
        if (formats.contains("*") && formats.size() != 1) throw new IOException("通配类型不能与其他类型混用");
        List<Object> types = Json.list(port.get("dataTypes"));
        if (types.isEmpty() || !Set.of("TEXT", "FILE", "JSON").containsAll(types))
            throw new IOException("数据类型声明无效");
    }

    static String summary(WorkflowApp.Node node) {
        if (node.kind == WorkflowApp.Kind.FILE_MERGE)
            return "内置文件集合汇合：2至8个输入，按端口顺序合并。\n相同来源、名称和内容去重；不同来源同名文件由输出冲突策略处理。";
        if (Subgraphs.boundary(node.kind)) return "内置虚拟接口：传递数据与来源，不启动插件。\n类型、数量和声明格式在边界再次校验。";
        if (node.kind == WorkflowApp.Kind.SUBGRAPH)
            return "内置子图：具名父子端口映射、独立调用上下文。\n定义随流程嵌入，可导出和重复使用。禁止递归，最多8层。";
        if (node.kind == WorkflowApp.Kind.FILE) return "原文件选取可用；内容提取待实现。检测结果与来源一起保留。";
        if (node.kind == WorkflowApp.Kind.JAVA_BUILD)
            return "BAT工具：UTF-8 .java集合编译并打包project.jar。\n需要JDK；不解析Maven/Gradle依赖。编译器验证实际语法。";
        if (node.kind == WorkflowApp.Kind.AUDIO)
            return "EXE工具：FFmpeg解析媒体，提取第一条音轨为MP3。\n无音轨或类型不符时失败；支持格式："
                    + String.join(", ", new TreeSet<>(ExecutableTools.MEDIA));
        if (node.kind == WorkflowApp.Kind.EXCEL_MERGE)
            return "Python工具：XLSX/XLSM第一工作表按表头对齐合并，加来源列。\n保存值，不复制格式；公式需要缓存结果。旧XLS不支持。";
        if (node.kind == WorkflowApp.Kind.PDF)
            return "逐文件PDF / 默认排序合并PDF，可运行。\n当前格式："
                    + String.join(", ", PdfTool.supported())
                    + "\n办公格式需要本机Office或LibreOffice；旧OLE由实际转换器继续校验。";
        if (node.kind == WorkflowApp.Kind.RENAME)
            return "可运行：必需文件集合 + 可选CSV。\n自然排序逐行对应；保留扩展名。\n{x}序号 / {createtime}创建日期 / {CSV表头}。";
        Contract c = inputContract(node);
        if (node.kind == WorkflowApp.Kind.SAVE && c.operation.equals("PRESERVE"))
            return c.formats.contains("*")
                    ? "原样传递：接受任意可读文件，包括未识别类型；不解析正文。"
                    : "已发现格式范围：" + String.join(", ", new TreeSet<>(c.formats)) + "\n按实际文件检测后校验。";
        if (node.kind == WorkflowApp.Kind.READ || node.kind == WorkflowApp.Kind.PDF)
            return "声明格式："
                    + String.join(", ", new TreeSet<>(c.formats))
                    + "\n需要内容类型检查；单项文件。\n当前处理器尚未实现，不能运行。";
        return "配置声明不等于已实现处理器；运行前检查实际数据。";
    }
}

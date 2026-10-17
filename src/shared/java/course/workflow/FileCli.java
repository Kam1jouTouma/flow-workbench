package course.workflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * 内置独立工具共用的 v1 命令行协议：查询描述，或通过 JSON 请求与暂存目录交换数据。
 *
 * <p>Shared v1 CLI envelope for built-in tools, including file I/O, PDF, rename and AI processing.
 */
final class FileCli {
    static final Set<String> SUPPORTED =
            Collections.unmodifiableSet(
                    new LinkedHashSet<>(
                            List.of(
                                    "txt", "md", "csv", "tsv", "json", "pdf", "doc", "docx", "docm",
                                    "dot", "dotx", "rtf", "xls", "xlsx", "xlsm", "xlsb", "xlt",
                                    "xltx", "ppt", "pptx", "pptm", "pps", "ppsx", "odt", "ods",
                                    "odp", "png", "jpg", "jpeg", "webp", "bmp", "java", "mp4",
                                    "mkv", "mov", "avi", "webm", "mp3", "wav", "m4a", "flac", "aac",
                                    "ogg")));

    private FileCli() {}

    interface Action {
        void run(Map<String, Object> input, Map<String, Object> parameters, Path output)
                throws IOException;
    }

    /** 协议校验与退出码集中处理，实际业务委托给 Action。 / Validate the envelope before dispatch. */
    static void main(String[] args, String id, Action action) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
                System.out.println(
                        id
                                + " "
                                + version(id)
                                + "\n--describe | --capabilities | --version | --input <JSON> --config <JSON> --output <empty directory>\n"
                                + describe(id).get("description"));
                return;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                System.out.println(version(id));
                return;
            }
            if (args.length == 1 && args[0].equals("--describe")) {
                System.out.println(Json.stringify(describe(id)));
                return;
            }
            if (args.length == 1 && args[0].equals("--capabilities")) {
                System.out.println(Json.stringify(ToolCapabilities.declaration(id)));
                return;
            }
            if (args.length != 6)
                throw new IllegalArgumentException(
                        "Expected --input, --config and --output exactly once");
            Map<String, String> options = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!List.of("--input", "--config", "--output").contains(args[i])
                        || options.put(args[i], args[i + 1]) != null)
                    throw new IllegalArgumentException("Unknown or duplicate option");
            }
            Path inputPath = Path.of(options.get("--input")),
                    configPath = Path.of(options.get("--config")),
                    output = Path.of(options.get("--output"));
            if (!inputPath.isAbsolute() || !configPath.isAbsolute() || !output.isAbsolute())
                throw new IllegalArgumentException("Paths must be absolute");
            if (!Files.isDirectory(output))
                throw new IllegalArgumentException("Output directory must exist");
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent())
                    throw new IllegalArgumentException("Output directory must be empty");
            }
            Map<String, Object> input = Json.object(Json.read(inputPath)),
                    config = Json.object(Json.read(configPath));
            if (!input.keySet().equals(Set.of("protocolVersion", "runId", "nodeId", "inputs"))
                    || !config.keySet().equals(Set.of("protocolVersion", "parameters")))
                throw new IllegalArgumentException("Unknown or missing protocol fields");
            if (!(input.get("protocolVersion") instanceof java.math.BigDecimal iv)
                    || iv.compareTo(java.math.BigDecimal.ONE) != 0
                    || !(config.get("protocolVersion") instanceof java.math.BigDecimal cv)
                    || cv.compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IllegalArgumentException("Unsupported protocolVersion");
            Json.string(input, "runId");
            Json.string(input, "nodeId");
            action.run(input, Json.object(config.get("parameters")), output);
            System.out.println("Completed: " + id);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
            System.exit(4);
        } catch (RuntimeException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }

    /** 分块计算内容摘要，不将整个文件读入内存。 / Hash file contents with a fixed-size buffer. */
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> item(String path) {
        return Map.of("path", path, "mediaType", "application/octet-stream");
    }

    static Map<String, Object> value(String type, List<?> items) {
        return Map.of("type", type, "items", items);
    }

    /** 结果携带原请求身份，供宿主检查是否属于本次节点运行。 / Echo run and node identities. */
    static void result(Map<String, Object> input, Path directory, Map<String, Object> outputs)
            throws IOException {
        Json.write(
                directory.resolve("result.json"),
                Map.of(
                        "protocolVersion",
                        1,
                        "runId",
                        input.get("runId"),
                        "nodeId",
                        input.get("nodeId"),
                        "outputs",
                        outputs));
    }

    static Map<String, Object> describe(String id) {
        if (id.equals("ai.text")) {
            var data =
                    Map.of(
                            "name",
                            "data",
                            "label",
                            "输入文本",
                            "types",
                            List.of("TEXT"),
                            "cardinality",
                            "SINGLE",
                            "required",
                            true);
            var source =
                    Map.of(
                            "name",
                            "source",
                            "label",
                            "来源清单",
                            "types",
                            List.of("JSON"),
                            "cardinality",
                            "SINGLE",
                            "required",
                            true);
            return Map.of(
                    "protocolVersion",
                    1,
                    "id",
                    id,
                    "name",
                    "AI文本处理",
                    "version",
                    version(id),
                    "description",
                    "调用系统设置中的AI服务；Chat Completions兼容格式，Prompt及单个输入文本生成AI结果.txt；Key运行时DPAPI解密，不进入参数或日志。",
                    "inputs",
                    List.of(data, source),
                    "outputs",
                    List.of(data, source),
                    "parameters",
                    List.of(
                            parameter("serviceId", "系统AI服务ID", "string", null),
                            parameter("prompt", "Prompt", "string", null),
                            parameter("maxTokens", "最大Token", "integer", 2048),
                            parameter("timeoutSeconds", "响应超时秒", "integer", 120),
                            parameter("temperature", "Temperature（空为服务默认）", "string", ""),
                            parameter(
                                    "tokenParameter",
                                    "Token字段",
                                    "string",
                                    "max_completion_tokens")));
        }
        if (id.equals("batch.pdf") || id.equals("batch.rename")) {
            boolean pdf = id.equals("batch.pdf");
            Map<String, Object> data =
                    Map.of(
                            "name",
                            "data",
                            "label",
                            "待处理文件",
                            "types",
                            List.of("FILE"),
                            "cardinality",
                            "COLLECTION",
                            "required",
                            true,
                            "minItems",
                            1);
            Map<String, Object> source =
                    Map.of(
                            "name",
                            "source",
                            "label",
                            "原文件来源",
                            "types",
                            List.of("JSON"),
                            "cardinality",
                            "SINGLE",
                            "required",
                            true);
            List<Object> inputs = new ArrayList<>(List.of(data, source));
            if (!pdf)
                inputs.add(
                        Map.of(
                                "name",
                                "csv",
                                "label",
                                "模板CSV（可选）",
                                "types",
                                List.of("FILE"),
                                "cardinality",
                                "SINGLE",
                                "required",
                                false));
            return Map.of(
                    "protocolVersion",
                    1,
                    "id",
                    id,
                    "name",
                    pdf ? "批量PDF" : "批量改名",
                    "version",
                    version(id),
                    "description",
                    pdf
                            ? "逐文件生成PDF或按自然排序合并；输入名自动派生，合并为合并结果.pdf；Office格式依赖本机转换器。"
                            : "模板{x}序号、{createtime}创建日期、其他字段匹配CSV表头，缺失留空并输出warnings.json；保留扩展名，不修改原文件。",
                    "inputs",
                    inputs,
                    "outputs",
                    List.of(data, source),
                    "parameters",
                    pdf
                            ? List.of(
                                    parameter("pdfMode", "PDF生成方式", "string", "EACH"),
                                    parameter("encoding", "文本编码", "string", "UTF-8"))
                            : List.of(
                                    parameter("template", "命名模板", "string", "{x}"),
                                    parameter("csvEncoding", "CSV编码", "string", "UTF-8"),
                                    parameter("csvDelimiter", "CSV分隔符", "string", ",")));
        }
        Map<String, Object> files =
                Map.of(
                        "name",
                        "data",
                        "label",
                        "文件数据",
                        "types",
                        List.of("FILE"),
                        "cardinality",
                        "COLLECTION",
                        "required",
                        true,
                        "minItems",
                        1);
        Map<String, Object> source =
                Map.of(
                        "name",
                        "source",
                        "label",
                        "来源清单",
                        "types",
                        List.of("JSON"),
                        "cardinality",
                        "SINGLE",
                        "required",
                        true);
        boolean input = id.equals("io.file_select");
        List<Map<String, Object>> parameters =
                input
                        ? List.of(
                                parameter("sourcePath", "源文件或目录", "string", null),
                                        parameter("selectionMode", "选取方式", "string", "SINGLE"),
                                parameter("extensions", "指定后缀", "string", "txt,csv"),
                                        parameter("recursive", "扫描子目录", "boolean", false))
                        : List.of();
        return Map.of(
                "protocolVersion",
                1,
                "id",
                id,
                "name",
                input ? "文件选择输入" : "原文件输出",
                "version",
                version(id),
                "description",
                input
                        ? "按单文件、指定后缀或支持后缀清单选择并快照文件。支持后缀：" + String.join(",", SUPPORTED)
                        : "生成原字节输出工件，最终发布与搬移由平台负责",
                "inputs",
                input ? List.of() : List.of(files, source),
                "outputs",
                input ? List.of(files, source) : List.of(files),
                "parameters",
                parameters);
    }

    static String version(String id) {
        return id.equals("io.file_select")
                ? "1.4.0"
                : id.equals("io.file_export") ? "1.1.0" : "1.0.0";
    }

    static Map<String, Object> parameter(String name, String label, String type, Object initial) {
        Map<String, Object> p =
                new LinkedHashMap<>(
                        Map.of(
                                "name",
                                name,
                                "label",
                                label,
                                "type",
                                type,
                                "required",
                                initial == null));
        if (initial != null) p.put("default", initial);
        return p;
    }
}

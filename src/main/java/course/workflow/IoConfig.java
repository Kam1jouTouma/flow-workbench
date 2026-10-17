package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 元素配置的默认值、存储类型和基础诊断，供编辑器、流程读取及执行前检查共同使用。
 *
 * <p>Shared element defaults, stored value types and basic diagnostics for editing, loading and
 * execution.
 */
final class IoConfig {
    private IoConfig() {}

    /** 每次返回独立的可变参数表，避免节点共享设置。 Returns an independent mutable map for each node. */
    static Map<String, Object> defaults(WorkflowApp.Kind kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kind == WorkflowApp.Kind.SCRIPT) {
            m.put("scriptPath", "");
            m.put("toolId", "");
            m.put("scriptHash", "");
            m.put("capabilityJSON", "{}");
            m.put("descriptorJSON", "{}");
            m.put("parametersJSON", "{}");
        } else if (kind == WorkflowApp.Kind.AI) {
            m.put("serviceId", "");
            m.put("prompt", "请总结输入文本，保留关键事实。");
            m.put("maxTokens", 2048);
            m.put("timeoutSeconds", 120);
            m.put("temperature", "");
            m.put("tokenParameter", "max_completion_tokens");
        } else if (kind == WorkflowApp.Kind.TEXT) {
            m.put("literalType", "TEXT");
        } else if (Subgraphs.boundary(kind)) {
            m.put("interfaceName", kind == WorkflowApp.Kind.VIRTUAL_IN ? "input" : "output");
            m.put("dataType", "FILE");
            m.put("cardinality", "COLLECTION");
            m.put("formats", "*");
            m.put("required", true);
            m.put("defaultValue", "");
            m.put("internalGate", false);
        } else if (kind == WorkflowApp.Kind.FILE_MERGE) {
            m.put("inputCount", 2);
        } else if (kind == WorkflowApp.Kind.SUBGRAPH) {
            m.put("interfaceVersion", 1);
            m.put("originFile", "");
        } else if (kind == WorkflowApp.Kind.FILE) {
            m.put("sourcePath", "");
            m.put("mode", "FILE");
            m.put("encoding", "UTF-8");
            m.put("startLine", 1);
            m.put("endLine", 0);
            m.put("jsonPointer", "");
            m.put("headerRow", 1);
            m.put("delimiter", ",");
            m.put("columns", "");
            m.put("selectionMode", "SINGLE");
            m.put("extensions", "txt,csv");
            m.put("recursive", false);
        } else if (kind == WorkflowApp.Kind.SAVE) {
            m.put("format", "KEEP");
            m.put("encoding", "UTF-8");
            m.put("delimiter", ",");
            m.put("prettyPrint", true);
            m.put("directory", "");
            m.put("fileName", "处理结果.txt");
            m.put("conflictPolicy", "ERROR");
            m.put("createDirectories", true);
            m.put("operation", "COPY");
            m.put("preserveNames", true);
            m.put("location", "DIRECTORY");
            m.put("openFolderAfterSuccess", false);
        } else if (kind == WorkflowApp.Kind.PDF) {
            m.put("pdfMode", "EACH");
            m.put("encoding", "UTF-8");
        } else if (kind == WorkflowApp.Kind.RENAME) {
            m.put("template", "{x}");
            m.put("csvEncoding", "UTF-8");
            m.put("csvDelimiter", ",");
        } else if (kind == WorkflowApp.Kind.JAVA_BUILD) {
            m.put("mainClass", "Main");
            m.put("targetRelease", "17");
            m.put("jdkPath", "");
        } else if (kind == WorkflowApp.Kind.AUDIO) {
            m.put("bitrate", "192");
        } else if (kind == WorkflowApp.Kind.EXCEL_MERGE) {
            m.put("sourceColumn", "来源文件");
        }
        return m;
    }

    /**
     * 检查持久化字段的键、Java 值类型及已列出的选项；业务完整性另由 diagnostics 检查。
     *
     * <p>Checks stored setting keys, Java types and listed choices separately from readiness
     * diagnostics.
     */
    static void requireSetting(WorkflowApp.Kind kind, String key, Object value) throws IOException {
        Object initial = defaults(kind).get(key);
        if (initial == null || !initial.getClass().isInstance(value))
            throw new IOException("配置键或类型无效：" + key);
        List<String> choices =
                switch (key) {
                    case "tokenParameter" -> List.of("max_completion_tokens", "max_tokens");
                    case "mode" -> List.of("TEXT", "JSON", "CSV", "FILE");
                    case "format" -> List.of("KEEP", "TXT", "JSON", "CSV");
                    case "encoding" -> List.of("UTF-8", "UTF-8-SIG", "GB18030");
                    case "delimiter" -> List.of(",", ";", "\t");
                    case "conflictPolicy" -> List.of("ERROR", "RENAME", "OVERWRITE");
                    case "selectionMode" -> List.of("SINGLE", "TYPES", "SUPPORTED");
                    case "operation" -> List.of("COPY", "MOVE");
                    case "location" -> List.of("DIRECTORY", "SOURCE");
                    case "pdfMode" -> List.of("EACH", "MERGE");
                    case "csvEncoding" -> List.of("UTF-8", "UTF-8-SIG", "GB18030");
                    case "csvDelimiter" -> List.of(",", ";", "\t");
                    case "targetRelease" -> List.of("17", "21", "25");
                    case "bitrate" -> List.of("128", "192", "256", "320");
                    case "dataType" -> List.of("FILE", "TEXT", "JSON", "ANY");
                    case "literalType" -> List.of("TEXT", "JSON");
                    case "cardinality" -> List.of("SINGLE", "COLLECTION");
                    default -> List.of();
                };
        if (!choices.isEmpty() && !choices.contains(value)) throw new IOException("配置选项无效：" + key);
        if (key.equals("maxTokens") && ((Integer) value < 1 || (Integer) value > 1000000)
                || key.equals("timeoutSeconds") && ((Integer) value < 1 || (Integer) value > 600))
            throw new IOException("AI数值超出范围。");
        if (key.equals("inputCount") && ((Integer) value < 2 || (Integer) value > 8))
            throw new IOException("汇合输入数量须在2至8之间。");
        if (value instanceof Integer number && number < (key.equals("endLine") ? 0 : 1))
            throw new IOException("配置数值无效：" + key);
    }

    /**
     * 收集供用户修正的本地配置问题；具体工具仍会在执行时验证实际输入。
     *
     * <p>Collects local configuration issues; individual tools still validate their runtime inputs.
     */
    static List<String> diagnostics(WorkflowApp.Node node) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> s = node.settings;
        String prefix = node.name + "：";
        if (node.kind == WorkflowApp.Kind.SCRIPT)
            try {
                ScriptTools.validate(node);
            } catch (Exception ex) {
                errors.add(prefix + ex.getMessage());
            }
        if (node.kind == WorkflowApp.Kind.AI) {
            if (((String) s.get("serviceId")).isBlank()) errors.add(prefix + "请选择AI服务。");
            if (((String) s.get("prompt")).isBlank()) errors.add(prefix + "请填写Prompt。");
            String temp = (String) s.get("temperature");
            if (!temp.isBlank())
                try {
                    double d = Double.parseDouble(temp);
                    if (!Double.isFinite(d) || d < 0 || d > 2) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    errors.add(prefix + "Temperature须为0至2或留空。");
                }
        }
        if (node.kind == WorkflowApp.Kind.FILE) {
            String path = (String) s.get("sourcePath");
            if (path.isBlank()) errors.add(prefix + "请选择源文件。");
            else
                try {
                    Path file = Path.of(path);
                    if (!file.isAbsolute()) errors.add(prefix + "源文件路径必须是绝对路径。");
                    else if (s.get("selectionMode").equals("SINGLE")
                            && (!Files.isRegularFile(file) || !Files.isReadable(file)))
                        errors.add(prefix + "源文件不存在或不可读取。");
                    else if (!s.get("selectionMode").equals("SINGLE") && !Files.isDirectory(file))
                        errors.add(prefix + "请选择源目录。");
                } catch (InvalidPathException ex) {
                    errors.add(prefix + "源文件路径无效。");
                }
            if (!s.get("selectionMode").equals("SINGLE") && !s.get("mode").equals("FILE"))
                errors.add(prefix + "批量文件传递请使用保留原文件模式。");
            if (s.get("selectionMode").equals("TYPES") && ((String) s.get("extensions")).isBlank())
                errors.add(prefix + "请填写指定后缀。");
            if (s.get("mode").equals("TEXT")
                    && (Integer) s.get("endLine") != 0
                    && (Integer) s.get("endLine") < (Integer) s.get("startLine"))
                errors.add(prefix + "截止行应不小于起始行，或设为 0。");
            if (s.get("mode").equals("JSON")) {
                String pointer = (String) s.get("jsonPointer");
                if (!pointer.isEmpty()
                        && (!pointer.startsWith("/") || pointer.matches(".*~(?![01]).*")))
                    errors.add(prefix + "JSON 位置须为空或合法的 /路径。");
            }
        } else if (node.kind == WorkflowApp.Kind.SAVE) {
            String directory = (String) s.get("directory"), name = (String) s.get("fileName");
            if (s.get("location").equals("SOURCE")) {
                // 每个工件分别定位其原始目录。 Each artifact resolves to its original source directory.
            } else if (directory.isBlank()) errors.add(prefix + "请选择保存目录。");
            else
                try {
                    Path dir = Path.of(directory);
                    if (!dir.isAbsolute()) errors.add(prefix + "保存目录必须是绝对路径。");
                    else if (Files.exists(dir) && !Files.isDirectory(dir))
                        errors.add(prefix + "保存位置不是目录。");
                    else if (!Files.exists(dir) && !(Boolean) s.get("createDirectories"))
                        errors.add(prefix + "目录不存在，且未允许自动创建。");
                } catch (InvalidPathException ex) {
                    errors.add(prefix + "保存目录路径无效。");
                }
            if (name.isBlank()
                    || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                    || name.endsWith(".")
                    || name.endsWith(" ")
                    || name.equals(".")
                    || name.equals("..")
                    || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
                errors.add(prefix + "请输入不含路径的合法文件名。");
            String format = (String) s.get("format");
            String suffix =
                    switch (format) {
                        case "TXT" -> ".txt";
                        case "JSON" -> ".json";
                        case "CSV" -> ".csv";
                        default -> "";
                    };
            if (!suffix.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(suffix))
                errors.add(prefix + "当前输出格式要求 " + suffix + " 后缀。");
        } else if (node.kind == WorkflowApp.Kind.RENAME && ((String) s.get("template")).isBlank())
            errors.add(prefix + "请填写命名模板。");
        else if (node.kind == WorkflowApp.Kind.EXCEL_MERGE
                && ((String) s.get("sourceColumn")).isBlank()) errors.add(prefix + "来源列名不能为空。");
        else if (node.kind == WorkflowApp.Kind.JAVA_BUILD
                && !((String) s.get("mainClass")).isEmpty()
                && !((String) s.get("mainClass"))
                        .matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            errors.add(prefix + "请填写合法的Java主类名，或留空生成库JAR。");
        else if (Subgraphs.boundary(node.kind)
                && !((String) s.get("interfaceName")).matches("[\\p{L}_][\\p{L}\\p{N}_-]{0,31}"))
            errors.add(prefix + "接口名称无效。");
        return errors;
    }

    static boolean accepts(String type, String format) {
        if (format.equals("KEEP") || type.equals("ANY")) return true;
        return type.equals("TEXT") && format.equals("TXT")
                || type.equals("JSON") && (format.equals("JSON") || format.equals("CSV"));
    }
}

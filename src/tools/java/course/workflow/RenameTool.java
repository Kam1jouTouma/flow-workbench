package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * 改名工具只为暂存副本生成新名字；原件的保留或删除由最终输出策略决定。
 *
 * <p>Renames staged copies only; the host controls final publication and source retention.
 */
public final class RenameTool {
    private RenameTool() {}

    public static void main(String[] args) {
        FileCli.main(args, "batch.rename", RenameTool::execute);
    }

    /** 先计算全部名称再生成工件，CSV 行与自然排序后的文件逐项对应。 / Plan names before copying. */
    static void execute(Map<String, Object> input, Map<String, Object> parameters, Path output)
            throws IOException {
        if (!Set.of("template", "csvEncoding", "csvDelimiter").containsAll(parameters.keySet()))
            throw new IOException("未知改名配置。");
        String template = (String) parameters.getOrDefault("template", "{x}");
        List<BatchFiles.File> files = BatchFiles.read(input);
        Map<String, Object> ports = Json.object(input.get("inputs"));
        CsvTable.Table csv = null;
        if (ports.containsKey("csv")) {
            Map<String, Object> port = Json.object(ports.get("csv"));
            List<Object> items = Json.list(port.get("items"));
            if (!"FILE".equals(port.get("type")) || items.size() != 1)
                throw new IOException("可选CSV端口要求一个CSV文件。");
            Path path = Path.of(Json.string(Json.object(items.get(0)), "path"));
            if (!FileCli.extension(path).equals("csv")) throw new IOException("CSV端口只接受.csv文件。");
            csv =
                    CsvTable.read(
                            path,
                            (String) parameters.getOrDefault("csvEncoding", "UTF-8"),
                            ((String) parameters.getOrDefault("csvDelimiter", ",")).charAt(0));
        }
        List<String> names = new ArrayList<>(), warnings = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            BatchFiles.File file = files.get(i);
            Map<String, String> row =
                    csv != null && i < csv.rows().size() ? csv.rows().get(i) : Map.of();
            String base = expand(template, file, i + 1, row, warnings);
            String extension = FileCli.extension(file.path());
            String name =
                    BatchFiles.validateName(base + (extension.isEmpty() ? "" : "." + extension));
            Path previous = Path.of((String) file.source().get("relativePath"));
            String relative =
                    (previous.getParent() == null
                                    ? name
                                    : previous.getParent().resolve(name).toString())
                            .replace('\\', '/');
            if (!used.add(relative.toLowerCase(Locale.ROOT)))
                warnings.add("模板产生同名结果：" + relative + "；由输出元素的同名策略处理。");
            names.add(relative);
        }
        List<Object> items = new ArrayList<>(), sources = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            BatchFiles.File file = files.get(i);
            String relative = names.get(i);
            String artifact = "payloads/" + i + "/" + relative;
            Path target = output.resolve(artifact);
            Files.createDirectories(target.getParent());
            Files.copy(file.path(), target);
            Map<String, Object> source = new LinkedHashMap<>(file.source());
            source.put("relativePath", relative);
            source.put("sha256", FileCli.sha256(target));
            source.put("originals", BatchFiles.originals(file));
            items.add(FileCli.item(artifact));
            sources.add(source);
        }
        BatchFiles.write(input, output, items, sources, warnings.stream().distinct().toList());
    }

    /** 内置字段优先，其余字段精确匹配 CSV 表头；缺值留空并记录警告。 / Expand template fields. */
    static String expand(
            String template,
            BatchFiles.File file,
            int index,
            Map<String, String> csv,
            List<String> warnings)
            throws IOException {
        Matcher matcher = Pattern.compile("\\{([^{}]+)}").matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1), value;
            switch (key) {
                case "x" -> value = "" + index;
                case "createtime" -> {
                    Object time = file.source().get("creationTime");
                    if (!(time instanceof String s)) throw new IOException("缺少原文件创建时间。");
                    value =
                            Instant.parse(s)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
                }
                default -> {
                    value = csv.getOrDefault(key, "");
                    if (value.isEmpty())
                        warnings.add("第" + index + "个文件：字段 {" + key + "} 没有匹配值，已留空。");
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        String result = out.toString();
        if (result.contains("{") || result.contains("}")) throw new IOException("模板花括号格式不完整。");
        if (result.isBlank()) throw new IOException("第" + index + "个文件模板结果为空，无法命名。");
        return result;
    }
}

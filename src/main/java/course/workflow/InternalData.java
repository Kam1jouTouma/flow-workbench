package course.workflow;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 将手动文本、文件内容读取和虚拟接口统一为宿主内部数据，不启动外部插件。
 *
 * <p>Creates internal values and validates subgraph boundaries using the same artifact envelope.
 */
final class InternalData {
    /** 手动数据标记为 synthetic，避免输出阶段将它当作用户原件删除。 / Mark generated provenance. */
    static BatchRunner.Bundle literal(WorkflowApp.Node n, Path stage) throws IOException {
        String type = (String) n.settings.get("literalType");
        String text = n.value;
        if (type.equals("JSON")) text = Json.stringify(Json.parse(text));
        Path payload = stage.resolve("literal." + (type.equals("JSON") ? "json" : "txt"));
        Files.writeString(payload, text);
        Map<String, Object> source =
                new LinkedHashMap<>(
                        Map.of(
                                "relativePath",
                                type.equals("JSON") ? "默认数据.json" : "文本输入.txt",
                                "originalPath",
                                payload.toAbsolutePath().toString(),
                                "sha256",
                                FileCli.sha256(payload),
                                "creationTime",
                                Instant.now().toString(),
                                "originals",
                                List.of(),
                                "synthetic",
                                true));
        return bundle(stage, payload, type, source);
    }

    static BatchRunner.Bundle bundle(
            Path stage, Path payload, String type, Map<String, Object> source) throws IOException {
        Json.write(stage.resolve("source.json"), Map.of("files", List.of(source)));
        return new BatchRunner.Bundle(
                Map.of(
                        "data",
                        FileCli.value(
                                type, List.of(FileCli.item(payload.toAbsolutePath().toString()))),
                        "source",
                        FileCli.value(
                                "JSON",
                                List.of(
                                        Map.of(
                                                "path",
                                                stage.resolve("source.json")
                                                        .toAbsolutePath()
                                                        .toString(),
                                                "mediaType",
                                                "application/json")))),
                List.of(source));
    }

    /**
     * 从已完成的文件快照提取文本或结构化内容，同时保留原件血缘。
     *
     * <p>Extracts content from an input snapshot while retaining its original-file lineage.
     */
    static BatchRunner.Bundle readMode(WorkflowApp.Node n, BatchRunner.Bundle raw, Path stage)
            throws IOException {
        String mode = (String) n.settings.get("mode");
        if (mode.equals("FILE")) return raw;
        List<Object> items = Json.list(Json.object(raw.ports().get("data")).get("items"));
        if (items.size() != 1) throw new IOException("内容读取模式要求单文件。");
        Path source = Path.of(Json.string(Json.object(items.get(0)), "path"));
        if (Files.size(source) > 16 * 1024 * 1024) throw new IOException("内容输入超过16MiB。");
        String type = mode.equals("TEXT") ? "TEXT" : "JSON", text;
        if (mode.equals("CSV")) {
            if ((Integer) n.settings.get("headerRow") != 1)
                throw new IOException("当前CSV内容输入仅支持第一条记录作表头。");
            var table =
                    CsvTable.read(
                            source,
                            (String) n.settings.get("encoding"),
                            ((String) n.settings.get("delimiter")).charAt(0));
            Set<String> columns = new LinkedHashSet<>();
            for (String c : ((String) n.settings.get("columns")).split("\\R"))
                if (!c.isBlank()) columns.add(c);
            if (!table.headers().containsAll(columns)) throw new IOException("CSV保留列不存在。");
            List<Object> rows = new ArrayList<>();
            for (var row : table.rows()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (String c : table.headers())
                    if (columns.isEmpty() || columns.contains(c)) values.put(c, row.get(c));
                rows.add(values);
            }
            text = Json.stringify(rows);
        } else {
            String encoding = mode.equals("JSON") ? "UTF-8" : (String) n.settings.get("encoding");
            text =
                    Files.readString(
                            source,
                            Charset.forName(encoding.equals("UTF-8-SIG") ? "UTF-8" : encoding));
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (mode.equals("JSON")) {
                Object value = Json.parse(text);
                String pointer = (String) n.settings.get("jsonPointer");
                if (!pointer.isEmpty())
                    for (String piece : pointer.substring(1).split("/", -1)) {
                        String key = piece.replace("~1", "/").replace("~0", "~");
                        if (value instanceof Map<?, ?> map && map.containsKey(key))
                            value = map.get(key);
                        else if (value instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                            int i = Integer.parseInt(key);
                            if (i >= list.size()) throw new IOException("JSON位置越界。");
                            value = list.get(i);
                        } else throw new IOException("JSON位置不存在。");
                    }
                text = Json.stringify(value);
            } else {
                String[] lines = text.split("\\R", -1);
                int start = (Integer) n.settings.get("startLine") - 1,
                        end = (Integer) n.settings.get("endLine");
                if (start >= lines.length) text = "";
                else
                    text =
                            String.join(
                                    "\n",
                                    Arrays.copyOfRange(
                                            lines,
                                            start,
                                            end == 0 ? lines.length : Math.min(lines.length, end)));
            }
        }
        Path payload = stage.resolve("selected." + (type.equals("JSON") ? "json" : "txt"));
        Files.writeString(payload, text);
        Map<String, Object> info = new LinkedHashMap<>(Json.object(raw.sources().get(0)));
        info.put(
                "originals",
                info.getOrDefault(
                        "originals",
                        List.of(
                                Map.of(
                                        "path",
                                        info.get("originalPath"),
                                        "sha256",
                                        info.get("sha256")))));
        info.put("sha256", FileCli.sha256(payload));
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        info.put(
                "relativePath",
                (dot > 0 ? name.substring(0, dot) : name)
                        + (type.equals("JSON") ? ".json" : ".txt"));
        return bundle(stage, payload, type, info);
    }

    /** 接口声明需与实际数据再次核对，动态数量无法仅靠连线时判定。 / Enforce runtime contracts. */
    static void validate(WorkflowApp.Node gate, BatchRunner.Bundle value) throws IOException {
        if (value == null) {
            if ((Boolean) gate.settings.get("required")
                    || gate.kind == WorkflowApp.Kind.VIRTUAL_OUT)
                throw new IOException(gate.name + "：必需接口没有数据。");
            return;
        }
        var port = Subgraphs.port(gate);
        var data = Json.object(value.ports().get("data"));
        String type = Json.string(data, "type");
        List<Object> items = Json.list(data.get("items"));
        if (!port.type().equals("ANY") && !port.type().equals(type))
            throw new IOException(gate.name + "：接口类型不匹配。");
        if (port.cardinality().equals("SINGLE") && items.size() != 1)
            throw new IOException(gate.name + "：接口只接受单项，收到" + items.size() + "项。");
        if (port.required() && items.isEmpty()) throw new IOException(gate.name + "：必需接口的数据为空。");
        if (type.equals("JSON"))
            for (Object item : items) {
                Path payload = Path.of(Json.string(Json.object(item), "path"));
                if (Files.size(payload) > 16 * 1024 * 1024)
                    throw new IOException("JSON接口数据超过16MiB。");
                Json.read(payload);
            }
        if (type.equals("FILE") && !port.formats().contains("*"))
            for (Object item : items) {
                var detection =
                        FileTypeDetector.detect(Path.of(Json.string(Json.object(item), "path")));
                if (!detection.state().equals("IDENTIFIED")
                        || !port.formats().contains(detection.format()))
                    throw new IOException(gate.name + "：实际文件格式不符合接口声明，" + detection.message());
            }
    }
}

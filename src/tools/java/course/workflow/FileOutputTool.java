package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 文件输出插件仅生成暂存工件，目标目录写入和删源策略由宿主执行器负责。
 *
 * <p>Stages output artifacts; the host owns destination publication and source deletion policy.
 */
public final class FileOutputTool {
    private FileOutputTool() {}

    public static void main(String[] args) {
        FileCli.main(args, "io.file_export", FileOutputTool::execute);
    }

    /** 保持数据项与来源逐项对应，用序号目录隔离同名工件。 / Stage duplicate names independently. */
    static void execute(Map<String, Object> input, Map<String, Object> config, Path output)
            throws IOException {
        if (!config.isEmpty())
            throw new IllegalArgumentException("File-preserving output has no tool parameters");
        Map<String, Object> inputs = Json.object(input.get("inputs"));
        if (!inputs.keySet().equals(Set.of("data", "source")))
            throw new IllegalArgumentException("Expected data and source ports");
        Map<String, Object> data = Json.object(inputs.get("data")),
                sourceValue = Json.object(inputs.get("source"));
        if (!Objects.equals(data.get("type"), "FILE")
                || !Objects.equals(sourceValue.get("type"), "JSON"))
            throw new IllegalArgumentException("Wrong port type");
        List<Object> dataItems = Json.list(data.get("items")),
                sourceItems = Json.list(sourceValue.get("items"));
        if (dataItems.isEmpty() || sourceItems.size() != 1)
            throw new IllegalArgumentException("Invalid port cardinality");
        Path sourceManifest = Path.of(Json.string(Json.object(sourceItems.get(0)), "path"));
        if (!sourceManifest.isAbsolute())
            throw new IllegalArgumentException("Expected absolute input path");
        List<Object> sources = Json.list(Json.object(Json.read(sourceManifest)).get("files"));
        if (sources.size() != dataItems.size())
            throw new IllegalArgumentException("Source identity count mismatch");
        List<Object> outputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < dataItems.size(); i++) {
            String relative = Json.string(Json.object(sources.get(i)), "relativePath");
            if (relative.contains("\\")
                    || relative.contains(":")
                    || relative.startsWith("/")
                    || Arrays.stream(relative.split("/", -1))
                            .anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
                throw new IllegalArgumentException("Invalid relative filename");
            String artifact = "payloads/" + i + "/" + relative;
            Path file = Path.of(Json.string(Json.object(dataItems.get(i)), "path")),
                    target = output.resolve(artifact).normalize();
            if (!file.isAbsolute() || !target.startsWith(output))
                throw new IllegalArgumentException("Invalid file path");
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
            if (!FileCli.sha256(file).equals(FileCli.sha256(target)))
                throw new IOException("Output hash mismatch");
            outputs.add(FileCli.item(artifact));
        }
        FileCli.result(input, output, Map.of("data", FileCli.value("FILE", outputs)));
    }
}

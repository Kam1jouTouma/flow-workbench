package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 文件输入插件：选择原件，生成内容快照及来源清单。
 *
 * <p>Selects source files and snapshots their bytes and provenance into the tool output directory.
 */
public final class FileInputTool {
    private FileInputTool() {}

    public static void main(String[] args) {
        FileCli.main(args, "io.file_select", FileInputTool::execute);
    }

    /** 复制前后复核哈希，避免把复制期间变化的文件当作稳定快照。 / Verify snapshot consistency. */
    static void execute(Map<String, Object> input, Map<String, Object> config, Path output)
            throws IOException {
        if (!Json.object(input.get("inputs")).isEmpty())
            throw new IllegalArgumentException("Input selector has no upstream ports");
        if (!Set.of("sourcePath", "selectionMode", "extensions", "recursive")
                .containsAll(config.keySet()))
            throw new IllegalArgumentException("Unknown configuration");
        Path source = Path.of(Json.string(config, "sourcePath"));
        String selection = (String) config.getOrDefault("selectionMode", "SINGLE");
        if (!source.isAbsolute() || Files.isSymbolicLink(source))
            throw new IllegalArgumentException("Expected absolute, non-link source");
        if (!List.of("SINGLE", "TYPES", "SUPPORTED").contains(selection))
            throw new IllegalArgumentException("Invalid selection mode");
        Object recurse = config.getOrDefault("recursive", false);
        if (!(recurse instanceof Boolean))
            throw new IllegalArgumentException("recursive must be boolean");
        Set<String> extensions = new LinkedHashSet<>();
        for (String e : ((String) config.getOrDefault("extensions", "txt,csv")).split("[,;\\s]+"))
            if (!e.isBlank()) extensions.add(e.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT));
        if (selection.equals("TYPES") && extensions.isEmpty())
            throw new IllegalArgumentException("Specify at least one extension");
        List<Path> files = selectFiles(source, selection, extensions, (Boolean) recurse);
        List<Object> items = new ArrayList<>(), sources = new ArrayList<>();
        for (Path file : files) {
            String relative =
                    selection.equals("SINGLE")
                            ? file.getFileName().toString()
                            : source.relativize(file).toString().replace('\\', '/');
            Path target = output.resolve("payloads").resolve(relative);
            Files.createDirectories(target.getParent());
            String hash = FileCli.sha256(file);
            Files.copy(file, target);
            if (!hash.equals(FileCli.sha256(target)) || !hash.equals(FileCli.sha256(file)))
                throw new IOException("Source changed while taking snapshot: " + file);
            FileTypeDetector.Detection detection = FileTypeDetector.detect(target);
            items.add(FileCli.item("payloads/" + relative));
            sources.add(
                    Map.of(
                            "originalPath",
                            file.toAbsolutePath().toString(),
                            "relativePath",
                            relative,
                            "sha256",
                            hash,
                            "fileType",
                            detection.json(),
                            "creationTime",
                            Files.readAttributes(
                                            file, java.nio.file.attribute.BasicFileAttributes.class)
                                    .creationTime()
                                    .toInstant()
                                    .toString()));
        }
        Json.write(output.resolve("source.json"), Map.of("files", sources));
        FileCli.result(
                input,
                output,
                Map.of(
                        "data",
                        FileCli.value("FILE", items),
                        "source",
                        FileCli.value(
                                "JSON",
                                List.of(
                                        Map.of(
                                                "path",
                                                "source.json",
                                                "mediaType",
                                                "application/json")))));
    }

    /** 批量选择按相对路径自然排序，后续 CSV 行与文件顺序以此为基础。 / Define batch input order. */
    static List<Path> selectFiles(
            Path source, String selection, Set<String> extensions, boolean recursive)
            throws IOException {
        List<Path> files;
        if (selection.equals("SINGLE")) {
            if (!Files.isRegularFile(source) || !Files.isReadable(source))
                throw new IOException("Source file is not readable");
            files = List.of(source);
        } else {
            if (!Files.isDirectory(source)) throw new IOException("Source must be a directory");
            Set<String> allowed = selection.equals("SUPPORTED") ? FileCli.SUPPORTED : extensions;
            try (Stream<Path> paths = Files.walk(source, recursive ? Integer.MAX_VALUE : 1)) {
                files =
                        paths.filter(
                                        p ->
                                                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                                                        && allowed.contains(FileCli.extension(p)))
                                .sorted(
                                        (a, b) ->
                                                BatchFiles.compare(
                                                        source.relativize(a).toString(),
                                                        source.relativize(b).toString()))
                                .toList();
            }
            if (files.isEmpty()) throw new IOException("No matching files");
        }
        return files;
    }
}

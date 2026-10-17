package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 批处理工具共用的文件集合封装，保持文件路径与来源清单逐项对应。
 *
 * <p>Shared collection transport for standalone tools, pairing each artifact with its provenance.
 */
final class BatchFiles {
    record File(Path path, Map<String, Object> source) {}

    /** 先配对数据与来源，再一起排序，避免内容和元信息错位。 / Pair before sorting. */
    static List<File> read(Map<String, Object> input) throws IOException {
        Map<String, Object> ports = Json.object(input.get("inputs"));
        Map<String, Object> data = Json.object(ports.get("data"));
        if (!"FILE".equals(data.get("type"))) throw new IOException("data 必须为 FILE 集合。");
        List<Object> items = Json.list(data.get("items"));
        Map<String, Object> source = Json.object(ports.get("source"));
        if (!"JSON".equals(source.get("type"))) throw new IOException("缺少来源信息。");
        List<Object> manifests = Json.list(source.get("items"));
        if (manifests.size() != 1 || items.isEmpty()) throw new IOException("输入数量无效。");
        Path manifest = Path.of(Json.string(Json.object(manifests.get(0)), "path"));
        List<Object> metadata = Json.list(Json.object(Json.read(manifest)).get("files"));
        if (metadata.size() != items.size()) throw new IOException("来源数量不匹配。");
        List<File> files = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Path path = Path.of(Json.string(Json.object(items.get(i)), "path"));
            if (!path.isAbsolute() || !Files.isRegularFile(path))
                throw new IOException("文件输入路径无效。");
            files.add(new File(path, new LinkedHashMap<>(Json.object(metadata.get(i)))));
        }
        files.sort(
                (a, b) ->
                        compare(
                                (String) a.source.get("relativePath"),
                                (String) b.source.get("relativePath")));
        return files;
    }

    /** 数字段按数值比较，使 2 排在 10 前面。 / Compare digit runs numerically for natural order. */
    static int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int ia = i, jb = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                java.math.BigInteger n = new java.math.BigInteger(a.substring(ia, i)),
                        m = new java.math.BigInteger(b.substring(jb, j));
                int c = n.compareTo(m);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        int c = Integer.compare(a.length(), b.length());
        return c == 0 ? a.compareTo(b) : c;
    }

    static void write(
            Map<String, Object> input,
            Path output,
            List<Object> items,
            List<Object> sources,
            List<String> warnings)
            throws IOException {
        Json.write(output.resolve("source.json"), Map.of("files", sources));
        Json.write(output.resolve("warnings.json"), Map.of("warnings", warnings));
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

    static String validateName(String name) throws IOException {
        if (name.isBlank()
                || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                || name.endsWith(".")
                || name.endsWith(" ")
                || name.equals(".")
                || name.equals("..")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
            throw new IOException("生成的文件名无效：" + name);
        return name;
    }

    /** 延续已有血缘；首次转换则登记当前原件。 / Preserve lineage across successive transforms. */
    static List<Object> originals(File file) {
        Object inherited = file.source.get("originals");
        return inherited instanceof List<?> list
                ? new ArrayList<>(list)
                : List.of(
                        Map.of(
                                "path",
                                file.source.get("originalPath"),
                                "sha256",
                                file.source.get("sha256")));
    }
}

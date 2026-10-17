package course.workflow;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * 支持引号字段的 CSV 读取器，保留表头名称供改名模板精确匹配。
 *
 * <p>Parses quoted CSV records and keeps exact header keys for template field lookup.
 */
final class CsvTable {
    record Table(List<String> headers, List<Map<String, String>> rows) {}

    /** 校验表头唯一性与列数，将缺少的尾部单元格补为空串。 / Normalize rows against unique headers. */
    static Table read(Path path, String encoding, char delimiter) throws IOException {
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("CSV 超过 16 MiB 限制。");
        String text =
                Charset.forName(encoding.equals("UTF-8-SIG") ? "UTF-8" : encoding)
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path)))
                        .toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = parse(text, delimiter);
        if (records.isEmpty()) throw new IOException("CSV 缺少表头。");
        List<String> headers = records.get(0);
        if (headers.stream().anyMatch(String::isEmpty)
                || new HashSet<>(headers).size() != headers.size())
            throw new IOException("CSV 表头为空或重复。");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() > headers.size())
                throw new IOException("CSV 第 " + (i + 1) + " 条记录列数超过表头。");
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++)
                row.put(headers.get(j), j < record.size() ? record.get(j) : "");
            rows.add(row);
        }
        return new Table(headers, rows);
    }

    /** 逐字符状态机区分引号内外，允许字段内换行与双引号转义。 / Parse quoting as a state machine. */
    static List<List<String>> parse(String text, char delimiter) throws IOException {
        List<List<String>> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false, atStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else field.append(c);
                continue;
            }
            if (c == '"' && atStart) {
                quoted = true;
                atStart = false;
                continue;
            }
            if (c == delimiter || c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                closed = false;
                atStart = true;
                if (c != delimiter) {
                    result.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
                continue;
            }
            if (closed || c == '"') throw new IOException("CSV 引号格式错误。");
            field.append(c);
            atStart = false;
        }
        if (quoted) throw new IOException("CSV 引号未闭合。");
        if (!row.isEmpty() || field.length() > 0 || closed) {
            row.add(field.toString());
            result.add(row);
        }
        return result;
    }
}

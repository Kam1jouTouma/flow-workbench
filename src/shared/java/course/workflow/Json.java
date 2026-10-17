package course.workflow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/**
 * 无额外依赖的 JSON 编解码器，为命令行请求、结果与运行记录提供统一格式。
 *
 * <p>Small JSON codec for CLI envelopes and persisted records; numbers are parsed as BigDecimal.
 */
final class Json {
    private Json() {}

    static Object read(Path path) throws IOException {
        return new Parser(Files.readString(path)).parse();
    }

    static Object parse(String text) throws IOException {
        return new Parser(text).parse();
    }

    static void write(Path path, Object value) throws IOException {
        Files.writeString(path, stringify(value) + "\n");
    }

    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray())
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 32) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (var e : map.entrySet())
                parts.add(stringify(e.getKey().toString()) + ":" + stringify(e.getValue()));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Collection<?> collection)
            return "[" + String.join(",", collection.stream().map(Json::stringify).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    /** 在协议边界显式检查类型，将结构错误交给调用方处理。 / Validate an object at the boundary. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) throws IOException {
        if (!(value instanceof List<?>)) throw new IOException("Expected JSON array");
        return (List<Object>) value;
    }

    static String string(Map<String, Object> value, String key) throws IOException {
        if (!(value.get(key) instanceof String s)) throw new IOException("Missing string: " + key);
        return s;
    }

    /** 递归下降解析，拒绝重复键、尾随内容和过深嵌套。 / Recursive descent with structural checks. */
    private static final class Parser {
        final String source;
        int i, depth;

        Parser(String source) {
            this.source = source;
        }

        Object parse() throws IOException {
            Object value = value();
            space();
            if (i != source.length()) error();
            return value;
        }

        void space() {
            while (i < source.length() && " \t\r\n".indexOf(source.charAt(i)) >= 0) i++;
        }

        IOException error() throws IOException {
            throw new IOException("Invalid JSON at character " + i);
        }

        Object value() throws IOException {
            space();
            if (i >= source.length() || ++depth > 128) throw error();
            Object value;
            char c = source.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (take('}')) value = map;
                else {
                    do {
                        space();
                        String key = text();
                        space();
                        if (!take(':') || map.containsKey(key)) throw error();
                        map.put(key, value());
                        space();
                    } while (take(','));
                    if (!take('}')) throw error();
                    value = map;
                }
            } else if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                space();
                if (!take(']')) {
                    do {
                        list.add(value());
                        space();
                    } while (take(','));
                    if (!take(']')) throw error();
                }
                value = list;
            } else if (c == '"') value = text();
            else if (source.startsWith("true", i)) {
                i += 4;
                value = true;
            } else if (source.startsWith("false", i)) {
                i += 5;
                value = false;
            } else if (source.startsWith("null", i)) {
                i += 4;
                value = null;
            } else {
                int start = i;
                while (i < source.length() && "0123456789.eE+-".indexOf(source.charAt(i)) >= 0) i++;
                String number = source.substring(start, i);
                if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                    throw error();
                try {
                    value = new BigDecimal(number);
                } catch (NumberFormatException ex) {
                    throw error();
                }
            }
            depth--;
            return value;
        }

        boolean take(char c) {
            if (i < source.length() && source.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String text() throws IOException {
            if (!take('"')) throw error();
            StringBuilder out = new StringBuilder();
            while (i < source.length()) {
                char c = source.charAt(i++);
                if (c == '"') return out.toString();
                if (c < 32) throw error();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= source.length()) throw error();
                char escape = source.charAt(i++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > source.length()) throw error();
                        try {
                            out.append((char) Integer.parseInt(source.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error();
                        }
                        i += 4;
                    }
                    default -> throw error();
                }
            }
            throw error();
        }
    }
}

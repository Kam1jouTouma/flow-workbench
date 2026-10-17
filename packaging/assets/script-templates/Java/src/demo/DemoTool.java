package demo;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class DemoTool {
    static Map<String, Object> dp(String n, String type, String count) {
        return Map.of(
                "name",
                n,
                "label",
                n,
                "types",
                List.of(type),
                "cardinality",
                count,
                "required",
                true);
    }

    static Map<String, Object> cp(
            String n, String type, String count, List<String> formats, String validation) {
        return Map.of(
                "name",
                n,
                "dataTypes",
                List.of(type),
                "cardinality",
                count,
                "formats",
                formats,
                "validation",
                validation);
    }

    static List<Object> descriptions() {
        return List.of(dp("data", "FILE", "COLLECTION"), dp("source", "JSON", "SINGLE"));
    }

    static List<Object> capabilities() {
        return List.of(
                cp("data", "FILE", "COLLECTION", List.of("*"), "OPAQUE"),
                cp("source", "JSON", "SINGLE", List.of("json"), "IDENTIFY"));
    }

    static String hash(Path p) throws Exception {
        var d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    public static void main(String[] args) {
        try {
            System.setOut(
                    new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(
                    new PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
            if (args.length == 1) {
                switch (args[0]) {
                    case "--help":
                    case "-h":
                        System.out.println(
                                "Java copy demo\n--describe | --capabilities | --version | --input JSON --config JSON --output empty-directory");
                        return;
                    case "--version":
                        System.out.println("1.0.0");
                        return;
                    case "--describe":
                        System.out.println(
                                Json.stringify(
                                        Map.of(
                                                "protocolVersion",
                                                1,
                                                "id",
                                                "user.java_copy_demo",
                                                "name",
                                                "Java复制示例",
                                                "version",
                                                "1.0.0",
                                                "description",
                                                "独立JAR复制暂存文件，不修改原件",
                                                "inputs",
                                                descriptions(),
                                                "outputs",
                                                descriptions(),
                                                "parameters",
                                                List.of())));
                        return;
                    case "--capabilities":
                        System.out.println(
                                Json.stringify(
                                        Map.of(
                                                "capabilityVersion",
                                                1,
                                                "toolId",
                                                "user.java_copy_demo",
                                                "toolVersion",
                                                "1.0.0",
                                                "implementation",
                                                "AVAILABLE",
                                                "operation",
                                                "PRESERVE",
                                                "inputs",
                                                capabilities(),
                                                "outputs",
                                                capabilities(),
                                                "knownExtensions",
                                                List.of())));
                        return;
                }
            }
            if (args.length != 6) throw new IOException("Expected --input --config --output");
            Map<String, Path> paths = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!Set.of("--input", "--config", "--output").contains(args[i])
                        || paths.put(args[i], Path.of(args[i + 1])) != null
                        || !Path.of(args[i + 1]).isAbsolute())
                    throw new IOException("Invalid arguments");
            }
            Path output = paths.get("--output");
            try (var files = Files.list(output)) {
                if (files.findAny().isPresent()) throw new IOException("Output must be empty");
            }
            var request = Json.object(Json.read(paths.get("--input")));
            var config = Json.object(Json.read(paths.get("--config")));
            if (!Json.object(config.get("parameters")).isEmpty())
                throw new IOException("No parameters in this demo");
            var incoming = Json.object(request.get("inputs"));
            var data = Json.list(Json.object(incoming.get("data")).get("items"));
            var manifests = Json.list(Json.object(incoming.get("source")).get("items"));
            var sources =
                    Json.list(
                            Json.object(
                                            Json.read(
                                                    Path.of(
                                                            Json.string(
                                                                    Json.object(manifests.get(0)),
                                                                    "path"))))
                                    .get("files"));
            if (sources.size() != data.size() || data.isEmpty())
                throw new IOException("Source count mismatch");
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                Path input = Path.of(Json.string(Json.object(data.get(i)), "path"));
                var source = Json.object(sources.get(i));
                if (!hash(input).equals(source.get("sha256")))
                    throw new IOException("Hash mismatch");
                String name = Path.of(Json.string(source, "relativePath")).getFileName().toString();
                String artifact = "payloads/" + i + "/" + name;
                Path target = output.resolve(artifact);
                Files.createDirectories(target.getParent());
                Files.copy(input, target);
                items.add(Map.of("path", artifact, "mediaType", "application/octet-stream"));
            }
            Json.write(output.resolve("source.json"), Map.of("files", sources));
            Json.write(
                    output.resolve("result.json"),
                    Map.of(
                            "protocolVersion",
                            1,
                            "runId",
                            request.get("runId"),
                            "nodeId",
                            request.get("nodeId"),
                            "outputs",
                            Map.of(
                                    "data",
                                    Map.of("type", "FILE", "items", items),
                                    "source",
                                    Map.of(
                                            "type",
                                            "JSON",
                                            "items",
                                            List.of(
                                                    Map.of(
                                                            "path",
                                                            "source.json",
                                                            "mediaType",
                                                            "application/json"))))));
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            System.exit(4);
        }
    }
}

package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 将 JAR、Python、EXE 和批处理统一为命令行调用，保持参数列表与解释器选择集中管理。
 *
 * <p>Adapts supported executable formats and probes their real CLI metadata.
 */
final class ToolLauncher {
    /** 普通进程使用参数列表；批处理额外拒绝无法安全传递的字符。 / Build format-specific commands. */
    static List<String> command(Path tool, List<String> arguments) throws IOException {
        tool = tool.toAbsolutePath();
        if (!Files.isRegularFile(tool)) throw new IOException("工具不存在。");
        List<String> command = new ArrayList<>();
        switch (FileCli.extension(tool)) {
            case "jar" -> {
                command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
                command.add("-jar");
                command.add(tool.toString());
                command.addAll(arguments);
            }
            case "py" -> {
                command.add(python());
                command.add(tool.toString());
                command.addAll(arguments);
            }
            case "exe" -> {
                command.add(tool.toString());
                command.addAll(arguments);
            }
            case "bat", "cmd" -> {
                List<String> tokens = new ArrayList<>();
                tokens.add(tool.toString());
                tokens.addAll(arguments);
                StringJoiner joined = new StringJoiner(" ");
                for (String token : tokens) {
                    if (token.matches(".*[\"%!?\\r\\n].*"))
                        throw new IOException("批处理参数含无法安全传递的字符。");
                    joined.add("\"" + token + "\"");
                }
                command.addAll(List.of("cmd.exe", "/d", "/s", "/c", "\"" + joined + "\""));
            }
            default -> throw new IOException("支持的工具格式：exe、py、bat、cmd、jar。");
        }
        return command;
    }

    /** 依次采用用户设置、环境变量、内置运行时、本机安装和 PATH。 / Resolve Python by precedence. */
    static String python() throws IOException {
        String preference = SystemSettings.current().python();
        if (!preference.isBlank()) {
            if (!Files.isRegularFile(Path.of(preference)))
                throw new IOException("系统设置指定的Python不存在，请在设置中重新选择或留空。");
            return preference;
        }
        String configured = System.getenv("FLOW_PYTHON");
        if (configured != null && !configured.isBlank()) {
            if (!Files.isRegularFile(Path.of(configured)))
                throw new IOException("FLOW_PYTHON指定的解释器不存在。");
            return configured;
        }
        Path bundled = FileTransferRunner.toolPath("runtime/python/python.exe");
        if (Files.isRegularFile(bundled)) return bundled.toString();
        Path installed =
                Path.of(System.getProperty("user.home"), "AppData", "Local", "Programs", "Python");
        if (Files.isDirectory(installed))
            try (var dirs = Files.list(installed)) {
                var candidates =
                        dirs.map(p -> p.resolve("python.exe"))
                                .filter(Files::isRegularFile)
                                .sorted(Comparator.reverseOrder())
                                .toList();
                if (!candidates.isEmpty()) return candidates.get(0).toString();
            }
        return "python.exe";
    }

    static Process start(List<String> command, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) builder.directory(cwd.toFile());
        String preference = SystemSettings.current().python();
        if (!preference.isBlank()) builder.environment().put("FLOW_PYTHON", preference);
        return builder.start();
    }

    /** 只验证查询接口及声明一致性，不代表工具业务逻辑已通过测试。 / Probe metadata, not behavior. */
    static Map<String, Object> probe(Path tool) throws IOException {
        Path absolute = tool.toAbsolutePath();
        String help =
                ProcessCommands.run(command(absolute, List.of("--help")), absolute.getParent(), 10);
        if (help.isBlank()) throw new IOException("--help 没有帮助文本。");
        Map<String, Object> description =
                Json.object(
                        Json.parse(
                                ProcessCommands.run(
                                        command(absolute, List.of("--describe")),
                                        absolute.getParent(),
                                        10)));
        if (!description
                .keySet()
                .equals(
                        Set.of(
                                "protocolVersion",
                                "id",
                                "name",
                                "version",
                                "description",
                                "inputs",
                                "outputs",
                                "parameters"))) throw new IOException("工具描述字段不符合v1规范。");
        if (!(description.get("protocolVersion") instanceof java.math.BigDecimal v)
                || v.compareTo(java.math.BigDecimal.ONE) != 0) throw new IOException("工具协议版本不支持。");
        for (String key : List.of("id", "name", "version", "description"))
            Json.string(description, key);
        for (String key : List.of("inputs", "outputs", "parameters"))
            Json.list(description.get(key));
        Map<String, Object> capability =
                Json.object(
                        Json.parse(
                                ProcessCommands.run(
                                        command(absolute, List.of("--capabilities")),
                                        absolute.getParent(),
                                        10)));
        if (!Objects.equals(description.get("id"), capability.get("toolId"))
                || !Objects.equals(description.get("version"), capability.get("toolVersion")))
            throw new IOException("能力声明与工具描述身份不一致。");
        if (!(capability.get("capabilityVersion") instanceof java.math.BigDecimal cv)
                || cv.compareTo(java.math.BigDecimal.ONE) != 0
                || !capability.get("implementation").equals("AVAILABLE"))
            throw new IOException("能力声明无效。");
        for (String direction : List.of("inputs", "outputs"))
            for (Object port : Json.list(capability.get(direction)))
                ElementCapabilities.validatePort(Json.object(port));
        return Map.of(
                "path",
                absolute.toString(),
                "sha256",
                FileCli.sha256(absolute),
                "help",
                help,
                "describe",
                description,
                "capabilities",
                capability,
                "validation",
                "CLI_QUERIES_ONLY");
    }
}

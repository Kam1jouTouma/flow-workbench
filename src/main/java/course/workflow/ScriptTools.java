package course.workflow;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

/**
 * 自定义脚本的登记与节点适配；注册保存原路径和入口哈希，不搬移脚本及同目录依赖。
 *
 * <p>Registers local scripts and adapts them to nodes, retaining the original path and entry-file
 * hash.
 */
final class ScriptTools {
    static Path library() throws IOException {
        return new FlowLibrary(SystemSettings.current().file)
                .directory()
                .getParent()
                .resolve("user-scripts/registry");
    }

    /**
     * 实际运行查询接口，检查当前支持的 FILE/JSON 端口形状；这不验证脚本的全部业务行为。
     *
     * <p>Executes metadata queries and checks the supported FILE/JSON contract, not full tool
     * behavior.
     */
    static Map<String, Object> inspect(Path path) throws IOException {
        var record = new LinkedHashMap<>(ToolLauncher.probe(path));
        var cap = Json.object(record.get("capabilities"));
        for (String side : List.of("inputs", "outputs")) {
            var ports = Json.list(cap.get(side));
            if (ports.size() != 2)
                throw new IOException("当前脚本元素要求 data(FILE) 与 source(JSON) 两个端口。");
            for (Object raw : ports) {
                var p = Json.object(raw);
                String name = Json.string(p, "name");
                if (name.equals("data")) {
                    if (!Json.list(p.get("dataTypes")).equals(List.of("FILE")))
                        throw new IOException("当前脚本元素只支持FILE数据。");
                } else if (!name.equals("source")
                        || !Json.list(p.get("dataTypes")).equals(List.of("JSON"))
                        || !p.get("cardinality").equals("SINGLE"))
                    throw new IOException("source端口应为单项JSON来源清单。");
            }
            if (Json.object(ports.get(0)).get("name").equals(Json.object(ports.get(1)).get("name")))
                throw new IOException("端口名称重复。");
        }
        String version =
                ProcessCommands.run(
                                ToolLauncher.command(path, List.of("--version")),
                                path.toAbsolutePath().getParent(),
                                10)
                        .strip();
        if (!version.equals(Json.object(record.get("describe")).get("version")))
            throw new IOException("--version 与描述版本不一致。");
        record.put("validation", "EXECUTABLE_FILE_TOOL_V1");
        return record;
    }

    /** 相同规范化路径使用同一登记文件。 A normalized path maps to a stable registry entry. */
    static Map<String, Object> register(Path path) throws IOException {
        var r = inspect(path);
        Path folder = library();
        Files.createDirectories(folder);
        String id =
                UUID.nameUUIDFromBytes(
                                path.toAbsolutePath()
                                        .normalize()
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        Json.write(folder.resolve(id + ".json"), r);
        return r;
    }

    /**
     * 将描述和能力快照放入节点，以便保存后重建端口；只初始化显式声明的默认参数。
     *
     * <p>Stores descriptor and capability snapshots for persistence and copies declared defaults.
     */
    static void configure(WorkflowApp.Node n, Map<String, Object> record) throws IOException {
        var desc = Json.object(record.get("describe"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Object v : Json.list(desc.get("parameters"))) {
            var p = Json.object(v);
            if (p.containsKey("default")) parameters.put(Json.string(p, "name"), p.get("default"));
        }
        n.name = Json.string(desc, "name");
        n.value = Json.string(desc, "description");
        n.settings.put("scriptPath", record.get("path"));
        n.settings.put("toolId", desc.get("id"));
        n.settings.put("scriptHash", record.get("sha256"));
        n.settings.put("capabilityJSON", Json.stringify(record.get("capabilities")));
        n.settings.put("descriptorJSON", Json.stringify(desc));
        n.settings.put("parametersJSON", Json.stringify(parameters));
    }

    static Map<String, Object> capability(WorkflowApp.Node n) {
        try {
            return Json.object(Json.parse((String) n.settings.get("capabilityJSON")));
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** 画布只展示 data；source 来源清单由执行层配对传递。 Exposes data; the runner carries provenance. */
    static List<Subgraphs.Port> ports(WorkflowApp.Node n, boolean out) {
        try {
            for (Object raw : Json.list(capability(n).get(out ? "outputs" : "inputs"))) {
                var p = Json.object(raw);
                if (p.get("name").equals("data"))
                    return List.of(
                            new Subgraphs.Port(
                                    "data",
                                    "FILE",
                                    Json.string(p, "cardinality"),
                                    true,
                                    Subgraphs.formats(
                                            String.join(
                                                    ",",
                                                    Json.list(p.get("formats")).stream()
                                                            .map(String::valueOf)
                                                            .toList()))));
            }
            return List.of();
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /**
     * 解析侧边栏 JSON，检查必填、类型、enum 及未知键；此方法不调用脚本。
     *
     * <p>Checks parameter JSON for required values, types, enum membership and unknown keys
     * locally.
     */
    static Map<String, Object> parameters(WorkflowApp.Node n) throws IOException {
        var values = Json.object(Json.parse((String) n.settings.get("parametersJSON")));
        var desc = Json.object(Json.parse((String) n.settings.get("descriptorJSON")));
        Set<String> names = new HashSet<>();
        for (Object raw : Json.list(desc.get("parameters"))) {
            var p = Json.object(raw);
            String key = Json.string(p, "name");
            if (!names.add(key)) throw new IOException("重复参数：" + key);
            Object v = values.get(key);
            if (v == null) {
                if (Boolean.TRUE.equals(p.get("required"))) throw new IOException("缺少参数：" + key);
                continue;
            }
            String type = Json.string(p, "type");
            boolean valid =
                    switch (type) {
                        case "string" -> v instanceof String;
                        case "boolean" -> v instanceof Boolean;
                        case "integer" ->
                                v instanceof java.math.BigDecimal b
                                        && b.stripTrailingZeros().scale() <= 0;
                        case "number" -> v instanceof Number;
                        default -> false;
                    };
            if (!valid) throw new IOException("参数类型错误：" + key);
            if (p.get("enum") instanceof List<?> list && !list.contains(v))
                throw new IOException("参数选项错误：" + key);
        }
        if (!names.containsAll(values.keySet())) throw new IOException("存在未声明的脚本参数。");
        return values;
    }

    /** 校验入口文件哈希及参数；哈希不覆盖依赖目录。 Checks entry hash and parameters, not dependencies. */
    static void validate(WorkflowApp.Node n) throws IOException {
        Path path = Path.of((String) n.settings.get("scriptPath"));
        if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IOException("脚本不存在，请重新导入。");
        if (!FileCli.sha256(path).equals(n.settings.get("scriptHash")))
            throw new IOException("脚本已修改，请重新导入确认新版本。");
        parameters(n);
    }

    /** 按端口声明检查实际输入或输出工件。 Checks actual artifacts against the selected port contract. */
    static void checkData(WorkflowApp.Node n, Map<String, Object> value, boolean out)
            throws IOException {
        if (!"FILE".equals(value.get("type"))) throw new IOException("自定义脚本必须传递FILE数据。");
        var port = ports(n, out).get(0);
        var items = Json.list(value.get("items"));
        if (items.isEmpty() || port.cardinality().equals("SINGLE") && items.size() != 1)
            throw new IOException("脚本文件数量不符合端口声明。");
        boolean identify = false;
        for (Object raw : Json.list(capability(n).get(out ? "outputs" : "inputs"))) {
            var p = Json.object(raw);
            if (p.get("name").equals("data")) identify = p.get("validation").equals("IDENTIFY");
        }
        var contract =
                new ElementCapabilities.Contract(
                        "READ", port.formats(), identify, port.cardinality(), true);
        for (Object raw : items) {
            String error =
                    ElementCapabilities.rejection(
                            contract,
                            FileTypeDetector.detect(
                                    Path.of(Json.string(Json.object(raw), "path"))));
            if (error != null) throw new IOException("脚本" + (out ? "输出" : "输入") + "：" + error);
        }
    }

    static void help(Component owner) {
        try {
            Path doc = FileTransferRunner.toolPath("docs/release-v0.16.1/脚本适配教程.html");
            java.awt.Desktop.getDesktop().open(doc.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    owner,
                    "请查看程序 docs/release-v0.16.1/脚本适配教程.html。",
                    "脚本教程",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static void choose(WorkflowApp.Editor editor) {
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            if (Files.isDirectory(library()))
                try (var files = Files.list(library())) {
                    for (Path p :
                            files.filter(f -> f.toString().endsWith(".json")).sorted().toList())
                        try {
                            records.add(Json.object(Json.read(p)));
                        } catch (IOException ignored) {
                        }
                }
            List<String> choices = new ArrayList<>(List.of("导入新脚本…", "查看脚本适配教程"));
            for (var r : records)
                choices.add(Json.object(r.get("describe")).get("name") + " · " + r.get("path"));
            String selected =
                    (String)
                            JOptionPane.showInputDialog(
                                    editor,
                                    "导入会查询并运行脚本的帮助接口；登记原位置，保持同目录依赖。",
                                    "脚本库",
                                    JOptionPane.PLAIN_MESSAGE,
                                    null,
                                    choices.toArray(),
                                    choices.get(0));
            if (selected == null) return;
            int index = choices.indexOf(selected);
            if (index == 1) {
                help(editor);
                return;
            }
            if (index >= 2) {
                add(editor, records.get(index - 2));
                return;
            }
            JFileChooser picker = new JFileChooser();
            picker.setDialogTitle("导入脚本（保留原位置和同目录依赖）");
            picker.setFileFilter(
                    new javax.swing.filechooser.FileNameExtensionFilter(
                            "脚本 exe / py / bat / cmd / jar", "exe", "py", "bat", "cmd", "jar"));
            if (picker.showOpenDialog(editor) != JFileChooser.APPROVE_OPTION) return;
            Path path = picker.getSelectedFile().toPath();
            new SwingWorker<Map<String, Object>, Void>() {
                protected Map<String, Object> doInBackground() throws Exception {
                    return register(path);
                }

                protected void done() {
                    try {
                        add(editor, get());
                    } catch (Exception ex) {
                        editor.error(
                                "脚本导入失败",
                                new IOException(
                                        ex.getCause() == null
                                                ? ex.getMessage()
                                                : ex.getCause().getMessage()));
                    }
                }
            }.execute();
        } catch (IOException ex) {
            editor.error("脚本库读取失败", ex);
        }
    }

    static void add(WorkflowApp.Editor editor, Map<String, Object> r) throws IOException {
        var n =
                new WorkflowApp.Node(
                        UUID.randomUUID().toString(), WorkflowApp.Kind.SCRIPT, 80, 100);
        configure(n, r);
        validate(n);
        editor.flow.nodes.add(n);
        editor.select(n);
        editor.changed();
        editor.log("已添加脚本节点：" + n.name + "；请连接文件输入与文件输出。");
    }
}

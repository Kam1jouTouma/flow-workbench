package course.workflow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

final class SubgraphTests {
    static void require(boolean b, String name) {
        if (!b) throw new AssertionError(name);
    }

    static WorkflowApp.Node virtual(
            WorkflowApp.Flow f, boolean out, String name, String type, String count, double y) {
        var n =
                f.add(
                        out ? WorkflowApp.Kind.VIRTUAL_OUT : WorkflowApp.Kind.VIRTUAL_IN,
                        out ? 750 : 25,
                        y);
        n.settings.put("interfaceName", name);
        n.settings.put("dataType", type);
        n.settings.put("cardinality", count);
        return n;
    }

    static WorkflowApp.Flow module() {
        var f = new WorkflowApp.Flow();
        var in = virtual(f, false, "files", "FILE", "COLLECTION", 100);
        var pdf = f.add(WorkflowApp.Kind.PDF, 280, 100);
        pdf.settings.put("pdfMode", "MERGE");
        var rename = f.add(WorkflowApp.Kind.RENAME, 520, 100);
        rename.settings.put("template", "report_{x}");
        var out = virtual(f, true, "result", "FILE", "COLLECTION", 100);
        require(f.connect(in, pdf) == null, "module input");
        f.connect(pdf, rename);
        f.connect(rename, out);
        return f;
    }

    static WorkflowApp.Flow wrap(WorkflowApp.Flow child) {
        var f = new WorkflowApp.Flow();
        var in = virtual(f, false, "files", "FILE", "COLLECTION", 100);
        var n = f.add(WorkflowApp.Kind.SUBGRAPH, 320, 100);
        n.child = child;
        var out = virtual(f, true, "result", "FILE", "COLLECTION", 100);
        f.connect(in, "data", n, "files", true);
        f.connect(n, "result", out, "data", true);
        return f;
    }

    static WorkflowApp.Flow parent(Path source, WorkflowApp.Flow child, Path destination) {
        var f = new WorkflowApp.Flow();
        var in = BatchTests.input(f, source, true, "txt");
        var n = f.add(WorkflowApp.Kind.SUBGRAPH, 350, 100);
        n.child = child;
        var out = f.add(WorkflowApp.Kind.SAVE, 650, 100);
        out.settings.put("directory", destination.toString());
        require(f.connect(in, "data", n, "files", true) == null, "parent input");
        require(f.connect(n, "result", out, "data", true) == null, "parent output");
        return f;
    }

    static FileTransferRunner.Result execute(WorkflowApp.Flow f, Path root, String name)
            throws IOException {
        Path definition = root.resolve(name + ".flow.xml");
        f.save(definition);
        return BatchRunner.execute(
                WorkflowApp.Flow.load(definition), root.resolve("runs"), warnings -> {});
    }

    static boolean rejects(WorkflowApp.Flow f, Path root) {
        try {
            BatchRunner.execute(f, root.resolve("runs"), warnings -> {});
            return false;
        } catch (IOException ex) {
            return true;
        }
    }

    static void run(Path root) throws Exception {
        root = root.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录必须不存在。");
        Files.createDirectories(root);
        List<String> checks = new ArrayList<>();
        Path source = root.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("1.txt"), "FIRST 中文一");
        Files.writeString(source.resolve("2.txt"), "SECOND 中文二");
        var definition = module();
        Path exported = root.resolve("module.flow.xml");
        definition.save(exported);
        var f = parent(source, WorkflowApp.Flow.load(exported), root.resolve("basic"));
        var basic = execute(f, root, "basic");
        Path pdf = basic.files().get(0).destination();
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            require(
                    doc.getNumberOfPages() == 2 && text.contains("中文一") && text.contains("中文二"),
                    "content across module");
        }
        require(pdf.getFileName().toString().equals("report_1.pdf"), "nested settings persisted");
        var record = Json.object(Json.read(basic.record()));
        require(
                Json.list(record.get("boundaryChecks")).size() == 2,
                "internal gates and no fake plugin");
        checks.add("导出/载入v4子图，FILE集合→PDF合并→改名→父图输出，2页中文与名称一致。");
        var nested =
                execute(
                        parent(source, wrap(Subgraphs.copy(definition)), root.resolve("nested")),
                        root,
                        "nested");
        require(Files.exists(nested.files().get(0).destination()), "nested actual output");
        require(
                Json.list(Json.object(Json.read(nested.record())).get("boundaryChecks")).size()
                        == 4,
                "nested four boundaries");
        checks.add("两层嵌套、具名映射和4次实际边界校验通过。");
        var repeated = parent(source, Subgraphs.copy(definition), root.resolve("repeated"));
        var first = repeated.nodes.get(1);
        var second = repeated.add(WorkflowApp.Kind.SUBGRAPH, 500, 350);
        second.child = Subgraphs.copy(definition);
        second.child.nodes.stream()
                .filter(n -> n.kind == WorkflowApp.Kind.RENAME)
                .findFirst()
                .orElseThrow()
                .settings
                .put("template", "second_{x}");
        var save = repeated.nodes.get(2);
        repeated.edges.removeIf(e -> e.to().equals(save.id));
        repeated.connect(first, "result", second, "files", true);
        repeated.connect(second, "result", save, "data", true);
        var twice = execute(repeated, root, "repeated");
        require(
                twice.files().get(0).destination().getFileName().toString().equals("second_1.pdf"),
                "private instance parameters");
        require(
                definition.nodes.stream()
                        .filter(n -> n.kind == WorkflowApp.Kind.RENAME)
                        .findFirst()
                        .orElseThrow()
                        .settings
                        .get("template")
                        .equals("report_{x}"),
                "definition unchanged");
        var contexts = Json.list(Json.object(Json.read(twice.record())).get("subgraphContexts"));
        Set<Object> runtimeIds = new HashSet<>();
        for (Object raw : contexts)
            require(
                    runtimeIds.add(Json.object(raw).get("runtimeNodeId")),
                    "distinct runtime identities");
        checks.add("同一子图两次调用：独立参数、独立运行ID，不串值。");
        Path csv = root.resolve("rules.csv");
        Files.writeString(csv, "姓名\n张三\n李四\n");
        var wire = new WorkflowApp.Flow();
        var docsIn = virtual(wire, false, "documents", "FILE", "COLLECTION", 100);
        var csvIn = virtual(wire, false, "rules", "FILE", "SINGLE", 260);
        csvIn.settings.put("formats", "csv");
        var docsOut = virtual(wire, true, "documentsOut", "FILE", "COLLECTION", 100);
        var csvOut = virtual(wire, true, "rulesOut", "FILE", "SINGLE", 260);
        csvOut.settings.put("formats", "csv");
        wire.connect(docsIn, docsOut);
        wire.connect(csvIn, csvOut);
        var multi = new WorkflowApp.Flow();
        var docs = BatchTests.input(multi, source, true, "txt");
        var table = BatchTests.input(multi, csv, false, "csv");
        table.y = 350;
        var router = multi.add(WorkflowApp.Kind.SUBGRAPH, 350, 100);
        router.child = wire;
        var renamer = multi.add(WorkflowApp.Kind.RENAME, 650, 100);
        renamer.settings.put("template", "{姓名}_{x}");
        var multiSave = BatchTests.output(multi, renamer, root.resolve("multi"));
        multi.connect(docs, "data", router, "documents", true);
        multi.connect(table, "data", router, "rules", true);
        multi.connect(router, "documentsOut", renamer, "data", true);
        multi.connect(router, "rulesOut", renamer, "csv", true);
        execute(multi, root, "multi");
        require(
                Files.exists(root.resolve("multi/张三_1.txt"))
                        && Files.exists(root.resolve("multi/李四_2.txt"))
                        && Files.exists(csv),
                "multiple named outputs");
        checks.add("双输入/双输出路由，CSV穿过子图接改名可选端口，规则文件保留。");
        var optionalModule = new WorkflowApp.Flow();
        var optFiles = virtual(optionalModule, false, "files", "FILE", "COLLECTION", 100);
        var optCsv = virtual(optionalModule, false, "rules", "FILE", "SINGLE", 300);
        optCsv.settings.put("required", false);
        optCsv.settings.put("formats", "csv");
        var optRename = optionalModule.add(WorkflowApp.Kind.RENAME, 320, 100);
        var optOut = virtual(optionalModule, true, "result", "FILE", "COLLECTION", 100);
        optionalModule.connect(optFiles, optRename);
        optionalModule.connect(optCsv, optRename, "csv");
        optionalModule.connect(optRename, optOut);
        execute(
                parent(source, Subgraphs.copy(optionalModule), root.resolve("optional")),
                root,
                "optional");
        require(Files.exists(root.resolve("optional/1.txt")), "absent optional route");
        optCsv.settings.put("defaultValue", csv.toString());
        optRename.settings.put("template", "{姓名}_{x}");
        execute(parent(source, optionalModule, root.resolve("default-csv")), root, "default-csv");
        require(Files.exists(root.resolve("default-csv/张三_1.txt")), "CSV fallback");
        checks.add("可选CSV未绑定时正常省略；FILE默认路径绑定实际CSV通过。");
        var jsonModule = new WorkflowApp.Flow();
        var jsonIn = virtual(jsonModule, false, "value", "JSON", "SINGLE", 100);
        jsonIn.settings.put("defaultValue", "{\"ok\":true,\"n\":3}");
        var jsonOut = virtual(jsonModule, true, "result", "JSON", "SINGLE", 100);
        jsonModule.connect(jsonIn, jsonOut);
        var jsonParent = new WorkflowApp.Flow();
        var jsonNode = jsonParent.add(WorkflowApp.Kind.SUBGRAPH, 300, 100);
        jsonNode.child = jsonModule;
        var jsonSave = jsonParent.add(WorkflowApp.Kind.SAVE, 600, 100);
        jsonSave.settings.put("directory", root.resolve("json").toString());
        jsonParent.connect(jsonNode, "result", jsonSave, "data", true);
        var jsonResult = execute(jsonParent, root, "json-default");
        require(
                Json.object(Json.read(jsonResult.files().get(0).destination()))
                        .get("ok")
                        .equals(true),
                "JSON typed default");
        checks.add("JSON默认值经过具名虚拟接口返回，实际JSON输出与类型保持。");
        var textModule = new WorkflowApp.Flow();
        var textIn = virtual(textModule, false, "value", "TEXT", "SINGLE", 100);
        var textOut = virtual(textModule, true, "result", "TEXT", "SINGLE", 100);
        textModule.connect(textIn, textOut);
        var textParent = new WorkflowApp.Flow();
        var literal = textParent.add(WorkflowApp.Kind.TEXT, 50, 100);
        literal.value = "虚拟接口保留中文文本";
        var textNode = textParent.add(WorkflowApp.Kind.SUBGRAPH, 320, 100);
        textNode.child = textModule;
        var textSave = textParent.add(WorkflowApp.Kind.SAVE, 600, 100);
        textSave.settings.put("directory", root.resolve("text").toString());
        textParent.connect(literal, "data", textNode, "value", true);
        textParent.connect(textNode, "result", textSave, "data", true);
        var textResult = execute(textParent, root, "text");
        require(
                Files.readString(textResult.files().get(0).destination()).equals(literal.value),
                "TEXT boundary");
        checks.add("内置手动TEXT输入经过子图返回，中文内容一致，不依赖虚拟插件。");
        var unbound = parent(source, Subgraphs.copy(definition), root.resolve("unbound"));
        unbound.remove(unbound.nodes.get(0));
        require(
                rejects(unbound, root) && !Files.exists(root.resolve("unbound")),
                "required unbound rejected");
        var duplicate = Subgraphs.copy(definition);
        var dup = virtual(duplicate, false, "files", "FILE", "COLLECTION", 350);
        boolean failed = false;
        try {
            Subgraphs.validateInterfaces(duplicate);
        } catch (IOException ex) {
            failed = true;
        }
        require(failed, "duplicate names rejected");
        var mismatched = Subgraphs.copy(textParent);
        mismatched.nodes.get(1).child.nodes.get(0).settings.put("dataType", "JSON");
        require(rejects(mismatched, root), "type mismatch rejected");
        checks.add("必需未绑定、重复名称、TEXT/JSON类型不匹配在运行前拒绝。");
        var cycle = Subgraphs.copy(definition);
        var self = cycle.add(WorkflowApp.Kind.SUBGRAPH, 400, 350);
        self.child = cycle;
        require(
                rejects(parent(source, cycle, root.resolve("cycle")), root),
                "recursive reference rejected");
        Path untouched = root.resolve("untouched.flow.xml");
        Files.writeString(untouched, "keep");
        failed = false;
        try {
            cycle.save(untouched);
        } catch (IOException ex) {
            failed = true;
        }
        require(
                failed && Files.readString(untouched).equals("keep"),
                "recursive save doesn't overwrite");
        var deep = Subgraphs.copy(definition);
        for (int i = 0; i < 8; i++) deep = wrap(deep);
        require(rejects(parent(source, deep, root.resolve("depth")), root), "depth bound");
        checks.add("递归引用、超过8层嵌套拒绝；保存失败不覆盖原文件。");
        var flat = new WorkflowApp.Flow();
        var flatIn = BatchTests.input(flat, source, true, "txt");
        var flatPdf = flat.add(WorkflowApp.Kind.PDF, 320, 100);
        flatPdf.settings.put("pdfMode", "MERGE");
        var flatRename = flat.add(WorkflowApp.Kind.RENAME, 540, 100);
        flatRename.settings.put("template", "enc_{x}");
        flat.connect(flatIn, flatPdf);
        flat.connect(flatPdf, flatRename);
        BatchTests.output(flat, flatRename, root.resolve("encapsulated"));
        var encapsulated = Subgraphs.encapsulate(flat, Set.of(flatPdf.id, flatRename.id));
        require(
                flat.nodes.size() == 3
                        && encapsulated.inputPorts().size() == 1
                        && encapsulated.outputPorts().size() == 1,
                "encapsulation rewires boundaries");
        var encResult = execute(flat, root, "encapsulated");
        require(
                encResult.files().get(0).destination().getFileName().toString().equals("enc_1.pdf"),
                "encapsulation actual result");
        checks.add("自动封装PDF/改名两步，生成虚拟接口并保持父图连线与执行结果。");
        final WorkflowApp.Flow uiFlow = multi;
        SwingUtilities.invokeAndWait(
                () -> {
                    var editor = new WorkflowApp.Editor();
                    editor.flow = Subgraphs.copy(uiFlow);
                    var node =
                            editor.flow.nodes.stream()
                                    .filter(n -> n.kind == WorkflowApp.Kind.SUBGRAPH)
                                    .findFirst()
                                    .orElseThrow();
                    var renameNode =
                            editor.flow.nodes.stream()
                                    .filter(n -> n.kind == WorkflowApp.Kind.RENAME)
                                    .findFirst()
                                    .orElseThrow();
                    editor.flow.edges.removeIf(
                            e -> e.from().equals(node.id) && e.port().equals("csv"));
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_PRESSED,
                                    1,
                                    0,
                                    (int) node.x + WorkflowApp.Canvas.W,
                                    (int) node.y + 108,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    editor.canvas.dispatchEvent(
                            new MouseEvent(
                                    editor.canvas,
                                    MouseEvent.MOUSE_RELEASED,
                                    2,
                                    0,
                                    (int) renameNode.x,
                                    (int) renameNode.y + 108,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    require(
                            editor.flow.edges.stream()
                                    .anyMatch(
                                            e ->
                                                    e.fromPort().equals("rulesOut")
                                                            && e.port().equals("csv")),
                            "second output mouse port");
                    editor.select(node);
                    editor.copySelection();
                    node.child.nodes.get(0).settings.put("interfaceName", "changed");
                    editor.paste(null);
                    require(
                            editor.selected
                                    .child
                                    .nodes
                                    .get(0)
                                    .settings
                                    .get("interfaceName")
                                    .equals("documents"),
                            "deep clipboard snapshot");
                });
        checks.add("Swing第二具名输出端口鼠标连线、子图深复制剪贴板通过。");
        Path aSource = root.resolve("a-source"), bSource = root.resolve("b-source");
        Files.createDirectories(aSource);
        Files.createDirectories(bSource);
        Files.writeString(aSource.resolve("a.txt"), "ONLY ALPHA");
        Files.writeString(bSource.resolve("b.txt"), "ONLY BETA");
        var aResult =
                execute(parent(aSource, definition, root.resolve("a-result")), root, "a-call");
        var bResult =
                execute(parent(bSource, definition, root.resolve("b-result")), root, "b-call");
        try (var aDoc = Loader.loadPDF(aResult.files().get(0).destination().toFile());
                var bDoc = Loader.loadPDF(bResult.files().get(0).destination().toFile())) {
            String aText = new PDFTextStripper().getText(aDoc),
                    bText = new PDFTextStripper().getText(bDoc);
            require(
                    aText.contains("ALPHA")
                            && !aText.contains("BETA")
                            && bText.contains("BETA")
                            && !bText.contains("ALPHA"),
                    "different inputs never cross");
        }
        checks.add("同一已导出定义处理两组不同输入，内容ALPHA/BETA与来源不串值。");
        var strictModule = new WorkflowApp.Flow();
        var strictIn = virtual(strictModule, false, "files", "FILE", "COLLECTION", 100);
        var strictOut = virtual(strictModule, true, "result", "FILE", "COLLECTION", 100);
        strictModule.connect(strictIn, strictOut);
        Path fake = root.resolve("fake.pdf");
        Files.writeString(fake, "not a pdf");
        var strictParent = parent(source, strictModule, root.resolve("strict-output"));
        strictIn.settings.put("formats", "pdf");
        strictParent.nodes.get(0).settings.put("selectionMode", "SINGLE");
        strictParent.nodes.get(0).settings.put("sourcePath", fake.toString());
        require(
                rejects(strictParent, root) && !Files.exists(root.resolve("strict-output")),
                "content gate rejects disguised extension");
        strictIn.settings.put("cardinality", "SINGLE");
        failed = false;
        try {
            InternalData.validate(
                    strictIn,
                    new BatchRunner.Bundle(
                            Map.of(
                                    "data",
                                    FileCli.value(
                                            "FILE",
                                            List.of(
                                                    FileCli.item(
                                                            source.resolve("1.txt").toString()),
                                                    FileCli.item(
                                                            source.resolve("2.txt").toString())))),
                            List.of()));
        } catch (IOException ex) {
            failed = true;
        }
        require(failed, "actual single cardinality check");
        checks.add("运行时边界拒绝伪装PDF和单项接口收到两项，未发布输出。");
        var noncontiguous = new WorkflowApp.Flow();
        var ncIn = BatchTests.input(noncontiguous, source, true, "txt");
        var a = noncontiguous.add(WorkflowApp.Kind.PDF, 250, 100);
        var b = noncontiguous.add(WorkflowApp.Kind.RENAME, 480, 100);
        var c = noncontiguous.add(WorkflowApp.Kind.RENAME, 710, 100);
        noncontiguous.connect(ncIn, a);
        noncontiguous.connect(a, b);
        noncontiguous.connect(b, c);
        BatchTests.output(noncontiguous, c, root.resolve("noncontiguous"));
        int originalNodes = noncontiguous.nodes.size(), originalEdges = noncontiguous.edges.size();
        failed = false;
        try {
            Subgraphs.encapsulate(noncontiguous, Set.of(a.id, c.id));
        } catch (IOException ex) {
            failed = true;
        }
        require(
                failed
                        && noncontiguous.nodes.size() == originalNodes
                        && noncontiguous.edges.size() == originalEdges,
                "invalid encapsulation is atomic");
        checks.add("非连续封装导致父图环路时拒绝，原图节点与连线保持不变。");
        Json.write(root.resolve("summary.json"), Map.of("status", "PASS", "checks", checks));
        Files.write(root.resolve("测试结果.txt"), checks);
        System.out.println(
                "PASS subgraphs: " + checks.size() + " checks; " + root.resolve("summary.json"));
    }
}

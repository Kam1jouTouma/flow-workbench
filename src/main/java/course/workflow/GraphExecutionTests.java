package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import javax.swing.*;

final class GraphExecutionTests {
    static Path root, txt, two, csv;
    static List<Object> checks = new ArrayList<>();

    interface Action {
        void run() throws Exception;
    }

    static void require(boolean b, String why) {
        if (!b) throw new AssertionError(why);
    }

    static void test(String id, String name, Action action) {
        try {
            action.run();
            checks.add(Map.of("id", id, "name", name, "status", "PASS"));
            System.out.println("PASS " + id + " " + name);
        } catch (Throwable ex) {
            checks.add(Map.of("id", id, "name", name, "status", "FAIL", "error", ex.toString()));
            System.out.println("FAIL " + id + " " + ex);
        }
    }

    static WorkflowApp.Node in(WorkflowApp.Flow f, Path p) {
        return BatchTests.input(f, p, false, "txt,csv");
    }

    static WorkflowApp.Node rename(WorkflowApp.Flow f, WorkflowApp.Node n, String template) {
        var work = f.add(WorkflowApp.Kind.RENAME, 300, 100);
        work.settings.put("template", template);
        require(f.connect(n, work) == null, "rename binding");
        return work;
    }

    static WorkflowApp.Node out(WorkflowApp.Flow f, WorkflowApp.Node n, String dir) {
        return BatchTests.output(f, n, root.resolve(dir));
    }

    static FileTransferRunner.Result execute(WorkflowApp.Flow f, String id) throws IOException {
        return BatchTests.execute(f, root, id, new ArrayList<>());
    }

    static Map<String, Object> record(FileTransferRunner.Result r) throws IOException {
        return Json.object(Json.read(r.record()));
    }

    static boolean rejected(WorkflowApp.Flow f, String id) throws IOException {
        try {
            execute(f, id);
            return false;
        } catch (IOException ex) {
            Files.writeString(root.resolve(id + "-error.txt"), ex.toString());
            return true;
        }
    }

    static WorkflowApp.Node merge(WorkflowApp.Flow f, WorkflowApp.Node a, WorkflowApp.Node b) {
        var n = f.add(WorkflowApp.Kind.FILE_MERGE, 350, 100);
        require(
                f.connect(a, "data", n, "input1", true) == null
                        && f.connect(b, "data", n, "input2", true) == null,
                "merge named inputs");
        return n;
    }

    static void run(Path path) throws Exception {
        run(path, true);
    }

    static void run(Path path, boolean officeEnabled) throws Exception {
        root = path.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录必须不存在");
        Files.createDirectories(root);
        Path inputs = root.resolve("输入 中文 spaces");
        Files.createDirectories(inputs);
        txt = inputs.resolve("1.txt");
        two = inputs.resolve("2.txt");
        csv = inputs.resolve("rules.csv");
        Files.writeString(txt, "first 中文");
        Files.writeString(two, "second 中文");
        Files.writeString(csv, "label\nAlpha\nBeta\n");
        test(
                "T01",
                "单输入单输出",
                () -> {
                    var f = new WorkflowApp.Flow();
                    out(f, in(f, txt), "T01");
                    var r = execute(f, "T01");
                    require(
                            r.files().size() == 1
                                    && Files.readString(r.files().get(0).destination())
                                            .equals(Files.readString(txt)),
                            "copy content");
                });
        test(
                "T02",
                "多输入单输出 文档加CSV",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = BatchTests.input(f, inputs, true, "txt");
                    var rule = in(f, csv);
                    var n = rename(f, a, "{label}_{x}");
                    f.connect(rule, n, "csv");
                    out(f, n, "T02");
                    require(
                            execute(f, "T02").files().size() == 2
                                    && Files.exists(root.resolve("T02/Alpha_1.txt"))
                                    && Files.exists(root.resolve("T02/Beta_2.txt")),
                            "CSV mapping");
                });
        test(
                "T03",
                "单输入多最终输出",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    out(f, a, "T03-A");
                    out(f, rename(f, a, "branch_{x}"), "T03-B");
                    var r = execute(f, "T03");
                    require(r.files().size() == 2 && Files.exists(txt), "two destinations");
                });
        test(
                "T04",
                "多输入多输出",
                () -> {
                    var f = new WorkflowApp.Flow();
                    out(f, in(f, txt), "T04-A");
                    out(f, in(f, two), "T04-B");
                    require(execute(f, "T04").files().size() == 2, "independent outputs");
                });
        test(
                "T05",
                "实际并行任务 时间区间重叠",
                () -> {
                    var f = new WorkflowApp.Flow();
                    out(f, rename(f, in(f, txt), "A_{x}"), "T05-A");
                    out(f, rename(f, in(f, two), "B_{x}"), "T05-B");
                    var r = execute(f, "T05");
                    List<Map<String, Object>> executions = new ArrayList<>();
                    for (Object v : Json.list(record(r).get("nodeExecutions"))) {
                        var e = Json.object(v);
                        if ("RENAME".equals(e.get("kind"))) executions.add(e);
                    }
                    require(executions.size() == 2, "both work tasks");
                    var a = executions.get(0);
                    var b = executions.get(1);
                    require(
                            !a.get("thread").equals(b.get("thread"))
                                    && Instant.parse((String) a.get("startedAt"))
                                            .isBefore(Instant.parse((String) b.get("finishedAt")))
                                    && Instant.parse((String) b.get("startedAt"))
                                            .isBefore(Instant.parse((String) a.get("finishedAt"))),
                            "real overlapping execution");
                });
        test(
                "T06",
                "同源分支处理后汇合 CSV单项动态检查",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, csv);
                    var data = rename(f, a, "payload_{x}");
                    var rules = rename(f, a, "rules_{x}");
                    var joined = rename(f, data, "{label}_{x}");
                    require(f.connect(rules, joined, "csv") == null, "processed CSV binding");
                    out(f, joined, "T06");
                    var r = execute(f, "T06");
                    require(Files.exists(root.resolve("T06/Alpha_1.csv")), "processed CSV used");
                    long count =
                            Json.list(record(r).get("toolCapabilities")).stream()
                                    .filter(
                                            v ->
                                                    ((Map<?, ?>) v)
                                                            .get("toolId")
                                                            .equals("io.file_select"))
                                    .count();
                    require(count == 1, "shared input once");
                });
        test(
                "T07",
                "两个独立文件集合汇合再合并PDF",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var m = merge(f, in(f, txt), in(f, two));
                    var pdf = f.add(WorkflowApp.Kind.PDF, 450, 100);
                    pdf.settings.put("pdfMode", "MERGE");
                    f.connect(m, pdf);
                    out(f, pdf, "T07");
                    var r = execute(f, "T07");
                    require(
                            BatchTests.pages(r.files().get(0).destination()) == 2
                                    && BatchTests.text(r.files().get(0).destination())
                                            .contains("second"),
                            "two-page merge");
                });
        test(
                "T08",
                "一路工作失败 所有目标不发布不删源",
                () -> {
                    Path fake = root.resolve("fake.pdf");
                    Files.writeString(fake, "not pdf");
                    var f = new WorkflowApp.Flow();
                    var good = out(f, rename(f, in(f, txt), "good_{x}"), "T08-good");
                    good.settings.put("operation", "MOVE");
                    var bad = f.add(WorkflowApp.Kind.PDF, 350, 350);
                    f.connect(in(f, fake), bad);
                    out(f, bad, "T08-bad");
                    require(
                            rejected(f, "T08")
                                    && !Files.exists(root.resolve("T08-good"))
                                    && !Files.exists(root.resolve("T08-bad"))
                                    && Files.exists(txt),
                            "all branches protected");
                });
        test(
                "T09",
                "空集合拒绝",
                () -> {
                    Path empty = root.resolve("empty");
                    Files.createDirectories(empty);
                    var f = new WorkflowApp.Flow();
                    out(f, BatchTests.input(f, empty, true, "txt"), "T09");
                    require(rejected(f, "T09") && !Files.exists(root.resolve("T09")), "empty safe");
                });
        test(
                "T10",
                "101文件含8MiB 内容一致",
                () -> {
                    Path many = root.resolve("many");
                    Files.createDirectories(many);
                    for (int i = 0; i < 100; i++)
                        Files.writeString(many.resolve(i + ".txt"), "中文" + i + "x".repeat(1024));
                    byte[] big = new byte[8 * 1024 * 1024];
                    Arrays.fill(big, (byte) 'x');
                    Files.write(many.resolve("big.txt"), big);
                    var f = new WorkflowApp.Flow();
                    out(f, BatchTests.input(f, many, true, "txt"), "T10");
                    long start = System.nanoTime();
                    var r = execute(f, "T10");
                    require(r.files().size() == 101, "count");
                    for (var p : r.files())
                        require(
                                FileCli.sha256(p.destination())
                                        .equals(
                                                FileCli.sha256(
                                                        many.resolve(
                                                                p.destination().getFileName()))),
                                "hash");
                    Json.write(
                            root.resolve("load-metrics.json"),
                            Map.of(
                                    "files",
                                    101,
                                    "largestBytes",
                                    big.length,
                                    "milliseconds",
                                    (System.nanoTime() - start) / 1000000));
                });
        test(
                "T11",
                "同源双端口共享缓存",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, csv);
                    var n = rename(f, a, "{label}_{x}");
                    f.connect(a, n, "csv");
                    out(f, n, "T11");
                    require(execute(f, "T11").files().size() == 1, "dual port");
                });
        test(
                "T12",
                "同一流程重复三次",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var o = out(f, in(f, txt), "T12");
                    o.settings.put("conflictPolicy", "RENAME");
                    Set<String> runs = new HashSet<>();
                    Set<Path> targets = new HashSet<>();
                    for (int i = 0; i < 3; i++) {
                        var r = execute(f, "T12-" + i);
                        runs.add(r.runId());
                        targets.add(r.files().get(0).destination());
                    }
                    require(runs.size() == 3 && targets.size() == 3, "isolated records");
                });
        test(
                "S01",
                "跨输出同名ERROR拒绝 不发布",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    out(f, a, "S01");
                    out(f, a, "S01");
                    require(
                            rejected(f, "S01") && !Files.exists(root.resolve("S01")),
                            "cross-target preflight");
                });
        test(
                "S02",
                "跨输出同名RENAME不互相覆盖",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    out(f, a, "S02").settings.put("conflictPolicy", "RENAME");
                    out(f, a, "S02").settings.put("conflictPolicy", "RENAME");
                    var r = execute(f, "S02");
                    require(
                            r.files().size() == 2
                                    && !r.files()
                                            .get(0)
                                            .destination()
                                            .equals(r.files().get(1).destination()),
                            "distinct names");
                });
        test(
                "S03",
                "共享来源COPY优先保留",
                () -> {
                    Path source = root.resolve("mixed.txt");
                    Files.writeString(source, "mixed");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, source);
                    out(f, a, "S03-A").settings.put("operation", "MOVE");
                    out(f, a, "S03-B");
                    var r = execute(f, "S03");
                    require(
                            r.operation().equals("MIXED")
                                    && r.files().size() == 2
                                    && Files.exists(source),
                            "COPY wins");
                });
        test(
                "S04",
                "所有分支MOVE成功后只删一次",
                () -> {
                    Path source = root.resolve("move.txt");
                    Files.writeString(source, "move");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, source);
                    out(f, a, "S04-A").settings.put("operation", "MOVE");
                    out(f, a, "S04-B").settings.put("operation", "MOVE");
                    var r = execute(f, "S04");
                    require(
                            !Files.exists(source)
                                    && r.files().size() == 2
                                    && Json.list(record(r).get("removedSources")).size() == 1,
                            "shared deletion once");
                });
        test(
                "S05",
                "规则CSV即使MOVE输出仍保留",
                () -> {
                    Path source = root.resolve("csv-source.txt");
                    Files.writeString(source, "csv source");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, source);
                    var rule = in(f, csv);
                    var n = rename(f, a, "{label}_{x}");
                    f.connect(rule, n, "csv");
                    out(f, n, "S05-A").settings.put("operation", "MOVE");
                    out(f, rule, "S05-B").settings.put("operation", "MOVE");
                    execute(f, "S05");
                    require(
                            !Files.exists(source) && Files.exists(csv),
                            "CSV protected across outputs");
                });
        test(
                "S06",
                "发布中途异常 回滚覆盖和新目标",
                () -> {
                    Path source = root.resolve("rollback.txt");
                    Files.writeString(source, "new");
                    Path old = root.resolve("S06-A");
                    Files.createDirectories(old);
                    Files.writeString(old.resolve("rollback.txt"), "old");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, source);
                    var first = out(f, a, "S06-A");
                    first.settings.put("conflictPolicy", "OVERWRITE");
                    first.settings.put("operation", "MOVE");
                    out(f, a, "S06-B").settings.put("operation", "MOVE");
                    boolean failed = false;
                    try {
                        BatchRunner.execute(
                                f,
                                root.resolve("runs"),
                                v -> {},
                                count -> {
                                    if (count == 2)
                                        throw new IOException("injected commit failure");
                                });
                    } catch (IOException ex) {
                        failed = true;
                    }
                    require(
                            failed
                                    && Files.readString(old.resolve("rollback.txt")).equals("old")
                                    && !Files.exists(root.resolve("S06-B/rollback.txt"))
                                    && Files.exists(source),
                            "rollback all destinations and retain source");
                });
        test(
                "S07",
                "实际多文件规则输入运行时拒绝",
                () -> {
                    Path rules = root.resolve("many-csv");
                    Files.createDirectories(rules);
                    Files.copy(csv, rules.resolve("1.csv"));
                    Files.copy(csv, rules.resolve("2.csv"));
                    var f = new WorkflowApp.Flow();
                    var n = rename(f, in(f, txt), "{label}_{x}");
                    var rule = rename(f, BatchTests.input(f, rules, true, "csv"), "rule_{x}");
                    require(f.connect(rule, n, "csv") == null, "dynamic binding allowed");
                    out(f, n, "S07");
                    require(
                            rejected(f, "S07") && !Files.exists(root.resolve("S07")),
                            "runtime count enforced");
                });
        test(
                "S08",
                "汇合集合相同来源去重",
                () -> {
                    var f = new WorkflowApp.Flow();
                    out(f, merge(f, in(f, txt), in(f, txt)), "S08");
                    require(execute(f, "S08").files().size() == 1, "identity dedup");
                });
        test(
                "S09",
                "汇合输入缺失拒绝",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    var m = f.add(WorkflowApp.Kind.FILE_MERGE, 300, 100);
                    f.connect(a, "data", m, "input1", true);
                    out(f, m, "S09");
                    require(
                            rejected(f, "S09") && !Files.exists(root.resolve("S09")),
                            "unbound input");
                });
        test(
                "S10",
                "汇合元素配置与保存回读",
                () -> {
                    SwingUtilities.invokeAndWait(
                            () -> {
                                var e = new WorkflowApp.Editor();
                                e.flow = new WorkflowApp.Flow();
                                var n = e.flow.add(WorkflowApp.Kind.FILE_MERGE, 100, 100);
                                e.select(n);
                                ((JSpinner) e.batchPanel.mergeControls.get("inputCount"))
                                        .setValue(3);
                                require(n.inputPorts().size() == 3, "live ports");
                                try {
                                    Path p = root.resolve("merge-config.flow.xml");
                                    e.flow.save(p);
                                    require(
                                            WorkflowApp.Flow.load(p)
                                                            .nodes
                                                            .get(0)
                                                            .inputPorts()
                                                            .size()
                                                    == 3,
                                            "persist ports");
                                } catch (IOException ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
                });
        test(
                "S11",
                "嵌套子图内汇合 多父图输出",
                () -> {
                    var child = new WorkflowApp.Flow();
                    var a = SubgraphTests.virtual(child, false, "a", "FILE", "COLLECTION", 100);
                    var b = SubgraphTests.virtual(child, false, "b", "FILE", "COLLECTION", 300);
                    var m = merge(child, a, b);
                    var v = SubgraphTests.virtual(child, true, "result", "FILE", "COLLECTION", 100);
                    child.connect(m, v);
                    var f = new WorkflowApp.Flow();
                    var module = f.add(WorkflowApp.Kind.SUBGRAPH, 300, 100);
                    module.child = child;
                    f.connect(in(f, txt), "data", module, "a", true);
                    f.connect(in(f, two), "data", module, "b", true);
                    var o = f.add(WorkflowApp.Kind.SAVE, 650, 100);
                    o.settings.put("directory", root.resolve("S11-A").toString());
                    f.connect(module, "result", o, "data", true);
                    var o2 = f.add(WorkflowApp.Kind.SAVE, 650, 300);
                    o2.settings.put("directory", root.resolve("S11-B").toString());
                    f.connect(module, "result", o2, "data", true);
                    require(execute(f, "S11").files().size() == 4, "nested merged outputs");
                });
        test(
                "S12",
                "发布期间来源被改写 拒绝删除并回滚",
                () -> {
                    Path source = root.resolve("changed.txt");
                    Files.writeString(source, "initial");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, source);
                    out(f, a, "S12").settings.put("operation", "MOVE");
                    boolean failed = false;
                    try {
                        BatchRunner.execute(
                                f,
                                root.resolve("runs"),
                                v -> {},
                                count -> Files.writeString(source, "external change"));
                    } catch (IOException ex) {
                        failed = true;
                    }
                    require(
                            failed
                                    && Files.readString(source).equals("external change")
                                    && !Files.exists(root.resolve("S12/changed.txt")),
                            "preserve changed source");
                });
        test(
                "S13",
                "发布期间目标被占用 不覆盖外部文件",
                () -> {
                    Path secondTarget = root.resolve("S13-B/1.txt");
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    out(f, a, "S13-A");
                    out(f, a, "S13-B");
                    boolean failed = false;
                    try {
                        BatchRunner.execute(
                                f,
                                root.resolve("runs"),
                                v -> {},
                                count -> {
                                    if (count == 1)
                                        Files.writeString(secondTarget, "external target");
                                });
                    } catch (IOException ex) {
                        failed = true;
                    }
                    require(
                            failed
                                    && !Files.exists(root.resolve("S13-A/1.txt"))
                                    && Files.readString(secondTarget).equals("external target"),
                            "race rollback safe");
                });
        test(
                "S14",
                "调度依赖完成后才启动下游",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var a = in(f, txt);
                    var b = rename(f, a, "one_{x}");
                    var c = rename(f, b, "two_{x}");
                    out(f, c, "S14");
                    var r = execute(f, "S14");
                    Map<String, Map<String, Object>> times = new HashMap<>();
                    for (Object v : Json.list(record(r).get("nodeExecutions"))) {
                        var e = Json.object(v);
                        times.put((String) e.get("nodeId"), e);
                    }
                    for (var edge : Subgraphs.expand(f).flow().edges) {
                        if (times.containsKey(edge.from()) && times.containsKey(edge.to()))
                            require(
                                    !Instant.parse((String) times.get(edge.to()).get("startedAt"))
                                            .isBefore(
                                                    Instant.parse(
                                                            (String)
                                                                    times.get(edge.from())
                                                                            .get("finishedAt"))),
                                    "dependency timing");
                    }
                });
        test(
                "S15",
                "动态CSV边界封装后单项断言保持",
                () -> {
                    var f = new WorkflowApp.Flow();
                    var rule = rename(f, in(f, csv), "rule_{x}");
                    var n = rename(f, in(f, txt), "{label}_{x}");
                    f.connect(rule, n, "csv");
                    out(f, n, "S15");
                    Subgraphs.encapsulate(f, Set.of(rule.id));
                    Subgraphs.encapsulate(f, Set.of(n.id));
                    require(
                            execute(f, "S15").files().size() == 1
                                    && Files.exists(root.resolve("S15/Alpha_1.txt")),
                            "single-boundary encapsulation");
                });
        if (officeEnabled)
            test(
                    "S16",
                    "并行Office PDF分支 按Office家族互斥",
                    () -> {
                        Path fixture = FileTransferRunner.toolPath("test-fixtures/word.docx");
                        require(Files.isRegularFile(fixture), "real Word fixture");
                        var f = new WorkflowApp.Flow();
                        var a = in(f, fixture);
                        var p1 = f.add(WorkflowApp.Kind.PDF, 300, 100);
                        var p2 = f.add(WorkflowApp.Kind.PDF, 300, 300);
                        f.connect(a, p1);
                        f.connect(a, p2);
                        out(f, p1, "S16-A");
                        out(f, p2, "S16-B");
                        var r = execute(f, "S16");
                        require(r.files().size() == 2, "both Office branches");
                        for (var file : r.files())
                            require(
                                    BatchTests.pages(file.destination()) > 0
                                            && BatchTests.text(file.destination()).contains("Word"),
                                    "valid Word conversion");
                    });
        else
            System.out.println(
                    "SKIP S16: optional Office integration; use --test-graph-execution with Office and fixtures installed.");
        Json.write(
                root.resolve("summary.json"),
                Map.of(
                        "checks",
                        checks,
                        "pass",
                        checks.stream()
                                .filter(v -> ((Map<?, ?>) v).get("status").equals("PASS"))
                                .count(),
                        "fail",
                        checks.stream()
                                .filter(v -> ((Map<?, ?>) v).get("status").equals("FAIL"))
                                .count()));
        if (checks.stream().anyMatch(v -> ((Map<?, ?>) v).get("status").equals("FAIL")))
            throw new AssertionError("Graph execution regression failed; see summary");
        System.out.println("PASS graph execution: " + checks.size() + " checks");
    }
}

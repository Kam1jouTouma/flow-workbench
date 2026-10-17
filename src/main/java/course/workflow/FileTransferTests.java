package course.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** End-to-end tests use actual saved graphs and both independent Java CLI JARs. */
final class FileTransferTests {
    private FileTransferTests() {}

    static WorkflowApp.Flow graph(Path source, Path target, String selection, String operation) {
        WorkflowApp.Flow f = new WorkflowApp.Flow();
        WorkflowApp.Node input = f.add(WorkflowApp.Kind.FILE, 62, 135),
                output = f.add(WorkflowApp.Kind.SAVE, 382, 135);
        input.settings.put("sourcePath", source.toAbsolutePath().toString());
        input.settings.put("mode", "FILE");
        input.settings.put("selectionMode", selection);
        input.settings.put("extensions", "txt,csv");
        output.settings.put("directory", target.toAbsolutePath().toString());
        output.settings.put("format", "KEEP");
        output.settings.put("operation", operation);
        output.settings.put("preserveNames", true);
        f.connect(input, output);
        return f;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void run(Path requested) throws IOException {
        Path root = requested.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录已存在，请使用新目录，以免改变已有测试文件。");
        Files.createDirectories(root);
        Path runs = root.resolve("runs");
        List<Object> cases = new ArrayList<>();
        Path single = root.resolve("01-single/source/指定文件 中文.txt"),
                singleDestination = root.resolve("01-single/destination");
        Files.createDirectories(single.getParent());
        Files.writeString(single, "指定文件搬移测试\r\n原字节内容必须一致。\r\n");
        String singleHash = FileCli.sha256(single);
        WorkflowApp.Flow f = graph(single, singleDestination, "SINGLE", "MOVE");
        Path singleFlow = root.resolve("01-single/graph.flow.xml");
        f.save(singleFlow);
        FileTransferRunner.Result moved =
                FileTransferRunner.execute(WorkflowApp.Flow.load(singleFlow), runs);
        check(
                moved.files().size() == 1 && !Files.exists(single),
                "MOVE must remove original only after successful destination");
        check(
                FileCli.sha256(moved.files().get(0).destination()).equals(singleHash),
                "MOVE content hash");
        cases.add(
                Map.of(
                        "scenario",
                        "指定单文件搬移",
                        "status",
                        "PASS",
                        "count",
                        1,
                        "sourceRemoved",
                        true,
                        "runRecord",
                        moved.record().toString()));

        Path batch = root.resolve("batch-source");
        Files.createDirectories(batch);
        List<String> supported =
                new ArrayList<>(
                        List.of(
                                "01.txt", "02.TXT", "03.csv", "04.md", "05.json", "06.pdf",
                                "07.docx", "08.xlsx", "09.png", "10.jpg", "11.jpeg", "12.webp"));
        for (String extension : FileCli.SUPPORTED)
            if (supported.stream().noneMatch(n -> FileCli.extension(Path.of(n)).equals(extension)))
                supported.add("office." + extension.toUpperCase(Locale.ROOT));
        for (String name : supported) {
            byte[] payload =
                    ("二进制保真传递测试:" + name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(batch.resolve(name), payload);
        }
        for (String name : List.of("13.exe", "14.bin", "15-no-extension"))
            Files.writeString(batch.resolve(name), "不应被支持类型筛选选中:" + name);
        Files.createDirectories(batch.resolve("nested"));
        Files.writeString(batch.resolve("nested/16.txt"), "默认不递归");
        Map<String, String> originals = new LinkedHashMap<>();
        try (var paths = Files.walk(batch)) {
            for (Path p : paths.filter(Files::isRegularFile).toList())
                originals.put(batch.relativize(p).toString(), FileCli.sha256(p));
        }
        Path typesDestination = root.resolve("02-types/destination");
        f = graph(batch, typesDestination, "TYPES", "COPY");
        Path typesFlow = root.resolve("02-types/graph.flow.xml");
        Files.createDirectories(typesFlow.getParent());
        f.save(typesFlow);
        FileTransferRunner.Result chosen =
                FileTransferRunner.execute(WorkflowApp.Flow.load(typesFlow), runs);
        Set<String> actual = new HashSet<>();
        for (var p : chosen.files()) {
            actual.add(p.destination().getFileName().toString());
            check(
                    FileCli.sha256(p.source()).equals(FileCli.sha256(p.destination())),
                    "filtered copy hash");
        }
        check(
                actual.equals(Set.of("01.txt", "02.TXT", "03.csv")),
                "exact case-insensitive txt/csv selection");
        cases.add(
                Map.of(
                        "scenario",
                        "目录中指定类型复制 txt,csv",
                        "status",
                        "PASS",
                        "count",
                        3,
                        "sourceRemoved",
                        false,
                        "runRecord",
                        chosen.record().toString()));

        Path allDestination = root.resolve("03-supported/destination");
        f = graph(batch, allDestination, "SUPPORTED", "COPY");
        Path allFlow = root.resolve("03-supported/graph.flow.xml");
        Files.createDirectories(allFlow.getParent());
        f.save(allFlow);
        FileTransferRunner.Result all =
                FileTransferRunner.execute(WorkflowApp.Flow.load(allFlow), runs);
        check(all.files().size() == supported.size(), "all supported extensions");
        for (var p : all.files())
            check(
                    FileCli.sha256(p.source()).equals(FileCli.sha256(p.destination())),
                    "supported file content hash");
        for (var e : originals.entrySet())
            check(
                    Files.isRegularFile(batch.resolve(e.getKey()))
                            && FileCli.sha256(batch.resolve(e.getKey())).equals(e.getValue()),
                    "batch source kept unchanged");
        check(
                !Files.exists(allDestination.resolve("13.exe"))
                        && !Files.exists(allDestination.resolve("14.bin"))
                        && !Files.exists(allDestination.resolve("15-no-extension"))
                        && !Files.exists(allDestination.resolve("nested")),
                "unsupported files and default subdirectories skipped");
        cases.add(
                Map.of(
                        "scenario",
                        "所有支持后缀复制",
                        "status",
                        "PASS",
                        "count",
                        supported.size(),
                        "sourceRemoved",
                        false,
                        "runRecord",
                        all.record().toString()));
        WorkflowApp.Flow office =
                graph(batch, root.resolve("04-office/destination"), "TYPES", "COPY");
        office.nodes.get(0).settings.put("extensions", ".doc,.docx,.pdf,.xls,.xlsx,.ppt,.pptx");
        FileTransferRunner.Result officeResult = FileTransferRunner.execute(office, runs);
        check(officeResult.files().size() == 7, "specified Word PDF Excel PowerPoint selection");
        for (var p : officeResult.files())
            check(
                    FileCli.sha256(p.source()).equals(FileCli.sha256(p.destination())),
                    "office file hash");
        cases.add(
                Map.of(
                        "scenario",
                        "指定Word PDF Excel PowerPoint复制",
                        "status",
                        "PASS",
                        "count",
                        7,
                        "sourceRemoved",
                        false,
                        "runRecord",
                        officeResult.record().toString()));

        WorkflowApp.Flow clash = graph(batch.resolve("01.txt"), typesDestination, "SINGLE", "COPY");
        String protectedHash = FileCli.sha256(typesDestination.resolve("01.txt"));
        boolean refused = false;
        try {
            FileTransferRunner.execute(clash, runs);
        } catch (IOException ex) {
            refused = true;
        }
        check(
                refused && FileCli.sha256(typesDestination.resolve("01.txt")).equals(protectedHash),
                "ERROR does not overwrite");
        clash.nodes.get(1).settings.put("conflictPolicy", "RENAME");
        FileTransferRunner.Result renamed = FileTransferRunner.execute(clash, runs);
        check(
                renamed.files().get(0).destination().getFileName().toString().equals("01 (1).txt"),
                "RENAME finds actual unused name");
        Path overwriteDir = root.resolve("overwrite-check");
        Files.createDirectories(overwriteDir);
        Files.writeString(overwriteDir.resolve("01.txt"), "old");
        WorkflowApp.Flow overwrite = graph(batch.resolve("01.txt"), overwriteDir, "SINGLE", "COPY");
        overwrite.nodes.get(1).settings.put("conflictPolicy", "OVERWRITE");
        check(
                FileTransferRunner.execute(overwrite, runs).files().size() == 1
                        && FileCli.sha256(overwriteDir.resolve("01.txt"))
                                .equals(originals.get("01.txt")),
                "explicit overwrite");
        WorkflowApp.Flow nested =
                graph(batch, batch.resolve("should-not-create"), "SUPPORTED", "COPY");
        refused = false;
        try {
            FileTransferRunner.execute(nested, runs);
        } catch (IOException ex) {
            refused = true;
        }
        check(
                refused && !Files.exists(batch.resolve("should-not-create")),
                "refuse output inside batch source");
        WorkflowApp.Flow none = graph(batch, root.resolve("no-match"), "TYPES", "COPY");
        none.nodes.get(0).settings.put("extensions", "xyz");
        refused = false;
        try {
            FileTransferRunner.execute(none, runs);
        } catch (IOException ex) {
            refused = true;
        }
        check(
                refused && !Files.exists(root.resolve("no-match")),
                "empty selection fails without publication");
        Map<String, Object> summary =
                Map.of(
                        "status",
                        "PASS",
                        "cases",
                        cases,
                        "supportedExtensions",
                        FileCli.SUPPORTED,
                        "extraChecks",
                        List.of("已有文件不覆盖", "自动改名", "显式覆盖", "目标位于源目录内拒绝", "无匹配文件拒绝"),
                        "fixtureNote",
                        "后缀类型测试使用自建载荷，测试原字节传递和筛选，不测试PDF/Office/图片解码或格式有效性");
        Json.write(root.resolve("summary.json"), summary);
        Files.writeString(
                root.resolve("测试结果.txt"),
                "文件传递与办公格式筛选测试全部通过\n1. 指定单文件搬移：1个，目标SHA256一致，源文件删除。\n2. 指定类型txt,csv复制：3个，大小写后缀兼容，源文件保留。\n3. 所有支持类型复制："
                        + supported.size()
                        + "个文件、"
                        + FileCli.SUPPORTED.size()
                        + "种后缀，源文件保留；exe、bin、无后缀和子目录排除。\n4. 指定doc,docx,pdf,xls,xlsx,ppt,pptx复制：7个，内容一致。\n全部目标逐文件SHA256与源文件/输入快照一致。\n额外检查：报错不覆盖、自动改名、显式覆盖、源目录内目标拒绝、无匹配拒绝。\n测试通过真实保存的两节点流程和独立Java CLI工具执行，不是模拟结果。\n后缀测试载荷为自建内容，不声明具体文档/图片格式有效性。\n详细清单：summary.json；每次调用：runs/<runId>/run.json及两个工具日志。\n");
        System.out.println("PASS 1: moved 1 specified file; source removed; SHA-256 identical.");
        System.out.println(
                "PASS 2: copied 3 txt/csv files; case-insensitive selection; all sources preserved.");
        System.out.println(
                "PASS 3: copied "
                        + supported.size()
                        + " files across "
                        + FileCli.SUPPORTED.size()
                        + " supported extensions; unsupported files excluded; all sources preserved.");
        System.out.println(
                "PASS 4: copied 7 specified Word/PDF/Excel/PowerPoint files, including uppercase extensions.");
        System.out.println(
                "PASS extra: conflict ERROR, RENAME, OVERWRITE, output-inside-source and empty selection.");
        System.out.println("Report: " + root.resolve("summary.json"));
    }
}

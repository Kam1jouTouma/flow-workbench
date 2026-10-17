package course.workflow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

/** Execute real BAT/EXE/Python programs through persisted workflow graphs. */
final class ExecutableToolTests {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static WorkflowApp.Flow graph(
            Path source, String extensions, WorkflowApp.Kind kind, Path destination) {
        var flow = new WorkflowApp.Flow();
        var in = BatchTests.input(flow, source, true, extensions);
        var work = flow.add(kind, 350, 100);
        check(flow.connect(in, work) == null, "work connection");
        BatchTests.output(flow, work, destination);
        return flow;
    }

    static FileTransferRunner.Result execute(WorkflowApp.Flow flow, Path root, String name)
            throws IOException {
        Path path = root.resolve(name + ".flow.xml");
        flow.save(path);
        return BatchRunner.execute(
                WorkflowApp.Flow.load(path), root.resolve("runs"), messages -> {});
    }

    static void run(Path root) throws Exception {
        root = root.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录必须不存在。");
        Files.createDirectories(root);
        List<String> checks = new ArrayList<>();
        for (var kind :
                List.of(
                        WorkflowApp.Kind.JAVA_BUILD,
                        WorkflowApp.Kind.AUDIO,
                        WorkflowApp.Kind.EXCEL_MERGE)) {
            var probe = ToolLauncher.probe(FileTransferRunner.toolPath(ExecutableTools.file(kind)));
            check(
                    Json.object(probe.get("describe")).get("id").equals(ExecutableTools.id(kind)),
                    "tool identity");
        }
        checks.add("真实BAT、EXE、PY查询help/describe/capabilities通过。");
        Path javaSource = root.resolve("Java源码");
        Files.createDirectories(javaSource.resolve("app"));
        Files.createDirectories(javaSource.resolve("util"));
        Files.writeString(
                javaSource.resolve("app/Main.java"),
                "package app; public class Main { public static void main(String[] a) { System.out.println(util.Greeting.message()); } }");
        Files.writeString(
                javaSource.resolve("util/Greeting.java"),
                "package util; public class Greeting { public static String message() { return \"Hello BAT\"; } }");
        var build = graph(javaSource, "java", WorkflowApp.Kind.JAVA_BUILD, root.resolve("JAR结果"));
        build.nodes.get(0).settings.put("recursive", true);
        build.nodes.get(1).settings.put("mainClass", "app.Main");
        var built = execute(build, root, "build");
        Path jar = built.files().get(0).destination();
        check(jar.getFileName().toString().equals("project.jar"), "JAR name");
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        check(
                ProcessCommands.run(List.of(javaExe, "-jar", jar.toString()), root, 30)
                        .strip()
                        .equals("Hello BAT"),
                "compiled JAR really runs");
        check(Files.exists(javaSource.resolve("app/Main.java")), "source preserved");
        checks.add("BAT编译2个包内Java文件并打包，实际运行JAR输出Hello BAT。");
        Path bad = root.resolve("坏源码");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("Main.java"), "public class Main { broken syntax }");
        var badFlow = graph(bad, "java", WorkflowApp.Kind.JAVA_BUILD, root.resolve("坏源码输出"));
        boolean rejected = false;
        try {
            execute(badFlow, root, "bad-build");
        } catch (IOException ex) {
            rejected = true;
        }
        check(
                rejected
                        && !Files.exists(root.resolve("坏源码输出"))
                        && Files.exists(bad.resolve("Main.java")),
                "compiler failure doesn't publish");
        checks.add("BAT语法错误拒绝发布，源文件保留。");
        String ffmpeg = FileTransferRunner.toolPath("tools/ffmpeg/ffmpeg.exe").toString();
        String ffprobe = FileTransferRunner.toolPath("tools/ffmpeg/ffprobe.exe").toString();
        Path media = root.resolve("视频输入");
        Files.createDirectories(media);
        for (String name : List.of("2.mp4", "10.mp4"))
            ProcessCommands.run(
                    List.of(
                            ffmpeg,
                            "-nostdin",
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-n",
                            "-f",
                            "lavfi",
                            "-i",
                            "color=c=blue:s=160x96:r=10:d=1",
                            "-f",
                            "lavfi",
                            "-i",
                            "sine=frequency=440:duration=1",
                            "-c:v",
                            "mpeg4",
                            "-c:a",
                            "aac",
                            "-shortest",
                            media.resolve(name).toString()),
                    root,
                    60);
        var audio = graph(media, "mp4", WorkflowApp.Kind.AUDIO, root.resolve("MP3结果"));
        var out = audio.nodes.get(2);
        audio.edges.removeIf(e -> e.to().equals(out.id));
        var rename = audio.add(WorkflowApp.Kind.RENAME, 500, 300);
        audio.connect(audio.nodes.get(1), rename);
        audio.connect(rename, out);
        var extracted = execute(audio, root, "audio-rename");
        check(
                extracted.files().size() == 2
                        && Files.exists(root.resolve("MP3结果/1.mp3"))
                        && Files.exists(root.resolve("MP3结果/2.mp3")),
                "audio plus rename");
        for (var file : extracted.files()) {
            String metadata =
                    ProcessCommands.run(
                            List.of(
                                    ffprobe,
                                    "-v",
                                    "error",
                                    "-show_streams",
                                    "-of",
                                    "json",
                                    file.destination().toString()),
                            root,
                            30);
            check(metadata.contains("mp3"), "valid MP3 codec");
        }
        checks.add("EXE提取2个MP4音轨，再接改名元素，生成有效1.mp3、2.mp3。");
        Path silent = root.resolve("无音轨输入");
        Files.createDirectories(silent);
        ProcessCommands.run(
                List.of(
                        ffmpeg,
                        "-nostdin",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-n",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=s=160x96:r=10:d=1",
                        "-c:v",
                        "mpeg4",
                        silent.resolve("silent.mp4").toString()),
                root,
                60);
        rejected = false;
        try {
            execute(
                    graph(silent, "mp4", WorkflowApp.Kind.AUDIO, root.resolve("无音轨输出")),
                    root,
                    "silent");
        } catch (IOException ex) {
            rejected = true;
        }
        check(rejected && !Files.exists(root.resolve("无音轨输出")), "no audio rejected");
        checks.add("EXE无音轨视频拒绝发布。");
        Path sheets = root.resolve("表格输入");
        Files.createDirectories(sheets);
        Path fixtureScript = root.resolve("excel-fixtures.py");
        String vendor = FileTransferRunner.toolPath("tools/python-vendor").toString();
        String fixture =
                "import sys,json\nfrom pathlib import Path\nsys.path.insert(0,json.loads("
                        + Json.stringify(Json.stringify(vendor))
                        + "))\nfrom openpyxl import Workbook,load_workbook\nfrom openpyxl.cell import WriteOnlyCell\np=Path(sys.argv[1]);p.mkdir(exist_ok=True)\nfor name,rows in [('2.xlsx',[['姓名','金额'],['张三',10]]),('10.xlsx',[['金额','姓名','备注'],[20,'李四','正常']])]:\n w=Workbook();s=w.active\n for r in rows:s.append(r)\n w.save(p/name)\n";
        Files.writeString(fixtureScript, fixture);
        ProcessCommands.run(
                List.of(ToolLauncher.python(), fixtureScript.toString(), sheets.toString()),
                root,
                30);
        var excel = graph(sheets, "xlsx", WorkflowApp.Kind.EXCEL_MERGE, root.resolve("Excel结果"));
        var merged = execute(excel, root, "excel");
        Path xlsx = merged.files().get(0).destination();
        Path checkScript = root.resolve("check-excel.py");
        Files.writeString(
                checkScript,
                "import sys,json\nsys.path.insert(0,json.loads("
                        + Json.stringify(Json.stringify(vendor))
                        + "))\nfrom openpyxl import load_workbook\nw=load_workbook(sys.argv[1],read_only=True);r=list(w.worksheets[0].values)\nassert r[0]==('姓名','金额','备注','来源文件'),r\nassert r[1]==('张三',10,None,'2.xlsx'),r\nassert r[2]==('李四',20,'正常','10.xlsx'),r\nw.close();print('PASS actual XLSX values')\n");
        ProcessCommands.run(
                List.of(ToolLauncher.python(), checkScript.toString(), xlsx.toString()), root, 30);
        checks.add("PY合并错序表头，按1/2/10自然排序对齐列并增加来源列，实际读取XLSX值通过。");
        var excelOut = excel.nodes.get(2);
        excel.edges.removeIf(e -> e.to().equals(excelOut.id));
        var pdf = excel.add(WorkflowApp.Kind.PDF, 500, 300);
        excel.connect(excel.nodes.get(1), pdf);
        excel.connect(pdf, excelOut);
        excelOut.settings.put("directory", root.resolve("Excel到PDF").toString());
        var pdfResult = execute(excel, root, "excel-pdf");
        try (var document = Loader.loadPDF(pdfResult.files().get(0).destination().toFile())) {
            String text = new PDFTextStripper().getText(document);
            check(
                    document.getNumberOfPages() > 0 && text.contains("张三") && text.contains("李四"),
                    "merged XLSX to PDF");
        }
        checks.add("PY合并→现有PDF→文件输出组合通过，PDF中文内容正确。");
        Path formula = root.resolve("公式输入");
        Files.createDirectories(formula);
        Path formulaScript = root.resolve("formula-fixture.py");
        Files.writeString(
                formulaScript,
                "import sys,json\nsys.path.insert(0,json.loads("
                        + Json.stringify(Json.stringify(vendor))
                        + "))\nfrom openpyxl import Workbook\nw=Workbook();s=w.active;s.append(['金额']);s.append(['=1+1']);w.save(sys.argv[1])\n");
        ProcessCommands.run(
                List.of(
                        ToolLauncher.python(),
                        formulaScript.toString(),
                        formula.resolve("formula.xlsx").toString()),
                root,
                30);
        rejected = false;
        try {
            execute(
                    graph(formula, "xlsx", WorkflowApp.Kind.EXCEL_MERGE, root.resolve("公式输出")),
                    root,
                    "formula");
        } catch (IOException ex) {
            rejected = true;
        }
        check(rejected && !Files.exists(root.resolve("公式输出")), "uncached formula rejected");
        checks.add("PY缺少公式缓存值明确报错，不静默丢失数据。");
        SwingUtilities.invokeAndWait(
                () -> {
                    var editor = new WorkflowApp.Editor();
                    for (var kind :
                            List.of(
                                    WorkflowApp.Kind.JAVA_BUILD,
                                    WorkflowApp.Kind.AUDIO,
                                    WorkflowApp.Kind.EXCEL_MERGE)) {
                        editor.batchDemo(kind, false);
                        editor.select(editor.flow.nodes.get(1));
                        check(editor.selected.settings.size() > 0, "real form settings");
                        check(editor.flow.edges.size() == 2, "executable example");
                    }
                    ((JTextField) editor.batchPanel.excelControls.get("sourceColumn"))
                            .setText("文件来源");
                    check(
                            editor.selected.settings.get("sourceColumn").equals("文件来源"),
                            "form updates model");
                });
        checks.add("三种元素实际配置页、示例、流程保存读取与组合连线通过。");
        Json.write(root.resolve("summary.json"), Map.of("status", "PASS", "checks", checks));
        Files.write(root.resolve("测试结果.txt"), checks);
        System.out.println(
                "PASS executable tools: "
                        + checks.size()
                        + " checks; "
                        + root.resolve("summary.json"));
    }
}

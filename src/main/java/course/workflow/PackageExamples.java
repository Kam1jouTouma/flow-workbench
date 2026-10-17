package course.workflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 在安装位置已确定后创建示例流程，并把样本与脚本模板补充到独立数据目录。
 *
 * <p>Initializes examples after installation paths are known, keeping editable samples in a data
 * folder.
 */
final class PackageExamples {
    /** 仅补充缺失文件，以保留用户对样本和模板的修改。 Copies missing files without replacing edits. */
    static void copyNewTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var stream = Files.walk(from)) {
            for (Path p : stream.toList()) {
                Path dest = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else if (!Files.exists(dest)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }

    static WorkflowApp.Node in(WorkflowApp.Flow f, Path path, boolean batch, String formats) {
        var n = f.add(WorkflowApp.Kind.FILE, 40, 100);
        n.settings.put("sourcePath", path.toAbsolutePath().toString());
        n.settings.put("selectionMode", batch ? "TYPES" : "SINGLE");
        n.settings.put("extensions", formats);
        return n;
    }

    static WorkflowApp.Node out(WorkflowApp.Flow f, WorkflowApp.Node from, Path folder) {
        var n = f.add(WorkflowApp.Kind.SAVE, 660, 100);
        n.settings.put("directory", folder.toAbsolutePath().toString());
        n.settings.put("conflictPolicy", "RENAME");
        f.connect(from, n);
        return n;
    }

    static void save(WorkflowApp.Flow flow, Path library, String name) throws IOException {
        Path file = library.resolve(name + ".flow.xml");
        if (!Files.exists(file)) flow.save(file);
    }

    /**
     * 生成指向本机样本的绝对路径；重复初始化保留既有流程、文件和已设置的目录偏好。
     *
     * <p>Builds examples with local absolute paths while preserving existing files and location
     * settings.
     */
    static void initialize(Path app, Path data) throws IOException {
        app = app.toAbsolutePath().normalize();
        data = data.toAbsolutePath().normalize();
        Files.createDirectories(data);
        Path samples = data.resolve("samples"),
                library = data.resolve("flow-library"),
                outputs = data.resolve("outputs"),
                scripts = data.resolve("user-scripts");
        Files.createDirectories(library);
        Files.createDirectories(outputs);
        Files.createDirectories(scripts);
        copyNewTree(app.resolve("samples"), samples);
        copyNewTree(app.resolve("script-templates"), scripts);
        Properties settings = new Properties();
        Path settingsFile = app.resolve("user-settings.properties");
        if (Files.isRegularFile(settingsFile))
            try (InputStream i = Files.newInputStream(settingsFile)) {
                settings.load(i);
            }
        if (!settings.containsKey("workflow.library"))
            settings.setProperty("workflow.library", library.toString());
        if (!settings.containsKey("execution.runs"))
            settings.setProperty("execution.runs", data.resolve("run-history").toString());
        try (OutputStream o = Files.newOutputStream(settingsFile)) {
            settings.store(o, "Friend test package locations");
        }
        var f = new WorkflowApp.Flow();
        f.title = "01 单文件复制";
        out(f, in(f, samples.resolve("texts/1.txt"), false, "txt"), outputs.resolve("01-copy"));
        save(f, library, "01 单文件复制");
        f = new WorkflowApp.Flow();
        f.title = "02 指定类型批量复制";
        out(
                f,
                in(f, samples.resolve("mixed"), true, "txt,csv,png,pdf,xlsx"),
                outputs.resolve("02-types"));
        save(f, library, "02 指定类型批量复制");
        f = new WorkflowApp.Flow();
        f.title = "03 文本图片逐文件PDF";
        var a = in(f, samples.resolve("mixed"), true, "txt,png,pdf");
        var pdf = f.add(WorkflowApp.Kind.PDF, 340, 100);
        f.connect(a, pdf);
        out(f, pdf, outputs.resolve("03-pdf"));
        save(f, library, "03 文本图片逐文件PDF");
        f = new WorkflowApp.Flow();
        f.title = "04 CSV模板批量改名";
        a = in(f, samples.resolve("texts"), true, "txt");
        var csv = in(f, samples.resolve("rules.csv"), false, "csv");
        csv.y = 330;
        csv.name = "固定CSV规则";
        var rename = f.add(WorkflowApp.Kind.RENAME, 340, 100);
        rename.settings.put("template", "{姓名}_{x}");
        f.connect(a, rename);
        f.connect(csv, rename, "csv");
        out(f, rename, outputs.resolve("04-rename"));
        save(f, library, "04 CSV模板批量改名");
        f = new WorkflowApp.Flow();
        f.title = "05 双输入并行处理双输出";
        a = in(f, samples.resolve("texts/1.txt"), false, "txt");
        var b = in(f, samples.resolve("texts/2.txt"), false, "txt");
        b.y = 330;
        var merge = f.add(WorkflowApp.Kind.FILE_MERGE, 330, 190);
        f.connect(a, "data", merge, "input1", true);
        f.connect(b, "data", merge, "input2", true);
        rename = f.add(WorkflowApp.Kind.RENAME, 620, 70);
        rename.settings.put("template", "归档_{x}");
        pdf = f.add(WorkflowApp.Kind.PDF, 620, 330);
        pdf.settings.put("pdfMode", "MERGE");
        f.connect(merge, rename);
        f.connect(merge, pdf);
        out(f, rename, outputs.resolve("05-renamed")).x = 920;
        var po = out(f, pdf, outputs.resolve("05-merged-pdf"));
        po.x = 920;
        po.y = 330;
        save(f, library, "05 双输入并行处理双输出");
        f = new WorkflowApp.Flow();
        f.title = "06 子图复用与逐级画布";
        a = in(f, samples.resolve("texts"), true, "txt");
        var module = f.add(WorkflowApp.Kind.SUBGRAPH, 330, 100);
        var so = f.add(WorkflowApp.Kind.SAVE, 660, 100);
        so.settings.put("directory", outputs.resolve("06-subgraph").toString());
        so.settings.put("conflictPolicy", "RENAME");
        f.connect(a, "data", module, "input", true);
        f.connect(module, "output", so, "data", true);
        save(f, library, "06 子图复用与逐级画布");
        f = new WorkflowApp.Flow();
        f.title = "07 Excel表格合并";
        a = in(f, samples.resolve("excel"), true, "xlsx");
        var excel = f.add(WorkflowApp.Kind.EXCEL_MERGE, 330, 100);
        f.connect(a, excel);
        out(f, excel, outputs.resolve("07-excel"));
        save(f, library, "07 Excel表格合并");
        f = new WorkflowApp.Flow();
        f.title = "08 Java编译打包";
        a = in(f, samples.resolve("java"), true, "java");
        a.settings.put("recursive", true);
        var build = f.add(WorkflowApp.Kind.JAVA_BUILD, 330, 100);
        f.connect(a, build);
        out(f, build, outputs.resolve("08-java"));
        save(f, library, "08 Java编译打包");
        f = new WorkflowApp.Flow();
        f.title = "09 音频提取可选组件";
        a = in(f, samples.resolve("audio/demo.wav"), false, "wav");
        var audio = f.add(WorkflowApp.Kind.AUDIO, 330, 100);
        f.connect(a, audio);
        out(f, audio, outputs.resolve("09-audio"));
        save(f, library, "09 音频提取可选组件");
        System.out.println("Initialized 9 examples; existing graphs and scripts preserved.");
    }
}

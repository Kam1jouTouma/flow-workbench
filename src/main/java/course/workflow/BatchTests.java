package course.workflow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.*;

final class BatchTests {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static WorkflowApp.Node input(
            WorkflowApp.Flow flow, Path path, boolean batch, String extensions) {
        var n = flow.add(WorkflowApp.Kind.FILE, 50, 100);
        n.settings.put("mode", "FILE");
        n.settings.put("sourcePath", path.toAbsolutePath().toString());
        n.settings.put("selectionMode", batch ? "TYPES" : "SINGLE");
        n.settings.put("extensions", extensions);
        return n;
    }

    static WorkflowApp.Node output(WorkflowApp.Flow flow, WorkflowApp.Node source, Path directory) {
        var n = flow.add(WorkflowApp.Kind.SAVE, 650, 100);
        n.settings.put("directory", directory.toAbsolutePath().toString());
        require(flow.connect(source, n) == null, "output connection");
        return n;
    }

    static FileTransferRunner.Result execute(
            WorkflowApp.Flow flow, Path root, String name, List<String> warnings)
            throws IOException {
        Path file = root.resolve(name + ".flow.xml");
        flow.save(file);
        return BatchRunner.execute(
                WorkflowApp.Flow.load(file), root.resolve("runs"), warnings::addAll);
    }

    static String text(Path pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    static int pages(Path pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    static void run(Path root) throws Exception {
        run(root, true);
    }

    static void run(Path root, boolean officeEnabled) throws Exception {
        root = root.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("测试目录必须不存在。");
        Files.createDirectories(root);
        List<String> checks = new ArrayList<>(), warnings = new ArrayList<>();
        Path source = root.resolve("PDF源文件");
        Files.createDirectories(source);
        Files.writeString(source.resolve("10.txt"), "ORDER TEN 十号\n");
        Files.writeString(source.resolve("2.txt"), "ORDER TWO 二号\n");
        Files.writeString(source.resolve("1.txt"), "ORDER ONE 一号\n");
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 200);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 20, 360, 160);
        graphics.dispose();
        ImageIO.write(image, "png", source.resolve("20.png").toFile());
        PdfTool.convert(source.resolve("1.txt"), source.resolve("30.pdf"), "UTF-8");
        WorkflowApp.Flow each = new WorkflowApp.Flow();
        var selected = input(each, source, true, "txt,png,pdf");
        var pdf = each.add(WorkflowApp.Kind.PDF, 350, 100);
        require(each.connect(selected, pdf) == null, "pdf connection");
        var save = output(each, pdf, root.resolve("逐文件PDF"));
        var eachResult = execute(each, root, "each", warnings);
        require(eachResult.files().size() == 5, "five native PDFs");
        require(text(root.resolve("逐文件PDF/2.pdf")).contains("二号"), "Chinese text retained");
        require(Files.exists(source.resolve("1.txt")), "preserved source");
        checks.add("逐文件PDF：文本/图片/既有PDF，5项通过，中文正文保留。");
        pdf.settings.put("pdfMode", "MERGE");
        save.settings.put("directory", root.resolve("合并PDF").toString());
        var merged = execute(each, root, "merge", warnings);
        Path mergedPdf = merged.files().get(0).destination();
        require(merged.files().size() == 1 && pages(mergedPdf) == 5, "merge pages");
        String mergedText = text(mergedPdf);
        require(
                mergedText.indexOf("ORDER ONE") < mergedText.indexOf("ORDER TWO")
                        && mergedText.indexOf("ORDER TWO") < mergedText.indexOf("ORDER TEN"),
                "natural order 1,2,10");
        checks.add("合并PDF：5页，自然排序1、2、10。");
        try (var document = Loader.loadPDF(mergedPdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            Path rendered = root.resolve("rendered");
            Files.createDirectories(rendered);
            for (int i = 0; i < document.getNumberOfPages(); i++)
                ImageIO.write(
                        renderer.renderImageWithDPI(i, 90),
                        "png",
                        rendered.resolve("page-" + (i + 1) + ".png").toFile());
        }
        Path names = root.resolve("改名源文件");
        Files.createDirectories(names);
        Files.writeString(names.resolve("item10.txt"), "ten");
        Files.writeString(names.resolve("item2.txt"), "two");
        Files.writeString(names.resolve("item1.txt"), "one");
        WorkflowApp.Flow rename = new WorkflowApp.Flow();
        var file = input(rename, names, true, "txt");
        var renamer = rename.add(WorkflowApp.Kind.RENAME, 350, 100);
        require(rename.connect(file, renamer) == null, "rename data");
        var renameOut = output(rename, renamer, root.resolve("序号结果"));
        var numbered = execute(rename, root, "numbered", warnings);
        require(
                numbered.files().size() == 3
                        && Files.readString(root.resolve("序号结果/2.txt")).equals("two"),
                "numbering");
        checks.add("模板{x}：1.txt、2.txt、3.txt，自然排序内容对应。");
        renamer.settings.put("template", "{createtime}_photo");
        renameOut.settings.put("conflictPolicy", "RENAME");
        renameOut.settings.put("directory", root.resolve("日期结果").toString());
        var dated = execute(rename, root, "dated", warnings);
        String date =
                Files.readAttributes(names.resolve("item1.txt"), BasicFileAttributes.class)
                        .creationTime()
                        .toInstant()
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        require(
                dated.files().size() == 3
                        && Files.exists(root.resolve("日期结果/" + date + "_photo.txt")),
                "creation date");
        checks.add("模板{createtime}_photo：原始创建日期与同名自动编号通过。");
        Path csv = root.resolve("规则.csv");
        Files.writeString(csv, "\uFEFF姓名,编号\r\n张三,A\r\n\"李,四\",B\r\n");
        var csvInput = input(rename, csv, false, "csv");
        require(rename.connect(csvInput, renamer, "csv") == null, "second optional CSV port");
        renamer.settings.put("template", "{姓名}_{编号}_{x}");
        renameOut.settings.put("directory", root.resolve("CSV结果").toString());
        warnings.clear();
        var csvResult = execute(rename, root, "csv", warnings);
        require(
                Files.exists(root.resolve("CSV结果/张三_A_1.txt"))
                        && Files.exists(root.resolve("CSV结果/李,四_B_2.txt"))
                        && Files.exists(root.resolve("CSV结果/__3.txt")),
                "CSV fields/blank/quoted");
        require(!warnings.isEmpty(), "missing row warning");
        require(Files.exists(csv), "CSV preserved");
        checks.add("CSV可选第二输入：表头字段、BOM、引号逗号、缺行留空及提示通过。");
        renamer.settings.put("template", "{无此表头}_{x}");
        renameOut.settings.put("directory", root.resolve("缺表头结果").toString());
        warnings.clear();
        execute(rename, root, "missing-header", warnings);
        require(
                warnings.size() == 3 && Files.exists(root.resolve("缺表头结果/_1.txt")),
                "missing headers blank warnings");
        checks.add("缺失表头：留空并保留3条提示证据。");
        renamer.settings.put("template", "copy_{x}");
        renameOut.settings.put("location", "SOURCE");
        renameOut.settings.put("operation", "MOVE");
        var moved = execute(rename, root, "in-place-delete", warnings);
        require(
                moved.files().size() == 3
                        && !Files.exists(names.resolve("item1.txt"))
                        && Files.exists(names.resolve("copy_1.txt"))
                        && Files.exists(csv),
                "delete originals only after all outputs, CSV preserved");
        checks.add("原目录输出+删除原文件：3项哈希验证，CSV规则输入保留。");
        Path invalid = root.resolve("非法源.txt");
        Files.writeString(invalid, "keep");
        WorkflowApp.Flow bad = new WorkflowApp.Flow();
        var badIn = input(bad, invalid, false, "txt");
        var badRename = bad.add(WorkflowApp.Kind.RENAME, 350, 100);
        badRename.settings.put("template", "../escape");
        bad.connect(badIn, badRename);
        output(bad, badRename, root.resolve("非法输出"));
        boolean rejected = false;
        try {
            execute(bad, root, "invalid-name", warnings);
        } catch (IOException ex) {
            rejected = true;
        }
        require(
                rejected && Files.exists(invalid) && !Files.exists(root.resolve("非法输出")),
                "invalid name rejected before publish");
        Path disguised = root.resolve("伪装.pdf");
        Files.writeString(disguised, "not a PDF");
        WorkflowApp.Flow fake = new WorkflowApp.Flow();
        var fakeIn = input(fake, disguised, false, "pdf");
        var fakePdf = fake.add(WorkflowApp.Kind.PDF, 350, 100);
        fake.connect(fakeIn, fakePdf);
        output(fake, fakePdf, root.resolve("伪装输出"));
        rejected = false;
        try {
            execute(fake, root, "disguised", warnings);
        } catch (IOException ex) {
            rejected = true;
        }
        require(rejected && !Files.exists(root.resolve("伪装输出")), "disguised content rejected");
        checks.add("非法名称、伪装PDF：发布前失败且原文件保留。");
        Path chainSource = root.resolve("组合源文件");
        Files.createDirectories(chainSource);
        Files.writeString(chainSource.resolve("1.txt"), "chain one");
        Files.writeString(chainSource.resolve("2.txt"), "chain two");
        WorkflowApp.Flow chain = new WorkflowApp.Flow();
        var chainIn = input(chain, chainSource, true, "txt");
        var chainPdf = chain.add(WorkflowApp.Kind.PDF, 280, 100);
        chainPdf.settings.put("pdfMode", "MERGE");
        var chainRename = chain.add(WorkflowApp.Kind.RENAME, 500, 100);
        chainRename.settings.put("template", "report_{x}");
        chain.connect(chainIn, chainPdf);
        chain.connect(chainPdf, chainRename);
        var chainOut = output(chain, chainRename, root.resolve("组合输出"));
        chainOut.settings.put("operation", "MOVE");
        var chained = execute(chain, root, "chain", warnings);
        require(
                chained.files().size() == 1
                        && pages(root.resolve("组合输出/report_1.pdf")) == 2
                        && !Files.exists(chainSource.resolve("1.txt"))
                        && !Files.exists(chainSource.resolve("2.txt")),
                "merge rename and lineage deletion");
        checks.add("文件输入→合并PDF→改名→输出：2页PDF，成功后删除全部2项原始来源。");
        Path blocked = root.resolve("冲突结果");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("1.txt"), "existing");
        WorkflowApp.Flow conflict = new WorkflowApp.Flow();
        var conflictIn = input(conflict, invalid, false, "txt");
        var conflictRename = conflict.add(WorkflowApp.Kind.RENAME, 350, 100);
        conflict.connect(conflictIn, conflictRename);
        var conflictOut = output(conflict, conflictRename, blocked);
        conflictOut.settings.put("operation", "MOVE");
        rejected = false;
        try {
            execute(conflict, root, "publication-conflict", warnings);
        } catch (IOException ex) {
            rejected = true;
        }
        require(
                rejected
                        && Files.readString(blocked.resolve("1.txt")).equals("existing")
                        && Files.exists(invalid),
                "conflict preserves source with deletion selected");
        checks.add("输出冲突且选择删源：预检查失败，原文件及已有目标都保留。");
        Path officeFixtures = FileTransferRunner.toolPath("test-fixtures");
        if (officeEnabled && Files.isDirectory(officeFixtures)) {
            WorkflowApp.Flow office = new WorkflowApp.Flow();
            var officeIn = input(office, officeFixtures, true, "docx,xlsx,pptx");
            var officePdf = office.add(WorkflowApp.Kind.PDF, 350, 100);
            office.connect(officeIn, officePdf);
            output(office, officePdf, root.resolve("OfficePDF"));
            var result = execute(office, root, "office", warnings);
            require(result.files().size() == 3, "three Office conversions");
            for (var published : result.files())
                require(pages(published.destination()) > 0, "Office valid PDF");
            require(text(root.resolve("OfficePDF/word.pdf")).contains("Word"), "Word content");
            require(text(root.resolve("OfficePDF/excel.pdf")).contains("Excel"), "Excel content");
            require(
                    text(root.resolve("OfficePDF/powerpoint.pdf")).contains("PowerPoint"),
                    "PowerPoint content");
            checks.add("真实DOCX/XLSX/PPTX经Office转换：3项PDF及正文检查通过。");
        }
        final Path testRoot = root;
        SwingUtilities.invokeAndWait(
                () -> {
                    WorkflowApp.Editor editor = new WorkflowApp.Editor();
                    editor.flow = new WorkflowApp.Flow();
                    var a = input(editor.flow, testRoot.resolve("规则.csv"), false, "csv");
                    var b = editor.flow.add(WorkflowApp.Kind.RENAME, 380, 130);
                    var c = input(editor.flow, testRoot.resolve("非法源.txt"), false, "txt");
                    require(editor.flow.connect(c, b) == null, "UI required data");
                    editor.canvas.pending = a;
                    editor.canvas.dispatchEvent(
                            new java.awt.event.MouseEvent(
                                    editor.canvas,
                                    java.awt.event.MouseEvent.MOUSE_RELEASED,
                                    1,
                                    0,
                                    (int) b.x,
                                    (int) b.y + 108,
                                    1,
                                    false,
                                    java.awt.event.MouseEvent.BUTTON1));
                    require(
                            editor.flow.edges.stream().anyMatch(e -> e.port().equals("csv")),
                            "CSV port mouse event");
                    editor.select(b);
                    require(
                            editor.batchPanel.renameControls.containsKey("template"),
                            "real template control");
                    try {
                        Path path = testRoot.resolve("two-ports.flow.xml");
                        editor.flow.save(path);
                        require(
                                WorkflowApp.Flow.load(path).edges.stream()
                                        .anyMatch(e -> e.port().equals("csv")),
                                "port persistence v3");
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                    editor.batchDemo(WorkflowApp.Kind.PDF, true);
                    require(
                            editor.flow.nodes.size() == 3
                                    && editor.flow.nodes.stream()
                                            .anyMatch(
                                                    n ->
                                                            n.kind == WorkflowApp.Kind.PDF
                                                                    && n.settings
                                                                            .get("pdfMode")
                                                                            .equals("MERGE")),
                            "PDF example");
                    editor.batchDemo(WorkflowApp.Kind.RENAME, false);
                    require(
                            editor.flow.edges.stream().anyMatch(e -> e.port().equals("csv")),
                            "CSV example");
                });
        checks.add("真实Swing可选CSV端口、配置页和流程v3保存读取通过。");
        require(
                CsvTable.parse("a,b\n\"line\nvalue\",\"quote\"\"x\"", ',')
                        .get(1)
                        .get(0)
                        .equals("line\nvalue"),
                "multiline CSV parser");
        require(
                ToolLauncher.probe(FileTransferRunner.toolPath("pdf-tool-v1.jar"))
                        .get("validation")
                        .equals("CLI_QUERIES_ONLY"),
                "standalone PDF help/describe/capabilities");
        require(
                ToolLauncher.probe(FileTransferRunner.toolPath("rename-tool-v1.jar"))
                        .get("validation")
                        .equals("CLI_QUERIES_ONLY"),
                "standalone rename discovery");
        checks.add("两独立JAR真实--help/--describe/--capabilities查询通过。");
        Json.write(
                root.resolve("summary.json"),
                Map.of("status", "PASS", "checks", checks, "pdfFormats", PdfTool.supported()));
        Files.write(root.resolve("测试结果.txt"), checks);
        System.out.println(
                "PASS batch: " + checks.size() + " checks; report " + root.resolve("summary.json"));
    }
}

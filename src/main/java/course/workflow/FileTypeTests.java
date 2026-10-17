package course.workflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Tests declaration/discovery and bounded identification, not office document rendering. */
final class FileTypeTests {
    private FileTypeTests() {}

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void office(Path file, String part, String mime) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(
                    ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/"
                                    + part
                                    + "\" ContentType=\""
                                    + mime
                                    + "\"/></Types>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(part));
            zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    static void run(Path requested) throws IOException {
        Path root = requested.toAbsolutePath();
        if (Files.exists(root)) throw new IOException("请选择不存在的测试目录。");
        Files.createDirectories(root);
        Path text = root.resolve("文本.txt"),
                pdf = root.resolve("样例.pdf"),
                fake = root.resolve("伪装.pdf"),
                json = root.resolve("结构.json");
        Files.writeString(text, "中文文本\n");
        Files.writeString(pdf, "%PDF-1.7\n% header fixture, not a fully validated document\n");
        Files.writeString(fake, "这不是PDF，只是文本");
        Files.writeString(json, "{\"name\":\"张三\"}");
        Path docx = root.resolve("样例.DOCX"),
                xlsx = root.resolve("样例.xlsx"),
                pptx = root.resolve("样例.pptx");
        office(
                docx,
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        office(
                xlsx,
                "xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
        office(
                pptx,
                "ppt/presentation.xml",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml");
        Path renamed = root.resolve("Excel伪装Word.docx");
        Files.copy(xlsx, renamed);
        Path plainZip = root.resolve("普通压缩包伪装.docx");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(plainZip))) {
            z.putNextEntry(new ZipEntry("note.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path ole = root.resolve("旧版.doc");
        Files.write(
                ole,
                new byte[] {
                    (byte) 0xD0,
                    (byte) 0xCF,
                    0x11,
                    (byte) 0xE0,
                    (byte) 0xA1,
                    (byte) 0xB1,
                    0x1A,
                    (byte) 0xE1
                });
        Path unknown = root.resolve("未知.bin");
        Files.write(unknown, new byte[] {0, 0, (byte) 255, 1});
        Path brokenJson = root.resolve("非法.json");
        Files.writeString(brokenJson, "{broken");
        Path brokenUtf8 = root.resolve("非法编码.txt");
        Files.write(brokenUtf8, new byte[] {'a', (byte) 255});
        List<Path> samples =
                List.of(
                        text,
                        pdf,
                        fake,
                        json,
                        docx,
                        xlsx,
                        pptx,
                        renamed,
                        plainZip,
                        ole,
                        unknown,
                        brokenJson,
                        brokenUtf8);
        List<Object> detections = new ArrayList<>();
        for (Path p : samples)
            detections.add(
                    Map.of("path", p.toString(), "detection", FileTypeDetector.detect(p).json()));
        require(FileTypeDetector.detect(text).state().equals("IDENTIFIED"), "UTF8 text");
        require(FileTypeDetector.detect(pdf).format().equals("pdf"), "PDF signature");
        require(
                FileTypeDetector.detect(fake).state().equals("MISMATCH"),
                "renamed text rejected as PDF");
        require(FileTypeDetector.detect(json).format().equals("json"), "JSON structure");
        require(
                FileTypeDetector.detect(docx).format().equals("docx")
                        && FileTypeDetector.detect(docx).state().equals("IDENTIFIED"),
                "Office package content type, uppercase suffix");
        require(
                FileTypeDetector.detect(xlsx).format().equals("xlsx")
                        && FileTypeDetector.detect(pptx).format().equals("pptx"),
                "Excel and PowerPoint packages");
        require(
                FileTypeDetector.detect(renamed).format().equals("xlsx")
                        && FileTypeDetector.detect(renamed).state().equals("MISMATCH"),
                "Excel disguised as Word");
        require(
                FileTypeDetector.detect(plainZip).format().equals("zip")
                        && FileTypeDetector.detect(plainZip).state().equals("MISMATCH"),
                "generic zip is not docx");
        require(
                FileTypeDetector.detect(ole).state().equals("UNRESOLVED"),
                "OLE not falsely inferred as Word");
        require(FileTypeDetector.detect(unknown).state().equals("UNRESOLVED"), "unknown binary");
        require(
                !FileTypeDetector.detect(brokenJson).state().equals("IDENTIFIED")
                        && !FileTypeDetector.detect(brokenUtf8).state().equals("IDENTIFIED"),
                "bad JSON and UTF8");
        ElementCapabilities.Contract strictPdf =
                new ElementCapabilities.Contract("READ", Set.of("pdf"), true, "SINGLE", true);
        ElementCapabilities.Contract preserve =
                new ElementCapabilities.Contract(
                        "PRESERVE", Set.of("*"), false, "COLLECTION", true);
        require(
                ElementCapabilities.rejection(strictPdf, FileTypeDetector.detect(fake)) != null
                        && ElementCapabilities.rejection(strictPdf, FileTypeDetector.detect(pdf))
                                == null,
                "strict declared formats");
        require(
                ElementCapabilities.rejection(preserve, FileTypeDetector.detect(fake)) == null
                        && ElementCapabilities.rejection(preserve, FileTypeDetector.detect(unknown))
                                == null,
                "opaque copy preserves unrecognized bytes");
        WorkflowApp.Flow f = new WorkflowApp.Flow();
        WorkflowApp.Node in = f.add(WorkflowApp.Kind.FILE, 50, 100),
                read = f.add(WorkflowApp.Kind.READ, 300, 100);
        in.settings.put("mode", "FILE");
        in.settings.put("sourcePath", docx.toString());
        require(f.connect(in, read) != null, "connection refuses unsupported format");
        in.settings.put("sourcePath", text.toString());
        require(f.connect(in, read) == null, "declared compatible text connection");
        in.settings.put("selectionMode", "TYPES");
        require(
                ElementCapabilities.connectionError(in, read) != null,
                "collection not silently used as single");
        require(
                !ElementCapabilities.inputContract(read).implemented(),
                "planned reader never marked implemented");
        Map<String, Object> input =
                ElementCapabilities.discover(FileTransferRunner.toolPath("file-input-tool-v1.jar"));
        Map<String, Object> output =
                ElementCapabilities.discover(
                        FileTransferRunner.toolPath("file-output-tool-v1.jar"));
        require(
                input.get("toolId").equals("io.file_select")
                        && output.get("toolId").equals("io.file_export"),
                "actual CLI discovery identities");
        require(
                ElementCapabilities.discoveredInput(output).formats().equals(Set.of("*")),
                "use discovered output contract");
        Map<String, Object> invalid =
                Map.of(
                        "name",
                        "data",
                        "dataTypes",
                        List.of("FILE"),
                        "formats",
                        List.of("*", "pdf"),
                        "cardinality",
                        "COLLECTION",
                        "validation",
                        "OPAQUE");
        boolean rejected = false;
        try {
            ElementCapabilities.validatePort(invalid);
        } catch (IOException ex) {
            rejected = true;
        }
        require(rejected, "reject contradictory wildcard declaration");
        Json.write(
                root.resolve("detections.json"),
                Map.of(
                        "status",
                        "PASS",
                        "files",
                        detections,
                        "discovered",
                        List.of(input, output),
                        "fullyValidated",
                        false));
        Files.writeString(
                root.resolve("测试结果.txt"),
                "类型声明、发现、检测和兼容性测试通过。\n13个样例覆盖文本、PDF头、JSON、Word/Excel/PowerPoint包、改名伪装、普通ZIP、OLE待确认、未知二进制和非法编码。\n具体元素拒绝不支持或未确定类型，原样复制允许保留未识别文件。\n真实调用两个独立工具--capabilities，发现声明并应用输出限制。\n明确数量约束，读取正文处理器仍标为未实现。\n识别只验证头或有限结构，不声明文件可完整打开或具体内容可解析。\n详细结果：detections.json。\n");
        System.out.println(
                "PASS: 13 format fixtures; disguised/unknown formats, declared restrictions, collection limits, actual CLI discovery and planned implementation status.");
        System.out.println("Report: " + root.resolve("detections.json"));
    }
}

package course.workflow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * 独立 PDF 工具：按文件生成或按自然顺序合并，输出工件携带原始文件血缘。
 *
 * <p>Converts or merges staged files, preserving lineage for the host's final output policy.
 */
public final class PdfTool {
    static final Set<String> NATIVE = PdfFormats.NATIVE;
    static final Set<String> WORD = PdfFormats.WORD;
    static final Set<String> EXCEL = PdfFormats.EXCEL;
    static final Set<String> POWERPOINT = PdfFormats.POWERPOINT;

    private PdfTool() {}

    public static void main(String[] args) {
        FileCli.main(args, "batch.pdf", PdfTool::execute);
    }

    static Path officeProgram(String name) {
        return PdfFormats.officeProgram(name);
    }

    /** 内置格式固定，办公格式随本机可用转换器声明。 / Advertise locally available converters. */
    static Set<String> supported() {
        return PdfFormats.supported();
    }

    static Path libreOffice() {
        return PdfFormats.libreOffice();
    }

    /** EACH 保留逐项来源，MERGE 汇总全部原件到一个结果。 / Preserve lineage for both PDF modes. */
    static void execute(Map<String, Object> input, Map<String, Object> parameters, Path output)
            throws IOException {
        System.setProperty("java.io.tmpdir", output.toAbsolutePath().toString());
        if (!Set.of("pdfMode", "encoding").containsAll(parameters.keySet()))
            throw new IOException("未知PDF配置。");
        String mode = (String) parameters.getOrDefault("pdfMode", "EACH");
        if (!Set.of("EACH", "MERGE").contains(mode)) throw new IOException("PDF模式无效。");
        String encoding = (String) parameters.getOrDefault("encoding", "UTF-8");
        List<BatchFiles.File> files = BatchFiles.read(input);
        List<Path> converted = new ArrayList<>();
        List<Object> items = new ArrayList<>(), sources = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            BatchFiles.File file = files.get(i);
            String relative = (String) file.source().get("relativePath");
            int dot = relative.lastIndexOf('.');
            if (dot > relative.lastIndexOf('/')) relative = relative.substring(0, dot);
            relative += ".pdf";
            Path target =
                    output.resolve(
                            mode.equals("EACH")
                                    ? "payloads/" + i + "/" + relative
                                    : "converted/" + i + ".pdf");
            Files.createDirectories(target.getParent());
            convert(file.path(), target, encoding);
            verify(target);
            converted.add(target);
            if (mode.equals("EACH")) {
                Map<String, Object> source = new LinkedHashMap<>(file.source());
                source.put("relativePath", relative);
                source.put("sha256", FileCli.sha256(target));
                source.put("originals", BatchFiles.originals(file));
                items.add(FileCli.item("payloads/" + i + "/" + relative));
                sources.add(source);
            }
        }
        if (mode.equals("MERGE")) {
            Path target = output.resolve("payloads/合并结果.pdf");
            Files.createDirectories(target.getParent());
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationFileName(target.toString());
            for (Path path : converted) merger.addSource(path.toFile());
            merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
            verify(target);
            Map<String, Object> source = new LinkedHashMap<>(files.get(0).source());
            source.put("relativePath", "合并结果.pdf");
            source.put("sha256", FileCli.sha256(target));
            List<Object> originals = new ArrayList<>();
            for (var f : files) originals.addAll(BatchFiles.originals(f));
            source.put("originals", originals);
            items.add(FileCli.item("payloads/合并结果.pdf"));
            sources.add(source);
        }
        BatchFiles.write(input, output, items, sources, List.of());
    }

    static void verify(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            if (doc.isEncrypted() || doc.getNumberOfPages() == 0)
                throw new IOException("PDF加密或没有可输出页面。");
        }
    }

    /** 根据格式分派转换；先核对内容线索，再让对应解析器处理。 / Dispatch after format checks. */
    static void convert(Path source, Path target, String encoding) throws IOException {
        String extension = FileCli.extension(source);
        if (!supported().contains(extension))
            throw new IOException("当前转换器不支持." + extension + "，请检查Office/LibreOffice依赖。");
        FileTypeDetector.Detection detected = FileTypeDetector.detect(source);
        boolean legacy =
                Set.of("doc", "dot", "xls", "xlt", "ppt", "pps").contains(extension)
                        && detected.format().equals("ole")
                        && detected.state().equals("UNRESOLVED");
        boolean encodedText =
                Set.of("txt", "md", "csv", "tsv").contains(extension)
                        && !encoding.startsWith("UTF-8");
        if (!legacy && !encodedText && !detected.state().equals("IDENTIFIED"))
            throw new IOException("类型不匹配或未确定：" + source.getFileName() + " / " + detected.message());
        if (extension.equals("pdf")) {
            verify(source);
            Files.copy(source, target);
            return;
        }
        if (Set.of("png", "jpg", "jpeg", "bmp").contains(extension)) {
            var image = ImageIO.read(source.toFile());
            if (image == null) throw new IOException("无法解码图片。");
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                float margin = 30,
                        scale =
                                Math.min(
                                        (page.getMediaBox().getWidth() - margin * 2)
                                                / image.getWidth(),
                                        (page.getMediaBox().getHeight() - margin * 2)
                                                / image.getHeight());
                float w = image.getWidth() * scale, h = image.getHeight() * scale;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.drawImage(
                            LosslessFactory.createFromImage(doc, image),
                            (page.getMediaBox().getWidth() - w) / 2,
                            (page.getMediaBox().getHeight() - h) / 2,
                            w,
                            h);
                }
                doc.save(target.toFile());
            }
            return;
        }
        if (Set.of("txt", "md", "csv", "tsv", "json").contains(extension)) {
            text(source, target, encoding);
            return;
        }
        office(source, target, extension);
    }

    static void text(Path source, Path target, String encoding) throws IOException {
        if (Files.size(source) > 16 * 1024 * 1024) throw new IOException("文本超过16MiB限制。");
        String text =
                Charset.forName(encoding.equals("UTF-8-SIG") ? "UTF-8" : encoding)
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(source)))
                        .toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try (PDDocument doc = new PDDocument()) {
            Path bundledFont = ToolPaths.resolve("fonts/NotoSansSC.ttf");
            Path chineseFont =
                    Files.isRegularFile(bundledFont)
                            ? bundledFont
                            : Path.of(
                                    System.getenv().getOrDefault("WINDIR", "C:/Windows"),
                                    "Fonts",
                                    "simhei.ttf");
            PDFont font =
                    Files.isRegularFile(chineseFont)
                            ? PDType0Font.load(doc, chineseFont.toFile())
                            : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<String> lines = new ArrayList<>();
            for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (int cp : raw.replace("\t", "    ").codePoints().toArray()) {
                    if (cp < 32) continue;
                    String next = new String(Character.toChars(cp));
                    float width = font.getStringWidth(line.toString() + next) * 10 / 1000;
                    if (width > PDRectangle.A4.getWidth() - 80 && line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(next);
                }
                lines.add(line.toString());
            }
            for (int start = 0; start < lines.size(); start += 51) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(font, 10);
                    stream.setLeading(14);
                    stream.newLineAtOffset(40, page.getMediaBox().getHeight() - 48);
                    for (int j = start; j < Math.min(start + 51, lines.size()); j++) {
                        stream.showText(lines.get(j));
                        stream.newLine();
                    }
                    stream.endText();
                }
            }
            doc.save(target.toFile());
        } catch (IllegalArgumentException ex) {
            throw new IOException("字体不支持文本中的字符：" + ex.getMessage(), ex);
        }
    }

    /** 办公转换委托本机 Office 或 LibreOffice，主程序不自行解析办公排版。 / Use local converters. */
    static void office(Path source, Path target, String extension) throws IOException {
        String family =
                WORD.contains(extension)
                        ? "WORD"
                        : EXCEL.contains(extension)
                                ? "EXCEL"
                                : POWERPOINT.contains(extension) ? "POWERPOINT" : "LIBREOFFICE";
        if (family.equals("LIBREOFFICE")
                || !Files.isRegularFile(
                        officeProgram(
                                family.equals("WORD")
                                        ? "WINWORD"
                                        : family.equals("EXCEL") ? "EXCEL" : "POWERPNT"))) {
            if (!Files.isRegularFile(libreOffice())) throw new IOException("未找到办公转换依赖。");
            Path dir = Files.createTempDirectory(target.getParent(), "lo-");
            List<String> command =
                    List.of(
                            libreOffice().toString(),
                            "-env:UserInstallation=" + dir.resolve("profile").toUri(),
                            "--headless",
                            "--convert-to",
                            "pdf",
                            "--outdir",
                            dir.toString(),
                            source.toString());
            ProcessCommands.run(command, target.getParent(), 180);
            String name = source.getFileName().toString();
            Path produced = dir.resolve(name.substring(0, name.lastIndexOf('.')) + ".pdf");
            if (!Files.isRegularFile(produced)) throw new IOException("LibreOffice未生成PDF。");
            Files.copy(produced, target);
            return;
        }
        Path script = ToolPaths.resolve("tools/office-to-pdf.ps1");
        if (!Files.isRegularFile(script)) throw new IOException("办公转换适配脚本缺失。");
        ProcessCommands.run(
                List.of(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        script.toString(),
                        "-Source",
                        source.toString(),
                        "-Target",
                        target.toString(),
                        "-Family",
                        family),
                target.getParent(),
                180);
        if (!Files.isRegularFile(target)) throw new IOException("Office未生成PDF。");
    }
}

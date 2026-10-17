package course.workflow;

import java.nio.file.*;
import java.util.*;

/**
 * 统一 PDF 输入格式与本机 Office 发现，避免通用协议被迫加载 PDF 引擎或界面。
 *
 * <p>PDF input formats and locally available office converters, without PDFBox or application code.
 */
final class PdfFormats {
    static final Set<String> NATIVE =
            Set.of("pdf", "txt", "md", "csv", "tsv", "json", "png", "jpg", "jpeg", "bmp");
    static final Set<String> WORD = Set.of("doc", "docx", "rtf", "dot", "dotx", "docm");
    static final Set<String> EXCEL = Set.of("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx");
    static final Set<String> POWERPOINT = Set.of("ppt", "pptx", "pptm", "pps", "ppsx");

    private PdfFormats() {}

    static Path officeProgram(String name) {
        String root = System.getenv("ProgramFiles");
        return Path.of(
                root == null ? "C:/Program Files" : root,
                "Microsoft Office",
                "root",
                "Office16",
                name + ".EXE");
    }

    static Set<String> supported() {
        Set<String> formats = new TreeSet<>(NATIVE);
        if (Files.isRegularFile(officeProgram("WINWORD"))) formats.addAll(WORD);
        if (Files.isRegularFile(officeProgram("EXCEL"))) formats.addAll(EXCEL);
        if (Files.isRegularFile(officeProgram("POWERPNT"))) formats.addAll(POWERPOINT);
        if (Files.isRegularFile(libreOffice()))
            formats.addAll(
                    List.of(
                            "doc", "docx", "rtf", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
                            "odp"));
        return formats;
    }

    static Path libreOffice() {
        String root = System.getenv("ProgramFiles");
        return Path.of(
                root == null ? "C:/Program Files" : root, "LibreOffice", "program", "soffice.exe");
    }
}

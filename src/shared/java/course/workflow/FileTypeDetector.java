package course.workflow;

import org.w3c.dom.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 通过文件头和受限读取的容器元信息识别格式；识别成功不代表完整文档校验通过。
 *
 * <p>Identifies formats using bounded content inspection; processing tools must validate documents.
 */
final class FileTypeDetector {
    record Detection(
            String extension,
            String format,
            String family,
            String state,
            String evidence,
            String message) {
        Map<String, Object> json() {
            return Map.of(
                    "extension",
                    extension,
                    "detectedFormat",
                    format,
                    "family",
                    family,
                    "state",
                    state,
                    "evidence",
                    evidence,
                    "message",
                    message,
                    "fullyValidated",
                    false);
        }
    }

    private FileTypeDetector() {}

    static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "tsv", "java");
    static final Map<String, String> OFFICE_MIMES =
            Map.ofEntries(
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                            "docx"),
                    Map.entry("application/vnd.ms-word.document.macroEnabled.main+xml", "docm"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
                            "dotx"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                            "xlsx"),
                    Map.entry("application/vnd.ms-excel.sheet.macroEnabled.main+xml", "xlsm"),
                    Map.entry("application/vnd.ms-excel.sheet.binary.macroEnabled.main", "xlsb"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml",
                            "xltx"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                            "pptx"),
                    Map.entry(
                            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
                            "pptm"),
                    Map.entry(
                            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
                            "ppsx"));

    /** 后缀只是声明，识别结果需与内容证据核对。 / Compare extension claims with content evidence. */
    static Detection detect(Path path) {
        String extension = FileCli.extension(path);
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path))
                return new Detection(
                        extension, "unknown", "unknown", "UNREADABLE", "NONE", "文件不存在或不可读");
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path)) {
                bytes = in.readNBytes(8192);
            }
            String latin = new String(bytes, StandardCharsets.ISO_8859_1);
            if (latin.startsWith("%PDF-")) return identified(extension, "pdf", "pdf", "HEADER");
            if (latin.startsWith("BM")) return identified(extension, "bmp", "image", "HEADER");
            if (latin.startsWith("RIFF")
                    && latin.length() >= 12
                    && latin.substring(8, 12).equals("WAVE"))
                return identified(extension, "wav", "media", "HEADER");
            if (latin.startsWith("RIFF")
                    && latin.length() >= 12
                    && latin.substring(8, 12).equals("AVI "))
                return identified(extension, "avi", "media", "HEADER");
            if (latin.startsWith("fLaC")) return identified(extension, "flac", "media", "HEADER");
            if (latin.startsWith("OggS")) return identified(extension, "ogg", "media", "HEADER");
            if (latin.startsWith("ID3")) return identified(extension, "mp3", "media", "HEADER");
            if (latin.length() >= 12 && latin.substring(4, 8).equals("ftyp"))
                return identified(
                        extension,
                        Set.of("mov", "m4a").contains(extension) ? extension : "mp4",
                        "media",
                        "CONTAINER_HEADER");
            if (starts(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                return identified(extension, "png", "image", "HEADER");
            if (starts(bytes, 0xFF, 0xD8, 0xFF))
                return identified(
                        extension, extension.equals("jpeg") ? "jpeg" : "jpg", "image", "HEADER");
            if (latin.startsWith("RIFF")
                    && latin.length() >= 12
                    && latin.substring(8, 12).equals("WEBP"))
                return identified(extension, "webp", "image", "HEADER");
            if (latin.startsWith("{\\rtf"))
                return identified(extension, "rtf", "text-document", "HEADER");
            if (starts(bytes, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))
                return new Detection(
                        extension,
                        "ole",
                        "compound-office",
                        "UNRESOLVED",
                        "HEADER",
                        "识别为复合文档容器，尚不能确定是 Word、Excel 或 PowerPoint");
            if (starts(bytes, 0x50, 0x4B, 0x03, 0x04) || starts(bytes, 0x50, 0x4B, 0x05, 0x06))
                return zip(path, extension);
            if (extension.equals("json") && Files.size(path) <= 4 * 1024 * 1024) {
                try {
                    Json.read(path);
                    return identified(extension, "json", "structured-text", "JSON_PARSE");
                } catch (IOException ex) {
                    return new Detection(
                            extension,
                            "text",
                            "text",
                            "MISMATCH",
                            "JSON_PARSE_FAILED",
                            "文件声明 JSON，但内容无法按严格 JSON 解析");
                }
            }
            String text = decodePrefix(bytes, Files.size(path) > bytes.length);
            if (text != null
                    && text.codePoints()
                            .noneMatch(c -> c == 0 || c < 32 && c != 9 && c != 10 && c != 13)) {
                String format = TEXT_EXTENSIONS.contains(extension) ? extension : "text";
                if (extension.equals("json"))
                    return new Detection(
                            extension,
                            "unknown",
                            "text",
                            "UNRESOLVED",
                            "UTF8_PREFIX",
                            "JSON 文件较大，尚未进行完整 JSON 检查");
                return identified(extension, format, "text", "UTF8_PREFIX");
            }
            return new Detection(
                    extension, "unknown", "unknown", "UNRESOLVED", "NONE", "未识别实际格式；后缀仅作为声明");
        } catch (Exception ex) {
            return new Detection(
                    extension,
                    "unknown",
                    "unknown",
                    "UNRESOLVED",
                    "INSPECTION_FAILED",
                    "检测未完成：" + ex.getMessage());
        }
    }

    static String decodePrefix(byte[] bytes, boolean truncated) {
        for (int trim = 0; trim <= (truncated ? Math.min(3, bytes.length) : 0); trim++)
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim))
                        .toString();
            } catch (CharacterCodingException ex) {
                // 有界前缀可能截断 UTF-8 字符，允许去掉末尾不完整字节后重试。
                // A bounded prefix may end inside a multibyte character.
            }
        return null;
    }

    static Detection identified(String extension, String format, String family, String evidence) {
        boolean matches =
                extension.equals(format)
                        || Set.of("jpg", "jpeg").contains(extension)
                                && Set.of("jpg", "jpeg").contains(format)
                        || format.equals("text") && extension.isEmpty();
        return new Detection(
                extension,
                format,
                family,
                matches ? "IDENTIFIED" : "MISMATCH",
                evidence,
                matches
                        ? "格式线索与后缀一致，仍需处理工具验证具体内容"
                        : "后缀 ." + extension + " 与识别内容 " + format + " 不一致");
    }

    /** 只查看 ZIP 内的类型线索，不将容器解压到文件系统。 / Inspect archive metadata in place. */
    static Detection zip(Path path, String extension) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry types = zip.getEntry("[Content_Types].xml");
            if (types != null) {
                byte[] xml = bounded(zip, types, 65536);
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                NodeList parts =
                        factory.newDocumentBuilder()
                                .parse(new ByteArrayInputStream(xml))
                                .getElementsByTagNameNS("*", "Override");
                Set<String> found = new HashSet<>();
                for (int i = 0; i < parts.getLength(); i++) {
                    Element part = (Element) parts.item(i);
                    String format = OFFICE_MIMES.get(part.getAttribute("ContentType"));
                    String entryName = part.getAttribute("PartName").replaceFirst("^/", "");
                    if (format != null && zip.getEntry(entryName) != null) found.add(format);
                }
                if (found.size() == 1)
                    return identified(
                            extension, found.iterator().next(), "office-xml", "ZIP_CONTENT_TYPES");
                if (found.size() > 1)
                    return new Detection(
                            extension,
                            "office-container",
                            "office-xml",
                            "UNRESOLVED",
                            "ZIP_CONTENT_TYPES",
                            "存在多种主文档类型，不能自动确定");
            }
            ZipEntry mime = zip.getEntry("mimetype");
            if (mime != null) {
                String value = new String(bounded(zip, mime, 256), StandardCharsets.US_ASCII);
                String format =
                        switch (value) {
                            case "application/vnd.oasis.opendocument.text" -> "odt";
                            case "application/vnd.oasis.opendocument.spreadsheet" -> "ods";
                            case "application/vnd.oasis.opendocument.presentation" -> "odp";
                            default -> null;
                        };
                if (format != null && zip.getEntry("content.xml") != null)
                    return identified(extension, format, "open-document", "ZIP_MIMETYPE");
            }
            if (zip.getEntry("META-INF/MANIFEST.MF") != null
                    && zip.stream().anyMatch(entry -> entry.getName().endsWith(".class")))
                return identified(extension, "jar", "java-archive", "ZIP_MANIFEST_CLASSES");
            return identified(extension, "zip", "archive", "ZIP_CONTAINER");
        }
    }

    /** 对解压后的元信息长度设上限，不能只信任 ZIP 声明的大小。 / Bound decompressed metadata. */
    static byte[] bounded(ZipFile zip, ZipEntry entry, int maximum) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("类型元信息超过检测限制");
            return bytes;
        }
    }

    static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++)
            if ((bytes[i] & 255) != signature[i]) return false;
        return true;
    }
}

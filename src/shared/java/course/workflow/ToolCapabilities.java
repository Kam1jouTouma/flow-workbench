package course.workflow;

import java.util.*;

/** 纯协议能力声明，不引用画布或执行器。 / CLI capability declarations without host dependencies. */
final class ToolCapabilities {
    private ToolCapabilities() {}

    static Map<String, Object> declaration(String toolId) {
        if (toolId.equals("ai.text")) {
            var data =
                    Map.of(
                            "name",
                            "data",
                            "dataTypes",
                            List.of("TEXT"),
                            "cardinality",
                            "SINGLE",
                            "formats",
                            List.of("txt"),
                            "validation",
                            "IDENTIFY");
            var source =
                    Map.of(
                            "name",
                            "source",
                            "dataTypes",
                            List.of("JSON"),
                            "cardinality",
                            "SINGLE",
                            "formats",
                            List.of("json"),
                            "validation",
                            "IDENTIFY");
            return Map.of(
                    "capabilityVersion",
                    1,
                    "toolId",
                    toolId,
                    "toolVersion",
                    "1.0.0",
                    "implementation",
                    "AVAILABLE",
                    "operation",
                    "CONVERT",
                    "inputs",
                    List.of(data, source),
                    "outputs",
                    List.of(data, source),
                    "knownExtensions",
                    List.of("txt"));
        }
        if (toolId.equals("batch.pdf") || toolId.equals("batch.rename")) {
            boolean pdf = toolId.equals("batch.pdf");
            Map<String, Object> data =
                    Map.of(
                            "name",
                            "data",
                            "dataTypes",
                            List.of("FILE"),
                            "cardinality",
                            "COLLECTION",
                            "formats",
                            pdf ? new ArrayList<>(PdfFormats.supported()) : List.of("*"),
                            "validation",
                            pdf ? "IDENTIFY" : "OPAQUE");
            Map<String, Object> source =
                    Map.of(
                            "name",
                            "source",
                            "dataTypes",
                            List.of("JSON"),
                            "cardinality",
                            "SINGLE",
                            "formats",
                            List.of("json"),
                            "validation",
                            "IDENTIFY");
            List<Object> inputs = new ArrayList<>(List.of(data, source));
            if (!pdf)
                inputs.add(
                        Map.of(
                                "name",
                                "csv",
                                "dataTypes",
                                List.of("FILE"),
                                "cardinality",
                                "SINGLE",
                                "formats",
                                List.of("csv"),
                                "validation",
                                "IDENTIFY"));
            return Map.of(
                    "capabilityVersion",
                    1,
                    "toolId",
                    toolId,
                    "toolVersion",
                    FileCli.version(toolId),
                    "implementation",
                    "AVAILABLE",
                    "operation",
                    pdf ? "CONVERT" : "PRESERVE",
                    "inputs",
                    inputs,
                    "outputs",
                    List.of(
                            pdf
                                    ? Map.of(
                                            "name",
                                            "data",
                                            "dataTypes",
                                            List.of("FILE"),
                                            "cardinality",
                                            "COLLECTION",
                                            "formats",
                                            List.of("pdf"),
                                            "validation",
                                            "IDENTIFY")
                                    : data,
                            source),
                    "knownExtensions",
                    pdf ? new ArrayList<>(PdfFormats.supported()) : FileCli.SUPPORTED);
        }
        Map<String, Object> filePort =
                Map.of(
                        "name",
                        "data",
                        "dataTypes",
                        List.of("FILE"),
                        "cardinality",
                        "COLLECTION",
                        "formats",
                        List.of("*"),
                        "validation",
                        "OPAQUE");
        Map<String, Object> sourcePort =
                Map.of(
                        "name",
                        "source",
                        "dataTypes",
                        List.of("JSON"),
                        "cardinality",
                        "SINGLE",
                        "formats",
                        List.of("json"),
                        "validation",
                        "IDENTIFY");
        return Map.of(
                "capabilityVersion",
                1,
                "toolId",
                toolId,
                "toolVersion",
                FileCli.version(toolId),
                "implementation",
                "AVAILABLE",
                "operation",
                "PRESERVE",
                "inputs",
                toolId.equals("io.file_select") ? List.of() : List.of(filePort, sourcePort),
                "outputs",
                toolId.equals("io.file_select") ? List.of(filePort, sourcePort) : List.of(filePort),
                "knownExtensions",
                FileCli.SUPPORTED);
    }
}

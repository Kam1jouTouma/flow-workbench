package course.workflow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Files;
import java.nio.file.Path;

/** 验证按本机位置生成的演示流程。 / Verify relocated demonstration templates. */
public final class CourseTemplatesSmoke {
    public static void main(String[] args) throws Exception {
        Path prepared = Path.of(args[0]).toAbsolutePath();
        var ai = WorkflowApp.Flow.load(prepared.resolve("01-csv-ai-report.flow.xml"));
        if (!ai.node("course_ai").settings.get("serviceId").equals("")) {
            throw new AssertionError("The shared template must not contain a bound service");
        }
        var flow = WorkflowApp.Flow.load(prepared.resolve("02-offline-report-pdf.flow.xml"));
        var result = BatchRunner.execute(flow, prepared.resolve("test-runs"), ignored -> {});
        if (result.files().size() != 1) throw new AssertionError("Expected one offline PDF");
        try (var document = Loader.loadPDF(result.files().get(0).destination().toFile())) {
            String text = new PDFTextStripper().getText(document);
            if (!text.contains("1750.50") || !text.contains("人工核对基准")) {
                throw new AssertionError("The expected reference content did not reach the PDF");
            }
        }
        if (!Files.isRegularFile(
                Path.of((String) flow.node("course_text_file").settings.get("sourcePath")))) {
            throw new AssertionError("Input must remain intact");
        }
        System.out.println(
                "PASS: relocated templates load, offline chain runs, PDF content and source verified.");
    }
}

package course.workflow;

import java.nio.file.*;
import java.util.*;

public class ScriptImportSmoke {
    static void check(boolean v, String m) {
        if (!v) throw new AssertionError(m);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(), app = FileTransferRunner.toolPath("");
        Files.createDirectories(root);
        var record = ScriptTools.register(app.resolve("script-templates/Python/copy_files.py"));
        var flow = new WorkflowApp.Flow();
        Path input = root.resolve("original.txt");
        Files.writeString(input, "custom script test");
        var a = BatchTests.input(flow, input, false, "txt");
        var script = flow.add(WorkflowApp.Kind.SCRIPT, 300, 100);
        ScriptTools.configure(script, record);
        script.settings.put("parametersJSON", "{\"prefix\":\"mine_\"}");
        check(flow.connect(a, script) == null, "connect generic script");
        BatchTests.output(flow, script, root.resolve("output"));
        Path saved = root.resolve("script.flow.xml");
        flow.save(saved);
        flow = WorkflowApp.Flow.load(saved);
        var result = BatchRunner.execute(flow, root.resolve("runs"), w -> {});
        check(
                Files.readString(root.resolve("output/mine_original.txt"))
                        .equals("custom script test"),
                "actual custom Python processing");
        check(Files.exists(input) && result.files().size() == 1, "source kept");
        var n =
                flow.nodes.stream()
                        .filter(v -> v.kind == WorkflowApp.Kind.SCRIPT)
                        .findFirst()
                        .orElseThrow();
        n.settings.put("parametersJSON", "{\"prefix\":7}");
        boolean rejected = false;
        try {
            ScriptTools.parameters(n);
        } catch (java.io.IOException ex) {
            rejected = true;
        }
        check(rejected, "bad parameter type rejected");
        n.settings.put("parametersJSON", "{\"unknown\":\"value\"}");
        rejected = false;
        try {
            ScriptTools.parameters(n);
        } catch (java.io.IOException ex) {
            rejected = true;
        }
        check(rejected, "unknown parameter rejected");
        n.settings.put("parametersJSON", "{}");
        n.settings.put("scriptHash", "changed");
        rejected = false;
        try {
            ScriptTools.validate(n);
        } catch (java.io.IOException ex) {
            rejected = true;
        }
        check(rejected, "modified script rejected");
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    var e = new WorkflowApp.Editor();
                    e.flow = Subgraphs.copy(flowForUi(saved));
                    e.select(
                            e.flow.nodes.stream()
                                    .filter(v -> v.kind == WorkflowApp.Kind.SCRIPT)
                                    .findFirst()
                                    .orElseThrow());
                    check(
                            e.batchPanel.scriptControls.containsKey("parametersJSON"),
                            "sidebar available");
                });
        System.out.println(
                "PASS: real import, registry, script ports, parameters, persistence, Python execution, source preservation, sidebar and tamper rejection");
    }

    static WorkflowApp.Flow flowForUi(Path p) {
        try {
            return WorkflowApp.Flow.load(p);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

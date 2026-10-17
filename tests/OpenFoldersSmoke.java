package course.workflow;

import java.nio.file.*;
import java.util.*;

public class OpenFoldersSmoke {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        Path source = root.resolve("source.txt");
        Files.writeString(source, "folder test");
        var f = new WorkflowApp.Flow();
        var in = f.add(WorkflowApp.Kind.FILE, 0, 0);
        in.settings.put("sourcePath", source.toString());
        var a = f.add(WorkflowApp.Kind.SAVE, 200, 0);
        var b = f.add(WorkflowApp.Kind.SAVE, 200, 200);
        a.settings.put("directory", root.resolve("enabled").toString());
        b.settings.put("directory", root.resolve("disabled").toString());
        f.connect(in, a);
        f.connect(in, b);
        a.settings.put("openFolderAfterSuccess", true);
        Path file = root.resolve("flow.xml");
        f.save(file);
        f = WorkflowApp.Flow.load(file);
        check(
                Boolean.TRUE.equals(f.node(a.id).settings.get("openFolderAfterSuccess")),
                "persist enabled");
        check(
                Boolean.FALSE.equals(f.node(b.id).settings.get("openFolderAfterSuccess")),
                "default off");
        var result = BatchRunner.execute(f, root.resolve("runs"), w -> {});
        check(
                result.foldersToOpen().equals(List.of(root.resolve("enabled").toRealPath())),
                "only selected output");
        for (var out : List.of(f.node(a.id), f.node(b.id))) {
            out.settings.put("openFolderAfterSuccess", true);
            out.settings.put("directory", root.resolve("shared").toString());
            out.settings.put("conflictPolicy", "RENAME");
        }
        result = BatchRunner.execute(f, root.resolve("runs"), w -> {});
        check(
                result.foldersToOpen().equals(List.of(root.resolve("shared").toRealPath())),
                "deduplicate folders");
        f.nodes.remove(f.node(b.id));
        f.edges.removeIf(e -> e.to().equals(b.id));
        f.node(a.id).settings.put("location", "SOURCE");
        f.node(a.id).settings.put("preserveNames", false);
        f.node(a.id).settings.put("fileName", "result.txt");
        result = BatchRunner.execute(f, root.resolve("runs"), w -> {});
        check(result.foldersToOpen().equals(List.of(root.toRealPath())), "original file folder");
        System.out.println(
                "PASS: default off, configuration roundtrip, selected outputs only, shared folder deduplication, SOURCE output actual destination.");
    }
}

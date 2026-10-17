package course.workflow;

import java.nio.file.*;
import java.util.*;

import javax.swing.SwingUtilities;

final class WorkflowImportTests {
    private WorkflowImportTests() {}

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        var editor = new WorkflowApp.Editor();
                        editor.folderSettings = dir.resolve("preferences.properties");
                        editor.fileDemo();
                        Path original = dir.resolve("original.flow.xml");
                        editor.currentFile = original;
                        var existing = editor.flow.nodes.get(0);
                        String originalTitle = editor.flow.title;
                        var incoming = Subgraphs.copy(editor.flow);
                        Path input = dir.resolve("import.flow.xml");
                        incoming.save(input);
                        byte[] before = Files.readAllBytes(input);
                        editor.importFlowFile(input);
                        require(
                                editor.flow.nodes.size() == 4 && editor.flow.edges.size() == 2,
                                "import appends nodes and edges");
                        require(
                                editor.flow.nodes.get(0) == existing
                                        && editor.currentFile.equals(original)
                                        && editor.flow.title.equals(originalTitle)
                                        && editor.dirty,
                                "preserve root file, title and existing node identity");
                        Set<String> ids = new HashSet<>();
                        for (var n : editor.flow.nodes)
                            require(ids.add(n.id), "unique imported IDs");
                        for (var e : editor.flow.edges)
                            require(
                                    editor.flow.node(e.from()) != null
                                            && editor.flow.node(e.to()) != null,
                                    "remapped edges");
                        require(
                                Arrays.equals(before, Files.readAllBytes(input)),
                                "source file unchanged");
                        var module = Subgraphs.defaultModule();
                        Path moduleFile = dir.resolve("module.flow.xml");
                        module.save(moduleFile);
                        editor.importFlowFile(moduleFile);
                        require(
                                editor.selected.kind == WorkflowApp.Kind.SUBGRAPH
                                        && editor.flow.nodes.size() == 5,
                                "definition imported as independent module");
                        editor.selected.child.title = "edited";
                        require(
                                !WorkflowApp.Flow.load(moduleFile).title.equals("edited"),
                                "module source isolation");
                        editor.editSubgraph();
                        int count = editor.flow.nodes.size();
                        boolean rejected = false;
                        try {
                            editor.importFlowFile(input);
                        } catch (java.io.IOException ex) {
                            rejected = true;
                        }
                        require(
                                rejected && editor.flow.nodes.size() == count,
                                "reject real output import inside child atomically");
                        editor.importFlowFile(moduleFile);
                        require(
                                editor.levels.size() == 1
                                        && editor.selected.kind == WorkflowApp.Kind.SUBGRAPH,
                                "import at current nested canvas");
                        Path remembered = dir.resolve("chosen-folder");
                        Files.createDirectories(remembered);
                        editor.rememberDirectory(remembered.resolve("flow.xml"));
                        require(
                                editor.workflowDirectory()
                                        .toPath()
                                        .equals(remembered.toAbsolutePath()),
                                "last folder remembered");
                        var reopened = new WorkflowApp.Editor();
                        reopened.folderSettings = editor.folderSettings;
                        require(
                                reopened.workflowDirectory()
                                        .toPath()
                                        .equals(remembered.toAbsolutePath()),
                                "last folder restored across editors");
                        Files.writeString(
                                dir.resolve("result.txt"),
                                "PASS: full flow merge; IDs/edges remapped; root and source preserved; definition module; nested import; atomic failure; persisted folder.\n");
                        System.out.println("PASS: workflow import and remembered folder");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }
}

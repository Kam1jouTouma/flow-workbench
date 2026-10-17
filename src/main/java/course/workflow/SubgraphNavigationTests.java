package course.workflow;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.SwingUtilities;

/** Exercises actual canvas events, shared editor navigation and complete workflow persistence. */
final class SubgraphNavigationTests {
    private SubgraphNavigationTests() {}

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run(Path destination) throws Exception {
        Files.createDirectories(destination);
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        WorkflowApp.Editor editor = new WorkflowApp.Editor();
                        editor.subgraphDemo();
                        editor.setSize(1440, 900);
                        WorkflowApp.layoutTree(editor);
                        WorkflowApp.Flow root = editor.flow;
                        WorkflowApp.Node module = editor.selected;
                        editor.canvas.zoom = 1.2;
                        editor.canvas.panX = 42;
                        editor.canvas.panY = 17;
                        Point scroll =
                                new Point(editor.canvasScroll.getViewport().getViewPosition());
                        int windows = java.awt.Window.getWindows().length;
                        int x = (int) ((module.x + 65) * editor.canvas.zoom + editor.canvas.panX);
                        int y = (int) ((module.y + 35) * editor.canvas.zoom + editor.canvas.panY);
                        editor.canvas.dispatchEvent(
                                new MouseEvent(
                                        editor.canvas,
                                        MouseEvent.MOUSE_PRESSED,
                                        System.currentTimeMillis(),
                                        0,
                                        x,
                                        y,
                                        2,
                                        false,
                                        MouseEvent.BUTTON1));
                        require(
                                editor.flow == module.child && editor.levels.size() == 1,
                                "double-click enters child in same editor");
                        require(
                                java.awt.Window.getWindows().length == windows,
                                "navigation creates no window");
                        require(
                                editor.rootFlow() == root && !editor.runButton.isEnabled(),
                                "root identity and child execution state");
                        WorkflowApp.Node nested =
                                editor.flow.add(WorkflowApp.Kind.SUBGRAPH, 350, 120);
                        editor.changed();
                        editor.select(nested);
                        editor.editSubgraph();
                        require(
                                editor.levels.size() == 2 && editor.flow == nested.child,
                                "nested navigation");
                        WorkflowApp.Node step =
                                editor.flow.nodes.stream()
                                        .filter(n -> n.kind == WorkflowApp.Kind.RENAME)
                                        .findFirst()
                                        .orElseThrow();
                        step.settings.put("template", "navigation_{x}");
                        editor.changed();
                        Path saved = destination.resolve("nested-navigation.flow.xml");
                        editor.currentFile = saved;
                        editor.save();
                        WorkflowApp.Flow loaded = WorkflowApp.Flow.load(saved);
                        WorkflowApp.Node loadedModule =
                                loaded.nodes.stream()
                                        .filter(n -> n.kind == WorkflowApp.Kind.SUBGRAPH)
                                        .findFirst()
                                        .orElseThrow();
                        WorkflowApp.Node loadedNested =
                                loadedModule.child.nodes.stream()
                                        .filter(n -> n.kind == WorkflowApp.Kind.SUBGRAPH)
                                        .findFirst()
                                        .orElseThrow();
                        require(
                                loaded.nodes.size() == root.nodes.size()
                                        && loadedNested.child.nodes.stream()
                                                .anyMatch(
                                                        n ->
                                                                "navigation_{x}"
                                                                        .equals(
                                                                                n.settings.get(
                                                                                        "template"))),
                                "save from child persists entire root and nested edits");
                        require(
                                !editor.dirty && editor.levels.size() == 2,
                                "save preserves current level and clears dirty flag");
                        editor.navigateTo(0);
                        require(
                                editor.flow == root
                                        && editor.selected == module
                                        && editor.levels.isEmpty(),
                                "breadcrumb returns to root selection");
                        require(
                                editor.canvas.zoom == 1.2
                                        && editor.canvas.panX == 42
                                        && editor.canvas.panY == 17
                                        && editor.canvasScroll
                                                .getViewport()
                                                .getViewPosition()
                                                .equals(scroll),
                                "parent view restored");
                        require(
                                editor.runButton.isEnabled()
                                        && "navigation_{x}".equals(step.settings.get("template")),
                                "edits retained and root run enabled");
                        editor.select(module);
                        editor.editSubgraph();
                        editor.fresh();
                        require(
                                editor.levels.isEmpty()
                                        && editor.flow.nodes.isEmpty()
                                        && editor.currentFile == null
                                        && !editor.moduleEditor,
                                "new flow clears navigation");
                        editor.subgraphDemo();
                        editor.editSubgraph();
                        editor.fileDemo();
                        require(
                                editor.levels.isEmpty()
                                        && editor.rootFlow() == editor.flow
                                        && !editor.moduleEditor,
                                "example clears navigation");
                        require(
                                editor.getInputMap(
                                                        javax.swing.JComponent
                                                                .WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                                                .get(javax.swing.KeyStroke.getKeyStroke("alt LEFT"))
                                        != null,
                                "back keyboard shortcut installed");
                        Files.writeString(
                                destination.resolve("result.txt"),
                                "PASS: same editor double-click; no extra window; nested navigation; root save; dirty state; breadcrumbs; view restoration; edit retention; new/example reset; Alt+Left.\n");
                        System.out.println("PASS: subgraph canvas navigation and persistence");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }
}

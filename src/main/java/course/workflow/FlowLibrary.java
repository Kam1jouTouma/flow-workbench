package course.workflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 用户配置的固定流程库；库位置与外部文件最近打开的位置独立。
 *
 * <p>User-configurable graph storage, independent of the last external file location.
 */
final class FlowLibrary {
    final Path settingsFile;

    FlowLibrary(Path settingsFile) {
        this.settingsFile = settingsFile;
    }

    Properties settings() throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(settingsFile))
            try (InputStream in = Files.newInputStream(settingsFile)) {
                p.load(in);
            }
        return p;
    }

    /** 读取流程库位置并确保目录存在。 Resolves and creates the configured library directory. */
    Path directory() throws IOException {
        String value = settings().getProperty("workflow.library", "");
        Path path =
                value.isBlank()
                        ? settingsFile.toAbsolutePath().getParent().resolve("flow-library")
                        : Path.of(value);
        Files.createDirectories(path);
        return path.toAbsolutePath().normalize();
    }

    void setDirectory(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        Files.createDirectories(path);
        Properties p = settings();
        p.setProperty("workflow.library", path.toString());
        try (OutputStream out = Files.newOutputStream(settingsFile)) {
            p.store(out, "Workflow locations");
        }
    }

    List<Path> files() throws IOException {
        try (var stream = Files.list(directory())) {
            return stream.filter(Files::isRegularFile)
                    .filter(
                            p ->
                                    p.getFileName()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(".xml"))
                    .sorted(
                            Comparator.comparing(
                                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

    /**
     * 先解析来源流程，再占用不冲突的文件名保存副本；已经位于本库的文件直接返回。
     *
     * <p>Parses before reserving a unique copy name; files already in this library are reused.
     */
    Path importFile(Path source) throws IOException {
        WorkflowApp.Flow flow = WorkflowApp.Flow.load(source);
        Path folder = directory();
        if (source.toAbsolutePath().normalize().getParent().equals(folder))
            return source.toAbsolutePath().normalize();
        String base = source.getFileName().toString().replaceFirst("(?i)(\\.flow)?\\.xml$", "");
        for (int i = 0; i < 10000; i++) {
            Path target = folder.resolve(base + (i == 0 ? "" : " (" + i + ")") + ".flow.xml");
            try {
                Files.createFile(target);
            } catch (FileAlreadyExistsException ex) {
                continue;
            }
            try {
                flow.save(target);
                return target;
            } catch (IOException | RuntimeException ex) {
                Files.deleteIfExists(target);
                throw ex;
            }
        }
        throw new IOException("同名文件过多，请更改来源文件名。");
    }

    static void verify(Path destination) throws Exception {
        Files.createDirectories(destination);
        FlowLibrary library = new FlowLibrary(destination.resolve("settings.properties"));
        Path chosen = destination.resolve("my-graphs").toAbsolutePath();
        library.setDirectory(chosen);
        Path outside = destination.resolve("outside");
        Files.createDirectories(outside);
        var flow = new WorkflowApp.Flow();
        flow.title = "我的复制流程";
        var in = flow.add(WorkflowApp.Kind.FILE, 50, 100);
        var out = flow.add(WorkflowApp.Kind.SAVE, 350, 100);
        flow.connect(in, out);
        Path source = outside.resolve("copy.flow.xml");
        flow.save(source);
        byte[] original = Files.readAllBytes(source);
        Path first = library.importFile(source), second = library.importFile(source);
        if (first.equals(second)
                || !first.getParent().equals(chosen)
                || library.files().size() != 2)
            throw new AssertionError("imports stored in library without overwrite");
        if (!Arrays.equals(original, Files.readAllBytes(source)))
            throw new AssertionError("source unchanged");
        if (!library.importFile(first).equals(first) || library.files().size() != 2)
            throw new AssertionError("same-library import avoids duplicate");
        Path bad = outside.resolve("bad.xml");
        Files.writeString(bad, "bad");
        boolean rejected = false;
        try {
            library.importFile(bad);
        } catch (IOException ex) {
            rejected = true;
        }
        if (!rejected || library.files().size() != 2)
            throw new AssertionError("invalid import atomic");
        Path defaultFolder = new FlowLibrary(destination.resolve("fresh.properties")).directory();
        if (!defaultFolder.equals(destination.toAbsolutePath().resolve("flow-library")))
            throw new AssertionError("fixed default directory");
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        var editor = new WorkflowApp.Editor();
                        editor.folderSettings = library.settingsFile;
                        editor.rememberDirectory(source);
                        if (!new FlowLibrary(editor.folderSettings).directory().equals(chosen))
                            throw new AssertionError("external chooser cannot redirect library");
                        var panel = new FlowLibraryPanel(editor);
                        if (panel.shown.size() != 2) throw new AssertionError("library list");
                        panel.search.setText("我的复制");
                        if (panel.shown.size() != 2) throw new AssertionError("title search");
                        panel.search.setText("不存在");
                        if (!panel.shown.isEmpty() || panel.open.isEnabled())
                            throw new AssertionError("empty filtered state");
                        editor.dirty = false;
                        if (!editor.openFlowFile(first)
                                || !editor.currentFile.equals(first)
                                || !editor.flow.title.equals(flow.title))
                            throw new AssertionError("open library graph");
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
        if (!new FlowLibrary(library.settingsFile).directory().equals(chosen))
            throw new AssertionError("folder persistence");
        Files.writeString(
                destination.resolve("result.txt"),
                "PASS: fixed default/custom folder; persistent library independent of import directory; import preserves source and collisions; same-folder import; invalid import; list/search; open graph.\n");
        System.out.println("PASS: fixed flow library, import and list");
    }
}

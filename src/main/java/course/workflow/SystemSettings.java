package course.workflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 应用级目录、运行选项与 AI 服务配置；这些偏好独立于可分享的流程文件。
 *
 * <p>Application storage, runtime and AI preferences live separately from shareable graph files.
 */
final class SystemSettings {
    final Path file;

    SystemSettings(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    static SystemSettings current() throws IOException {
        return new SystemSettings(FileTransferRunner.toolPath("user-settings.properties"));
    }

    Properties load() throws IOException {
        return new FlowLibrary(file).settings();
    }

    /** 未配置的目录相对设置文件定位，而非进程当前目录。 Defaults resolve beside the settings file. */
    Path path(Properties p, String key, String fallback) {
        String value = p.getProperty(key, "").strip();
        return (value.isEmpty() ? file.getParent().resolve(fallback) : Path.of(value))
                .toAbsolutePath()
                .normalize();
    }

    Path runs() throws IOException {
        return path(load(), "execution.runs", "run-history");
    }

    int workers() throws IOException {
        String value = load().getProperty("execution.workers", "");
        if (value.isBlank())
            return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= 8) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new IOException("系统设置中的同时运行任务数须为1至8。");
    }

    String python() throws IOException {
        return load().getProperty("runtime.python", "").strip();
    }

    void save(String library, String runs, String python, int workers) throws IOException {
        save(library, runs, python, workers, null);
    }

    /**
     * 验证后以临时文件替换设置；services 为 null 时保留已有 AI 服务配置。
     *
     * <p>Validates before file replacement; a null services argument preserves existing AI entries.
     */
    void save(
            String library,
            String runs,
            String python,
            int workers,
            List<AiServices.Service> services)
            throws IOException {
        if (workers < 1 || workers > 8) throw new IOException("同时运行任务数须为1至8。");
        Path lib = directory(library), logs = directory(runs);
        python = python.strip();
        if (!python.isEmpty()) {
            Path exe;
            try {
                exe = Path.of(python);
            } catch (InvalidPathException ex) {
                throw new IOException("Python路径无效。", ex);
            }
            if (!exe.isAbsolute()
                    || !Files.isRegularFile(exe)
                    || !exe.getFileName().toString().equalsIgnoreCase("python.exe"))
                throw new IOException("请选择存在的 Python 可执行文件 python.exe，或留空自动查找。");
            python = exe.normalize().toString();
        }
        Properties p = load();
        p.setProperty("workflow.library", lib.toString());
        p.setProperty("execution.runs", logs.toString());
        p.setProperty("runtime.python", python);
        p.setProperty("execution.workers", String.valueOf(workers));
        if (services != null) AiServices.put(p, services);
        Files.createDirectories(lib);
        Files.createDirectories(logs);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                p.store(out, "Flow system settings");
            }
            // 同目录替换优先采用原子移动，不支持时退回普通替换。 Prefer an atomic sibling-file replacement.
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path directory(String value) throws IOException {
        try {
            if (value.isBlank()) throw new IOException("请填写目录。");
            Path p = Path.of(value.strip());
            if (!p.isAbsolute() || (Files.exists(p) && !Files.isDirectory(p)))
                throw new IOException("请选择有效的绝对目录路径。");
            return p.normalize();
        } catch (InvalidPathException ex) {
            throw new IOException("目录路径无效。", ex);
        }
    }
}

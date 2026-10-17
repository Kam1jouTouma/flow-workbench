package course.workflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 有时间和日志大小限制的命令调用，供工具查询及转换适配器使用。
 *
 * <p>Runs helper commands with a deadline and bounded stdout/stderr capture.
 */
final class ProcessCommands {
    static String run(List<String> command, Path cwd, int timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) builder.directory(cwd.toFile());
        String python = ToolPaths.loadSettings().getProperty("runtime.python", "").strip();
        if (!python.isBlank()) builder.environment().put("FLOW_PYTHON", python);
        Process process = builder.start();
        // 双路并行读取，避免只消费 stdout 时被 stderr 管道阻塞。
        // Drain both output streams concurrently rather than waiting on one pipe at a time.
        ExecutorService readers = Executors.newFixedThreadPool(2);
        var stdout = readers.submit(() -> process.getInputStream().readNBytes(1024 * 1024 + 1));
        var stderr = readers.submit(() -> process.getErrorStream().readNBytes(1024 * 1024 + 1));
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
                throw new IOException("工具超时，已停止启动的子进程。");
            }
            byte[] out = stdout.get(5, TimeUnit.SECONDS), err = stderr.get(5, TimeUnit.SECONDS);
            if (out.length > 1024 * 1024 || err.length > 1024 * 1024)
                throw new IOException("工具查询/日志超过1MiB限制。");
            if (process.exitValue() != 0)
                throw new IOException(
                        "工具退出"
                                + process.exitValue()
                                + "："
                                + new String(err, StandardCharsets.UTF_8));
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(ex);
        } catch (ExecutionException | TimeoutException ex) {
            process.destroyForcibly();
            throw new IOException(ex);
        } finally {
            readers.shutdownNow();
        }
    }
}

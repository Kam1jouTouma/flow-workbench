package course.workflow;

import com.sun.net.httpserver.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

public class AiPipelineSmoke {
    static void check(boolean v, String m) {
        if (!v) throw new AssertionError(m);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        var settings = SystemSettings.current();
        String key = "dummy-" + UUID.randomUUID(), id = UUID.randomUUID().toString();
        String cipher = AiServices.protect(key.toCharArray());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AtomicBoolean auth = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        calls.incrementAndGet();
                        String path = exchange.getRequestURI().getPath();
                        auth.set(
                                ("Bearer " + key)
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization")));
                        captured.set(
                                Json.object(
                                        Json.parse(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8))));
                        if (path.equals("/redirect")) {
                            exchange.getResponseHeaders().add("Location", "/ok");
                            exchange.sendResponseHeaders(302, -1);
                            return;
                        }
                        byte[] body =
                                (path.equals("/error")
                                                ? key
                                                : Json.stringify(
                                                        Map.of(
                                                                "choices",
                                                                List.of(
                                                                        Map.of(
                                                                                "message",
                                                                                Map.of(
                                                                                        "content",
                                                                                        "测试AI输出"),
                                                                                "finish_reason",
                                                                                "length")))))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(
                                path.equals("/error") ? 401 : 200, body.length);
                        if (path.equals("/slow"))
                            try {
                                Thread.sleep(3500);
                            } catch (InterruptedException ignored) {
                            }
                        exchange.getResponseBody().write(body);
                    } catch (Exception ignored) {
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            settings.save(
                    root.resolve("flows").toString(),
                    root.resolve("records").toString(),
                    "",
                    2,
                    List.of(
                            new AiServices.Service(
                                    id, "Mock", base + "/ok?version=1", "mock-model", cipher)));
            var f = new WorkflowApp.Flow();
            var text = f.add(WorkflowApp.Kind.TEXT, 0, 0);
            text.value = "中文输入内容";
            var ai = f.add(WorkflowApp.Kind.AI, 200, 0);
            ai.settings.put("serviceId", id);
            ai.settings.put("prompt", "测试Prompt");
            ai.settings.put("maxTokens", 321);
            ai.settings.put("timeoutSeconds", 3);
            var out = BatchTests.output(f, ai, root.resolve("output"));
            f.connect(text, ai);
            Path graph = root.resolve("ai.flow.xml");
            f.save(graph);
            f = WorkflowApp.Flow.load(graph);
            var result =
                    BatchRunner.execute(
                            f,
                            settings.runs(),
                            w -> check(w.get(0).contains("Token"), "truncation warning"));
            check(
                    Files.readString(result.files().get(0).destination()).equals("测试AI输出"),
                    "published AI answer");
            check(auth.get(), "runtime key header");
            check(
                    captured.get().get("model").equals("mock-model")
                            && captured.get().get("max_completion_tokens").toString().equals("321"),
                    "model/token knobs");
            check(
                    Json.string(
                                    Json.object(Json.list(captured.get().get("messages")).get(0)),
                                    "content")
                            .equals("测试Prompt"),
                    "prompt propagated");
            try (var files = Files.walk(settings.runs())) {
                for (Path p : files.filter(Files::isRegularFile).toList())
                    check(
                            !new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                                    .contains(key),
                            "key absent from run files");
            }
            check(
                    !Files.readString(graph).contains(key)
                            && !Files.readString(graph).contains(cipher),
                    "graph contains service ID only");
            ToolLauncher.probe(root.resolve("ai-text-tool-v1.jar"));
            SwingUtilities.invokeAndWait(
                    () -> {
                        var editor = new WorkflowApp.Editor();
                        var n = editor.flow.add(WorkflowApp.Kind.AI, 10, 10);
                        editor.select(n);
                        check(
                                editor.batchPanel.aiControls.containsKey("prompt")
                                        && editor.batchPanel.aiControls.containsKey(
                                                "timeoutSeconds"),
                                "AI sidebar controls");
                    });
            for (String path : List.of("error", "redirect", "slow")) {
                settings.save(
                        root.resolve("flows").toString(),
                        root.resolve("records").toString(),
                        "",
                        2,
                        List.of(
                                new AiServices.Service(
                                        id, "Mock", base + "/" + path, "mock-model", cipher)));
                f.node(ai.id).settings.put("timeoutSeconds", 1);
                f.node(out.id).settings.put("directory", root.resolve("failed-" + path).toString());
                int before = calls.get();
                long start = System.nanoTime();
                boolean failed = false;
                try {
                    BatchRunner.execute(f, settings.runs(), w -> {});
                } catch (java.io.IOException e) {
                    failed = true;
                    check(!e.getMessage().contains(key), "safe error");
                }
                check(
                        failed && !Files.exists(root.resolve("failed-" + path)),
                        "no publication on " + path);
                check(calls.get() == before + 1, "no retry/redirect on " + path);
                if (path.equals("slow"))
                    check(
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 3,
                            "timeout includes body read");
            }
            System.out.println(
                    "PASS: real CLI mock HTTP pipeline, Prompt/model/token/auth, save/load/sidebar, truncation warning, key absent from graph/run files, HTTP error/redirection/slow-body timeout and no publication or retries.");
        } finally {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}

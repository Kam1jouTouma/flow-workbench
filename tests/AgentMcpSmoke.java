package course.workflow;

import com.sun.net.httpserver.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class AgentMcpSmoke {
    static void check(boolean v, String m) {
        if (!v) throw new AssertionError(m);
    }

    static Map<String, Object> call(String id, String name, Object arguments) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", Json.stringify(arguments)));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        var initial = new WorkflowApp.Flow();
        var original = initial.add(WorkflowApp.Kind.TEXT, 0, 0);
        original.value = "保留原画布";
        var mcp = new WorkflowMcp(initial, root.resolve("prefs.properties"));
        check(
                Json.object(
                                mcp.request(
                                                Map.of(
                                                        "jsonrpc",
                                                        "2.0",
                                                        "id",
                                                        1,
                                                        "method",
                                                        "initialize",
                                                        "params",
                                                        Map.of()))
                                        .get("result"))
                        .get("protocolVersion")
                        .equals("2025-06-18"),
                "MCP initialize");
        Object flow =
                Map.of(
                        "title",
                        "自动搭建测试",
                        "nodes",
                        List.of(
                                Map.of("id", "input", "kind", "TEXT", "value", "摘要内容"),
                                Map.of(
                                        "id",
                                        "save",
                                        "kind",
                                        "SAVE",
                                        "settings",
                                        Map.of(
                                                "directory",
                                                root.resolve("not-created-output").toString()))),
                        "edges",
                        List.of(Map.of("from", "input", "to", "save")));
        AtomicInteger rounds = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext(
                "/chat",
                e -> {
                    try {
                        var body =
                                Json.object(
                                        Json.parse(
                                                new String(
                                                        e.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8)));
                        check(
                                Json.list(body.get("tools")).size() == 5,
                                "MCP discovered tool schemas provided to API");
                        int n = rounds.getAndIncrement();
                        Object message =
                                switch (n) {
                                    case 0 ->
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "tool_calls",
                                                    List.of(
                                                            call("c1", "catalog_list", Map.of()),
                                                            call("c2", "workflow_get", Map.of())));
                                    case 1 ->
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "tool_calls",
                                                    List.of(
                                                            call(
                                                                    "c3",
                                                                    "workflow_replace",
                                                                    Map.of(
                                                                            "expectedRevision",
                                                                            0,
                                                                            "flow",
                                                                            flow))));
                                    case 2 ->
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "tool_calls",
                                                    List.of(
                                                            call(
                                                                    "c4",
                                                                    "workflow_validate",
                                                                    Map.of())));
                                    default ->
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "content",
                                                    "已通过MCP搭建文本输入到文件输出草稿。");
                                };
                        byte[] bytes =
                                Json.stringify(
                                                Map.of(
                                                        "choices",
                                                        List.of(
                                                                Map.of(
                                                                        "message",
                                                                        message,
                                                                        "finish_reason",
                                                                        n < 3
                                                                                ? "tool_calls"
                                                                                : "stop"))))
                                        .getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                    } catch (Exception ignored) {
                    } finally {
                        e.close();
                    }
                });
        server.start();
        try {
            var service =
                    new AiServices.Service(
                            UUID.randomUUID().toString(),
                            "Mock",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                            "mock-tools",
                            "");
            var agent = new AgentConversation(mcp);
            String reply =
                    agent.turn(
                            service,
                            "生成文本输入到文件输出",
                            1024,
                            5,
                            6,
                            "max_tokens",
                            new AtomicBoolean(),
                            s -> {});
            check(
                    reply.contains("MCP")
                            && mcp.draft.nodes.size() == 2
                            && mcp.draft.edges.size() == 1,
                    "API tool loop creates graph");
            check(
                    initial.nodes.size() == 1 && initial.node(original.id).value.equals("保留原画布"),
                    "original unchanged");
            check(!Files.exists(root.resolve("not-created-output")), "no execution/files created");
            check(mcp.revision == 1, "revision advances");
            var conflict =
                    mcp.request(
                            Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    8,
                                    "method",
                                    "tools/call",
                                    "params",
                                    Map.of(
                                            "name",
                                            "workflow_replace",
                                            "arguments",
                                            Map.of("expectedRevision", 0, "flow", flow))));
            check(
                    Boolean.TRUE.equals(Json.object(conflict.get("result")).get("isError"))
                            && mcp.revision == 1,
                    "revision conflict no mutation");
            Map<String, Object> bad =
                    Map.of(
                            "title",
                            "bad",
                            "nodes",
                            List.of(
                                    Map.of(
                                            "id",
                                            "x",
                                            "kind",
                                            "AI",
                                            "settings",
                                            Map.of("key", "never-store-secret"))),
                            "edges",
                            List.of());
            var rejected =
                    mcp.request(
                            Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    9,
                                    "method",
                                    "tools/call",
                                    "params",
                                    Map.of(
                                            "name",
                                            "workflow_replace",
                                            "arguments",
                                            Map.of("expectedRevision", 1, "flow", bad))));
            check(
                    Boolean.TRUE.equals(Json.object(rejected.get("result")).get("isError"))
                            && mcp.draft.title.equals("自动搭建测试"),
                    "bad/key settings rejected atomically");
            check(
                    mcp.request(
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            10,
                                            "method",
                                            "tools/call",
                                            "params",
                                            Map.of("name", "run_start", "arguments", Map.of())))
                            .containsKey("error"),
                    "no execute permission");
            rounds.set(0);
            boolean stopped = false;
            try {
                agent.turn(
                        service, "取消", 1024, 5, 6, "max_tokens", new AtomicBoolean(true), s -> {});
            } catch (java.io.IOException ex) {
                stopped = true;
            }
            check(stopped && rounds.get() == 0, "cancel before request");
            System.out.println(
                    "PASS: MCP initialize/list/call, API multi-round tool loop, draft nodes/edges, original and filesystem untouched, revision conflict, atomic validation, denied execution, cancellation.");
        } finally {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}

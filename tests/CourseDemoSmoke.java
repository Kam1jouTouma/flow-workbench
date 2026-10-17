package course.workflow;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 隔离目录中的课程演示链路验证，只使用本地模拟服务和随机假 Key。
 *
 * <p>Checks real CLI transport with a fixed mock response, not a model's arithmetic ability.
 */
public final class CourseDemoSmoke {
    private static final String MARKER = "FLOW_COURSE_DEMO_SMOKE_V1";

    private CourseDemoSmoke() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 先确认代码和设置均位于专用副本，再允许写入测试服务。 / Fail closed before settings access. */
    private static Path isolatedApplication(String argument) throws Exception {
        Path app = Path.of(argument).toAbsolutePath().normalize().toRealPath();
        Path actual = FileTransferRunner.toolPath("user-settings.properties");
        check(
                actual.getParent().toRealPath().equals(app),
                "Run using the isolated application JAR");
        check(
                Files.readString(app.resolve(".course-demo-smoke.marker")).strip().equals(MARKER),
                "Dedicated test marker is required");
        check(!Files.exists(actual), "Refusing to replace an existing settings file");
        check(
                !Files.exists(app.resolve("install-data.path")),
                "Do not copy installed data pointers");
        check(!Files.exists(app.resolve("installation.json")), "Use a clean test application copy");
        check(!Files.exists(app.resolve("course-test-data")), "Use a fresh test directory");
        for (String file :
                List.of(
                        "file-input-tool-v1.jar",
                        "ai-text-tool-v1.jar",
                        "file-output-tool-v1.jar",
                        "tools/key-protector.exe")) {
            check(Files.isRegularFile(app.resolve(file)), "Missing test dependency: " + file);
        }
        return app;
    }

    private static String xmlText(Path path) {
        return path.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Usage: CourseDemoSmoke <isolated-application-directory>");
        Path app = isolatedApplication(args[0]);
        Path material = app.resolve("examples/course");
        Path data = Files.createDirectory(app.resolve("course-test-data"));
        Path csv = data.resolve("expenses.csv");
        Files.copy(material.resolve("data/expenses.csv"), csv);
        String csvText = Files.readString(csv, StandardCharsets.UTF_8);
        String sourceHash = FileCli.sha256(csv);
        String fixedResponse =
                Files.readString(material.resolve("expected-report.txt"), StandardCharsets.UTF_8);

        // 只核对合成样本自身的基准；此计算不由模拟服务或真实模型完成。
        // Validate the fixture independently; the mock server does not compute statistics.
        var fixture = CsvTable.read(csv, "UTF-8", ',');
        check(fixture.rows().size() == 3, "Expected three synthetic CSV records");
        BigDecimal total = BigDecimal.ZERO;
        for (var row : fixture.rows()) total = total.add(new BigDecimal(row.get("amount")));
        check(total.compareTo(new BigDecimal("1750.50")) == 0, "Fixture total must be 1750.50");

        String template =
                Files.readString(
                        material.resolve("templates/csv-ai-report.flow.xml.template"),
                        StandardCharsets.UTF_8);
        Path flowPath = data.resolve("csv-ai-report.flow.xml");
        Path output = data.resolve("output");
        Files.writeString(
                flowPath,
                template.replace("@@CSV_SOURCE@@", xmlText(csv))
                        .replace("@@AI_OUTPUT@@", xmlText(output)),
                StandardCharsets.UTF_8);
        var flow = WorkflowApp.Flow.load(flowPath);
        var ai = flow.node("course_ai");
        check(ai != null && ai.settings.get("serviceId").equals(""), "Template service is unbound");
        check(flow.node("course_csv").settings.get("mode").equals("TEXT"), "CSV is read as TEXT");
        check(
                flow.node("course_ai_save").settings.get("operation").equals("COPY"),
                "The demonstration must retain its input");

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        String fakeKey = "course-test-only-" + UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try {
                        calls.incrementAndGet();
                        check(exchange.getRequestMethod().equals("POST"), "Expected POST");
                        check(
                                ("Bearer " + fakeKey)
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization")),
                                "Dummy authentication header");
                        byte[] request = exchange.getRequestBody().readNBytes(65537);
                        check(request.length <= 65536, "Unexpectedly large demonstration request");
                        captured.set(
                                Json.object(
                                        Json.parse(new String(request, StandardCharsets.UTF_8))));
                        byte[] response =
                                Json.stringify(
                                                Map.of(
                                                        "choices",
                                                        List.of(
                                                                Map.of(
                                                                        "message",
                                                                        Map.of(
                                                                                "content",
                                                                                fixedResponse),
                                                                        "finish_reason",
                                                                        "stop"))))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    } catch (Throwable failure) {
                        handlerFailure.compareAndSet(null, failure);
                        try {
                            exchange.sendResponseHeaders(500, -1);
                        } catch (IOException ignored) {
                            // 请求可能已关闭，原始断言保存在 handlerFailure 中。
                            // Preserve the original assertion if the exchange is already closed.
                        }
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            // 该路径已由 isolatedApplication 验证，不读取日常使用的服务列表。
            // This fresh settings path belongs to the explicitly verified test application.
            var settings = SystemSettings.current();
            String serviceId = UUID.randomUUID().toString();
            settings.save(
                    data.resolve("library").toString(),
                    data.resolve("runs").toString(),
                    "",
                    2,
                    List.of(
                            new AiServices.Service(
                                    serviceId,
                                    "Course local mock",
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/v1/chat/completions",
                                    "course-mock-model",
                                    AiServices.protect(fakeKey.toCharArray()))));
            ai.settings.put("serviceId", serviceId);
            ai.settings.put("timeoutSeconds", 10);
            flow.save(flowPath);
            var result =
                    BatchRunner.execute(
                            WorkflowApp.Flow.load(flowPath),
                            settings.runs(),
                            warnings -> check(warnings.isEmpty(), "Unexpected tool warning"));
            if (handlerFailure.get() != null)
                throw new AssertionError("Mock request verification failed", handlerFailure.get());
            check(calls.get() == 1, "One local request, without retries");
            var request = captured.get();
            check(request != null, "The mock server received the request");
            var messages = Json.list(request.get("messages"));
            check(
                    Json.string(Json.object(messages.get(1)), "content")
                            .equals(csvText.replace("\r\n", "\n").replace('\r', '\n')),
                    "Real CLI file input reaches the AI request as complete CSV text");
            check(
                    Json.string(Json.object(messages.get(0)), "content")
                            .equals(ai.settings.get("prompt")),
                    "The demonstration prompt reaches the service");
            check(request.get("model").equals("course-mock-model"), "Mock model selected");
            check(
                    request.get("max_completion_tokens").toString().equals("512"),
                    "The configured token limit reaches the service");
            check(result.files().size() == 1, "One report is published");
            Path report = result.files().get(0).destination();
            check(report.getParent().equals(output), "Report remains inside test output directory");
            check(FileCli.extension(report).equals("txt"), "Report has TXT format");
            check(
                    Files.readString(report).equals(fixedResponse),
                    "Fixed mock response is preserved");
            check(FileCli.sha256(csv).equals(sourceHash), "Synthetic CSV input is unchanged");
            var record = Json.object(Json.read(result.record()));
            check(record.get("state").equals("SUCCEEDED"), "Successful run record");
            check(record.get("publicationState").equals("SUCCEEDED"), "Successful publication");
            var kinds =
                    Json.list(record.get("nodeExecutions")).stream()
                            .map(value -> ((Map<?, ?>) value).get("kind"))
                            .toList();
            check(kinds.containsAll(List.of("FILE", "AI")), "Actual input and AI nodes executed");
            try (var files = Files.walk(data)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    check(
                            !Files.readString(file).contains(fakeKey),
                            "Dummy key absent from artifacts");
            }
            check(
                    !Files.readString(settings.file).contains(fakeKey),
                    "Dummy key is protected in test settings");
            Json.write(data.resolve("captured-request.json"), request);
            Json.write(
                    data.resolve("summary.json"),
                    Map.of(
                            "status", "PASS",
                            "validationScope", "MOCK_TRANSPORT_ONLY_NOT_MODEL_ARITHMETIC",
                            "fixtureRows", fixture.rows().size(),
                            "fixtureTotal", total.toPlainString(),
                            "httpCalls", calls.get(),
                            "report", report.toString(),
                            "runRecord", result.record().toString()));
            System.out.println(
                    "PASS: CSV -> real CLI -> local HTTP mock -> TXT and run record. "
                            + "This verifies transport, not model arithmetic. Evidence: "
                            + data.resolve("summary.json"));
        } finally {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}

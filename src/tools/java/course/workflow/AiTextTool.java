package course.workflow;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 独立文本处理 CLI：验证单个 TEXT 快照，调用兼容 Chat Completions 的服务并生成暂存结果。
 *
 * <p>Standalone TEXT tool that validates its input snapshot and stages a Chat Completions response.
 */
public final class AiTextTool {
    static final String ID = "ai.text";

    public static void main(String[] args) {
        FileCli.main(args, ID, AiTextTool::execute);
    }

    static int integer(Map<String, Object> p, String key, int fallback, int min, int max)
            throws IOException {
        Object value = p.getOrDefault(key, fallback);
        try {
            int n = new java.math.BigDecimal(value.toString()).intValueExact();
            if (n >= min && n <= max) return n;
        } catch (RuntimeException ignored) {
        }
        throw new IOException(key + "数值超出范围。");
    }

    /**
     * 服务配置只按 ID 在本机读取；结果保持来源清单，最终复制或移动由主程序完成。
     *
     * <p>Resolves local services by ID and preserves provenance for publication by the main
     * application.
     */
    static void execute(Map<String, Object> request, Map<String, Object> p, Path output)
            throws IOException {
        if (!Set.of(
                        "serviceId",
                        "prompt",
                        "maxTokens",
                        "timeoutSeconds",
                        "temperature",
                        "tokenParameter")
                .containsAll(p.keySet())) throw new IOException("未知AI工具配置。");
        String serviceId = Json.string(p, "serviceId"), prompt = Json.string(p, "prompt");
        if (prompt.isBlank()) throw new IOException("请填写Prompt。");
        int max = integer(p, "maxTokens", 2048, 1, 1000000),
                timeout = integer(p, "timeoutSeconds", 120, 1, 600);
        String tokenParameter = (String) p.getOrDefault("tokenParameter", "max_completion_tokens");
        if (!Set.of("max_tokens", "max_completion_tokens").contains(tokenParameter))
            throw new IOException("Token参数格式无效。");
        String temperature = (String) p.getOrDefault("temperature", "");
        java.math.BigDecimal temp = null;
        if (!temperature.isBlank())
            try {
                temp = new java.math.BigDecimal(temperature);
                if (temp.compareTo(java.math.BigDecimal.ZERO) < 0
                        || temp.compareTo(java.math.BigDecimal.valueOf(2)) > 0)
                    throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new IOException("Temperature须为0至2，或留空由服务决定。");
            }
        var service =
                AiServices.load(ToolPaths.loadSettings()).stream()
                        .filter(s -> s.id().equals(serviceId))
                        .findFirst()
                        .orElseThrow(() -> new IOException("所选AI服务不存在，请在系统设置中配置并重新选择。"));
        var ports = Json.object(request.get("inputs"));
        if (!ports.keySet().equals(Set.of("data", "source")))
            throw new IOException("AI工具要求data和source输入。");
        var data = Json.object(ports.get("data"));
        var items = Json.list(data.get("items"));
        var manifests = Json.list(Json.object(ports.get("source")).get("items"));
        if (!"TEXT".equals(data.get("type")) || items.size() != 1 || manifests.size() != 1)
            throw new IOException("AI工具只接受单个TEXT输入。请将文件输入设为文本读取。");
        Path input = Path.of(Json.string(Json.object(items.get(0)), "path")),
                manifest = Path.of(Json.string(Json.object(manifests.get(0)), "path"));
        if (!input.isAbsolute() || !manifest.isAbsolute() || Files.size(input) > 1024 * 1024)
            throw new IOException("AI文本输入须为绝对路径且不超过1MiB。");
        var sources = Json.list(Json.object(Json.read(manifest)).get("files"));
        if (sources.size() != 1) throw new IOException("AI输入来源数量不匹配。");
        var source = new LinkedHashMap<>(Json.object(sources.get(0)));
        if (!FileCli.sha256(input).equals(source.get("sha256")))
            throw new IOException("AI输入内容校验失败。");
        String text = Files.readString(input, StandardCharsets.UTF_8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", service.model());
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", text)));
        body.put(tokenParameter, max);
        body.put("stream", false);
        if (temp != null) body.put("temperature", temp);
        byte[] keyBytes = AiServices.unprotect(service);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        Arrays.fill(keyBytes, (byte) 0);
        Map<String, Object> response;
        try {
            response = call(service.url(), key, body, timeout);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
        String answer;
        boolean truncated;
        try {
            var choices = Json.list(response.get("choices"));
            if (choices.isEmpty()) throw new IOException();
            var choice = Json.object(choices.get(0));
            answer = Json.string(Json.object(choice.get("message")), "content");
            truncated = "length".equals(choice.get("finish_reason"));
            if (answer.isBlank()) throw new IOException();
        } catch (Exception ex) {
            throw new IOException("AI服务未返回有效文本；请确认URL使用Chat Completions兼容格式。");
        }
        if (!key.isEmpty()) answer = answer.replace(key, "[Key已隐藏]");
        String filename = "AI结果.txt";
        Path payload = output.resolve("payloads").resolve(filename);
        Files.createDirectories(payload.getParent());
        Files.writeString(payload, answer, StandardCharsets.UTF_8);
        source.put(
                "originals",
                source.getOrDefault(
                        "originals",
                        List.of(
                                Map.of(
                                        "path",
                                        source.get("originalPath"),
                                        "sha256",
                                        source.get("sha256")))));
        source.put("relativePath", filename);
        source.put("sha256", FileCli.sha256(payload));
        Json.write(output.resolve("source.json"), Map.of("files", List.of(source)));
        if (truncated)
            Json.write(
                    output.resolve("warnings.json"),
                    Map.of("warnings", List.of("AI达到Token上限，结果可能被截断；可增加最大Token后重新执行。")));
        FileCli.result(
                request,
                output,
                Map.of(
                        "data",
                        FileCli.value("TEXT", List.of(FileCli.item("payloads/" + filename))),
                        "source",
                        FileCli.value("JSON", List.of(FileCli.item("source.json")))));
    }

    /**
     * 一次请求使用完整 URL，不重试或跳转；总时限覆盖响应体读取，错误正文不进入异常消息。
     *
     * <p>One exact-URL request with no retries or redirects, a body deadline and redacted error
     * reporting.
     */
    static Map<String, Object> call(String url, String key, Map<String, Object> body, int timeout)
            throws IOException {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        InputStream stream = null;
        CompletableFuture<HttpResponse<InputStream>> pending = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        try {
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.min(timeout, 30)))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(timeout))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            Json.stringify(body), StandardCharsets.UTF_8));
            if (!key.isEmpty()) builder.header("Authorization", "Bearer " + key);
            pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            var response =
                    pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            stream = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new SafeFailure(
                        "AI请求失败（HTTP "
                                + response.statusCode()
                                + "）。请检查URL、Model、Key或服务额度；未记录服务错误正文。");
            final InputStream input = stream;
            Future<byte[]> result = reader.submit(() -> input.readNBytes(4 * 1024 * 1024 + 1));
            byte[] bytes =
                    result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (bytes.length > 4 * 1024 * 1024) throw new SafeFailure("AI响应超过4MiB限制。");
            try {
                return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new SafeFailure("AI响应不是有效的JSON，请确认服务格式。");
            }
        } catch (SafeFailure ex) {
            throw ex;
        } catch (TimeoutException ex) {
            throw new IOException("AI响应超时，已停止等待；服务端可能仍在处理。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("AI请求已中断。");
        } catch (Exception ex) {
            throw new IOException("AI连接或响应失败，请检查网络、证书和服务设置；详细响应未写入日志。");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (stream != null)
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            reader.shutdownNow();
        }
    }

    @SuppressWarnings("serial")
    static final class SafeFailure extends IOException {
        SafeFailure(String message) {
            super(message);
        }
    }
}

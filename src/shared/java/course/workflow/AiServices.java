package course.workflow;

import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 保存服务元数据和绑定当前 Windows 账户的 DPAPI 密文；流程节点只引用服务 ID。
 *
 * <p>Stores service metadata and current-user DPAPI ciphertext; graph nodes reference service IDs.
 */
final class AiServices {
    record Service(String id, String name, String url, String model, String protectedKey) {
        boolean hasKey() {
            return !protectedKey.isEmpty();
        }

        public String toString() {
            return "AI service [" + id + "]";
        }
    }

    static final String PREFIX = "ai.services.", KEY_PREFIX = "dpapi-user-v1:";

    static List<Service> load(Properties p) throws IOException {
        List<Service> services = new ArrayList<>();
        int count;
        try {
            count = Integer.parseInt(p.getProperty(PREFIX + "count", "0"));
        } catch (NumberFormatException ex) {
            throw new IOException("AI服务列表格式无效。");
        }
        if (count < 0 || count > 100) throw new IOException("AI服务列表数量无效。");
        for (int i = 0; i < count; i++) {
            String k = PREFIX + i + ".";
            var service =
                    new Service(
                            p.getProperty(k + "id", ""),
                            p.getProperty(k + "name", ""),
                            p.getProperty(k + "url", ""),
                            p.getProperty(k + "model", ""),
                            p.getProperty(k + "protectedKey", ""));
            validate(service);
            services.add(service);
        }
        checkIds(services);
        return services;
    }

    /** 只替换 AI 服务前缀下的配置，保留其他系统设置。 Replaces only the AI service namespace. */
    static void put(Properties p, List<Service> services) throws IOException {
        if (services.size() > 100) throw new IOException("最多配置100个AI服务。");
        checkIds(services);
        for (var s : services) validate(s);
        p.keySet().removeIf(k -> k.toString().startsWith(PREFIX));
        p.setProperty(PREFIX + "count", String.valueOf(services.size()));
        for (int i = 0; i < services.size(); i++) {
            var s = services.get(i);
            String k = PREFIX + i + ".";
            p.setProperty(k + "id", s.id);
            p.setProperty(k + "name", s.name);
            p.setProperty(k + "url", s.url);
            p.setProperty(k + "model", s.model);
            p.setProperty(k + "protectedKey", s.protectedKey);
        }
    }

    static void checkIds(List<Service> services) throws IOException {
        Set<String> ids = new HashSet<>();
        for (var s : services) if (!ids.add(s.id)) throw new IOException("AI服务标识重复。");
    }

    /**
     * 检查元数据格式及密文封装，不发起网络请求，也不在此解密 Key。
     *
     * <p>Validates metadata and ciphertext framing without network calls or key decryption.
     */
    static void validate(Service s) throws IOException {
        try {
            UUID.fromString(s.id);
        } catch (IllegalArgumentException ex) {
            throw new IOException("AI服务标识无效。");
        }
        if (s.name.isBlank()
                || s.name.length() > 100
                || s.name.chars().anyMatch(Character::isISOControl))
            throw new IOException("请输入有效的服务名称（最多100字）。");
        if (s.model.isBlank()
                || s.model.length() > 256
                || s.model.chars().anyMatch(Character::isISOControl))
            throw new IOException("请输入有效的Model名称。");
        try {
            URI u = new URI(s.url);
            if (s.url.length() > 4096
                    || !("https".equalsIgnoreCase(u.getScheme())
                            || "http".equalsIgnoreCase(u.getScheme()))
                    || u.getHost() == null
                    || u.getRawUserInfo() != null
                    || u.getFragment() != null) throw new Exception();
            if (u.getRawQuery() != null)
                for (String part : u.getRawQuery().split("&")) {
                    String key =
                            java.net.URLDecoder.decode(
                                    part.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (Set.of(
                                    "key",
                                    "api_key",
                                    "apikey",
                                    "api-key",
                                    "token",
                                    "access_token",
                                    "secret")
                            .contains(key.toLowerCase(Locale.ROOT))) throw new Exception();
                }
        } catch (Exception ex) {
            throw new IOException("请填写完整HTTP/HTTPS URL；认证信息应放在Key字段，不放在URL中。");
        }
        if (!s.protectedKey.isEmpty())
            try {
                if (!s.protectedKey.startsWith(KEY_PREFIX) || s.protectedKey.length() > 45000)
                    throw new IllegalArgumentException();
                byte[] data =
                        Base64.getDecoder().decode(s.protectedKey.substring(KEY_PREFIX.length()));
                if (data.length < 32) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IOException("Key保护数据格式无效，请重新输入Key。");
            }
    }

    /** 清零传入字符数组与编码缓冲区，调用方不应复用该数组。 Consumes and wipes the supplied key array. */
    static String protect(char[] key) throws IOException {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(key));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            if (bytes.length == 0 || bytes.length > 8192) throw new IOException("Key长度须为1至8192字节。");
            byte[] cipher = transform("protect", bytes);
            try {
                return KEY_PREFIX + Base64.getEncoder().encodeToString(cipher);
            } finally {
                Arrays.fill(cipher, (byte) 0);
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
            Arrays.fill(key, '\0');
        }
    }

    /** 返回临时明文字节，由调用方使用后清零。 Caller owns and must wipe the returned plaintext bytes. */
    static byte[] unprotect(Service service) throws IOException {
        validate(service);
        if (!service.hasKey()) return new byte[0];
        byte[] cipher =
                Base64.getDecoder().decode(service.protectedKey.substring(KEY_PREFIX.length()));
        try {
            return transform("unprotect", cipher);
        } finally {
            Arrays.fill(cipher, (byte) 0);
        }
    }

    /**
     * 通过标准输入输出与本地保护程序交换字节，避免把 Key 放进命令行参数或临时文件。
     *
     * <p>Uses binary process streams so key material stays out of command arguments and temp files.
     */
    private static byte[] transform(String mode, byte[] input) throws IOException {
        Path helper = ToolPaths.resolve("tools/key-protector.exe");
        if (!Files.isRegularFile(helper)) throw new IOException("缺少Windows密钥保护组件，未保存明文Key。");
        Process process = new ProcessBuilder(helper.toString(), mode).start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<byte[]> reader = pool.submit(() -> process.getInputStream().readNBytes(32769));
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("密钥保护超时，未保存明文Key。");
            }
            byte[] result = reader.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || result.length == 0 || result.length > 32768) {
                Arrays.fill(result, (byte) 0);
                throw new IOException("当前Windows账户无法保护或读取该Key，请重新输入。");
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("密钥保护已中断。");
        } catch (ExecutionException | TimeoutException ex) {
            throw new IOException("密钥保护失败，未保存明文Key。");
        } finally {
            process.destroy();
            pool.shutdownNow();
        }
    }
}

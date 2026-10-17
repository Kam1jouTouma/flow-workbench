package course.workflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

public class AiServicesSmoke {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        var settings = SystemSettings.current();
        String dummy = "test-only-" + UUID.randomUUID();
        char[] chars = dummy.toCharArray();
        String ciphertext = AiServices.protect(chars);
        check(new String(chars).chars().allMatch(c -> c == 0), "input chars erased");
        var first =
                new AiServices.Service(
                        UUID.randomUUID().toString(),
                        "测试服务",
                        "https://example.invalid/v1/chat/completions?api-version=2026-01-01",
                        "test-model",
                        ciphertext);
        var local =
                new AiServices.Service(
                        UUID.randomUUID().toString(),
                        "本地服务",
                        "http://127.0.0.1:11434/v1/chat/completions",
                        "local-model",
                        "");
        settings.save(
                root.resolve("flows").toString(),
                root.resolve("runs").toString(),
                "",
                2,
                List.of(first, local));
        var loaded = AiServices.load(settings.load());
        check(
                loaded.equals(List.of(first, local)),
                "list persistence, exact URL and no-key service");
        byte[] decrypted = AiServices.unprotect(loaded.get(0));
        try {
            check(
                    Arrays.equals(decrypted, dummy.getBytes(StandardCharsets.UTF_8)),
                    "DPAPI same-user roundtrip");
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
        byte[] bytes = Files.readAllBytes(settings.file);
        check(
                !new String(bytes, StandardCharsets.ISO_8859_1).contains(dummy),
                "no plaintext key in file");
        check(
                !first.toString().contains(ciphertext) && !first.toString().contains(dummy),
                "safe service diagnostic representation");
        settings.save(root.resolve("flows").toString(), root.resolve("runs").toString(), "", 3);
        check(
                AiServices.load(settings.load()).equals(loaded),
                "unrelated settings retain services");
        boolean rejected = false;
        try {
            AiServices.validate(
                    new AiServices.Service(
                            first.id(),
                            first.name(),
                            "https://example.invalid/v1?api_key=" + dummy,
                            first.model(),
                            ciphertext));
        } catch (java.io.IOException e) {
            rejected = true;
            check(!e.getMessage().contains(dummy), "validation error does not echo URL secret");
        }
        check(rejected, "reject secret query parameter");
        rejected = false;
        try {
            AiServices.validate(
                    new AiServices.Service(
                            first.id(),
                            first.name(),
                            "https://user:password@example.invalid/",
                            first.model(),
                            ciphertext));
        } catch (java.io.IOException e) {
            rejected = true;
        }
        check(rejected, "reject embedded credentials");
        var modified =
                new AiServices.Service(
                        first.id(), "编辑后名称", first.url(), "changed-model", first.protectedKey());
        settings.save(
                root.resolve("flows").toString(),
                root.resolve("runs").toString(),
                "",
                3,
                List.of(modified));
        check(
                AiServices.load(settings.load()).get(0).protectedKey().equals(ciphertext),
                "edit metadata preserves protected key");
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        var editor = new WorkflowApp.Editor();
                        editor.folderSettings = settings.file;
                        var panel = new SystemSettingsPanel(editor);
                        check(panel.ai.table.getRowCount() == 1, "UI list restored");
                        check(panel.ai.table.getValueAt(0, 3).equals("已保护"), "UI masks key");
                        panel.ai.services.clear();
                        panel.ai.reload();
                        panel.reload();
                        check(
                                panel.ai.services.size() == 1,
                                "cancel/reload discards unsaved list deletion");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        var cleared =
                new AiServices.Service(
                        modified.id(), modified.name(), modified.url(), modified.model(), "");
        settings.save(
                root.resolve("flows").toString(),
                root.resolve("runs").toString(),
                "",
                3,
                List.of(cleared));
        check(!AiServices.load(settings.load()).get(0).hasKey(), "clear key persists");
        var corrupted =
                new AiServices.Service(
                        first.id(),
                        first.name(),
                        first.url(),
                        first.model(),
                        AiServices.KEY_PREFIX + Base64.getEncoder().encodeToString(new byte[128]));
        rejected = false;
        try {
            byte[] bad = AiServices.unprotect(corrupted);
            Arrays.fill(bad, (byte) 0);
        } catch (java.io.IOException e) {
            rejected = true;
            check(!e.getMessage().contains(dummy), "cryptographic error redacted");
        }
        check(rejected, "invalid ciphertext fails closed");
        settings.save(
                root.resolve("flows").toString(),
                root.resolve("runs").toString(),
                "",
                3,
                List.of());
        check(AiServices.load(settings.load()).isEmpty(), "delete services persists");
        System.out.println(
                "PASS: DPAPI encrypt/decrypt, no plaintext at rest, erased chars, exact URL list persistence, UI masking/reload, preserve/clear key, delete, validation and corrupt ciphertext fail closed.");
    }
}

package course.workflow;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Properties;

/** 以工具自身 JAR 为基准寻找配置和依赖。 / Resolves files beside the standalone tool JAR. */
final class ToolPaths {
    private ToolPaths() {}

    static Path root() throws IOException {
        try {
            return Path.of(
                            ToolPaths.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                    .getParent();
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
    }

    static Path resolve(String path) throws IOException {
        return root().resolve(path).normalize();
    }

    /** 与宿主一致：缺少设置文件时使用空配置。 / A missing settings file yields empty properties. */
    static Properties loadSettings() throws IOException {
        Properties properties = new Properties();
        Path settings = resolve("user-settings.properties");
        if (Files.isRegularFile(settings))
            try (InputStream input = Files.newInputStream(settings)) {
                properties.load(input);
            }
        return properties;
    }
}

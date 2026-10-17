package course.workflow;

import java.util.*;

/**
 * 集中列出可执行工作元素及内置工具身份；汇合和自定义脚本由执行器单独分派。
 *
 * <p>Maps built-in work kinds to CLI tools; merge and custom-script nodes have dedicated dispatch.
 */
final class ExecutableTools {
    static final Set<WorkflowApp.Kind> WORK =
            Set.of(
                    WorkflowApp.Kind.SCRIPT,
                    WorkflowApp.Kind.AI,
                    WorkflowApp.Kind.FILE_MERGE,
                    WorkflowApp.Kind.PDF,
                    WorkflowApp.Kind.RENAME,
                    WorkflowApp.Kind.JAVA_BUILD,
                    WorkflowApp.Kind.AUDIO,
                    WorkflowApp.Kind.EXCEL_MERGE);

    /** 路径相对于程序目录，插件进程不依赖用户当前工作目录。 / Resolve bundled tool locations. */
    static String file(WorkflowApp.Kind kind) {
        return switch (kind) {
            case AI -> "ai-text-tool-v1.jar";
            case JAVA_BUILD -> "tools/java-build-tool.bat";
            case AUDIO -> "tools/audio-extract-tool.exe";
            case EXCEL_MERGE -> "tools/excel-merge-tool.py";
            case PDF -> "pdf-tool-v1.jar";
            case RENAME -> "rename-tool-v1.jar";
            default -> throw new IllegalArgumentException("Not a work tool");
        };
    }

    static String id(WorkflowApp.Kind kind) {
        return switch (kind) {
            case AI -> "ai.text";
            case JAVA_BUILD -> "build.java_jar";
            case AUDIO -> "media.extract_audio";
            case EXCEL_MERGE -> "table.merge_excel";
            case PDF -> "batch.pdf";
            case RENAME -> "batch.rename";
            default -> throw new IllegalArgumentException("Not a work tool");
        };
    }

    static List<String> all() {
        return List.of(
                "ai-text-tool-v1.jar",
                "file-input-tool-v1.jar",
                "file-output-tool-v1.jar",
                "pdf-tool-v1.jar",
                "rename-tool-v1.jar",
                "tools/java-build-tool.bat",
                "tools/audio-extract-tool.exe",
                "tools/excel-merge-tool.py");
    }

    static final Set<String> MEDIA =
            Set.of("mp4", "mkv", "mov", "avi", "webm", "mp3", "wav", "m4a", "flac", "aac", "ogg");
}

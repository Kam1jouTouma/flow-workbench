# 源码阅读与维护

## 建议阅读顺序

1. `WorkflowApp`：从 `Kind`、`Node`、`Flow` 数据结构开始，再看编辑器和画布。
2. `BatchRunner`：图展开、依赖调度、缓存、工具调用与记录。
3. `BatchPublication`：统一输出预检、来源追踪、发布和失败补偿。
4. `FileCli` 与独立 Java 工具：CLI 参数、请求和结果的协议。
5. `Subgraphs`：命名接口如何展开为隔离的运行节点。
6. `AgentConversation`、`WorkflowMcp`、`AgentDialog`：模型建议、受限工具和人工应用之间的边界。

源码中的双语注释重点解释职责、不变量及设计原因，不逐行翻译代码。修改行为时同步检查注释和相关说明。

## 格式约定

Java 使用 google-java-format 的 AOSP 风格（四空格），保留长字符串内容；Python 使用 Black，行宽 100；C# 使用根目录 `.clang-format`。不格式化第三方依赖。PowerShell 保存为 UTF-8 BOM，兼容 Windows PowerShell 5.1 的中文读取。

本地 Java 格式化：`powershell -NoProfile -ExecutionPolicy Bypass -File .\format.ps1`；校验模式追加 `-Check`。首次运行需要联网下载固定版本格式化器，文件下载到忽略目录 `work/format-tools` 并校验 SHA-256。格式化器本身要求 JDK 21 或更高；应用编译目标仍是 Java 17。

不要为了“可读性”更改字符串、条件、执行顺序、错误码或公开协议。修改执行器应使用隔离样本和发行目录布局回归，详见 `tests/README.txt`。

## 文档与交付边界

`development-logs/` 仅用于本地详细开发记录，不进入 GitHub 源码包或安装包。公开变更摘要写入 `RELEASE_NOTES.md`。个人配置、密钥、运行历史、临时测试数据和课程身份材料均不进入公开包。

课程材料由独立交付目录维护，包含说明书、阶段报告、演示数据和脱敏的开发过程说明；原始日志仍留在工作区。提交前检查文件清单，而不仅依赖 `.gitignore`。

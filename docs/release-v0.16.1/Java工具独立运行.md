# Java 工具独立运行

Java 的可执行入口是带 `Main-Class` 清单的 JAR。参数放在 JAR 文件名后，例如 `java -jar rename-tool-v1.jar --help`。这与 Python 解释器运行 `.py`、系统运行 `.exe` 一样，都是独立进程。它不需要打开 Flow 界面。

本次交付将源码分为宿主 `src/main/java`、公共协议 `src/shared/java`、Java 工具 `src/tools/java`。`build.ps1` 分别编译五个工具，工具的编译类路径没有宿主源码或宿主 classes；每个工具 JAR 不含 `WorkflowApp`、`BatchRunner`、配置面板或 MCP 类。主 JAR 也不再内嵌这五个工具实现。

| 入口 | 用途 | 单独复制时的配套文件 |
| --- | --- | --- |
| file-input-tool-v1.jar | 文件选择与快照 | 仅 JAR 和 Java 17+ |
| file-output-tool-v1.jar | 输出工件暂存 | 仅 JAR 和 Java 17+ |
| rename-tool-v1.jar | 模板和 CSV 改名 | 仅 JAR 和 Java 17+ |
| pdf-tool-v1.jar | 原生格式和 Office 转 PDF | `lib/pdfbox-app-3.0.8.jar`、`fonts/NotoSansSC.ttf`；Office 转换另需 `tools/office-to-pdf.ps1` 和本机 Office/LibreOffice |
| ai-text-tool-v1.jar | AI 文本处理 | `tools/key-protector.exe`、当前 Windows 用户自行配置的 `user-settings.properties`，以及可用 AI 服务 |

目录都相对于对应 JAR 所在位置。不要把个人设置或 Key 发给别人；换电脑或账户后重新配置 AI 服务。主程序正常发行目录也必须保留全部 JAR 与依赖。

```powershell
java -jar rename-tool-v1.jar --help
java -jar rename-tool-v1.jar --describe
java -jar rename-tool-v1.jar --capabilities
java -jar rename-tool-v1.jar --version
java -jar rename-tool-v1.jar --input <绝对请求路径> --config <绝对参数路径> --output <绝对空暂存目录>
```

请求和结果格式见工具协议 v1 和脚本适配教程。配置路径采用绝对路径，是**运行时调用协议**的要求；发行包和模板不会预写开发者机器上的绝对路径。程序依据本机选择生成请求，课程演示脚本依据解压位置生成流程。

输出工具独立运行时只写给定暂存目录。最终复制、覆盖、移动删源和失败补偿由宿主统一发布，避免一个分支提前破坏原文件。虚拟输入/输出、汇合和子图属于图引擎机制，按设计由宿主处理。

独立不代表沙箱隔离。工具进程拥有当前用户的文件权限；应只运行可信脚本。现有内置 Java 元素使用预定义侧栏配置，而导入的通用脚本使用协议参数配置；执行边界均为 CLI。

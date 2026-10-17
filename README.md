# Flow 工作流

Windows 桌面可视化批处理工具，使用 Java Swing 编写。将输入、处理和输出元素连接成可复用流程，也可以让 AI 流程助手通过 MCP 生成草稿。

当前版本：**v0.16.1 预览版（2026-10-17 交付整理）**，适用于 Windows x64。

## 下载与启动

在 GitHub Releases 下载 `FlowWorkbench-Setup-0.16.1.exe` 安装，或解压 `FlowWorkbench-0.16.1-windows-x64.zip` 后双击 `launch.cmd`。内置 Java 21、Python 3.12 和中文字体，无需单独安装 Java/Python。

安装程序无需管理员权限。安装文件尚未进行代码签名；可用发布附件中的 SHA256SUMS.txt 校验完整性。解压后请保留完整目录结构。

## 主要功能

- 文件输入、类型筛选、复制/移动输出、完成后打开目标文件夹。
- 文本/图片/已有 PDF 转 PDF；逐文件生成或自然排序合并。
- 模板和 CSV 字段批量改名、Excel 合并、Java 编译打包、音频提取。
- 多输入、多输出、并行分支、失败时回滚输出；子图封装及逐级画布。
- 固定流程库、导入、元素能力查询与文件类型搜索。
- 系统设置及 AI 服务列表；完整 URL、model 和 Windows DPAPI 保护的 Key。
- AI 文本处理节点；流程助手调用 MCP 查询、搭建、配置及校验草稿。

## 快速体验

1. 启动后点击“打开”，选择预置流程 01～08，先执行随包样本。
2. 在侧边栏选择输入路径、输出位置及元素参数，点击“执行”。
3. 中键平移、Shift + 滚轮缩放，右键节点编辑，双击子图进入下一级。
4. 在“设置 → AI 服务”填写自己的服务，使用 AI 节点或“流程助手”。

详细说明：包内 `manual.html`；[发布使用说明](docs/release-v0.16.1/使用与开发说明.txt)；[AI 文本工具](docs/release-v0.16.1/AI文本工具v1使用说明.txt)；[流程助手与 MCP](docs/release-v0.16.1/流程助手与MCP使用说明v1.txt)。

## 当前限制

- Office 文档转 PDF 需要安装 Microsoft Office 或 LibreOffice；音频需另装可选 FFmpeg 组件。
- 自定义 exe/py/bat/cmd/jar 可导入脚本库并作为可执行FILE节点；需声明 data(FILE) 与 source(JSON) 两个输入输出端口。
- 流程助手仅支持基础元素建图，不生成子图。草稿需用户应用，真实文件处理仍需点击“执行”。
- `--mcp-stdio` 创建独立草稿会话，不远程操纵已打开的窗口；旧 MCP 设计文档中的执行接口不代表当前实现。
- AI 服务必须支持兼容 Chat Completions 的请求格式；流程助手还需要 function calling。max token 限制的是模型输出。
- 目前只验证 Windows x64；程序运行目录需要当前用户写入权限。
- 请使用较短的安装、解压和工作目录。过深的目录层级可能触发 Windows 子进程工作目录长度限制，导致工具启动失败（CreateProcess error=267）；当前版本未完整支持超长路径。
- 英文 Windows 的 Java 启动器可能无法接收中文绝对路径。建议程序及工作目录使用较短的英文路径；单独运行位于中文目录的工具时，先进入工具目录，再用 `java -jar file-input-tool-v1.jar --help` 这样的相对 JAR 名启动。该方式不代表所有非系统代码页命令行参数均已兼容。

## 从源码构建

安装 Windows JDK 17 或更高版本、设置 JAVA_HOME，并启用系统 .NET Framework 4.x。依赖 PDFBox 和 Python 库已附带，许可见 `licenses/` 与依赖自身目录。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
.\launch.cmd
java -Djava.awt.headless=true -jar workflow-ui-v0.16.1.jar --verify work/verify
```

源码的 `packaging/assets` 包含样本、脚本模板和字体。源码直接启动不会自动初始化预置流程，可在输入节点选择这些样本；完整预置流程请从发行包体验。

重新制作发行包：先构建，再执行 `python packaging/release.py --runtime-root <包含java和python子目录的运行时目录> --output <全新输出目录>`。可从已验证便携包提取 runtime 目录；保留 Java legal 和 Python LICENSE。不要将个人设置或数据打包。

## 目录与开发

`src/main/java/course/workflow` 为宿主、界面及内置回归入口；`src/shared/java` 为不依赖界面的公共协议和工具辅助代码；`src/tools/java` 为五个独立 Java 工具及其辅助类；`tools` 为 Python、批处理和原生工具。`packaging` 包含安装器和打包脚本，`tests` 包含额外验收程序。详细开发日志只保存在本地 `development-logs/`，不进入公开源码包或安装包。

五个 Java 工具分别构建为可直接 `java -jar` 调用的独立 JAR，不含主程序/UI/执行器类；主程序也不再内嵌这五个工具实现，通过外部 JAR 链接和 CLI 子进程使用它们。依赖和单独运行方法见 [Java 工具独立运行](docs/release-v0.16.1/Java工具独立运行.md)。阅读顺序和格式约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

当前已知问题：输出格式应使用 KEEP；TXT/JSON/CSV 转换选项尚不能从主执行入口运行。自定义脚本的无默认值必填参数、部分参数约束和损坏能力数据处理仍需改进；大日志可能消耗过多内存；可选音频组件下载中断的恢复仍不完善。这些问题没有因源码排版而自动解决。

反馈问题时请提供版本、复现步骤和脱敏样本，不要提交 Key、个人配置、真实文档或完整运行历史。许可范围见 [LICENSE.txt](LICENSE.txt)。

新增：程序内“导入脚本”和“脚本教程”；安装版提供“卸载程序.cmd”及 Windows 已安装应用入口。卸载保留用户数据，移除此版本的 AI 服务配置。

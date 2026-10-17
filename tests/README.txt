验收测试使用独立的临时程序目录，不要针对自己的日常安装或个人设置运行。

内置测试可在复制的发行包目录中执行：
runtime/java/bin/java.exe -Djava.awt.headless=true -jar workflow-ui-v0.16.1.jar --verify <全新测试目录>
runtime/java/bin/java.exe -Djava.awt.headless=true -jar workflow-ui-v0.16.1.jar --test-graph-native <全新测试目录>
runtime/java/bin/java.exe -Djava.awt.headless=true -jar workflow-ui-v0.16.1.jar --test-batch-native <全新测试目录>

--test-graph-native 不依赖 Office。完整 --test-graph-execution 额外需要本机 Office/LibreOffice 和程序目录/test-fixtures/word.docx；缺少条件会失败，不会静默算作通过。

PackageSmoke.java 运行初始化后的 01～08 示例，会在样本数据目录生成输出。09 音频依赖可选组件。
AgentMcpSmoke.java 使用本机模拟 HTTP 服务，需要完整 JDK 的 jdk.httpserver 模块，不请求外部 API。
AiPipelineSmoke.java 和 AiServicesSmoke.java 会写入对应程序 JAR 所在目录的 user-settings.properties，只能对隔离副本运行；它们使用随机假 Key，不要复制个人配置进去。参数目录必须与隔离 JAR 所在目录相同。
OpenFoldersSmoke.java 校验打开目录配置、去重和结果列表，不实际弹出资源管理器。

编译额外测试时使用 --release 17 -encoding UTF-8 -cp <隔离目录/workflow-ui-v0.16.1.jar> -d <测试class目录>。
运行时 classpath 使用隔离 JAR 与测试 class 目录，不能只使用 build/classes，否则工具路径定位不正确。


2026-10-17 新增：Java 工具独立性与课程模板
- python tests/standalone_tools_smoke.py --java <java.exe> --output <全新目录>
  仅使用合成输入；将每个 JAR 放入独立目录，验证20次协议查询、4步真实CLI和主/工具类边界。
  中文工具目录使用 cwd + 相对 JAR 名启动；中文源文件路径通过 UTF-8 JSON 传递。
  这不覆盖英文 Windows 原生 Java 启动器接收中文绝对命令行参数的限制。
- CourseDemoSmoke.java：在专用隔离应用目录运行，用本机HTTP mock和随机假Key验证课程CSV模板。
  目录必须有内容为 FLOW_COURSE_DEMO_SMOKE_V1 的 .course-demo-smoke.marker；没有已有配置或安装指针。
  需要复制 examples/course、tools/key-protector.exe、主程序及五个工具JAR和lib；只证明链路，不证明模型算术。
- CourseTemplatesSmoke.java：先运行 examples/course/prepare-demo.ps1 -OutputDirectory <全新目录>，
  再以生成目录为参数运行测试，验证离线模板的PDF正文与原文件保留。建议目录包含中文、空格和&。
- 源码现分 src/main/java、src/shared/java、src/tools/java，请用根目录 build.ps1 统一构建。
  不要只手工编译旧src/main/java目录；主程序JAR须和五个独立工具JAR位于同一目录。

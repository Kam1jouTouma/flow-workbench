# 工具规范 v1 配套文件

`工具规范v1.md` 是工作元素的调用与数据交换规范。它定义查询和执行入口、工具描述、参数、文件载荷、具名端口、单项与集合、结果和错误处理。

## 文件用途

- `schemas/common.schema.json` 定义公共端口、参数和数据项结构。
- `schemas/descriptor.schema.json` 定义 `--describe` 输出。
- `schemas/input.schema.json` 定义 `--input` 文件。
- `schemas/config.schema.json` 定义 `--config` 文件。
- `schemas/result.schema.json` 定义输出目录中的 `result.json`。
- `schemas/error.schema.json` 定义可选的 `error.json`。
- `examples/` 提供一个文本替换的描述、输入、配置、成功清单、失败清单和对应文本。

示例是静态协议样例，成功与失败清单用于展示两种情况，不表示一次调用同时产生两种状态。`examples/` 在此作为示例输出根目录，成功清单指向其中的 `payloads/replaced.txt`。没有交付文本替换工具程序，也没有实现执行引擎。

## 样例检查

使用 Python 3.10 或更高版本运行：

```text
python check_examples.py
```

检查脚本只依赖 Python 标准库，检查这些 schema 使用到的结构子集，并补充参数默认值、端口数量、文件路径和载荷内容等规则。它不是完整 JSON Schema 2020-12 校验器，也不运行工具命令。生产实现还需要依据规范检查完整协议、执行过程和文件系统。

输入样例使用本目录实际文件的绝对路径。移动整套文件后，应将 `examples/input.json` 中的 `path` 更新为新位置的 `payloads/source.txt`，再执行检查；相对结果路径无需修改。

## 后续实现顺序

先实现共用协议对象和校验，随后提供 Python 与 Java 工具模板，再接入 Java 平台的发现和进程执行。首条实际流程采用文本读取、文本替换和结果保存，依据规范逐项验收。

"""Standalone v1 tool: copy payloads with a configurable filename prefix."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import shutil
from cli_v1 import main, inputs, dump, sha, description_port, port

DESCRIPTION = {
    "protocolVersion": 1,
    "id": "user.copy_demo",
    "name": "用户复制示例",
    "version": "1.0.0",
    "description": "将收到的文件复制到暂存结果，添加prefix前缀；不修改或删除原文件。",
    "inputs": [description_port("data"), description_port("source", "JSON", "SINGLE")],
    "outputs": [description_port("data"), description_port("source", "JSON", "SINGLE")],
    "parameters": [
        {
            "name": "prefix",
            "label": "文件名前缀",
            "type": "string",
            "default": "copy_",
            "required": False,
        }
    ],
}
CAPABILITY = {
    "capabilityVersion": 1,
    "toolId": DESCRIPTION["id"],
    "toolVersion": "1.0.0",
    "implementation": "AVAILABLE",
    "operation": "PRESERVE",
    "inputs": [port("data", ["*"]), port("source", ["json"], "JSON", "SINGLE")],
    "outputs": [port("data", ["*"]), port("source", ["json"], "JSON", "SINGLE")],
    "knownExtensions": [],
}
# Preservation tools accept opaque files: they do not claim to decode their content.
CAPABILITY["inputs"][0]["validation"] = "OPAQUE"
CAPABILITY["outputs"][0]["validation"] = "OPAQUE"


def execute(request, parameters, output):
    if set(parameters) - {"prefix"}:
        raise ValueError("Unknown parameter")
    prefix = parameters.get("prefix", "copy_")
    if not isinstance(prefix, str) or any(c in prefix for c in '\\/:*?"<>|'):
        raise ValueError("Invalid prefix")
    items = []
    sources = []
    for index, (path, source) in enumerate(inputs(request)):
        relative = prefix + Path(source["relativePath"]).name
        artifact = "payloads/" + str(index) + "/" + relative
        target = output / artifact
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        source["originals"] = source.get(
            "originals", [{"path": source["originalPath"], "sha256": source["sha256"]}]
        )
        source.update(relativePath=relative, sha256=sha(target))
        sources.append(source)
        items.append({"path": artifact, "mediaType": "application/octet-stream"})
    dump({"files": sources}, output / "source.json")
    dump(
        {
            "protocolVersion": 1,
            "runId": request["runId"],
            "nodeId": request["nodeId"],
            "outputs": {
                "data": {"type": "FILE", "items": items},
                "source": {
                    "type": "JSON",
                    "items": [{"path": "source.json", "mediaType": "application/json"}],
                },
            },
        },
        output / "result.json",
    )


if __name__ == "__main__":
    main(DESCRIPTION, CAPABILITY, execute)

"""Verify Java tools without the application JAR, including a real file chain.

Usage: python tests/standalone_tools_smoke.py --java PATH --output NEW_DIRECTORY
Only synthetic files are used. / 仅使用合成文件，不访问真实服务或用户设置。
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    java = str(args.java.resolve())
    tools = {}
    checks = []
    for name in ("file-input", "file-output", "rename", "pdf", "ai-text"):
        folder = destination / (name + " 独立目录")
        folder.mkdir()
        jar = folder / (name + "-tool-v1.jar")
        shutil.copy2(root / jar.name, jar)
        if name == "pdf":
            shutil.copytree(root / "lib", folder / "lib")
            shutil.copytree(root / "packaging/assets/fonts", folder / "fonts")
        with zipfile.ZipFile(jar) as archive:
            entries = archive.namelist()
            assert not any(
                any(token in item for token in ("WorkflowApp", "Runner", "Panel", "Dialog"))
                for item in entries
            ), "Host code leaked into standalone JAR"
        tools[name] = jar
        for flag in ("--help", "--describe", "--capabilities", "--version"):
            result = subprocess.run(
                [java, "-jar", str(jar), flag],
                cwd=destination,
                capture_output=True,
                encoding="utf-8",
                check=True,
                timeout=30,
            )
            assert result.stdout.strip(), "Metadata response is empty"
            if flag in ("--describe", "--capabilities"):
                assert json.loads(result.stdout)["id" if flag == "--describe" else "toolId"]
            checks.append(name + " " + flag)

    # Every output becomes the next request using absolute artifact paths.
    # 按真实 CLI 结果连接工具，而不是调用工具的 Java 内部方法。
    def run(name, parameters, incoming):
        stage = destination / ("stage-" + name)
        stage.mkdir()
        request = destination / (name + "-input.json")
        config = destination / (name + "-config.json")
        request.write_text(
            json.dumps(
                {
                    "protocolVersion": 1,
                    "runId": "standalone-smoke",
                    "nodeId": name,
                    "inputs": incoming,
                }
            ),
            encoding="utf-8",
        )
        config.write_text(
            json.dumps({"protocolVersion": 1, "parameters": parameters}), encoding="utf-8"
        )
        subprocess.run(
            [
                java,
                "-jar",
                str(tools[name]),
                "--input",
                str(request),
                "--config",
                str(config),
                "--output",
                str(stage),
            ],
            cwd=destination,
            capture_output=True,
            check=True,
            timeout=60,
        )
        ports = json.loads((stage / "result.json").read_text(encoding="utf-8"))["outputs"]
        for port in ports.values():
            for item in port["items"]:
                artifact = (stage / item["path"]).resolve()
                assert artifact.is_relative_to(stage)
                assert artifact.is_file()
                item["path"] = str(artifact)
        checks.append(name + " real CLI execution")
        return ports

    source = destination / "synthetic.txt"
    source.write_text("Standalone Java tool chain\nTotal: 1750.50\n", encoding="utf-8")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    ports = run(
        "file-input",
        {
            "sourcePath": str(source),
            "selectionMode": "SINGLE",
            "extensions": "txt",
            "recursive": False,
        },
        {},
    )
    ports = run("rename", {"template": "report_{x}"}, ports)
    assert Path(ports["data"]["items"][0]["path"]).name == "report_1.txt"
    ports = run("pdf", {"pdfMode": "EACH", "encoding": "UTF-8"}, ports)
    ports = run("file-output", {}, ports)
    result_file = Path(ports["data"]["items"][0]["path"])
    assert result_file.name == "report_1.pdf"
    assert result_file.read_bytes().startswith(b"%PDF-")
    assert hashlib.sha256(source.read_bytes()).hexdigest() == source_hash
    with zipfile.ZipFile(root / "workflow-ui-v0.16.1.jar") as archive:
        entries = archive.namelist()
        for name in ("FileInputTool", "FileOutputTool", "PdfTool", "RenameTool", "AiTextTool"):
            assert not any(
                item.split("/")[-1].split("$")[0].split(".")[0] == name for item in entries
            )
    report = {
        "status": "PASS",
        "checks": checks,
        "hostClassesAbsent": True,
        "sourcePreserved": True,
        "aiScope": "metadata only; mock pipeline tested separately",
    }
    (destination / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("PASS: 20 metadata queries, 4 real CLI steps, host/tool class separation.")


if __name__ == "__main__":
    main()

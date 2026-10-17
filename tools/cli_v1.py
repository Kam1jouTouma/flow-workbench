# 统一处理协议信封、来源清单和结果路径。 / Shared CLI envelopes and provenance helpers.
"""Small v1 transport helper; independent tools never publish or delete originals."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


def dump(value, path=None):
    text = json.dumps(value, ensure_ascii=False, allow_nan=False)
    if path is None:
        print(text)
    else:
        Path(path).write_text(text, encoding="utf-8")


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def natural(name):
    return tuple((1, int(x)) if x.isdigit() else (0, x.lower()) for x in re.split(r"(\d+)", name))


def inputs(request):
    ports = request["inputs"]
    if ports["data"]["type"] != "FILE" or ports["source"]["type"] != "JSON":
        raise ValueError("Expected FILE data and JSON source ports")
    metadata = ports["source"]["items"]
    if len(metadata) != 1:
        raise ValueError("Expected one source manifest")
    sources = load(metadata[0]["path"])["files"]
    items = ports["data"]["items"]
    if len(items) != len(sources) or not items:
        raise ValueError("Data/source cardinality mismatch")
    files = [(Path(item["path"]), dict(source)) for item, source in zip(items, sources)]
    for path, source in files:
        if (
            not path.is_absolute()
            or not path.is_file()
            or not Path(source["originalPath"]).is_absolute()
        ):
            raise ValueError("Input paths must be absolute files")
        if sha(path) != source["sha256"]:
            raise ValueError("Input snapshot hash mismatch")
    return sorted(files, key=lambda f: (natural(f[1]["relativePath"]), f[1]["relativePath"]))


def result(request, output, filename, files, warnings=()):
    output = Path(output)
    origins = []
    for path, source in files:
        origins.extend(
            source.get("originals", [{"path": source["originalPath"], "sha256": source["sha256"]}])
        )
    source = dict(files[0][1])
    source.update(
        relativePath=filename, sha256=sha(output / "payloads" / filename), originals=origins
    )
    dump({"files": [source]}, output / "source.json")
    dump({"warnings": list(warnings)}, output / "warnings.json")
    dump(
        {
            "protocolVersion": 1,
            "runId": request["runId"],
            "nodeId": request["nodeId"],
            "outputs": {
                "data": {
                    "type": "FILE",
                    "items": [
                        {"path": "payloads/" + filename, "mediaType": "application/octet-stream"}
                    ],
                },
                "source": {
                    "type": "JSON",
                    "items": [{"path": "source.json", "mediaType": "application/json"}],
                },
            },
        },
        output / "result.json",
    )


def main(description, capability, execute):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    if len(sys.argv) == 2:
        query = sys.argv[1]
        if query in ("--help", "-h"):
            print(
                description["name"]
                + "\n"
                + description["description"]
                + "\n--describe | --capabilities | --version | --input <JSON> --config <JSON> --output <empty directory>"
            )
            return
        if query == "--describe":
            dump(description)
            return
        if query == "--capabilities":
            dump(capability)
            return
        if query == "--version":
            print(description["version"])
            return
    parser = argparse.ArgumentParser(add_help=False)
    for key in ("input", "config", "output"):
        parser.add_argument("--" + key, required=True)
    if len(sys.argv) != 7 or len(set(sys.argv[1::2])) != 3:
        parser.error("Provide input/config/output exactly once")
    args = parser.parse_args()
    try:
        for path in (args.input, args.config, args.output):
            if not Path(path).is_absolute():
                raise ValueError("Paths must be absolute")
        output = Path(args.output)
        if not output.is_dir() or any(output.iterdir()):
            raise ValueError("Output directory must be empty")
        request, config = load(args.input), load(args.config)
        if set(request) != {"protocolVersion", "runId", "nodeId", "inputs"} or set(config) != {
            "protocolVersion",
            "parameters",
        }:
            raise ValueError("Unknown protocol fields")
        if request["protocolVersion"] != 1 or config["protocolVersion"] != 1:
            raise ValueError("Unsupported protocol version")
        execute(request, config["parameters"], output)
        print("Completed: " + description["id"])
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(4)


def port(name, formats, kind="FILE", cardinality="COLLECTION"):
    return {
        "name": name,
        "dataTypes": [kind],
        "cardinality": cardinality,
        "formats": formats,
        "validation": "IDENTIFY",
    }


def description_port(name, kind="FILE", cardinality="COLLECTION"):
    return {
        "name": name,
        "label": name,
        "types": [kind],
        "cardinality": cardinality,
        "required": True,
    }

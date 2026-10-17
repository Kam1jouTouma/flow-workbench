#!/usr/bin/env python3
"""Create separate public source and course hand-in packages.

Usage:
  python packaging/deliver.py --release-dir PATH --output NEW_DELIVERY_DIR \
    --student-id ID --student-name NAME [--private-path-fragment FRAGMENT]

The release directory must already contain FlowWorkbench-Setup-0.16.1.exe and
FlowWorkbench-0.16.1-windows-x64.zip. If it is output/GitHub, those large files
stay there and are not copied elsewhere.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.16.1"
GITHUB_SOURCE_NAME = f"FlowWorkbench-{VERSION}-source"
SETUP_NAME = f"FlowWorkbench-Setup-{VERSION}.exe"
PORTABLE_NAME = f"FlowWorkbench-{VERSION}-windows-x64.zip"

# Keep this allowlist explicit: private checkout directories must never be
# reached by walking the repository root. / 白名单必须精确，绝不遍历整个仓库。
ROOT_FILES = (
    "README.md",
    "RELEASE_NOTES.md",
    "LICENSE.txt",
    "SECURITY.md",
    "CONTRIBUTING.md",
    ".gitignore",
    ".editorconfig",
    ".clang-format",
    "pyproject.toml",
    "build.ps1",
    "format.ps1",
    "java-runtime.ps1",
    "launch.ps1",
    "launch.cmd",
    "启动界面.cmd",
    "重新构建.cmd",
)
PUBLIC_DIRS = ("src", "tests", "tools", "lib", "licenses", ".github", "packaging")
PUBLIC_DOC_DIRS = ("docs/tool-protocol-v1", "docs/release-v0.16.1")
TEXT_SUFFIXES = {
    ".bat",
    ".cmd",
    ".conf",
    ".cpp",
    ".cs",
    ".css",
    ".csv",
    ".html",
    ".java",
    ".js",
    ".json",
    ".md",
    ".properties",
    ".ps1",
    ".py",
    ".sh",
    ".sql",
    ".svg",
    ".toml",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
CACHE_DIR_NAMES = {
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".cache",
    ".tox",
    ".venv",
    "venv",
    "prepared",
}
PRIVATE_FILE_NAMES = {".env", ".env.local", "user-settings.properties", "install-data.path"}
EXCLUDED_DIR_NAMES = {"development-logs", "run-history", "work", "build"}


class DeliveryError(RuntimeError):
    """A validation error that should stop delivery generation."""


def inside(path: Path, parent: Path) -> bool:
    """Return whether path is parent or one of its descendants."""
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def check_no_link(path: Path) -> None:
    """Refuse symlinks so a listed source cannot escape its approved tree."""
    if path.is_symlink():
        raise DeliveryError(f"拒绝复制符号链接 / Refusing symlink: {path}")


def ignored_directory(name: str) -> bool:
    lower = name.casefold()
    if lower in CACHE_DIR_NAMES or lower in EXCLUDED_DIR_NAMES or lower in {".git", ".svn"}:
        return True
    return False


def ignored_file(path: Path, *, tools_tree: bool = False) -> bool:
    lower = path.name.casefold()
    if lower in PRIVATE_FILE_NAMES or "user-settings" in lower:
        return True
    if path.suffix.casefold() in {".pyc", ".pyo"}:
        return True
    if tools_tree and path.suffix.casefold() == ".exe":
        return True
    if lower.endswith(".log") or ".log." in lower:
        return True
    return False


def copy_filtered_tree(
    source: Path,
    destination: Path,
    *,
    tools_tree: bool = False,
) -> int:
    """Copy one allowlisted tree while pruning caches and private artifacts."""
    check_no_link(source)
    if not source.is_dir():
        raise DeliveryError(f"缺少目录 / Missing directory: {source}")
    destination.mkdir(parents=True, exist_ok=False)
    copied = 0
    for current, dirnames, filenames in os.walk(source, topdown=True, followlinks=False):
        current_path = Path(current)
        kept_dirs: list[str] = []
        for name in sorted(dirnames):
            item = current_path / name
            check_no_link(item)
            if not ignored_directory(name):
                kept_dirs.append(name)
        dirnames[:] = kept_dirs

        relative_dir = current_path.relative_to(source)
        target_dir = destination / relative_dir
        target_dir.mkdir(parents=True, exist_ok=True)
        for name in sorted(filenames):
            item = current_path / name
            check_no_link(item)
            if ignored_file(item, tools_tree=tools_tree):
                continue
            if not item.is_file():
                continue
            target = target_dir / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)
            copied += 1
    return copied


def copy_public_source(destination: Path) -> int:
    """Copy only the public source allowlist into destination."""
    destination.mkdir(parents=True, exist_ok=False)
    count = 0
    for name in PUBLIC_DIRS:
        source = ROOT / name
        check_no_link(source)
        if not source.is_dir():
            raise DeliveryError(f"缺少源码目录 / Missing source directory: {source}")
        count += copy_filtered_tree(
            source,
            destination / name,
            tools_tree=(name == "tools"),
        )

    for relative in PUBLIC_DOC_DIRS:
        source = ROOT / Path(relative)
        if not source.is_dir():
            raise DeliveryError(f"缺少公开文档目录 / Missing public docs: {source}")
        count += copy_filtered_tree(source, destination / Path(relative))

    count += copy_filtered_tree(
        ROOT / "examples/course",
        destination / "examples/course",
    )

    for name in ROOT_FILES:
        source = ROOT / name
        check_no_link(source)
        if not source.is_file():
            raise DeliveryError(f"缺少公开根文件 / Missing public root file: {source}")
        shutil.copy2(source, destination / name)
        count += 1
    return count


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def zip_tree(folder: Path, archive_path: Path) -> None:
    """Write folder under its own top-level name and validate the ZIP."""
    with zipfile.ZipFile(
        archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6
    ) as archive:
        for path in sorted(folder.rglob("*")):
            check_no_link(path)
            if path.is_file():
                archive.write(path, (Path(folder.name) / path.relative_to(folder)).as_posix())
    with zipfile.ZipFile(archive_path, "r") as archive:
        bad_member = archive.testzip()
        if bad_member is not None:
            raise DeliveryError(f"ZIP 校验失败 / ZIP integrity check failed: {bad_member}")


def make_course_stage(
    output: Path,
    public_source: Path,
    release_dir: Path,
    student_id: str,
    student_name: str,
) -> tuple[Path, Path, int]:
    course_root = output / "课程作业"
    course_root.mkdir(parents=False, exist_ok=False)
    course_name = f"Flow工作流_{student_id}_{student_name}"
    stage = course_root / course_name
    stage.mkdir(parents=False, exist_ok=False)
    count = 0

    pdf_dir = ROOT / "docs/course/pdf"
    pdf_names = (
        "01_软件需求说明书.pdf",
        "02_项目开发计划书.pdf",
        "03_测试与迭代报告.pdf",
        f"04_产品说明书_{student_id}_{student_name}.pdf",
    )
    for index, name in enumerate(pdf_names, start=1):
        source = pdf_dir / name
        if not source.is_file():
            # The 04 PDF may use a generic title in an older checkout.
            if index == 4:
                alternatives = sorted(pdf_dir.glob("04_*产品说明书*.pdf"))
                source = alternatives[0] if len(alternatives) == 1 else source
            if not source.is_file():
                raise DeliveryError(f"缺少课程 PDF / Missing course PDF: {source}")
        shutil.copy2(source, stage / ("04_产品说明书.pdf" if index == 4 else name))
        count += 1

    source_stage = stage / "05_源代码" / GITHUB_SOURCE_NAME
    source_stage.parent.mkdir(parents=True, exist_ok=False)
    shutil.copytree(public_source, source_stage)
    count += sum(1 for item in source_stage.rglob("*") if item.is_file())

    executable_stage = stage / "06_可执行程序"
    executable_stage.mkdir()
    setup = release_dir / SETUP_NAME
    if not setup.is_file():
        raise DeliveryError(f"缺少安装程序 / Missing installer: {setup}")
    shutil.copy2(setup, executable_stage / SETUP_NAME)
    instructions = (
        "Flow 工作流 v0.16.1 安装与启动说明\n"
        "\n"
        "双击 FlowWorkbench-Setup-0.16.1.exe，按提示安装后从开始菜单启动。\n"
        "安装程序内置 Java、Python 和应用所需运行时，安装后请保留应用目录中的 runtime 文件夹。\n"
        "本作业目录只提供安装程序；portable ZIP 与发布附件保留在 GitHub 发布目录。\n"
        "\n"
        "Flow Workflow v0.16.1 installation and launch\n"
        "Run the installer and keep the installed runtime directory in place.\n"
    )
    (executable_stage / "安装与启动说明.txt").write_text(instructions, encoding="utf-8")
    count += 2

    demo_stage = stage / "07_演示材料"
    count += copy_filtered_tree(ROOT / "examples/course", demo_stage)

    manuscript_stage = stage / "08_说明书源稿"
    manuscript_stage.mkdir()
    course_docs = ROOT / "docs/course"
    for source in sorted(course_docs.glob("*.md")):
        if source.name == "开发过程说明.md":
            continue
        check_no_link(source)
        shutil.copy2(source, manuscript_stage / source.name)
        count += 1

    development_doc = course_docs / "开发过程说明.md"
    if development_doc.is_file():
        check_no_link(development_doc)
        development_stage = stage / "09_开发过程"
        development_stage.mkdir()
        shutil.copy2(development_doc, development_stage / development_doc.name)
        count += 1

    standalone = course_root / f"产品说明书_{student_id}_{student_name}.pdf"
    product_pdf = stage / "04_产品说明书.pdf"
    shutil.copy2(product_pdf, standalone)
    return stage, standalone, count


def scan_delivery_tree(
    root: Path,
    public_source: Path,
    student_id: str,
    student_name: str,
    private_path_fragments: list[str],
) -> list[str]:
    """Report unsafe names everywhere and sensitive text in public source."""
    findings: list[str] = []
    absolute_path_pattern = re.compile(
        r"(?:[A-Za-z]:[\\/](?:Users|Documents|home|var|tmp)[\\/][^\s\"'<>|]+|"
        r"/(?:Users|home|mnt|tmp)/[^\s\"'<>|]+)",
        re.IGNORECASE,
    )
    for current, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        base = Path(current)
        for name in sorted(dirnames + filenames):
            path = base / name
            if name.casefold() in PRIVATE_FILE_NAMES:
                findings.append(f"用户配置文件名 / user data filename: {path.relative_to(root)}")
            if path.is_file() and (name.casefold().endswith(".log") or ".log." in name.casefold()):
                findings.append(f"原始日志文件 / raw log file: {path.relative_to(root)}")
            if path.is_symlink():
                findings.append(f"存在符号链接 / symlink: {path.relative_to(root)}")

    student_patterns = (
        re.compile(re.escape(student_id), re.IGNORECASE),
        re.compile(re.escape(student_name)),
    )
    for path in sorted(public_source.rglob("*")):
        if not path.is_file() or path.suffix.casefold() not in TEXT_SUFFIXES:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            try:
                content = path.read_text(encoding="utf-8-sig")
            except (UnicodeDecodeError, OSError):
                continue
        except OSError as error:
            findings.append(
                f"无法读取源码 / source read error: {path.relative_to(public_source)}: {error}"
            )
            continue
        for number, line in enumerate(content.splitlines(), start=1):
            matched = [pattern.pattern for pattern in student_patterns if pattern.search(line)]
            if matched:
                findings.append(
                    f"源码含学生身份信息 / student identity in source: "
                    f"{path.relative_to(public_source)}:{number}"
                )
            if absolute_path_pattern.search(line):
                findings.append(
                    f"源码含绝对路径 / absolute path in source: "
                    f"{path.relative_to(public_source)}:{number}"
                )
            for fragment in private_path_fragments:
                if fragment and fragment.casefold() in line.casefold():
                    findings.append(
                        f"源码含指定本机路径片段 / local path fragment in source: "
                        f"{path.relative_to(public_source)}:{number}"
                    )
    return findings


def write_manifest(
    output: Path,
    source_count: int,
    course_count: int,
    student_id: str,
    student_name: str,
    course_name: str,
) -> Path:
    manifest_path = output / "delivery-manifest.json"
    payload = {
        "version": VERSION,
        "student": {"id": student_id, "name": student_name},
        "packages": [
            {
                "name": "GitHub public source",
                "source": "project checkout (explicit public allowlist)",
                "directory": f"GitHub/{GITHUB_SOURCE_NAME}",
                "archive": f"GitHub/{GITHUB_SOURCE_NAME}.zip",
                "file_count": source_count,
            },
            {
                "name": "course hand-in",
                "source": "project docs/course, examples/course, public source, and supplied release installer",
                "directory": f"课程作业/{course_name}",
                "archive": f"课程作业/{course_name}.zip",
                "product_pdf": f"课程作业/产品说明书_{student_id}_{student_name}.pdf",
                "file_count": course_count,
            },
        ],
        "excluded_policy": [
            "Only explicit public source directories and root files are copied; repository root is never walked.",
            "Exclude development-logs, work, run-history, data, user settings, credentials, and raw logs.",
            "Exclude caches, __pycache__, .pyc/.pyo, user-settings files, and .exe files from tools/.",
            "Include only docs/tool-protocol-v1 and docs/release-v0.16.1 in public source.",
            "Exclude docs/course identity material from GitHub source; exclude prepared/ from course demos.",
            "The supplied installer and portable ZIP remain in the GitHub release directory and are not copied.",
        ],
    }
    manifest_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest_path


def write_checksums(output: Path, release_dir: Path) -> Path:
    candidates: set[Path] = set()
    for path in output.iterdir():
        if path.is_file() and path.name not in {"SHA256SUMS.txt", "delivery-manifest.json"}:
            candidates.add(path)
    github_dir = output / "GitHub"
    if github_dir.is_dir():
        for path in github_dir.iterdir():
            if path.is_file() and path.name != "delivery-manifest.json":
                candidates.add(path)
    if inside(release_dir, github_dir) and release_dir.is_dir():
        for path in release_dir.iterdir():
            if path.is_file() and path.name != "delivery-manifest.json":
                candidates.add(path)
    course_root = output / "课程作业"
    if course_root.is_dir():
        for path in course_root.iterdir():
            if path.is_file() and path.name not in {"SHA256SUMS.txt", "delivery-manifest.json"}:
                candidates.add(path)
    sums_path = output / "SHA256SUMS.txt"
    rows = [f"{sha256(path)}  {path.relative_to(output).as_posix()}" for path in sorted(candidates)]
    sums_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return sums_path


def prepare_output(output: Path, release_dir: Path) -> None:
    """Create a new output, or use its pre-existing GitHub release directory."""
    github_dir = output / "GitHub"
    if not output.exists():
        output.mkdir(parents=True)
        github_dir.mkdir()
        return
    if not output.is_dir():
        raise DeliveryError(f"输出路径不是目录 / Output is not a directory: {output}")
    if inside(release_dir, github_dir.resolve()):
        allowed = {github_dir.name}
        existing = {item.name for item in output.iterdir()}
        if existing - allowed:
            raise DeliveryError("输出目录并非新目录 / Output already contains delivery artifacts")
        if not github_dir.is_dir():
            raise DeliveryError(
                "发行目录应位于已有的 output/GitHub 下 / Release directory must be inside an existing output/GitHub"
            )
        return
    raise DeliveryError(
        "输出目录必须全新；仅允许预置 output/GitHub 发布目录 / Output must be new, except a pre-existing output/GitHub release directory"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--release-dir", required=True, type=Path, help="已构建的 0.16.1 安装资产目录"
    )
    parser.add_argument("--output", required=True, type=Path, help="全新的交付目录")
    parser.add_argument("--student-id", required=True, help="课程作业使用的学号")
    parser.add_argument("--student-name", required=True, help="课程作业使用的真实姓名")
    parser.add_argument(
        "--private-path-fragment",
        action="append",
        default=[],
        help="额外扫描的本机路径片段；可重复指定",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    release_dir = args.release_dir.expanduser().resolve()
    output = args.output.expanduser().resolve()
    github_dir = output / "GitHub"
    student_id = args.student_id.strip()
    student_name = args.student_name.strip()
    if not student_id or not student_name:
        raise DeliveryError("学号和姓名不能为空 / Student ID and name are required")
    if any(separator in value for value in (student_id, student_name) for separator in ("/", "\\")):
        raise DeliveryError(
            "学号和姓名不能含路径分隔符 / Student identity cannot contain path separators"
        )
    course_name = f"Flow工作流_{student_id}_{student_name}"

    if not release_dir.is_dir():
        raise DeliveryError(f"发行目录不存在 / Release directory does not exist: {release_dir}")
    for name in (SETUP_NAME, PORTABLE_NAME):
        if not (release_dir / name).is_file():
            raise DeliveryError(f"发行目录缺少资产 / Missing release asset: {release_dir / name}")

    # Do not let generated output become input to any recursively copied tree.
    recursive_inputs = [ROOT / name for name in PUBLIC_DIRS]
    recursive_inputs += [ROOT / value for value in PUBLIC_DOC_DIRS]
    recursive_inputs += [ROOT / "examples/course", ROOT / "docs/course"]
    if any(inside(output, source.resolve()) for source in recursive_inputs):
        raise DeliveryError(
            "输出目录不能位于递归复制的源码目录中 / Output cannot be inside a recursively copied input"
        )
    if inside(release_dir, ROOT) and any(
        inside(release_dir, source.resolve()) for source in recursive_inputs
    ):
        raise DeliveryError(
            "发行目录不能位于待复制源码中 / Release directory cannot be inside a copied input"
        )
    if release_dir != github_dir and not inside(release_dir, github_dir):
        raise DeliveryError(
            "发行目录应位于 output/GitHub 下 / Release directory must be inside output/GitHub"
        )

    prepare_output(output, release_dir)
    if not github_dir.is_dir():
        raise DeliveryError(f"GitHub 目录不存在 / Missing GitHub directory: {github_dir}")
    source_stage = github_dir / GITHUB_SOURCE_NAME
    source_archive = github_dir / f"{GITHUB_SOURCE_NAME}.zip"
    if source_stage.exists() or source_archive.exists():
        raise DeliveryError(
            "拒绝复用现有源码 stage 或 ZIP / Refusing to overwrite existing source stage or ZIP"
        )
    course_root = output / "课程作业"
    course_stage = course_root / course_name
    course_archive = course_root / f"{course_name}.zip"
    product_pdf = course_root / f"产品说明书_{student_id}_{student_name}.pdf"
    if course_root.exists() or any(
        path.exists() for path in (course_stage, course_archive, product_pdf)
    ):
        raise DeliveryError(
            "拒绝复用现有课程 stage 或附件 / Refusing to overwrite existing course stage or attachment"
        )

    source_count = copy_public_source(source_stage)
    zip_tree(source_stage, source_archive)
    stage, standalone_pdf, course_count = make_course_stage(
        output,
        source_stage,
        release_dir,
        student_id,
        student_name,
    )
    zip_tree(stage, course_archive)

    findings = scan_delivery_tree(
        output,
        source_stage,
        student_id,
        student_name,
        args.private_path_fragment,
    )
    if findings:
        print("交付安全扫描发现问题，已保留源码并停止生成校验附件：", file=sys.stderr)
        for finding in findings:
            print("- " + finding, file=sys.stderr)
        raise DeliveryError("安全扫描未通过；不要删除样例或测试硬编码，请由维护者处理后重新运行。")

    write_manifest(output, source_count, course_count, student_id, student_name, course_name)
    write_checksums(output, release_dir)
    print(f"交付包已生成 / Delivery packages created in: {output}")
    print(f"GitHub source: {source_archive}")
    print(f"Course ZIP: {course_archive}")
    print(f"Standalone product PDF: {standalone_pdf}")
    print("ZIP testzip: passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DeliveryError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)

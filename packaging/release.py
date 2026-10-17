"""Build Windows assets from a compiled checkout and a supplied runtime directory.
Usage: python packaging/release.py --runtime-root PATH --output PATH
runtime-root contains java/ and python/ (Windows x64); never contains user data.
"""

from pathlib import Path
import argparse, hashlib, shutil, subprocess, zipfile, os, sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.16.1"


def copy_tree(src, dst):
    shutil.copytree(
        src,
        dst,
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "classes"),
    )


def archive(folder, target):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(folder.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(folder).as_posix())


def main():
    subprocess.run([sys.executable, str(ROOT / "packaging/render_tutorial.py")], check=True)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--runtime-root", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    runtime, dest = a.runtime_root.resolve(), a.output.resolve()
    stage = dest / f"FlowWorkbench-{VERSION}-windows-x64"
    if stage.exists():
        raise SystemExit("Output staging directory must not exist")
    for file in ("java/bin/java.exe", "java/bin/javac.exe", "python/python.exe"):
        if not (runtime / file).is_file():
            raise SystemExit("Missing runtime: " + file)
    stage.mkdir(parents=True)
    copy_tree(ROOT / "packaging/assets", stage)
    copy_tree(runtime / "java", stage / "runtime/java")
    copy_tree(runtime / "python", stage / "runtime/python")
    copy_tree(ROOT / "tools/python-vendor", stage / "tools/python-vendor")
    for f in (ROOT / "tools").iterdir():
        if f.is_file() and f.suffix in (".py", ".bat", ".ps1", ".exe", ".cs"):
            (stage / "tools").mkdir(exist_ok=True)
            shutil.copy2(f, stage / "tools" / f.name)
    copy_tree(ROOT / "lib", stage / "lib")
    for f in [ROOT / f"workflow-ui-v{VERSION}.jar", *ROOT.glob("*-tool-v1.jar")]:
        shutil.copy2(f, stage / f.name)
    for name in ("README.md", "RELEASE_NOTES.md", "LICENSE.txt", "SECURITY.md"):
        shutil.copy2(ROOT / name, stage / name)
    copy_tree(ROOT / "docs/release-v0.16.1", stage / "docs/release-v0.16.1")
    copy_tree(ROOT / "docs/tool-protocol-v1", stage / "docs/tool-protocol-v1")
    # No preferences, graph libraries, runs, caches or personal logs enter the package.
    forbidden = ("user-settings.properties", "install-data.path")
    assert not any(f.name in forbidden for f in stage.rglob("*"))
    portable = dest / f"FlowWorkbench-{VERSION}-windows-x64.zip"
    archive(stage, portable)
    setup = dest / f"FlowWorkbench-Setup-{VERSION}.exe"
    csc = Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    subprocess.run(
        [
            str(csc),
            "/nologo",
            "/optimize+",
            "/target:winexe",
            "/reference:System.Windows.Forms.dll",
            "/reference:System.Drawing.dll",
            "/reference:System.IO.Compression.dll",
            "/reference:Microsoft.CSharp.dll",
            "/reference:System.Web.Extensions.dll",
            "/out:" + str(setup),
            "/resource:" + str(portable) + ",payload.zip",
            str(ROOT / "packaging/FlowSetup.cs"),
        ],
        check=True,
    )
    sums = (
        "\n".join(
            hashlib.file_digest(f.open("rb"), "sha256").hexdigest() + "  " + f.name
            for f in (portable, setup)
        )
        + "\n"
    )
    (dest / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
    print("Release assets ready:", dest)


if __name__ == "__main__":
    main()

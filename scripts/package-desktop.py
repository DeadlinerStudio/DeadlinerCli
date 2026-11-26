#!/usr/bin/env python3
"""Create a self-contained desktop CLI image from :shared:stageDesktopCli."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import stat
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("jars", type=Path)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ["JAVA_HOME"], "bin")
modules = "java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs"
image = args.destination / "deadliner"
if os.name == "nt":
    image.mkdir(parents=True)
    shutil.copytree(args.jars, image / "app")
    subprocess.run([str(jdk / "jlink.exe"), "--add-modules", modules, "--strip-debug",
                    "--no-header-files", "--no-man-pages", "--output", str(image / "runtime")], check=True)
    subprocess.run(["go", "build", "-trimpath", "-o", str(image / "deadliner.exe"),
                    str(root / "scripts/launcher_windows.go")], check=True)
    sdk_bin = Path(os.environ["ProgramFiles(x86)"], "Windows Kits", "10", "bin")
    mt = shutil.which("mt.exe") or next(iter(sorted(sdk_bin.glob("10.*/x64/mt.exe"), reverse=True)), None)
    if not mt:
        raise SystemExit("Windows SDK manifest tool (mt.exe) is required for Unicode arguments")
    launcher = image / "runtime" / "bin" / "java.exe"
    # Keep the original resource open for reading and write to a separate, writable copy.
    with tempfile.TemporaryDirectory(dir=launcher.parent) as staging:
        staged = Path(staging).resolve() / launcher.name
        shutil.copyfile(launcher, staged)
        subprocess.run([str(mt), "-nologo", f"-inputresource:{launcher.resolve()};#1",
                        "-manifest", str(root / "scripts/windows-utf8.manifest"),
                        f"-outputresource:{staged};#1"], check=True)
        launcher.chmod(stat.S_IREAD | stat.S_IWRITE)
        os.replace(staged, launcher)
else:
    subprocess.run([
        str(jdk / "jpackage"), "--type", "app-image", "--dest", str(args.destination),
        "--name", "deadliner", "--input", str(args.jars),
        "--main-jar", "deadliner-cli.jar", "--main-class", "com.deadliner.cli.MainKt",
        "--java-options", "-Dfile.encoding=UTF-8", "--java-options", "-Dstdout.encoding=UTF-8",
        "--java-options", "-Dstderr.encoding=UTF-8", "--add-modules", modules,
    ], check=True)
shutil.copy2(root / "LICENSE", image / "CLI-LICENSE")

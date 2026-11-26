#!/usr/bin/env python3
"""Create a self-contained desktop CLI image from :shared:stageDesktopCli."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("jars", type=Path)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
jpackage = Path(os.environ["JAVA_HOME"], "bin", "jpackage.exe" if os.name == "nt" else "jpackage")
options = ["--win-console"] if os.name == "nt" else []
subprocess.run([
    str(jpackage), "--type", "app-image", "--dest", str(args.destination),
    "--name", "deadliner", "--input", str(args.jars),
    "--main-jar", "deadliner-cli.jar", "--main-class", "com.deadliner.cli.MainKt",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dstdout.encoding=UTF-8",
    "--java-options", "-Dstderr.encoding=UTF-8",
    "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs",
    *options,
], check=True)
root = Path(__file__).resolve().parents[1]
shutil.copy2(root / "LICENSE", args.destination / "deadliner" / "CLI-LICENSE")

#!/usr/bin/env python3
"""Qualify only synthetic OS credentials. Never accepts or prints real account secrets."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--jar", required=True)
parser.add_argument("--probe", required=True)
parser.add_argument("--native-storage")
parser.add_argument("--native-login")
args = parser.parse_args()
classpath = os.pathsep.join([str(Path(args.jar).resolve()), str(Path(args.probe).resolve())])
storage = [args.native_storage] if args.native_storage else ["java", "-cp", classpath, "io.fluxzero.tools.account.AccountPlatformProbe"]
login = [args.native_login] if args.native_login else ["java", "-cp", classpath, "io.fluxzero.tools.account.AccountPlatformLoginProbe"]
key = "synthetic-" + uuid.uuid4().hex
with tempfile.TemporaryDirectory(prefix="fluxzero-account-qualification-") as temporary:
    directory = str(Path(temporary) / "metadata")
    try:
        for operation in ("write", "read", "update", "updated", "delete"):
            subprocess.run([*storage, operation, key, directory], check=True, timeout=90)
        subprocess.run([*login, str(Path(temporary) / "login")], check=True, timeout=120)
    finally:
        subprocess.run([*storage, "delete", key, directory], check=True, timeout=30)

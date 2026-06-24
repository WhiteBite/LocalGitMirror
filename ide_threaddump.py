#!/usr/bin/env python
"""
ide_threaddump.py — capture thread dumps of a (possibly hung) JetBrains IDE.

Run this WHILE the IDE is stuck (e.g. won't close after a plugin reinstall):
    python ide_threaddump.py

It finds the running idea64.exe process(es), locates the bundled JBR jstack,
and writes 3 dumps (5s apart) to the Desktop. Send those files for analysis —
the stack of any `localgitmirror.*` thread (or a stuck EDT) shows what holds
the IDE.

Optional:
    python ide_threaddump.py --exe idea64.exe         # other IDE process name
    python ide_threaddump.py --jstack "C:\\path\\jstack.exe"
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path


def find_pids(exe_name: str) -> list:
    out = subprocess.run(
        ["tasklist", "/FI", f"IMAGENAME eq {exe_name}", "/FO", "CSV", "/NH"],
        capture_output=True, text=True,
    ).stdout
    pids = []
    for line in out.splitlines():
        line = line.strip()
        if not line or exe_name.lower() not in line.lower():
            continue
        # CSV: "idea64.exe","12345","Console","1","1,234 K"
        parts = [p.strip('"') for p in line.split('","')]
        if len(parts) >= 2 and parts[1].isdigit():
            pids.append(parts[1])
    return pids


def exe_path_of(pid: str) -> str | None:
    # Try wmic (deprecated but often present), then PowerShell CIM.
    for cmd in (
        ["wmic", "process", "where", f"ProcessId={pid}", "get", "ExecutablePath", "/value"],
        ["powershell", "-NoProfile", "-Command",
         f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').Path"],
    ):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=20).stdout
        except Exception:
            continue
        for line in out.splitlines():
            line = line.strip()
            if line.lower().startswith("executablepath="):
                line = line.split("=", 1)[1].strip()
            if line.lower().endswith(".exe") and os.path.sep in line:
                return line
    return None


def find_jstack(exe_path: str | None, override: str | None) -> str | None:
    if override and Path(override).is_file():
        return override
    candidates = []
    if exe_path:
        # <install>\bin\idea64.exe  ->  <install>\jbr\bin\jstack.exe
        install = Path(exe_path).parent.parent
        candidates.append(install / "jbr" / "bin" / "jstack.exe")
    # Known fallbacks
    candidates += [
        Path(r"S:\Jetbrains\IntelliJ IDEA\jbr\bin\jstack.exe"),
    ]
    for c in candidates:
        if c.is_file():
            return str(c)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", default="idea64.exe")
    ap.add_argument("--jstack", default="")
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--gap", type=float, default=5.0)
    args = ap.parse_args()

    pids = find_pids(args.exe)
    if not pids:
        print(f"No running process named {args.exe}. Is the IDE open?")
        sys.exit(1)
    print(f"Found {args.exe} PID(s): {', '.join(pids)}")

    desktop = Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Desktop"
    desktop.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M%S")

    for pid in pids:
        exe = exe_path_of(pid)
        jstack = find_jstack(exe, args.jstack or None)
        if not jstack:
            print(f"  PID {pid}: jstack not found (exe={exe}). "
                  f"Pass --jstack <path to jbr\\bin\\jstack.exe>.")
            continue
        for i in range(1, args.count + 1):
            out_file = desktop / f"ide-dump-{pid}-{ts}-{i}.txt"
            print(f"  PID {pid}: dump {i}/{args.count} -> {out_file}")
            with open(out_file, "w", encoding="utf-8", errors="replace") as f:
                subprocess.run([jstack, "-l", pid], stdout=f, stderr=subprocess.STDOUT, timeout=60)
            if i < args.count:
                time.sleep(args.gap)
    print("\nDone. Send the ide-dump-*.txt files from your Desktop.")


if __name__ == "__main__":
    main()

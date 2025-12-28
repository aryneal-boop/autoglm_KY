import os
import subprocess

from phone_agent.adb.adb_path import INTERNAL_ADB_PATH


def main() -> int:
    adb_path = os.environ.get("INTERNAL_ADB_PATH") or INTERNAL_ADB_PATH
    print(f"INTERNAL_ADB_PATH={adb_path}")

    try:
        result = subprocess.run(
            [adb_path, "devices"],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except FileNotFoundError:
        print("ERROR: adb not found. Please set environment variable INTERNAL_ADB_PATH to the app-private adb absolute path.")
        return 2
    except Exception as e:
        print(f"ERROR: failed to run adb: {e}")
        return 3

    print("returncode:", result.returncode)
    print("stdout:\n" + (result.stdout or ""))
    if result.stderr:
        print("stderr:\n" + result.stderr)

    if result.returncode != 0:
        return result.returncode

    lines = (result.stdout or "").splitlines()
    devices = []
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))

    print("parsed_devices:", devices)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Prints the UDID of an available iPhone simulator of the newest iOS runtime installed on the macOS runner.

Reads the output of `xcrun simctl list devices available --json` on its standard input (.github/workflows/ios.yml).
"""

import json
import sys


def runtime_version(key):
    # "com.apple.CoreSimulator.SimRuntime.iOS-18-2" -> (18, 2)
    return tuple(int(part) for part in key.rsplit("iOS-", 1)[1].split("-") if part.isdigit())


def main():
    devices = json.load(sys.stdin)["devices"]
    runtimes = sorted((key for key in devices if "SimRuntime.iOS-" in key), key=runtime_version, reverse=True)
    for runtime in runtimes:
        phones = [device for device in devices[runtime] if device.get("name", "").startswith("iPhone")]
        if phones:
            print(phones[0]["udid"])
            print(f"{phones[0]['name']} ({runtime})", file=sys.stderr)
            return 0
    print("No iPhone simulator available", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Read-only SDK ZIP/DEX size and parent-first host-class collision checks."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile

LIMIT = 4 * 1024 * 1024


def classes(data):
    assert data.startswith(b"dex\n"), "Invalid DEX"
    count, offset = struct.unpack_from("<II", data, 96)
    strings = struct.unpack_from("<I", data, 60)[0]
    types = struct.unpack_from("<I", data, 68)[0]
    result = set()
    for index in range(count):
        type_id = struct.unpack_from("<I", data, offset + index * 32)[0]
        string_id = struct.unpack_from("<I", data, types + type_id * 4)[0]
        position = struct.unpack_from("<I", data, strings + string_id * 4)[0]
        while data[position] & 128:
            position += 1
        position += 1
        result.add(data[position:data.index(b"\0", position)].decode("utf-8"))
    return result


def scan(archive):
    result = set()
    for name in archive.namelist():
        if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
            result.update(classes(archive.read(name)))
    return result


def check(path, host_apk=None):
    assert path.stat().st_size <= LIMIT
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        assert len(names) == len(set(names)) <= 512
        core = {"kiosk-satellite-plugin.json", "plugin.jar", "LICENSE"}
        assert core <= set(names)
        assert all(n in core or n.startswith("assets/") and all(re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", p) for p in n[7:].split("/")) for n in names)
        assert sum(i.file_size for i in package.infolist()) <= LIMIT
        manifest = json.loads(package.read("kiosk-satellite-plugin.json"))
        if manifest.get("icon"):
            icon_path = manifest["icon"]
            assert isinstance(icon_path, str) and len(icon_path) in range(1, 241)
            assert all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", part) for part in icon_path.split("/"))
            # Kiosk PluginPackage.assetFile resolves relative to its assets directory.
            icon = package.read("assets/" + icon_path)
            assert len(icon) <= 256 * 1024 and icon[:8] == b'\x89PNG\r\n\x1a\n'
            assert struct.unpack_from('>II', icon, 16) == (256, 256)
        with zipfile.ZipFile(io.BytesIO(package.read("plugin.jar"))) as dex:
            assert all(re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", n) for n in dex.namelist())
            expanded = sum(i.file_size for i in dex.infolist())
            assert expanded <= LIMIT
            defined = scan(dex)
        assert "L" + manifest["entryClass"].replace(".", "/") + ";" in defined
        assert not any(c.startswith("Lme/jxl/kiosk/") for c in defined)
        if host_apk:
            with zipfile.ZipFile(host_apk) as host:
                overlap = defined & scan(host)
            assert not overlap, "Parent-loader class collisions: " + repr(sorted(overlap))
        print("PASS", manifest["version"], "ZIP bytes", path.stat().st_size,
              "DEX bytes", expanded, "classes", len(defined), "host checked", bool(host_apk))
        print("SHA256", hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--host-apk", type=Path)
    args = parser.parse_args()
    check(args.package, args.host_apk)

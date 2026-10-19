#!/usr/bin/env python3
"""Build with JDK 21 and Android SDK 36. Never installs or publishes the plugin."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_PACKAGE = 4 * 1024 * 1024


def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def validate_settings(fields):
    """Validate this plugin's scalar settings against KS PluginManifest rules.

    Conditions can only reference earlier non-secret fields on the same level.
    This is not a replacement for the host's complete package validation.
    """
    if not isinstance(fields, list) or len(fields) > 50:
        raise ValueError("Invalid settings list")
    earlier = {}
    for field in fields:
        key = field.get("key")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key) or key in earlier:
            raise ValueError("Invalid or duplicate setting key")
        for name, limit in [("title", 80), ("description", 400)]:
            if name == "description" and name not in field:
                continue
            text = field.get(name)
            if not isinstance(text, str) or not text.strip() or len(text) > limit:
                raise ValueError("Invalid setting " + name)
        if "visibleWhen" in field:
            condition = field["visibleWhen"]
            if not isinstance(condition, dict):
                raise ValueError("Invalid setting condition")
            reference = condition.get("key")
            target = earlier.get(reference) if isinstance(reference, str) else None
            if target is None or target["type"] in {"list", "secret"}:
                raise ValueError("Setting condition must reference an earlier non-secret field")
            operators = set(condition) - {"key"}
            if len(operators) != 1 or not operators <= {"equals", "notEquals", "notEmpty"}:
                raise ValueError("Invalid setting condition operator")
            operator = next(iter(operators))
            if operator == "notEmpty":
                if condition[operator] is not True:
                    raise ValueError("Invalid setting notEmpty condition")
            else:
                expected = condition[operator]
                expected = expected if isinstance(expected, list) else [expected]
                if not 1 <= len(expected) <= 32 or not all(isinstance(v, (str, bool, int, float)) for v in expected):
                    raise ValueError("Invalid setting condition value")
        kind, default = field.get("type"), field.get("default")
        if kind == "boolean":
            valid = isinstance(default, bool)
        elif kind == "string":
            valid = isinstance(default, str) and len(default) <= 512
        elif kind == "select":
            options = field.get("options")
            valid = (isinstance(options, list) and 1 <= len(options) <= 32
                     and all(isinstance(v, str) and v.strip() and len(v) <= 80 for v in options)
                      and len(set(options)) == len(options) and isinstance(default, str) and default in options)
        elif kind == "number":
            valid = (key == "duckPercent" and field.get("min") == 1 and field.get("max") == 10
                     and field.get("step") == 1 and isinstance(default, (int, float))
                     and not isinstance(default, bool) and 1 <= default <= 10)
        else:
            raise ValueError("Unsupported setting type for this plugin")
        if not valid:
            raise ValueError("Invalid setting default")
        earlier[key] = field


def manifest(path):
    data = json.loads(path.read_text())
    assert data["schemaVersion"] == data["apiVersion"] == 1
    assert re.fullmatch(r"[a-z][a-z0-9-]{0,63}", data["id"])
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", data["version"])
    assert data["minAndroidSdk"] >= 24
    assert data["id"] == "musicassistantplugin"
    assert data["name"] == "Music Assistant"
    assert data["author"] == "Mark Andrew"
    assert data["icon"] == "icon.png"
    icon = (ROOT / "assets" / data["icon"]).read_bytes()
    assert len(icon) <= 256 * 1024 and icon[:8] == b'\x89PNG\r\n\x1a\n'
    import struct
    assert struct.unpack('>II', icon[16:24]) == (256, 256)
    assert data["entryClass"] == "net.markandrew.kiosk.music.MusicAssistantPlugin"
    assert set(data["capabilities"]) == {"overlay", "host.read", "host.control", "ha"}
    assert {item["id"] for item in data["commands"]} == {"toggle"}
    validate_settings(data["settings"])
    assert [item["key"] for item in data["settings"]] == ["addon", "defaultPlayer", "idleRevertSeconds", "duckPercent", "preventScreensaverWhilePlaying", "hardwareGroupVolume", "receiverEnabled", "receiverName", "receiverEndpoint", "receiverServer", "playerMode", "localPlayer"]
    return data


def sdk_sources(fetch=False):
    lock = json.loads((ROOT / "sdk.lock.json").read_text())
    assert re.fullmatch(r"[0-9a-f]{40}", lock["commit"])
    destination = ROOT
    for name, digest in lock["git_blobs"].items():
        if name.startswith("/") or ".." in Path(name).parts:
            raise ValueError("Invalid SDK lock path")
        file = destination / ("sdk/LICENSE" if name == "LICENSE" else name)
        if not file.exists():
            if not fetch:
                raise SystemExit("Pinned SDK is absent. Review sdk.lock.json, then build with --fetch-sdk.")
            url = f'https://raw.githubusercontent.com/{lock["repository"]}/{lock["commit"]}/{name}'
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(128 * 1024 + 1)
            if len(data) > 128 * 1024 or git_blob(data) != digest:
                raise SystemExit("Pinned SDK verification failed")
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        if git_blob(file.read_bytes()) != digest:
            raise SystemExit("Cached SDK verification failed; refusing modified SDK files")
    return sorted((destination / "sdk" / "src").rglob("*.java"))


def add_zip(archive, name, data):
    info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


def build(args):
    data = manifest(ROOT / "kiosk-satellite-plugin.json")
    if args.gradle:
        return build_player(args, data)
    if (ROOT / "receiver").exists():
        raise SystemExit("This version requires --gradle pointing to a reviewed Gradle 9.3.1 wrapper")
    java_home = os.environ.get("JAVA_HOME")
    def tool(name):
        value = str(Path(java_home) / "bin" / name) if java_home else shutil.which(name)
        if not value or not Path(value).is_file():
            raise SystemExit("JDK 17+ is required (javac and jar). Nothing was installed.")
        return value
    javac, jar = tool("javac"), tool("jar")
    sdk_root = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    android = sdk_root / "platforms" / ("android-" + args.android_platform) / "android.jar"
    compilers = [*sdk_root.glob("build-tools/*/d8"), *sdk_root.glob("build-tools/*/d8.bat")]
    compilers = [file for file in compilers if re.fullmatch(r"[0-9.]+", file.parent.name)]
    compilers.sort(key=lambda file: tuple(int(part) for part in file.parent.name.split(".")))
    if not android.is_file() or not compilers:
        raise SystemExit("Set ANDROID_HOME to an SDK with platform " + args.android_platform + " and build-tools. Nothing was installed.")
    sources = sdk_sources(args.fetch_sdk)
    build_root = ROOT / "build"
    # Unique work directory: never reuse stale compiled classes in a package.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="compile-", dir=build_root) as temporary:
        temporary = Path(temporary)
        sdk, classes, dex = [temporary / name for name in ("sdk", "classes", "dex")]
        for folder in (sdk, classes, dex):
            folder.mkdir()
        subprocess.run([javac, "--release", "8", "-cp", str(android), "-d", str(sdk), *map(str, sources)], check=True)
        sdk_jar = temporary / "sdk.jar"
        subprocess.run([jar, "cf", str(sdk_jar), "-C", str(sdk), "."], check=True)
        subprocess.run([javac, "--release", "8", "-cp", os.pathsep.join([str(sdk_jar), str(android)]),
                        "-d", str(classes), *map(str, sorted((ROOT / "src").rglob("*.java")))], check=True)
        if (classes / "me" / "jxl" / "kiosk" / "plugins").exists():
            raise SystemExit("Refusing to package SDK classes")
        subprocess.run([str(compilers[-1]), "--min-api", str(data["minAndroidSdk"]), "--lib", str(android),
                        "--classpath", str(sdk_jar), "--output", str(dex), *map(str, sorted(classes.rglob("*.class")))], check=True)
        dex_files = sorted(dex.glob("*.dex"))
        if not dex_files or sum(file.stat().st_size for file in dex_files) > MAX_PACKAGE:
            raise SystemExit("Missing or oversized DEX output")
        dex_jar = temporary / "plugin.jar"
        with zipfile.ZipFile(dex_jar, "w") as archive:
            for file in dex_files:
                add_zip(archive, file.name, file.read_bytes())
        content = {
            "kiosk-satellite-plugin.json": (ROOT / "kiosk-satellite-plugin.json").read_bytes(),
            "plugin.jar": dex_jar.read_bytes(), "LICENSE": (ROOT / "LICENSE").read_bytes(),
            "assets/" + data["icon"]: (ROOT / "assets" / data["icon"]).read_bytes(),
        }
        if sum(map(len, content.values())) > MAX_PACKAGE:
            raise SystemExit("Expanded package exceeds SDK limit")
        candidate = temporary / "candidate.zip"
        with zipfile.ZipFile(candidate, "w") as archive:
            for name, value in content.items():
                add_zip(archive, name, value)
        if candidate.stat().st_size > MAX_PACKAGE:
            raise SystemExit("Package exceeds SDK limit")
        output = ROOT / "dist"
        output.mkdir(exist_ok=True)
        package = output / (data["id"] + "-" + data["version"] + ".zip")
        if package.exists():
            raise SystemExit("Package already exists; change the version or move the old output before rebuilding")
        shutil.copyfile(candidate, package)
        digest = hashlib.sha256(package.read_bytes()).hexdigest()
        package.with_suffix(".zip.sha256").write_text(digest + "  " + package.name + "\n")
        (output / "kiosk-satellite-plugin.json").write_bytes(content["kiosk-satellite-plugin.json"])
        print("Built", package.name, "(not installed or published)")


def build_player(args, data):
    """R8-shrunk DEX from a build-only APK container, never an installed app."""
    sdk_sources(args.fetch_sdk)
    subprocess.run([str(Path(args.gradle).resolve()), "--project-dir", str(ROOT / "player-build"),
                    ":bundle:assembleRelease", ":bundle:writeRuntimeArtifacts", "--console=plain"], check=True)
    apk = ROOT / "player-build/bundle/build/outputs/apk/release/bundle-release-unsigned.apk"
    import io
    dex = io.BytesIO()
    with zipfile.ZipFile(apk) as source, zipfile.ZipFile(dex, "w") as target:
        names = [n for n in source.namelist() if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", n)]
        if not names:
            raise SystemExit("No DEX in build container")
        if sum(source.getinfo(name).file_size for name in names) > MAX_PACKAGE:
            raise SystemExit("Expanded DEX exceeds the host's 4 MiB limit")
        for name in sorted(names):
            add_zip(target, name, source.read(name))
    content = {"kiosk-satellite-plugin.json": (ROOT / "kiosk-satellite-plugin.json").read_bytes(),
               "plugin.jar": dex.getvalue(), "LICENSE": (ROOT / "LICENSE").read_bytes()}
    # Third-party notices are an approved package asset, not an unrecognized ZIP entry.
    for name in ["LICENSE", "UPSTREAM.md"]:
        content["assets/sendspindroid-" + name] = (ROOT / "third_party/sendspindroid" / name).read_bytes()
    content["assets/DEPENDENCIES.md"] = (ROOT / "DEPENDENCIES.md").read_bytes()
    content["assets/" + data["icon"]] = (ROOT / "assets" / data["icon"]).read_bytes()
    content["assets/Apache-2.0.txt"] = (ROOT / "sdk/LICENSE").read_bytes()
    notices = []
    def collect_notices(data, origin):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for entry in archive.infolist():
                    name = entry.filename
                    if entry.file_size > 256 * 1024:
                        continue
                    if re.search(r"(?:^|/)(?:LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*|AL2\.0|LGPL2\.1)$", name, re.I):
                        notices.append(origin + "/" + name + "\n" + archive.read(name).decode("utf-8", errors="replace"))
                    elif name == "classes.jar":
                        collect_notices(archive.read(name), origin + "/" + name)
        except zipfile.BadZipFile:
            pass
    for path in (ROOT / "player-build/bundle/build/runtime-artifacts.txt").read_text().splitlines():
        artifact = Path(path)
        if artifact.is_file():
            collect_notices(artifact.read_bytes(), artifact.name)
    content["assets/dependency-notices.txt"] = "\n\n".join(notices).encode()
    if sum(map(len, content.values())) > MAX_PACKAGE:
        raise SystemExit("Expanded package exceeds the host's 4 MiB limit")
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    package = output / (data["id"] + "-" + data["version"] + ".zip")
    if package.exists():
        raise SystemExit("Package already exists; refusing overwrite")
    with zipfile.ZipFile(package, "w") as archive:
        for name, value in content.items():
            add_zip(archive, name, value)
    if package.stat().st_size > MAX_PACKAGE:
        raise SystemExit("Package exceeds SDK limit")
    package.with_suffix(".zip.sha256").write_text(hashlib.sha256(package.read_bytes()).hexdigest() + "  " + package.name + "\n")
    (output / "kiosk-satellite-plugin.json").write_bytes(content["kiosk-satellite-plugin.json"])
    print("Built", package, "(not installed)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch-sdk", action="store_true", help="Fetch and verify pinned public compile-time SDK files")
    parser.add_argument("--android-platform", default="35")
    parser.add_argument("--gradle", default=str(ROOT / "player-build/gradlew"), help="Gradle wrapper path (defaults to the included Gradle 9.3.1 wrapper)")
    parser.add_argument("--version", help="Require the manifest version to match this release tag version")
    args = parser.parse_args()
    if args.version and manifest(ROOT / "kiosk-satellite-plugin.json")["version"] != args.version:
        parser.error("Release version does not match kiosk-satellite-plugin.json")
    build(args)

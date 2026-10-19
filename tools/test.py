#!/usr/bin/env python3
"""Run offline source, Java, Android fixture and shared JVM tests serially."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'player-build/gradlew')
    parser.add_argument('--json-jar', type=Path, help='Optional org.json JAR for additional loopback API tests')
    parser.add_argument('--fetch-test-deps', action='store_true', help='Download and verify the pinned test-only org.json JAR')
    args = parser.parse_args()
    if args.fetch_test_deps and not args.json_jar:
        args.json_jar = ROOT / 'build/test-dependencies/json-20240303.jar'
        if not args.json_jar.exists():
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30) as response:
                data = response.read(1024 * 1024 + 1)
            if hashlib.sha256(data).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
                raise SystemExit('Test dependency checksum mismatch')
            args.json_jar.parent.mkdir(parents=True, exist_ok=True)
            args.json_jar.write_bytes(data)
        if hashlib.sha256(args.json_jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
            raise SystemExit('Cached test dependency checksum mismatch')
    checks = [sys.executable, str(ROOT / 'tools/check.py'), '--java']
    if args.json_jar:
        checks += ['--json-jar', str(args.json_jar.resolve())]
    subprocess.run(checks, check=True)
    subprocess.run([str(args.gradle.resolve()), '--project-dir', str(ROOT / 'player-build'),
                    ':bundle:testDebugUnitTest', ':shared:jvmTest', '--console=plain'], check=True)


if __name__ == '__main__':
    main()

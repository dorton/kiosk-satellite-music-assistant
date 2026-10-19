#!/usr/bin/env python3
"""Check local release assets; live community eligibility is checked separately."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def check():
    manifest = (ROOT / 'kiosk-satellite-plugin.json').read_bytes()
    data = json.loads(manifest)
    assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', data['version']), 'Stable version required'
    name = f'{data["id"]}-{data["version"]}.zip'
    package = ROOT / 'dist' / name
    digest = hashlib.sha256(package.read_bytes()).hexdigest()
    assert (ROOT / 'dist' / (name + '.sha256')).read_text().strip() == digest + '  ' + name
    assert (ROOT / 'dist/kiosk-satellite-plugin.json').read_bytes() == manifest
    with zipfile.ZipFile(package) as archive:
        assert archive.read('kiosk-satellite-plugin.json') == manifest
        assert archive.read('LICENSE') == (ROOT / 'LICENSE').read_bytes()
    assert (ROOT / 'README.md').is_file() and (ROOT / 'sdk/LICENSE').is_file()
    print('PASS local stable release assets:', name)
    print('Live repository visibility, licence recognition, Actions uploader and plugin-ID availability remain separate checks.')


if __name__ == '__main__':
    check()

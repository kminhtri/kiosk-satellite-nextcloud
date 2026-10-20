#!/usr/bin/env python3
"""Validate the built ZIP using the upstream installer's manifest/package rules."""
import hashlib
import json
import io
import zipfile
from pathlib import Path
from community_plugins import validate_manifest, validate_package
from dex_namespace import verify_namespace

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'dist/kiosk-satellite-plugin.json').read_text())
validate_manifest(manifest)
package = root / 'dist' / f"{manifest['id']}-{manifest['version']}.zip"
validate_package(package.read_bytes(), manifest)
classes = 0
with zipfile.ZipFile(package) as archive:
    with zipfile.ZipFile(io.BytesIO(archive.read('plugin.jar'))) as jar:
        for name in jar.namelist():
            classes += verify_namespace(jar.read(name))
digest = hashlib.sha256(package.read_bytes()).hexdigest()
assert package.with_suffix('.zip.sha256').read_text() == f'{digest}  {package.name}\n'
print(f'Installer validation passed: {package.name} ({package.stat().st_size:,} bytes)')
print(f'DEX isolation verified: {classes} plugin-private classes, no shared Kotlin/OkHttp/Okio type references.')

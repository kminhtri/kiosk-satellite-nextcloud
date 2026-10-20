"""Prepare identical package and release manifests without editing source files."""
import json
import re
from pathlib import Path


def build_manifest(path, version=None):
    source = path.read_bytes()
    manifest = json.loads(source)
    chosen = manifest.get('version') if version is None else version
    if not isinstance(chosen, str) or len(chosen) > 40 or not re.fullmatch(
        r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?', chosen
    ):
        raise ValueError('Plugin version must be major.minor.patch with an optional prerelease suffix.')
    if version is None:
        return source
    manifest['version'] = chosen
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')


def write_build_info(directory, manifest_bytes):
    """Compile the effective package version into the HTTP User-Agent too."""
    version = json.loads(manifest_bytes)['version']
    target = Path(directory) / 'io/github/kiosknextcloud/BuildInfo.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('package io.github.kiosknextcloud;\n'
                      'final class BuildInfo { static final String VERSION = '
                      + json.dumps(version) + '; }\n', encoding='utf-8')
    return target

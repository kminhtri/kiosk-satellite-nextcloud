"""Fetch and verify the exact runtime libraries bundled in the plugin."""
import hashlib
import json
from pathlib import Path
import urllib.request


def dependencies(root):
    target = root / '.deps'
    target.mkdir(exist_ok=True)
    result = []
    for item in json.loads((root / 'tools/dependencies.lock.json').read_text()):
        file = target / item['url'].rsplit('/', 1)[-1]
        if not file.exists():
            with urllib.request.urlopen(item['url'], timeout=60) as response:
                data = response.read(8 * 1024 * 1024 + 1)
            if hashlib.sha256(data).hexdigest() != item['sha256']:
                raise ValueError('Dependency checksum mismatch: ' + file.name)
            file.write_bytes(data)
        if hashlib.sha256(file.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Dependency checksum mismatch: ' + file.name)
        result.append(file)
    return result


if __name__ == '__main__':
    for file in dependencies(Path(__file__).resolve().parents[1]):
        print(file.name)

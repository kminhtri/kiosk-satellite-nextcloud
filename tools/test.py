#!/usr/bin/env python3
"""Exercise the Nextcloud plugin against a local WebDAV fixture without a device."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform
from dependencies import dependencies
from isolate import isolate

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
# The SDK's overlay classes use Android views. The tests never draw one,
# so the platform's stub classes are enough to compile and load them.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error
sources = [*sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests/io').rglob('*.java'))]
libraries = dependencies(root)
classpath = os.pathsep.join(map(str, [platform, *libraries]))
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    temp = Path(directory)
    sdk_classes, classes, host = [temp / part for part in ('sdk', 'classes', 'host')]
    for folder in (sdk_classes, classes, host):
        folder.mkdir()
    subprocess.run([tool('javac'), '--release', '8', '-cp', platform, '-d', str(sdk_classes),
                    *map(str, sorted((root / 'sdk/src').rglob('*.java')))], check=True)
    sdk = temp / 'sdk.jar'
    subprocess.run([tool('jar'), 'cf', str(sdk), '-C', str(sdk_classes), '.'], check=True)
    subprocess.run([tool('javac'), '--release', '8', '-cp', os.pathsep.join([str(sdk), classpath]), '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([tool('javac'), '--release', '8', '-d', str(host), *map(str, sorted((root / 'tests/host-runtime').rglob('*.java')))], check=True)
    # Reproduce the user's missing-method error before asserting the fix.
    broken = subprocess.run([tool('java'), '-cp', os.pathsep.join([str(host), str(classes), str(sdk), classpath]),
                             'io.github.kiosknextcloud.IsolationProbe'], capture_output=True, text=True)
    if broken.returncode == 0 or 'NoSuchMethodError' not in broken.stderr or 'checkNotNullParameter' not in broken.stderr:
        raise AssertionError('The conflicting-host fixture did not reproduce the reported Kotlin error: ' + broken.stderr)
    print('Reproduced the reported checkNotNullParameter conflict.', flush=True)
    isolated = temp / 'isolated-tests.jar'
    isolate(root, tool('java'), sdk_root, platform, sdk, classes, libraries, isolated)
    runtime = os.pathsep.join(map(str, [host, sdk, platform, isolated]))
    # No original dependency JARs are on this classpath; the old host class wins
    # any accidental unrelocated reference, just as it does in Android's loader.
    subprocess.run([tool('java'), '-ea', '-cp', runtime, 'io.github.kiosknextcloud.IsolationProbe'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', runtime, 'io.github.kiosknextcloud.NextcloudTest'], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_plugin_manifest.py')], check=True)

subprocess.run([sys.executable, str(root / "tools/test_plugin_assets.py")], check=True)

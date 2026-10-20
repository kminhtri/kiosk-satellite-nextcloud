"""Relocate bundled libraries before DEX compilation and regression testing."""
from pathlib import Path
import subprocess
import zipfile
from android_sdk import android_build_tools


def r8_jar(sdk_root):
    return android_build_tools(sdk_root) / 'lib/d8.jar'


def isolate(root, java, sdk_root, platform, sdk, classes, libraries, output, mapping=None):
    # DEX packages contain code only. Strip module descriptors and data resources
    # before relocation, so Java resource lookup cannot pin a dependency's name.
    inputs = Path(output).with_suffix('.input.jar')
    with zipfile.ZipFile(inputs, 'w', zipfile.ZIP_DEFLATED) as merged:
        for file in sorted(Path(classes).rglob('*.class')):
            merged.write(file, file.relative_to(classes).as_posix())
        for library in libraries:
            with zipfile.ZipFile(library) as archive:
                for name in archive.namelist():
                    if name.endswith('.class') and not name.startswith('META-INF/') and name != 'module-info.class':
                        merged.writestr(name, archive.read(name))
    mapping = Path(mapping) if mapping is not None else Path(output).with_suffix('.mapping.txt')
    subprocess.run([str(java), '-cp', str(r8_jar(sdk_root)), 'com.android.tools.r8.R8',
                    '--classfile', '--release', '--lib', str(platform), '--lib', str(sdk),
                    '--pg-conf', str(root / 'tools/isolated-runtime.pro'),
                    '--pg-map-output', str(mapping),
                    '--output', str(output), str(inputs)], check=True)
    if not mapping.is_file() or mapping.stat().st_size == 0:
        raise ValueError('R8 did not produce its name mapping')
    with zipfile.ZipFile(output) as archive:
        classes = [name for name in archive.namelist() if name.endswith('.class')]
        outside = [name for name in classes if not name.startswith('io/github/kiosknextcloud/')]
        if not classes or outside:
            raise ValueError('The runtime contains classes outside the plugin namespace: ' + ', '.join(outside[:20]))

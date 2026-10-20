"""Relocate bundled libraries before DEX compilation and regression testing."""
from pathlib import Path
import subprocess
import zipfile


def r8_jar(sdk_root):
    jars = sorted((Path(sdk_root) / 'build-tools').glob('*/lib/d8.jar'),
                  key=lambda p: tuple(int(v) for v in p.parent.parent.name.split('.') if v.isdigit()))
    if not jars:
        raise ValueError('Install Android build-tools to isolate the plugin runtime.')
    return jars[-1]


def isolate(root, java, sdk_root, platform, sdk, classes, libraries, output):
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
    subprocess.run([str(java), '-cp', str(r8_jar(sdk_root)), 'com.android.tools.r8.R8',
                    '--classfile', '--release', '--lib', str(platform), '--lib', str(sdk),
                    '--pg-conf', str(root / 'tools/isolated-runtime.pro'),
                    '--output', str(output), str(inputs)], check=True)
    with zipfile.ZipFile(output) as archive:
        classes = [name for name in archive.namelist() if name.endswith('.class')]
        outside = [name for name in classes if not name.startswith('io/github/kiosknextcloud/')]
        if not classes or outside:
            raise ValueError('The runtime contains classes outside the plugin namespace: ' + ', '.join(outside[:20]))

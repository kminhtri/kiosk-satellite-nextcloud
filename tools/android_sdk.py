"""Select an installed Android platform without assuming integer directory names."""
import re
from pathlib import Path

DEFAULT_PLATFORM = '35'
BUILD_TOOLS_VERSION = '35.0.0'


def android_build_tools(sdk_root, requested=BUILD_TOOLS_VERSION):
    """Use the tested R8/D8 version even when a runner preinstalls newer tools."""
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', requested):
        raise ValueError('Android build-tools version must be numeric.')
    directory = Path(sdk_root) / 'build-tools' / requested
    if not (directory / 'lib/d8.jar').is_file() or not any(
        (directory / name).is_file() for name in ('d8', 'd8.bat')
    ):
        raise ValueError(f'Install Android build-tools {requested} before building.')
    return directory


def android_platform(sdk_root, requested=None):
    platforms = Path(sdk_root) / 'platforms'
    if requested is not None:
        if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', requested):
            raise ValueError('Android platform must be a numeric version such as 35 or 37.0.')
        jar = platforms / f'android-{requested}' / 'android.jar'
        if not jar.is_file():
            raise ValueError(f'Install Android platform android-{requested} before building.')
        return jar

    candidates = []
    for jar in platforms.glob('android-*/android.jar'):
        version = jar.parent.name.removeprefix('android-')
        if jar.is_file() and re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', version):
            candidates.append((tuple(int(part) for part in version.split('.')), jar))
    if not candidates:
        raise ValueError('Install a numbered Android SDK platform before building.')
    return max(candidates, key=lambda candidate: candidate[0])[1]

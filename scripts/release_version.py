"""Shared CI metadata, matching Gradle's version.json + full Git commit count."""
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def metadata(root=ROOT):
    values = json.loads((root / 'version.json').read_text())
    parts = [str(values[key]) for key in ('major', 'minor')]
    if not all(re.fullmatch(r'0|[1-9][0-9]*', part) for part in parts):
        raise ValueError('version.json major/minor must be non-negative integers or integer strings')
    count = subprocess.check_output(['git', 'rev-list', '--count', 'HEAD'], cwd=root, text=True).strip()
    properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    return {'version': '.'.join(parts + [count]), 'minecraft': properties['minecraft_version'], 'commit': commit}


if __name__ == '__main__':
    result = metadata()
    print(json.dumps(result, sort_keys=True))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in result.items():
                output.write(f'{key}={value}\n')

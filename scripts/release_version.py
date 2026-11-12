"""Shared CI metadata, matching Gradle's Minecraft version + full Git commit count."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def metadata(root=ROOT):
    count = subprocess.check_output(['git', 'rev-list', '--count', 'HEAD'], cwd=root, text=True).strip()
    properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    minecraft = properties['minecraft_version']
    return {'version': f'{minecraft}-{count}', 'minecraft': minecraft, 'revision': int(count), 'commit': commit}


if __name__ == '__main__':
    result = metadata()
    print(json.dumps(result, sort_keys=True))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in result.items():
                output.write(f'{key}={value}\n')

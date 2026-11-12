"""Shared CI metadata, matching Gradle's Minecraft version + full Git commit count."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD_TASKS = {'26': 'universalJar', '1.20': 'build1201'}


def artifact_names(line, version):
    prefixes = {'26': ['retina'], '1.20': ['retina-fabric', 'retina-forge', 'retina-bukkit']}
    return [f'{prefix}-{version}.jar' for prefix in prefixes[line]]


def metadata(root=ROOT, line='26'):
    count = subprocess.check_output(['git', 'rev-list', '--count', 'HEAD'], cwd=root, text=True).strip()
    properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    minecraft = properties['minecraft_version'] if line == '26' else '1.20.1'
    return {'version': f'{minecraft}-{count}', 'minecraft': minecraft, 'revision': int(count), 'commit': commit,
            'line': line, 'build_task': BUILD_TASKS[line]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--line', choices=BUILD_TASKS, default='26')
    result = metadata(line=parser.parse_args().line)
    print(json.dumps(result, sort_keys=True))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in result.items():
                output.write(f'{key}={value}\n')

"""Publish immutable universal assets with linked PR/direct-commit release notes."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote
from release_version import metadata

REPO = os.environ.get('GH_REPO', '')


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path, missing=False, pages=False):
    command = ['gh', 'api', f'repos/{REPO}/{path}']
    if pages:
        command += ['--paginate', '--slurp']
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode:
        if missing and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError(result.stderr)
    data = json.loads(result.stdout)
    return [entry for page in data for entry in page] if pages else data


def release_for_tag(tag):
    # The tag endpoint excludes drafts, even for their authenticated creator.
    return next((release for release in api('releases?per_page=100', pages=True)
                 if release['tag_name'] == tag), None)


def release_count(tag):
    # Recognize current and historical Retina version schemes during migration.
    if re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', tag) or re.fullmatch(r'v0\.[0-9]+', tag):
        return int(tag.rsplit('.', 1)[1])
    return -1


def published():
    return [release for release in api('releases?per_page=100', pages=True)
            if not release['draft'] and not release['prerelease'] and release_count(release['tag_name']) >= 0]


def tag_commit(tag):
    ref = api('git/ref/tags/' + quote(tag, safe=''), missing=True)
    if ref is None:
        return None
    obj = ref['object']
    while obj['type'] == 'tag':
        obj = api('git/tags/' + obj['sha'])['object']
    if obj['type'] != 'commit':
        raise ValueError(f'{tag} does not identify a commit')
    return obj['sha']


def escape(text):
    return text.replace('\\', '\\\\').replace('[', '\\[').replace(']', '\\]').replace('\n', ' ')


def changes(notes_commits, pull_requests, author):
    pulls = {pr['merge_commit_sha']: pr for pr in pull_requests if pr.get('merged_at')}
    lines = []
    for commit, subject in notes_commits:
        pr = pulls.get(commit)
        if pr:
            lines.append(f"* {escape(pr['title'])} by @{pr['user']['login']} in [#{pr['number']}]({pr['html_url']})")
        else:
            by = author(commit)
            lines.append(f'* {escape(subject)}{(" by " + by) if by else ""} in [{commit[:7]}](https://github.com/{REPO}/commit/{commit})')
    return '\n'.join(lines)


def release_notes(info, tag, sha256, jar):
    older = sorted((r for r in published() if release_count(r['tag_name']) < int(info['version'].rsplit('.', 1)[1])),
                   key=lambda r: release_count(r['tag_name']), reverse=True)
    previous = None
    for release in older:
        source = tag_commit(release['tag_name'])
        if source and subprocess.run(['git', 'merge-base', '--is-ancestor', source, info['commit']]).returncode == 0:
            previous = release['tag_name']
            break
    revision = f'{previous}..{info["commit"]}' if previous else info['commit']
    history = run('git', 'log', '--first-parent', '--reverse', '--format=%H%x00%s', revision)
    commits = [line.split('\0', 1) for line in history.splitlines() if line]
    pulls = api('pulls?state=closed&per_page=100', pages=True)

    def author(commit):
        data = api('commits/' + commit)
        user = data.get('author')
        return '@' + user['login'] if user else escape(data['commit']['author']['name'])

    body = "## What's Changed\n\n" + changes(commits, pulls, author) + '\n\n'
    if previous:
        body += f'**Full Changelog**: [{previous}...{tag}](https://github.com/{REPO}/compare/{previous}...{tag})\n'
    else:
        body += f'**Full Changelog**: [History through {tag}](https://github.com/{REPO}/commits/{tag})\n'
    body += (f'\nMinecraft **{info["minecraft"]}** · Fabric / NeoForge / Bukkit · Java 25\n\n'
             'Packaged natives: Windows x64, macOS ARM64, Linux x64.\n\n'
             f'Source: [{info["commit"][:7]}](https://github.com/{REPO}/commit/{info["commit"]})\n\n'
             f'`{jar.name}` SHA-256: `{sha256}`\n')
    return body


def reconcile_latest():
    # Every writer re-reads after updating. A late older run therefore repairs a
    # stale update instead of leaving itself latest. Builds never cancel each other.
    for _ in range(5):
        newest = max(published(), key=lambda r: (release_count(r['tag_name']), r['tag_name']))
        current = api('releases/latest', missing=True)
        if current and current['tag_name'] == newest['tag_name']:
            return
        run('gh', 'release', 'edit', newest['tag_name'], '--repo', REPO, '--latest')
    raise RuntimeError('Concurrent release publication is still changing latest; rerun to reconcile')


def verify_published_assets(tag, assets, expected):
    if sorted(asset['name'] for asset in assets) != sorted(expected):
        raise ValueError('Published release does not contain exactly the expected release JAR')
    with tempfile.TemporaryDirectory(prefix='retina-release-verify-') as directory:
        for name, data in expected.items():
            run('gh', 'release', 'download', tag, '--repo', REPO, '--pattern', name, '--dir', directory)
            if (Path(directory) / name).read_bytes() != data:
                raise ValueError('Rerun differs from the published artifact; refusing to replace it')


def publish():
    info = metadata()
    if not REPO:
        raise ValueError('GH_REPO must identify the destination repository')
    # The checkout is the event SHA; never follow the moving release branch here.
    if info['commit'] != os.environ['GITHUB_SHA']:
        raise ValueError('Checkout does not match the triggering commit')
    jar = Path('dist') / f'retina-{info["minecraft"]}-{info["version"]}.jar'
    expected = {jar.name: jar.read_bytes()}
    contents = expected[jar.name]
    sha256 = hashlib.sha256(contents).hexdigest()
    tag = 'v' + info['version']
    existing = release_for_tag(tag)
    source = tag_commit(tag)
    if source is not None and source != info['commit']:
        raise ValueError(f'{tag} already points to a different commit')
    if existing and not existing['draft']:
        if source != info['commit']:
            raise ValueError('Published release tag does not match the build')
        verify_published_assets(tag, existing['assets'], expected)
        print(f'Existing release verified: {tag} {sha256}')
        reconcile_latest()
        return
    if existing:
        target = api('commits/' + quote(existing['target_commitish'], safe=''))['sha']
        if target != info['commit']:
            raise ValueError('Draft release targets a different commit')
    run('git', 'fetch', '--tags', 'origin')
    body = release_notes(info, tag, sha256, jar)
    with tempfile.TemporaryDirectory(prefix='retina-release-notes-') as directory:
        notes = Path(directory) / 'notes.md'; notes.write_text(body)
        if not existing:
            run('gh', 'release', 'create', tag, '--repo', REPO, '--target', info['commit'],
                '--title', f'Retina {info["version"]}', '--notes-file', str(notes), '--draft', '--latest=false')
        else:
            run('gh', 'release', 'edit', tag, '--repo', REPO,
                '--title', f'Retina {info["version"]}', '--notes-file', str(notes))
        run('gh', 'release', 'upload', tag, str(jar), '--repo', REPO, '--clobber')
        draft = release_for_tag(tag)
        if sorted(asset['name'] for asset in draft['assets']) != sorted(expected):
            raise ValueError('Draft has unexpected assets; refusing to publish an incomplete distribution')
        run('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest=false')
    if tag_commit(tag) != info['commit']:
        raise ValueError('Published tag does not identify the build source')
    reconcile_latest()
    print(f'Published https://github.com/{REPO}/releases/tag/{tag} sha256={sha256}')


if __name__ == '__main__':
    publish()

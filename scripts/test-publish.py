"""Exercise the real Gradle publish task against disposable local Git remotes."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(directory, *args, success=True):
    result = subprocess.run(args, cwd=directory, text=True, capture_output=True)
    if success and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    if not success and not result.returncode:
        raise AssertionError('Expected Git to reject divergent release')
    return result.stdout + result.stderr


def git(directory, *args):
    return run(directory, 'git', *args).strip()


def identity(directory):
    git(directory, 'config', 'user.email', 'publish-test@example.invalid')
    git(directory, 'config', 'user.name', 'Publish test')


with tempfile.TemporaryDirectory(prefix='retina-publish-test-') as temporary:
    root = Path(temporary)
    remote = root / 'remote.git'
    git(root, 'init', '--bare', '--initial-branch=main', str(remote))
    caller = root / 'caller'; caller.mkdir()
    git(caller, 'init', '--initial-branch=main'); identity(caller)
    (caller / 'gradle').mkdir()
    shutil.copy2(ROOT / 'settings.gradle', caller / 'settings.gradle')
    for name in ('publish.gradle', 'publish-only.gradle'):
        shutil.copy2(ROOT / 'gradle' / name, caller / 'gradle' / name)
    (caller / '.gitignore').write_text('.gradle/\n')
    (caller / 'tracked.txt').write_text('original\n')
    git(caller, 'add', '.'); git(caller, 'commit', '-m', 'Initial published main')
    initial = git(caller, 'rev-parse', 'HEAD')
    git(caller, 'remote', 'add', 'origin', str(remote)); git(caller, 'push', 'origin', 'main')
    git(caller, 'switch', '-c', 'local-work')
    (caller / 'unpublished.txt').write_text('local commit\n')
    git(caller, 'add', '.'); git(caller, 'commit', '-m', 'Unpublished local work')
    local_head = git(caller, 'rev-parse', 'HEAD')
    (caller / 'tracked.txt').write_text('dirty user changes\n')
    (caller / 'untracked.txt').write_text('untracked user data\n')
    status = git(caller, 'status', '--porcelain')

    def publish(success=True, alias=False):
        log = run(caller, str(ROOT / 'gradlew'), '-p', str(caller),
                  'releaseRetina' if alias else 'publish', '--no-daemon', '--configuration-cache', success=success)
        if any(token in log for token in ('compileJava', 'buildNative', 'Fabric Loom', 'generateVanilla')):
            raise AssertionError('Publish configured build tooling: ' + log)
        assert git(caller, 'rev-parse', 'HEAD') == local_head
        assert git(caller, 'branch', '--show-current') == 'local-work'
        assert git(caller, 'status', '--porcelain') == status
        assert (caller / 'tracked.txt').read_text() == 'dirty user changes\n'
        assert (caller / 'untracked.txt').read_text() == 'untracked user data\n'
        return log

    publish()
    assert git(remote, 'rev-parse', 'release') == initial
    print('PASS initial release uses remote main and preserves dirty local branch')
    upstream = root / 'upstream'
    git(root, 'clone', str(remote), str(upstream)); identity(upstream)
    (upstream / 'next.txt').write_text('next main\n')
    git(upstream, 'add', '.'); git(upstream, 'commit', '-m', 'Advance main'); git(upstream, 'push', 'origin', 'main')
    advanced = git(upstream, 'rev-parse', 'HEAD')
    publish(alias=True)
    assert git(remote, 'rev-parse', 'release') == advanced
    print('PASS fast-forward fetches newer remote main through compatibility alias')
    log = publish()
    assert git(remote, 'rev-parse', 'release') == advanced
    assert 'Everything up-to-date' in log
    print('PASS already-current publish is a no-op')
    git(upstream, 'switch', '-c', 'divergent-release')
    (upstream / 'release-only.txt').write_text('release divergence\n')
    git(upstream, 'add', '.'); git(upstream, 'commit', '-m', 'Release-only commit')
    divergent = git(upstream, 'rev-parse', 'HEAD')
    git(upstream, 'push', 'origin', 'HEAD:release')
    log = publish(success=False)
    assert 'rejected' in log
    assert git(remote, 'rev-parse', 'release') == divergent
    print('PASS Git rejects divergence without changing remote or local work')

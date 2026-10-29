"""Small real executable-JAR smoke: three dimensions, resume, locks and cancellation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from benchmark_support import decode_nbt, mca_records


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [str(args.java.resolve()), '-jar', str(args.jar.resolve())]
    runs = []
    def run(*arguments, code=0):
        result = subprocess.run(command + list(arguments), capture_output=True, text=True, timeout=180)
        index = len(runs)
        (args.output / f'{index}.stdout').write_text(result.stdout)
        (args.output / f'{index}.stderr').write_text(result.stderr)
        runs.append({'command': command + list(arguments), 'exit': result.returncode})
        (args.output / 'commands.json').write_text(json.dumps(runs, indent=2))
        assert result.returncode == code, result.stderr
        return result.stdout

    run('--help')
    run('generate', '--world', 'unused', '--region-radius', '-1', code=2)
    run('generate', '--world', 'unused', '--region-radius', '0', '--dimension', 'x:../outside', code=2)
    with tempfile.TemporaryDirectory(prefix='retina-jar-cli-') as temporary:
        root = Path(temporary)
        metadata = json.loads(run('init', '--world', temporary, '--seed', '-123456789'))
        original = digest(root / 'retina.dat')
        assert json.loads(run('inspect', '--world', temporary)) == metadata
        assert metadata['seed'] == -123456789
        run('init', '--world', temporary, '--seed', '42', code=2)
        assert digest(root / 'retina.dat') == original
        results = []
        for dimension, folder, coord in [('overworld','region','-1,0'), ('the_nether','DIM-1/region','0,0'), ('the_end','DIM1/region','0,0')]:
            arguments = ['generate','--world',temporary,'--dimension','minecraft:' + dimension,
                         '--region-min=' + coord,'--region-max=' + coord]
            fresh = json.loads(run(*arguments))
            assert fresh['generated_chunks'] == 1024 and fresh['preserved_chunks'] == 0, fresh
            x,z = map(int, coord.split(','))
            region = root / folder / f'r.{x}.{z}.mca'
            records = mca_records(region)
            assert len(records) == 1024
            for slot in (0,1023):
                nbt = next(iter(decode_nbt(records[slot]).values()))
                assert nbt['xPos'] == x * 32 + slot % 32 and nbt['zPos'] == z * 32 + slot // 32
                assert nbt['DataVersion'] == metadata['data_version']
            before = digest(region)
            resumed = json.loads(run(*arguments))
            assert resumed['generated_chunks'] == 0 and resumed['preserved_chunks'] == 1024, resumed
            assert digest(region) == before
            assert digest(root / 'retina.dat') == original
            results.append({'dimension':dimension,'region_sha256':before,'generated':fresh,'resumed':resumed})
        if os.name == 'posix':
            import fcntl
            with (root / 'session.lock').open('r+b') as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                run('generate','--world',temporary,'--region-radius','0',code=1)
            # One existing End region followed by one new region. Stop between calls
            # or during the second; cancellation must leave only complete region files.
            cancel_command = command + ['generate','--world',temporary,'--dimension','minecraft:the_end',
                                        '--region-min=0,0','--region-max=1,0']
            with (args.output / 'cancel.stdout').open('w') as stdout:
                process = subprocess.Popen(cancel_command, stdout=stdout, stderr=subprocess.PIPE, text=True)
                lines = []
                for line in process.stderr:
                    lines.append(line)
                    if line.startswith('Region '):
                        process.terminate()
                        break
                rest = process.communicate(timeout=180)[1]; lines.append(rest)
            (args.output / 'cancel.stderr').write_text(''.join(lines))
            cancelled = json.loads((args.output / 'cancel.stdout').read_text())
            assert cancelled['status'] in ('cancelled','complete'), cancelled
            for region in (root / 'DIM1/region').glob('*.mca'): assert len(mca_records(region)) == 1024
            results.append({'cancellation':cancelled,'exit':process.returncode,'command':cancel_command})
        assert digest(root / 'retina.dat') == original
        report = {'jar_sha256':digest(args.jar),'definition_sha256':original,'metadata':metadata,'results':results,
                  'temporary_worlds_deleted_on_exit':True}
        (args.output / 'results.json').write_text(json.dumps(report,indent=2))
    print('Executable universal JAR: create/inspect, all dimensions, preservation, lock and cancellation PASS')


if __name__ == '__main__':
    main()

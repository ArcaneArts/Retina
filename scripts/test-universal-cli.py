"""Small real executable-JAR smoke: three dimensions, resume, locks and cancellation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
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
    for invalid in ('0', '17', 'four'):
        run('generate', '--world', 'unused', '--region-radius', '0', '--parallel', invalid, code=2)
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
            with region.open('rb') as stream:
                first_sector = int.from_bytes(stream.read(3), 'big')
                stream.seek(first_sector * 4096 + 4)
                assert stream.read(1) == b'\x03', 'headless JAR defaults to raw NBT'
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
        end = root / 'DIM1/region'
        prior = digest(end / 'r.0.0.mca')
        batch = ['generate', '--world', temporary, '--dimension', 'minecraft:the_end',
                 '--region-min=0,0', '--region-max=2,0', '--parallel', '4']
        fresh = json.loads(run(*batch))
        assert (fresh['regions'], fresh['generated_chunks'], fresh['preserved_chunks']) == (3, 2048, 1024), fresh
        assert digest(end / 'r.0.0.mca') == prior
        reference = root / 'serial-reference'
        serial = json.loads(run('generate', '--world', str(reference), '--definition', str(root / 'retina.dat'),
                                '--dimension', 'minecraft:the_end', '--region-min=1,0', '--region-max=2,0'))
        assert serial['generated_chunks'] == 2048, serial
        for name in ('r.1.0.mca', 'r.2.0.mca'):
            records = mca_records(end / name)
            assert len(records) == 1024 and records == mca_records(reference / 'DIM1/region' / name)
        before = {region.name: digest(region) for region in end.glob('*.mca')}
        resumed = json.loads(run(*batch))
        assert resumed['generated_chunks'] == 0 and resumed['preserved_chunks'] == 3072, resumed
        assert all(digest(end / name) == checksum for name, checksum in before.items())
        results.append({'parallel': fresh, 'parallel_resume': resumed})
        failed_world = root / 'failed-region'
        failed_region = failed_world / 'DIM1/region/r.0.0.mca'
        failed_region.parent.mkdir(parents=True)
        failed_region.write_bytes(b'truncated MCA')
        failed_batch = ['generate', '--world', str(failed_world), '--definition', str(root / 'retina.dat'),
                        '--dimension', 'minecraft:the_end', '--region-min=0,0', '--region-max=7,0',
                        '--parallel', '2']
        run(*failed_batch, code=1)
        assert failed_region.read_bytes() == b'truncated MCA'
        failed_region.unlink()
        partial = list(failed_region.parent.glob('*.mca'))
        assert len(partial) < 8 and all(len(mca_records(region)) == 1024 for region in partial)
        retry = json.loads(run(*failed_batch))
        assert retry['generated_chunks'] + retry['preserved_chunks'] == 8 * 1024, retry
        assert retry['preserved_chunks'] == len(partial) * 1024, retry
        if os.name == 'posix':
            import fcntl
            with (root / 'session.lock').open('r+b') as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                run('generate','--world',temporary,'--region-radius','0',code=1)
            # Finish in-flight writes after SIGTERM with more regions still eligible.
            cancel_command = command + ['generate','--world',temporary,'--dimension','minecraft:the_end',
                                        '--region-min=3,0','--region-max=18,0','--parallel','2']
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
            assert cancelled['status'] == 'cancelled', cancelled
            assert process.returncode in (130, 128 + signal.SIGTERM), process.returncode
            assert 1 <= cancelled['regions'] < 16, cancelled
            assert cancelled['generated_chunks'] == 1024 * cancelled['regions'], cancelled
            assert len(list(end.glob('*.mca'))) == len(before) + cancelled['regions']
            for region in end.glob('*.mca'): assert len(mca_records(region)) == 1024
            assert all(digest(end / name) == checksum for name, checksum in before.items())
            resumed = json.loads(run('generate', '--world', temporary, '--dimension', 'minecraft:the_end',
                                     '--region-min=3,0', '--region-max=3,0', '--parallel', '2'))
            assert resumed['generated_chunks'] + resumed['preserved_chunks'] == 1024, resumed
            results.append({'cancellation':cancelled,'exit':process.returncode,'command':cancel_command})
        assert digest(root / 'retina.dat') == original
        report = {'jar_sha256':digest(args.jar),'definition_sha256':original,'metadata':metadata,'results':results,
                  'temporary_worlds_deleted_on_exit':True}
        (args.output / 'results.json').write_text(json.dumps(report,indent=2))
    print('Executable universal JAR: create/inspect, all dimensions, parallel parity, preservation, lock and cancellation PASS')


if __name__ == '__main__':
    main()

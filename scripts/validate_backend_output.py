#!/usr/bin/env python3
"""Bounded offline validation of saved backend MCA output, including partial runs.

Only reads recorded files; never loads a native generator, compiles, launches
Minecraft or measures generation. The separate validation report cannot turn an
interrupted benchmark into a completed one. One Python process decodes one chunk
at a time at reduced priority; the supervisor bounds the entire audit.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

from benchmark_limits import positive_seconds, supervise
from test_bend_chunks import MAP_NAMES, palette, unpack
from test_bend_formats import iter_region


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_report(path, report):
    temporary = path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(report, indent=2)+'\n')
    temporary.replace(path)


def validate(path, profile, rx, rz, progress=None):
    data = path.read_bytes()
    identity = dict(bytes=len(data), sha256=digest(data))
    def key(state):
        return (state, ()) if isinstance(state, str) else (state['id'], tuple(sorted(state.get('properties', {}).items())))
    materials = {key(state) for state in profile['materials']}
    biomes = {biome['id'] for biome in profile['biomes']}
    first = profile['geology_min_y']//16
    height = profile['geology_height']
    end = first+height//16
    seen = set()
    totals = dict(chunks=0, lit_chunks=0, structure_starts=0, block_entities=0, entities=0,
                  block_indices=0, biome_indices=0, heightmap_entries=0)
    if progress:
        progress(dict(totals, **identity))
    for slot, record in iter_region(data):
        try:
            tag = record['tag']
            lit = 0 < slot%32 < 31 and 0 < slot//32 < 31
            assert (tag['xPos'], tag['zPos'], tag['yPos'], tag['DataVersion']) == (rx*32+slot%32, rz*32+slot//32, first, 5023)
            assert tag['Status'] == ('minecraft:light' if lit else 'minecraft:features') and tag['isLightOn'] == int(lit)
            assert set(tag['Heightmaps']) == set(MAP_NAMES)
            for packed in tag['Heightmaps'].values():
                assert all(0 <= h <= height for h in unpack(packed, 256, height.bit_length()))
                totals['heightmap_entries'] += 256
            sections = tag['sections']
            assert len(sections) == end-first+(2 if lit else 0)
            assert {s['Y'] for s in sections} == set(range(first-int(lit), end+int(lit)))
            for section in sections:
                for channel in ('SkyLight', 'BlockLight'):
                    # Absent all-zero arrays are valid: Rust omits them and
                    # Bend writes explicit zero arrays.
                    if channel in section:
                        assert lit and len(section[channel]) == 2048
                if first <= section['Y'] < end:
                    states = section['block_states']
                    assert all(key(state) in materials for state in states['palette'])
                    palette(states, 4096, 4)
                    assert all(biome in biomes for biome in palette(section['biomes'], 64, 1))
                    totals['block_indices'] += 4096
                    totals['biome_indices'] += 64
                else:
                    assert set(section) <= {'Y', 'SkyLight', 'BlockLight'}
            if lit and profile['lighting']['sky']:
                assert any(any(s.get('SkyLight', b'')) for s in sections), 'No sunlight in a lit Overworld chunk'
            totals['chunks'] += 1
            totals['lit_chunks'] += tag['isLightOn']
            totals['structure_starts'] += len(tag['structures']['starts'])
            totals['block_entities'] += len(tag['block_entities'])
            totals['entities'] += len(tag.get('entities', []))
            seen.add(slot)
            if progress and totals['chunks'] % 64 == 0:
                progress(dict(totals, **identity))
        except (AssertionError, KeyError, TypeError, ValueError) as error:
            raise AssertionError(f'{path}: chunk slot {slot}: {error}') from error
    assert seen == set(range(1024)), f'{path}: missing chunk slots {sorted(set(range(1024))-seen)}'
    return dict(totals, **identity)


def audit(report_path, profile_path, output, backend=None):
    original = report_path.read_bytes()
    source = json.loads(original)
    raw_profile = profile_path.read_bytes()
    profile = json.loads(raw_profile)
    report = dict(schema='retina.saved-output-validation.v1', scope=__doc__.strip(),
                  status='running', source_report=str(report_path.resolve()),
                  source_report_sha256=digest(original), generation_status=source.get('status'),
                  generation_error=source.get('error'), backend=source.get('backend'),
                  recorded_artifact_sha256=source.get('artifact_sha256'),
                  profile_sha256=digest(raw_profile), regions=[],
                  sources={name: digest(Path(__file__).with_name(name).read_bytes()) for name in
                           ('validate_backend_output.py', 'test_bend_formats.py', 'test_bend_chunks.py',
                            'test_bend_binary.py', 'benchmark_limits.py')},
                  limitations=['Validates saved-file formats and registry membership only; does not rerun generation.',
                               'Does not prove lighting propagation, heightmap agreement with blocks, biome/terrain parity or Minecraft loading.',
                               'Does not change the original benchmark status, timing samples or errors.'])
    destination = output/'report.json'
    write_report(destination, report)
    try:
        assert source['profile_sha256'] == report['profile_sha256'], 'Recorded profile differs from supplied profile'
        if backend is not None:
            assert source['backend'] == backend, 'Recorded backend differs from requested backend'
        assert source['regions'], 'No completed region files recorded'
        report['recorded_regions'] = len(source['regions'])
        for row in source['regions']:
            path = Path(row['path'])
            report['current_region'] = dict(path=str(path), rx=row['rx'], rz=row['rz'], chunks=0)
            write_report(destination, report)
            def progress(counts):
                report['current_region'].update(counts)
                write_report(destination, report)
            result = validate(path, profile, row['rx'], row['rz'], progress)
            assert result['bytes'] == row['bytes'], f'Recorded file size changed: {path}'
            previous = row.get('validation', {})
            if previous and 'sha256' in previous:
                assert result['sha256'] == previous['sha256'], f'Previously validated file changed: {path}'
            report['regions'].append(dict(path=str(path), rx=row['rx'], rz=row['rz'], validation=result))
            report.pop('current_region')
            write_report(destination, report)
            print(f"PASS saved file {path.name}: {result['chunks']} chunks", flush=True)
        report['status'] = 'passed'
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        write_report(destination, report)
    return report


def finish_control(output):
    """Finalize a killed child's checkpoint from the supervisor's actual result."""
    destination = output/'report.json'
    if destination.exists():
        control = json.loads((output/'run-control.json').read_text())
        report = json.loads(destination.read_text())
        report['run_control'] = control
        if control['status'] != 'completed':
            report['status'] = control['status']
        write_report(destination, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True, help='Existing benchmark report; read only')
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True, help='New directory for separate audit/checkpoints')
    parser.add_argument('--backend', choices=['rust', 'bend'])
    parser.add_argument('--max-seconds', type=positive_seconds, default=10., help='Total audit limit; no automatic retry (default: 10 seconds)')
    args = parser.parse_args()
    # Set an absolute floor, rather than incrementing inherited nice repeatedly.
    os.setpriority(os.PRIO_PROCESS, 0, max(10, os.getpriority(os.PRIO_PROCESS, 0)))
    result = supervise(__file__, args.max_seconds, args.out)
    if result is not None:
        finish_control(args.out)
        return result
    report = audit(args.report, args.profile, args.out, args.backend)
    print(f"PASS saved-file validation: {len(report['regions'])} regions; generation status remains {report['generation_status']}", flush=True)


if __name__ == '__main__':
    raise SystemExit(main())

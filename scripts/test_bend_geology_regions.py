#!/usr/bin/env python3
"""Keep complete base/material MCA serialization unchanged with resident geology.

A typed geology model is prepared before generation and retained through every
chunk/region query. This checks complete file/state integration of that model;
it does not claim caves are carved or that world-generator parity is complete.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import signal
import struct

import test_bend_generated_regions as regions
from test_bend_geology import fixtures as geology_fixtures
from test_bend_registry_noise import fixture_wire
from test_bend_compression import ROOT


class GeologyWorker(regions.Worker):
    def call(self, opcode, data=b'', status=0, fragment=False):
        result=super().call(opcode,data,status,fragment)
        if opcode==25 and status==0:
            counts=struct.unpack('>8I',super().call(30))
            assert counts[0]==6 and counts[1]>0 and counts[3]>0
        return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metal-probe',type=Path)
    parser.add_argument('--baseline-report',type=Path)
    args=parser.parse_args()
    folder=ROOT/'build/bend/geology-regions';folder.mkdir(parents=True,exist_ok=True)
    raw=regions.fixtures(folder/'fixtures')[0]
    name,source,_=raw;source=copy.deepcopy(source)
    geology=geology_fixtures(folder/'geology')[0][0][1]
    source['cave_noises']=geology['cave_noises']
    source['carveable']=[i not in (0,source['water']) for i in range(len(source['materials']))]
    source['lava']=source['stone'];source['lava_level']=source['geology_min_y']+3
    for i,biome in enumerate(source['biomes']):
        biome['carvers']=copy.deepcopy(geology['biomes'][i%len(geology['biomes'])]['carvers'])
    path=folder/'ramp.rbp';path.write_bytes(fixture_wire(source,True));profiles=[(name,source,path)]
    regions.Worker=GeologyWorker
    binary=ROOT/'build/bend/engine'
    report=dict(scope=__doc__.strip(),runs=[regions.exercise(binary,profiles,False,folder,5023,repeat=False),
                                          regions.exercise(binary,profiles,True,folder,5023,repeat=False)])
    expected={gpu:report['runs'][gpu]['regions'][0]['sha256'] for gpu in (0,1)}
    if args.metal_probe:
        result=regions.exercise(args.metal_probe,profiles,True,folder,5023,repeat=False)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['regions'][0]['sha256']==expected[1]
        report['actual_metal_proof']=result
    if args.baseline_report:
        baseline=json.loads(args.baseline_report.read_text())
        for gpu in (0,1):
            old=next(row['sha256'] for run in baseline['runs'] if run['gpu_required']==bool(gpu)
                     for row in run['regions'] if row['name']=='ramp')
            assert expected[gpu]==old,(gpu,expected[gpu],old)
        report['baseline_files']='byte-identical by SHA-256 to prior independently validated MCA output'
        report['baseline_report_sha256']=hashlib.sha256(args.baseline_report.read_bytes()).hexdigest()
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['input_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    output=ROOT/'build/bend/geology-region-tests.json';output.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: resident geology retains complete base/material MCA bytes;',output)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('geology region test timeout')))
    main()

#!/usr/bin/env python3
"""Check loaded registry optics projection without substituting synthetic rules."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources
from test_bend_compression import ROOT


def exercise(binary, profiles, output):
    output.mkdir(parents=True, exist_ok=True)
    results=[]
    for index,path in enumerate(profiles):
        profile=json.loads(path.read_text())
        # Only transport fields consumed by this projection; values are unchanged.
        value={key:profile[key] for key in ('lighting','materials')}
        source=output/f'profile-{index}.rbp';target=output/f'profile-{index}.bin'
        source.write_bytes(fixture_wire(value,True))
        process=subprocess.run(['nice','-n','10',str(binary),str(source),str(target),'--threads','2','--gpu','off'],capture_output=True,check=True,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        optical=value['lighting'];n=len(optical['states']);coverage=optical['blocked']
        words=[int(optical['sky']),(optical['faces']+31)//32,n,len(coverage)]
        words += [v for row in optical['states'] for v in row[:7]+[0]]
        words += coverage
        expected=struct.pack('>'+str(len(words))+'I',*words)
        assert target.read_bytes()==expected
        results.append(dict(profile_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),states=n,faces=optical['faces'],sha256=hashlib.sha256(expected).hexdigest()))
    base={key:json.loads(profiles[0].read_text())[key] for key in ('lighting','materials')}
    mutations=[('missing-lighting',lambda p:p.pop('lighting')),
      ('missing-sky',lambda p:p['lighting'].pop('sky')),
      ('invalid-sky',lambda p:p['lighting'].__setitem__('sky',3)),
      ('zero-faces',lambda p:p['lighting'].__setitem__('faces',0)),
      ('negative-faces',lambda p:p['lighting'].__setitem__('faces',-1)),
      ('oversize-faces',lambda p:p['lighting'].__setitem__('faces',16385)),
      ('missing-state',lambda p:p['lighting']['states'].pop()),
      ('short-state',lambda p:p['lighting']['states'][0].pop()),
      ('negative-traits',lambda p:p['lighting']['states'][0].__setitem__(0,-1)),
      ('oversize-traits',lambda p:p['lighting']['states'][0].__setitem__(0,256)),
      ('invalid-face',lambda p:p['lighting']['states'][0].__setitem__(1,p['lighting']['faces'])),
      ('short-coverage',lambda p:p['lighting']['blocked'].pop()),
      ('empty-palette',lambda p:p.__setitem__('materials',[]))]
    invalid=[]
    for name,mutate in mutations:
        value=copy.deepcopy(base);mutate(value)
        source=output/(name+'.rbp');target=output/(name+'.bin')
        source.write_bytes(fixture_wire(value,True))
        process=subprocess.run(['nice','-n','10',str(binary),str(source),str(target),'--threads','2','--gpu','off'],capture_output=True,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        assert process.returncode!=0 and b'invalid registered lighting' in process.stderr,(name,process.returncode,process.stderr)
        assert not target.exists(),name
        invalid.append(name)
    result=dict(profiles=results,invalid_rejected=invalid,sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources(ROOT/'bend/tests/registry-lighting.bend')|{Path(__file__).resolve()}},binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    (output/'report.json').write_text(json.dumps(result,indent=2)+'\n')
    print('PASS registry lighting:',len(results),'profiles;',len(invalid),'invalid inputs')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=ROOT/'build/bend/lighting/registry')
    parser.add_argument('--profile',type=Path,action='append',required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/bend/lighting/registry-tests')
    args=parser.parse_args();exercise(args.binary,args.profile,args.output)

if __name__=='__main__':main()

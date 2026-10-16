#!/usr/bin/env python3
"""Independently verify halo-cropped Bend light arrays inside compressed MCA.

Controlled terrain uses loaded Minecraft optics. The oracle floods the entire
input volume; Bend lights separate halo tiles, crops them, packs nibbles, and
serializes actual chunks. This is component evidence, not full gameplay parity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from test_bend_registry_noise import fixture_wire
from test_bend_light_volume import reference
from test_bend_formats import read_region
from test_bend_chunks import palette
from test_bend_compression import ROOT
from test_bend_placement_merge import sources


def fixtures(profile):
    names=[s if isinstance(s,str) else s['id'] for s in profile['materials']]
    def material(name):return next(i for i,s in enumerate(names) if s.split('[')[0]=='minecraft:'+name)
    stone,water,leaves=map(material,['stone','water','oak_leaves'])
    emitter=next(i for i,s in enumerate(profile['lighting']['states']) if (s[0]>>4)==15)
    partial=next(i for i,s in enumerate(profile['lighting']['states']) if any(s[1:7]) and (s[0]&15)<15)
    for width in (48,160):
        height,depth=32,48;blocks=[0]*(width*height*depth)
        def put(x,y,z,m):blocks[(y*depth+z)*width+x]=m
        for z in range(depth):
            for x in range(width):
                put(x,0,z,stone)
                if z<24:
                    for y in range(1,12):put(x,y,z,water)
                if 8<x<width-8 and 7<z<depth-8:
                    if (x%13,z%11)!=(4,3):put(x,24,z,stone)
                if x%11==0:put(x,18,z,leaves)
                if x%7==0:put(x,16,z,partial)
        for x in (0,15,16,31,32,width-17,width-16,110,111,112,113):
            if x>=width:continue
            for z in (15,16,31,32):put(x,15,z,emitter)
        yield dict(name='halo-'+str(width),w=width,h=height,d=depth,sky=profile['lighting']['sky'],blocks=blocks)


def check(path,case,profile):
    chunks=read_region(path.read_bytes());w,h,d=case['w'],case['h'],case['d']
    assert len(chunks)==w*d//256
    expected=reference(case,profile['lighting']);lit_count=0;bytes_checked=0;blocks_checked=0
    for row in chunks.values():
        tag=row['tag'];cx,cz=tag['xPos']+32,tag['zPos']+32
        assert 0<=cx<w//16 and 0<=cz<d//16
        lit=0<cx<w//16-1 and 0<cz<d//16-1
        assert tag['isLightOn']==int(lit)
        assert tag['Status']==('minecraft:light' if lit else 'minecraft:features')
        assert len(tag['sections'])==h//16+(2 if lit else 0)
        lit_count+=lit
        for section in tag['sections']:
            sy=section['Y'];relative=sy+4
            if lit:
                block=section['BlockLight'];sky=section['SkyLight']
                assert len(block)==len(sky)==2048
                for i in range(4096):
                    actual=((block[i//2]>>((i%2)*4))&15)|(((sky[i//2]>>((i%2)*4))&15)<<4)
                    x=cx*16+i%16;z=cz*16+(i//16)%16;y=(relative+1)*16+i//256
                    wanted=expected[(y*d+z)*w+x]
                    assert actual==wanted,(case['name'],cx,cz,sy,i,actual,wanted)
                bytes_checked+=4096
            else:assert 'BlockLight' not in section and 'SkyLight' not in section
            if relative<0 or relative>=h//16:
                assert set(section)=={'Y','BlockLight','SkyLight'}
                continue
            actual_blocks=palette(section['block_states'],4096,4)
            for i,actual in enumerate(actual_blocks):
                x=cx*16+i%16;z=cz*16+(i//16)%16;y=relative*16+i//256
                wanted=profile['materials'][case['blocks'][(y*d+z)*w+x]]
                if isinstance(wanted,str):wanted={'id':wanted}
                assert actual==wanted,(case['name'],cx,cz,sy,i,actual,wanted)
            blocks_checked+=4096
    assert lit_count==(w//16-2)*(d//16-2)
    return dict(chunks=len(chunks),lit_chunks=lit_count,light_values_checked=bytes_checked,blocks_checked=blocks_checked,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=ROOT/'build/bend/lighting/region')
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--gpu',action='store_true')
    parser.add_argument('--case')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args();profile=json.loads(args.profile.read_text());results=[]
    folder=args.output or ROOT/'build/bend/lighting'/('region-gpu' if args.gpu else 'region-cpu');folder.mkdir(parents=True,exist_ok=True)
    for case in fixtures(profile):
        if args.case and args.case!=case['name']:continue
        source=folder/(case['name']+'.rbp');target=folder/(case['name']+'.mca')
        payload=dict(profile,params=[case['w'],case['h'],case['d'],-512,-512,123456789,123,-64],blocks=case['blocks'])
        source.write_bytes(fixture_wire(payload,True))
        start=time.monotonic()
        p=subprocess.run(['nice','-n','10',str(args.binary),str(source),str(target),'--threads','2','--gpu','4GB' if args.gpu else 'off'],check=True,capture_output=True,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        elapsed=(time.monotonic()-start)*1000
        (folder/(case['name']+'.log')).write_bytes(p.stdout+p.stderr)
        result=dict(name=case['name'],wall_ms=elapsed,**check(target,case,profile));results.append(result);print('PASS',result,flush=True)
    assert results
    report=dict(results=results,gpu_requested=args.gpu,cpu_threads=2,profile_sha256=hashlib.sha256(args.profile.read_bytes()).hexdigest(),binary_sha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources(ROOT/'bend/tests/light-region.bend')|{Path(__file__).resolve(),ROOT/'scripts/test_bend_light_volume.py'}})
    (folder/'report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()

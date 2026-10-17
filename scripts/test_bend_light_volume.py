#!/usr/bin/env python3
"""Compare Bend's brick lighting to an independent scalar flood fill.

Uses actual exported Minecraft optics and paired face coverage. Tests both light
channels, opaque emitters, fluids/leaves, partial shapes, isolated cavities,
brick boundaries and virtual top/bottom sections. No MCA/whole-region claim.
"""
import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import time

from test_bend_compression import ROOT
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fixtures(profile):
    names = [s if isinstance(s,str) else s['id'] for s in profile['materials']]
    states = profile['lighting']['states']
    def material(name):
        return next(i for i, s in enumerate(names) if s.split('[')[0] == 'minecraft:' + name)
    stone, water = material('stone'), material('water')
    leaves = material('oak_leaves')
    emitter = next(i for i, s in enumerate(states) if (s[0] >> 4) == 15)
    opaque_emitter = next(i for i, s in enumerate(states) if (s[0] & 255) == 255)
    partial = [i for i, s in enumerate(states) if any(s[1:7]) and (s[0] & 15) < 15]
    assert partial and states[emitter][0] >> 4 == 15
    rng = random.Random(20261016)
    cases = []
    def new(name, sky=True, w=24, h=32, d=24):
        blocks = [0] * (w*h*d)
        def put(x, y, z, m):
            blocks[(y*d+z)*w+x] = m
        cases.append(dict(name=name, sky=sky, w=w, h=h, d=d, blocks=blocks))
        return put, blocks
    new('open-sky')
    new('dark-air', False)
    put, _ = new('opaque-roof-and-sideways-sky')
    for z in range(24):
        for x in range(24):
            put(x, 23, z, stone)
    put(12, 23, 12, 0)
    put, _ = new('sealed-emitter', False)
    for z in range(24):
        for x in range(24):
            for y in range(32):
                if x in (0,23) or z in (0,23) or y in (0,31): put(x,y,z,stone)
    put(7,15,8,emitter)
    # A completely opaque brick must keep its own emission even when skipped
    # by propagation; adjacent air must still receive light from that brick.
    put, _ = new('opaque-emitter-brick',False)
    for z in range(8,16):
        for x in range(8,16):
            for y in range(8,16): put(x,y,z,stone)
    put(15,11,11,opaque_emitter)
    put, _ = new('water-leaves-and-partial-faces')
    for z in range(24):
        for x in range(24):
            put(x,0,z,stone)
            if x < 8:
                for y in range(1,19): put(x,y,z,water)
            elif x < 16:
                for y in range(12,17): put(x,y,z,leaves)
            else:
                for y in (7,8,15,16): put(x,y,z,partial[(x*29+z*7+y)%len(partial)])
    put, _ = new('emitters-across-brick-faces',False,w=40,h=48,d=24)
    for x,y,z in [(7,7,7),(8,8,8),(15,15,15),(16,16,16),(31,31,7),(32,32,8),(0,0,0),(39,47,23)]:
        put(x,y,z,emitter)
    put, _ = new('sun-through-world-bottom')
    for z in range(24):
        for x in range(24):
            if not (x in (7,8,15,16) and z in (7,8,15,16)):
                for y in range(4): put(x,y,z,stone)
    for sky in (False,True):
        put, _ = new('random-optics-'+str(sky),sky)
        for z in range(24):
            for x in range(24):
                for y in range(32):
                    r = rng.random()
                    if r < .035: put(x,y,z,rng.choice(partial))
                    elif r < .045: put(x,y,z,emitter)
                    elif r < .16: put(x,y,z,stone)
                    elif r < .22: put(x,y,z,water)
                    elif r < .27: put(x,y,z,leaves)
    # Every actual registered material participates, including low emissions.
    put, _ = new('registered-optics-gallery',True,w=40,h=48,d=24)
    for i in range(len(states)):
        put(i%40, 1+(i//(40*24))*8, (i//40)%24, i)
    return cases


def reference(case, optical):
    w,h,d = case['w'],case['h'],case['d']
    height,plane = h+32,w*d
    mats = [0]*(height*plane)
    mats[16*plane:(16+h)*plane] = case['blocks']
    states = optical['states'];coverage=optical['blocked'];stride=(optical['faces']+31)//32
    def occludes(a,b,direction):
        a=states[a][1+direction];b=states[b][1+(direction^1)]
        return bool(coverage[a*stride+b//32] & (1<<(b%32)))
    block=bytearray((states[m][0]>>4)&15 for m in mats)
    sky=bytearray(len(mats))
    if case['sky']:
        for z in range(d):
            for x in range(w):
                above=0;sun=True
                for y in reversed(range(height)):
                    i=(y*d+z)*w+x;m=mats[i]
                    sun=sun and (states[m][0]&15)==0 and not occludes(above,m,0)
                    if sun: sky[i]=15
                    above=m
    # Queue-based fixed point is independent of Bend's fourteen Jacobi passes,
    # sparse-brick classification, column run scan and packed tree representation.
    for light in (block,sky):
        queue=deque(i for i,v in enumerate(light) if v>1)
        while queue:
            i=queue.popleft();v=light[i]
            x=i%w;z=(i//w)%d;y=i//plane
            adjacent=((i-plane,0,y>0),(i+plane,1,y+1<height),
                      (i-w,2,z>0),(i+w,3,z+1<d),(i-1,4,x>0),(i+1,5,x+1<w))
            for j,direction,inside in adjacent:
                if not inside: continue
                loss=max(1,states[mats[j]][0]&15)
                if v-loss<=light[j] or occludes(mats[i],mats[j],direction): continue
                light[j]=v-loss;queue.append(j)
    return bytes(a|(b<<4) for a,b in zip(block,sky))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=ROOT/'build/bend/lighting/volume')
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--gpu',action='store_true')
    parser.add_argument('--case')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args();profile=json.loads(args.profile.read_text())
    optical=profile['lighting'];folder=args.output or ROOT/'build/bend/lighting'/('gpu' if args.gpu else 'cpu')
    folder.mkdir(parents=True,exist_ok=True);results=[]
    for case in fixtures(profile):
        if args.case and args.case!=case['name']: continue
        source=folder/(case['name']+'.rbp');target=folder/(case['name']+'.bin')
        source.write_bytes(fixture_wire(dict(params=[case['w'],case['h'],case['d'],int(case['sky']),(optical['faces']+31)//32],
                          states=[v for row in optical['states'] for v in row],blocked=optical['blocked'],blocks=case['blocks']),True))
        expected=reference(case,optical)
        command=['nice','-n','10',str(args.binary),str(source),str(target),'--threads','2','--gpu','4GB' if args.gpu else 'off']
        start=time.monotonic()
        process=subprocess.run(command,check=True,capture_output=True,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        elapsed=(time.monotonic()-start)*1000
        (folder/(case['name']+'.log')).write_bytes(process.stderr)
        actual=target.read_bytes()
        assert len(actual)==len(expected),(case['name'],len(actual),len(expected))
        mismatches=[(i,a,e) for i,(a,e) in enumerate(zip(actual,expected)) if a!=e]
        assert not mismatches,(case['name'],len(mismatches),mismatches[:20])
        dispatches=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([\d.]+)',process.stderr)]
        results.append(dict(name=case['name'],voxels=len(actual),sha256=sha(actual),wall_ms=elapsed,metal_dispatch_ms=dispatches))
        print('PASS',case['name'],len(actual),round(elapsed,2),flush=True)
    assert results,'no matching fixture'
    report=dict(scope=__doc__.strip(),cpu_threads=2,gpu_requested=args.gpu,profile_sha256=sha(args.profile.read_bytes()),
                binary_sha256=sha(args.binary.read_bytes()),results=results,
                sources={str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in sources(ROOT/'bend/tests/light-volume.bend')|{Path(__file__).resolve()}})
    (folder/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':main()

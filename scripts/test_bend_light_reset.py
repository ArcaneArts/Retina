#!/usr/bin/env python3
"""Independently decode Bend's repair records and check unrelated typed NBT."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import zlib

from test_bend_binary import NbtReader
from test_bend_compression import ROOT
from test_bend_registry_noise import fixture_wire
from test_bend_structure_nbt import expected
from test_bend_placement_merge import sources


def fixture():
    sections=[]
    for y in range(-5,21):
        value={'Y':[1,y],'BlockLight':[7,[-1]*2048],'SkyLight':[7,[17]*2048]}
        if -4<=y<20:
            value['block_states']=[10,{'palette':[9,[10,[[10,{'Name':[8,'minecraft:air']}]]]]}]
            value['biomes']=[10,{'palette':[9,[8,[[8,'minecraft:plains']]]]}]
        sections.append([10,value])
    return [10,{'xPos':[3,-31],'zPos':[3,1],'isLightOn':[1,1],
        'Status':[8,'minecraft:light'],'sections':[9,[10,sections]],
        'Heightmaps':[10,{'WORLD_SURFACE':[12,[-1,-(1<<63),(1<<63)-1]]}],
        'entities':[9,[10,[[10,{'id':[8,'minecraft:armor_stand'],
            'UUID':[11,[-1,0,1,-2147483648]],'Pos':[9,[6,[[6,-511.5],[6,64.0],[6,15.75]]]]}]]]],
        'block_entities':[9,[10,[[10,{'LootTableSeed':[4,-(1<<63)],'custom':[8,'A\0é😀\ud800']}]]]],
        'unrelated':[10,{'BlockLight':[7,[1,2,3]],'SkyLight':[2,-12],'empty':[9,[0,[]]]}]}]


def verify(record,tag):
    size=struct.unpack('>I',record[:4])[0]
    assert record[4]==2 and len(record)%4096==0 and 0<len(record)//4096<=255
    assert not any(record[4+size:])
    decoder=zlib.decompressobj();raw=decoder.decompress(record[5:4+size])+decoder.flush()
    assert decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail
    reader=NbtReader(raw);assert reader.number('>B')==10 and reader.text()==''
    actual=reader.tag(10);assert reader.offset==len(raw)
    wanted=expected(tag);wanted['Status']='minecraft:features';wanted['isLightOn']=0
    wanted['sections']=[{k:v for k,v in s.items() if k not in ('BlockLight','SkyLight')}
                        for s in wanted['sections'] if 'block_states' in s]
    assert actual==wanted
    return dict(record_bytes=len(record),nbt_bytes=len(raw),sections=len(actual['sections']),
                sha256=hashlib.sha256(record).hexdigest())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=ROOT/'build/bend/lighting/reset')
    args=parser.parse_args();folder=ROOT/'build/bend/lighting/reset-tests';folder.mkdir(parents=True,exist_ok=True)
    results=[]
    for lit in (True,False):
        tag=fixture()
        if not lit:
            tag[1]['isLightOn']=[1,0];tag[1]['Status']=[8,'minecraft:features']
            tag[1]['sections'][1][1]=tag[1]['sections'][1][1][1:-1]
            for section in tag[1]['sections'][1][1]:
                section[1].pop('BlockLight');section[1].pop('SkyLight')
        inp=folder/f'{lit}.rbp';out=folder/f'{lit}.record'
        inp.write_bytes(fixture_wire({'chunk':tag,'unlit_status':'minecraft:features'},True))
        subprocess.run(['nice','-n','10',str(args.binary),str(inp),str(out),'--threads','2','--gpu','off'],check=True)
        results.append(verify(out.read_bytes(),tag))
    assert results[0]['sha256']==results[1]['sha256'],'reset is not idempotent'
    invalid=[]
    for name,tag in [('wrong-root',[3,2]),('missing-sections',[10,{}]),('wrong-section-type',[10,{'sections':[9,[3,[[3,1]]]]}])]:
        inp=folder/(name+'.rbp');out=folder/(name+'.record')
        inp.write_bytes(fixture_wire({'chunk':tag,'unlit_status':'minecraft:features'},True))
        out.unlink(missing_ok=True)
        p=subprocess.run(['nice','-n','10',str(args.binary),str(inp),str(out),'--threads','2','--gpu','off'],capture_output=True)
        assert p.returncode!=0 and not out.exists(),name
        invalid.append(name)
    report=dict(results=results,invalid=invalid,binary_sha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources(ROOT/'bend/tests/light-reset.bend')|{Path(__file__).resolve()}})
    (folder/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: pure Bend repair records, retained metadata, idempotence and invalid inputs')


if __name__=='__main__':main()

#!/usr/bin/env python3
"""Persistent terrain -> structure replay -> weather -> compressed MCA integration.

A controlled registered multipart building crosses chunk boundaries and carries
chest loot and an entity. An independent decoder checks their final positions;
the shared region harness compares every sampled voxel with live block queries
and requires standalone compressed chunks to equal streamed MCA records.
"""
import argparse,copy,hashlib,json,struct
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_full_region import exercise,key
from test_bend_structure_region import control
from test_bend_structure_replay import controls
from test_bend_formats import read_region
from test_bend_chunks import palette
from test_bend_structure_candidates import region


def fixture(path):
    source=control(json.loads(path.read_text()))
    controls(source)
    structures=source['structures']
    # Keep the multipart control's capped processor, chest metadata and entity.
    definition=structures['definitions'][-3]
    definition['biomes']=list(range(len(source['biomes'])))
    definition['config'].update(start_height={'absolute':0},max_distance_from_center=32)
    structures['definitions']=[definition]
    structures['sets']=[dict(id='test:worker',placement=dict(spacing=2,separation=1,salt=1234,frequency=1.),entries=[dict(definition=0,weight=1)])]
    # Preserve real material/feature/surface registries. The artificial density
    # field provides a known projection height for this integration control.
    return source


def verify(records,source):
    starts=entities=chests=references=0
    blocks={}
    for rec in records.values():
        t=rec['tag'];cx,cz=t['xPos'],t['zPos']
        for section in t['sections']:
            for i,state in enumerate(palette(section['block_states'],4096,4)):
                blocks[cx*16+i%16,section['Y']*16+i//256,cz*16+(i//16)%16]=key(state)[0]
    for rec in records.values():
        t=rec['tag'];ss=t['structures'];starts+=len(ss['starts']);references+=sum(map(len,ss['References'].values()))
        for tag in t['block_entities']:
            pos=(tag['x'],tag['y'],tag['z'])
            assert blocks[pos]=='minecraft:chest',(pos,blocks[pos])
            assert tag['id']=='minecraft:chest' and tag['LootTable']=='minecraft:chests/village/village_plains_house'
            assert 'LootTableSeed' in tag
            chests+=1
        for entity in t['entities']:
            assert entity['id']=='minecraft:item_frame'
            pos=[struct.unpack('>d',v)[0] if isinstance(v,bytes) else v for v in entity['Pos']]
            assert int(pos[0]//16)==t['xPos'] and int(pos[2]//16)==t['zPos']
            assert len(entity['UUID'])==4
            entities+=1
    assert starts and references and chests and entities,(starts,references,chests,entities)
    candidates=region(source['structures'],123456789,-32,0,4)
    owners={(x,z) for _,x,z in candidates if -32<=x<-28 and 0<=z<4}
    actual={(t['ChunkX'],t['ChunkZ']) for r in records.values() for t in r['tag']['structures']['starts'].values()}
    assert actual==owners,(actual,owners)
    return dict(starts=starts,references=references,chests=chests,entities=entities)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--binary',type=Path,required=True);p.add_argument('--gpu',action='store_true');p.add_argument('--profile',type=Path,default=ROOT/'build/bend/profiles/profile-0.json');a=p.parse_args()
    folder=ROOT/'build/bend/structure-worker'/('gpu' if a.gpu else 'cpu');folder.mkdir(parents=True,exist_ok=True)
    source=fixture(a.profile);path=folder/'profile.json';path.write_text(json.dumps(source))
    result=exercise(a.binary,path,a.gpu,folder,64,'4GB')
    (folder/'pipeline-report.json').write_text(json.dumps(result,indent=2)+'\n')
    result['metadata']=verify(read_region((folder/'r.-1.0.mca').read_bytes()),source)
    result['scope']=__doc__
    (folder/'report.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS',result['metadata'],flush=True)

if __name__=='__main__':main()

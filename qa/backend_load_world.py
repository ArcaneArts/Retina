#!/usr/bin/env python3
"""Copy completed vanilla benchmark MCAs into a disposable Minecraft 26.3 world.

This writes QA world metadata only. Terrain, light and compressed chunk records
are copied verbatim from the benchmark; no generator or codec is substituted.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct

ROOT = Path(__file__).resolve().parents[1]


def tag(value):
    if isinstance(value, bool): return 1, int(value)
    if isinstance(value, int): return (3 if -(1<<31) <= value < (1<<31) else 4), value
    if isinstance(value, float): return 6, value
    if isinstance(value, str): return 8, value
    if isinstance(value, dict): return 10, {k:tag(v) for k,v in value.items()}
    if isinstance(value, list):
        items=[tag(v) for v in value]
        kind=items[0][0] if items else 0
        assert all(k==kind for k,_ in items)
        return 9,(kind,[v for _,v in items])
    raise TypeError(type(value))


def text(value):
    # All generated metadata keys, IDs and labels are ASCII.
    value=value.encode('ascii')
    return struct.pack('>H',len(value))+value


def payload(kind,value):
    if kind in (1,3,4,5,6): return struct.pack({1:'>b',3:'>i',4:'>q',5:'>f',6:'>d'}[kind],value)
    if kind==8: return text(value)
    if kind==9:
        child,items=value
        return bytes([child])+struct.pack('>i',len(items))+b''.join(payload(child,v) for v in items)
    if kind==10: return b''.join(bytes([k])+text(name)+payload(k,v) for name,(k,v) in value.items())+b'\0'
    if kind==11: return struct.pack('>i',len(value))+b''.join(struct.pack('>i',v) for v in value)
    raise ValueError(kind)


def write_nbt(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(gzip.compress(b'\x0a\0\0'+payload(10,value),mtime=0))


def prepare(benchmark,destination):
    report=json.loads((benchmark/'report.json').read_text())
    destination.mkdir(parents=True,exist_ok=False)
    world=destination/'world'
    backend=report['backend']
    preset='bend' if backend=='bend' else 'gpu'
    dimensions=json.loads((ROOT/f'common/src/main/resources/data/retina/worldgen/world_preset/{preset}.json').read_text())['dimensions']
    metadata=tag(dict(DataVersion=5023,Data=dict(DataVersion=5023,LevelName='Retina backend load benchmark',
        initialized=True,GameType=1,allowCommands=True,Time=0,
        Version=dict(Snapshot=False,Series='main',Id=5023,Name='26.3'),version_history=[5023],
        spawn=dict(pos=[256,128,256],dimension='minecraft:overworld',yaw=0.,pitch=0.),
        DataPacks=dict(Enabled=['vanilla','retina'],Disabled=[]))))[1]
    data=metadata['Data'][1]
    data['Time']=(4,0)
    data['spawn'][1]['pos']=(11,[256,128,256])
    write_nbt(world/'level.dat',metadata)
    settings=tag(dict(DataVersion=5023,data=dict(seed=report['seed'],bonus_chest=False,
        generate_structures=True,dimensions=dimensions)))[1]
    settings['data'][1]['seed']=(4,report['seed'])
    write_nbt(world/'data/minecraft/world_gen_settings.dat',settings)
    files=[]
    for row in report['regions']:
        if row['kind']!='warm': continue
        source=Path(row['path'])
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        if digest!=row['validation']['sha256']: raise ValueError(f'Benchmark file changed: {source}')
        target=world/'dimensions/minecraft/overworld/region'/source.name
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,target)
        assert hashlib.sha256(target.read_bytes()).hexdigest()==digest
        files.append(dict(file=str(target.relative_to(world)),sha256=digest))
    # All benchmark probes lie away from north/south boundaries, and both sides
    # of the tested X seams have real generated neighbors.
    (destination/'eula.txt').write_text('eula=true\n')
    (destination/'server.properties').write_text('\n'.join([
        'server-ip=127.0.0.1','server-port=25579','online-mode=false','level-name=world',
        'level-seed='+str(report['seed']),'level-type=retina:'+preset,'gamemode=creative',
        'view-distance=2','simulation-distance=2','max-tick-time=-1','pause-when-empty-seconds=0',
        'spawn-protection=0','enable-query=false','enable-rcon=false','sync-chunk-writes=false','']) )
    (destination/'input.json').write_text(json.dumps(dict(backend=backend,profile_sha256=report['profile_sha256'],
        seed=report['seed'],files=files,scope='vanilla registry benchmark load-in; copied generated MCAs'),indent=2)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('benchmark',type=Path)
    parser.add_argument('destination',type=Path)
    args=parser.parse_args()
    prepare(args.benchmark,args.destination)

#!/usr/bin/env python3
"""Accepted command journals -> spatial anchor replay -> repair -> NBT/zlib/MCA.

Independent dense CPU replay covers transient writes, role identity, attachments,
atomic plant pairs, canonical anchor order and malformed commands. This is a
resident component check, not complete-region performance or playable lighting.
"""
import argparse,copy,hashlib,itertools,json,random,struct
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,command,metal_compiler_command,verify_stream
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import base,origin,sources,MINIMUM,HEIGHT,MASK
from test_bend_formats import read_region
from test_bend_chunks import palette,unpack,MAP_NAMES


def fixtures(s):
    flags=s['material_flags'];pick=lambda mask:next(i for i,f in enumerate(flags) if f&mask)
    log,leaf,soil,cocoa_log=pick(8),pick(4),pick(1),pick(32)
    path=next(i for i,m in enumerate(s['materials']) if (m if isinstance(m,str) else m['id'])=='minecraft:dirt_path')
    lower=next(i for i,v in enumerate(s['plant_halves']) if v>0)
    upper=next(i for i,v in enumerate(s['plant_halves']) if v==-s['plant_halves'][lower])
    ox,oz=origin(0)
    def c(x,y,z,m,role=6,extra=0):return (ox+x,y,oz+z,m,role,extra)
    a=dict(origin=(0,0,0),commands=[c(8,2,8,log,1),c(9,2,8,leaf,2),c(11,2,8,1),c(12,2,8,log,1),
        c(14,2,8,cocoa_log,1),c(14,4,8,cocoa_log,1),c(15,2,8,log,1),c(18,1,8,1),c(18,-1,8,soil),c(19,-1,8,soil),c(20,-1,8,soil),
        c(22,-1,8,soil),c(23,-1,8,s['snow'])])
    b=dict(origin=(16,0,0),commands=[c(8,2,8,leaf,2),c(9,2,8,log,1),c(11,2,8,0),c(11,2,8,log,1),
        c(12,2,8,1,1),c(12,2,8,1),c(15,2,8,0),c(15,2,8,leaf,2),c(15,2,8,1,3,0),c(15,4,8,1,3,0),
        c(15,3,8,leaf,4,0),c(15,2,8,0),c(15,3,8,0),c(15,2,8,leaf,4,0),c(15,1,8,leaf,4,0),
        c(18,0,8,lower,5,upper),c(19,0,8,lower,5,upper),c(20,0,8,lower,5),
        c(22,-1,8,1,0,s['snow']),c(23,-1,8,soil,0,s['snow']),c(-1,0,8,1),c(31,0,31,1),c(8,64,8,1)])
    d=dict(origin=(32,0,0),commands=[c(20,-1,8,path),c(19,1,8,0),c(18,1,8,0),c(18,0,8,lower,5,upper)])
    e=dict(origin=(48,0,0),commands=[c(8,2,8,1,0,s['snow']),c(8,2,8,leaf,2),c(21,0,8,leaf,4,0),c(21,0,9,1,3,0)])
    cases=[list(p) for p in itertools.permutations([a,b,d,e])]
    cases += [[],[a],[b],[dict(a,origin=(-2147483648,0,0)),dict(b,origin=(2147483647,0,0))],
              [dict(b,origin=(0,0,-1)),dict(a,origin=(0,0,-2))],
              [dict(b,origin=(0,-1,0)),dict(a,origin=(0,-2,0))]]
    rng=random.Random(123456789)
    for _ in range(6):
        rows=[]
        for i in range(8):
            cmds=[]
            for j in range(40):
                role=rng.randrange(7);m=rng.choice([0,1,log,leaf,soil,lower,upper,s['water']])
                extra=s['snow'] if role==0 else rng.randrange(4) if role in (3,4) else rng.choice([0,upper]) if role==5 else 0
                cmds.append(c(rng.randrange(-1,33),rng.randrange(-66,66),rng.randrange(-1,33),m,role,extra))
            rows.append(dict(origin=(i*16-32,0,0),commands=cmds))
        rng.shuffle(rows);cases.append(rows)
    invalid=[[a,a],[dict(a,commands=[c(8,0,8,1,7)])],[dict(a,commands=[c(8,0,8,len(flags))])],
        [dict(a,commands=[c(8,0,8,1,3,4)])],[dict(a,commands=[c(8,0,8,1,4,4)])],
        [dict(a,commands=[c(8,0,8,lower,5,len(flags))])],[dict(a,commands=[c(8,0,8,1,0,len(flags))])]]
    return cases+invalid,len(invalid)


def replay(s,anchors):
    palette_size=len(s['materials']);ordered=sorted(anchors,key=lambda a:(a['origin'][0],a['origin'][2],a['origin'][1]))
    if len({tuple(a['origin']) for a in ordered})!=len(ordered):return None
    for a in ordered:
        for x,y,z,m,r,e in a['commands']:
            if not 0<=m<palette_size or not 0<=r<=6 or (r in (0,5) and not 0<=e<palette_size) or (r in (3,4) and not 0<=e<4):return None
    cells,metadata=base(s,32,0);ox,oz=origin(0);flags=s['material_flags']
    def index(x,y,z):
        dx,dz=x-ox,z-oz
        return (dx+dz*32,y-MINIMUM) if 0<=dx<32 and 0<=dz<32 and MINIMUM<=y<MINIMUM+HEIGHT and metadata[dx+dz*32][3] else None
    def get(x,y,z):
        if not MINIMUM<=y<MINIMUM+HEIGHT:return 0
        i=index(x,y,z);return None if i is None else cells[i[0]][i[1]]
    def put(x,y,z,m):
        i=index(x,y,z)
        if i is not None:cells[i[0]][i[1]]=m
    for a in ordered:
        for x,y,z,m,r,e in a['commands']:
            previous=get(x,y,z)
            if previous is None:continue
            if r==0:ok=bool(flags[previous]&1) or previous==e
            elif r==1:ok=previous==0 or bool(flags[previous]&20)
            else:ok=r==6 or previous==0
            if not ok:continue
            if r in (3,4):
                dx,dz=[(1,0),(-1,0),(0,1),(0,-1)][e];support=get(x-dx,y,z-dz)
                ok=support is not None and bool(flags[support]&(32 if r==3 else 12))
                if r==4:ok=ok or get(x,y+1,z)==m
                if not ok:continue
            if r==5 and e:
                if index(x,y+1,z) is None or get(x,y+1,z)!=0:continue
                put(x,y+1,z,e)
            put(x,y,z,m)
    removed=0
    for i,row in enumerate(cells):
        if not metadata[i][3]:continue
        for j,m in enumerate(row):
            floor=s['plant_floor_masks'][m]
            if floor and (j==0 or not flags[row[j-1]]&floor):row[j]=0;removed+=1
        for j,m in enumerate(row):
            half=s['plant_halves'][m];other=j+(1 if half>0 else -1)
            if half and (not 0<=other<HEIGHT or s['plant_halves'][row[other]]!=-half):row[j]=0;removed+=1
        metadata[i]=(*metadata[i][:2],1+sum(a!=b for a,b in zip(row,row[1:])),metadata[i][3])
    return cells,metadata,removed


def check(raw,s,anchors):
    expected=replay(s,anchors);at=0
    def words(n):
        nonlocal at
        xs=struct.unpack_from('>'+str(n)+'I',raw,at);at+=n*4;return xs
    code,=words(1)
    if expected is None:assert code==773 and at==len(raw);return dict(rejected=True)
    assert code==0
    header=words(8);ox,oz=origin(0);assert header==(ox&MASK,oz&MASK,32,32,123456789,2147483648,MINIMUM&MASK,HEIGHT)
    cells,metadata,removed=expected
    for i in range(1024):
        *meta,n=words(5)
        assert meta[:2]==[v&MASK for v in metadata[i][:2]] and meta[-1]==metadata[i][-1],(i,meta,metadata[i])
        assert meta[2]==n if meta[-1] else meta[2]==8
        row=[]
        for j in range(n):
            a,b,m=words(3);assert a==len(row) and a<b<=HEIGHT
            row.extend([m]*(b-a))
        assert row==cells[i],(i,next((j for j in range(HEIGHT) if row[j]!=cells[i][j]),None))
    assert at==len(raw)
    return dict(rejected=False,voxels=1024*HEIGHT,removed_plants=removed,sha256=hashlib.sha256(raw).hexdigest())


def controls(s,cases):
    cells,_,_=replay(s,cases[0]);ox,oz=origin(0)
    at=lambda x,y,z:cells[x+z*32][y-MINIMUM]
    log=next(i for i,f in enumerate(s['material_flags']) if f&8)
    assert at(8,2,8)==log, 'leaves and soil cannot replace the other anchor trunk'
    assert at(11,2,8)==log, 'transient air must precede conditional trunk replay'
    assert at(12,2,8)==1, 'same-state generic write must retain unconditional role'
    lower=next(i for i,v in enumerate(s['plant_halves']) if v>0)
    upper=next(i for i,v in enumerate(s['plant_halves']) if v==-s['plant_halves'][lower])
    assert at(18,0,8)==lower and at(18,1,8)==upper, 'pair can place after obstruction is cleared'
    assert at(19,0,8)==0 and at(19,1,8)==0, 'orphan pair must be removed'
    assert at(20,0,8)==0, 'path replacement must remove grass'
    assert at(15,4,8)==1, 'cocoa sees the prior anchor jungle support'
    assert at(21,0,9)==0, 'unsupported cocoa is rejected'
    return dict(trunk_protection=True,transient_air=True,same_material_role=True,atomic_pair=True,orphan_repair=True,path_repair=True,attachment_support=True)


def check_mca(path,s,anchors):
    expected=replay(s,anchors)[0];chunks=read_region(path.read_bytes());ox,oz=origin(0);blocks=0
    assert len(chunks)==4
    for slot,record in chunks.items():
        tag=record['tag'];cx,cz=tag['xPos'],tag['zPos'];assert tag['DataVersion']==5023 and tag['isLightOn']==0
        heights=[[0]*256 for _ in range(6)]
        for sy,section in enumerate(tag['sections']):
            states=palette(section['block_states'],4096,4)
            for i,state in enumerate(states):
                x,z=cx*16+i%16-ox,cz*16+(i%256)//16-oz;y=sy*16+i//256
                m=expected[x+z*32][y];want=s['materials'][m];want={'id':want} if isinstance(want,str) else want
                assert state==want,(slot,sy,i,state,want)
                for kind in range(6):
                    if s['heightmap_masks'][m]&(1<<kind):heights[kind][i%256]=y+1
                blocks+=1
        for kind,key in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][key],256,HEIGHT.bit_length())==heights[kind]
    return dict(chunks=4,blocks_checked=blocks,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def check_journal(path,s):
    raw=path.read_bytes();n,=struct.unpack_from('>I',raw);rows=[struct.unpack_from('>6I',raw,4+i*24) for i in range(n)]
    ox,oz=origin(0);jungle=next(i for i,f in enumerate(s['material_flags']) if f&32)
    c=lambda x,y,m,r,e:(ox+x,y,(oz+8)&MASK,m,r,e)
    expected=[c(8,2,1,1,0),c(8,2,1,6,0),c(9,2,1,5,1),c(15,2,1,0,0),c(10,2,1,1,0),
              c(16,2,jungle,1,0),c(16,3,jungle,1,0),c(11,2,1,2,0),c(17,2,1,3,0),c(17,3,1,4,0),
              c(12,2,1,5,0),c(13,2,1,5,1),c(14,3,1,6,0),c(14,2,1,6,0)]
    assert rows==expected,(rows,expected)
    assert len(raw)==4+24*n
    return dict(commands=n,roles=sorted({r[4] for r in rows}),same_material_commands_preserved=True,paired_plant_single_command=True,rejected_writes_absent=True,sha256=hashlib.sha256(raw).hexdigest())


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True);p.add_argument('--skip-build',action='store_true');a=p.parse_args()
    binary=ROOT/'build/bend/anchor-replay';driver=ROOT/'bend/tests/anchor-replay.bend';folder=ROOT/'build/bend/anchor-replay-tests';folder.mkdir(parents=True,exist_ok=True)
    if not a.skip_build:build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=600)
    runs=[]
    for i,path in enumerate(a.profile):
        s=json.loads(path.read_text());cases,invalid=fixtures(s);semantic=controls(s,cases);payload=copy.deepcopy(s)
        payload['anchor_replay_cases']=[[list(row['origin'])+[v for c in row['commands'] for v in c] for row in case] for case in cases]
        inp=folder/f'profile-{i}.rbp';inp.write_bytes(fixture_wire(payload,True));out=folder/f'profile-{i}'
        result=command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600)
        assert 'PASS: spatially ordered anchor command replay' in result.stdout
        data=[Path(str(out)+'.'+str(j)).read_bytes() for j in range(len(cases))]
        checks=[check(raw,s,case) for raw,case in zip(data,cases)];assert all(raw==data[0] for raw in data[:24])
        if i==0:
            control=folder/'one-thread';command(['nice','-n','10',binary,inp,control,'--threads','1','--gpu','off'],timeout=600)
            assert all(Path(str(control)+'.'+str(j)).read_bytes()==raw for j,raw in enumerate(data))
            assert Path(str(control)+'.0.mca').read_bytes()==Path(str(out)+'.0.mca').read_bytes()
        runs.append(dict(profile=str(path),cases=checks,completion_permutations=24,semantic_controls=semantic,malformed_rejected=invalid,journal=check_journal(Path(str(out)+'.journal'),s),mca=check_mca(Path(str(out)+'.0.mca'),s,cases[0])))
        print('profile',i,'pass',len(cases),'cases',flush=True)
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/test_bend_placement_merge.py'}
    report=dict(scope=__doc__.strip(),execution_mode='cpu',runs=runs,one_two_threads_equal=True,sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in a.profile},native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-anchor-replay-correctness.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()

#!/usr/bin/env python3
"""Independently replay registered Bend cave dressing into resident block data.

Captured pre-dressing blocks isolate this transform from terrain generation.
Biome decisions, hashes, recipes and survival checks have independent references.
This is component QA; no full-world or region-performance claim is made.
"""
import argparse,copy,hashlib,json,re,signal,struct,sys,threading,time
from collections import Counter
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,verify_stream
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_caves import fixtures as caves,Reference
from test_bend_geology import encode as queries
from test_bend_cave_biomes import strata
from test_bend_density import ins
from test_bend_surface import tile_bytes,query_bytes
from test_bend_material_columns import decode as decode_columns
from test_bend_generated_chunks import request as chunk_request,validate as validate_chunk
from test_bend_noise import MASK,mix_hash,f32

LO,HI=0x87654321,0x80000001


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True);s=strata(caves(folder/'caves')[0][1]);s['sea_level']=-20
    # A registered analytic horizontal ellipsoid section supplies a closed air
    # span at every X/Z, including distant coordinates and independent tile sizes.
    s['registry_program']['programs'][2]=dict(nodes=[ins(29,1),ins(0,p=(-12,0,0,0)),ins(5,0,1),
        ins(6,2,2),ins(0,p=(1/49,0,0,0)),ins(6,3,4),ins(0,p=(1,0,0,0)),ins(5,5,6)],roots=[7])
    for n in s['cave_noises']:n['amplitude']=0.
    for b in s['biomes']:b.update(carvers=[],ores=[])
    s['ores']=[]
    s['materials'] += ['minecraft:moss_block','minecraft:clay','minecraft:spore_blossom','minecraft:short_grass',
        dict(id='minecraft:cave_vines_plant',properties={'berries':'false'}),dict(id='minecraft:cave_vines',properties={'berries':'false'})]
    for direction in ('up','down'):
        s['materials'] += [dict(id='minecraft:pointed_dripstone',properties={'vertical_direction':direction,'thickness':shape,'waterlogged':'false'})
            for shape in ('tip','frustum','middle','base')]
    s['materials'] += [dict(id='minecraft:tall_grass',properties={'half':'lower'}),dict(id='minecraft:tall_grass',properties={'half':'upper'})]
    n=len(s['materials']);assert n==22
    s.update(carveable=[False]+[True]*4+[False]*(n-5),material_flags=[0,1,1,1,1,2,1,0,0,16,0,0]+[0]*8+[16,16],
        plant_halves=[0]*20+[1,-1],plant_floor_masks=[0]*9+[1]+[0]*10+[1,0],
        heightmap_masks=[0,63,63,63,63,3,63,63,3,3,3,3]+[3]*10,lava_level=-30)
    recipe=dict(floor=6,clay=7,blossom=8,plants=[9],vine_bodies=[10],vine_tips=[11],drip_up=[12,13,14,15],
        drip_down=[16,17,18,19],drip_min=2,drip_max=5,density=1.,plant_chance=1.,vine_max=7,
        replaceable=[False,True,True,True,True]+[False]*(n-5))
    for b in s['biomes']:b.update(cave_kind=1,cave_features=copy.deepcopy(recipe))
    s['biomes'][0]['cave_kind']=0
    out=[]
    def add(name,change=lambda _:None):
        v=copy.deepcopy(s);change(v);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(v,True));out.append((name,v,p))
    add('lush')
    for name,kind in [('dripstone',2),('deep_dark',3),('sulfur',4)]:
        add(name,lambda v,k=kind:[b.update(cave_kind=k) for b in v['biomes'][1:]])
    add('registered',lambda v:[b['cave_features'].update(registered_vines=True,registered_floor_patch=True,registered_ceiling_patch=True) for b in v['biomes']])
    add('orphan',lambda v:[b['cave_features'].update(plants=[20]) for b in v['biomes']])
    add('unsupported',lambda v:[b['cave_features'].update(floor=7,clay=0) for b in v['biomes']])
    add('wet',lambda v:v.update(lava_level=31,sea_level=20))
    add('protected',lambda v:v.update(carveable=[False]*n))
    add('nonaligned',lambda v:v.update(geology_min_y=-31,geology_height=63))
    add('vertical_mix',lambda v:[b.update(cave_kind=(i%4)+1) for i,b in enumerate(v['biomes'])])
    add('empty',lambda v:[b.update(cave_kind=0,cave_features={}) for b in v['biomes']])
    bad=[]
    def invalid(name,change):
        v=copy.deepcopy(s);change(v);p=folder/('invalid-'+name+'.rbp');p.write_bytes(fixture_wire(v,True));bad.append(p)
    for name,value in [('kind',5),('kind_type','x')]:invalid(name,lambda v,a=value:v['biomes'][1].update(cave_kind=a))
    for name,value in [('density',1.1),('plant_chance',-1.),('drip_max',129),('vine_max',129),('floor',n),('replaceable',[True]),('drip_up',[12]),('plants',[n])]:
        invalid(name,lambda v,k=name,a=value:v['biomes'][1]['cave_features'].update({k:a}))
    invalid('drip_down',lambda v:v['biomes'][1]['cave_features'].update(drip_up=[],drip_down=[16]))
    invalid('object',lambda v:v['biomes'][1].update(cave_features='bad'))
    invalid('halves',lambda v:v.update(plant_halves=[0]))
    invalid('half_min',lambda v:v['plant_halves'].__setitem__(20,-2147483648))
    invalid('mask',lambda v:v['plant_floor_masks'].__setitem__(9,4))
    return out,bad


def registered_recipe(name,source,folder):
    """Keep exported recipes/materials intact; isolate them in an analytic cave.

    Unmodified profile checks remain separate. Forcing only geometry/climate
    makes the exported dripstone recipe observable rather than relying on a
    particular random world location containing both air and this cave biome.
    """
    s=copy.deepcopy(source)
    # Templates and ordered decoration graphs are not inputs to this component.
    # Keep their unmodified full-profile checks above; avoid duplicating them in
    # this geometry-only recipe fixture and its generic Python wire encoder.
    s.pop('structures',None);s.pop('decorations',None)
    cave=next(i for i,b in enumerate(s['biomes']) if b.get('cave_kind')==2)
    land=next(i for i,b in enumerate(s['biomes']) if not b['flags']&(1|2|8|16))
    s['climate_targets']=[dict(min=[-1]*4,max=[1]*4,weirdness=[-1,1],depth=depth,offset=0,biome=id)
        for id,depth in ((land,[-.25,.25]),(cave,[.5,2.]))]
    programs=s['registry_program']['programs']
    programs[0]=dict(nodes=[ins(0),ins(0,p=(1,0,0,0))],roots=[0,0,0,0,0,1])
    programs[1]=dict(nodes=[ins(0,p=(112,0,0,0)),ins(29,1),ins(5,0,1)],roots=[2])
    programs[2]=dict(nodes=[ins(29,1),ins(0,p=(-12,0,0,0)),ins(5,0,1),ins(6,2,2),
        ins(0,p=(1/49,0,0,0)),ins(6,3,4),ins(0,p=(1,0,0,0)),ins(5,5,6)],roots=[7])
    for n in s['cave_noises']:n['amplitude']=0.
    for b in s['biomes']:b['carvers']=[]
    path=folder/(name+'_drip_recipe.rbp');path.write_bytes(fixture_wire(s,True))
    return name+'_drip_recipe',s,path


def replay(source,columns,core,lo,hi):
    minimum=source['geology_min_y'];height=source['geology_height'];reference=Reference(source);counts=Counter()
    base={p:[m for a,b,m in c['runs'] for _ in range(a,b)] for p,c in columns.items()};output=copy.deepcopy(base)
    def rnd(x,y,z,salt):return mix_hash(((x*2654435769)^(y*2246822507)^(z*3266489909)^lo^mix_hash(hi)^salt)&MASK)
    def unit(h):return f32((h>>8)/16777216.)
    def biome(x,y,z):
        id=reference.node_biome((x//4*4,y//4*4,z//4*4),lo,hi)
        return source['biomes'][id] if id!=MASK else {}
    def policy(name,id):return source.get(name,[])[id] if id<len(source.get(name,[])) else 0
    x,z,w,d=core
    for zz in range(z,z+d):
        for xx in range(x,x+w):
            original=base[xx,zz];blocks=output[xx,zz];updates=[];i=6
            while i+1<height:
                if original[i]!=0:i+=1;continue
                start=i
                while i<height and original[i]==0:i+=1
                end=i
                if end>=height or end-start<2:continue
                b=biome(xx,minimum+start,zz);f=b.get('cave_features',{});kind=b.get('cave_kind',0)
                if not kind or not f.get('floor',0):continue
                counts['eligible_spans']+=1;h=rnd(xx,minimum+start,zz,4139)
                replaceable=f.get('replaceable',[])
                if blocks[start-1]<len(replaceable) and replaceable[blocks[start-1]] and not f.get('registered_floor_patch',False):
                    blocks[start-1]=f['floor'];counts['floor_coatings']+=1
                    if kind==1:
                        if f.get('clay',0) and mix_hash(h)%19==0:blocks[start-1]=f['clay'];counts['clay']+=1
                        if f.get('plants',[]) and unit(h)<f.get('plant_chance',0):
                            id=f['plants'][mix_hash(h^7151)%len(f['plants'])];blocks[start]=id
                            if policy('plant_halves',id) or policy('plant_floor_masks',id):updates.append(start)
                            counts['plants']+=1
                    elif kind==2 and unit(h)<f32(f32(f.get('density',0))*f32(.18)) and f.get('drip_up',[]):
                        length=min(f.get('drip_min',0)+mix_hash(h)%(f.get('drip_max',0)-f.get('drip_min',0)+1),end-start-1)
                        for j in range(length):blocks[start+j]=f['drip_up'][min(length-j-1,3)];counts['drip_up']+=1
                b=biome(xx,minimum+end-1,zz);f=b.get('cave_features',{});kind=b.get('cave_kind',0);rep=f.get('replaceable',[])
                if blocks[end]>=len(rep) or not rep[blocks[end]]:continue
                h=rnd(xx,minimum+end,zz,9283)
                if kind==1 and f.get('floor',0):
                    if not f.get('registered_ceiling_patch',False):blocks[end]=f['floor'];counts['roof_coatings']+=1
                    if not f.get('registered_vines',False) and f.get('vine_bodies',[]) and f.get('vine_tips',[]) and h%11==0:
                        length=min(1+mix_hash(h)%max(f.get('vine_max',0),1),end-start-1)
                        for j in range(length):
                            if blocks[end-j-1]!=0:break
                            values=f['vine_tips'] if j+1==length else f['vine_bodies'];blocks[end-j-1]=values[mix_hash(h^j)%len(values)];counts['vines']+=1
                    elif f.get('blossom',0) and h%101==0:blocks[end-1]=f['blossom'];counts['blossom']+=1
                elif kind==2 and f.get('floor',0):
                    blocks[end]=f['floor'];counts['roof_coatings']+=1
                    if unit(h)<f32(f32(f.get('density',0))*f32(.18)) and f.get('drip_down',[]):
                        length=min(f.get('drip_min',0)+mix_hash(h)%(f.get('drip_max',0)-f.get('drip_min',0)+1),end-start-1)
                        for j in range(length):
                            if blocks[end-j-1]!=0:break
                            blocks[end-j-1]=f['drip_down'][min(length-j-1,3)];counts['drip_down']+=1
            for i in updates:
                mask=policy('plant_floor_masks',blocks[i])
                if mask and (i==0 or not policy('material_flags',blocks[i-1])&mask):blocks[i]=0;counts['support_removed']+=1
            for i in updates:
                half=policy('plant_halves',blocks[i]);other=i+1 if half>0 else i-1
                if half and (not 0<=other<height or policy('plant_halves',blocks[other])!=-half):blocks[i]=0;counts['orphan_removed']+=1
    counts['changed_voxels']=sum(a!=b for p in base for a,b in zip(base[p],output[p]));return output,counts,reference


def exercise(binary,profiles,bad,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray();reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    digest=hashlib.sha256();records=[];totals=Counter();voxels=chunks=commands=0;active_name=''
    def call(op,data=b'',code=0):
        nonlocal commands
        trace=active_name in ('vanilla','terralith','combined') or active_name.endswith('_drip_recipe')
        if trace and op in (5,11,19,21,30,9,15,25):print('  prepare opcode',op,flush=True)
        started=time.monotonic()
        try:
            signal.alarm(300);value=worker.call(op,data,status=1 if code else 0)
        except TimeoutError as e:raise TimeoutError(f'{active_name}: opcode {op} timed out') from e
        if trace and time.monotonic()-started>1:print(f'  opcode {op}: {time.monotonic()-started:.3f}s (component QA under uncontrolled load)',flush=True)
        if code:assert value==struct.pack('>I',code),(op,code,value)
        else:commands+={13:1,16:1,29:2,22:2 if gpu else 1,33:2,35:1,36:1,51:1}.get(op,0)
        return value
    def read(pts):return decode_columns(b''.join(call(24,query_bytes(pts[i:i+256])) for i in range(0,len(pts),256)))
    def prepare(path):
        call(5,path_request(path))
        for op in (11,19,21,30,9,15,25):call(op)
    def generate(core,s):
        x,z,w,d=core;halo=7
        call(13,call(18,tile_bytes((x-halo,z-halo,w+2*halo,d+2*halo,LO,HI))))
        call(16,tile_bytes((x-1,z-1,w+2,d+2,LO,HI)));call(29);call(22);call(33);call(35)
        pts=[(xx,zz,LO,HI) for zz in range(z-1,z+d+1) for xx in range(x-1,x+w+1)]
        return pts,read(pts)
    try:
        call(51,tile_bytes((0,0,16,16,LO,HI)),758)
        for path in bad:
            call(5,path_request(path))
            for op in (11,19,21):call(op)
            call(30,code=757)
        for name,s,path in profiles:
            active_name=name
            print('checking','GPU' if gpu else 'CPU',name,flush=True);prepare(path)
            cores=[(0,0,32,32),(-16,-16,16,16),(29999984,-30000000,16,16),(-2147483632,2147483616,16,16)] if name=='lush' else [(0,0,16,16)]
            stats=Counter()
            for core in cores:
                x,z,w,d=core;pts,before=generate(core,s);want,counters,ref=replay(s,{p[:2]:c for p,c in zip(pts,before)},core,LO,HI)
                quart=[];quart_before=b''
                serializable=s['geology_min_y']%16==0 and s['geology_height']%16==0 and core==cores[0]
                if serializable:
                    quart=[(xx,yy,zz,LO,HI) for yy in range(s['geology_min_y'],s['geology_min_y']+s['geology_height'],4) for zz in range(z,z+16,4) for xx in range(x,x+16,4)]
                    quart_before=b''.join(call(36,queries(quart[i:i+256])) for i in range(0,len(quart),256))
                ack=struct.unpack('>4I',call(51,tile_bytes((*core,LO,HI))));assert ack[:3]==(w+2,d+2,(w+2)*(d+2))
                raw=b''.join(call(24,query_bytes(pts[i:i+256])) for i in range(0,len(pts),256));after=decode_columns(raw)
                actual={p[:2]:[m for a,b,m in c['runs'] for _ in range(a,b)] for p,c in zip(pts,after)}
                assert actual==want,(name,core,next(((p,i,a,b) for p in actual for i,(a,b) in enumerate(zip(actual[p],want[p])) if a!=b),None))
                for c in after:
                    end=0;previous=None
                    for a,b,m in c['runs']:assert a==end and a<b and m!=previous;end=b;previous=m
                    assert end==s['geology_height']
                digest.update(raw);voxels+=len(pts)*s['geology_height'];stats.update(counters)
                if serializable:
                    selected=[after[(zz-z+1)*(w+2)+(xx-x+1)] for zz in range(z,z+16) for xx in range(x,x+16)]
                    assert quart_before==b''.join(call(36,queries(quart[i:i+256])) for i in range(0,len(quart),256)),'decorations changed the source cave-biome volume'
                    pairs=list(struct.iter_unpack('>2I',quart_before));assert all(present==1 for present,id in pairs)
                    ids=[id for present,id in pairs]
                    # Generated chunk biomes inherit finalized surface IDs at MAX.
                    ids=[selected[(i%16)//4*4*16+(i%4)*4]['biome'] if v==MASK else v for i,v in enumerate(ids)]
                    req=chunk_request(x//16,z//16,LO,HI,5023);nbt=call(26,req);validate_chunk(nbt,s,selected,x//16,z//16,5023,biome_ids=ids,fluid_materials={s['water'],s['lava']})
                    verify_stream(call(27,req),nbt);chunks+=1
                for body in (b'',b'\0',tile_bytes((*core,LO,HI))+b'\0'):call(51,body,603)
                call(51,tile_bytes((x+1,z,w,d,LO,HI)),759);call(51,tile_bytes((*core,LO,HI^1)),759)
                assert read(pts)==after,'rejected decoration request changed snapshot'
                generate(core,s);call(51,tile_bytes((*core,LO,HI)));assert read(pts)==after,'repeat build changed result'
                if name=='lush' and w==32:
                    for small in [(16,16,16,16),(0,16,16,16),(16,0,16,16),(0,0,16,16)]:
                        q,cols=generate(small,s);call(51,tile_bytes((*small,LO,HI)));small_after=read(q)
                        for p,c in zip(q,small_after):
                            if small[0]<=p[0]<small[0]+16 and small[1]<=p[1]<small[1]+16:assert [m for a,b,m in c['runs'] for _ in range(a,b)]==actual[p[:2]],'tile-order seam'
                    stats['independent_adjacent_cores']+=4
            if name in ('protected','empty','wet'):assert stats['changed_voxels']==0
            if name=='lush':assert stats['plants'] and stats['vines'] and stats['blossom']
            if name=='dripstone':assert stats['drip_up'] and stats['drip_down']
            if name.endswith('_drip_recipe'):assert stats['floor_coatings'] and stats['drip_up'] and stats['drip_down']
            if name=='orphan':assert stats['orphan_removed']
            if name=='unsupported':assert stats['support_removed']
            if name=='registered':assert stats['floor_coatings']==stats['roof_coatings']==stats['vines']==0
            records.append(dict(name=name,**stats));totals.update(stats)
            call(5,path_request(path));call(51,tile_bytes((0,0,16,16,LO,HI)),758)
        call(4);assert worker.process.wait(timeout=20)==0;reader.join(timeout=20);assert not reader.is_alive()
        text=diagnostics.decode(errors='replace');assert not any(x in text for x in ('ERR_','GPU execution failed')),text
        return dict(gpu_required=gpu,profiles=records,totals=dict(totals),voxels_checked=voxels,nbt_zlib_chunks=chunks,invalid_profiles=len(bad),sha256=digest.hexdigest(),minimum_gpu_commands=commands,metal_commands_ms=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',text)])
    finally:
        signal.alarm(0);failed=sys.exc_info()[0] is not None
        try:worker.close()
        except BaseException:
            if not failed:raise
        reader.join(timeout=5)
        (ROOT/'build/bend'/('cave-dressing-gpu-stderr.log' if gpu else 'cave-dressing-cpu-stderr.log')).write_bytes(diagnostics)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');p.add_argument('--metal-probe',type=Path);p.add_argument('--profile',nargs=2,action='append',default=[]);p.add_argument('--output',type=Path);p.add_argument('--execution',choices=('cpu','gpu','both'),default='both');a=p.parse_args()
    binary=ROOT/'build/bend/engine'
    if not a.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    profiles,bad=fixtures(ROOT/'build/bend/cave-dressing-profiles')
    for js,rbp in a.profile:
        name=Path(js).parent.name;source=json.loads(Path(js).read_text())
        profiles.append((name,source,Path(rbp)));profiles.append(registered_recipe(name,source,ROOT/'build/bend/cave-dressing-profiles'))
    executions=[False,True] if a.execution=='both' else [a.execution=='gpu']
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,bad,gpu) for gpu in executions])
    if a.prove_metal:
        probe=a.metal_probe or ROOT/'build/bend/cave-dressing-metal-probe'
        if a.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        r=exercise(probe,profiles,bad,True);assert len(r['metal_commands_ms'])>=r['minimum_gpu_commands'];assert r['sha256']==report['runs'][-1]['sha256'];report['actual_metal_proof']=r
    report.update(bend_version='2.0.36',source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))},inputs=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles],executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    output=a.output or ROOT/'build/bend/cave-dressing-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('cave dressing timeout')))
    main()

#!/usr/bin/env python3
"""Registered final snow/freezing: independent dense CPU/Metal and MCA checks.

Original vanilla/Terralith/combined palettes, temperatures, flags, precipitation
and support tables remain unchanged. Controlled input columns cover the complete
palette, all loaded biome rows, fluid/cover/build edges and invalid columns.
This component harness is not a complete lit-region throughput benchmark.
"""
import argparse,copy,hashlib,json,math,re,struct,subprocess
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,command,metal_compiler_command
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources
from test_bend_density import perlin
from test_bend_noise import f32
from test_bend_formats import read_region
from test_bend_chunks import palette,unpack,MAP_NAMES

MASK=(1<<32)-1


def fixtures(source):
    cold=next(i for i,b in enumerate(source['biomes']) if b['id']=='minecraft:snowy_plains')
    warm=next(i for i,b in enumerate(source['biomes']) if b['id']=='minecraft:plains')
    stone=source['stone'];cover=source['terrain_features']['snow_layer'];rows=[]
    # Every registered material receives both exposed and covered contexts.
    for start in range(0,len(source['materials']),256):
        for cover_id in (0,cover,stone):
            rows.append([-64,32,-64,384,80,cold,143,0,start,stone,cover_id,1])
    rows += [[29990000,-30000000,-64,384,48,cold,110,1,0,stone,stone,1],
             [-30000000,29990000,-64,384,80,warm,143,0,0,stone,cover,1],
             [-64,32,-64,384,80,cold,143,2,0,stone,cover,0],
             [-64,32,0,16,16,cold,15,0,0,stone,stone,1],
             [-64,32,-16,16,0,cold,15,0,0,stone,stone,1],
             [-64,32,-64,384,80,cold,143,6,0,stone,0,1]]
    for start in range(0,len(source['biomes']),256):
        for top in (64,144,300):
            rows.append([29990000,-30000000,-64,384,top-63,start,top,4,0,stone,0,1])
    return rows


def initial(source,spec,i):
    x,z,minimum,height,terrain,biome,top,mode,start,stone,cover,write=spec
    if mode==4:biome=(biome+i)%len(source['biomes'])
    m=stone if mode==1 else (start+i)%len(source['materials'])
    cells=[stone]*(top+1)+[0]*(height-top-1);cells[top]=m
    level=top+1
    if mode==1:
        level=source['sea_level']-minimum;cells[top+1:level]=[source['water']]*(level-top-1)
    if i%8==0 and level<height:cells[level]=cover
    return cells,biome,not(mode==2 and i%2==0)


def expected(source,spec,i):
    cells,biome,valid=initial(source,spec,i)
    return (cells if spec[7]==6 else oracle(source,cells,biome,valid,spec[2],spec[4],spec[0]+i%16,spec[1]+i//16)),biome,valid


def oracle(source,cells,biome,valid,minimum,terrain,x,z):
    out=list(cells)
    if not valid:return out
    b=source['biomes'][biome];temperature=f32(b.get('temperature',0.));sea=source['sea_level']
    if terrain>sea+17:
        noise=f32(perlin((x*.125,0.,z*.125),1234))
        temperature=f32(temperature-f32(f32(f32(noise*8.)+f32(terrain-sea-17))*.00125))
    if not b.get('flags',0)&32 or temperature>=f32(.15):return out
    at=sea-minimum
    if 0<at<len(out) and out[at-1]==source['water'] and out[at]==0 and source['ice']:out[at-1]=source['ice']
    if b.get('snow_surface',False) and source['terrain_features'].get('snow_layer',0):
        for y in range(len(out)-2,-1,-1):
            m=out[y]
            if not source['heightmap_masks'][m]&16:continue
            supports=source.get('snow_support',[])
            support=supports[m] if supports else m!=source['ice']
            if support and out[y+1]==0 and m not in (source['water'],source['lava']):out[y+1]=source['terrain_features']['snow_layer']
            break
    return out


def check(data,source,spec):
    at=0
    def words(n):
        nonlocal at
        value=struct.unpack_from('>'+str(n)+'I',data,at);at+=n*4;return value
    assert words(8)==(spec[0]&MASK,spec[1]&MASK,16,16,123456789,2147483648,spec[2]&MASK,spec[3])
    columns=[];changed=snow=ice=0
    for i in range(256):
        h,b,count,valid,n=words(5);want,biome,v=expected(source,spec,i)
        assert (h,b,count,valid)==(spec[4]&MASK,biome,n,int(v))
        cells=[];previous=None
        for _ in range(n):
            a,end,m=words(3);assert a==len(cells) and a<end<=spec[3] and m!=previous
            cells.extend([m]*(end-a));previous=m
        assert cells==want,(spec,i,next((y for y,(a,b) in enumerate(zip(cells,want)) if a!=b),None))
        before,_,_=initial(source,spec,i)
        changed+=sum(a!=b for a,b in zip(cells,before))
        snow+=sum(a!=b and a==source['terrain_features']['snow_layer'] for a,b in zip(cells,before))
        ice+=sum(a!=b and a==source['ice'] for a,b in zip(cells,before));columns.append(cells)
    assert at==len(data)
    return columns,dict(voxels=256*spec[3],changed=changed,snow_layers=snow,frozen_water=ice,sha256=hashlib.sha256(data).hexdigest())


def check_mca(path,source,columns):
    chunks=read_region(path.read_bytes());assert len(chunks)==1;tag=next(iter(chunks.values()))['tag'];maps=[[0]*256 for _ in range(6)]
    for s,section in enumerate(tag['sections']):
        for i,state in enumerate(palette(section['block_states'],4096,4)):
            m=columns[i%256][s*16+i//256];want=source['materials'][m];want={'id':want} if isinstance(want,str) else want
            assert state==want
            for k in range(6):
                if source['heightmap_masks'][m]&(1<<k):maps[k][i%256]=s*16+i//256+1
    bits=(len(tag['sections'])*16).bit_length()
    for k,key in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][key],256,bits)==maps[k]
    return dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),blocks_checked=len(columns)*len(columns[0]))


def exercise(binary,path,index,gpu,folder):
    source=json.loads(path.read_text());specs=fixtures(source);payload=copy.deepcopy(source);payload['snow_test_specs']=specs
    folder.mkdir(parents=True,exist_ok=True)
    inp=folder/f'profile-{index}.rbp';inp.write_bytes(fixture_wire(payload,True))
    # Keep prefixes as ordinary paths; each saved case has its own private file.
    out=folder/f"profile-{index}-{'gpu' if gpu else 'cpu'}"
    result=command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','on' if gpu else 'off'],timeout=1800)
    assert 'PASS: snow columns' in result.stdout
    runs=[];raw=[]
    for i,spec in enumerate(specs):
        data=Path(str(out)+f'.{i}').read_bytes();columns,row=check(data,source,spec);raw.append(data)
        if spec[11]:row['mca']=check_mca(Path(str(out)+f'.{i}.mca'),source,columns)
        runs.append(row)
    assert sum(r['snow_layers'] for r in runs)>0 and sum(r['frozen_water'] for r in runs)>0
    return runs,raw,result.stderr


def malformed(binary,path,folder):
    source=json.loads(path.read_text());cases=[]
    def bad(name,change):
        value=copy.deepcopy(source);value['snow_test_specs']=fixtures(value)[:1];change(value);cases.append((name,value))
    bad('support_short',lambda s:s.__setitem__('snow_support',[False]))
    bad('support_not_boolean',lambda s:s['snow_support'].__setitem__(0,1))
    bad('mask_short',lambda s:s.__setitem__('heightmap_masks',[0]))
    bad('water_zero',lambda s:s.__setitem__('water',0))
    bad('ice_outside',lambda s:s.__setitem__('ice',len(s['materials'])))
    bad('layer_outside',lambda s:s['terrain_features'].__setitem__('snow_layer',len(s['materials'])))
    bad('biomes_empty',lambda s:s.__setitem__('biomes',[]))
    bad('temperature_nan',lambda s:s['biomes'][0].__setitem__('temperature',float('nan')))
    bad('flags_negative',lambda s:s['biomes'][0].__setitem__('flags',-1))
    bad('surface_string',lambda s:s['biomes'][0].__setitem__('snow_surface','true'))
    bad('feature_type',lambda s:s.__setitem__('terrain_features',[]))
    for name,value in cases:
        inp=folder/('invalid-'+name+'.rbp');out=folder/('invalid-'+name+'.out');inp.write_bytes(fixture_wire(value,True))
        result=subprocess.run(['nice','-n','10',str(binary),str(inp),str(out),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=180)
        # Nonfinite numbers reject at the shared registry transport before the
        # typed snow projection; the other cases reach its own error 777.
        code,message=(701,'invalid registry transport') if name=='temperature_nan' else (777,'invalid snow profile')
        assert result.returncode==code%256 and message in result.stderr,(name,result.returncode,result.stdout,result.stderr)
        assert not list(folder.glob(out.name+'.*')),name
    return [name for name,_ in cases]


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True);p.add_argument('--skip-build',action='store_true');p.add_argument('--metal-probe',type=Path);a=p.parse_args()
    binary=ROOT/'build/bend/snow';driver=ROOT/'bend/tests/snow.bend';folder=ROOT/'build/bend/snow-tests';folder.mkdir(parents=True,exist_ok=True)
    if not a.skip_build:
        build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=900)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800)
    runs=[]
    for i,path in enumerate(a.profile):
        cpu,raw,_=exercise(binary,path,i,False,folder);gpu,other,_=exercise(binary,path,i,True,folder);assert raw==other
        runs.append(dict(profile=str(path),cpu=cpu,gpu=gpu,cpu_gpu_bytes_equal=True));print('profile',i,'pass',flush=True)
    report=dict(scope=__doc__.strip(),runs=runs)
    report['malformed_rejected']=malformed(binary,a.profile[0],folder)
    if a.metal_probe:
        assert binary.with_suffix('.gpu').read_bytes()==a.metal_probe.with_suffix('.gpu').read_bytes()
        observed,raw,stderr=exercise(a.metal_probe,a.profile[0],0,True,folder/'observer')
        times=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)];assert times
        assert observed==runs[0]['gpu'];report['observed_metal_device_ms']=times
    paths=sources(driver)|{Path(__file__).resolve()};report['sources']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
    report['native_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    (ROOT/'docs/benchmarks/bend-snow-correctness.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()

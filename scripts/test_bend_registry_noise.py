#!/usr/bin/env python3
"""Check typed loaded noise parameters and actual Bend GPU sampling.

This exercises climate/ridge octave stacks. Registry density programs and the
complete terrain pipeline are separate unfinished work, not implied by this test.
"""
import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, configuration, metal_observer
from test_bend_noise import f32, world_simplex
from test_bend_registry import path_request

MASK = 0xffffffff


def fixture_wire(value, packed=False):
    # Small independent test encoder, never used by production. Actual loaded
    # profiles use the shared Java streaming encoder and are passed as files.
    dictionary = {}
    def key(text):
        return dictionary.setdefault(text,len(dictionary))
    def node(value):
        if value is None: return [0,2]
        if type(value) is bool: return [1+int(value),2]
        if type(value) is int:
            return [3,4,(value >> 32)&MASK,value&MASK]
        if type(value) is float:
            hi,lo = struct.unpack('>2I',struct.pack('>d',value))
            return [4,4,hi,lo]
        if type(value) is str: return [5,3,key(value)]
        if type(value) is dict:
            children = []
            for name,item in value.items(): children += [key(name)]+node(item)
            return [7,len(children)+3,len(value)]+children
        if packed and value and all(type(v) is float for v in value):
            words = [word for v in value for word in struct.unpack('>2I',struct.pack('>d',v))]
            return [10,len(words)+3,len(value)]+words
        children = [word for item in value for word in node(item)]
        return [6,len(children)+3,len(value)]+children
    tape = node(value)
    words = []
    for text in dictionary:
        encoded = text.encode('utf-16-be','surrogatepass')
        words += [len(encoded)//2]
        encoded += b'\0\0' if len(encoded)%4 else b''
        words += [v for (v,) in struct.iter_unpack('>I',encoded)]
    return struct.pack(f'>{5+len(words)+len(tape)}I',0x52425031,1,len(dictionary),len(words),len(tape),*words,*tape)


def reference(profile,channel,low,high,x,z):
    frequency,amplitude = f32(profile['frequency']),f32(profile['amplitude'])
    values,weights = [],[]
    for i,modifier in enumerate(profile['modifiers']):
        weight = f32(max(f32(modifier),0)/(1 << i))
        if weight > 0:
            weights.append(weight)
            values.append(world_simplex(x&MASK,z&MASK,f32(frequency*(1 << i)),
                (low+channel*7919+i*1013)&MASK,high)*weight)
    return min(1.,max(-1.,sum(values)/max(.0001,sum(weights))*amplitude*f32(1.6)))


def exercise(binary,profiles,invalid,gpu):
    worker = Worker(binary,gpu)
    maximum,grids,checks = 0.,[],0
    try:
        assert worker.call(8,struct.pack('>3I',0,0,0),status=1) == struct.pack('>I',708)
        for payload in (b'',b'\0'*11,b'\0'*13,struct.pack('>3I',0,0,5),struct.pack('>3I',0,0,MASK)):
            assert worker.call(8,payload,status=1) == struct.pack('>I',603)
        for name,source,path in profiles:
            worker.call(5,path_request(path))
            for channel,noise in enumerate(source['noises']+[source['weirdness_noise']]):
                for low,high in ((0,0),(0x12345678,0x80000001)):
                    worker.call(8,struct.pack('>3I',low,high,channel))
                    for x,z,width,height,step in ((-5401,9343,8,8,1),(29999900,-30000000,8,8,3),
                        (-2147483648,2147483548,8,8,1),(7143,-399,128,128,1)):
                        request = struct.pack('>5I',x&MASK,z&MASK,width,height,step)
                        data = worker.call(2,request)
                        values = struct.unpack(f'>{width*height}f',data)
                        indices = range(len(values)) if len(values) < 1000 else range(0,len(values),127)
                        for i in indices:
                            expected = reference(noise,channel,low,high,x+(i%width)*step,z+(i//width)*step)
                            error = abs(values[i]-expected)
                            assert math.isfinite(values[i]) and error < .00001,(name,channel,x,z,i,values[i],expected,error)
                            maximum = max(maximum,error);checks += 1
                        grids.append(hashlib.sha256(data).hexdigest())
                    # Verify typed projection matches an explicitly prepared stack
                    # byte-for-byte; the scalar equations above remain independent.
                    probe = struct.pack('>5I',(-5401)&MASK,9343,16,16,1)
                    loaded = worker.call(2,probe)
                    mods = noise['modifiers']
                    worker.call(1,struct.pack(f'>2I2f2I{len(mods)}f',low,high,noise['frequency'],noise['amplitude'],channel,len(mods),*mods))
                    assert loaded == worker.call(2,probe)
            assert worker.call(0)  # one resident process across all full snapshots
        probe = struct.pack('>5I',55,123,8,8,1)
        worker.call(1,configuration())
        old = worker.call(2,probe)
        for path,code,channel in invalid:
            worker.call(5,path_request(path))
            assert worker.call(8,struct.pack('>3I',0,0,channel),status=1) == struct.pack('>I',code),path
            assert worker.call(2,probe) == old,'failed typed preparation changed cached noise'
        worker.call(4)
        assert worker.process.wait(timeout=10) == 0
        diagnostics = worker.process.stderr.read().decode()
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':len(profiles),
                'independent_sample_checks':checks,'grids':len(grids),'grid_sha256':hashlib.sha256(b''.join(bytes.fromhex(h) for h in grids)).hexdigest(),'max_reference_error':maximum,
                'invalid_typed_profiles_rejected':len(invalid),'old_stack_preserved':'pass',
                'requests_in_one_process':worker.id,'observed_metal_command_ms':
                    [float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)]}
    finally: worker.close()


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    # Mixed lexical integers/reals and shuffled schema keys exercise the typed
    # resolver without depending on a particular dictionary ID or field order.
    model = {'noise_source':'\0not a schema key 🌍','weirdness_noise':
        {'modifiers':[1.,0.,.5,2.],'amplitude':.75,'frequency':.001953125},
        'noises':[{'modifiers':[1,0.,.5,-1],'amplitude':1,'frequency':.0035},
                  {'frequency':0,'modifiers':[1.]*32,'amplitude':0.8},
                  {'frequency':.000244140625,'amplitude':.25,'modifiers':[1.,1.,2.]},
                  {'amplitude':.9,'frequency':.015625,'modifiers':[0.,0.,0.]}]}
    profiles = []
    for packed in (False,True):
        path = folder/f'schema-{int(packed)}.rbp'
        path.write_bytes(fixture_wire(model,packed))
        profiles.append((f'schema-{int(packed)}',model,path))
    invalid = []
    cases = [({},710,0),(None,710,0),({'noises':model['noises'][:3]},710,0),
             ({'noises':[None]*4},710,0),({'noises':[1]*4},710,0),({},710,4)]
    for field,value,code in [('frequency',True,710),('frequency',-1.,501),
        ('frequency',1.e200,501),('amplitude','bad',710),('amplitude',-1,501),
        ('modifiers',[],710),('modifiers',[1.]*33,710),('modifiers',[True],710),
        ('modifiers',[None],710),('modifiers',False,710),('modifiers',[1.e200],501),
        ('modifiers',[1.e7],501)]:
        bad = copy.deepcopy(model);bad['noises'][0][field] = value;cases.append((bad,code,0))
    for i,(source,code,channel) in enumerate(cases):
        path = folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source))
        invalid.append((path,code,channel))
    return profiles,invalid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--profile',nargs=2,action='append',metavar=('JSON','WIRE'),default=[])
    parser.add_argument('--prove-metal',action='store_true')
    args = parser.parse_args()
    binary = ROOT/'build/bend/engine'
    if not args.skip_build: build(args.bend,binary,ROOT/'bend/engine.bend')
    profiles,invalid = fixtures(ROOT/'build/bend/noise-profiles')
    report = {'scope':'Loaded climate/ridge octave stacks; full density programs and terrain integration pending',
              'profiles':[]}
    for source,path in args.profile:
        source,path = Path(source),Path(path)
        model = json.loads(source.read_text())
        profiles.append((source.parent.name,model,path))
        report['profiles'].append({'name':source.parent.name,'json_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    report['runs'] = [exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)]
    report['cpu_gpu_bytes_equal'] = report['runs'][0]['grid_sha256'] == report['runs'][1]['grid_sha256']
    if args.prove_metal:
        diagnostic = ROOT/'build/bend/registry-noise-metal-probe'
        observer = metal_observer(args.bend,ROOT/'bend/engine.bend',diagnostic)
        observed = exercise(diagnostic,profiles,invalid,True)
        assert observed['grid_sha256'] == report['runs'][1]['grid_sha256']
        assert len(observed['observed_metal_command_ms']) == len(profiles)*60+1+len(invalid)
        assert all(x > 0 for x in observed['observed_metal_command_ms'])
        report['metal_observation'] = {'diagnostic_only':True,'baseline_command_buffers':len(observer['observed_metal_command_ms']),
            'loaded_profile_command_buffers':len(observed['observed_metal_command_ms']),
            'loaded_profile_device_ms':{'min':min(observed['observed_metal_command_ms']),'max':max(observed['observed_metal_command_ms']),'sum':sum(observed['observed_metal_command_ms'])},'output_bytes_equal':True}
    for name in ('bend/numbers.bend','bend/registry_values.bend','bend/registry_noise.bend','bend/engine.bend'):
        report.setdefault('source_sha256',{})[name] = hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    destination = ROOT/'build/bend/registry-noise-tests.json'
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {len(profiles)} loaded input profiles; independent CPU/GPU samples; {destination}')


if __name__ == '__main__': main()

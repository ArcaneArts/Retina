#!/usr/bin/env python3
"""Check complete registry transport against source JSON and a pure Bend worker.

These are structural/input correctness checks, not world-generator benchmarks.
The Java side only encodes data; Bend alone validates and interprets its tape.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, configuration

MASK = 0xffffffff


class Document:
    def __init__(self, data):
        self.data = data
        magic, version, count, dictionary, tape = struct.unpack_from('>5I', data)
        assert (magic, version) == (0x52425031, 1)
        assert len(data) == 4*(5+dictionary+tape)
        self.strings = []
        at = 5
        for _ in range(count):
            length = self.word(at)
            self.strings.append(data[4*(at+1):4*(at+1)+length*2].decode('utf-16-be', 'surrogatepass'))
            at += 1+(length+1)//2
        assert at == 5+dictionary
        assert len(set(self.strings)) == count
        self.ids = {text:i for i,text in enumerate(self.strings)}
        self.root = at
        self.summary = [len(data)//4, count, at, self.hash()]
        self.counts = collections.Counter()

    def word(self, at): return struct.unpack_from('>I', self.data, at*4)[0]
    def wide(self, at): return struct.unpack_from('>Q', self.data, at*4)[0]
    def signed(self, at): return struct.unpack_from('>q', self.data, at*4)[0]

    def hash(self):
        result = 2166136261
        for (word,) in struct.iter_unpack('>I', self.data[20:]): result = ((result ^ word)*16777619) & MASK
        return result

    def compare(self, at, source):
        tag, span = self.word(at), self.word(at+1)
        self.counts[tag] += 1
        if tag == 0: assert source is None and span == 2
        elif tag in (1,2): assert type(source) is bool and source == (tag == 2) and span == 2
        elif tag == 3: assert type(source) is int and source == self.signed(at+2) and span == 4
        elif tag == 4: assert type(source) is float and struct.pack('>d',source) == self.data[4*(at+2):4*(at+4)] and span == 4
        elif tag == 5: assert type(source) is str and source == self.strings[self.word(at+2)] and span == 3
        elif tag == 7:
            assert type(source) is dict and len(source) == self.word(at+2)
            child = at+3
            for key, value in source.items():
                assert self.strings[self.word(child)] == key
                child = self.compare(child+1,value)
            assert child == at+span
        elif tag == 6:
            assert type(source) is list and len(source) == self.word(at+2)
            child = at+3
            for value in source: child = self.compare(child,value)
            assert child == at+span
        elif tag in (8,9,10,11):
            assert type(source) is list and len(source) == self.word(at+2)
            for i,value in enumerate(source):
                if tag == 8: assert type(value) is int and value == struct.unpack_from('>i',self.data,4*(at+3+i))[0]
                elif tag == 9: assert type(value) is int and value == self.signed(at+3+i*2)
                elif tag == 10: assert type(value) is float and struct.pack('>d',value) == self.data[4*(at+3+i*2):4*(at+5+i*2)]
                else: assert type(value) is bool and value == bool((self.word(at+3+i//32) >> (i%32)) & 1)
            self.counts['packed_values'] += len(source)
        else: raise AssertionError(tag)
        return at+span

    def path(self, parts):
        steps = []
        for part in parts: steps.extend([1,part] if type(part) is int else [0,self.ids[part]])
        return struct.pack(f'>{len(steps)+1}I',len(parts),*steps)


def path_request(path):
    points = list(map(ord,str(path.resolve())))
    return struct.pack(f'>{len(points)+1}I',len(points),*points)


def check_query(worker, doc, source, path):
    expected = source
    for step in path: expected = expected[step]
    tag, a, b, _ = struct.unpack('>4I',worker.call(6,doc.path(path)))
    if expected is None: assert tag == 0
    elif type(expected) is bool: assert tag == 1+int(expected)
    elif type(expected) is int: assert tag == 3 and ((a << 32)|b) == expected & 0xffffffffffffffff
    elif type(expected) is float: assert tag == 4 and struct.pack('>2I',a,b) == struct.pack('>d',expected)
    elif type(expected) is str:
        assert tag == 5 and doc.strings[a] == expected
        check_text(worker,a,expected)
    elif type(expected) is dict: assert tag == 7 and a == len(expected)
    else: assert tag in (6,8,9,10,11) and a == len(expected)


def check_text(worker, key, expected):
    raw = worker.call(7,struct.pack('>I',key))
    length = struct.unpack_from('>I',raw)[0]
    assert len(raw) == 4+4*((length+1)//2)
    assert raw[4:4+length*2].decode('utf-16-be','surrogatepass') == expected
    assert length % 2 == 0 or raw[-2:] == b'\0\0'


def corruptions(doc):
    data = doc.data
    def changed(at,value):
        copy = bytearray(data);struct.pack_into('>I',copy,at*4,value);return bytes(copy)
    cases = [(b'',703),(data[:19],703),(data[:-1],703),(data+b'\0',701),
             (changed(0,0),700),(changed(1,2),700),(changed(2,1048577),700),
             (changed(3,MASK),700),(changed(4,MASK),700),(changed(doc.root,12),701),
             (changed(doc.root+1,MASK),701),(changed(doc.root+2,MASK),701),
             (changed(doc.root+3,len(doc.strings)),701),(changed(5,MASK),701)]
    # Independently construct malformed node shapes (no dictionary), not only
    # byte damage that might fail at the outer header before reaching a node.
    for tape in [[4,4,0x7ff00000,0],[4,4,0xfff00000,1],[5,3,0],
                 [6,3,1],[7,3,1],[8,4,2,1],[9,4,1,1],[10,5,1,0x7ff80000,0],
                 [11,4,1,2],[11,3,0x40000001],[6,5,0,0,2],
                 [6,6,1,0,2,0]]:
        cases.append((struct.pack(f'>{5+len(tape)}I',0x52425031,1,0,0,len(tape),*tape),701))
    # Depth 129 exceeds the accepted 0..128 levels.
    tape = [0,2]
    for _ in range(129): tape = [6,len(tape)+3,1]+tape
    cases.append((struct.pack(f'>{5+len(tape)}I',0x52425031,1,0,0,len(tape),*tape),701))
    # An odd UTF-16 string must have zero padding in its final low half.
    cases.append((struct.pack('>10I',0x52425031,1,1,2,3,1,0x00610001,5,3,0),701))
    return cases


def exercise(binary, fixtures, source_paths, gpu):
    worker = Worker(binary,gpu)
    queries = 0
    try:
        assert worker.call(6,struct.pack('>I',0),status=1) == struct.pack('>I',708)
        worker.call(1,configuration())
        noise_request = struct.pack('>5I',MASK,30000000,16,16,1)
        noise_before = worker.call(2,noise_request)
        for i, (path,source_path) in enumerate(zip(fixtures,source_paths)):
            source = json.loads(source_path.read_text())
            doc = Document(path.read_bytes())
            assert list(struct.unpack('>4I',worker.call(5,path_request(path)))) == doc.summary
            if i == 0:
                paths = [[],['null'],['false'],['true'],['text'],['empty'],['object'],['long',0],['long',1],
                         *[['int',i] for i in range(4)],*[['real',i] for i in range(4)],
                         *[['flags',i] for i in range(3)],['mixed',0],['mixed',1],['mixed',2],['nested','x',0,'y']]
            else:
                paths = [[],['sea_level'],['materials',0],['biomes',0,'id'],['biomes',len(source['biomes'])-1,'id'],
                         ['noises',0,'frequency'],['noises',0,'modifiers',0],['registry_program'],
                         ['structures','templates',0,'blocks',0],['structures','templates',0,'blocks',3],
                         ['lighting'],['heightmap_masks',len(source['materials'])-1]]
            for path_steps in paths: check_query(worker,doc,source,path_steps);queries += 1
            assert worker.call(2,noise_request) == noise_before,'profile upload replaced resident noise'
            for key,text in enumerate(doc.strings): check_text(worker,key,text)
            del doc, source
        # Re-load a fixture under a Unicode path, delete it, then query the owned
        # snapshot repeatedly: no lazy dependency on a staging file remains.
        doc = Document(fixtures[0].read_bytes());source = json.loads(source_paths[0].read_text())
        staged = fixtures[0].with_name('staged-🌍.rbp');staged.write_bytes(doc.data)
        worker.call(5,path_request(staged));staged.unlink()
        check_query(worker,doc,source,['long',1]);queries += 1
        broken = fixtures[0].with_name('broken.rbp')
        for data,code in corruptions(doc):
            broken.write_bytes(data)
            assert worker.call(5,path_request(broken),status=1) == struct.pack('>I',code)
            check_query(worker,doc,source,['long',1]);queries += 1
        assert worker.call(5,path_request(broken.with_name('missing.rbp')),status=1) == struct.pack('>I',702)
        assert worker.call(5,path_request(broken.parent),status=1) in [struct.pack('>I',703),struct.pack('>I',704)]
        assert worker.call(7,struct.pack('>I',len(doc.strings)),status=1) == struct.pack('>I',707)
        assert worker.call(7,b'\0',status=1) == struct.pack('>I',603)
        for payload in [b'',struct.pack('>I',129),struct.pack('>3I',1,4,0)]:
            assert worker.call(6,payload,status=1) == struct.pack('>I',603)
        assert worker.call(6,struct.pack('>3I',1,0,MASK),status=1) == struct.pack('>I',707)
        assert worker.call(6,doc.path(['int',99]),status=1) == struct.pack('>I',707)
        assert worker.call(6,doc.path(['long',1,'null']),status=1) == struct.pack('>I',707)
        for payload in [struct.pack('>I',4097),struct.pack('>2I',1,0),struct.pack('>2I',1,0xd800),struct.pack('>2I',1,0x110000)]:
            assert worker.call(5,payload,status=1) == struct.pack('>I',603)
        check_query(worker,doc,source,['nested','x',0,'y']);queries += 1
        assert worker.call(2,noise_request) == noise_before
        worker.call(4)
        return {'mode':'gpu_required' if gpu else 'cpu','queries':queries,
                'malformed_uploads_rejected':len(corruptions(doc)),'requests_in_one_process':worker.id,
                'old_profile_and_noise_preserved':'pass','deleted_staging_file':'pass'}
    finally: worker.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--classpath',required=True)
    parser.add_argument('--profile',action='append',type=Path,default=[])
    parser.add_argument('--skip-build',action='store_true')
    args = parser.parse_args()
    binary = ROOT/'build/bend/engine'
    version = 'existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    directory = ROOT/'build/bend/registry-fixtures'
    subprocess.run(['nice','-n','10',str(args.java),'-Xmx256m','-cp',args.classpath,
        'art.arcane.retina.worldgen.BendProfileWireTest',str(directory),
        *map(str,args.profile),'--worker',str(binary)],check=True,cwd=ROOT,timeout=120)
    fixtures = [directory/'fixture.rbp']+[directory/f'profile-{i+1}.rbp' for i in range(len(args.profile))]
    source_paths = [directory/'fixture.json']+args.profile
    report = {'scope':'Registry transport and resident input access, not a terrain generator or performance benchmark',
              'bend_version':version,'java_heap_limit_mib':256,'profiles':[]}
    for path,source_path in zip(fixtures,source_paths):
        source = json.loads(source_path.read_text())
        doc = Document(path.read_bytes())
        assert doc.compare(doc.root,source) == len(doc.data)//4
        report['profiles'].append({'file':path.name,'bytes':len(doc.data),'strings':len(doc.strings),
            'tape_words':len(doc.data)//4-doc.root,'sha256':hashlib.sha256(doc.data).hexdigest(),
            'node_counts':dict(doc.counts)})
        print(f'Independent full-value check passed: {path.name}',flush=True)
        del source, doc
    report['workers'] = [exercise(binary,fixtures,source_paths,False),exercise(binary,fixtures,source_paths,True)]
    (ROOT/'build/bend/registry-tests.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: complete source values, persistent CPU/GPU profiles, query paths and rejected uploads')


if __name__ == '__main__': main()

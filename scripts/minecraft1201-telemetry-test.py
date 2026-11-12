"""Exercise optional Retina telemetry on a packaged 1.20.1 dedicated server without a graphical client."""
import argparse
import io
import json
import os
import select
import socket
import struct
import time
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

# Minecraft 1.20.1's ConnectionProtocol registration order, with compression disabled.
PROTOCOL = 763
LOGIN_DISCONNECT, LOGIN_PROFILE, LOGIN_QUERY = 0, 2, 4
PLAY_PAYLOAD, PLAY_DISCONNECT, PLAY_KEEPALIVE, PLAY_CHUNK, PLAY_LOGIN, PLAY_POSITION = 0x17, 0x1a, 0x23, 0x24, 0x28, 0x3c
SEND_TELEPORT, SEND_PAYLOAD, SEND_KEEPALIVE = 0, 0x0d, 0x12
REQUEST = 'retina:terrain_stats_request_v1'
RESPONSE = 'retina:terrain_stats_v1'


def read_exact(reader, count):
    data = bytearray()
    while len(data) < count:
        chunk = reader(count - len(data))
        if not chunk:
            raise EOFError('Minecraft connection ended mid-packet')
        data.extend(chunk)
    return bytes(data)


def read_varint(reader):
    value = 0
    for shift in range(0, 70, 7):
        byte = read_exact(reader, 1)[0]
        value |= (byte & 127) << shift
        if byte < 128:
            return value
    raise ValueError('Oversized Minecraft VarInt')


def varint(value):
    data = bytearray()
    while value >= 128:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def string(value):
    data = value.encode('utf-8')
    return varint(len(data)) + data


def read_string(packet):
    return read_exact(packet.read, read_varint(packet.read)).decode('utf-8')


def send(connection, packet_id, data=b''):
    packet = varint(packet_id) + data
    connection.sendall(varint(len(packet)) + packet)


def receive(connection, deadline):
    wait = max(0, deadline - time.monotonic())
    if wait == 0 or not select.select([connection], [], [], wait)[0]:
        return None, None
    packet = io.BytesIO(read_exact(connection.recv, read_varint(connection.recv)))
    return read_varint(packet.read), packet


def login(connection, port):
    send(connection, 0, varint(PROTOCOL) + string('localhost') + struct.pack('>H', port) + b'\x02')
    send(connection, 0, string('RetinaProbe') + b'\x00')
    deadline = time.monotonic() + 45
    while True:
        packet_id, packet = receive(connection, deadline)
        if packet_id == LOGIN_PROFILE:
            return
        if packet_id == LOGIN_QUERY:
            send(connection, 2, varint(read_varint(packet.read)) + b'\x00')
        if packet_id is None or packet_id == LOGIN_DISCONNECT:
            raise AssertionError('Vanilla login failed' if packet is None else read_string(packet))


def play(connection, deadline):
    packet_id, packet = receive(connection, deadline)
    if packet_id == PLAY_KEEPALIVE:
        send(connection, SEND_KEEPALIVE, packet.read())
    elif packet_id == PLAY_POSITION:
        packet.seek(33, io.SEEK_CUR)
        send(connection, SEND_TELEPORT, varint(read_varint(packet.read)))
    elif packet_id == PLAY_DISCONNECT:
        raise AssertionError(read_string(packet))
    return packet_id, packet


def read_stats(packet):
    active = read_exact(packet.read, 1)
    if active == b'\x00':
        assert not packet.read()
        return {'dimension': None}
    assert active == b'\x01'
    dimension = read_string(packet)
    rate, window, mean, p95 = struct.unpack('>4d', read_exact(packet.read, 32))
    samples, in_flight, chunks, previews, publications, failures = (read_varint(packet.read) for _ in range(6))
    assert not packet.read()
    assert 0 < window <= 10 and 0 <= samples <= 20 and rate >= 0 and p95 >= 0 and mean >= 0
    return dict(dimension=dimension, rate=rate, window=window, mean=mean, p95=p95, samples=samples,
                in_flight=in_flight, chunks=chunks, previews=previews, publications=publications, failures=failures)


def collect_stats(connection, seconds, first=False):
    deadline = time.monotonic() + seconds
    stats = []
    while True:
        packet_id, packet = play(connection, deadline)
        if packet_id is None:
            return stats
        if packet_id == PLAY_PAYLOAD and read_string(packet) == RESPONSE:
            stats.append(read_stats(packet))
            if first:
                return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--backend', default='metal', choices=('cpu', 'metal', 'vulkan'))
    args = parser.parse_args()
    spec = spec_from_file_location('legacy_server', Path(__file__).with_name('minecraft1201-server-test.py'))
    legacy = module_from_spec(spec)
    spec.loader.exec_module(legacy)
    support = legacy.sibling('bukkit-server-test')
    os.environ['RETINA_GPU_BACKEND'] = args.backend
    if args.forge_install:
        name = 'win_args.txt' if os.name == 'nt' else 'unix_args.txt'
        launch, = args.forge_install.glob('libraries/net/minecraftforge/forge/1.20.1-*/' + name)
        command = ['@' + str(launch.resolve())]
    else:
        command = ['-jar', str(args.server.resolve())]

    directory = args.output.resolve()
    legacy.prepare(args, directory)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    properties = directory / 'server.properties'
    properties.write_text(properties.read_text().replace('server-port=0', f'server-port={port}')
                          + 'network-compression-threshold=-1\n')
    server = support.Server(Namespace(java=args.java.resolve(), launch_args=command), directory)
    try:
        server.wait('Done (')
        with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
            login(connection, port)
            deadline = time.monotonic() + 60
            entered = False
            while True:
                packet_id, _ = play(connection, deadline)
                assert packet_id is not None, 'A vanilla client did not receive terrain'
                entered |= packet_id == PLAY_LOGIN
                if entered and packet_id == PLAY_CHUNK:
                    break
            assert not collect_stats(connection, .8), 'Unsubscribed client got unsolicited telemetry'
            send(connection, SEND_PAYLOAD, string(REQUEST) + b'\x01')
            assert not collect_stats(connection, .8), 'Malformed request received a response'
            for _ in range(12):
                send(connection, SEND_PAYLOAD, string(REQUEST))
            [overworld] = collect_stats(connection, .9)
            assert overworld['dimension'] == 'minecraft:overworld'
            assert overworld['chunks'] == overworld['previews'] * 1024 > 0
            assert 0 < overworld['publications'] <= overworld['previews'] and overworld['failures'] == 0
            server.command('execute in minecraft:the_nether run tp RetinaProbe 0 100 0', 'Teleported RetinaProbe')
            time.sleep(1)
            send(connection, SEND_PAYLOAD, string(REQUEST))
            [nether] = collect_stats(connection, 45, first=True)
            assert nether['dimension'] == 'minecraft:the_nether'
    finally:
        server.stop()
    result = {'status': 'pass', 'overworld': overworld, 'nether': nether}
    (directory / 'telemetry.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()

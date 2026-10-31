"""Bounded installed-JAR create/reopen acceptance; supply preinstalled headless servers."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cpu_selection(text, enabled):
    if not enabled:
        return None
    line = next((line for line in text.splitlines() if 'CPU backend selected:' in line), None)
    if line is None:
        raise AssertionError('Installed JAR did not report actual CPU selection')
    return line


def settings(directory):
    (directory / 'eula.txt').write_text('eula=true\n')
    (directory / 'server.properties').write_text(
        'level-name=world\nlevel-seed=123456789\nserver-ip=127.0.0.1\nserver-port=0\n'
        'online-mode=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-tick-time=0\n')


def mod(args, loader, directory):
    settings(directory)
    mods = directory / 'mods'; mods.mkdir(exist_ok=True)
    shutil.copy2(args.jar, mods / 'Retina.jar')
    if loader == 'fabric': shutil.copy2(args.fabric_api, mods / 'fabric-api.jar')
    config = directory / 'config'; config.mkdir(exist_ok=True)
    config_text = 'mca_parallelism = 2\n'
    if args.cpu:
        config_text += 'allow_directx = false\nallow_metal = false\nallow_vulkan = false\n'
    (config / 'retina.toml').write_text(config_text)
    results = []
    definition = directory / 'world/retina.dat'
    for phase in ('create', 'reopen'):
        before = digest(definition) if phase == 'reopen' else None
        command = [str(args.java), '-Xmx2G', '-XX:ActiveProcessorCount=4', '-Djava.awt.headless=true',
                   '--enable-native-access=ALL-UNNAMED', '--illegal-native-access=allow',
                   '-Dretina.qa=true', '-Dretina.qa.automatic=true', '-Dretina.qa.end.activation=true',
                   '-Dretina.qa.definition.failCompiler=true']
        if args.cpu:
            command += ['-Dretina.gpuBackend=auto']
        if phase == 'reopen':
            command += ['-Dretina.qa.automatic.reopen=true', '-Dretina.qa.catalog.failMaterializer=true']
        if loader == 'fabric': command += ['-jar', 'fabric-server-launch.jar', '--nogui']
        else:
            argument_file = next(directory.glob('libraries/net/neoforged/neoforge/*/unix_args.txt'))
            command += ['@' + str(argument_file.relative_to(directory)), '--nogui']
        log = args.output / f'{loader}-{phase}.log'
        (args.output / f'{loader}-{phase}-command.json').write_text(json.dumps(command, indent=2) + '\n')
        with log.open('w') as writer:
            subprocess.run(command, cwd=directory, stdout=writer, stderr=subprocess.STDOUT, check=True, timeout=240)
        events = []
        text = log.read_text()
        selected = cpu_selection(text, args.cpu)
        for line in text.splitlines():
            if 'QA_EVT ' in line:
                events.append(json.loads(line.split('QA_EVT ', 1)[1]))
        for name in ('minecraft_automatic_activation', 'minecraft_automatic_end_activation', 'minecraft_automatic_dedicated_save'):
            if not any(e.get('event') == name and e.get('status') == 'pass' for e in events):
                raise AssertionError(f'{loader} {phase}: missing {name}; see {log}')
        saved = {e['context']['dimension'] for e in events
                 if e.get('event') == 'minecraft_dimension_saved_edit' and e.get('status') == 'pass'}
        if saved != {'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'}:
            raise AssertionError(f'Missing saved edits: {saved}')
        opened = next(e for e in events if e.get('event') == 'world_definition_open')
        expected = 'saved-file' if phase == 'reopen' else 'bundled-vanilla'
        if opened.get('source') != expected: raise AssertionError(opened)
        if not any(e.get('event') == 'world_definition_closed_after_drain' for e in events):
            raise AssertionError('Missing closed-after-drain event')
        after = digest(definition)
        if before and before != after: raise AssertionError('Saved definition changed')
        if digest(mods / 'Retina.jar') != digest(args.jar): raise AssertionError('Installed JAR changed')
        results.append({'loader': loader, 'phase': phase, 'jar_sha256': digest(args.jar),
                        'definition_sha256': after, 'opened': opened, 'log': str(log), 'events': events,
                        'cpu_selection': selected})
    return results


def bukkit(args):
    spec = importlib.util.spec_from_file_location('bukkit_server_test', Path(__file__).with_name('bukkit-server-test.py'))
    driver = importlib.util.module_from_spec(spec); spec.loader.exec_module(driver)
    directory = args.bukkit_directory; directory.mkdir(parents=True, exist_ok=True)
    options = SimpleNamespace(java=args.java, server=args.bukkit_server, plugin=args.jar, probe=args.probe,
                              jvm_args=['-Dretina.gpuBackend=auto'] if args.cpu else [])
    driver.prepare(options, directory, seed=123456789)
    if args.cpu:
        config = directory / 'plugins/Retina/config.yml'
        with config.open('a') as writer:
            writer.write('allow_directx: false\nallow_metal: false\nallow_vulkan: false\n')
    results = []
    for phase in ('create', 'reopen'):
        definition = directory / 'world/retina.dat'
        before = digest(definition) if phase == 'reopen' else None
        server = driver.Server(options, directory, phase == 'reopen')
        try:
            server.wait('Done (')
            server.command('retinaprobe universal-smoke ' + phase, 'RETINA_UNIVERSAL_BUKKIT_PASS')
            server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
        text = server.log.read_text()
        selected = cpu_selection(text, args.cpu)
        if 'jar_sha256=' + digest(args.jar) not in text: raise AssertionError('Actual plugin code source differs from release JAR')
        if text.count('RETINA_UNIVERSAL_DIMENSION_PASS') != 3: raise AssertionError('Missing dimension checks')
        after = digest(definition)
        if before and before != after: raise AssertionError('Bukkit saved definition changed')
        log = args.output / f'bukkit-{phase}.log'; shutil.copy2(server.log, log)
        results.append({'loader': 'bukkit', 'phase': phase, 'jar_sha256': digest(args.jar),
                        'definition_sha256': after, 'log': str(log), 'cpu_selection': selected})
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('jar', 'java', 'fabric-directory', 'fabric-api', 'neoforge-directory',
                 'bukkit-directory', 'bukkit-server', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--loader', choices=('all', 'fabric', 'neoforge', 'bukkit'), default='all')
    parser.add_argument('--cpu', action='store_true', help='Disable all graphics APIs in the actual adapter config; leave backend override on auto')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result_file = args.output / 'loader-results.json'
    results = json.loads(result_file.read_text()) if result_file.exists() else []
    for loader in ('fabric', 'neoforge', 'bukkit'):
        if args.loader not in ('all', loader): continue
        results += bukkit(args) if loader == 'bukkit' else mod(args, loader, getattr(args, loader + '_directory'))
        result_file.write_text(json.dumps(results, indent=2) + '\n')
        print(loader + ' installed universal JAR create/reopen PASS', flush=True)

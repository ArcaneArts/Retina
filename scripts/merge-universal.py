"""Merge compiled runtime inputs, accepting duplicate entries only when byte-identical."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile


def merge(destination, sources):
    entries = {}
    owners = {}
    duplicates = {}
    for argument in sources:
        label, value = argument.split('=', 1)
        path = Path(value)
        if path.is_dir():
            members = ((p.relative_to(path).as_posix(), p.read_bytes())
                       for p in sorted(path.rglob('*')) if p.is_file())
        else:
            with zipfile.ZipFile(path) as archive:
                members = [(name, archive.read(name)) for name in sorted(archive.namelist())
                           if not name.endswith('/') and name != 'META-INF/MANIFEST.MF']
        for name, content in members:
            if name in entries:
                if entries[name] != content:
                    raise ValueError(f'Conflicting universal entry {name}: {owners[name]} and {label}')
                duplicates.setdefault(name, [owners[name]]).append(label)
            else:
                entries[name] = content
                owners[name] = label
    destination = Path(destination)
    if destination.exists():
        shutil.rmtree(destination)
    for name, content in sorted(entries.items()):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    report = destination / 'META-INF/retina/runtime-inputs.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({'identical_duplicates': duplicates,
        'inputs': {name: {'owner': owners[name], 'sha256': hashlib.sha256(data).hexdigest()}
                   for name, data in sorted(entries.items())}}, sort_keys=True, indent=2) + '\n')
    print(f'Merged {len(entries)} runtime entries; {len(duplicates)} byte-identical duplicates')


if __name__ == '__main__':
    merge(sys.argv[1], sys.argv[2:])

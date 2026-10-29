"""Build the standalone probe with the same Windows toolchain as the library."""
from pathlib import Path
import json
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
target = 'x86_64-pc-windows-msvc'
linker = str(Path(shutil.which('cl')).with_name('link.exe'))
subprocess.run(['cargo', 'build', '--release', '--locked', '--bin', 'retina-gpu-probe',
                '--target', target, '--target-dir', str(root / 'build/native-target'),
                '--config', f'target.{target}.rustflags=["-C","target-feature=+crt-static"]',
                '--config', f'target.{target}.linker={json.dumps(linker)}'], cwd=root / 'native', check=True)
output = root / 'build/gpu-probe/retina-gpu-probe-windows-x64.exe'
output.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(root / 'build/native-target' / target / 'release/retina-gpu-probe.exe', output)
subprocess.run([str(output), '--help'], check=True)

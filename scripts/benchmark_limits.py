"""Bound opt-in local benchmarks, including blocked native calls and GPU workers.

The supervisor stays outside the process group doing the actual work. A Python
alarm/thread inside that process cannot reliably interrupt a native/GPU call.
These benchmark commands already use POSIX tools (nice and ps).
"""
import argparse
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

CHILD_ENV = 'RETINA_BENCHMARK_SUPERVISED_SCRIPT'


def positive_seconds(value):
    seconds = float(value)
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError('must be a finite number greater than zero')
    return seconds


def add_time_limit(parser):
    parser.add_argument('--max-seconds', type=positive_seconds, default=60.,
                        help='Total runtime limit, including startup and validation (default: 60 seconds)')


def write_control(path, value):
    temporary = path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(value, indent=2)+'\n')
    temporary.replace(path)


class Cancelled(Exception):
    def __init__(self, signum):
        self.signum = signum


def run_bounded(command, seconds, output, *, env=None, grace_seconds=1.):
    """Run a new process group; terminate the whole group on timeout/cancel/exit.

    output is a newly owned directory. run-control.json is separate from the
    child's checkpoints so an interrupted sample can never become a passed run.
    """
    control = dict(schema='retina.benchmark-control.v1', status='starting',
                   max_seconds=seconds, termination_grace_seconds=grace_seconds,
                   command=command, started_unix=time.time())
    path = output/'run-control.json'
    write_control(path, control)
    process = None
    started = time.monotonic()
    previous = {}

    def cancel(signum, frame):
        raise Cancelled(signum)

    def signal_group(signum):
        try:
            os.killpg(process.pid, signum)
        except ProcessLookupError:
            return False
        except PermissionError:
            # Signal zero only probes existence. EPERM still means a group
            # exists; allow the grace loop to wait for it to exit. On macOS a
            # successful SIGTERM can be followed briefly by EPERM here while
            # the child is exiting. Raising would skip the final SIGKILL.
            if signum == 0:
                return True
            # macOS can report EPERM while an exiting group is disappearing.
            # Check the actual process table before treating it as terminal;
            # a live group with a permissions failure must still report failure.
            rows = subprocess.check_output(['ps', '-axo', 'pgid=,stat='], text=True)
            for row in rows.splitlines():
                group, status = row.split()
                if int(group) == process.pid and not status.startswith('Z'):
                    raise
            return False
        return True

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, cancel)
        process = subprocess.Popen(command, env=env, start_new_session=True)
        control.update(status='running', pid=process.pid)
        write_control(path, control)
        code = process.wait(timeout=max(0., seconds-(time.monotonic()-started)))
        control.update(status='completed' if code == 0 else 'failed', exit_code=code)
    except subprocess.TimeoutExpired:
        code = 124
        control.update(status='timed_out', exit_code=code,
                       reason='Total runtime limit reached; no automatic retry')
    except Cancelled as stopped:
        code = 128+stopped.signum
        control.update(status='cancelled', exit_code=code, signal=stopped.signum)
    except BaseException as failure:
        control.update(status='failed', error=repr(failure))
        raise
    finally:
        # Repeated Ctrl-C must not interrupt process-group cleanup. Killing the
        # group also covers a worker left behind after its driver exits early.
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        try:
            if process is not None:
                signal_group(signal.SIGTERM)
                deadline = time.monotonic()+grace_seconds
                while time.monotonic() < deadline:
                    process.poll()  # Reap the driver when it exits.
                    if not signal_group(0):
                        break
                    time.sleep(min(.02, max(0., deadline-time.monotonic())))
                signal_group(signal.SIGKILL)
                process.wait(timeout=2.)
        except BaseException as failure:
            control.update(status='cleanup_failed', cleanup_error=repr(failure))
            raise
        finally:
            control['elapsed_seconds'] = time.monotonic()-started
            try:
                write_control(path, control)
            finally:
                for signum, handler in previous.items():
                    signal.signal(signum, handler)
    if code:
        print(f"Benchmark {control['status']}; completed checkpoints retained in {output}", file=sys.stderr)
    return code


def supervise(script, seconds, output):
    """Parent owns the output directory; return None only inside its child."""
    script = str(Path(script).resolve())
    if os.environ.get(CHILD_ENV) == script:
        return None
    output.mkdir(parents=True, exist_ok=False)
    return run_bounded([sys.executable, script, *sys.argv[1:]], seconds, output,
                       env=dict(os.environ, **{CHILD_ENV: script}))

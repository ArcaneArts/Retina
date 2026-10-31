"""Process counters for command-line benchmarks on Unix and Windows."""
import os
from types import SimpleNamespace

try:
    import resource
except ImportError:
    resource = None


def snapshot():
    if resource is not None:
        return resource.getrusage(resource.RUSAGE_SELF)
    # os.times uses process user/kernel times on Windows. Memory and scheduling
    # counters which this interface does not expose stay unavailable, never zero.
    times = os.times()
    return SimpleNamespace(ru_utime=times.user, ru_stime=times.system, ru_maxrss=None,
                           ru_nvcsw=None, ru_nivcsw=None, ru_minflt=None, ru_majflt=None)


def difference(after, before, name):
    old, new = getattr(before, name), getattr(after, name)
    return None if old is None or new is None else new-old


def peak_rss_bytes(usage):
    if usage.ru_maxrss is None:
        return None
    import platform
    return usage.ru_maxrss * (1 if platform.system() == "Darwin" else 1024)


SOURCE = "RUSAGE_SELF" if resource is not None else "os.times process user/kernel counters; RSS/context switches/faults unavailable"

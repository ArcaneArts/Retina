"""Process counters for command-line benchmarks on Unix and Windows."""
import os
from types import SimpleNamespace

try:
    import resource
except ImportError:
    resource = None


def windows_peak_working_set():
    """Optional lifetime high-water bytes; diagnostic failure never gates work.

    PROCESS_MEMORY_COUNTERS uses DWORD and SIZE_T (Windows is LLP64).
    https://learn.microsoft.com/windows/win32/api/psapi/ns-psapi-process_memory_counters
    """
    import ctypes as c
    class Counters(c.Structure):
        _fields_ = [("cb", c.c_uint32), ("faults", c.c_uint32)] + [
            (name, c.c_size_t) for name in ("peak", "working", "peak_paged", "paged",
                                           "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile")]
    try:
        kernel = c.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.argtypes = []
        kernel.GetCurrentProcess.restype = c.c_void_p
        kernel.K32GetProcessMemoryInfo.argtypes = [c.c_void_p, c.POINTER(Counters), c.c_uint32]
        kernel.K32GetProcessMemoryInfo.restype = c.c_int
        counters = Counters()
        counters.cb = c.sizeof(counters)
        if kernel.K32GetProcessMemoryInfo(kernel.GetCurrentProcess(), c.byref(counters), counters.cb):
            return counters.peak
    except (OSError, AttributeError):
        pass
    return None


def snapshot():
    if resource is not None:
        return resource.getrusage(resource.RUSAGE_SELF)
    # os.times uses process user/kernel times on Windows. Unsupported counters
    # remain unavailable, never zero. Working-set high-water is not phase RSS.
    times = os.times()
    peak = windows_peak_working_set() if os.name == "nt" else None
    return SimpleNamespace(ru_utime=times.user, ru_stime=times.system, ru_maxrss=peak,
                           ru_nvcsw=None, ru_nivcsw=None, ru_minflt=None, ru_majflt=None)


def difference(after, before, name):
    old, new = getattr(before, name), getattr(after, name)
    return None if old is None or new is None else new-old


def peak_rss_bytes(usage):
    if usage.ru_maxrss is None:
        return None
    import platform
    return usage.ru_maxrss * (1 if platform.system() in ("Darwin", "Windows") else 1024)


SOURCE = ("RUSAGE_SELF" if resource is not None else
          "os.times process user/kernel counters; optional K32GetProcessMemoryInfo lifetime peak working-set bytes; context switches/faults unavailable")

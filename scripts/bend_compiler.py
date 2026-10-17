"""Resource settings for Retina's pinned Bun-based Bend 2.0.37 compiler."""
import os


def compiler_environment():
    # Rayon settings do not control this JavaScript compiler. Keep GC marking
    # serial and JIT compilation synchronous instead. These pinned JSC options
    # reduce compiler CPU concurrency; they are not CPU affinity, a hard memory
    # limit, or a limit on Apple's external shader compiler services.
    return dict(os.environ, BEND_NO_TELEMETRY='1',
                BUN_JSC_numberOfGCMarkers='1', BUN_JSC_useConcurrentJIT='false',
                BUN_JSC_forceRAMSize='2147483648')

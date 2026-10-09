package art.arcane.retina.worldgen;

/** Actual packaged-resource startup, including Metal's executable-location cache. */
public final class BendPackagedRuntimeTest {
    public static void main(String[] args) throws Exception {
        var path=BendRuntime.executable();
        if(!path.toString().contains(".cache/retina/bend-2.0.36"))throw new AssertionError("Expected packaged content-addressed worker: "+path);
        var before=java.nio.file.Files.getLastModifiedTime(path);
        var metadata=java.nio.file.Files.getAttribute(path,"unix:ctime");
        // Simulate the resolver in a new client JVM. Even a redundant chmod
        // changes ctime and invalidates Metal's executable-location cache.
        var cached=BendRuntime.class.getDeclaredField("extracted");cached.setAccessible(true);cached.set(null,null);
        if(!path.equals(BendRuntime.executable()) || !metadata.equals(java.nio.file.Files.getAttribute(path,"unix:ctime")))
            throw new AssertionError("Fresh resolver changed the cached executable metadata");
        for(int i=0;i<2;i++) {
            long start=System.nanoTime();
            try(var worker=new BendWorker(path,BendWorker.Execution.GPU_REQUIRED,2)) {
                var hello=worker.request(0,new byte[0]).join();
                if(hello.length!=20)throw new AssertionError("Invalid packaged worker hello");
                System.out.printf("PASS: packaged GPU worker start %d in %.2f ms at %s%n",i,(System.nanoTime()-start)/1e6,path);
            }
        }
        if(!before.equals(java.nio.file.Files.getLastModifiedTime(BendRuntime.executable())))throw new AssertionError("Reusing the packaged runtime rewrote its executable");
        var cancelled=new java.util.concurrent.atomic.AtomicLong();
        try {
            new BendWorker(path,BendWorker.Execution.CPU,2,worker -> {
                cancelled.set(worker.processId());worker.close();
            });
            throw new AssertionError("Startup cancellation was ignored");
        } catch(java.io.IOException expected) {
            if(cancelled.get()==0 || ProcessHandle.of(cancelled.get()).map(ProcessHandle::isAlive).orElse(false))
                throw new AssertionError("Cancelled startup retained its process");
            System.out.println("PASS: world shutdown can terminate startup before handshake");
        }
    }
}

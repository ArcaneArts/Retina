package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeLibrary;
import art.arcane.retina.core.NativeWorldDefinition;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** First dlopen on a short-lived host thread, then repeated new-thread FFM calls. No Minecraft/GPU. */
public final class NativeWorldDefinitionThreadTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("retina.native.path",args[0]);
        var path=Path.of(args[1]);var failure=new AtomicReference<Throwable>();
        Runnable read=()->{
            try(var world=NativeWorldDefinition.open(path)) {
                if(!world.metadata().contains("dimensions")||!world.metadata().contains("seed"))throw new AssertionError("Saved world metadata missing");
            }catch(Throwable error){failure.compareAndSet(null,error);}
        };
        // Do not initialize NativeLibrary on the Java main thread: a JVM server
        // thread can be the first caller and exit before the next world opens.
        var first=Thread.ofPlatform().name("initial-world-thread").start(read);first.join();
        if(failure.get()!=null)throw new AssertionError("Initial host-thread load failed",failure.get());
        for(int batch=0;batch<16;batch++) {
            var threads=new Thread[4];
            for(int i=0;i<threads.length;i++)threads[i]=Thread.ofPlatform().name("world-reopen-"+batch+"-"+i).start(read);
            for(var thread:threads)thread.join();
            if(failure.get()!=null)throw new AssertionError("Host-thread world reopen failed",failure.get());
        }
        System.out.println("QA_EVT {\"event\":\"native_host_thread_reopen\",\"status\":\"pass\",\"context\":{\"threads\":65,\"concurrent_readers\":4,\"gpu_initialized\":false}}");
    }
}

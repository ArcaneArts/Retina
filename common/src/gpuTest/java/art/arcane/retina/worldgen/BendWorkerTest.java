package art.arcane.retina.worldgen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

/** Actual Bend IPC plus controlled transport fixtures for cancellation/failure races. */
public final class BendWorkerTest {
    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args.length == 0 ? "build/bend/engine" : args[0]);
        for (var execution : BendWorker.Execution.values()) real(binary, execution);
        Path folder = Files.createTempDirectory("retina-bend-worker-");
        try {
            Path fake = fixture(folder);
            cancellation(fake, folder);
            snapshot(fake, folder);
            queueBound(fake, folder);
            byteBound(fake, folder);
            failure(fake, folder, 8);
            failure(fake, folder, 9);
            failure(fake, folder, 10);
        } finally {
            try (var files = Files.walk(folder)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("QA_EVT {\"event\":\"bend_worker_transport\",\"status\":\"pass\",\"context\":{\"cpu_gpu\":true,\"queue_limit\":16,\"byte_budget_mib\":32,\"cancellation_shutdown_failure\":true}}");
    }

    private static void real(Path binary, BendWorker.Execution execution) throws Exception {
        long pid;
        try (var worker = new BendWorker(binary, execution, 2)) {
            pid = worker.processId();
            require(worker.execution() == execution, "actual strict runtime selection");
            try (var callers = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
                for (int caller = 0; caller < 4; caller++) {
                    int salt = caller;
                    pending.add(callers.submit(() -> {
                        try {
                            for (int i = 0; i < 16; i++) {
                                byte[] data = ("minecraft:stone/" + salt + "/" + i).repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                byte[] encoded = worker.request(3, data).get(15, TimeUnit.SECONDS);
                                var inflater = new Inflater();
                                try {
                                    inflater.setInput(encoded);
                                    byte[] actual = new byte[data.length + 1];
                                    int size = inflater.inflate(actual);
                                    require(size == data.length && inflater.finished() && inflater.getRemaining() == 0
                                            && java.util.Arrays.equals(data, java.util.Arrays.copyOf(actual, size)), "independent zlib decode");
                                } finally { inflater.end(); }
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }));
                }
                for (var request : pending) request.get(30, TimeUnit.SECONDS);
            }
            var config = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(config)) {
                out.writeInt(0x12345678); out.writeInt(0x80000000);
                out.writeFloat(.0035f); out.writeFloat(.8f); out.writeInt(2); out.writeInt(3);
                out.writeFloat(1); out.writeFloat(.25f); out.writeFloat(.5f);
            }
            worker.request(1, config.toByteArray()).get(10, TimeUnit.SECONDS);
            var grid = java.nio.ByteBuffer.allocate(20).putInt(-33).putInt(63).putInt(13).putInt(17).putInt(1).array();
            byte[] a = worker.request(2, grid).get(15, TimeUnit.SECONDS);
            byte[] b = worker.request(2, grid).get(15, TimeUnit.SECONDS);
            require(a.length == 13*17*4 && java.util.Arrays.equals(a,b), "resident noise profile and repeated grid");
            fails(worker.request(999, new byte[0]));
            require(worker.request(0, new byte[0]).get(10, TimeUnit.SECONDS).length == 20, "request rejection preserves worker");
            require(worker.processId() == pid, "all requests reuse one process");
        }
        dead(pid);
    }

    private static Path fixture(Path folder) throws Exception {
        Path executable = folder.resolve("worker-fixture");
        // Only simulates frames and controlled waits; it implements no generator
        // or codec, and production never invokes this fixture.
        Files.writeString(executable, """
                #!/usr/bin/env python3
                import pathlib,struct,sys,time
                root=pathlib.Path(__file__).parent
                def read(n):
                    b=b''
                    while len(b)<n:
                        p=sys.stdin.buffer.read(n-len(b))
                        if not p:sys.exit(0)
                        b+=p
                    return b
                while True:
                    magic,n,id,op=struct.unpack('>4I',read(16))
                    payload=read(n)
                    with (root/'calls').open('a') as f:f.write(str(op)+'\\n')
                    if op==7:
                        (root/'entered').touch()
                        while not (root/'release').exists():time.sleep(.01)
                    if op==9:sys.exit(4)
                    body=struct.pack('>5I',1,2,0,36,0) if op==0 else payload if op==3 else b''
                    sys.stdout.buffer.write(struct.pack('>5I',0 if op==8 else magic,len(body),id,op,2 if op==10 else 0)+body)
                    sys.stdout.buffer.flush()
                """);
        require(executable.toFile().setExecutable(true), "executable fixture");
        return executable;
    }

    private static void reset(Path folder) throws Exception {
        for (String name : List.of("entered", "release", "calls")) Files.deleteIfExists(folder.resolve(name));
    }

    private static void entered(Path folder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(folder.resolve("entered")) && System.nanoTime() < deadline) Thread.sleep(10);
        require(Files.exists(folder.resolve("entered")), "actual blocked request reached fixture");
    }

    private static void cancellation(Path fake, Path folder) throws Exception {
        reset(folder);
        try (var worker = new BendWorker(fake, BendWorker.Execution.CPU, 2)) {
            var active = worker.request(7, new byte[0]);
            entered(folder);
            require(active.cancel(true), "in-flight cancellation");
            var canceled = worker.request(3, new byte[1024]);
            require(canceled.cancel(true), "queued cancellation");
            var next = worker.request(0, new byte[0]);
            Files.createFile(folder.resolve("release"));
            fails(active);
            next.get(5, TimeUnit.SECONDS);
            require(Files.readAllLines(folder.resolve("calls")).equals(List.of("0", "7", "0")), "canceled request was not sent");
        }
    }

    private static void snapshot(Path fake, Path folder) throws Exception {
        reset(folder);
        try (var worker = new BendWorker(fake, BendWorker.Execution.CPU, 2)) {
            var active = worker.request(7, new byte[0]); entered(folder);
            byte[] data = {1,2,3};
            var echo = worker.request(3, data);
            data[0] = 9;
            Files.createFile(folder.resolve("release"));
            active.get(5, TimeUnit.SECONDS);
            require(java.util.Arrays.equals(echo.get(5, TimeUnit.SECONDS), new byte[]{1,2,3}), "queued payload snapshot");
        }
    }

    private static void queueBound(Path fake, Path folder) throws Exception {
        reset(folder);
        long pid;
        try (var worker = new BendWorker(fake, BendWorker.Execution.CPU, 2)) {
            pid = worker.processId();
            var active = worker.request(7, new byte[0]); entered(folder);
            var queued = new ArrayList<CompletableFuture<byte[]>>();
            for (int i = 0; i < 16; i++) queued.add(worker.request(0, new byte[0]));
            fails(worker.request(0, new byte[0]));
            worker.close();
            fails(active);
            for (var future : queued) fails(future);
        }
        dead(pid);
    }

    private static void byteBound(Path fake, Path folder) throws Exception {
        reset(folder);
        try (var worker = new BendWorker(fake, BendWorker.Execution.CPU, 2)) {
            var active = worker.request(7, new byte[0]); entered(folder);
            byte[] large = new byte[16*1024*1024];
            var first = worker.request(3, large); var second = worker.request(3, large);
            require(!first.isDone() && !second.isDone(), "two bounded large requests accepted");
            first.cancel(true); second.cancel(true);
            // Canceled queued jobs still own their cloned buffers until drained.
            fails(worker.request(3, new byte[1]));
            Files.createFile(folder.resolve("release"));
            active.get(5, TimeUnit.SECONDS);
            worker.request(0, new byte[0]).get(5, TimeUnit.SECONDS);
            Files.delete(folder.resolve("release")); Files.delete(folder.resolve("entered"));
            worker.request(7, new byte[0]); entered(folder);
            first = worker.request(3, large); second = worker.request(3, large);
            require(!first.isDone() && !second.isDone(), "drained cancellation restores full byte budget");
            fails(worker.request(3, new byte[1]));
            fails(worker.request(3, new byte[16*1024*1024+1]));
        }
    }

    private static void failure(Path fake, Path folder, int opcode) throws Exception {
        reset(folder);
        long pid;
        try (var worker = new BendWorker(fake, BendWorker.Execution.CPU, 2)) {
            pid = worker.processId();
            fails(worker.request(opcode, new byte[0]));
            fails(worker.request(0, new byte[0]));
        }
        dead(pid);
    }

    private static void fails(CompletableFuture<byte[]> future) throws Exception {
        try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("request unexpectedly succeeded"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expected) { }
    }

    private static void dead(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
        require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "Bend process terminated");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}

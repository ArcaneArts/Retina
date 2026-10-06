package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** Bounded interactive searches; only result delivery accesses the server thread. */
public final class RetinaLocateCommands {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("retina-locate-",0).factory());
    private static final ConcurrentHashMap<UUID,LocateTask> PENDING = new ConcurrentHashMap<>();
    private RetinaLocateCommands() { }

    private static class LocateTask extends FutureTask<Void> {
        final TerrainQueries context;
        LocateTask(TerrainQueries context, Callable<Void> search) { super(search); this.context=context; }
    }

    static void cancel(TerrainQueries context) {
        PENDING.forEach((id,task) -> {
            if (task.context==context && PENDING.remove(id,task)) {
                task.cancel(true); WORKER.remove(task);
            }
        });
    }

    public static <T> void submit(CommandSourceStack source, TerrainQueries context, Supplier<Pair<BlockPos,Holder<T>>> search,
                                  String name, String notFound, BiConsumer<Pair<BlockPos,Holder<T>>,Duration> deliver) {
        var server=source.getServer();
        var player=(ServerPlayer)source.getEntity();
        var id=player.getUUID();
        long started=System.nanoTime();
        // Keep ownership until delivery; cancelling a completed worker must also
        // suppress a result already queued on the server thread.
        var ticket=new java.util.concurrent.atomic.AtomicReference<LocateTask>();
        var task=new LocateTask(context,() -> {
            try {
                context.checkActive();
                var result=search.get();
                context.checkActive();
                var elapsed=Duration.ofNanos(System.nanoTime()-started);
                server.execute(() -> {
                    if (!PENDING.remove(id,ticket.get()) || !context.active() || player.hasDisconnected()) return;
                    if (result==null) source.sendFailure(Component.translatableEscape(notFound,name));
                    else deliver.accept(result,elapsed);
                });
            } catch (CancellationException ignored) {
                PENDING.remove(id,ticket.get());
            } catch (RuntimeException error) {
                Retina.LOGGER.error("Retina locate failed for {}",name,error);
                server.execute(() -> {
                    if (PENDING.remove(id,ticket.get()) && context.active() && !player.hasDisconnected()) source.sendFailure(Component.translatable("retina.locate.failed",name));
                });
            }
            return null;
        }) {
            @Override protected void done() { if (isCancelled()) PENDING.remove(id,this); }
        };
        ticket.set(task);
        var previous=PENDING.put(id,task);
        if (previous!=null) { previous.cancel(true); WORKER.remove(previous); }
        try { WORKER.execute(task); }
        catch (RejectedExecutionException error) {
            task.cancel(false); source.sendFailure(Component.translatable("retina.locate.busy"));
        }
    }
}

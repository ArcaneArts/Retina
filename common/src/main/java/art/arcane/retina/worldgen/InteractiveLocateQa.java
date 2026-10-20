package art.arcane.retina.worldgen;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.CompletableFuture;

/** Observe the real asynchronous player-command path without installing a result callback. */
final class InteractiveLocateQa {
    static CompletableFuture<BlockPos> locate(ServerPlayer player, String structure) {
        var result = new CompletableFuture<BlockPos>();
        var receiver = new CommandSource() {
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
            public void sendSystemMessage(Component message) {
                player.sendSystemMessage(message);
                try {
                    require(message.getContents() instanceof TranslatableContents text
                            && text.getKey().equals("commands.locate.structure.success"), "locate command succeeds: " + message.getString());
                    var text = (TranslatableContents) message.getContents();
                    require(text.getArgs()[0].equals(structure), "command locates requested structure");
                    var coordinates = (Component) text.getArgs()[1];
                    var click = (ClickEvent.SuggestCommand) coordinates.getStyle().getClickEvent();
                    var fields = click.command().split(" ");
                    result.complete(new BlockPos(Integer.parseInt(fields[2]), 0, Integer.parseInt(fields[4])));
                } catch (Throwable error) { result.completeExceptionally(error); }
            }
        };
        var server = player.level().getServer();
        var source = player.createCommandSourceStack().withSource(receiver)
                .withPermission(server.createCommandSourceStack().permissions());
        require(source.callback() == CommandResultCallback.EMPTY, "interactive locate path has no result callback");
        server.getCommands().performPrefixedCommand(source, "locate structure " + structure);
        require(!result.isDone(), "interactive locate delivers asynchronously");
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Interactive locate QA: " + message);
    }
}

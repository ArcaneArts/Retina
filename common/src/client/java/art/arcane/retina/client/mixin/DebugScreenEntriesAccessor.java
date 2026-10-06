package art.arcane.retina.client.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Register namespaced entries without depending on a loader's access widening. */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesAccessor {
    @Invoker("register")
    static Identifier retina$register(Identifier id, DebugScreenEntry entry) {
        throw new AssertionError("Client debug registry mixin was not applied");
    }
}

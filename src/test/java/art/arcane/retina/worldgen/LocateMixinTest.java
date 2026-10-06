package art.arcane.retina.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.commands.LocateCommand;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocateMixinTest {
    @Test
    void commandInjectionsApplyToMinecraft() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var methods=Arrays.stream(LocateCommand.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertTrue(methods.stream().anyMatch(name -> name.contains("retina$biome")),"biome locate injection applied");
        assertTrue(methods.stream().anyMatch(name -> name.contains("retina$structure")),"structure locate injection applied");
    }
}

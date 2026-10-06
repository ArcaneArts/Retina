package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.RetinaBiomeSource;
import art.arcane.retina.worldgen.RetinaLocateCommands;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocateCommand.class)
public abstract class LocateCommandMixin {
    private static RetinaChunkGenerator retina$interactive(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer) || source.callback()!=CommandResultCallback.EMPTY) return null;
        return source.getLevel().getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina && retina.queries()!=null ? retina : null;
    }

    @Inject(method="locateBiome",at=@At("HEAD"),cancellable=true)
    private static void retina$biome(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> target, CallbackInfoReturnable<Integer> callback) {
        var generator=retina$interactive(source);
        if (generator==null) return;
        var origin=BlockPos.containing(source.getPosition());
        var level=source.getLevel();
        var biomes=(RetinaBiomeSource)generator.getBiomeSource();
        int minY=level.getMinY(),maxY=level.getMaxY();
        RetinaLocateCommands.submit(source,generator.queries(),() -> biomes.findClosestQuery(origin,6400,32,64,target,minY,maxY),
                target.asPrintable(),"commands.locate.biome.not_found",(result,elapsed) ->
                        LocateCommand.showLocateResult(source,target,origin,result,"commands.locate.biome.success",true,elapsed));
        callback.setReturnValue(1);
    }

    @Inject(method="locateStructure",at=@At("HEAD"),cancellable=true)
    private static void retina$structure(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> target, CallbackInfoReturnable<Integer> callback) throws CommandSyntaxException {
        var generator=retina$interactive(source);
        if (generator==null) return;
        var registry=source.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> wanted=target.unwrap().map(id -> registry.get(id).map(h -> (HolderSet<Structure>)HolderSet.direct(h)),registry::get)
                .orElseThrow(() -> new DynamicCommandExceptionType(value -> Component.translatableEscape("commands.locate.structure.invalid",value)).create(target.asPrintable()));
        var origin=BlockPos.containing(source.getPosition());
        var search=generator.prepareStructureSearch(source.getLevel(),wanted,origin,100);
        RetinaLocateCommands.submit(source,generator.queries(),search,target.asPrintable(),
                "commands.locate.structure.not_found",(result,elapsed) ->
                        LocateCommand.showLocateResult(source,target,origin,result,"commands.locate.structure.success",false,elapsed));
        callback.setReturnValue(1);
    }
}

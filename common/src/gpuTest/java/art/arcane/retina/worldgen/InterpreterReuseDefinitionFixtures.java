package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.nbt.*;
import java.nio.file.*;

/** Test-only program variants; Python generation consumes only these saved definitions. */
public final class InterpreterReuseDefinitionFixtures {
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]),output=Path.of(args[1]);
        String dimension=args.length>2?args[2]:"minecraft:overworld";
        var document=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap());
        JsonObject original;
        try(var world=NativeWorldDefinition.open(input)) {
            original=JsonParser.parseString(world.diagnosticProfile(dimension)).getAsJsonObject();
        }
        original.getAsJsonObject("registry_program").addProperty("density_composition",false);
        Files.createDirectories(output);
        var variants=new JsonArray();
        write(document,original,output,dimension,"original",variants);
        write(document,interpolated(original,0,2.0),output,dimension,"climate",variants);
        var material=original;
        // Exercise every biome's material graph, including neighbors reached by generation halos.
        for(int biome=0;biome<original.getAsJsonArray("biomes").size();biome++)
            material=interpolated(material,3+biome,original.get("stone").getAsDouble()+1);
        write(document,material,output,dimension,"material",variants);
        var aquifer=original.getAsJsonObject("registry_program").getAsJsonObject("aquifer");
        if(aquifer!=null && aquifer.get("enabled").getAsBoolean())
            write(document,interpolated(original,aquifer.get("program").getAsInt()+3,null),output,dimension,"barrier",variants);
        var again=new JsonObject();again.addProperty("name","original-again");again.addProperty("definition","original/retina.dat");variants.add(again);
        var manifest=new JsonObject();manifest.addProperty("dimension",dimension);manifest.add("variants",variants);
        Files.writeString(output.resolve("variants.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest),StandardOpenOption.CREATE_NEW);
        System.out.println("QA_EVT {\"event\":\"interpreter_reuse_saved_variants\",\"status\":\"pass\",\"variants\":"+variants.size()+"}");
    }

    private static void write(CompoundTag source,JsonObject profile,Path output,String dimension,String name,JsonArray manifest) throws Exception {
        var saved=source.copy();
        // Keep every palette, template, numeric width and other dimension exactly as stored.
        saved.getCompoundOrEmpty("dimensions").getCompoundOrEmpty(dimension).getCompoundOrEmpty("profile")
                .put("registry_program",ProfileNbt.encode(profile.getAsJsonObject("registry_program")));
        WorldDefinitionFile.writeNew(output.resolve(name),saved);
        var item=new JsonObject();item.addProperty("name",name);item.addProperty("definition",name+"/retina.dat");manifest.add(item);
    }

    private static JsonObject interpolated(JsonObject profile,int program,Double constant) {
        var result=profile.deepCopy();var registry=result.getAsJsonObject("registry_program");
        var graph=registry.getAsJsonArray("programs").get(program).getAsJsonObject();
        var field=new JsonObject();var input=graph.deepCopy();
        var roots=new JsonArray();roots.add(graph.getAsJsonArray("roots").get(0).deepCopy());input.add("roots",roots);
        if(constant!=null) input=JsonParser.parseString("{\"nodes\":[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":["+constant+",0,0,0]}],\"roots\":[0]}").getAsJsonObject();
        field.add("input",input);field.add("cell",JsonParser.parseString("[7,5]"));
        var fields=registry.getAsJsonArray("interpolations");int fieldId=fields.size();fields.add(field);
        var nodes=graph.getAsJsonArray("nodes");int start=nodes.size();
        for(int axis=0;axis<3;axis++) nodes.add(JsonParser.parseString("{\"op\":29,\"a\":"+axis+",\"b\":0,\"c\":0,\"p\":[0,0,0,0]}"));
        nodes.add(JsonParser.parseString("{\"op\":28,\"a\":"+start+",\"b\":"+(start+1)+",\"c\":"+(start+2)+",\"p\":["+fieldId+",0,0,0]}"));
        graph.getAsJsonArray("roots").set(0,new JsonPrimitive(start+3));
        return result;
    }
}

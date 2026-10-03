package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;

/** Analytic surfaces exercise the real GPU; no CPU noise implementation is used. */
final class RegistryGpuProgramIntegrationChecks {
    static void checkNoiseSurfacePreservation(BiomeTerrainProfile profile) {
        var data=JsonParser.parseString(profile.json()).getAsJsonObject();
        data.addProperty("biome_scale",128);
        var program=data.getAsJsonObject("registry_program");
        for(var value:program.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonArray("nodes")) {
            var node=value.getAsJsonObject();int op=node.get("op").getAsInt();
            if(op==1 || op==2)program.getAsJsonArray("noises").get(op==1?node.getAsJsonArray("p").get(0).getAsInt():node.get("a").getAsInt())
                    .getAsJsonObject().addProperty("horizontal_scale",2);
        }
        // Isolate registered density cavities from intentional carver entrances,
        // tree soils, ores and structures. This is the reported vanilla world's seed.
        data.remove("structures");
        for(var value:data.getAsJsonArray("biomes")) {
            var biome=value.getAsJsonObject();
            for(String key:new String[]{"carvers","decorations","ores"})biome.add(key,new com.google.gson.JsonArray());
            biome.add("lakes",JsonParser.parseString("[0,0]"));
            biome.addProperty("cave_kind",0);
        }
        int id=NativeTerrain.instance().registerProfile(data.toString()), checked=0, undergroundAir=0;
        for(int[] pos:new int[][]{{-29,-32},{-28,-32},{-6,-13},{0,0}}) {
            var request=new TerrainRequest(-2570674330951730954L,pos[0],pos[1],-64,384,64,48,.008F,id);
            try(var chunk=NativeTerrain.instance().generate(request)) {
                var columns=chunk.columns();
                for(int c=0;c<256;c++) {
                    int expected=columns.materials()[c]&65535, y=columns.heights()[c]-1;
                    if(y<63 || expected==0)continue;
                    int actual=Short.toUnsignedInt(chunk.blocks().getAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT,(long)(y+64)*256+c));
                    if(actual!=expected)throw new AssertionError("Registered density stripped surface at "+(pos[0]*16+c%16)+","+y+","+(pos[1]*16+c/16)+": expected "+expected+", got "+actual);
                    checked++;
                }
                for(int y=6;y<104;y++)for(int c=0;c<256;c++)if(y-64<columns.heights()[c]-16
                        && chunk.blocks().getAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT,(long)y*256+c)==0)undergroundAir++;
            }
        }
        if(checked<256 || undergroundAir<100)throw new AssertionError("Surface preservation must exercise land and underground cavities: "+checked+"/"+undergroundAir);
        System.out.println("QA_EVT {\"event\":\"registered_density_preserves_surface\",\"status\":\"pass\",\"context\":{\"columns\":"+checked+",\"underground_air\":"+undergroundAir+"}}");
    }

    static void checkSurfaceInterpolation(BiomeTerrainProfile profile) {
        var data=JsonParser.parseString(profile.json()).getAsJsonObject();
        for(var biome:data.getAsJsonArray("biomes"))biome.getAsJsonObject().add("lakes",JsonParser.parseString("[0,0]"));
        var program=data.getAsJsonObject("registry_program");
        program.add("surface",JsonParser.parseString("[-64,8,0]"));
        program.getAsJsonArray("programs").set(1,JsonParser.parseString("""
                {"nodes":[
                  {"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":0,"b":0,"c":0,"p":[-256,256,-128,128]},
                  {"op":0,"a":0,"b":0,"c":0,"p":[64.35,0,0,0]},
                  {"op":4,"a":1,"b":2,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":1,"b":0,"c":0,"p":[-64,320,64,-320]},
                  {"op":4,"a":3,"b":4,"c":0,"p":[0,0,0,0]}
                ],"roots":[5,3]}
                """));
        var nativeTerrain=NativeTerrain.instance();int id=nativeTerrain.registerProfile(data.toString());
        for(int chunk:new int[]{-4,0,1,7,15}) {
            var columns=nativeTerrain.sampleColumns(new TerrainRequest(123456789L,chunk,0,-64,384,64,48,.008F,id));
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int expected=(int)Math.floor(65.35+(chunk*16+x)*.5);
                int actual=columns.heights()[z*16+x];
                if(actual!=expected)throw new AssertionError("Continuous GPU surface at x="+(chunk*16+x)+": expected "+expected+", got "+actual);
            }
        }
        System.out.println("QA_EVT {\"event\":\"continuous_gpu_surface_zero_crossing\",\"status\":\"pass\",\"context\":{\"columns\":1280}}");
    }
}

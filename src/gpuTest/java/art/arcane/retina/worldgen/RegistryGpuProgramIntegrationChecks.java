package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;

/** Analytic surfaces exercise the real GPU; no CPU noise implementation is used. */
final class RegistryGpuProgramIntegrationChecks {
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

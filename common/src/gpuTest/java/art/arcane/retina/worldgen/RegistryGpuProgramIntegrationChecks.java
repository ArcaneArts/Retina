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
        checkDensityInterpolation(profile);
    }

    private static void checkDensityInterpolation(BiomeTerrainProfile profile) {
        var data=JsonParser.parseString(profile.json()).getAsJsonObject();
        for(var biome:data.getAsJsonArray("biomes"))biome.getAsJsonObject().add("lakes",JsonParser.parseString("[0,0]"));
        var program=data.getAsJsonObject("registry_program");
        program.add("surface",JsonParser.parseString("[-64,8,0]"));
        // D(x,y,z) = (H(x,z)-y) * A(x,y,z). Both factors vary over
        // the lattice, so height blending cannot reproduce its zero crossing.
        var expression=JsonParser.parseString("""
                {"nodes":[
                  {"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":0,"b":0,"c":0,"p":[-256,256,-128,128]},
                  {"op":0,"a":0,"b":0,"c":0,"p":[64.35,0,0,0]},
                  {"op":4,"a":1,"b":2,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":2,"b":0,"c":0,"p":[-128,128,-32,32]},
                  {"op":4,"a":3,"b":4,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":1,"b":0,"c":0,"p":[-64,320,64,-320]},
                  {"op":4,"a":5,"b":6,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":0,"b":0,"c":0,"p":[0,4,1,9]},
                  {"op":3,"a":2,"b":0,"c":0,"p":[0,4,0,4]},
                  {"op":3,"a":1,"b":0,"c":0,"p":[0,256,0,1]},
                  {"op":4,"a":8,"b":9,"c":0,"p":[0,0,0,0]},
                  {"op":4,"a":11,"b":10,"c":0,"p":[0,0,0,0]},
                  {"op":6,"a":7,"b":12,"c":0,"p":[0,0,0,0]}
                ],"roots":[13,5]}
                """).getAsJsonObject();
        program.getAsJsonArray("programs").set(1,expression);
        var density=expression.deepCopy();density.add("roots",JsonParser.parseString("[13]"));
        program.getAsJsonArray("programs").set(2,density);
        int checked=0,differentFromHeightBlend=0;
        for(int[] cell:new int[][]{{4,8},{6,12},{8,4}}) {
            program.add("terrain_cell",JsonParser.parseString("["+cell[0]+","+cell[1]+"]"));
            int id=NativeTerrain.instance().registerProfile(data.toString());
            // Shared world-aligned cells must agree even for negative chunk
            // coordinates and sizes which do not divide the chunk width.
            try(var workers=java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var futures=new java.util.ArrayList<java.util.concurrent.Future<NativeTerrain.Columns>>();
                int[] chunks={-4,-1,0,1,7,15};
                int[] chunksZ={-1,0,0,0,1,15};
                for(int i=0;i<chunks.length;i++) {
                    int chunk=chunks[i], chunkZ=chunksZ[i];
                    int min=chunk%2==0?-64:-63, height=chunk%2==0?384:383;
                    futures.add(workers.submit(()->NativeTerrain.instance().sampleColumns(
                            new TerrainRequest(123456789L,chunk,chunkZ,min,height,64,48,.008F,id))));
                }
                for(int i=0;i<chunks.length;i++) {
                    NativeTerrain.Columns columns;
                    try {columns=futures.get(i).get();}catch(Exception e){throw new AssertionError("Concurrent density interpolation",e);}
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        int wx=chunks[i]*16+x, wz=chunksZ[i]*16+z;
                        int x0=Math.floorDiv(wx,cell[0])*cell[0], z0=Math.floorDiv(wz,cell[0])*cell[0];
                        int top=Math.ceilDiv(320,cell[1])*cell[1], expected=320;
                        double upper=fixtureDensityLayer(wx,wz,top,cell[0]);
                        for(int y=top-cell[1];y>=Math.floorDiv(-64,cell[1])*cell[1];y-=cell[1]) {
                            double lower=fixtureDensityLayer(wx,wz,y,cell[0]);
                            if(lower>0) {expected=(int)Math.floor(y+cell[1]*lower/(lower-upper)+1);break;}
                            upper=lower;
                        }
                        int actual=columns.heights()[z*16+x];
                        if(actual!=expected)throw new AssertionError("Density-first surface x/z="+wx+"/"+wz+", cell="+cell[0]+"x"+cell[1]+": expected "+expected+", got "+actual);
                        double tx=(double)(wx-x0)/cell[0],tz=(double)(wz-z0)/cell[0];
                        int heightBlend=(int)Math.floor((1-tx)*(1-tz)*fixtureHeight(x0,z0)+tx*(1-tz)*fixtureHeight(x0+cell[0],z0)+(1-tx)*tz*fixtureHeight(x0,z0+cell[0])+tx*tz*fixtureHeight(x0+cell[0],z0+cell[0])+1);
                        if(heightBlend!=expected)differentFromHeightBlend++;
                        checked++;
                    }
                }
            }
        }
        if(differentFromHeightBlend<32)throw new AssertionError("Fixture must distinguish density interpolation from height blending");
        System.out.println("QA_EVT {\"event\":\"trilinear_gpu_density_surface\",\"status\":\"pass\",\"context\":{\"columns\":"+checked+",\"different_from_height_blend\":"+differentFromHeightBlend+"}}");
        // A positive grid node above the world ceiling must not make the whole
        // column solid. This two-interval field is air inside the upper partial cell.
        var capped=JsonParser.parseString("""
                {"nodes":[
                  {"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]},
                  {"op":3,"a":1,"b":0,"c":0,"p":[-64,320,-383.25,0.75]},
                  {"op":3,"a":1,"b":0,"c":0,"p":[-64,320,136.35,-247.65]},
                  {"op":9,"a":1,"b":2,"c":0,"p":[0,0,0,0]}
                ],"roots":[3,0]}
                """).getAsJsonObject();
        program.getAsJsonArray("programs").set(1,capped);
        var cappedDensity=capped.deepCopy();cappedDensity.add("roots",JsonParser.parseString("[3]"));
        program.getAsJsonArray("programs").set(2,cappedDensity);
        program.add("terrain_cell",JsonParser.parseString("[6,12]"));
        int cappedId=NativeTerrain.instance().registerProfile(data.toString());
        var cappedColumns=NativeTerrain.instance().sampleColumns(new TerrainRequest(123456789L,-1,0,-64,383,64,48,.008F,cappedId));
        for(int h:cappedColumns.heights())if(h!=73)throw new AssertionError("Partial density cell above world ceiling: expected 73, got "+h);
        System.out.println("QA_EVT {\"event\":\"gpu_density_partial_ceiling\",\"status\":\"pass\",\"context\":{\"columns\":256}}");
    }

    private static double fixtureHeight(int x,int z) {
        return 64.35+Math.clamp(x*.5,-128,128)+Math.clamp(z*.25,-32,32);
    }

    private static double fixtureDensityLayer(int x,int z,int y,int step) {
        int x0=Math.floorDiv(x,step)*step,z0=Math.floorDiv(z,step)*step;
        double tx=(double)(x-x0)/step,tz=(double)(z-z0)/step,value=0;
        for(int dz=0;dz<=1;dz++)for(int dx=0;dx<=1;dx++) {
            int nx=x0+dx*step,nz=z0+dz*step;
            double amplitude=1+8*Math.clamp(nx/4.0,0,1)+4*Math.clamp(nz/4.0,0,1)+Math.clamp(y/256.0,0,1);
            value+=(dx==0?1-tx:tx)*(dz==0?1-tz:tz)*(fixtureHeight(nx,nz)-y)*amplitude;
        }
        return value;
    }
}

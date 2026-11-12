package art.arcane.retina.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Lowers registry expressions to Retina's shared CPU and GPU program representation. */
public abstract class DensityProgramCompiler {
    public final JsonArray noises = new JsonArray(), programs = new JsonArray(), points = new JsonArray();
    public final JsonArray interpolations = new JsonArray();
    private final Map<String,Integer> interpolationIds = new HashMap<>();
    private final Set<String> resolvingInterpolations = new HashSet<>();
    private final Map<String,Integer> noiseIds = new LinkedHashMap<>();
    public final Set<String> approximations = new TreeSet<>();

    protected abstract JsonElement resolve(JsonElement value);

    protected abstract JsonObject noiseParameters(JsonElement value);

    protected JsonObject normalizeDensity(JsonObject value) {
        return value;
    }

    protected abstract int lowerOtherDensity(Program program, JsonObject value, String kind);

    public final JsonArray terrainCell(JsonElement value, Set<String> visited) {
        if(value==null || !visited.add(value.toString()))return null;
        value=resolve(value);
        if(!value.isJsonObject())return null;
        var o=normalizeDensity(value.getAsJsonObject());
        if(o.has("type") && type(o).equals("interpolated")) {
            var sizes=new JsonArray();sizes.add((int)number(o,"cell_size_xz",4));sizes.add((int)number(o,"cell_size_y",8));return sizes;
        }
        for(String key:List.of("input","left","right","alpha","first","second","when_in_range","when_out_of_range")) {
            var sizes=terrainCell(o.get(key),visited);if(sizes!=null)return sizes;
        }
        return null;
    }
    public final JsonElement unwrap(JsonElement value) {
        for(int i=0;i<128;i++) {
            value=resolve(value);
            if(!value.isJsonObject())return value;
            var o=normalizeDensity(value.getAsJsonObject());
            if(!Set.of("cache","interpolated","blend_density").contains(type(o)))return value;
            value=o.get("input");
        } throw new IllegalArgumentException("Recursive surface density function");
    }
    public final int noise(JsonElement value) {
        String key=value.toString(); var existing=noiseIds.get(key); if(existing!=null)return existing;
        var o=noiseParameters(value);var n=new JsonObject();
        n.addProperty("frequency",Math.scalb(1.0,o.get("base_octave").getAsInt()));
        n.addProperty("amplitude",number(o,"base_amplitude",1));
        n.addProperty("salt",value.isJsonPrimitive()?value.getAsString().hashCode():key.hashCode());
        int count=(int)number(o,"octave_count",1);
        var modifiers=new JsonArray();var supplied=o.getAsJsonArray("amplitude_modifiers");
        for(int i=0;i<count;i++)modifiers.add(supplied!=null && i<supplied.size()?supplied.get(i).getAsDouble():1);
        n.add("modifiers",modifiers);
        // Match NormalNoise's metadata normalization. The actual noise is still sampled only on the GPU.
        String normalization=o.has("normalize")?o.get("normalize").getAsString():"true";
        double base=normalization.equals("false")?1:Math.scalb(1.0,count-1)/(Math.scalb(1.0,count)-1);
        double amplitude=number(o,"base_amplitude",1), total=0, variance=0;
        int first=count,last=-1;
        for(int i=0;i<count;i++) {
            double weight=amplitude*base*Math.scalb(1.0,-i)*modifiers.get(i).getAsDouble();
            total+=Math.abs(weight);variance+=Math.pow(.2702247831245211*weight,2);
            if(weight!=0){first=Math.min(first,i);last=i;}
        }
        double gain=variance==0?0:total/3/(Math.sqrt(variance)*Math.sqrt(2));
        if(normalization.equals("legacy") && gain!=0)gain=amplitude*.5/3/(.1*(1+1.0/(last-first+1)));
        var coefficients=new JsonArray();
        for(int i=0;i<count;i++)coefficients.add(base*Math.scalb(1.0,-i)*modifiers.get(i).getAsDouble()*gain);
        n.add("coefficients",coefficients);n.addProperty("normalize",normalization);
        int id=noises.size();noises.add(n);noiseIds.put(key,id);return id;
    }
    public static String type(JsonObject o) { return o.get("type").getAsString().replace("minecraft:",""); }
    public static float number(JsonObject o,String key,float fallback) { return o.has(key)?o.get(key).getAsFloat():fallback; }
    public final void collectNoises(JsonObject program,Set<Integer> ids,Set<Integer> fields) {
        for(var value:program.getAsJsonArray("nodes")) {
            var node=value.getAsJsonObject();int op=node.get("op").getAsInt();
            if(op==1 || op==27)ids.add(node.getAsJsonArray("p").get(0).getAsInt());
            if(op==2)ids.add(node.get("a").getAsInt());
            if(op==28) {
                int field=node.getAsJsonArray("p").get(0).getAsInt();
                if(fields.add(field))collectNoises(interpolations.get(field).getAsJsonObject().getAsJsonObject("input"),ids,fields);
            }
        }
    }
    private int interpolation(JsonObject value) {
        // The child samples its own corner coordinates. Outer slices modify
        // the lookup point; slices inside the child retain their own scope.
        String key=value.toString();var existing=interpolationIds.get(key);if(existing!=null)return existing;
        if(!resolvingInterpolations.add(key))throw new IllegalArgumentException("Recursive interpolation field: "+key);
        try {
            var input=new Program();input.roots.add(input.density(value.get("input")));
            var field=new JsonObject();field.add("input",input.finish());
            var sizes=new JsonArray();sizes.add(value.get("cell_size_xz"));sizes.add(value.get("cell_size_y"));field.add("cell",sizes);
            int id=interpolations.size();interpolations.add(field);interpolationIds.put(key,id);return id;
        } finally {resolvingInterpolations.remove(key);}
    }
    public class Program {
        public final JsonArray nodes=new JsonArray(), roots=new JsonArray();
        private final Map<String,Integer> compiled=new HashMap<>(); private final Set<String> resolving=new HashSet<>();
        private Coordinates coordinates = new Coordinates(null, null, null);
        public Program() { constant(0); }
        public final int node(int op,int a,int b,int c,float... args) {
            var n=new JsonObject();n.addProperty("op",op);n.addProperty("a",a);n.addProperty("b",b);n.addProperty("c",c);
            var p=new JsonArray();for(int i=0;i<4;i++)p.add(i<args.length?Math.max(-1.0e9f,Math.min(1.0e9f,args[i])):0);n.add("p",p);
            String key=n.toString();var existing=compiled.get(key);if(existing!=null)return existing;
            // Graph length is independent of interpreter scratch: Rust reuses values
            // after their last read and sizes scratch from the actual live-register requirement.
            int index=nodes.size();nodes.add(n);compiled.put(key,index);return index;
        }
        public final int constant(float x) { return node(0,0,0,0,x); }
        public final int density(JsonElement value) {
            if(value==null)return constant(0);
            String key="df:"+(coordinates.sliced()?coordinates+":":"")+value;var existing=compiled.get(key);if(existing!=null)return existing;
            if(!resolving.add(key))throw new IllegalArgumentException("Recursive registry density function: "+key);
            int index;
            if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())index=constant(value.getAsFloat());
            else if(value.isJsonPrimitive()) index=density(resolve(value));
            else {
                var o=normalizeDensity(value.getAsJsonObject());String kind=type(o);
                index=switch(kind) {
                    case "constant" -> constant(o.get("value").getAsFloat());
                    case "cache","blend_density" -> density(o.get("input"));
                    case "interpolated" -> node(28,coordinate(0),coordinate(1),coordinate(2),interpolation(o));
                    case "slice" -> slice(o);
                    case "blend_alpha" -> constant(1);
                    case "blend_offset","beardifier" -> constant(0);
                    case "noise" -> noise(o);
                    case "shift","shift_a","shift_b" -> shift(o,kind);
                    case "gradient" -> gradient(o);
                    case "add","sub","mul","div","min","max","pow" -> node(4+List.of("add","sub","mul","div","min","max","pow").indexOf(kind),density(o.get("left")),density(o.get("right")),0);
                    case "abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign" -> node(11+List.of("abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign").indexOf(kind),density(o.get("input")),0,0);
                    case "clamp" -> node(22,density(o.get("input")),0,0,number(o,"min",0),number(o,"max",1));
                    case "range_choice" -> node(23,density(o.get("input")),density(o.get("when_in_range")),density(o.get("when_out_of_range")),number(o,"min_inclusive",0),number(o,"max_exclusive",1));
                    case "lerp" -> node(24,density(o.get("alpha")),density(o.get("first")),density(o.get("second")));
                    case "spline" -> spline(o.get("spline"));
                    case "old_blended_noise" -> {approximations.add("density:old_blended_noise");yield coordinates.sliced()
                            ?node(31,coordinate(0),coordinate(1),coordinate(2),number(o,"xz_scale",.25f),number(o,"y_scale",.125f),number(o,"xz_factor",80),number(o,"y_factor",160))
                            :node(26,0,0,0,number(o,"xz_scale",.25f),number(o,"y_scale",.125f),number(o,"xz_factor",80),number(o,"y_factor",160));}
                    case "interval_select" -> interval(o);
                    case "scaled_absolute_noise" -> scaledAbsoluteNoise(o);
                    default -> lowerOtherDensity(this, o, kind);
                };
            }
            resolving.remove(key);compiled.put(key,index);return index;
        }
        private int slice(JsonObject o) {
            var previous=coordinates;
            coordinates=coordinates.with(axis(o),o.get("coordinate").getAsInt());
            try {return density(o.get("input"));}finally {coordinates=previous;}
        }
        private int coordinate(int axis) {
            Integer fixed=coordinates.at(axis);
            return fixed==null?node(29,axis,0,0):constant(fixed);
        }
        private int scaledCoordinate(int axis,float scale) {
            if(scale==0)return 0;
            int value=coordinate(axis);
            return scale==1?value:node(6,value,constant(scale),0);
        }
        private int noise(JsonObject o) {
            int x=density(o.get("shift_x")),y=density(o.get("shift_y")),z=density(o.get("shift_z"));
            int id=DensityProgramCompiler.this.noise(o.get("noise"));
            float xz=number(o,"xz_scale",1),ys=number(o,"y_scale",1);
            if(!coordinates.sliced())return node(1,x,y,z,id,xz,ys,0);
            return node(27,node(4,scaledCoordinate(0,xz),x,0),node(4,scaledCoordinate(1,ys),y,0),node(4,scaledCoordinate(2,xz),z,0),id,1);
        }
        private int scaledAbsoluteNoise(JsonObject expression) {
            var thresholds = expression.getAsJsonArray("thresholds");
            var scales = expression.getAsJsonArray("scales");
            int input = density(expression.get("input"));
            int scale = constant(scales.get(scales.size() - 1).getAsFloat());
            for (int i = thresholds.size() - 1; i >= 0; i--) {
                scale = node(23, input, constant(scales.get(i).getAsFloat()), scale,
                        -Float.MAX_VALUE, thresholds.get(i).getAsFloat());
            }
            int inverse = node(17, scale, 0, 0);
            int sample = node(27, node(6, coordinate(0), inverse, 0),
                    node(6, coordinate(1), inverse, 0), node(6, coordinate(2), inverse, 0),
                    DensityProgramCompiler.this.noise(expression.get("noise")), 1);
            return node(6, scale, node(11, sample, 0, 0), 0);
        }

        private int shift(JsonObject o,String kind) {
            int id=DensityProgramCompiler.this.noise(o.get("noise"));
            if(!coordinates.sliced())return node(2,id,kind.equals("shift_b")?2:kind.equals("shift_a")?1:0,0);
            int x=scaledCoordinate(kind.equals("shift_b")?2:0,.25F);
            int y=kind.equals("shift_a")?0:scaledCoordinate(kind.equals("shift_b")?0:1,.25F);
            int z=kind.equals("shift_b")?0:scaledCoordinate(2,.25F);
            return node(27,x,y,z,id,4);
        }
        private int gradient(JsonObject o) {
            int axis=axis(o);
            int tiling=o.has("tiling")?List.of("clamp_to_edge","repeat","mirrored_repeat").indexOf(o.get("tiling").getAsString()):0;
            return node(coordinates.at(axis)==null?3:30,coordinates.at(axis)==null?axis:coordinate(axis),tiling,0,
                    number(o,"from_coordinate",0),number(o,"to_coordinate",1),number(o,"from_value",0),number(o,"to_value",1));
        }
        private int axis(JsonObject o) {return List.of("x","y","z").indexOf(o.get("axis").getAsString());}
        private int interval(JsonObject o) {
            var functions=o.getAsJsonArray("functions");var thresholds=o.getAsJsonArray("thresholds");
            int input=density(o.get("input")),result=density(functions.get(functions.size()-1));
            for(int i=thresholds.size()-1;i>=0;i--)result=node(23,input,density(functions.get(i)),result,-Float.MAX_VALUE,thresholds.get(i).getAsFloat());
            return result;
        }
        private int spline(JsonElement value) {
            if(value.isJsonPrimitive())return constant(value.getAsFloat());
            var o=value.getAsJsonObject();int coordinate=density(o.get("coordinate"));
            var data=new ArrayList<JsonArray>();
            for(var v:o.getAsJsonArray("points")) {
                var p=v.getAsJsonObject();int result=spline(p.get("value"));
                var record=new JsonArray();record.add(p.get("location"));record.add(p.get("derivative"));record.add(result);record.add(0);data.add(record);
            }
            int start=points.size();data.forEach(points::add);
            return node(25,coordinate,start,data.size());
        }
        public final boolean isConstant(int node) {return nodes.get(node).getAsJsonObject().get("op").getAsInt()==0;}
        public final float constantValue(int node) {return nodes.get(node).getAsJsonObject().getAsJsonArray("p").get(0).getAsFloat();}
        public final JsonObject finish() {var o=new JsonObject();o.add("nodes",nodes);o.add("roots",roots);return o;}
    }
    /** Scoped values follow SliceFunction: an inner slice of the same axis wins. */
    private record Coordinates(Integer x,Integer y,Integer z) {
        Integer at(int axis) {return switch(axis){case 0->x;case 1->y;case 2->z;default->throw new IllegalArgumentException("Invalid density axis "+axis);};}
        boolean sliced() {return x!=null || y!=null || z!=null;}
        Coordinates with(int axis,int value) {return switch(axis){case 0->new Coordinates(value,y,z);case 1->new Coordinates(x,value,z);case 2->new Coordinates(x,y,value);default->throw new IllegalArgumentException("Invalid density axis "+axis);};}
    }
}

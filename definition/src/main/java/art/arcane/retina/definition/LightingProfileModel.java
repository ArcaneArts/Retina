package art.arcane.retina.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Builds the shared lighting tables from a game's resolved directional occlusion shapes. */
public final class LightingProfileModel<S, B> {
    private final List<S> shapes = new ArrayList<>();
    private final LinkedHashMap<List<B>, Integer> ids = new LinkedHashMap<>();
    private final JsonArray states = new JsonArray();
    private final Function<S, List<B>> boxes;

    public LightingProfileModel(Function<S, List<B>> boxes) {
        this.boxes = boxes;
    }

    public void addState(int dampening, int emission, IntFunction<S> face) {
        var row = new JsonArray();
        row.add(dampening | emission << 4);
        for (int direction = 0; direction < 6; direction++) {
            S shape = face.apply(direction);
            int id = ids.computeIfAbsent(List.copyOf(boxes.apply(shape)), ignored -> {
                shapes.add(shape);
                return shapes.size() - 1;
            });
            row.add(id);
        }
        row.add(0); // Eight u32s match the storage buffer.
        states.add(row);
    }

    public JsonObject build(boolean sky, BiPredicate<S, S> occludes) {
        int stride = (shapes.size() + 31) / 32;
        var blocked = new JsonArray();
        for (S from : shapes) {
            for (int word = 0; word < stride; word++) {
                int bits = 0;
                for (int bit = 0; bit < 32 && word * 32 + bit < shapes.size(); bit++) {
                    if (occludes.test(from, shapes.get(word * 32 + bit))) bits |= 1 << bit;
                }
                blocked.add(Integer.toUnsignedLong(bits));
            }
        }
        var result = new JsonObject();
        result.addProperty("sky", sky);
        result.addProperty("faces", shapes.size());
        result.add("states", states);
        result.add("blocked", blocked);
        return result;
    }
}

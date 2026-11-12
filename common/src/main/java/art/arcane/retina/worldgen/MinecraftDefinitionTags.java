package art.arcane.retina.worldgen;

import art.arcane.retina.definition.DefinitionTags;
import net.minecraft.nbt.*;

/** 26.3 NBT adapter for the shared definition schema. */
enum MinecraftDefinitionTags implements DefinitionTags<Tag, CompoundTag, ListTag> {
    INSTANCE;

    @Override public CompoundTag compound() { return new CompoundTag(); }
    @Override public CompoundTag copy(CompoundTag value) { return value.copy(); }
    @Override public void put(CompoundTag compound, String key, Tag value) { compound.put(key, value); }
    @Override public ListTag list() { return new ListTag(); }
    @Override public void add(ListTag list, Tag value) { list.add(value); }
    @Override public Tag byteTag(byte value) { return ByteTag.valueOf(value); }
    @Override public Tag intTag(int value) { return IntTag.valueOf(value); }
    @Override public Tag longTag(long value) { return LongTag.valueOf(value); }
    // DoubleTag.valueOf normalizes -0.0. The saved profile keeps its exact bits.
    @SuppressWarnings("removal")
    @Override public Tag doubleTag(double value) { return new DoubleTag(value); }
    @Override public Tag string(String value) { return StringTag.valueOf(value); }
    @Override public Tag bytes(byte[] values) { return new ByteArrayTag(values); }
    @Override public Tag ints(int[] values) { return new IntArrayTag(values); }
    @Override public Tag longs(long[] values) { return new LongArrayTag(values); }
}

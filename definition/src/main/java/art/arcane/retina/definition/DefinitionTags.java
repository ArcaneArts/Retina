package art.arcane.retina.definition;

/** Writes the structural definition into the NBT implementation supplied by the host. */
public interface DefinitionTags<T, C extends T, L extends T> {
    C compound();
    C copy(C compound);
    void put(C compound, String key, T value);
    L list();
    void add(L list, T value);
    T byteTag(byte value);
    T intTag(int value);
    T longTag(long value);
    T doubleTag(double value);
    T string(String value);
    T bytes(byte[] values);
    T ints(int[] values);
    T longs(long[] values);
}

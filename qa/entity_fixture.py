"""Recreate the Minecraft 26.3 template used by the loader entity QA pack."""
from pathlib import Path
import gzip
import struct


def text(value):
    data = value.encode()
    return struct.pack(">H", len(data)) + data


def payload(kind, value):
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 6:
        return struct.pack(">d", value)
    if kind == 8:
        return text(value)
    if kind == 9:
        child, values = value
        return bytes([child]) + struct.pack(">i", len(values)) + b"".join(payload(child, v) for v in values)
    if kind == 10:
        return b"".join(bytes([k]) + text(name) + payload(k, v) for name, (k, v) in value.items()) + b"\0"
    if kind == 11:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value)
    raise ValueError(kind)


blocks = []
for y in range(4):
    for z in range(8):
        for x in range(8):
            blocks.append({"pos": (9, (3, [x, y, z])), "state": (3, int(z == 4 and 1 <= x <= 5))})
entities = []
for index, name in enumerate(["item_frame", "glow_item_frame", "painting"]):
    x = index + 2
    tag = {"id": (8, "minecraft:" + name), "block_pos": (11, [1234, 99, -5678])}
    if name == "painting":
        tag.update({"facing": (3, 2), "variant": (8, "minecraft:kebab")})
    else:
        tag.update({"Facing": (3, 2), "Item": (10, {"id": (8, "minecraft:diamond"), "count": (3, 1)})})
    entities.append({"pos": (9, (6, [x + .5, 1.5, 3.96875])), "blockPos": (9, (3, [x, 1, 3])), "nbt": (10, tag)})
template = {
    "DataVersion": (3, 5023), "size": (9, (3, [8, 4, 8])),
    # StructureTemplate's current NbtUtils palette codec uses compound id fields.
    "palette": (9, (10, [{"id": (8, "minecraft:air")}, {"id": (8, "minecraft:stone")}])),
    "blocks": (9, (10, blocks)), "entities": (9, (10, entities)),
}
output = Path(__file__).parent / "datapacks/entities/data/retina/structure/qa_entities.nbt"
output.write_bytes(gzip.compress(b"\x0a\x00\x00" + payload(10, template), mtime=0))

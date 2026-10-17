#!/usr/bin/env python3
"""Prepare an isolated real Bend server world for template entity/load QA.

The generated datapack uses a simple density plane and no plains features so the
test measures entity integration without waiting for unrelated forests/caves.
Terrain, structures, lighting, NBT and compressed MCA still run in production Bend.
No user worlds or installed game resources are modified.
"""
import argparse
import json
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from test_bend_structure_candidates import candidate


def prepare(destination, minecraft_jar, backend="bend"):
    destination.mkdir(parents=True, exist_ok=False)
    pack = destination / "world/datapacks/bend-entities"
    shutil.copytree(ROOT / "qa/datapacks/entities", pack)

    def write(name, value):
        path = pack / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + "\n")

    with zipfile.ZipFile(minecraft_jar) as source:
        settings = json.loads(source.read("data/minecraft/worldgen/noise_settings/overworld.json"))
        settings["noise_router"] = {key: 0.0 for key in settings["noise_router"]}
        settings["noise_router"]["chunk_surface_level"] = 64.0
        settings["noise_router"]["continents"] = 0.5
        settings["noise_router"]["final_density"] = {
            "type": "minecraft:gradient", "axis": "y", "from_coordinate": 0, "to_coordinate": 128,
            "from_value": 1.0, "to_value": -1.0,
        }
        settings["aquifers"] = {key: 0.0 for key in settings["aquifers"]}
        settings["debug_functions"] = []
        settings["disable_mob_generation"] = True
        write("data/minecraft/worldgen/noise_settings/overworld.json", settings)
        biome = json.loads(source.read("data/minecraft/worldgen/biome/plains.json"))
        biome["features"] = [[] for _ in biome["features"]]
        biome["carvers"] = []
        biome["attributes"].pop("minecraft:gameplay/natural_mob_spawns", None)
        write("data/minecraft/worldgen/biome/plains.json", biome)
        for name in source.namelist():
            if name.startswith("data/minecraft/worldgen/structure_set/") and name.endswith(".json"):
                entry = json.loads(source.read(name))
                if entry["placement"]["type"] == "minecraft:random_spread":
                    entry["placement"]["frequency"] = 0.0
                    write(name, entry)
    preset = json.loads((ROOT / "common/src/main/resources/data/retina/worldgen/world_preset/bend.json").read_text())
    preset_name = "bend" if backend == "bend" else "gpu"
    preset["dimensions"]["minecraft:overworld"]["generator"]["mode"] = "bend" if backend == "bend" else "mca"
    biomes = preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]
    biomes["biomes"] = ["minecraft:plains"]
    biomes["registry_source"] = {"type": "minecraft:fixed", "biome": "minecraft:plains"}
    write(f"data/retina/worldgen/world_preset/{preset_name}.json", preset)
    # Keep the fixture away from MCA boundaries so forced entity tickets do not
    # cause unrelated adjacent-region generation. Selection is registry-derived.
    placement = {"type": "minecraft:random_spread", "spacing": 32, "separation": 8, "salt": 0}
    while not all(10 <= v <= 20 for v in candidate(123456789, 0, 0, placement)):
        placement["salt"] += 1
    write("data/retina/worldgen/structure_set/qa_entities.json", {
        "structures": [{"structure": "retina:qa_entities", "weight": 1}], "placement": placement,
    })
    (destination / "server.properties").write_text("\n".join([
        "level-name=world", f"level-type=retina:{preset_name}", "level-seed=123456789",
        "server-ip=127.0.0.1", "server-port=25579", "online-mode=false",
        "view-distance=2", "simulation-distance=2", "max-tick-time=-1",
        "pause-when-empty-seconds=0", "spawn-protection=0", "enable-query=false",
        "enable-rcon=false", "motd=Retina isolated Bend entity QA", "",
    ]))
    # This is the repository's automated developer QA server, never a user install.
    (destination / "eula.txt").write_text("eula=true\n")
    print(json.dumps({"directory": str(destination), "start_chunk": candidate(123456789, 0, 0, placement), "salt": placement["salt"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("minecraft_jar", type=Path)
    parser.add_argument("--backend", choices=["bend", "rust"], default="bend", help="Use Rust for the shared QA regression control")
    args = parser.parse_args()
    prepare(args.destination.resolve(), args.minecraft_jar, args.backend)

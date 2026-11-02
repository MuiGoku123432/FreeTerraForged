#!/usr/bin/env python3
"""Read-only inventory of installed structure jars; emits facts, not runtime verdicts.

Usage: python3 inspect_artifacts.py /path/to/mods > artifact-inventory.json
Uses only the Python standard library. Does not extract or redistribute templates.
"""
import collections
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


class NbtReader:
    def __init__(self, data):
        self.stream = io.BytesIO(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.stream.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.stream.read(self.number("H")).decode("utf-8", errors="replace")

    def payload(self, tag):
        if tag in (1, 2, 3, 4, 5, 6):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[tag])
        if tag == 7:
            return self.stream.read(self.number("i"))
        if tag == 8:
            return self.string()
        if tag == 9:
            child, length = self.number("B"), self.number("i")
            return [self.payload(child) for _ in range(length)]
        if tag == 10:
            result = {}
            while (child := self.number("B")) != 0:
                name = self.string()
                result[name] = self.payload(child)
            return result
        if tag in (11, 12):
            return [self.number("i" if tag == 11 else "q") for _ in range(self.number("i"))]
        raise ValueError(f"Unsupported NBT tag {tag}")

    def root(self):
        tag = self.number("B")
        self.string()
        return self.payload(tag)


def inspect(path):
    result = {"artifact": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    structures, pools, templates = [], [], []
    with zipfile.ZipFile(path) as jar:
        for name in sorted(jar.namelist()):
            if "/worldgen/structure/" in name and name.endswith(".json"):
                data = json.loads(jar.read(name))
                if "type" in data:
                    structures.append({"path": name, **data})
            elif "/worldgen/template_pool/" in name and name.endswith(".json"):
                data = json.loads(jar.read(name))
                pools.append({"path": name, **data})
            elif "/structure/" in name and name.endswith(".nbt"):
                data = NbtReader(jar.read(name)).root()
                palette = data.get("palette", (data.get("palettes") or [[]])[0])
                names = [entry["Name"] for entry in palette]
                counts = collections.Counter(names[b["state"]] for b in data.get("blocks", []))
                templates.append({
                    "path": name, "size": data.get("size"),
                    "stored_blocks": len(data.get("blocks", [])),
                    "explicit_air_blocks": sum(counts[n] for n in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air")),
                    "structure_void_blocks": counts["minecraft:structure_void"],
                    "block_entities": sum("nbt" in b for b in data.get("blocks", [])),
                    "entities": len(data.get("entities", [])),
                    "palette_count": len(data.get("palettes", [])) or 1,
                })
        result["class_count"] = sum(n.endswith(".class") for n in jar.namelist())
    result.update(structures=structures, pools=pools, templates=templates)
    result["summary"] = {
        "structures": len(structures), "pools": len(pools), "templates": len(templates),
        "terrain_adaptation": dict(collections.Counter(s.get("terrain_adaptation", "none") for s in structures)),
        "heightmap": dict(collections.Counter(s.get("project_start_to_heightmap", "none") for s in structures)),
        "projection": dict(collections.Counter(e["element"].get("projection", "unspecified") for p in pools for e in p.get("elements", []))),
    }
    return result


if __name__ == "__main__":
    root = Path(sys.argv[1])
    selected = sorted(p for p in root.iterdir() if p.suffix == ".jar" and any(
        part in p.name.lower() for part in ("giant_natural_additions", "create-structures-arise", "create_structures_arise")
    ))
    if not selected:
        raise SystemExit("No target structure jars found")
    print(json.dumps({"method": "static installed-jar inventory; no world generation", "artifacts": [inspect(p) for p in selected]}, indent=2))

"""
Конвертер Sponge .schem (WorldEdit) → vanilla .nbt (структурный блок).

Использование:
    python tools/schem_to_nbt.py <input.schem> <output.nbt>

Зачем: Minecraft напрямую читает только vanilla .nbt структуры через
StructureTemplateManager. WorldEdit-формат внутри мода не подружить без
сторонних библиотек, поэтому конвертим заранее и кладём результат в
src/main/resources/data/pepel/structures/.
"""
import sys
import os
import nbtlib
from nbtlib import Compound, List, Int, String, Double


def decode_varints(data):
    """Sponge BlockData — последовательность varint'ов в палитру."""
    out = []
    i = 0
    while i < len(data):
        val = 0
        shift = 0
        while True:
            b = data[i]
            i += 1
            val |= (b & 0x7F) << shift
            if (b & 0x80) == 0:
                break
            shift += 7
        out.append(val)
    return out


def parse_block_string(s):
    """'minecraft:foo[a=b,c=d]' → ('minecraft:foo', {'a': 'b', 'c': 'd'})"""
    if "[" in s:
        name, props_part = s.split("[", 1)
        props_part = props_part.rstrip("]")
        props = {}
        for kv in props_part.split(","):
            k, v = kv.split("=", 1)
            props[k] = v
        return name, props
    return s, None


def convert(in_path, out_path):
    src = nbtlib.load(in_path)
    W = int(src["Width"])
    H = int(src["Height"])
    L = int(src["Length"])
    N = W * H * L

    indices = decode_varints(bytes(src["BlockData"]))
    if len(indices) != N:
        raise ValueError(f"BlockData length mismatch: {len(indices)} vs expected {N}")

    # palette: name → id, инвертим в id → name
    pal_by_id = {int(v): str(k) for k, v in src["Palette"].items()}

    vanilla_palette = []
    pid_to_idx = {}
    for pid in sorted(pal_by_id):
        name, props = parse_block_string(pal_by_id[pid])
        entry = Compound({"Name": String(name)})
        if props:
            entry["Properties"] = Compound({k: String(v) for k, v in props.items()})
        pid_to_idx[pid] = len(vanilla_palette)
        vanilla_palette.append(entry)

    # tile entities → map позиция → nbt
    te_map = {}
    for te in src.get("BlockEntities", []):
        pos = list(te["Pos"])
        te_data = Compound()
        for k, v in te.items():
            if k == "Pos":
                continue
            te_data["id" if k == "Id" else k] = v
        te_map[(int(pos[0]), int(pos[1]), int(pos[2]))] = te_data

    # blocks list — порядок Sponge schem v2: y, z, x
    blocks_list = []
    idx = 0
    for y in range(H):
        for z in range(L):
            for x in range(W):
                pid = indices[idx]
                idx += 1
                entry = Compound({
                    "pos": List[Int]([Int(x), Int(y), Int(z)]),
                    "state": Int(pid_to_idx[pid]),
                })
                if (x, y, z) in te_map:
                    entry["nbt"] = te_map[(x, y, z)]
                blocks_list.append(entry)

    # Sponge schem v2 хранит entity Pos как МИРОВЫЕ координаты (откуда //copy брал).
    # Vanilla structure NBT ждёт schema-local. Вычитаем Offset чтобы привести.
    offset = [int(p) for p in src.get("Offset", [0, 0, 0])]
    entities_list = []
    for e in src.get("Entities", []):
        world_pos = [float(p) for p in e["Pos"]]
        local_pos = [world_pos[i] - offset[i] for i in range(3)]
        block_pos = [int(p) for p in local_pos]
        ent_data = Compound()
        for k, v in e.items():
            # UUID убираем чтобы vanilla сгенерировал свежий при placeInWorld
            # (иначе при многократной установке будут entity-дубли с одним UUID).
            # Pos в nbt тоже не нужен — vanilla перепишет от template_origin + pos.
            if k in ("UUID", "Pos"):
                continue
            # Sponge хранит "Id" (с большой), vanilla EntityType.by() читает "id" (с маленькой).
            # Без rename'а entity молча пропускается при placeInWorld — приходят пустые.
            if k == "Id":
                ent_data["id"] = v
                continue
            ent_data[k] = v
        # Entity pos внутри nbt тоже нужно поставить schema-local
        # (некоторые ванильные коды читают Pos из nbt при загрузке).
        ent_data["Pos"] = List[Double]([Double(p) for p in local_pos])
        entities_list.append(Compound({
            "pos": List[Double]([Double(p) for p in local_pos]),
            "blockPos": List[Int]([Int(b) for b in block_pos]),
            "nbt": ent_data,
        }))

    out = nbtlib.File({
        "size": List[Int]([Int(W), Int(H), Int(L)]),
        "palette": List[Compound](vanilla_palette),
        "blocks": List[Compound](blocks_list),
        "entities": List[Compound](entities_list),
        "DataVersion": Int(3465),  # 1.20.1
    })

    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    out.save(out_path, gzipped=True)

    print(f"OK  {in_path} -> {out_path}")
    print(f"    size {W}x{H}x{L}={N}, palette {len(vanilla_palette)}, tile {len(te_map)}, entities {len(entities_list)}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python tools/schem_to_nbt.py <input.schem> <output.nbt>")
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2])

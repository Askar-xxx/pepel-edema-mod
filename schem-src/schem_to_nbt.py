"""
Конвертация Sponge Schematic v2 (.schem) -> Minecraft Structure (.nbt) для 1.20.1.
Запускается gradle-таской convertSchemToNbt.

Требования: Python 3.8+, nbtlib (pip install nbtlib).
"""
import sys
from pathlib import Path

try:
    import nbtlib
    from nbtlib.tag import Compound, Double, Int, IntArray, List, String
except ImportError:
    sys.stderr.write("ERROR: nbtlib не установлен. Установи: pip install nbtlib\n")
    sys.exit(2)

DATA_VERSION_1_20_1 = 3465

# Ремап блоков при конвертации: исходное имя -> целевой blockstate (свойства в значении разрешены).
# Свойства исходного блока отбрасываются.
REMAP = {
    'minecraft:stone': 'minecraft:sandstone',
}

# Блоки, вокруг которых кладётся один внешний слой sand
# (на те 6-соседние позиции, которые в исходнике были air/water/служебным песком).
OUTER_SAND_AROUND = {'minecraft:stone'}


def decode_varint_array(data: bytes, expected: int):
    out = [0] * expected
    i = 0
    pos = 0
    while i < expected:
        value = 0
        shift = 0
        while True:
            b = data[pos]
            pos += 1
            value |= (b & 0x7F) << shift
            if not (b & 0x80):
                break
            shift += 7
        out[i] = value
        i += 1
    return out


def parse_blockstate(s: str):
    """'minecraft:water[level=4]' -> ('minecraft:water', {'level': '4'}) ; 'minecraft:air' -> ('minecraft:air', {})"""
    if '[' not in s:
        return s, {}
    name, rest = s.split('[', 1)
    rest = rest.rstrip(']')
    props = {}
    for pair in rest.split(','):
        if not pair:
            continue
        k, _, v = pair.partition('=')
        props[k.strip()] = v.strip()
    return name, props


def convert(src: Path, dst: Path, village_mode: bool = False):
    """
    village_mode=True выключает island-специфичные трансформации:
    - REMAP stone→sandstone, OUTER_SAND, water/sand stripping, pepel_pit detection.
    Деревенские schem'ы конвертируются как есть (water/sand сохраняются если есть).
    """
    schem = nbtlib.load(src, gzipped=True)

    W = int(schem['Width'])
    H = int(schem['Height'])
    L = int(schem['Length'])

    # palette: {blockstate_string: id}
    palette_map = {str(k): int(v) for k, v in schem['Palette'].items()}
    max_id = max(palette_map.values())
    # id -> blockstate string
    id_to_state = [''] * (max_id + 1)
    for k, v in palette_map.items():
        id_to_state[v] = k

    # Запоминаем исходные id блоков, вокруг которых нужен внешний слой sand —
    # ДО применения REMAP (после REMAP имя в id_to_state поменяется).
    outer_source_ids = set()
    if not village_mode:
        for old_id, state_str in enumerate(id_to_state):
            name, _ = parse_blockstate(state_str)
            if name in OUTER_SAND_AROUND:
                outer_source_ids.add(old_id)

    # Применяем REMAP: заменяем state-строку для id-ов исходных блоков.
    remap_count = {}
    if not village_mode:
        for old_id, state_str in enumerate(id_to_state):
            name, _ = parse_blockstate(state_str)
            if name in REMAP:
                id_to_state[old_id] = REMAP[name]
                remap_count[name] = remap_count.get(name, 0) + 1

    air_id = palette_map.get('minecraft:air')

    # Кольцо воды вокруг острова в .schem было бы поверх натурального океана — убираем.
    # Все водные/пузырьковые блоки пропускаем как будто air: в Structure NBT их нет,
    # и natural ocean заливает эти позиции при пасте. Остров сам по себе сухой.
    # Песок (sand/red_sand) тоже стрипаем — в схеме стоит служебный столб песка,
    # отмечающий центр WorldEdit-копии. Пляжи восстановит природный океан.
    skip_ids = set()
    if air_id is not None:
        skip_ids.add(air_id)
    if not village_mode:
        # island-mode: вырезаем воду (натуральный океан зальёт) и служебный песок WorldEdit-маркера.
        # village-mode: ничего лишнего, конвертируем как есть (только air игнорируется).
        for state_str, sid in palette_map.items():
            name = state_str.split('[', 1)[0]
            if name in ('minecraft:water', 'minecraft:flowing_water',
                        'minecraft:bubble_column', 'minecraft:kelp',
                        'minecraft:kelp_plant', 'minecraft:seagrass',
                        'minecraft:tall_seagrass',
                        'minecraft:sand', 'minecraft:red_sand'):
                skip_ids.add(sid)

    raw = bytes((b & 0xFF) for b in schem['BlockData'])
    ids = decode_varint_array(raw, W * H * L)
    strip_flat_base = village_mode and src.name == 'fallen_cart.schem'

    # Формат Sponge: индекс = x + z*W + y*W*L
    # Формат Structure: хранит blocks как список не-air блоков.
    # Палитру строим из тех state-ов, что реально встретились (кроме air/water при пропуске).
    # Параллельно собираем bbox не-skip блоков по X/Z — пригодится для ямы в центре острова.
    used_ids = set()
    min_x, max_x = W, -1
    min_z, max_z = L, -1
    for y in range(H):
        for z in range(L):
            base = y * W * L + z * W
            for x in range(W):
                bid = ids[base + x]
                if strip_flat_base and y == 0:
                    base_name = id_to_state[bid].split('[', 1)[0]
                    if base_name in ('minecraft:grass_block', 'minecraft:dirt'):
                        continue
                if bid in skip_ids:
                    continue
                used_ids.add(bid)
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if z < min_z:
                    min_z = z
                if z > max_z:
                    max_z = z

    # Heightmap по (x, z): максимальная y не-skip блока. Нужен для автодетекта ямы —
    # колонки, чей топ заметно ниже соседей. Строим параллельно со сбором used_ids.
    hm = [[-1] * L for _ in range(W)]
    for y in range(H):
        for z in range(L):
            base = y * W * L + z * W
            for x in range(W):
                bid = ids[base + x]
                if strip_flat_base and y == 0:
                    base_name = id_to_state[bid].split('[', 1)[0]
                    if base_name in ('minecraft:grass_block', 'minecraft:dirt'):
                        continue
                if bid in skip_ids:
                    continue
                if y > hm[x][z]:
                    hm[x][z] = y

    # Находим самую глубокую яму внутри bbox: argmax(avg_neighbors_top - top).
    # Требуем минимум 6 соседей с данными — иначе это кромка острова, не яма.
    # village-mode: ямы детектить незачем, ставим яму=None.
    best_pit = None
    best_depth = 0.0
    for x in range(min_x, max_x + 1):
        if village_mode: break
        for z in range(min_z, max_z + 1):
            y = hm[x][z]
            if y < 0:
                continue
            neigh = []
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx == 0 and dz == 0:
                        continue
                    nx, nz = x + dx, z + dz
                    if 0 <= nx < W and 0 <= nz < L and hm[nx][nz] >= 0:
                        neigh.append(hm[nx][nz])
            if len(neigh) >= 6:
                avg = sum(neigh) / len(neigh)
                depth = avg - y
                if depth > best_depth:
                    best_depth = depth
                    best_pit = (x, y + 1, z)  # y+1 — позиция над дном (куда встанут ноги)

    # Сортируем по исходному id для детерминированности.
    sorted_used = sorted(used_ids)
    old_to_new = {old: new for new, old in enumerate(sorted_used)}

    new_palette = []
    for old_id in sorted_used:
        state_str = id_to_state[old_id]
        name, props = parse_blockstate(state_str)
        entry = Compound({'Name': String(name)})
        if props:
            entry['Properties'] = Compound({k: String(v) for k, v in props.items()})
        new_palette.append(entry)

    # Дописываем sand в палитру — им будем покрывать внешний слой вокруг outer_source_ids.
    sand_palette_index = len(new_palette)
    new_palette.append(Compound({'Name': String('minecraft:sand')}))

    # Собираем позиции для внешнего слоя sand: соседи исходных outer-блоков,
    # которые в исходнике были air/water/служебным песком (skip_ids).
    sand_override = set()
    if outer_source_ids:
        neighbors = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
        for y in range(H):
            for z in range(L):
                base = y * W * L + z * W
                for x in range(W):
                    if ids[base + x] not in outer_source_ids:
                        continue
                    for dx, dy, dz in neighbors:
                        nx, ny, nz = x + dx, y + dy, z + dz
                        if 0 <= nx < W and 0 <= ny < H and 0 <= nz < L:
                            if ids[ny * W * L + nz * W + nx] in skip_ids:
                                sand_override.add((nx, ny, nz))

    # block_entities — Sponge v2 хранит в Pos (IntArray[3]), Id (String), плюс произвольные NBT-поля.
    be_by_pos = {}
    for be in schem.get('BlockEntities', []):
        px, py, pz = [int(c) for c in be['Pos']]
        extra = Compound()
        for k, v in be.items():
            if k == 'Pos':
                continue
            if k == 'Id':
                extra['id'] = v
                continue
            extra[k] = v
        be_by_pos[(px, py, pz)] = extra

    blocks = []
    skipped_water = 0
    sand_layer_added = 0
    for y in range(H):
        for z in range(L):
            base = y * W * L + z * W
            for x in range(W):
                bid = ids[base + x]
                if strip_flat_base and y == 0:
                    base_name = id_to_state[bid].split('[', 1)[0]
                    if base_name in ('minecraft:grass_block', 'minecraft:dirt'):
                        continue
                if (x, y, z) in sand_override:
                    # Эти позиции в исходнике были skip-блоком (air/water/служебный песок);
                    # кладём внешний слой sand вместо них.
                    blocks.append(Compound({
                        'state': Int(sand_palette_index),
                        'pos': List[Int]([Int(x), Int(y), Int(z)]),
                    }))
                    sand_layer_added += 1
                    continue
                if bid in skip_ids:
                    if bid != air_id:
                        skipped_water += 1
                    continue
                entry = Compound({
                    'state': Int(old_to_new[bid]),
                    'pos': List[Int]([Int(x), Int(y), Int(z)]),
                })
                be = be_by_pos.get((x, y, z))
                if be is not None:
                    entry['nbt'] = be
                blocks.append(entry)

    # Entities: Sponge v2 хранит в Entities (если в WorldEdit копировали с -e).
    # ВАЖНО: Sponge v2 хранит entity.Pos в АБСОЛЮТНЫХ мировых координатах на момент копии,
    # не относительно origin'а схемы. Чтобы получить relative-to-origin (что нужно для
    # Minecraft Structure NBT) — вычитаем schem.Offset (мировая координата блока (0,0,0)).
    # Поля entity: Id (String, mc id), Pos (List<Double>×3, абсолютные), плюс произвольные
    # NBT-поля сущности (Health, Age, CustomName и т.д.).
    # Minecraft Structure NBT хранит entities как: blockPos (Int×3, relative) +
    # pos (Double×3, relative) + nbt (Compound с id + остальное). placeInWorld() сам
    # переписывает Pos через transform(entity.pos)+templatePos → world.
    # UUID отбрасываем — иначе при множественной постановке schem'ы будут конфликты UUID.
    schem_offset = schem.get('Offset')
    ox = int(schem_offset[0]) if schem_offset is not None else 0
    oy = int(schem_offset[1]) if schem_offset is not None else 0
    oz = int(schem_offset[2]) if schem_offset is not None else 0
    out_entities = []
    entities_skipped = 0
    for ent in schem.get('Entities', []) or []:
        if 'Pos' not in ent or 'Id' not in ent:
            entities_skipped += 1
            continue
        pos = ent['Pos']
        if len(pos) < 3:
            entities_skipped += 1
            continue
        # absolute → relative
        px = float(pos[0]) - ox
        py = float(pos[1]) - oy
        pz = float(pos[2]) - oz
        eid = str(ent['Id'])
        nbt_data = Compound()
        for k, v in ent.items():
            # Pos оригинальный (абсолютный) выкидываем: его перезапишет Minecraft
            # при placeInWorld через transform(entity.pos)+templatePos.
            if k in ('Id', 'Pos', 'UUID', 'UUIDLeast', 'UUIDMost'):
                continue
            nbt_data[k] = v
        nbt_data['id'] = String(eid)
        # Pos в nbt тоже relative — на случай если EntityType.loadEntityRecursive
        # прочитает его до того как Minecraft перепишет.
        nbt_data['Pos'] = List[Double]([Double(px), Double(py), Double(pz)])
        out_entities.append(Compound({
            'blockPos': List[Int]([Int(int(px)), Int(int(py)), Int(int(pz))]),
            'pos':      List[Double]([Double(px), Double(py), Double(pz)]),
            'nbt':      nbt_data,
        }))

    root_data = {
        'DataVersion': Int(DATA_VERSION_1_20_1),
        'size': List[Int]([Int(W), Int(H), Int(L)]),
        'palette': List[Compound](new_palette),
        'blocks': List[Compound](blocks),
        'entities': List[Compound](out_entities),
    }
    if best_pit is not None:
        root_data['pepel_pit'] = Compound({
            'x': Int(best_pit[0]),
            'y': Int(best_pit[1]),
            'z': Int(best_pit[2]),
        })
    root = nbtlib.File(root_data, gzipped=True)

    dst.parent.mkdir(parents=True, exist_ok=True)
    root.save(dst)
    remap_info = ', '.join(f"{k}->{REMAP[k]}" for k in remap_count) if remap_count else 'none'
    pit_info = f"pit=({best_pit[0]},{best_pit[1]},{best_pit[2]}) depth={best_depth:.1f}" if best_pit else "pit=none"
    ent_info = f"entities={len(out_entities)}" + (f" (skipped={entities_skipped})" if entities_skipped else "")
    print(f"[schem->nbt] {src.name} -> {dst.name}  (WxHxL = {W}x{H}x{L}, "
          f"palette={len(new_palette)}, blocks={len(blocks)}, water_stripped={skipped_water}, "
          f"remap=[{remap_info}], outer_sand={sand_layer_added}, {pit_info}, {ent_info}, "
          f"size={dst.stat().st_size} bytes)")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    village_mode = '--village-mode' in sys.argv[1:]
    if len(args) != 2:
        sys.stderr.write("usage: schem_to_nbt.py [--village-mode] <src.schem> <dst.nbt>\n")
        sys.exit(1)
    src = Path(args[0])
    dst = Path(args[1])
    if not src.is_file():
        sys.stderr.write(f"ERROR: src not found: {src}\n")
        sys.exit(1)
    convert(src, dst, village_mode=village_mode)


if __name__ == '__main__':
    main()

"""
Конвертация Sponge Schematic v2 (.schem) -> Minecraft Structure (.nbt) для 1.20.1.
Запускается gradle-таской convertSchemToNbt.

Требования: Python 3.8+, nbtlib (pip install nbtlib).
"""
import sys
from pathlib import Path

try:
    import nbtlib
    from nbtlib.tag import Compound, Int, IntArray, List, String
except ImportError:
    sys.stderr.write("ERROR: nbtlib не установлен. Установи: pip install nbtlib\n")
    sys.exit(2)

DATA_VERSION_1_20_1 = 3465


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


def convert(src: Path, dst: Path):
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

    air_id = palette_map.get('minecraft:air')

    # Кольцо воды вокруг острова в .schem было бы поверх натурального океана — убираем.
    # Все водные/пузырьковые блоки пропускаем как будто air: в Structure NBT их нет,
    # и natural ocean заливает эти позиции при пасте. Остров сам по себе сухой.
    # Песок (sand/red_sand) тоже стрипаем — в схеме стоит служебный столб песка,
    # отмечающий центр WorldEdit-копии. Пляжи восстановит природный океан.
    skip_ids = set()
    if air_id is not None:
        skip_ids.add(air_id)
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

    # Формат Sponge: индекс = x + z*W + y*W*L
    # Формат Structure: хранит blocks как список не-air блоков.
    # Палитру строим из тех state-ов, что реально встретились (кроме air/water при пропуске).
    used_ids = set()
    for bid in ids:
        if bid not in skip_ids:
            used_ids.add(bid)

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
    for y in range(H):
        for z in range(L):
            base = y * W * L + z * W
            for x in range(W):
                bid = ids[base + x]
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

    root = nbtlib.File({
        'DataVersion': Int(DATA_VERSION_1_20_1),
        'size': List[Int]([Int(W), Int(H), Int(L)]),
        'palette': List[Compound](new_palette),
        'blocks': List[Compound](blocks),
        'entities': List[Compound]([]),
    }, gzipped=True)

    dst.parent.mkdir(parents=True, exist_ok=True)
    root.save(dst)
    print(f"[schem->nbt] {src.name} -> {dst.name}  (WxHxL = {W}x{H}x{L}, "
          f"palette={len(new_palette)}, blocks={len(blocks)}, water_stripped={skipped_water}, "
          f"size={dst.stat().st_size} bytes)")


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: schem_to_nbt.py <src.schem> <dst.nbt>\n")
        sys.exit(1)
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    if not src.is_file():
        sys.stderr.write(f"ERROR: src not found: {src}\n")
        sys.exit(1)
    convert(src, dst)


if __name__ == '__main__':
    main()

"""Одноразовый диагностический скрипт: находит ямы в ostrov1.schem."""
import sys
from pathlib import Path
import nbtlib

SKIP = {'minecraft:air', 'minecraft:water', 'minecraft:flowing_water',
        'minecraft:bubble_column', 'minecraft:kelp', 'minecraft:kelp_plant',
        'minecraft:seagrass', 'minecraft:tall_seagrass',
        'minecraft:sand', 'minecraft:red_sand'}


def decode(data, expected):
    out = [0] * expected
    i = pos = 0
    while i < expected:
        v = shift = 0
        while True:
            b = data[pos]; pos += 1
            v |= (b & 0x7F) << shift
            if not (b & 0x80): break
            shift += 7
        out[i] = v; i += 1
    return out


def main():
    src = Path(sys.argv[1])
    schem = nbtlib.load(src, gzipped=True)
    W, H, L = int(schem['Width']), int(schem['Height']), int(schem['Length'])
    palette = {str(k): int(v) for k, v in schem['Palette'].items()}
    id_name = [''] * (max(palette.values()) + 1)
    for k, v in palette.items():
        id_name[v] = k.split('[', 1)[0]

    skip_ids = {i for i, n in enumerate(id_name) if n in SKIP}
    raw = bytes((b & 0xFF) for b in schem['BlockData'])
    ids = decode(raw, W * H * L)

    # heightmap[x][z] = max y where block is NOT skip (-1 if all skip)
    hm = [[-1] * L for _ in range(W)]
    min_x, max_x, min_z, max_z = W, -1, L, -1
    for y in range(H):
        for z in range(L):
            base = y * W * L + z * W
            for x in range(W):
                if ids[base + x] in skip_ids:
                    continue
                if y > hm[x][z]:
                    hm[x][z] = y
                if x < min_x: min_x = x
                if x > max_x: max_x = x
                if z < min_z: min_z = z
                if z > max_z: max_z = z

    cx_schem = (min_x + max_x) // 2
    cz_schem = (min_z + max_z) // 2
    print(f"Size: {W}x{H}x{L}")
    print(f"Island bbox (schem coords): x=[{min_x},{max_x}] z=[{min_z},{max_z}]")
    print(f"Island center (bbox mid, schem coords): ({cx_schem}, {cz_schem})")
    print()

    # Ищем локальные минимумы heightmap внутри bbox — это ямы.
    # Колонка — яма, если её top ниже всех 8 соседей минимум на 2.
    pits = []
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            y = hm[x][z]
            if y < 0:
                continue
            neigh = []
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx == 0 and dz == 0: continue
                    nx, nz = x + dx, z + dz
                    if 0 <= nx < W and 0 <= nz < L and hm[nx][nz] >= 0:
                        neigh.append(hm[nx][nz])
            if len(neigh) >= 6:
                avg = sum(neigh) / len(neigh)
                if avg - y >= 2:
                    pits.append((x, z, y, avg))

    print(f"Найдено потенциальных ям (топ глубже соседей >= 2): {len(pits)}")
    pits.sort(key=lambda p: p[3] - p[2], reverse=True)
    for x, z, y, avg in pits[:15]:
        dx = x - cx_schem
        dz = z - cz_schem
        print(f"  schem=({x:3d},{z:3d})  top_y={y:3d}  avg_neigh={avg:5.1f}  depth={avg - y:4.1f}  "
              f"offset_from_center=({dx:+d},{dz:+d})")

    # Абсолютный минимум heightmap в bbox
    min_y_in_bbox = H
    min_pos = None
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            y = hm[x][z]
            if y >= 0 and y < min_y_in_bbox:
                min_y_in_bbox = y
                min_pos = (x, z)
    print()
    print(f"Абсолютный минимум top_y в bbox острова: y={min_y_in_bbox} at schem={min_pos}")
    if min_pos:
        dx = min_pos[0] - cx_schem
        dz = min_pos[1] - cz_schem
        print(f"  offset от центра bbox: ({dx:+d}, {dz:+d})")


if __name__ == '__main__':
    main()

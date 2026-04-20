# pepel-edema-mod

Java-мод «Пепел Эдема» (Forge 1.20.1). Содержит Книгу Эрена и worldgen-структуру стартового острова.

## Требования

- Java **17** (строго, Forge 1.20.1 не запускается на 21)
- Python 3.8+ с `nbtlib`: `pip install nbtlib` (нужен для билд-таски `convertSchemToNbt`)

## Быстрый старт: цикл разработки

```bash
cd pepel-edema-mod
./gradlew build
cp build/libs/pepel-0.1.0.jar ../instances/PepelEdema/mods/
# Запусти лаунчер и создай НОВЫЙ мир — старые миры не пересчитывают worldgen
python ../main.py
```

## Структура

```
schem-src/
  ostrov1.schem          # WorldEdit-исходник стартового острова (NBT gzip)
  schem_to_nbt.py        # Python-конвертёр .schem → .nbt (вызывается Gradle-таской)

src/main/java/com/pepel/edema/
  PepelEdema.java        # @Mod, регистрация Config + event bus
  config/PepelConfig.java    # ForgeConfig: параметры ring-check
  item/ModItems.java         # Книга Эрена и др.
  worldgen/
    SpawnIslandStructure.java   # Structure + ring-check
    SpawnIslandPiece.java       # TemplateStructurePiece — пастит .nbt
    SpawnHandler.java           # Forge-event, ставит мировой спавн
    ModStructureTypes.java
    ModStructurePieceTypes.java

src/main/resources/data/pepel/
  structures/spawn_island.nbt         # Генерится из .schem на build
  worldgen/structure/spawn_island.json
  worldgen/structure_set/spawn_island.json
  tags/worldgen/biome/spawn_island_land.json
  tags/worldgen/biome/spawn_island_ocean.json
  tags/worldgen/structure/spawn_island.json
```

## Как работает spawn island

1. **Конвертер** (`schem_to_nbt.py`) при сборке читает `.schem`, **вырезает воду и песок** (они дают протекающее дно и служебный столб WE), пишет `.nbt` формата Minecraft 1.20.1.
2. **Worldgen** (`SpawnIslandStructure.findGenerationPoint`) для каждого чанка-кандидата:
   - Центр чанка должен быть океанским биомом.
   - *Внутренний круг* `[0..inner_radius]` — суши **не должно** быть (≤ `inner_coverage_max`), иначе это озеро.
   - *Внешнее кольцо* `[dist_min..dist_max]` — суши **должно быть много** (≥ `coverage_min`).
   - Origin пасты = `(cx - size.x/2, y, cz - size.z/2)` — **центр схемы** в центре чанка.
3. **Мировой спавн** (`SpawnHandler.onCreateSpawn`) при создании мира:
   - Находит структуру через `findNearestMapStructure` по тегу `#pepel:spawn_island`.
   - Читает сам `.nbt`-template в память, ищет все `grass_block / podzol / mycelium / moss_block`.
   - Центроид этих блоков → ближайший грасс-блок → `+1 вверх` = точка спавна игрока.

## Как поменять остров

1. В игре (`./gradlew runClient` или через лаунчер) делаем остров.
2. WorldEdit: `//copy` → `//schem save ostrov1`.
3. Копируем из `instances/PepelEdema/config/worldedit/schematics/ostrov1.schem` → `schem-src/ostrov1.schem` (перезапись).
4. `./gradlew build` (скрипт сам пересчитает `.nbt`).
5. Копируем jar в модпак, запускаем **новый** мир.

## Как крутить параметры без пересборки

Файл (создаётся при первом запуске мода):
```
instances/PepelEdema/config/pepel-worldgen.toml
```

| Параметр | Что делает |
|---|---|
| `coverage_min` | Доля лучей во внешнем кольце, которые должны упереться в берег (0.5 легче, 0.9 строже) |
| `distance_mean` | Среднее расстояние от острова до берега (блоки) |
| `distance_tolerance` | Допуск ±, проверяются радиусы [mean−tol .. mean+tol] |
| `inner_radius` | Радиус «чистой воды» вокруг острова |
| `inner_coverage_max` | Сколько суши разрешено внутри `inner_radius` |
| `num_rays` | Количество лучей по окружности (больше = точнее) |
| `search_ray_step` | Шаг по радиусу (блоки) |

Правим TOML → перезапуск → **новый** мир.

## Как поменять биомы

- `data/pepel/tags/worldgen/biome/spawn_island_land.json` — что считается «берегом».
- `data/pepel/tags/worldgen/biome/spawn_island_ocean.json` — где разрешён спавн острова.

Правим → `./gradlew build` → jar → новый мир.

## Как поменять высоту пасты

`data/pepel/worldgen/structure/spawn_island.json`, поле `y_offset` (текущее: `-7`).

Отрицательные значения = ниже относительно `seaLevel - 1`.

## Команды

### `/pepel book notify <players> <entryId> <normal|key>`

Триггерит уведомление в Книге Эрена у указанных игроков. Permission level 2 (OP / командный блок / FTB Quests reward).

- **Эффекты:** мерцание книги в инвентаре + vanilla Toast «Книга Эрена обновилась...» + звук (`amethyst_block.chime` для `normal`, `portal.trigger` для `key`)
- **Гасится** автоматически при открытии книги
- `entryId` — произвольная строка-идентификатор записи (нужна для дедупа: повторное `notify` с тем же `entryId` не дублирует уведомление)

**Примеры:**
```
/pepel book notify Bri awakening key
/pepel book notify @s test normal
/pepel book notify @a shard_unlocked normal
```

**Из KubeJS:**
```js
event.player.runCommandSilent(
    `pepel book notify ${event.player.username} awakening normal`
)
```

**Из FTB Quests reward "command":**
```
pepel book notify @s shard_unlocked key
```

## Логи

`instances/PepelEdema/logs/latest.log`. Искать строки:
- `Island bbox: (...)` — нашли структуру.
- `Template grass/podzol: N blocks, centroid=(x,z), spawn=...` — куда поставили спавн.
- `Spawn island not found within 200 chunks` — структура не сгенерировалась (ослабь `coverage_min`).

## Частые проблемы

| Симптом | Причина | Фикс |
|---|---|---|
| Игрок не на острове | Структура не сгенерилась рядом | Ослабь `coverage_min` до 0.5 |
| Игрок под водой | `y_offset` слишком маленький | `-5` или `-3` |
| Игрок на дереве | Центроид попал под крону | Подправь остров |
| Мод не грузится | jar не скопирован / не пересобран | `./gradlew build` → смотри вывод |
| `Cannot find template` | `.schem` не сконвертился | Проверь `schem-src/ostrov1.schem` |

## Коммиты

Изменения мода — отдельно от лаунчера и модпака. Формат:
```
feat(mod): <что сделал>
fix(mod): <что починил>
```

Подробнее — `docs/CONTRIBUTING.md` в корне репозитория.

## Что ещё планируется (бэклог)

- C4: Mixin-патчи для дропа осколков и условий порталов
- C6: крафтовая станция осколков (кастомный блок + UI)
- Команда `/pepel_ringdebug` — отладка ring-check прямо в игре
- Gradle-таска `copySchemFromModpack` — автокопирование `.schem` из модпака в `schem-src/`

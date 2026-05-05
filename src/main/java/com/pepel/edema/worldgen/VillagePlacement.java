package com.pepel.edema.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Размещение деревни Приют. Алгоритм:
 *
 *  1. Считаем вектор dir = normalize(fishermanPos - islandCenter).
 *  2. target = fishermanPos + dir * DISTANCE_FROM_FISHERMAN.
 *  3. Вокруг target ищем "плоское" место по bbox всей деревни (VILLAGE_BBOX_SIDE).
 *     Берём кандидата с минимальным перепадом heightmap.
 *  4. Грузим manifest priyut.json — список зданий с offset'ами от pivot=well.
 *  5. Для каждого здания: считаем медиану heightmap по его bbox → targetY,
 *     deforest+feather в bbox+EDGE_FEATHER, place template.
 *  6. Сохраняем pivot в VillagePriyutState.
 */
public class VillagePlacement
{
    /** Расстояние "за рыбаком" по вектору от острова. */
    private static final int DISTANCE_FROM_FISHERMAN = 300;

    /** Шаг сэмплирования heightmap по X/Z. */
    private static final int HEIGHTMAP_STEP = 4;

    /** Глубина dirt-слоя под grass при засыпке (чтобы под зданием не зияла пустота). */
    private static final int FILL_DEPTH = 3;

    /** Ширина "пера" у границы bbox каждого здания: linear-interp targetY → natural heightmap. */
    private static final int EDGE_FEATHER = 4;

    /** Радиус срубания деревьев вокруг каждого здания (logs+leaves, землю не трогаем). */
    private static final int DEFOREST_BUFFER = 5;

    /**
     * Максимальная глубина BFS от удалённого лога через соединённые листья.
     * 6 = vanilla leaf decay distance: лист дальше 6 шагов от любого лога естественно decay'ится.
     * Так листья соседних деревьев (не соединённых через цепочку с нашими логами) — остаются.
     */
    private static final int MAX_LEAF_BFS_DEPTH = 6;

    /** Полуширина гравийной дорожки от центра. 1 → 3-широкая дорожка. */
    private static final int ROAD_RADIUS = 1;

    /**
     * Размер bbox всей деревни в блоках для FLAT_SEARCH (70×70 — покрывает все здания
     * с раздвинутыми offset'ами в priyut.json: farm в (+20, +14) с bbox 31×24 даёт max +35.5/+26).
     * FLAT_SEARCH ищет место где этот bbox максимально плоский И не на воде.
     */
    private static final int VILLAGE_BBOX_SIDE = 90;

    /**
     * Расширенная зона для оценки плоскости при FLAT_SEARCH. Шире чем footprint деревни (90)
     * чтобы пенализировать «хилл-топы» — локально плоский пятак на вершине холма со склонами
     * по краям. На 140×140 в delta попадает склон вокруг → высокая delta → проигрывает
     * кандидату на настоящей равнине. villageTargetY (медиана) считается всё равно на
     * VILLAGE_BBOX_SIDE — чтобы плато совпадало с реальным footprint, не размывалось далёкими
     * холмами.
     */
    private static final int FLATNESS_BBOX_SIDE = 140;

    /**
     * Радиус поиска плоского места вокруг target. 192 = 4× площадь vs 96 (625 кандидатов
     * вместо 169) — в hilly биомах (тайга/savanna) гораздо больше шансов найти настоящую
     * равнину, не довольствоваться лучшим из плохих хилл-топов в узком радиусе.
     */
    private static final int FLAT_SEARCH_RADIUS = 192;

    /** Шаг поиска кандидатов на FLAT_SEARCH grid. */
    private static final int FLAT_SEARCH_STEP   = 16;

    /** Максимальная допустимая доля сэмплов на воде в bbox (если выше — кандидат отбрасывается). */
    private static final double MAX_WATER_RATIO = 0.10;

    /**
     * Подстроки в имени биома (lowercase), запрещающие постановку деревни.
     * Любое совпадение → штраф BAD_BIOME_PENALTY к score кандидата.
     * Dark Forest: 2×2 dark oak deforest не ловит чисто; mushroom_fields: грибы
     * не относятся к BlockTags.LOGS/LEAVES; ocean/river/beach/desert: нет grass;
     * snowy/frozen/ice: snow_layer; deep_dark/dripstone: подземные.
     */
    private static final String[] BIOME_BLACKLIST = {
            "dark_forest", "mushroom",
            "ocean", "river", "beach",
            "desert", "badlands",
            "snowy", "frozen", "ice",
            "jungle", "swamp",
            "deep_dark", "dripstone",
            "nether", "end"
    };

    /** Штраф к score кандидата за плохой биом — гарантирует что любой плохой проиграет любому хорошему. */
    private static final int BAD_BIOME_PENALTY = 10000;

    /** Resource location манифеста деревни. */
    private static final ResourceLocation MANIFEST_ID =
            new ResourceLocation(PepelEdema.MODID, "village/priyut.json");

    /** Префикс template'ов зданий: pepel:village/{id}. */
    private static final String TEMPLATE_PREFIX = "village/";

    public static void tryPlaceVillage(ServerLevel level, BlockPos islandCenter, BlockPos fishermanPos)
    {
        VillagePriyutState state = VillagePriyutState.get(level);
        if (state.isSpawned())
        {
            PepelEdema.LOGGER.info("[village] уже заспавнена в {}, пропускаем", state.getPivot());
            return;
        }

        // === Вектор от острова к рыбаку ===
        double dx = fishermanPos.getX() - islandCenter.getX();
        double dz = fishermanPos.getZ() - islandCenter.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0)
        {
            PepelEdema.LOGGER.warn("[village] рыбак почти в центре острова (len={}), пропускаем", len);
            return;
        }

        int targetX = (int) (fishermanPos.getX() + dx / len * DISTANCE_FROM_FISHERMAN);
        int targetZ = (int) (fishermanPos.getZ() + dz / len * DISTANCE_FROM_FISHERMAN);

        // === Force-load всех чанков в зоне поиска ===
        // Без этого level.getHeight для незагруженных чанков возвращает minBuildHeight=-64,
        // FLAT_SEARCH "находит" их как идеально-плоское место и деревня впечатывается в дно мира.
        // Размер зоны = 2*FLAT_SEARCH_RADIUS + FLATNESS_BBOX_SIDE (внешний край широкого bbox
        // для самых дальних кандидатов).
        int loadSide = 2 * FLAT_SEARCH_RADIUS + FLATNESS_BBOX_SIDE;
        forceLoadChunks(level, targetX, targetZ, loadSide);

        // === Загружаем manifest ===
        List<Building> buildings = loadManifest(level);
        if (buildings == null || buildings.isEmpty())
        {
            PepelEdema.LOGGER.error("[village] manifest пуст или не загрузился, пропускаем");
            return;
        }
        PepelEdema.LOGGER.info("[village] manifest: {} зданий", buildings.size());

        // === Поиск плоского НЕ-ВОДНОГО места в дружественном биоме (90×90 bbox) ===
        // Score-based: bbox-delta + штраф BAD_BIOME_PENALTY если биом из BIOME_BLACKLIST.
        // Так любой кандидат в хорошем биоме (плэйнсы/обычный лес/берёзы) выигрывает у любого
        // в Dark Forest/Mushroom Fields/болоте даже если последний идеально плоский. Если
        // НИ ОДНОГО хорошего биома в зоне поиска — fallback к лучшему плохому (с warning).
        // Вода — hard reject (как и раньше).
        int bestPivotX = targetX, bestPivotZ = targetZ;
        int bestScore = Integer.MAX_VALUE;
        int bestDelta = Integer.MAX_VALUE;
        boolean bestInGoodBiome = false;
        int rejectedWater = 0, badBiomeCount = 0, candidatesTotal = 0;
        for (int ox = -FLAT_SEARCH_RADIUS; ox <= FLAT_SEARCH_RADIUS; ox += FLAT_SEARCH_STEP)
        {
            for (int oz = -FLAT_SEARCH_RADIUS; oz <= FLAT_SEARCH_RADIUS; oz += FLAT_SEARCH_STEP)
            {
                candidatesTotal++;
                int cx = targetX + ox;
                int cz = targetZ + oz;
                AreaScan scan = scanArea(level, cx, cz, FLATNESS_BBOX_SIDE);
                if (scan.waterRatio > MAX_WATER_RATIO)
                {
                    rejectedWater++;
                    continue;
                }
                boolean badBiome = isBadBiome(level, cx, cz);
                if (badBiome) badBiomeCount++;
                int score = scan.delta + (badBiome ? BAD_BIOME_PENALTY : 0);
                if (score < bestScore)
                {
                    bestScore = score;
                    bestDelta = scan.delta;
                    bestPivotX = cx;
                    bestPivotZ = cz;
                    bestInGoodBiome = !badBiome;
                }
            }
        }
        PepelEdema.LOGGER.info("[village] FLAT_SEARCH: target=({},?,{}), кандидатов={} (вода-reject={}, плохой-биом={}), pivot=({},?,{}) (сдвиг dx={},dz={}), bbox-delta={}, biome-ok={}",
                targetX, targetZ, candidatesTotal, rejectedWater, badBiomeCount,
                bestPivotX, bestPivotZ,
                bestPivotX - targetX, bestPivotZ - targetZ, bestDelta, bestInGoodBiome);
        if (bestScore == Integer.MAX_VALUE)
        {
            PepelEdema.LOGGER.warn("[village] все кандидаты на воде, деревня не поставлена. Расширь FLAT_SEARCH_RADIUS или поплыви в другую сторону.");
            return;
        }
        if (!bestInGoodBiome)
        {
            PepelEdema.LOGGER.warn("[village] хороший биом не найден в радиусе {} — деревня сядет в плохом ({} кандидатов из {} были в blacklist). Поплыви дальше.",
                    FLAT_SEARCH_RADIUS, badBiomeCount, candidatesTotal);
        }

        // Sanity-check на villageTargetY убран: каждое здание считает targetY локально в
        // preparePlan() из своего bbox. Если строим в нормальной точке (bbox-delta фильтр
        // FLAT_SEARCH прошёл, водой не залило), все локальные targetY будут разумные.

        // === Двухпроходный алгоритм размещения ===
        // Прежняя реализация ставила каждое здание по очереди (deforest → align → place):
        // feather одного здания затирал template уже поставленного соседа, отсюда обрезанные крыши
        // и аномальные окопы между зданиями. Теперь:
        //   Pass 1: собираем целевой columnY для каждого (x,z) в общей зоне деревни.
        //           Внутри bbox любого здания — жёстко villageTargetY (приоритет).
        //           В feather-кольце — lerp от villageTargetY к natural heightmap.
        //           Если (x,z) попал в feather нескольких зданий — берём max columnY (ближе к
        //           деревне). Натуральный heightmap читаем ОДИН раз до любых модификаций.
        //   Pass 2: deforestation в общей зоне.
        //   Pass 3: применяем heightMap (выравнивание).
        //   Pass 4: ставим все template'ы — они уже не затирают друг друга, землю под собой
        //           тоже не модифицируют (она уже выровнена).
        List<BuildingPlan> plans = new ArrayList<>();
        for (Building b : buildings)
        {
            BuildingPlan p = preparePlan(level, b, bestPivotX, bestPivotZ);
            if (p != null) plans.add(p);
        }

        // Pass 1: собираем heightmap. Каждое здание использует СВОЙ p.targetY (90-перцентиль
        // local terrain), не общий villageTargetY — чтобы не делать общую плоскую платформу
        // на весь village и пирамидальные террасы по краям. На пересечении feather'ов
        // соседних зданий берём max columnY (ближе к плато ровнее).
        Map<Long, Integer> heightMap = new HashMap<>();
        for (BuildingPlan p : plans)
        {
            // Внутри bbox: жёстко p.targetY этого здания.
            for (int x = p.originX; x <= p.maxX; x++)
            {
                for (int z = p.originZ; z <= p.maxZ; z++)
                {
                    heightMap.put(key(x, z), p.targetY);
                }
            }
        }
        for (BuildingPlan p : plans)
        {
            // Feather кольцо: lerp natural → p.targetY. Не трогаем уже занятые bbox-клетки.
            for (int x = p.originX - EDGE_FEATHER; x <= p.maxX + EDGE_FEATHER; x++)
            {
                for (int z = p.originZ - EDGE_FEATHER; z <= p.maxZ + EDGE_FEATHER; z++)
                {
                    long k = key(x, z);
                    if (heightMap.containsKey(k)) continue; // уже bbox чьего-то здания

                    int distEdge = 0;
                    if (x < p.originX) distEdge = Math.max(distEdge, p.originX - x);
                    if (x > p.maxX)    distEdge = Math.max(distEdge, x - p.maxX);
                    if (z < p.originZ) distEdge = Math.max(distEdge, p.originZ - z);
                    if (z > p.maxZ)    distEdge = Math.max(distEdge, z - p.maxZ);
                    if (distEdge == 0 || distEdge > EDGE_FEATHER) continue;

                    // Медиана по 3×3 — отвергает одиночные ямы (одну клетку с natural=64 среди
                    // соседей с natural=72) и не делает в ней высокую DIRT-башню при Pass 3 fill.
                    // Реальные склоны (где много соседних клеток имеют такой же low natural)
                    // медиана сохраняет.
                    int natural = medianGroundY(level, x, z, 1);
                    float weight = 1.0f - (float) distEdge / (EDGE_FEATHER + 1);
                    int columnY = Math.round(p.targetY * weight + natural * (1.0f - weight));
                    Integer prev = heightMap.get(k);
                    // Пересечение feather'ов соседних зданий: берём max — ближе к плато ровнее.
                    if (prev == null || columnY > prev)
                    {
                        heightMap.put(k, columnY);
                    }
                }
            }
        }
        PepelEdema.LOGGER.info("[village] heightmap построен: {} колонок", heightMap.size());

        // Pass 2: deforestation. Трёхшаговая.
        //   Шаг А: собираем ВСЕ логи в расширенной зоне (deforest + SURVIVING_LOG_BUFFER).
        //          Сканируем колонку сверху вниз, пропуская AIR/LOGS/LEAVES, останавливаемся на
        //          первом солидном ground-блоке (dirt/grass/stone/etc.) — так ловим даже очень
        //          высокие деревья (skyroot, dark_oak), у которых нижние логи раньше уезжали ниже
        //          scanTo = top-25 и оставались огрызками.
        //   Шаг Б: классификация: лог в bbox+DEFOREST_BUFFER → к удалению, остальные → выжившие.
        //   Шаг В: BFS через face-adjacent листья от логов-к-удалению. ОСТАНАВЛИВАЕМСЯ на листьях
        //          прилегающих к выжившему логу — иначе крона соседнего нетронутого дерева
        //          сшелушивается и остаётся голый ствол. Глубина MAX_LEAF_BFS_DEPTH=6 = vanilla.
        final int SURVIVING_LOG_BUFFER = 12;
        Set<BlockPos> allLogs = new HashSet<>();
        int minBuild = level.getMinBuildHeight();
        for (BuildingPlan p : plans)
        {
            int xMin = p.originX - DEFOREST_BUFFER - SURVIVING_LOG_BUFFER;
            int xMax = p.maxX    + DEFOREST_BUFFER + SURVIVING_LOG_BUFFER;
            int zMin = p.originZ - DEFOREST_BUFFER - SURVIVING_LOG_BUFFER;
            int zMax = p.maxZ    + DEFOREST_BUFFER + SURVIVING_LOG_BUFFER;
            for (int x = xMin; x <= xMax; x++)
            {
                for (int z = zMin; z <= zMax; z++)
                {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    int scanFrom = Math.min(top + 1, level.getMaxBuildHeight() - 1);
                    for (int y = scanFrom; y >= minBuild; y--)
                    {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState bs = level.getBlockState(pos);
                        if (bs.is(BlockTags.LOGS))
                        {
                            allLogs.add(pos);
                        }
                        else if (bs.is(BlockTags.LEAVES) || bs.isAir())
                        {
                            // продолжаем вниз сквозь крону/воздух
                        }
                        else
                        {
                            // первый солидный ground-блок — кончили скан этой колонки
                            break;
                        }
                    }
                }
            }
        }

        // Шаг Б: классификация
        Set<BlockPos> logsToRemove = new HashSet<>();
        Set<BlockPos> survivingLogs = new HashSet<>();
        for (BlockPos pos : allLogs)
        {
            boolean inRemoveZone = false;
            for (BuildingPlan p : plans)
            {
                if (pos.getX() >= p.originX - DEFOREST_BUFFER && pos.getX() <= p.maxX + DEFOREST_BUFFER &&
                    pos.getZ() >= p.originZ - DEFOREST_BUFFER && pos.getZ() <= p.maxZ + DEFOREST_BUFFER)
                {
                    inRemoveZone = true;
                    break;
                }
            }
            if (inRemoveZone) logsToRemove.add(pos);
            else              survivingLogs.add(pos);
        }

        // Шаг В: BFS листьев с защитой выживших логов
        Queue<BlockPos> bfsQueue = new ArrayDeque<>();
        Map<BlockPos, Integer> leafDepth = new HashMap<>();
        for (BlockPos log : logsToRemove)
        {
            for (Direction dir : Direction.values())
            {
                BlockPos n = log.relative(dir);
                if (logsToRemove.contains(n) || survivingLogs.contains(n) || leafDepth.containsKey(n)) continue;
                if (level.getBlockState(n).is(BlockTags.LEAVES) && !isLeafProtected(n, survivingLogs))
                {
                    leafDepth.put(n, 1);
                    bfsQueue.add(n);
                }
            }
        }
        while (!bfsQueue.isEmpty())
        {
            BlockPos pos = bfsQueue.poll();
            int d = leafDepth.get(pos);
            if (d >= MAX_LEAF_BFS_DEPTH) continue;
            for (Direction dir : Direction.values())
            {
                BlockPos n = pos.relative(dir);
                if (leafDepth.containsKey(n) || logsToRemove.contains(n) || survivingLogs.contains(n)) continue;
                if (level.getBlockState(n).is(BlockTags.LEAVES) && !isLeafProtected(n, survivingLogs))
                {
                    leafDepth.put(n, d + 1);
                    bfsQueue.add(n);
                }
            }
        }

        for (BlockPos pos : logsToRemove)       level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (BlockPos pos : leafDepth.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        PepelEdema.LOGGER.info("[village] deforest: всего логов={}, к-удалению={}, выжило={}, листьев удалено={} (BFS, max depth={})",
                allLogs.size(), logsToRemove.size(), survivingLogs.size(), leafDepth.size(), MAX_LEAF_BFS_DEPTH);

        // Pass 3: применяем heightMap — выравнивание земли.
        long filled = 0, removed = 0;
        for (Map.Entry<Long, Integer> entry : heightMap.entrySet())
        {
            long k = entry.getKey();
            int x = (int) (k >> 32);
            int z = (int) (k & 0xFFFFFFFFL); // cast → int делает sign-extend автоматически
            int columnY = entry.getValue();
            int currentTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            if (currentTop < columnY)
            {
                for (int y = currentTop; y < columnY - 1; y++)
                {
                    level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                    filled++;
                }
                level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                filled++;
            }
            else if (currentTop >= columnY)
            {
                for (int y = columnY; y <= currentTop; y++)
                {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    removed++;
                }
                level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }

            for (int y = columnY - FILL_DEPTH; y < columnY - 1; y++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos).isAir())
                {
                    level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
                    filled++;
                }
            }
        }
        PepelEdema.LOGGER.info("[village] выравнивание: filled={}, removed={}", filled, removed);

        // Pass 4: ставим все template'ы — теперь они кладутся поверх плоской земли,
        // bbox-зоны не пересекаются (manifest гарантирует), template друг друга не затирают.
        // templatePos.y = villageTargetY - 1: template'ы authored в worldedit с собственным
        // слоем земли в Y=0 (well/q_board: dirt; herb_house/farm: grass+stone). Чтобы этот
        // слой совпал с feather-grass-уровнем (columnY-1) а здание начиналось ровно на нём,
        // опускаем templatePos на 1.
        // BlockIgnoreProcessor.AIR: AIR-блоки в template (верх bbox, окна, дверные проёмы)
        // не пишутся в мир — иначе срезают листву соседних деревьев и оставляют дыры в
        // воздухе там где было что-то живое.
        int placed = 0;
        for (BuildingPlan p : plans)
        {
            BlockPos templatePos = new BlockPos(p.originX, p.targetY - 1, p.originZ);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .addProcessor(BlockIgnoreProcessor.AIR);
            boolean ok = p.template.placeInWorld(level, templatePos, templatePos, settings, level.getRandom(), 2);
            if (ok)
            {
                placed++;
                PepelEdema.LOGGER.info("[village] {} поставлен: pivot=({},{},{}), bbox=[{}..{}, {}..{}], targetY={}",
                        p.id, p.pivotX, p.targetY, p.pivotZ,
                        p.originX, p.maxX, p.originZ, p.maxZ, p.targetY);
            }
            else
            {
                PepelEdema.LOGGER.error("[village] placeInWorld для {} вернул false", p.id);
            }
        }

        // Pass 5: гравийные дорожки от колодца к каждому зданию. Bresenham line
        // с brush-радиусом ROAD_RADIUS, поверхность подменяется на gravel.
        // Внутри bbox любого здания не красим (чтобы не лезть на пол/фундамент).
        BuildingPlan wellPlan = null;
        for (BuildingPlan p : plans) if ("well".equals(p.id)) { wellPlan = p; break; }
        if (wellPlan != null)
        {
            int painted = 0;
            for (BuildingPlan target : plans)
            {
                if (target == wellPlan) continue;
                painted += drawRoad(level, wellPlan.pivotX, wellPlan.pivotZ,
                                          target.pivotX, target.pivotZ, plans);
            }
            PepelEdema.LOGGER.info("[village] дорожки: {} блоков gravel'я положено", painted);
        }

        // Финальный pivot для savedData = pivot колодца если есть, иначе bestPivot из FLAT_SEARCH.
        // Y берём из well.targetY (или среднее по plans если well нет).
        int finalY = wellPlan != null ? wellPlan.targetY : (plans.isEmpty() ? 64 : plans.get(0).targetY);
        BlockPos finalPivot = new BlockPos(bestPivotX, finalY, bestPivotZ);
        state.markSpawned(finalPivot);
        PepelEdema.LOGGER.info("[village] деревня поставлена. Зданий: {}/{}. Pivot={}",
                placed, buildings.size(), finalPivot);
    }

    /**
     * Bresenham от (x1,z1) до (x2,z2). На каждой точке кладёт brush радиуса ROAD_RADIUS:
     * заменяет верхний грунт на gravel. Не трогает (x,z) внутри bbox любого здания.
     */
    private static int drawRoad(ServerLevel level, int x1, int z1, int x2, int z2, List<BuildingPlan> plans)
    {
        int painted = 0;
        int dx = Math.abs(x2 - x1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1, sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;
        int x = x1, z = z1;
        while (true)
        {
            for (int ox = -ROAD_RADIUS; ox <= ROAD_RADIUS; ox++)
            {
                for (int oz = -ROAD_RADIUS; oz <= ROAD_RADIUS; oz++)
                {
                    int px = x + ox, pz = z + oz;
                    if (insideAnyBuilding(plans, px, pz)) continue;
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                    BlockPos gp = new BlockPos(px, gy, pz);
                    BlockState ground = level.getBlockState(gp);
                    if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL)
                            || ground.is(Blocks.COARSE_DIRT))
                    {
                        level.setBlock(gp, Blocks.GRAVEL.defaultBlockState(), 2);
                        painted++;
                    }
                }
            }
            if (x == x2 && z == z2) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx)  { err += dx; z += sz; }
        }
        return painted;
    }

    /** True если (x,z) внутри bbox любого здания из plans. */
    private static boolean insideAnyBuilding(List<BuildingPlan> plans, int x, int z)
    {
        for (BuildingPlan p : plans)
        {
            if (x >= p.originX && x <= p.maxX && z >= p.originZ && z <= p.maxZ) return true;
        }
        return false;
    }

    /**
     * Проверяет имя биома в (x, sea_level, z) на blacklist-подстроки. Биомы пишутся
     * 4×4×4 столбцами, центральная точка кандидата репрезентативна для bbox 90×90.
     */
    private static boolean isBadBiome(ServerLevel level, int x, int z)
    {
        Holder<Biome> holder = level.getBiome(new BlockPos(x, level.getSeaLevel(), z));
        String name = holder.unwrapKey()
                .map(k -> k.location().toString().toLowerCase())
                .orElse("");
        for (String kw : BIOME_BLACKLIST)
        {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    /** Возвращает max(width, length) template'а здания по id. 0 если не загрузился. */
    private static int maxSize(ServerLevel level, String id)
    {
        try
        {
            Vec3i s = level.getServer().getStructureManager()
                    .getOrCreate(new ResourceLocation(PepelEdema.MODID, TEMPLATE_PREFIX + id))
                    .getSize();
            return Math.max(s.getX(), s.getZ());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /**
     * Перцентиль findGroundY по квадрату side×side вокруг (cx, cz).
     * percentile=0.5 → медиана. percentile=0.9 → 90-й перцентиль.
     * Используем 0.9 для villageTargetY чтобы плато сидело на 90-м перцентиле natural-ground:
     * 90% клеток bbox — НЕ ВЫШЕ плато → Pass 3 не выкапывает их (никаких ям/обрывов с
     * обнажением подземной породы). Оставшиеся 10% (одиночные холмы-выбросы) сглаживаются
     * через ELSE-ветку. Использовали бы MAX (1.0) — одна случайная вершина в углу bbox
     * задирала бы всё плато на 5+ блоков выше нужного.
     */
    private static int computeGroundPercentile(ServerLevel level, int cx, int cz, int side, double percentile)
    {
        int half = side / 2;
        List<Integer> heights = new ArrayList<>();
        for (int x = cx - half; x <= cx + half; x += HEIGHTMAP_STEP)
        {
            for (int z = cz - half; z <= cz + half; z += HEIGHTMAP_STEP)
            {
                heights.add(findGroundY(level, x, z));
            }
        }
        heights.sort(Integer::compareTo);
        int idx = (int) Math.round(percentile * (heights.size() - 1));
        return heights.get(Math.min(idx, heights.size() - 1));
    }

    /**
     * Y верхнего блока земли в столбце (x,z), пропуская logs/leaves.
     * Heightmap.MOTION_BLOCKING_NO_LEAVES учитывает logs (они блокируют движение),
     * поэтому в лесу возвращает верх ствола, а не grass под деревом — это вызывало
     * "колонны земли" в feather-зоне, когда natural завышался на высоту ствола берёзы
     * и Pass 3 поднимал dirt-столб до этой фейковой высоты.
     */
    private static int findGroundY(ServerLevel level, int x, int z)
    {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int min = level.getMinBuildHeight();
        while (y > min)
        {
            BlockState bs = level.getBlockState(new BlockPos(x, y - 1, z));
            if (bs.is(BlockTags.LOGS) || bs.is(BlockTags.LEAVES))
            {
                y--;
                continue;
            }
            break;
        }
        return y;
    }

    /**
     * Медиана findGroundY по квадрату (2*radius+1)² вокруг (x, z). Сглаживает одиночные
     * аномалии (ямы/пни), сохраняя широкие склоны.
     */
    private static int medianGroundY(ServerLevel level, int x, int z, int radius)
    {
        List<Integer> heights = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dz = -radius; dz <= radius; dz++)
            {
                heights.add(findGroundY(level, x + dx, z + dz));
            }
        }
        heights.sort(Integer::compareTo);
        return heights.get(heights.size() / 2);
    }

    /**
     * Лист защищён, если хотя бы один из 6 face-adjacent блоков — выживший лог.
     * BFS deforest пропускает такие листья, чтобы не сшелушивать крону соседнего
     * нетронутого дерева.
     */
    private static boolean isLeafProtected(BlockPos leaf, Set<BlockPos> survivingLogs)
    {
        for (Direction dir : Direction.values())
        {
            if (survivingLogs.contains(leaf.relative(dir))) return true;
        }
        return false;
    }

    /** Упакованный ключ (x,z) для HashMap — sign-extended в long. */
    private static long key(int x, int z)
    {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** План одного здания: загруженный template, координаты bbox в мире, локальный targetY. */
    private static class BuildingPlan
    {
        String id;
        StructureTemplate template;
        int pivotX, pivotZ;
        int originX, originZ;  // нижний-левый угол bbox
        int maxX, maxZ;        // верхний-правый угол bbox
        int targetY;           // 90-й перцентиль findGroundY по bbox этого здания
    }

    /** Подготавливает план здания: загружает template, считает bbox. null если не удалось. */
    private static BuildingPlan preparePlan(ServerLevel level, Building b, int pivotBaseX, int pivotBaseZ)
    {
        ResourceLocation templateId = new ResourceLocation(PepelEdema.MODID, TEMPLATE_PREFIX + b.id);
        StructureTemplate template;
        try
        {
            template = level.getServer().getStructureManager().getOrCreate(templateId);
        }
        catch (Exception e)
        {
            PepelEdema.LOGGER.error("[village] не удалось загрузить template {}", templateId, e);
            return null;
        }
        Vec3i size = template.getSize();
        if (size.getX() == 0 || size.getZ() == 0)
        {
            PepelEdema.LOGGER.error("[village] template {} пустой (size={})", templateId, size);
            return null;
        }
        BuildingPlan p = new BuildingPlan();
        p.id = b.id;
        p.template = template;
        p.pivotX = pivotBaseX + b.ox;
        p.pivotZ = pivotBaseZ + b.oz;
        p.originX = p.pivotX - size.getX() / 2;
        p.originZ = p.pivotZ - size.getZ() / 2;
        p.maxX = p.originX + size.getX() - 1;
        p.maxZ = p.originZ + size.getZ() - 1;

        // Локальный targetY: 90-й перцентиль findGroundY по bbox именно этого здания.
        // Так каждое здание сидит на своём уровне относительно local terrain — без общей
        // плоской платформы на весь village, без террасных пирамид по краям.
        List<Integer> heights = new ArrayList<>();
        for (int x = p.originX; x <= p.maxX; x += HEIGHTMAP_STEP)
        {
            for (int z = p.originZ; z <= p.maxZ; z += HEIGHTMAP_STEP)
            {
                heights.add(findGroundY(level, x, z));
            }
        }
        // Гарантируем хотя бы один сэмпл на маленьком bbox (well: 5×5, q_board: 8×5).
        if (heights.isEmpty()) heights.add(findGroundY(level, p.pivotX, p.pivotZ));
        heights.sort(Integer::compareTo);
        int idx = (int) Math.round(0.9 * (heights.size() - 1));
        p.targetY = heights.get(Math.min(idx, heights.size() - 1));
        return p;
    }

    // === Старый однопроходный placeBuilding оставлен ниже для справки, но не вызывается. ===
    // (фактически удалён рефакторингом — двухпроходный алгоритм inline в tryPlaceVillage)
    @Deprecated
    private static boolean placeBuilding(ServerLevel level, String id, int pivotX, int pivotZ, int targetY)
    {
        ResourceLocation templateId = new ResourceLocation(PepelEdema.MODID, TEMPLATE_PREFIX + id);
        StructureTemplate template;
        try
        {
            template = level.getServer().getStructureManager().getOrCreate(templateId);
        }
        catch (Exception e)
        {
            PepelEdema.LOGGER.error("[village] не удалось загрузить template {}", templateId, e);
            return false;
        }
        Vec3i size = template.getSize();
        if (size.getX() == 0 || size.getZ() == 0)
        {
            PepelEdema.LOGGER.error("[village] template {} пустой (size={})", templateId, size);
            return false;
        }

        int originX = pivotX - size.getX() / 2;
        int originZ = pivotZ - size.getZ() / 2;

        // === Deforestation в bbox + DEFOREST_BUFFER ===
        long treeRemoved = 0;
        int defXMin = originX - DEFOREST_BUFFER;
        int defXMax = originX + size.getX() - 1 + DEFOREST_BUFFER;
        int defZMin = originZ - DEFOREST_BUFFER;
        int defZMax = originZ + size.getZ() - 1 + DEFOREST_BUFFER;
        for (int x = defXMin; x <= defXMax; x++)
        {
            for (int z = defZMin; z <= defZMax; z++)
            {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int scanFrom = Math.min(top + 1, level.getMaxBuildHeight() - 1);
                int scanTo   = Math.max(top - 25, level.getMinBuildHeight());
                for (int y = scanFrom; y >= scanTo; y--)
                {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = level.getBlockState(p);
                    if (bs.is(BlockTags.LOGS) || bs.is(BlockTags.LEAVES))
                    {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        treeRemoved++;
                    }
                }
            }
        }

        // === Выравнивание с feather ===
        long filled = 0, removed = 0;
        int alignXMin = originX - EDGE_FEATHER;
        int alignXMax = originX + size.getX() - 1 + EDGE_FEATHER;
        int alignZMin = originZ - EDGE_FEATHER;
        int alignZMax = originZ + size.getZ() - 1 + EDGE_FEATHER;
        for (int x = alignXMin; x <= alignXMax; x++)
        {
            for (int z = alignZMin; z <= alignZMax; z++)
            {
                int distEdge = 0;
                if (x < originX)                   distEdge = Math.max(distEdge, originX - x);
                if (x > originX + size.getX() - 1) distEdge = Math.max(distEdge, x - (originX + size.getX() - 1));
                if (z < originZ)                   distEdge = Math.max(distEdge, originZ - z);
                if (z > originZ + size.getZ() - 1) distEdge = Math.max(distEdge, z - (originZ + size.getZ() - 1));

                int columnY;
                if (distEdge == 0)
                {
                    columnY = targetY;
                }
                else
                {
                    int natural = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    float weight = 1.0f - (float) distEdge / (EDGE_FEATHER + 1);
                    columnY = Math.round(targetY * weight + natural * (1.0f - weight));
                }

                int currentTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (currentTop < columnY)
                {
                    for (int y = currentTop; y < columnY - 1; y++)
                    {
                        level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                        filled++;
                    }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    filled++;
                }
                else if (currentTop >= columnY)
                {
                    for (int y = columnY; y <= currentTop; y++)
                    {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                        removed++;
                    }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }

                for (int y = columnY - FILL_DEPTH; y < columnY - 1; y++)
                {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isAir())
                    {
                        level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
                        filled++;
                    }
                }
            }
        }

        // === Placement template ===
        BlockPos templatePos = new BlockPos(originX, targetY, originZ);
        StructurePlaceSettings settings = new StructurePlaceSettings();
        boolean ok = template.placeInWorld(level, templatePos, templatePos, settings, level.getRandom(), 2);
        if (!ok)
        {
            PepelEdema.LOGGER.error("[village] placeInWorld для {} вернул false", id);
            return false;
        }

        PepelEdema.LOGGER.info("[village] {} поставлен: pivot=({},{},{}), bbox=[{}..{}, {}..{}], trees={}, fill={}, remove={}",
                id, pivotX, targetY, pivotZ,
                originX, originX + size.getX() - 1,
                originZ, originZ + size.getZ() - 1,
                treeRemoved, filled, removed);
        return true;
    }

    /** Сводка сэмплинга bbox: перепад высот + доля сэмплов на воде. */
    private static class AreaScan
    {
        int delta;
        double waterRatio;
    }

    /**
     * Сэмплирует bbox side×side вокруг (cx,cz): перепад heightmap, доля воды, доля незагруженных
     * чанков (где heightmap == minBuildHeight). Если незагруженных слишком много — кандидат
     * считаем "не валидным" (delta=MAX_VALUE).
     */
    private static AreaScan scanArea(ServerLevel level, int cx, int cz, int side)
    {
        int half = side / 2;
        int minH = level.getMinBuildHeight();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int total = 0, water = 0, unloaded = 0;
        for (int x = cx - half; x <= cx + half; x += HEIGHTMAP_STEP)
        {
            for (int z = cz - half; z <= cz + half; z += HEIGHTMAP_STEP)
            {
                // findGroundY: реальная земля без учёта стволов деревьев. Иначе delta
                // надувалась бы за счёт разной высоты деревьев в лесу, и алгоритм бы
                // предпочитал безлесные холмы лесистым равнинам.
                int y = findGroundY(level, x, z);
                total++;
                if (y == minH)
                {
                    // Незагруженный чанк — heightmap фиктивен. Не учитываем в delta/water.
                    unloaded++;
                    continue;
                }
                if (y < min) min = y;
                if (y > max) max = y;
                BlockPos surface = new BlockPos(x, y - 1, z);
                BlockState bs = level.getBlockState(surface);
                if (bs.is(Blocks.WATER) || !bs.getFluidState().isEmpty())
                {
                    water++;
                }
            }
        }
        AreaScan s = new AreaScan();
        // Если >50% сэмплов из незагруженных чанков — считаем кандидата невалидным.
        if (unloaded * 2 > total || min == Integer.MAX_VALUE)
        {
            s.delta = Integer.MAX_VALUE;
            s.waterRatio = 1.0;
            return s;
        }
        s.delta = max - min;
        // waterRatio считаем от ВАЛИДНЫХ сэмплов (не считая unloaded).
        int valid = total - unloaded;
        s.waterRatio = valid == 0 ? 1.0 : (double) water / valid;
        return s;
    }

    /**
     * Форсированно загружает все чанки в квадрате side×side вокруг (cx, cz) до ChunkStatus.SURFACE
     * (heightmap уже валидный к этому моменту, structure/decoration ещё нет — это быстрее чем FULL).
     * Без этого heightmap для удалённых от игрока чанков возвращает minBuildHeight.
     */
    private static void forceLoadChunks(ServerLevel level, int cx, int cz, int side)
    {
        int half = side / 2;
        int chunkXMin = (cx - half) >> 4;
        int chunkXMax = (cx + half) >> 4;
        int chunkZMin = (cz - half) >> 4;
        int chunkZMax = (cz + half) >> 4;
        int loaded = 0;
        for (int chunkX = chunkXMin; chunkX <= chunkXMax; chunkX++)
        {
            for (int chunkZ = chunkZMin; chunkZ <= chunkZMax; chunkZ++)
            {
                level.getChunk(chunkX, chunkZ, ChunkStatus.SURFACE, true);
                loaded++;
            }
        }
        PepelEdema.LOGGER.info("[village] force-loaded {} чанков в bbox вокруг ({}, {})", loaded, cx, cz);
    }

    private static List<Building> loadManifest(ServerLevel level)
    {
        ResourceManager rm = level.getServer().getResourceManager();
        Optional<Resource> resOpt = rm.getResource(MANIFEST_ID);
        if (resOpt.isEmpty())
        {
            PepelEdema.LOGGER.error("[village] manifest не найден: {}", MANIFEST_ID);
            return null;
        }
        try (Reader r = resOpt.get().openAsReader())
        {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("buildings");
            List<Building> out = new ArrayList<>();
            for (JsonElement e : arr)
            {
                JsonObject o = e.getAsJsonObject();
                Building b = new Building();
                b.id = o.get("id").getAsString();
                b.ox = o.get("ox").getAsInt();
                b.oz = o.get("oz").getAsInt();
                out.add(b);
            }
            return out;
        }
        catch (Exception e)
        {
            PepelEdema.LOGGER.error("[village] ошибка парсинга manifest", e);
            return null;
        }
    }

    private static class Building
    {
        String id;
        int ox, oz;
    }
}

package com.pepel.edema.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pepel.edema.PepelEdema;
import com.pepel.edema.event.StoryNpcSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * Точка входа: запускает фоновую генерацию деревни. Сама работа делается в state-machine
     * Task, разбитой по тикам через MinecraftServer.tell(TickTask) — мир продолжает тикать,
     * игрок не видит зависания. См. Task ниже про конкретные фазы.
     */
    public static void tryPlaceVillage(ServerLevel level, BlockPos islandCenter, BlockPos fishermanPos)
    {
        VillagePriyutState savedState = VillagePriyutState.get(level);
        if (savedState.isSpawned())
        {
            PepelEdema.LOGGER.info("[village] уже заспавнена в {}, пропускаем", savedState.getPivot());
            return;
        }
        new Task(level, islandCenter, fishermanPos, savedState).start();
    }

    /**
     * State-machine генератор деревни. Каждая фаза = один (или несколько) серверных тиков
     * через server.tell(TickTask). Между тиками сервер обрабатывает мобов, физику, события —
     * игрок не видит фриза.
     *
     * Phase-0  setup        : вектор за рыбака, список чанков для предзагрузки.
     * Phase-1  preload      : N чанков/тик до полного покрытия зоны FLAT_SEARCH (самая длинная
     *                         фаза — типично 22-28 секунд при 2 чанка/тик, 1122 чанка).
     *                         CHUNKS_PER_TICK адаптивный: 2 если игрок далеко, 50 (форс) если
     *                         подошёл ближе SAFETY_DISTANCE — иначе увидит недостроенную деревню.
     * Phase-2  manifest     : чтение priyut.json (1 ms).
     * Phase-3  flat-search  : поиск плоского биома (122 ms — лёгкий пик TPS, один тик).
     * Phase-4  prepare-plans: force-load #2 + preparePlan (5 ms).
     * Phase-5  pass1        : heightmap общей зоны (16 ms).
     * Phase-6  pass2        : deforest (64 ms).
     * Phase-7  pass3        : выравнивание земли (10 ms).
     * Phase-8  pass4        : placement шаблонов (62 ms).
     * Phase-9  finalize     : pass5 (дороги) + pass6 (NPCs) + savedData (10 ms).
     */
    private static class Task
    {
        /**
         * Сколько FORCED-тикетов ставим за один тик. Хотя сам setChunkForced дешёвый,
         * каждый тикет инициирует chunk gen pipeline которому нужны main-thread слоты
         * для FEATURES (деревья, структуры). Опытным путём:
         *   200/тик → 7.5 сек "Can't keep up" (main-thread queue overflow)
         *   30/тик  → 5.25 сек "Can't keep up" (всё ещё много для тяжёлых биомов)
         *   15/тик  → компромисс: 1122/15 ≈ 75 тиков постановки (≈4 сек),
         *             preload ~25-30 сек wall-time, лаги ожидаются ≈2-3 сек.
         */
        private static final int TICKETS_PER_TICK = 15;
        /** Если ближайший игрок ближе этого расстояния до bestPivot — врубаем форс. */
        private static final int SAFETY_DISTANCE = 100;
        /**
         * Минимальная дистанция от рыбака до bestPivot. FLAT_SEARCH ищет в радиусе ±192
         * от target=fisherman+dir*300, в худшем случае (направление назад) кандидат может
         * оказаться в 108 блоках от рыбака — игрок увидит деревню "перед мордой" с пирса.
         * 200 даёт деревню за горизонтом render-distance даже на чанковом стриме 16 чанков.
         */
        private static final int MIN_DISTANCE_FROM_FISHERMAN_SQ = 200 * 200;

        private final ServerLevel level;
        private final BlockPos islandCenter, fishermanPos;
        private final VillagePriyutState savedState;
        private final long tStart;
        private long tPhase;
        private int phase = 0;

        // setup → preload
        private int targetX, targetZ;
        private int loadSide;
        private int[] chunkCoords;     // pairs flat: cx0, cz0, cx1, cz1...
        private int chunkCursor;
        private int chunksTotal;

        // flat-search → prepare
        private int bestPivotX, bestPivotZ;
        private List<Building> buildings;
        private List<BuildingPlan> plans;

        // pass1
        private Map<Long, Integer> heightMap;

        // pass4
        private int placed;

        // pass5
        private BuildingPlan wellPlan;

        Task(ServerLevel level, BlockPos islandCenter, BlockPos fishermanPos, VillagePriyutState savedState)
        {
            this.level = level;
            this.islandCenter = islandCenter;
            this.fishermanPos = fishermanPos;
            this.savedState = savedState;
            this.tStart = System.currentTimeMillis();
            this.tPhase = tStart;
        }

        void start()
        {
            scheduleNext();
        }

        private void scheduleNext()
        {
            MinecraftServer server = level.getServer();
            if (server == null) return;
            server.tell(new TickTask(server.getTickCount() + 1, this::tick));
        }

        private void tick()
        {
            if (phase < 0) return;
            try
            {
                switch (phase)
                {
                    case 0: setup();         break;
                    case 1: preloadChunks(); break;
                    case 2: loadManifest_(); break;
                    case 3: flatSearch();    break;
                    case 4: preparePlans();  break;
                    case 5: pass1();         break;
                    case 6: pass2();         break;
                    case 7: pass3();         break;
                    case 8: pass4();         break;
                    case 9: pass5_6_finalize(); break;
                    default: return;
                }
            }
            catch (Exception e)
            {
                PepelEdema.LOGGER.error("[village] task crashed at phase {}", phase, e);
                releaseForcedChunks();
                phase = -1;
                return;
            }
            if (phase >= 0 && phase <= 9) scheduleNext();
        }

        private void logTiming(String name)
        {
            long now = System.currentTimeMillis();
            PepelEdema.LOGGER.info("[village] timing: {} = {}ms", name, now - tPhase);
            tPhase = now;
        }

        // ===== Phase 0: вычисляем target и список чанков =====
        private void setup()
        {
            double dx = fishermanPos.getX() - islandCenter.getX();
            double dz = fishermanPos.getZ() - islandCenter.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0)
            {
                PepelEdema.LOGGER.warn("[village] рыбак почти в центре острова (len={}), пропускаем", len);
                phase = -1;
                return;
            }
            targetX = (int) (fishermanPos.getX() + dx / len * DISTANCE_FROM_FISHERMAN);
            targetZ = (int) (fishermanPos.getZ() + dz / len * DISTANCE_FROM_FISHERMAN);
            loadSide = 2 * FLAT_SEARCH_RADIUS + FLATNESS_BBOX_SIDE;

            int half = loadSide / 2;
            int cxMin = (targetX - half) >> 4, cxMax = (targetX + half) >> 4;
            int czMin = (targetZ - half) >> 4, czMax = (targetZ + half) >> 4;
            int cntX = cxMax - cxMin + 1, cntZ = czMax - czMin + 1;
            chunksTotal = cntX * cntZ;
            chunkCoords = new int[chunksTotal * 2];
            int i = 0;
            for (int cx = cxMin; cx <= cxMax; cx++)
            {
                for (int cz = czMin; cz <= czMax; cz++)
                {
                    chunkCoords[i++] = cx;
                    chunkCoords[i++] = cz;
                }
            }
            chunkCursor = 0;
            PepelEdema.LOGGER.info("[village] async setup: target=({},?,{}), {} чанков предзагрузить ({}x{} зона)",
                    targetX, targetZ, chunksTotal, loadSide, loadSide);
            phase = 1;
        }

        // ===== Phase 1: async-предзагрузка через FORCED-тикеты =====
        // Шаг A: ставим FORCED-тикет на каждый чанк (TICKETS_PER_TICK/тик). Это не sync
        //        chunk gen — только добавление ticket в DistanceManager (~микросекунды),
        //        chunk gen происходит в worker pool параллельно server thread.
        // Шаг B: ждём пока все чанки попадут в level.hasChunk() — это значит chunk gen
        //        дошёл до FULL и chunk доступен для блочных операций.
        // Server thread на этой фазе вообще не блокируется — лагать не должно.
        private void preloadChunks()
        {
            // Step A: пакетно ставим тикеты пока не закроем все
            int requested = 0;
            while (chunkCursor < chunksTotal && requested < TICKETS_PER_TICK)
            {
                int cx = chunkCoords[chunkCursor * 2];
                int cz = chunkCoords[chunkCursor * 2 + 1];
                level.setChunkForced(cx, cz, true);
                chunkCursor++;
                requested++;
            }
            // Step B: если все тикеты поставлены — проверяем готовность всех чанков.
            // hasChunk() = true когда chunk gen дошёл до FULL.
            // Safety net: если игрок близко к зоне, не ждём остатки — двигаемся дальше,
            // sync force-load #2 в Phase 4 догонит что нужно.
            if (chunkCursor >= chunksTotal)
            {
                int ready = 0;
                for (int i = 0; i < chunksTotal; i++)
                {
                    int cx = chunkCoords[i * 2];
                    int cz = chunkCoords[i * 2 + 1];
                    if (level.hasChunk(cx, cz)) ready++;
                }
                boolean playerNear = playerWithinSafetyDistance();
                if (ready >= chunksTotal || playerNear)
                {
                    logTiming("preload (" + ready + "/" + chunksTotal + " чанков async через FORCED-тикеты"
                            + (playerNear ? ", safety-net: игрок близко" : "") + ")");
                    phase = 2;
                }
                // иначе остаёмся в фазе 1, ждём готовности
            }
        }

        /**
         * Снимает все FORCED-тикеты, поставленные в Phase 1. Чанки возвращаются под
         * управление обычной системы выгрузки (если игрок не рядом — выгрузятся).
         * Вызывается в финале и при abort.
         */
        private void releaseForcedChunks()
        {
            if (chunkCoords == null) return;
            int releaseUpTo = Math.min(chunkCursor, chunksTotal);
            for (int i = 0; i < releaseUpTo; i++)
            {
                int cx = chunkCoords[i * 2];
                int cz = chunkCoords[i * 2 + 1];
                level.setChunkForced(cx, cz, false);
            }
        }

        /**
         * Safety net: если игрок зашёл в зону потенциальной деревни до завершения генерации —
         * форсим оставшиеся чанки большим батчем (короткий лаг лучше чем видеть как деревья
         * падают и здания "вырастают" из-под земли).
         * Использует bestPivot если он уже посчитан, иначе target — на ранних фазах оба
         * находятся в одной области.
         */
        private boolean playerWithinSafetyDistance()
        {
            int x = bestPivotX != 0 ? bestPivotX : targetX;
            int z = bestPivotZ != 0 ? bestPivotZ : targetZ;
            for (ServerPlayer p : level.players())
            {
                double pdx = p.getX() - x, pdz = p.getZ() - z;
                if (pdx * pdx + pdz * pdz < (double) SAFETY_DISTANCE * SAFETY_DISTANCE) return true;
            }
            return false;
        }

        // ===== Phase 2: manifest =====
        private void loadManifest_()
        {
            buildings = loadManifest(level);
            if (buildings == null || buildings.isEmpty())
            {
                PepelEdema.LOGGER.error("[village] manifest пуст или не загрузился, пропускаем");
                phase = -1;
                return;
            }
            PepelEdema.LOGGER.info("[village] manifest: {} зданий", buildings.size());
            logTiming("manifest");
            phase = 3;
        }

        // ===== Phase 3: FLAT_SEARCH =====
        private void flatSearch()
        {
            bestPivotX = targetX; bestPivotZ = targetZ;
            int bestScore = Integer.MAX_VALUE, bestDelta = Integer.MAX_VALUE;
            boolean bestInGoodBiome = false;
            int rejectedWater = 0, rejectedNear = 0, badBiomeCount = 0, candidatesTotal = 0;
            for (int ox = -FLAT_SEARCH_RADIUS; ox <= FLAT_SEARCH_RADIUS; ox += FLAT_SEARCH_STEP)
            {
                for (int oz = -FLAT_SEARCH_RADIUS; oz <= FLAT_SEARCH_RADIUS; oz += FLAT_SEARCH_STEP)
                {
                    candidatesTotal++;
                    int cx = targetX + ox, cz = targetZ + oz;
                    double dxF = cx - fishermanPos.getX();
                    double dzF = cz - fishermanPos.getZ();
                    if (dxF * dxF + dzF * dzF < MIN_DISTANCE_FROM_FISHERMAN_SQ)
                    {
                        rejectedNear++;
                        continue;
                    }
                    AreaScan scan = scanArea(level, cx, cz, FLATNESS_BBOX_SIDE);
                    if (scan.waterRatio > MAX_WATER_RATIO) { rejectedWater++; continue; }
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
            PepelEdema.LOGGER.info("[village] FLAT_SEARCH: target=({},?,{}), кандидатов={} (близко-к-рыбаку={}, вода-reject={}, плохой-биом={}), pivot=({},?,{}) (сдвиг dx={},dz={}), bbox-delta={}, biome-ok={}",
                    targetX, targetZ, candidatesTotal, rejectedNear, rejectedWater, badBiomeCount,
                    bestPivotX, bestPivotZ,
                    bestPivotX - targetX, bestPivotZ - targetZ, bestDelta, bestInGoodBiome);
            if (bestScore == Integer.MAX_VALUE)
            {
                PepelEdema.LOGGER.warn("[village] все кандидаты на воде, деревня не поставлена.");
                phase = -1;
                return;
            }
            if (!bestInGoodBiome)
            {
                PepelEdema.LOGGER.warn("[village] хороший биом не найден в радиусе {} — деревня сядет в плохом ({} кандидатов из {} были в blacklist).",
                        FLAT_SEARCH_RADIUS, badBiomeCount, candidatesTotal);
            }
            logTiming("FLAT_SEARCH (" + candidatesTotal + " кандидатов)");
            phase = 4;
        }

        // ===== Phase 4: force-load #2 + preparePlan =====
        private void preparePlans()
        {
            forceLoadChunks(level, bestPivotX, bestPivotZ, 220);
            plans = new ArrayList<>();
            for (Building b : buildings)
            {
                BuildingPlan p = preparePlan(level, b, bestPivotX, bestPivotZ);
                if (p != null) plans.add(p);
            }
            logTiming("force-load #2 + preparePlan x" + buildings.size());
            phase = 5;
        }

        // ===== Phase 5: Pass 1 — heightmap =====
        private void pass1()
        {
            heightMap = new HashMap<>();
            for (BuildingPlan p : plans)
            {
                for (int x = p.originX; x <= p.maxX; x++)
                    for (int z = p.originZ; z <= p.maxZ; z++)
                        heightMap.put(key(x, z), p.targetY);
            }
            for (BuildingPlan p : plans)
            {
                for (int x = p.originX - EDGE_FEATHER; x <= p.maxX + EDGE_FEATHER; x++)
                {
                    for (int z = p.originZ - EDGE_FEATHER; z <= p.maxZ + EDGE_FEATHER; z++)
                    {
                        long k = key(x, z);
                        if (heightMap.containsKey(k)) continue;
                        int distEdge = 0;
                        if (x < p.originX) distEdge = Math.max(distEdge, p.originX - x);
                        if (x > p.maxX)    distEdge = Math.max(distEdge, x - p.maxX);
                        if (z < p.originZ) distEdge = Math.max(distEdge, p.originZ - z);
                        if (z > p.maxZ)    distEdge = Math.max(distEdge, z - p.maxZ);
                        if (distEdge == 0 || distEdge > EDGE_FEATHER) continue;
                        int natural = medianGroundY(level, x, z, 1);
                        float weight = 1.0f - (float) distEdge / (EDGE_FEATHER + 1);
                        int columnY = Math.round(p.targetY * weight + natural * (1.0f - weight));
                        Integer prev = heightMap.get(k);
                        if (prev == null || columnY > prev) heightMap.put(k, columnY);
                    }
                }
            }
            PepelEdema.LOGGER.info("[village] heightmap построен: {} колонок", heightMap.size());
            logTiming("Pass 1 heightmap");
            phase = 6;
        }

        // ===== Phase 6: Pass 2 — deforest =====
        private void pass2()
        {
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
                            if (bs.is(BlockTags.LOGS)) allLogs.add(pos);
                            else if (bs.is(BlockTags.LEAVES) || bs.isAir()) { /* пропускаем */ }
                            else break;
                        }
                    }
                }
            }
            Set<BlockPos> logsToRemove = new HashSet<>();
            Set<BlockPos> survivingLogs = new HashSet<>();
            for (BlockPos pos : allLogs)
            {
                boolean inRemoveZone = false;
                for (BuildingPlan p : plans)
                {
                    if (pos.getX() >= p.originX - DEFOREST_BUFFER && pos.getX() <= p.maxX + DEFOREST_BUFFER &&
                        pos.getZ() >= p.originZ - DEFOREST_BUFFER && pos.getZ() <= p.maxZ + DEFOREST_BUFFER)
                    { inRemoveZone = true; break; }
                }
                if (inRemoveZone) logsToRemove.add(pos); else survivingLogs.add(pos);
            }
            Queue<BlockPos> bfsQueue = new ArrayDeque<>();
            Map<BlockPos, Integer> leafDepth = new HashMap<>();
            for (BlockPos log : logsToRemove)
            {
                for (Direction dir : Direction.values())
                {
                    BlockPos n = log.relative(dir);
                    if (logsToRemove.contains(n) || survivingLogs.contains(n) || leafDepth.containsKey(n)) continue;
                    if (level.getBlockState(n).is(BlockTags.LEAVES) && !isLeafProtected(n, survivingLogs))
                    { leafDepth.put(n, 1); bfsQueue.add(n); }
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
                    { leafDepth.put(n, d + 1); bfsQueue.add(n); }
                }
            }
            for (BlockPos pos : logsToRemove)       level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            for (BlockPos pos : leafDepth.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            PepelEdema.LOGGER.info("[village] deforest: всего логов={}, к-удалению={}, выжило={}, листьев удалено={}",
                    allLogs.size(), logsToRemove.size(), survivingLogs.size(), leafDepth.size());
            logTiming("Pass 2 deforest");
            phase = 7;
        }

        // ===== Phase 7: Pass 3 — выравнивание земли =====
        private void pass3()
        {
            long filled = 0, removed = 0;
            for (Map.Entry<Long, Integer> entry : heightMap.entrySet())
            {
                long k = entry.getKey();
                int x = (int) (k >> 32);
                int z = (int) (k & 0xFFFFFFFFL);
                int columnY = entry.getValue();
                int currentTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (currentTop < columnY)
                {
                    for (int y = currentTop; y < columnY - 1; y++)
                    { level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2); filled++; }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    filled++;
                }
                else if (currentTop >= columnY)
                {
                    for (int y = columnY; y <= currentTop; y++)
                    { level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2); removed++; }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int y = columnY - FILL_DEPTH; y < columnY - 1; y++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir())
                    { level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2); filled++; }
                }
            }
            PepelEdema.LOGGER.info("[village] выравнивание: filled={}, removed={}", filled, removed);
            logTiming("Pass 3 align");
            phase = 8;
        }

        // ===== Phase 8: Pass 4 — placement шаблонов =====
        private void pass4()
        {
            placed = 0;
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
            logTiming("Pass 4 placement (" + placed + "/" + plans.size() + ")");
            phase = 9;
        }

        // ===== Phase 9: Pass 5 (roads) + Pass 6 (NPCs) + finalize =====
        private void pass5_6_finalize()
        {
            wellPlan = null;
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

            int npcsExpected = 0;
            for (BuildingPlan pl : plans) if (pl.npc != null) npcsExpected++;
            int npcsSpawned = 0;
            for (BuildingPlan p : plans)
            {
                if (p.npc == null) continue;
                BlockPos spawnPos = findFreeSpawnSpot(level, p);
                boolean ok = StoryNpcSpawner.spawnAt(level, spawnPos, 180.0f, p.npc);
                if (ok)
                {
                    npcsSpawned++;
                    PepelEdema.LOGGER.info("[village] NPC {} заспавнен в {} на {}", p.npc, p.id, spawnPos);
                }
                else
                {
                    PepelEdema.LOGGER.error("[village] NPC {} в {} не заспавнился как customnpcs", p.npc, p.id);
                }
            }
            PepelEdema.LOGGER.info("[village] story NPC: заспавнено {} из {}", npcsSpawned, npcsExpected);

            int finalY = wellPlan != null ? wellPlan.targetY : (plans.isEmpty() ? 64 : plans.get(0).targetY);
            BlockPos finalPivot = new BlockPos(bestPivotX, finalY, bestPivotZ);
            savedState.markSpawned(finalPivot);
            PepelEdema.LOGGER.info("[village] деревня поставлена. Зданий: {}/{}. Pivot={}",
                    placed, buildings.size(), finalPivot);
            // Снимаем FORCED-тикеты со всех чанков зоны FLAT_SEARCH — они дальше не нужны
            // нам, и могут выгружаться обычной системой когда игрок отойдёт.
            releaseForcedChunks();
            logTiming("Pass 5+6+finalize");
            PepelEdema.LOGGER.info("[village] timing: TOTAL = {}ms (async, мир не блокировался)",
                    System.currentTimeMillis() - tStart);
            phase = -1; // done
        }
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
                    if (hasTreeInColumn(level, px, pz)) continue;

                    int gy = findGroundY(level, px, pz) - 1;
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

    /**
     * True if the road brush would pass under a tree crown/trunk in this column.
     * Roads should bend around living terrain: do not break logs/leaves and do not
     * repaint the ground directly under them.
     */
    private static boolean hasTreeInColumn(ServerLevel level, int x, int z)
    {
        int groundY = findGroundY(level, x, z);
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = groundY; y <= topY; y++)
        {
            BlockState bs = level.getBlockState(new BlockPos(x, y, z));
            if (bs.is(BlockTags.LOGS) || bs.is(BlockTags.LEAVES)) return true;
        }
        return false;
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

    /**
     * Ищет свободную клетку для автоспавна NPC внутри здания.
     * Сканируем колонну Y ∈ [targetY-2, targetY+5] для каждого offset (от центра наружу):
     * walkable = пол (Y-1 непрозрачный) + 2 блока воздуха над ним (foot + head).
     * Это нужно потому что pivot может оказаться в фундаменте/плите/мебели:
     *   - ivar_house1 имеет cobblestone slab фундамент на templateY=1 → world Y=targetY,
     *     а пол комнаты начинается с templateY=2 → world Y=targetY+1.
     *   - В herb_house Y=targetY уже сразу пол → находится с первой попытки.
     * Если ничего не подошло — возвращаем pivot,targetY (NPC может оказаться в блоке,
     * но это лучше чем не заспавниться; такие случаи логируются и решаются правкой schem).
     */
    private static BlockPos findFreeSpawnSpot(ServerLevel level, BuildingPlan p)
    {
        int[][] offsets = {
                {0, 0},
                {1, 0}, {0, 1}, {-1, 0}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {0, 2}, {-2, 0}, {0, -2}
        };
        for (int[] off : offsets)
        {
            int x = p.pivotX + off[0];
            int z = p.pivotZ + off[1];
            for (int y = p.targetY - 2; y <= p.targetY + 5; y++)
            {
                if (isWalkable(level, x, y, z)) return new BlockPos(x, y, z);
            }
        }
        return new BlockPos(p.pivotX, p.targetY, p.pivotZ);
    }

    /**
     * walkable = блок под ногами (Y-1) непрозрачный, ноги (Y) и голова (Y+1) — воздух.
     * Использование isAir() для головы/ног отвергает любые блоки (включая полу-прозрачные
     * слэбы и лестницы), но это и нужно — NPC должен стоять, не пересекаясь с геометрией.
     */
    private static boolean isWalkable(ServerLevel level, int x, int y, int z)
    {
        BlockState floor = level.getBlockState(new BlockPos(x, y - 1, z));
        BlockState foot  = level.getBlockState(new BlockPos(x, y, z));
        BlockState head  = level.getBlockState(new BlockPos(x, y + 1, z));
        return !floor.isAir() && foot.isAir() && head.isAir();
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
        String npc;            // story NPC для автоспавна, null если не нужен
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
        p.npc = b.npc;
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

        // Sanity-фильтр: выбрасываем сэмплы на minBuildHeight — это битые heightmap'ы
        // незагруженных или недогруженных чанков (видели реальный случай:
        // ivar_house1 c targetY=-64, Pass 3 выкопал 76к блоков и обнажил стронгхолд).
        int minH = level.getMinBuildHeight();
        int rawSamples = heights.size();
        heights.removeIf(h -> h <= minH + 1);
        if (heights.isEmpty())
        {
            PepelEdema.LOGGER.error("[village] preparePlan({}): все {} сэмплов heightmap битые (= minBuildHeight). " +
                            "Чанки в bbox=[{}..{}, {}..{}] не загружены до нужного статуса. Здание пропущено.",
                    b.id, rawSamples, p.originX, p.maxX, p.originZ, p.maxZ);
            return null;
        }
        if (heights.size() < rawSamples)
        {
            PepelEdema.LOGGER.warn("[village] preparePlan({}): отброшено {} битых сэмплов из {}, осталось {}",
                    b.id, rawSamples - heights.size(), rawSamples, heights.size());
        }
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
                b.npc = o.has("npc") ? o.get("npc").getAsString() : null;
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
        String npc; // null если NPC в этом здании не нужен
    }
}

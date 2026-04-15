package com.pepel.edema.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class PepelConfig
{
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue COVERAGE_MIN;
    public static final ForgeConfigSpec.IntValue DISTANCE_MEAN;
    public static final ForgeConfigSpec.IntValue DISTANCE_TOLERANCE;
    public static final ForgeConfigSpec.IntValue SEARCH_RAY_STEP;
    public static final ForgeConfigSpec.IntValue NUM_RAYS;
    public static final ForgeConfigSpec.IntValue INNER_RADIUS;
    public static final ForgeConfigSpec.DoubleValue INNER_COVERAGE_MAX;

    public record Resolved(double coverageMin, int distMin, int distMax, int step, int numRays,
                           int innerRadius, double innerCoverageMax) {}

    private static volatile Resolved cached;

    static
    {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("placement");

        COVERAGE_MIN = b
                .comment("Минимальная доля лучей, упирающихся в берег, чтобы чанк был принят (0.0 – 1.0).")
                .defineInRange("coverage_min", 0.70, 0.0, 1.0);

        DISTANCE_MEAN = b
                .comment("Среднее целевое расстояние до берега в блоках.")
                .defineInRange("distance_mean", 400, 50, 4000);

        DISTANCE_TOLERANCE = b
                .comment("Допуск: проверяем радиусы [distance_mean - tolerance .. distance_mean + tolerance].")
                .defineInRange("distance_tolerance", 200, 0, 2000);

        SEARCH_RAY_STEP = b
                .comment("Шаг по радиусу (в блоках). Меньше шаг — точнее, дороже проверка.")
                .defineInRange("search_ray_step", 32, 4, 256);

        NUM_RAYS = b
                .comment("Количество лучей для сэмплинга (по окружности).")
                .defineInRange("num_rays", 16, 4, 64);

        INNER_RADIUS = b
                .comment("Внутренний радиус проверки пустоты в блоках: от 50 до inner_radius ищем сушу.",
                        "Если она там есть — это залив/озеро, отклоняем кандидат.")
                .defineInRange("inner_radius", 150, 0, 1000);

        INNER_COVERAGE_MAX = b
                .comment("Максимальная доля сэмплов на внутреннем радиусе, которые могут быть сушей (0.0 – 1.0).",
                        "Чем меньше, тем строже требование открытой воды вокруг острова.")
                .defineInRange("inner_coverage_max", 0.10, 0.0, 1.0);

        b.pop();
        SPEC = b.build();
    }

    public static Resolved resolved()
    {
        Resolved r = cached;
        if (r == null)
        {
            int mean = DISTANCE_MEAN.get();
            int tol = DISTANCE_TOLERANCE.get();
            r = new Resolved(
                    COVERAGE_MIN.get(),
                    Math.max(1, mean - tol),
                    mean + tol,
                    SEARCH_RAY_STEP.get(),
                    NUM_RAYS.get(),
                    INNER_RADIUS.get(),
                    INNER_COVERAGE_MAX.get()
            );
            cached = r;
        }
        return r;
    }

    public static void invalidateCache()
    {
        cached = null;
    }
}

package com.pepel.edema.book;

import com.pepel.edema.capability.Importance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр записей Книги Эрена. Каждая запись = id + importance + число строк.
 * Сами тексты лежат в assets/pepel/lang/<locale>.json по ключам
 * pepel.book.entry.<id>.title и pepel.book.entry.<id>.line<N> (N от 0).
 * Этот класс одинаково работает на сервере и клиенте — лор всегда на клиенте,
 * сервер шлёт только id записей и порядок их получения.
 */
public final class BookEntries
{
    public record Def(String id, Importance importance, int lineCount) {}

    private static final Map<String, Def> ALL = new LinkedHashMap<>();

    static
    {
        register("p0_awakening",       Importance.KEY,    4);
        register("p1_first_people",    Importance.NORMAL, 3);
        register("p2a_first_guardian", Importance.NORMAL, 2);
        register("p2b_eren_intro",     Importance.KEY,    3);
        register("p3a_council",        Importance.KEY,    4);
        register("p3b_shards_purpose", Importance.NORMAL, 2);
        register("p4_first_shard",     Importance.NORMAL, 3);
        register("p5_truth",           Importance.KEY,    5);
        register("p7_six_shards",      Importance.KEY,    2);
    }

    private BookEntries() {}

    private static void register(String id, Importance importance, int lines)
    {
        ALL.put(id, new Def(id, importance, lines));
    }

    public static Def get(String id)
    {
        return ALL.get(id);
    }

    public static boolean exists(String id)
    {
        return ALL.containsKey(id);
    }
}

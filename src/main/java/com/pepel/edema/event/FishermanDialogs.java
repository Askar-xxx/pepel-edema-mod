package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Копирует эталонные JSON-диалоги NPC из ресурсов мода в папку мира.
 * Копирует "только если файла нет" — чтобы не затирать правки Бришки в Custom NPC GUI.
 */
public class FishermanDialogs
{
    /** Категория диалогов. Нельзя использовать дефолтные "Villager"/"default" — конфликт со встроенными. */
    public static final String CATEGORY = "Pepel";

    /** ID диалогов из эталонной настройки Бришки. */
    public static final int DIALOG_START_ID   = 4;  // "Встреча"  — главное меню
    public static final int DIALOG_WHO_ID     = 5;  // "Кто ты?"
    public static final int DIALOG_VILLAGE_ID = 6;  // "О деревне"
    public static final int DIALOG_BYE_ID     = 7;  // "Пока"
    public static final int DIALOG_WHO_END_ID     = 8;
    public static final int DIALOG_VILLAGE_END_ID = 9;

    /** ID диалогов Ивара. */
    public static final int IVAR_START_ID = 10;
    public static final int IVAR_WHO_ID   = 11;
    public static final int IVAR_RUINS_ID = 12;
    public static final int IVAR_BYE_ID   = 13;
    public static final int IVAR_WHO_END_ID   = 14;
    public static final int IVAR_RUINS_END_ID = 15;

    /** ID диалогов Лии. */
    public static final int LIA_START_ID = 20;
    public static final int LIA_WHO_ID   = 21;
    public static final int LIA_ROOTS_ID = 22;
    public static final int LIA_BYE_ID   = 23;
    public static final int LIA_WHO_END_ID   = 24;
    public static final int LIA_ROOTS_END_ID = 25;

    /** ID диалогов Марты. */
    public static final int MARTA_START_ID = 30;
    public static final int MARTA_WHO_ID   = 31;
    public static final int MARTA_WORK_ID  = 32;
    public static final int MARTA_BYE_ID   = 33;
    public static final int MARTA_WHO_END_ID  = 34;
    public static final int MARTA_WORK_END_ID = 35;

    private static final int[] DIALOG_IDS = {
            4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15,
            20, 21, 22, 23, 24, 25,
            30, 31, 32, 33, 34, 35
    };
    private static final int[] MANAGED_DIALOG_IDS = DIALOG_IDS;

    public static void write(MinecraftServer server)
    {
        Path dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("customnpcs/dialogs/" + CATEGORY);
        try
        {
            Files.createDirectories(dir);
            for (int id : DIALOG_IDS)
            {
                Path file = dir.resolve(id + ".json");
                if (Files.exists(file) && !isManagedDialog(id))
                {
                    PepelEdema.LOGGER.debug("Диалог {} уже есть в мире, пропускаю", id);
                    continue;
                }
                String content = readResource("/assets/pepel/dialogs/" + id + ".json");
                if (content == null)
                {
                    PepelEdema.LOGGER.warn("Ресурс диалога {}.json не найден в моде", id);
                    continue;
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
                PepelEdema.LOGGER.info("Диалог {} скопирован из ресурсов в {}", id, file);
            }
        }
        catch (IOException e)
        {
            PepelEdema.LOGGER.error("Не удалось записать диалоги рыбака: {}", e.getMessage());
        }
    }

    private static boolean isManagedDialog(int id)
    {
        for (int managedId : MANAGED_DIALOG_IDS)
        {
            if (managedId == id)
            {
                return true;
            }
        }
        return false;
    }

    private static String readResource(String path)
    {
        try (InputStream in = FishermanDialogs.class.getResourceAsStream(path))
        {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            PepelEdema.LOGGER.error("Ошибка чтения ресурса {}: {}", path, e.getMessage());
            return null;
        }
    }
}

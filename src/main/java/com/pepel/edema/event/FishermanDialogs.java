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
 * Копирует эталонные JSON-диалоги рыбака из ресурсов мода в папку мира.
 * Копирует "только если файла нет" — чтобы не затирать правки Бришки в Custom NPC GUI.
 */
public class FishermanDialogs
{
    /** Категория диалогов рыбака. Нельзя использовать дефолтные "Villager"/"default" — конфликт со встроенными. */
    public static final String CATEGORY = "Pepel";

    /** ID диалогов из эталонной настройки Бришки. */
    public static final int DIALOG_START_ID   = 4;  // "Встреча"  — главное меню
    public static final int DIALOG_WHO_ID     = 5;  // "Кто ты?"
    public static final int DIALOG_VILLAGE_ID = 6;  // "О деревне"
    public static final int DIALOG_BYE_ID     = 7;  // "Пока"

    private static final int[] DIALOG_IDS = { 4, 5, 6, 7 };

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
                if (Files.exists(file))
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

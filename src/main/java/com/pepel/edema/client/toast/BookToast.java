package com.pepel.edema.client.toast;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.item.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BookToast implements Toast
{
    private static final long DURATION_MS = 5000L;
    private static final int COLOR_TITLE    = 0xFF7A1800;
    private static final int COLOR_SUBTITLE = 0xFF1A0E00;

    private final ItemStack icon;
    private final Importance importance;
    private final String entryId;

    public BookToast(String entryId, Importance importance)
    {
        this.icon = new ItemStack(ModItems.BOOK_OF_EREN.get());
        this.importance = importance;
        this.entryId = entryId;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long timeShown)
    {
        g.blit(TEXTURE, 0, 0, 0, 32, this.width(), this.height());

        Component title = Component.translatable("toast.pepel.book.title");
        // Подзаголовок — название конкретной записи, если она есть в локализации;
        // иначе общий "Появилась новая запись" (back-compat для команды /pepel book notify
        // с произвольным entryId, не зарегистрированным в BookEntries).
        String titleKey = "pepel.book.entry." + entryId + ".title";
        Component subtitle = Component.translatable(titleKey);
        // Эвристика: если перевод не нашёлся, translatable вернёт ключ как текст.
        // Тогда показываем общий fallback.
        if (subtitle.getString().equals(titleKey))
        {
            subtitle = Component.translatable("toast.pepel.book.subtitle");
        }

        g.drawString(toasts.getMinecraft().font, title, 30, 7, COLOR_TITLE, false);
        g.drawString(toasts.getMinecraft().font, subtitle, 30, 18, COLOR_SUBTITLE, false);

        g.renderFakeItem(icon, 8, 8);

        return timeShown >= DURATION_MS ? Visibility.HIDE : Visibility.SHOW;
    }
}

package com.pepel.edema.client.render;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.client.ClientBookState;
import com.pepel.edema.item.ModItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

@OnlyIn(Dist.CLIENT)
public class BookDecorator implements IItemDecorator
{
    private static final int PERIOD_MS = 1200;
    private static final int COLOR_NORMAL = 0xCC7A30;
    private static final int COLOR_KEY    = 0xCC2020;

    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y)
    {
        if (!stack.is(ModItems.BOOK_OF_EREN.get())) return false;
        if (ClientBookState.isEmpty()) return false;

        Importance highest = ClientBookState.highest();
        int rgb = (highest == Importance.KEY) ? COLOR_KEY : COLOR_NORMAL;

        long now = System.currentTimeMillis();
        float t = (now % PERIOD_MS) / (float) PERIOD_MS;
        float alpha = 0.5f + 0.2f * (float) Math.sin(t * 2.0 * Math.PI);
        int alphaByte = Math.round(alpha * 255f) & 0xFF;
        int color = (alphaByte << 24) | rgb;

        g.fill(x, y, x + 16, y + 16, color);
        return false;
    }
}

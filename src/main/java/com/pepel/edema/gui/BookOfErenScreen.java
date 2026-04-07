package com.pepel.edema.gui;

import com.pepel.edema.PepelEdema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class BookOfErenScreen extends Screen
{
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(PepelEdema.MODID, "textures/gui/book_of_eren.png");

    private static final int TEX_W = 512;
    private static final int TEX_H = 256;
    private static final int W     = 320;
    private static final int H     = 192;

    // Текстовые области (от bx/by)
    private static final int L_X   = 16;   // левая страница
    private static final int R_X   = 166;  // правая страница
    private static final int PY    = 36;   // начало текста (под заголовком)
    private static final int T_Y   = 16;   // заголовок страницы
    private static final int L_CX  = 80;   // центр левой страницы
    private static final int R_CX  = 238;  // центр правой страницы

    // Вкладки: 0=Лор, 1=Осколки
    private int tab = 0;
    private static final String[] TAB_NAMES = {"Лор", "Осколки"};

    private static final int[] TAB_BASE   = {0xFF6B3A0A, 0xFF1A2A7A};
    private static final int[] TAB_ACTIVE = {0xFFB06818, 0xFF2B4ABB};
    private static final int[] TAB_BRIGHT = {0xFFD4934A, 0xFF4A70D8};

    private static final int COLOR_TEXT  = 0xFF2A1800;
    private static final int COLOR_TITLE = 0xFF7A3A00;
    private static final int COLOR_HAS   = 0xFF1A6B1A;
    private static final int COLOR_MISS  = 0xFF888888;

    private static final List<String> SHARD_IDS = List.of(
            "pepel:shard_twilight",
            "pepel:shard_aether",
            "pepel:shard_blue_skies",
            "pepel:shard_undergarden",
            "pepel:shard_deeper",
            "pepel:shard_final"
    );

    // Короткие названия, чтобы влезли в 130px страницу
    private static final List<String> SHARD_NAMES = List.of(
            "Тенара — Сум. лес",
            "Кельвин — Эфир",
            "Ор/Ноа — Небеса",
            "Морна — Глубины",
            "Зерах — Изнанка",
            "Нара — Ниж. мир"
    );

    public BookOfErenScreen()
    {
        super(Component.empty());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta)
    {
        renderBackground(g);

        int bx = (width  - W) / 2;
        int by = (height - H) / 2;

        g.blit(TEXTURE, bx, by, 0, 0, W, H, TEX_W, TEX_H);

        drawSideTabs(g, bx, by, mx, my);

        switch (tab)
        {
            case 0 -> drawLorPages(g, bx, by);
            case 1 -> drawShardsPages(g, bx, by);
        }

        super.render(g, mx, my, delta);
    }

    // ─── Лор ──────────────────────────────────────────────
    private void drawLorPages(GuiGraphics g, int bx, int by)
    {
        // Заголовки
        g.drawCenteredString(font, "Лор", bx + L_CX, by + T_Y, COLOR_TITLE);
        g.drawCenteredString(font, "Миры", bx + R_CX, by + T_Y, COLOR_TITLE);

        int lx = bx + L_X, ly = by + PY;
        int rx = bx + R_X, ry = by + PY;

        // Левая — история Эрена
        line(g, lx, ly,      "Эрен — первый");
        line(g, lx, ly+12,   "Садовник, хранитель");
        line(g, lx, ly+24,   "Первоцвета.");
        line(g, lx, ly+42,   "Первоцвет угас.");
        line(g, lx, ly+54,   "Шесть осколков");
        line(g, lx, ly+66,   "рассеялись по мирам.");
        line(g, lx, ly+84,   "Каждый хранит");
        line(g, lx, ly+96,   "свой Садовник.");
        line(g, lx, ly+114,  "Собери все шесть —");
        line(g, lx, ly+126,  "пламя вернётся.");

        // Правая — список миров
        line(g, rx, ry,      "1. Сумеречный лес");
        line(g, rx, ry+14,   "2. Эфир");
        line(g, rx, ry+28,   "3. Blue Skies");
        line(g, rx, ry+42,   "4. Глубины");
        line(g, rx, ry+56,   "5. Изнанка");
        line(g, rx, ry+70,   "6. Нижний мир");
        line(g, rx, ry+90,   "— Лия");
    }

    // ─── Осколки ──────────────────────────────────────────
    private void drawShardsPages(GuiGraphics g, int bx, int by)
    {
        Player player = Minecraft.getInstance().player;

        g.drawCenteredString(font, "Осколки", bx + L_CX, by + T_Y, COLOR_TITLE);

        int lx = bx + L_X, ly = by + PY;
        int rx = bx + R_X, ry = by + PY;

        // Левая — чеклист
        for (int i = 0; i < 6; i++)
        {
            boolean has = player != null && hasShard(player, SHARD_IDS.get(i));
            g.drawString(font,
                    (has ? "* " : "o ") + SHARD_NAMES.get(i),
                    lx, ly + i * 20,
                    has ? COLOR_HAS : COLOR_MISS, false);
        }

        // Правая — итог и подсказка
        int count = countShards(player);
        g.drawCenteredString(font, count + " / 6", bx + R_CX, ry, COLOR_TITLE);

        if (count == 6) {
            line(g, rx, ry+20, "Все собраны!");
            line(g, rx, ry+32, "Первоцвет ждёт.");
        } else {
            line(g, rx, ry+20, "Победи хранителя");
            line(g, rx, ry+32, "каждого мира,");
            line(g, rx, ry+44, "чтобы получить");
            line(g, rx, ry+56, "осколок.");
        }
    }

    // ─── Вкладки ──────────────────────────────────────────
    private void drawSideTabs(GuiGraphics g, int bx, int by, int mx, int my)
    {
        int stubX = bx + W - 4;

        // Рисуем сначала свёрнутые, потом развёрнутые (Z-порядок)
        for (int pass = 0; pass < 2; pass++)
        {
            for (int i = 0; i < 2; i++)
            {
                int ty   = by + 24 + i * 68;
                int tabH = tab == i ? 48 : 42;
                int oty  = ty + (tab == i ? 0 : 3);

                boolean hovered  = mx >= stubX && mx <= stubX + 50
                        && my >= oty && my <= oty + tabH;
                boolean expanded = (tab == i) || hovered;

                if (pass == 0 && expanded)  continue;
                if (pass == 1 && !expanded) continue;

                int cMain   = (tab == i)  ? TAB_ACTIVE[i]
                            : hovered     ? TAB_BRIGHT[i]
                            :               TAB_BASE[i];

                if (expanded)
                {
                    // Развёрнутая — прямоугольник + кончик + текст
                    int nameW = font.width(TAB_NAMES[i]);
                    int bw    = nameW + 14;
                    int tx    = stubX;

                    g.fill(tx,     oty,      tx+bw,   oty+tabH,    cMain);
                    g.fill(tx+bw,  oty+5,    tx+bw+4, oty+tabH-5,  cMain);
                    g.fill(tx+bw+4,oty+10,   tx+bw+8, oty+tabH-10, cMain);

                    // Подсветка
                    g.fill(tx+1, oty+1, tx+bw-1, oty+tabH-1, TAB_BRIGHT[i]);

                    // Название
                    g.drawString(font, TAB_NAMES[i],
                            tx + 7, oty + (tabH - 8) / 2,
                            0xFF3A1000, false);
                }
                else
                {
                    // Свёрнутая — маленький кружочек/нашлёпка
                    g.fill(stubX,    oty+6,  stubX+8, oty+tabH-6, cMain);
                    g.fill(stubX+8,  oty+10, stubX+11,oty+tabH-10, cMain);
                }
            }
        }
    }

    // ─── Вспомогательные ──────────────────────────────────
    private void line(GuiGraphics g, int x, int y, String text)
    {
        g.drawString(font, text, x, y, COLOR_TEXT, false);
    }

    private boolean hasShard(Player player, String itemId)
    {
        String[] parts = itemId.split(":");
        for (ItemStack stack : player.getInventory().items)
        {
            if (stack.isEmpty()) continue;
            var loc = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (loc != null
                    && loc.getNamespace().equals(parts[0])
                    && loc.getPath().equals(parts[1]))
                return true;
        }
        return false;
    }

    private int countShards(Player player)
    {
        if (player == null) return 0;
        return (int) SHARD_IDS.stream().filter(id -> hasShard(player, id)).count();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        int bx = (width  - W) / 2;
        int by = (height - H) / 2;
        int stubX = bx + W - 4;

        for (int i = 0; i < 2; i++)
        {
            int ty   = by + 24 + i * 68;
            int tabH = tab == i ? 48 : 42;
            int oty  = ty + (tab == i ? 0 : 3);

            if (mx >= stubX && mx <= stubX + 60 && my >= oty && my <= oty + tabH)
            {
                tab = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

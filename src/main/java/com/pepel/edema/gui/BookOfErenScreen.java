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

    // Позиции относительно bx/by
    private static final int L_X  = 12;   // левая страница, текст X
    private static final int R_X  = 170;  // правая страница, текст X
    private static final int T_Y  = 10;   // заголовок Y (в полосе хедера)
    private static final int PY   = 36;   // контент Y (ниже разделителя)
    private static final int L_CX = 80;   // центр левой страницы
    private static final int R_CX = 242;  // центр правой страницы

    private int tab           = 0;
    private int selectedWorld = 0;   // первый мир открыт по умолчанию

    private static final String[] TAB_NAMES = {"Лор", "Осколки"};
    // Оба таба в тёплой кожаной гамме — Лор темнее, Осколки чуть светлее
    private static final int[] TAB_BASE   = {0xFF5C2E08, 0xFF7A4010};
    private static final int[] TAB_ACTIVE = {0xFF9C5214, 0xFFB86A20};
    private static final int[] TAB_BRIGHT = {0xFFCC7A30, 0xFFE09040};

    private static final int COLOR_TEXT  = 0xFF1A0E00;   // тёмно-коричневый, чёткий на пергаменте
    private static final int COLOR_TITLE = 0xFF5C2800;   // заголовок — насыщенный
    private static final int COLOR_HAS   = 0xFF1A5C1A;   // есть осколок — зелёный
    private static final int COLOR_MISS  = 0xFF7A6A55;   // нет — приглушённый, в тоне пергамента
    private static final int COLOR_SEL   = 0xFF7A1800;   // выбранный мир — бордо

    // ─── Данные миров ─────────────────────────────────────
    private static final List<String> SHARD_IDS = List.of(
            "pepel:shard_twilight", "pepel:shard_aether",  "pepel:shard_blue_skies",
            "pepel:shard_undergarden", "pepel:shard_deeper", "pepel:shard_nether"
    );

    private static final List<String> WORLD_NAMES = List.of(
            "Twilight Forest", "Aether", "Blue Skies",
            "Undergarden", "Deeper Dark", "Нижний мир"
    );

    // Фрагменты по мирам (пусто = сразу осколок)
    private static final List<List<String>> WORLD_FRAGMENTS = List.of(
            List.of("pepel:fragment_naga", "pepel:fragment_lich", "pepel:fragment_hydra",
                    "pepel:fragment_urghast", "pepel:fragment_snow_queen"),
            List.of("pepel:fragment_slider", "pepel:fragment_valkyrie", "pepel:fragment_sun_spirit"),
            List.of("pepel:fragment_summoner", "pepel:fragment_starlit",
                    "pepel:fragment_alchemist", "pepel:fragment_arachnarch"),
            List.of(),
            List.of(),
            List.of()
    );

    // Боссы по мирам
    private static final List<List<String>> WORLD_BOSSES = List.of(
            List.of("Нага", "Лич", "Гидра", "Ур-Гаст", "Снежная Королева"),
            List.of("Slider", "Valkyrie Queen", "Sun Spirit"),
            List.of("Summoner", "Starlit Crusher", "Alchemist", "Arachnarch"),
            List.of("Забытый Страж"),
            List.of("Warden"),
            List.of("Иссушитель")
    );

    public BookOfErenScreen() { super(Component.empty()); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta)
    {
        renderBackground(g);

        int bx = (width  - W) / 2;
        int by = (height - H) / 2;

        g.blit(TEXTURE, bx, by, 0, 0, W, H, TEX_W, TEX_H);
        drawSideTabs(g, bx, by, mx, my);

        switch (tab) {
            case 0 -> drawLorPages(g, bx, by);
            case 1 -> drawShardsPages(g, bx, by, mx, my);
        }

        super.render(g, mx, my, delta);
    }

    // ─── Лор ──────────────────────────────────────────────
    private void drawLorPages(GuiGraphics g, int bx, int by)
    {
        bigTitle(g, "Лор",  bx + L_CX, by + T_Y);
        bigTitle(g, "Миры", bx + R_CX, by + T_Y);

        int lx = bx + L_X, ly = by + PY;
        int rx = bx + R_X, ry = by + PY;

        line(g, lx, ly,     "Эрен — первый");
        line(g, lx, ly+12,  "Садовник, хранитель");
        line(g, lx, ly+24,  "Первоцвета.");
        line(g, lx, ly+40,  "Первоцвет угас.");
        line(g, lx, ly+52,  "Осколки рассеялись");
        line(g, lx, ly+64,  "по шести мирам.");
        line(g, lx, ly+80,  "Убей боссов —");
        line(g, lx, ly+92,  "получи фрагменты.");
        line(g, lx, ly+104, "Скрафти осколок.");
        line(g, lx, ly+120, "Собери все шесть —");
        line(g, lx, ly+132, "пламя вернётся.");

        line(g, rx, ry,     "1. Сумеречный лес");
        line(g, rx, ry+10,  "   Twilight Forest");
        line(g, rx, ry+24,  "2. Эфир — Aether");
        line(g, rx, ry+38,  "3. Небеса");
        line(g, rx, ry+48,  "   Blue Skies");
        line(g, rx, ry+62,  "4. Подземелье");
        line(g, rx, ry+72,  "   Undergarden");
        line(g, rx, ry+86,  "5. Глубже и темнее");
        line(g, rx, ry+96,  "   Deeper Dark");
        line(g, rx, ry+110, "6. Нижний мир");
        line(g, rx, ry+120, "   Nether");
        line(g, rx, ry+136, "\u2014 Лия");
    }

    // ─── Осколки ──────────────────────────────────────────
    private void drawShardsPages(GuiGraphics g, int bx, int by, int mx, int my)
    {
        Player player = Minecraft.getInstance().player;

        bigTitle(g, "Осколки", bx + L_CX, by + T_Y);

        int lx = bx + L_X, ly = by + PY;
        int rx = bx + R_X, ry = by + PY;

        // ── Левая: список миров со счётчиком ──
        for (int i = 0; i < 6; i++)
        {
            int rowY   = ly + i * 22;
            boolean hasShard = player != null && hasShard(player, SHARD_IDS.get(i));
            List<String> frags = WORLD_FRAGMENTS.get(i);

            String prefix;
            int color;
            if (hasShard) {
                prefix = "* ";
                color  = COLOR_HAS;
            } else {
                prefix = "o ";
                color  = (selectedWorld == i) ? COLOR_SEL : COLOR_MISS;
            }

            String counter = "";
            if (!frags.isEmpty() && !hasShard) {
                int have = countFragments(player, frags);
                counter  = " " + have + "/" + frags.size();
            }

            String label = prefix + WORLD_NAMES.get(i) + counter;
            g.drawString(font, label, lx, rowY, color, false);

            // Подчёркивание у выбранного
            if (selectedWorld == i && !hasShard) {
                int w = font.width(label);
                g.fill(lx, rowY + 10, lx + w, rowY + 11, COLOR_SEL);
            }
        }

        // ── Правая: боссы выбранного мира ──
        int totalShards = countShards(player);
        if (totalShards == 6) {
            bigTitle(g, "6 / 6", bx + R_CX, by + T_Y);
            line(g, rx, ry,     "Все осколки собраны!");
            line(g, rx, ry+14,  "Первоцвет ждёт тебя.");
        } else if (selectedWorld >= 0) {
            bigTitle(g, WORLD_NAMES.get(selectedWorld), bx + R_CX, by + T_Y);
            List<String> bosses = WORLD_BOSSES.get(selectedWorld);
            List<String> frags  = WORLD_FRAGMENTS.get(selectedWorld);
            boolean hasShard    = player != null && hasShard(player, SHARD_IDS.get(selectedWorld));

            if (hasShard) {
                line(g, rx, ry, "Осколок получен!");
            } else if (frags.isEmpty()) {
                line(g, rx, ry, "Убей одного босса");
                line(g, rx, ry+12, "— осколок твой:");
                for (int b = 0; b < bosses.size(); b++)
                    line(g, rx, ry + 28 + b * 12, "\u2022 " + bosses.get(b));
            } else {
                int have = countFragments(player, frags);
                line(g, rx, ry, "Фрагменты: " + have + "/" + frags.size());
                line(g, rx, ry+12, "Убей боссов:");
                for (int b = 0; b < bosses.size(); b++) {
                    boolean hasF = player != null && hasShard(player, frags.get(b));
                    int bc = hasF ? COLOR_HAS : COLOR_TEXT;
                    g.drawString(font, (hasF ? "* " : "o ") + bosses.get(b),
                            rx, ry + 28 + b * 12, bc, false);
                }
                line(g, rx, ry + 28 + bosses.size() * 12 + 6, "Затем скрафти");
                line(g, rx, ry + 28 + bosses.size() * 12 + 18, "осколок в верстаке.");
            }
        } else {
            bigTitle(g, totalShards + " / 6", bx + R_CX, by + T_Y);
            line(g, rx, ry,     "Нажми на мир");
            line(g, rx, ry+12,  "слева, чтобы");
            line(g, rx, ry+24,  "узнать боссов.");
        }
    }

    // ─── Вкладки ──────────────────────────────────────────
    private void drawSideTabs(GuiGraphics g, int bx, int by, int mx, int my)
    {
        int stubX = bx + W - 4;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 2; i++) {
                int ty   = by + 24 + i * 68;
                int tabH = tab == i ? 48 : 42;
                int oty  = ty + (tab == i ? 0 : 3);

                boolean hovered  = mx >= stubX && mx <= stubX + 50
                        && my >= oty && my <= oty + tabH;
                boolean expanded = (tab == i) || hovered;

                if (pass == 0 && expanded)  continue;
                if (pass == 1 && !expanded) continue;

                int cMain = (tab == i) ? TAB_ACTIVE[i] : (hovered ? TAB_BRIGHT[i] : TAB_BASE[i]);

                if (expanded) {
                    int bw = font.width(TAB_NAMES[i]) + 14;
                    int tx = stubX;
                    g.fill(tx,      oty,      tx+bw,    oty+tabH,    cMain);
                    g.fill(tx+bw,   oty+5,    tx+bw+4,  oty+tabH-5,  cMain);
                    g.fill(tx+bw+4, oty+10,   tx+bw+8,  oty+tabH-10, cMain);
                    g.fill(tx+1, oty+1, tx+bw-1, oty+tabH-1, TAB_BRIGHT[i]);
                    g.drawString(font, TAB_NAMES[i], tx+7, oty+(tabH-8)/2, 0xFF3A1000, false);
                } else {
                    g.fill(stubX,   oty+6,  stubX+8,  oty+tabH-6,  cMain);
                    g.fill(stubX+8, oty+10, stubX+11, oty+tabH-10, cMain);
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────

    private void bigTitle(GuiGraphics g, String text, int cx, int y)
    {
        g.drawString(font, text, cx - font.width(text) / 2, y, COLOR_TITLE, false);
    }

    private void line(GuiGraphics g, int x, int y, String text)
    {
        g.drawString(font, text, x, y, COLOR_TEXT, false);
    }

    private boolean hasShard(Player player, String itemId)
    {
        String[] parts = itemId.split(":");
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            var loc = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (loc != null && loc.getNamespace().equals(parts[0]) && loc.getPath().equals(parts[1]))
                return true;
        }
        return false;
    }

    private int countFragments(Player player, List<String> fragIds)
    {
        if (player == null) return 0;
        return (int) fragIds.stream().filter(id -> hasShard(player, id)).count();
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

        // Вкладки
        for (int i = 0; i < 2; i++) {
            int ty   = by + 24 + i * 68;
            int tabH = tab == i ? 48 : 42;
            int oty  = ty + (tab == i ? 0 : 3);
            if (mx >= stubX && mx <= stubX + 60 && my >= oty && my <= oty + tabH) {
                tab = i;
                selectedWorld = (i == 1) ? 0 : -1;
                return true;
            }
        }

        // Клик по миру в Осколках
        if (tab == 1) {
            int lx = bx + L_X;
            int ly = by + PY;
            for (int i = 0; i < 6; i++) {
                int rowY = ly + i * 22;
                if (mx >= lx && mx <= lx + 140 && my >= rowY && my <= rowY + 14) {
                    selectedWorld = (selectedWorld == i) ? -1 : i;
                    return true;
                }
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

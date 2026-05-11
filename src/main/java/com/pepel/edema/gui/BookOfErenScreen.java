package com.pepel.edema.gui;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.book.BookEntries;
import com.pepel.edema.capability.Importance;
import com.pepel.edema.capability.ReceivedEntry;
import com.pepel.edema.client.ClientBookState;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.network.packet.MarkReadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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
    private static final int LIST_ROW_H = 12;
    private static final int LIST_VISIBLE_ROWS = 11;
    private static final int RIGHT_TEXT_W = 138;
    private static final int RIGHT_LINE_H = 10;
    private static final int RIGHT_PARAGRAPH_GAP = 4;
    private static final int RIGHT_PAGE_LINES = 10;   // макс строк на одной странице справа
    private static final int RIGHT_PAGE_INDICATOR_Y_OFFSET = 162; // от by, ниже текста
    // Кликабельные стрелки пагинации в нижних углах правой страницы.
    // bbox 12×12 пикселей от верхнего-левого угла; стрелка визуально внутри.
    private static final int ARROW_W = 12;
    private static final int ARROW_H = 12;
    private static final int ARROW_Y_OFFSET = 158;            // от by
    private static final int ARROW_LEFT_X_OFFSET = R_X;       // от bx, левый нижний угол правой страницы
    // ARROW_RIGHT_X считается динамически (R_X + RIGHT_TEXT_W - ARROW_W).

    private int tab           = 0;
    private int selectedWorld = 0;   // первый мир открыт по умолчанию
    private int selectedEntry = -1;  // индекс выбранной записи в received (-1 = ничего)
    private int entryListScroll = 0; // сколько строк сверху прокручено
    private int entryPage     = 0;   // текущая страница текста выбранной записи

    private static final String[] TAB_NAMES = {"Записи", "Осколки"};
    // Оба таба в тёплой кожаной гамме — Записи темнее, Осколки чуть светлее
    private static final int[] TAB_BASE   = {0xFF5C2E08, 0xFF7A4010};
    private static final int[] TAB_ACTIVE = {0xFF9C5214, 0xFFB86A20};
    private static final int[] TAB_BRIGHT = {0xFFCC7A30, 0xFFE09040};

    private static final int COLOR_TEXT   = 0xFF1A0E00;   // тёмно-коричневый, чёткий на пергаменте
    private static final int COLOR_TITLE  = 0xFF5C2800;   // заголовок — насыщенный
    private static final int COLOR_HAS    = 0xFF1A5C1A;   // есть осколок — зелёный
    private static final int COLOR_MISS   = 0xFF7A6A55;   // нет — приглушённый, в тоне пергамента
    private static final int COLOR_SEL    = 0xFF7A1800;   // выбранный мир/запись — бордо
    private static final int COLOR_UNREAD = 0xFFB85A10;   // непрочитанная запись — оранжевый
    private static final int COLOR_DIM    = 0xFF5C4A30;   // подсказка-плейсхолдер

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
            case 0 -> drawEntriesPages(g, bx, by);
            case 1 -> drawShardsPages(g, bx, by, mx, my);
        }

        super.render(g, mx, my, delta);
    }

    // ─── Записи Эрена ─────────────────────────────────────
    private void drawEntriesPages(GuiGraphics g, int bx, int by)
    {
        bigTitle(g, "Записи", bx + L_CX, by + T_Y);

        List<ReceivedEntry> entries = ClientBookState.received;
        int lx = bx + L_X, ly = by + PY;

        if (entries.isEmpty())
        {
            line(g, lx, ly, "Пока пусто.", COLOR_DIM);
            line(g, lx, ly + 14, "Дневник заполнится", COLOR_DIM);
            line(g, lx, ly + 24, "по мере путешествия.", COLOR_DIM);

            bigTitle(g, "...", bx + R_CX, by + T_Y);
            int rx = bx + R_X, ry = by + PY;
            line(g, rx, ry, "Открой записи", COLOR_DIM);
            line(g, rx, ry + 12, "слева, когда они", COLOR_DIM);
            line(g, rx, ry + 24, "появятся.", COLOR_DIM);
            return;
        }

        // Левая страница — список заголовков
        int maxScroll = Math.max(0, entries.size() - LIST_VISIBLE_ROWS);
        entryListScroll = Math.max(0, Math.min(entryListScroll, maxScroll));

        for (int i = 0; i < LIST_VISIBLE_ROWS; i++)
        {
            int idx = entryListScroll + i;
            if (idx >= entries.size()) break;
            ReceivedEntry r = entries.get(idx);
            int rowY = ly + i * LIST_ROW_H;

            boolean isUnread = ClientBookState.unread.containsKey(r.id());
            boolean isSelected = idx == selectedEntry;
            String title = entryTitle(r.id());

            int color;
            String prefix;
            if (isSelected) { color = COLOR_SEL;    prefix = "> "; }
            else if (isUnread) { color = COLOR_UNREAD; prefix = "* "; }
            else { color = COLOR_TEXT; prefix = "  "; }

            String label = prefix + title;
            // Обрезка длинных заголовков, чтобы не выходили за ширину страницы
            label = truncate(label, 26);
            g.drawString(font, label, lx, rowY, color, false);
        }

        // Индикатор скролла если есть что прокручивать
        if (entries.size() > LIST_VISIBLE_ROWS)
        {
            String hint = (entryListScroll + LIST_VISIBLE_ROWS) + "/" + entries.size();
            g.drawString(font, hint, lx, ly + LIST_VISIBLE_ROWS * LIST_ROW_H + 2, COLOR_DIM, false);
        }

        // Правая страница — текст выбранной записи
        int rx = bx + R_X, ry = by + PY;
        if (selectedEntry < 0 || selectedEntry >= entries.size())
        {
            bigTitle(g, "...", bx + R_CX, by + T_Y);
            line(g, rx, ry, "Нажми на запись", COLOR_DIM);
            line(g, rx, ry + 12, "слева, чтобы прочитать.", COLOR_DIM);
            return;
        }

        ReceivedEntry r = entries.get(selectedEntry);
        bigTitle(g, entryTitle(r.id()), bx + R_CX, by + T_Y);

        // Собираем все отрендеренные строки записи в плоский список с пометкой,
        // после какой строки нужен дополнительный gap (конец параграфа).
        List<FormattedCharSequence> flat = new ArrayList<>();
        List<Boolean> paragraphBreak = new ArrayList<>();
        BookEntries.Def def = BookEntries.get(r.id());
        int lineCount = def != null ? def.lineCount() : 0;
        for (int i = 0; i < lineCount; i++)
        {
            String text = I18n.get("pepel.book.entry." + r.id() + ".line" + i);
            List<FormattedCharSequence> wrapped = font.split(Component.literal(text), RIGHT_TEXT_W);
            for (int k = 0; k < wrapped.size(); k++)
            {
                flat.add(wrapped.get(k));
                paragraphBreak.add(k == wrapped.size() - 1 && i < lineCount - 1);
            }
        }

        int totalPages = Math.max(1, (flat.size() + RIGHT_PAGE_LINES - 1) / RIGHT_PAGE_LINES);
        entryPage = Math.max(0, Math.min(entryPage, totalPages - 1));

        int from = entryPage * RIGHT_PAGE_LINES;
        int to = Math.min(flat.size(), from + RIGHT_PAGE_LINES);
        int curY = ry;
        for (int idx = from; idx < to; idx++)
        {
            g.drawString(font, flat.get(idx), rx, curY, COLOR_TEXT, false);
            curY += RIGHT_LINE_H;
            if (paragraphBreak.get(idx)) curY += RIGHT_PARAGRAPH_GAP;
        }

        // Стрелки пагинации в нижних углах правой страницы и номер страницы по центру.
        // Стрелки рисуем всегда, если страниц больше одной — крайние "погашены".
        if (totalPages > 1)
        {
            drawArrow(g, bx + ARROW_LEFT_X_OFFSET,
                    by + ARROW_Y_OFFSET, "<", entryPage > 0);
            drawArrow(g, bx + R_X + RIGHT_TEXT_W - ARROW_W,
                    by + ARROW_Y_OFFSET, ">", entryPage < totalPages - 1);
            String hint = (entryPage + 1) + "/" + totalPages;
            int hw = font.width(hint);
            g.drawString(font, hint, bx + R_CX - hw / 2,
                    by + RIGHT_PAGE_INDICATOR_Y_OFFSET, COLOR_DIM, false);
        }
    }

    /**
     * Рисует одну кликабельную стрелку пагинации в bbox ARROW_W×ARROW_H, начало
     * в (x, y). Активная — бордовая, погашенная — серая.
     */
    private void drawArrow(GuiGraphics g, int x, int y, String glyph, boolean active)
    {
        int color = active ? COLOR_SEL : COLOR_DIM;
        int gw = font.width(glyph);
        // Центрируем глиф внутри bbox
        int gx = x + (ARROW_W - gw) / 2;
        int gy = y + (ARROW_H - 8) / 2;
        g.drawString(font, glyph, gx, gy, color, false);
    }

    private static String entryTitle(String entryId)
    {
        String key = "pepel.book.entry." + entryId + ".title";
        String got = I18n.get(key);
        // I18n.get возвращает сам ключ, если перевод не найден — это норма для
        // записей выданных через /pepel book notify <произвольный_id>.
        return got.equals(key) ? entryId : got;
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
            line(g, rx, ry,     "Все осколки собраны!", COLOR_TEXT);
            line(g, rx, ry+14,  "Первоцвет ждёт тебя.", COLOR_TEXT);
        } else if (selectedWorld >= 0) {
            bigTitle(g, WORLD_NAMES.get(selectedWorld), bx + R_CX, by + T_Y);
            List<String> bosses = WORLD_BOSSES.get(selectedWorld);
            List<String> frags  = WORLD_FRAGMENTS.get(selectedWorld);
            boolean hasShard    = player != null && hasShard(player, SHARD_IDS.get(selectedWorld));

            if (hasShard) {
                line(g, rx, ry, "Осколок получен!", COLOR_TEXT);
            } else if (frags.isEmpty()) {
                line(g, rx, ry, "Убей одного босса", COLOR_TEXT);
                line(g, rx, ry+12, "— осколок твой:", COLOR_TEXT);
                for (int b = 0; b < bosses.size(); b++)
                    line(g, rx, ry + 28 + b * 12, "• " + bosses.get(b), COLOR_TEXT);
            } else {
                int have = countFragments(player, frags);
                line(g, rx, ry, "Фрагменты: " + have + "/" + frags.size(), COLOR_TEXT);
                line(g, rx, ry+12, "Убей боссов:", COLOR_TEXT);
                for (int b = 0; b < bosses.size(); b++) {
                    boolean hasF = player != null && hasShard(player, frags.get(b));
                    int bc = hasF ? COLOR_HAS : COLOR_TEXT;
                    g.drawString(font, (hasF ? "* " : "o ") + bosses.get(b),
                            rx, ry + 28 + b * 12, bc, false);
                }
                line(g, rx, ry + 28 + bosses.size() * 12 + 6, "Затем скрафти", COLOR_TEXT);
                line(g, rx, ry + 28 + bosses.size() * 12 + 18, "осколок в верстаке.", COLOR_TEXT);
            }
        } else {
            bigTitle(g, totalShards + " / 6", bx + R_CX, by + T_Y);
            line(g, rx, ry,     "Нажми на мир",       COLOR_TEXT);
            line(g, rx, ry+12,  "слева, чтобы",       COLOR_TEXT);
            line(g, rx, ry+24,  "узнать боссов.",     COLOR_TEXT);
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

    private void line(GuiGraphics g, int x, int y, String text, int color)
    {
        g.drawString(font, text, x, y, color, false);
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

        // Клик по записи на вкладке "Записи"
        if (tab == 0) {
            int lx = bx + L_X;
            int ly = by + PY;
            for (int i = 0; i < LIST_VISIBLE_ROWS; i++) {
                int idx = entryListScroll + i;
                if (idx >= ClientBookState.received.size()) break;
                int rowY = ly + i * LIST_ROW_H;
                if (mx >= lx && mx <= lx + 140 && my >= rowY && my <= rowY + LIST_ROW_H - 1) {
                    if (selectedEntry != idx) entryPage = 0;
                    selectedEntry = idx;
                    ReceivedEntry r = ClientBookState.received.get(idx);
                    if (ClientBookState.unread.containsKey(r.id())) {
                        ClientBookState.unread.remove(r.id());
                        PepelNetwork.sendToServer(new MarkReadPacket(r.id()));
                    }
                    return true;
                }
            }

            // Клик по стрелкам пагинации текста записи
            if (selectedEntry >= 0)
            {
                int leftX  = bx + ARROW_LEFT_X_OFFSET;
                int rightX = bx + R_X + RIGHT_TEXT_W - ARROW_W;
                int ay     = by + ARROW_Y_OFFSET;
                if (mx >= leftX && mx <= leftX + ARROW_W && my >= ay && my <= ay + ARROW_H) {
                    if (entryPage > 0) entryPage--;
                    return true;
                }
                if (mx >= rightX && mx <= rightX + ARROW_W && my >= ay && my <= ay + ARROW_H) {
                    entryPage++; // верхняя граница clamp'ится в drawEntriesPages
                    return true;
                }
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
    public boolean mouseScrolled(double mx, double my, double delta)
    {
        if (tab == 0)
        {
            int bx = (width  - W) / 2;
            int half = bx + W / 2;
            // Колесо над правой страницей — листает страницы выбранной записи.
            if (mx >= half && selectedEntry >= 0 && selectedEntry < ClientBookState.received.size())
            {
                entryPage -= (int) Math.signum(delta);
                if (entryPage < 0) entryPage = 0;
                return true;
            }
            // Колесо над левой страницей — скролл списка записей.
            int total = ClientBookState.received.size();
            if (total > LIST_VISIBLE_ROWS)
            {
                entryListScroll -= (int) Math.signum(delta);
                int maxScroll = Math.max(0, total - LIST_VISIBLE_ROWS);
                entryListScroll = Math.max(0, Math.min(entryListScroll, maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @SuppressWarnings("unused")
    private static List<String> debugDump()
    {
        List<String> out = new ArrayList<>();
        for (ReceivedEntry r : ClientBookState.received) out.add(r.id());
        return out;
    }
}

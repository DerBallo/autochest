package com.derballo.autochest.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AutoChestSearchScreen extends Screen {
    private static final int MAX_VISIBLE_RESULTS = 8;
    private static final int ROW_HEIGHT = 18;
    private static final int PANEL_MAX_WIDTH = 520;

    private EditBox searchBox;
    private List<SearchEntry> results = List.of();
    private int selectedIndex;
    private int scrollOffset;
    private boolean eventsRegistered;
    private final ScreenReturnContext returnContext;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int resultsY;

    public AutoChestSearchScreen() {
        this(ScreenReturnContext.none());
    }

    public AutoChestSearchScreen(ScreenReturnContext returnContext) {
        super(Component.literal("AutoChest Search"));
        this.returnContext = returnContext;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(240, this.width - 40));
        panelHeight = 58 + MAX_VISIBLE_RESULTS * ROW_HEIGHT + 18;
        panelX = (this.width - panelWidth) / 2;
        panelY = Math.max(24, this.height / 5);
        resultsY = panelY + 42;

        searchBox = new EditBox(
                this.font,
                panelX + 12,
                panelY + 12,
                panelWidth - 24,
                20,
                Component.literal("Search indexed items")
        );
        searchBox.setHint(Component.literal("Search indexed items"));
        searchBox.setMaxLength(80);
        searchBox.setResponder(this::rebuildResults);

        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);
        searchBox.setFocused(true);

        rebuildResults("");

        if (!eventsRegistered) {
            ScreenEvents.afterBackground(this).register((screen, graphics, mouseX, mouseY, delta) ->
                    drawPanel(graphics));

            ScreenEvents.afterExtract(this).register((screen, graphics, mouseX, mouseY, delta) ->
                    drawResults(graphics));

            ScreenKeyboardEvents.allowKeyPress(this).register((screen, event) ->
                    handlePaletteKey(event));

            eventsRegistered = true;
        }
    }

    private boolean handlePaletteKey(KeyEvent event) {
        if (event.isUp()) {
            if (!results.isEmpty()) {
                selectedIndex = Math.max(0, selectedIndex - 1);
                keepSelectionVisible();
            }
            return false;
        }

        if (event.isDown()) {
            if (!results.isEmpty()) {
                selectedIndex = Math.min(results.size() - 1, selectedIndex + 1);
                keepSelectionVisible();
            }
            return false;
        }

        if (event.isConfirmation()) {
            if (!results.isEmpty()) {
                SearchEntry selected = results.get(selectedIndex);

                if (event.hasShiftDown()) {
                    this.minecraft.gui.setScreen(new AutoChestQuantityScreen(selected.example(), returnContext));
                    return false;
                }

                this.minecraft.gui.setScreen(null);
                ContainerRetriever.start(
                        this.minecraft,
                        selected.example(),
                        ContainerRetriever.AmountMode.ONE_STACK,
                        returnContext
                );
            }
            return false;
        }

        return true;
    }

    private void rebuildResults(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] tokens = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");

        Map<Item, MutableSearchEntry> grouped = new LinkedHashMap<>();

        for (IndexedContainer container : ContainerIndex.snapshot().values()) {
            for (ItemStack stack : container.slots()) {
                addSearchStack(grouped, container, stack);
                if (ShulkerBoxSupport.isShulkerBox(stack)) {
                    for (ItemStack nested : ShulkerBoxSupport.contents(stack)) {
                        addSearchStack(grouped, container, nested);
                    }
                }
            }
        }

        List<SearchEntry> filtered = new ArrayList<>();
        for (MutableSearchEntry mutable : grouped.values()) {
            ItemStack example = mutable.example.copy();
            example.setCount(1);
            String name = example.getHoverName().getString();
            String searchable = name.toLowerCase(Locale.ROOT);

            boolean matches = true;
            for (String token : tokens) {
                if (!searchable.contains(token)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;

            filtered.add(new SearchEntry(
                    example,
                    name,
                    mutable.totalCount,
                    mutable.containerKeys.size()
            ));
        }

        filtered.sort(Comparator
                .comparing(SearchEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SearchEntry::name));

        results = List.copyOf(filtered);
        selectedIndex = results.isEmpty() ? 0 : Math.min(selectedIndex, results.size() - 1);
        scrollOffset = Math.min(scrollOffset, Math.max(0, results.size() - MAX_VISIBLE_RESULTS));
        keepSelectionVisible();
    }

    private static void addSearchStack(Map<Item, MutableSearchEntry> grouped, IndexedContainer container, ItemStack stack) {
        if (stack.isEmpty()) return;
        MutableSearchEntry entry = grouped.computeIfAbsent(
                stack.getItem(),
                item -> new MutableSearchEntry(stack.copy())
        );
        entry.totalCount += stack.getCount();
        entry.containerKeys.put(container.key(), Boolean.TRUE);
    }

    private void keepSelectionVisible() {
        if (results.isEmpty()) {
            selectedIndex = 0;
            scrollOffset = 0;
            return;
        }

        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + MAX_VISIBLE_RESULTS) {
            scrollOffset = selectedIndex - MAX_VISIBLE_RESULTS + 1;
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0181818);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF5A5A5A);
    }

    private void drawResults(GuiGraphicsExtractor graphics) {
        int visible = Math.min(MAX_VISIBLE_RESULTS, Math.max(0, results.size() - scrollOffset));

        if (results.isEmpty()) {
            graphics.text(
                    this.font,
                    ContainerIndex.size() == 0
                            ? "No indexed containers. Press the index key first."
                            : "No indexed items match your search.",
                    panelX + 14,
                    resultsY + 6,
                    0xFF9A9A9A,
                    false
            );
        } else {
            for (int row = 0; row < visible; row++) {
                int resultIndex = scrollOffset + row;
                SearchEntry entry = results.get(resultIndex);
                int y = resultsY + row * ROW_HEIGHT;

                if (resultIndex == selectedIndex) {
                    graphics.fill(
                            panelX + 8,
                            y,
                            panelX + panelWidth - 8,
                            y + ROW_HEIGHT - 1,
                            0x665A86C8
                    );
                }

                graphics.text(
                        this.font,
                        entry.name(),
                        panelX + 14,
                        y + 5,
                        0xFFFFFFFF,
                        false
                );

                String countText = entry.totalCount() + "  ·  " + entry.containerCount() + " chest"
                        + (entry.containerCount() == 1 ? "" : "s");
                int countX = panelX + panelWidth - 14 - this.font.width(countText);
                graphics.text(
                        this.font,
                        countText,
                        countX,
                        y + 5,
                        0xFFB8B8B8,
                        false
                );
            }
        }

        String footer = "↑↓ select   Enter: stack   Shift+Enter: amount   Esc: close";
        int footerX = panelX + (panelWidth - this.font.width(footer)) / 2;
        graphics.text(
                this.font,
                footer,
                footerX,
                panelY + panelHeight - 13,
                0xFF9A9A9A,
                false
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class MutableSearchEntry {
        private final ItemStack example;
        private final Map<BlockPos, Boolean> containerKeys = new LinkedHashMap<>();
        private int totalCount;

        private MutableSearchEntry(ItemStack example) {
            this.example = example;
        }
    }

    private record SearchEntry(
            ItemStack example,
            String name,
            int totalCount,
            int containerCount
    ) {}
}
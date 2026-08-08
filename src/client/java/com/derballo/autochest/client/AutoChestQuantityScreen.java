package com.derballo.autochest.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class AutoChestQuantityScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 54;

    private final ItemStack requested;
    private final ScreenReturnContext returnContext;
    private EditBox amountBox;
    private boolean eventsRegistered;
    private int panelX;
    private int panelY;

    public AutoChestQuantityScreen(ItemStack requested, ScreenReturnContext returnContext) {
        super(Component.literal("Retrieve quantity"));
        this.requested = requested.copy();
        this.requested.setCount(1);
        this.returnContext = returnContext;
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_WIDTH) / 2;
        panelY = Math.max(24, this.height / 3);

        amountBox = new EditBox(
                this.font,
                panelX + 12,
                panelY + 17,
                PANEL_WIDTH - 24,
                20,
                Component.literal("Amount")
        );
        amountBox.setHint(Component.literal("Amount"));
        amountBox.setMaxLength(9);

        this.addRenderableWidget(amountBox);
        this.setInitialFocus(amountBox);
        amountBox.setFocused(true);

        if (!eventsRegistered) {
            ScreenEvents.afterBackground(this).register((screen, graphics, mouseX, mouseY, delta) ->
                    drawPanel(graphics));
            ScreenKeyboardEvents.allowKeyPress(this).register((screen, event) ->
                    handleQuantityKey(event));
            eventsRegistered = true;
        }
    }

    private boolean handleQuantityKey(KeyEvent event) {
        if (!event.isConfirmation()) return true;

        String raw = amountBox.getValue().trim();
        if (raw.isEmpty()) return false;

        final int amount;
        try {
            amount = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return false;
        }

        if (amount <= 0) return false;

        this.minecraft.gui.setScreen(null);
        ContainerRetriever.start(this.minecraft, requested, amount, returnContext);
        return false;
    }

    private void drawPanel(GuiGraphicsExtractor graphics) {
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xE0181818);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF5A5A5A);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
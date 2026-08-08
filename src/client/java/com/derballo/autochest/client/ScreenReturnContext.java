package com.derballo.autochest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ScreenReturnContext {
    private enum Type { NONE, INVENTORY, CONTAINER }

    private static final ScreenReturnContext NONE = new ScreenReturnContext(Type.NONE, null, null);

    private final Type type;
    private final BlockPos interactionPos;
    private final AbstractContainerScreen<?> inventoryScreen;

    private ScreenReturnContext(Type type, BlockPos interactionPos, AbstractContainerScreen<?> inventoryScreen) {
        this.type = type;
        this.interactionPos = interactionPos;
        this.inventoryScreen = inventoryScreen;
    }

    public static ScreenReturnContext none() {
        return NONE;
    }

    public static ScreenReturnContext capture(Minecraft minecraft, AbstractContainerScreen<?> screen) {
        if (minecraft.player == null) return NONE;
        if (minecraft.player.containerMenu instanceof InventoryMenu) {
            return new ScreenReturnContext(Type.INVENTORY, null, screen);
        }
        BlockPos pos = ContainerIndexSynchronizer.getActiveInteractionPos();
        if (pos == null) return NONE;
        return new ScreenReturnContext(Type.CONTAINER, pos.immutable(), null);
    }

    public void prepare(Minecraft minecraft) {
        if (minecraft.player == null) return;
        minecraft.gui.setScreen(null);
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            minecraft.player.closeContainer();
        }
    }

    public void restore(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gameMode == null) return;
        if (type == Type.INVENTORY && inventoryScreen != null) {
            minecraft.gui.setScreen(inventoryScreen);
            return;
        }
        if (type == Type.CONTAINER && interactionPos != null
                && minecraft.player.isWithinBlockInteractionRange(interactionPos, 0.0)) {
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(interactionPos),
                    Direction.UP,
                    interactionPos,
                    false
            );
            minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
        }
    }
}
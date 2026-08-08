package com.derballo.autochest.client;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;

public final class ContainerIndexSynchronizer {
    private static final int PENDING_OPEN_TIMEOUT_TICKS = 40;

    private static BlockPos pendingKey;
    private static BlockPos pendingInteractionPos;
    private static int pendingTicks;
    private static BlockPos activeKey;
    private static BlockPos activeInteractionPos;
    private static AbstractContainerMenu lastOpenMenu;

    private ContainerIndexSynchronizer() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            pendingInteractionPos = hitResult.getBlockPos().immutable();
            pendingKey = ContainerIndex.findKeyByBlock(hitResult.getBlockPos());
            pendingTicks = 0;
            return InteractionResult.PASS;
        });
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            reset();
            return;
        }

        AbstractContainerMenu menu = minecraft.player.containerMenu;

        if (menu instanceof InventoryMenu) {
            if (activeKey != null && lastOpenMenu != null) {
                ContainerIndex.refreshFromMenu(activeKey, lastOpenMenu, minecraft.player.getInventory());
            }
            activeKey = null;
            activeInteractionPos = null;
            lastOpenMenu = null;

            if (pendingInteractionPos != null) {
                pendingTicks++;
                if (pendingTicks > PENDING_OPEN_TIMEOUT_TICKS) {
                    pendingKey = null;
                    pendingInteractionPos = null;
                    pendingTicks = 0;
                }
            }
            return;
        }

        if (activeInteractionPos == null && pendingInteractionPos != null) {
            activeInteractionPos = pendingInteractionPos;
            activeKey = pendingKey;
            pendingInteractionPos = null;
            pendingKey = null;
            pendingTicks = 0;
        }

        if (activeKey != null && ContainerIndex.contains(activeKey)) {
            ContainerIndex.refreshFromMenu(activeKey, menu, minecraft.player.getInventory());
            lastOpenMenu = menu;
        }
    }

    public static BlockPos getActiveInteractionPos() {
        return activeInteractionPos;
    }

    public static void refreshNow(Minecraft minecraft, BlockPos key) {
        if (minecraft.player == null) return;
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu instanceof InventoryMenu) return;
        ContainerIndex.refreshFromMenu(key, menu, minecraft.player.getInventory());
    }

    public static void forgetForModMutation(BlockPos key) {
        if (key == null) return;
        if (key.equals(pendingKey)) {
            pendingKey = null;
            pendingInteractionPos = null;
            pendingTicks = 0;
        }
        if (key.equals(activeKey)) {
            activeKey = null;
            activeInteractionPos = null;
            lastOpenMenu = null;
        }
    }

    public static void reset() {
        pendingKey = null;
        pendingInteractionPos = null;
        pendingTicks = 0;
        activeKey = null;
        activeInteractionPos = null;
        lastOpenMenu = null;
    }
}
package com.derballo.autochest.client;

import com.derballo.autochest.client.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ContainerDepositor {
    private enum State { IDLE, SELECT_TARGET, WAITING_FOR_MENU }

    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static State state = State.IDLE;
    private static int ticksInState;
    private static ItemStack selected = ItemStack.EMPTY;
    private static IndexedContainer current;
    private static BlockPos currentInteractionPos;
    private static final Set<BlockPos> attempted = new HashSet<>();
    private static int startingCount;

    private ContainerDepositor() {}

    public static boolean isDepositing() {
        return state != State.IDLE;
    }

    public static boolean startFromHoveredSlot(
            Minecraft minecraft,
            AbstractContainerScreen<?> screen
    ) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return false;
        }
        if (ContainerIndex.size() == 0) {
            AutoChestChat.warning(minecraft, "No indexed containers. Index nearby containers first.");
            return false;
        }

        Slot hovered = ((AbstractContainerScreenAccessor) screen).autochest$getHoveredSlot();
        Inventory inventory = minecraft.player.getInventory();

        if (hovered == null || hovered.container != inventory) return false;

        ItemStack stack = hovered.getItem();
        if (stack.isEmpty()) return false;

        selected = stack.copy();
        attempted.clear();
        current = null;
        currentInteractionPos = null;
        startingCount = countSelectedInInventory(inventory);

        if (startingCount <= 0) return false;

        minecraft.gui.setScreen(null);
        transition(State.SELECT_TARGET);
        return true;
    }

    public static void tick(Minecraft minecraft) {
        if (state == State.IDLE) return;

        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            abort();
            return;
        }

        ticksInState++;

        switch (state) {
            case SELECT_TARGET -> selectAndOpenTarget(minecraft);
            case WAITING_FOR_MENU -> waitForMenu(minecraft);
            case IDLE -> { }
        }
    }

    private static void selectAndOpenTarget(Minecraft minecraft) {
        int remaining = countSelectedInInventory(minecraft.player.getInventory());
        if (remaining <= 0) {
            finish(minecraft, 0);
            return;
        }

        TargetChoice choice = chooseTarget(minecraft);
        if (choice == null) {
            finish(minecraft, remaining);
            return;
        }

        current = choice.container();
        currentInteractionPos = choice.interactionPos();
        attempted.add(current.key());

        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(currentInteractionPos),
                Direction.UP,
                currentInteractionPos,
                false
        );

        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
        transition(State.WAITING_FOR_MENU);
    }

    private static void waitForMenu(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            minecraft.gui.setScreen(null);
            transferIntoOpenMenu(minecraft);
            return;
        }

        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            current = null;
            currentInteractionPos = null;
            transition(State.SELECT_TARGET);
        }
    }

    private static void transferIntoOpenMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();

        if (menu instanceof InventoryMenu) {
            transition(State.SELECT_TARGET);
            return;
        }

        int before = countSelectedInInventory(inventory);

        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory) continue;
            if (!isSelectedType(slot.getItem())) continue;

            minecraft.gameMode.handleContainerInput(
                    menu.containerId,
                    menuSlot,
                    0,
                    ContainerInput.QUICK_MOVE,
                    minecraft.player
            );
        }

        int after = countSelectedInInventory(inventory);
        int moved = Math.max(0, before - after);
        BlockPos completedKey = current.key();

        ContainerIndexSynchronizer.forgetForModMutation(completedKey);
        minecraft.player.closeContainer();

        if (moved > 0) {
            ContainerIndex.applyDeposited(completedKey, selected, moved);
        }

        current = null;
        currentInteractionPos = null;

        if (after >= before) {
            transition(State.SELECT_TARGET);
            return;
        }

        if (after <= 0) {
            finish(minecraft, 0);
        } else {
            transition(State.SELECT_TARGET);
        }
    }

    private static TargetChoice chooseTarget(Minecraft minecraft) {
        Map<BlockPos, IndexedContainer> snapshot = ContainerIndex.snapshot();
        TargetChoice firstEmpty = null;

        for (IndexedContainer indexed : snapshot.values()) {
            if (attempted.contains(indexed.key())) continue;

            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;

            boolean completelyEmpty = true;
            boolean containsType = false;
            int capacity = 0;

            for (ItemStack stack : indexed.slots()) {
                if (stack.isEmpty()) {
                    capacity += selected.getMaxStackSize();
                    continue;
                }

                completelyEmpty = false;

                if (isSelectedType(stack)) {
                    containsType = true;
                }

                if (ItemStack.isSameItemSameComponents(stack, selected)) {
                    capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
                }
            }

            if (containsType && capacity > 0) {
                return new TargetChoice(indexed, interactionPos);
            }

            if (firstEmpty == null && completelyEmpty && capacity > 0) {
                firstEmpty = new TargetChoice(indexed, interactionPos);
            }
        }

        return firstEmpty;
    }

    private static BlockPos firstReachableBlock(Minecraft minecraft, IndexedContainer indexed) {
        for (BlockPos pos : indexed.blocks()) {
            if (minecraft.player.isWithinBlockInteractionRange(pos, 0.0)) {
                return pos;
            }
        }
        return null;
    }

    private static int countSelectedInInventory(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isSelectedType(ItemStack stack) {
        return !stack.isEmpty() && !selected.isEmpty() && stack.getItem() == selected.getItem();
    }

    private static void finish(Minecraft minecraft, int remaining) {
        int moved = Math.max(0, startingCount - remaining);

        if (minecraft.player != null) {
            if (remaining == 0) {
                AutoChestChat.success(minecraft, "Deposited " + moved + " matching items.");
            } else {
                AutoChestChat.warning(minecraft, "Deposited " + moved + " items; " + remaining
                        + " remain (no suitable indexed container).");
            }
        }

        abort();
    }

    private static void transition(State next) {
        state = next;
        ticksInState = 0;
    }

    private static void abort() {
        state = State.IDLE;
        ticksInState = 0;
        selected = ItemStack.EMPTY;
        current = null;
        currentInteractionPos = null;
        attempted.clear();
        startingCount = 0;
    }

    private record TargetChoice(IndexedContainer container, BlockPos interactionPos) {}
}
package com.derballo.autochest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ContainerRetriever {
    public enum AmountMode {
        ONE_STACK,
        MAX,
        COUNT
    }

    private enum State {
        IDLE,
        SELECT_TARGET,
        WAITING_FOR_MENU
    }

    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static State state = State.IDLE;
    private static int ticksInState;

    private static Item selectedItem;
    private static ItemStack sample = ItemStack.EMPTY;
    private static AmountMode amountMode = AmountMode.ONE_STACK;

    private static IndexedContainer current;
    private static BlockPos currentInteractionPos;
    private static final Set<BlockPos> attempted = new HashSet<>();

    private static int retrievedTotal;
    private static int requestedCount;

    private ContainerRetriever() {}

    public static boolean isRetrieving() {
        return state != State.IDLE;
    }

    public static boolean start(Minecraft minecraft, ItemStack requested, AmountMode mode) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return false;
        }
        if (requested == null || requested.isEmpty()) {
            return false;
        }
        if (ContainerIndex.size() == 0) {
            AutoChestChat.warning(minecraft, "No indexed containers. Index nearby containers first.");
            return false;
        }
        if (isRetrieving()) {
            return false;
        }

        selectedItem = requested.getItem();
        sample = requested.copy();
        sample.setCount(1);
        amountMode = mode;
        attempted.clear();
        current = null;
        currentInteractionPos = null;
        retrievedTotal = 0;
        requestedCount = mode == AmountMode.ONE_STACK ? sample.getMaxStackSize() : 0;

        transition(State.SELECT_TARGET);
        return true;
    }


    public static boolean start(Minecraft minecraft, ItemStack requested, int amount) {
        if (amount <= 0) return false;
        if (!start(minecraft, requested, AmountMode.COUNT)) return false;
        requestedCount = amount;
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
        if (amountMode == AmountMode.ONE_STACK && retrievedTotal > 0) {
            finish(minecraft);
            return;
        }
        if (amountMode == AmountMode.COUNT && retrievedTotal >= requestedCount) {
            finish(minecraft);
            return;
        }

        TargetChoice choice = chooseTarget(minecraft);
        if (choice == null) {
            finish(minecraft);
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

        minecraft.gameMode.useItemOn(
                minecraft.player,
                InteractionHand.MAIN_HAND,
                hit
        );

        transition(State.WAITING_FOR_MENU);
    }

    private static void waitForMenu(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            minecraft.gui.setScreen(null);
            retrieveFromOpenMenu(minecraft);
            return;
        }

        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            current = null;
            currentInteractionPos = null;
            transition(State.SELECT_TARGET);
        }
    }

    private static void retrieveFromOpenMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();

        if (menu instanceof InventoryMenu || current == null) {
            transition(State.SELECT_TARGET);
            return;
        }

        int beforeInventoryCount = countSelectedInInventory(inventory);

        switch (amountMode) {
            case ONE_STACK -> retrieveOneStack(minecraft, menu, inventory);
            case MAX -> retrieveMaximum(minecraft, menu, inventory);
            case COUNT -> retrieveUpTo(minecraft, menu, inventory, requestedCount - retrievedTotal);
        }

        int afterInventoryCount = countSelectedInInventory(inventory);
        int moved = Math.max(0, afterInventoryCount - beforeInventoryCount);
        BlockPos completedKey = current.key();

        ContainerIndexSynchronizer.forgetForModMutation(completedKey);
        minecraft.player.closeContainer();

        if (moved > 0) {
            ContainerIndex.applyRetrieved(completedKey, selectedItem, moved);
            retrievedTotal += moved;
        }

        current = null;
        currentInteractionPos = null;

        if (amountMode == AmountMode.ONE_STACK) {
            finish(minecraft);
            return;
        }

        transition(State.SELECT_TARGET);
    }

    private static void retrieveOneStack(
            Minecraft minecraft,
            AbstractContainerMenu menu,
            Inventory inventory
    ) {
        int sourceMenuSlot = findFirstMatchingContainerSlot(menu, inventory);
        if (sourceMenuSlot < 0) return;

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                sourceMenuSlot,
                0,
                ContainerInput.QUICK_MOVE,
                minecraft.player
        );
    }

    private static void retrieveUpTo(
            Minecraft minecraft,
            AbstractContainerMenu menu,
            Inventory inventory,
            int remainingWanted
    ) {
        int remaining = Math.max(0, remainingWanted);
        if (remaining == 0) return;

        for (int menuSlot = 0; menuSlot < menu.slots.size() && remaining > 0; menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack source = slot.getItem();
            if (!isSelectedType(source)) continue;

            int sourceCount = source.getCount();
            if (sourceCount <= remaining) {
                int before = countSelectedInInventory(inventory);
                minecraft.gameMode.handleContainerInput(
                        menu.containerId,
                        menuSlot,
                        0,
                        ContainerInput.QUICK_MOVE,
                        minecraft.player
                );
                int moved = Math.max(0, countSelectedInInventory(inventory) - before);
                remaining -= moved;
                if (moved == 0) break;
            } else {
                int moved = retrievePartialFromSlot(minecraft, menu, inventory, menuSlot, remaining);
                remaining -= moved;
                break;
            }
        }
    }

    private static int retrievePartialFromSlot(
            Minecraft minecraft,
            AbstractContainerMenu menu,
            Inventory inventory,
            int sourceMenuSlot,
            int wanted
    ) {
        if (wanted <= 0) return 0;

        ItemStack source = menu.slots.get(sourceMenuSlot).getItem();
        if (!isSelectedType(source)) return 0;

        int before = countSelectedInInventory(inventory);

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                sourceMenuSlot,
                0,
                ContainerInput.PICKUP,
                minecraft.player
        );

        for (int i = 0; i < wanted; i++) {
            int destination = findPlayerDestinationSlot(menu, inventory, source);
            if (destination < 0) break;

            minecraft.gameMode.handleContainerInput(
                    menu.containerId,
                    destination,
                    1,
                    ContainerInput.PICKUP,
                    minecraft.player
            );
        }

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                sourceMenuSlot,
                0,
                ContainerInput.PICKUP,
                minecraft.player
        );

        return Math.max(0, countSelectedInInventory(inventory) - before);
    }

    private static void retrieveMaximum(
            Minecraft minecraft,
            AbstractContainerMenu menu,
            Inventory inventory
    ) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            if (!isSelectedType(slot.getItem())) continue;

            minecraft.gameMode.handleContainerInput(
                    menu.containerId,
                    menuSlot,
                    0,
                    ContainerInput.QUICK_MOVE,
                    minecraft.player
            );
        }
    }

    private static int findFirstMatchingContainerSlot(
            AbstractContainerMenu menu,
            Inventory inventory
    ) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            if (isSelectedType(slot.getItem())) return menuSlot;
        }
        return -1;
    }

    private static int findPlayerDestinationSlot(
            AbstractContainerMenu menu,
            Inventory inventory,
            ItemStack sourceStack
    ) {
        int firstEmpty = -1;

        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory) continue;
            if (!slot.mayPlace(sourceStack)) continue;

            ItemStack existing = slot.getItem();
            if (existing.isEmpty()) {
                if (firstEmpty < 0) firstEmpty = menuSlot;
                continue;
            }

            if (ItemStack.isSameItemSameComponents(existing, sourceStack)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(sourceStack))) {
                return menuSlot;
            }
        }

        return firstEmpty;
    }

    private static TargetChoice chooseTarget(Minecraft minecraft) {
        Map<BlockPos, IndexedContainer> snapshot = ContainerIndex.snapshot();

        for (IndexedContainer indexed : snapshot.values()) {
            if (attempted.contains(indexed.key())) continue;
            if (!containsSelected(indexed)) continue;

            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;

            return new TargetChoice(indexed, interactionPos);
        }

        return null;
    }

    private static boolean containsSelected(IndexedContainer indexed) {
        for (ItemStack stack : indexed.slots()) {
            if (isSelectedType(stack)) return true;
        }
        return false;
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
        if (selectedItem == null) return 0;

        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isSelectedType(ItemStack stack) {
        return selectedItem != null && !stack.isEmpty() && stack.getItem() == selectedItem;
    }

    private static void finish(Minecraft minecraft) {
        if (minecraft.player != null) {
            String itemName = sample.isEmpty() ? "item" : sample.getHoverName().getString();
            AutoChestChat.success(minecraft, "Retrieved " + retrievedTotal + " x " + itemName + ".");
            if ((amountMode == AmountMode.COUNT || amountMode == AmountMode.ONE_STACK)
                    && retrievedTotal < requestedCount) {
                AutoChestChat.debug(minecraft, "Requested " + requestedCount
                        + " but only " + retrievedTotal + " could be retrieved.");
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
        selectedItem = null;
        sample = ItemStack.EMPTY;
        amountMode = AmountMode.ONE_STACK;
        current = null;
        currentInteractionPos = null;
        attempted.clear();
        retrievedTotal = 0;
        requestedCount = 0;
    }

    private record TargetChoice(IndexedContainer container, BlockPos interactionPos) {}
}
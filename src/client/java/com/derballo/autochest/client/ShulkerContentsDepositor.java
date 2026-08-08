package com.derballo.autochest.client;

import com.derballo.autochest.client.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShulkerContentsDepositor {
    private enum State {
        IDLE,
        STAGE_SOURCE,
        WAITING_FOR_STAGE,
        OPEN_SHULKER,
        WAITING_FOR_SHULKER_MENU,
        WAITING_FOR_PLAYER_MENU,
        RESTORE_SOURCE_SLOT,
        WAITING_FOR_SOURCE_RESTORE,
        START_BATCH_TYPE,
        WAITING_FOR_BATCH_TYPE
    }

    private static final int STAGING_HOTBAR_INDEX = 8;
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static State state = State.IDLE;
    private static int ticksInState;
    private static int originalInventoryIndex = -1;
    private static int originalSelectedHotbar = -1;
    private static int previousMenuId = -1;
    private static final Set<Integer> batchCargoSlots = new LinkedHashSet<>();
    private static final List<Item> batchTypes = new ArrayList<>();
    private static int batchTypeIndex;
    private static long depositCompletionSerial;

    private ShulkerContentsDepositor() {}

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static boolean startFromHoveredSlot(Minecraft minecraft, AbstractContainerScreen<?> screen) {
        if (isRunning() || ContainerDepositor.isDepositing()) return false;
        if (minecraft.player == null || minecraft.gameMode == null) return false;

        Slot hovered = ((AbstractContainerScreenAccessor) screen).autochest$getHoveredSlot();
        Inventory inventory = minecraft.player.getInventory();
        if (hovered == null || hovered.container != inventory) return false;

        ItemStack shulker = hovered.getItem();
        if (!ShulkerBoxSupport.isShulkerBox(shulker) || ShulkerBoxSupport.isEmpty(shulker)) return false;

        originalInventoryIndex = resolveInventoryIndex(inventory, hovered);
        if (originalInventoryIndex < 0) return false;
        originalSelectedHotbar = inventory.getSelectedSlot();
        clearBatch();
        transition(State.STAGE_SOURCE);
        return true;
    }

    public static void tick(Minecraft minecraft) {
        if (state == State.IDLE) return;
        if (minecraft.player == null || minecraft.gameMode == null) {
            abort();
            return;
        }

        ticksInState++;
        switch (state) {
            case STAGE_SOURCE -> stageSource(minecraft);
            case WAITING_FOR_STAGE -> waitForStage(minecraft);
            case OPEN_SHULKER -> openShulker(minecraft);
            case WAITING_FOR_SHULKER_MENU -> waitForShulkerMenu(minecraft);
            case WAITING_FOR_PLAYER_MENU -> waitForPlayerMenu(minecraft);
            case RESTORE_SOURCE_SLOT -> restoreSourceSlot(minecraft);
            case WAITING_FOR_SOURCE_RESTORE -> waitForSourceRestore(minecraft);
            case START_BATCH_TYPE -> startBatchType(minecraft);
            case WAITING_FOR_BATCH_TYPE -> waitForBatchType(minecraft);
            case IDLE -> { }
        }
    }

    private static void stageSource(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        ItemStack source = inventory.getItem(originalInventoryIndex);
        if (!ShulkerBoxSupport.isShulkerBox(source)) {
            fail(minecraft, "Could not find the source shulker in its original inventory slot.");
            return;
        }

        clearBatch();

        if (ShulkerBoxSupport.isEmpty(source)) {
            finish(minecraft);
            return;
        }

        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            minecraft.player.closeContainer();
            return;
        }

        if (originalInventoryIndex == STAGING_HOTBAR_INDEX) {
            previousMenuId = minecraft.player.containerMenu.containerId;
            transition(State.OPEN_SHULKER);
            return;
        }

        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int sourceMenuSlot = playerMenuSlotByInventoryIndex(menu, originalInventoryIndex);
        if (sourceMenuSlot < 0) {
            if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not stage the source shulker in hotbar slot 9.");
            return;
        }

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                sourceMenuSlot,
                STAGING_HOTBAR_INDEX,
                ContainerInput.SWAP,
                minecraft.player
        );
        transition(State.WAITING_FOR_STAGE);
    }

    private static void waitForStage(Minecraft minecraft) {
        if (ShulkerBoxSupport.isShulkerBox(minecraft.player.getInventory().getItem(STAGING_HOTBAR_INDEX))) {
            previousMenuId = minecraft.player.containerMenu.containerId;
            if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
            transition(State.OPEN_SHULKER);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not stage the source shulker in hotbar slot 9.");
    }

    private static void openShulker(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        if (!ShulkerBoxSupport.isShulkerBox(inventory.getItem(STAGING_HOTBAR_INDEX))) {
            fail(minecraft, "Could not find the source shulker in hotbar slot 9.");
            return;
        }

        inventory.setSelectedSlot(STAGING_HOTBAR_INDEX);
        previousMenuId = minecraft.player.containerMenu.containerId;
        minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        transition(State.WAITING_FOR_SHULKER_MENU);
    }

    private static void waitForShulkerMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != previousMenuId) {
            takeBatch(minecraft, menu);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not open the source shulker.");
    }

    private static void takeBatch(Minecraft minecraft, AbstractContainerMenu menu) {
        Inventory inventory = minecraft.player.getInventory();
        clearBatch();

        List<Integer> destinations = emptyPlayerMenuSlots(menu, inventory);
        if (destinations.isEmpty()) {
            fail(minecraft, "At least one free player inventory slot is required to deposit shulker contents.");
            return;
        }

        Map<Item, List<Integer>> sourceSlotsByType = new LinkedHashMap<>();
        for (int sourceMenuSlot = 0; sourceMenuSlot < menu.slots.size(); sourceMenuSlot++) {
            Slot slot = menu.slots.get(sourceMenuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            sourceSlotsByType.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(sourceMenuSlot);
        }

        int destinationIndex = 0;
        for (Map.Entry<Item, List<Integer>> entry : sourceSlotsByType.entrySet()) {
            if (destinationIndex >= destinations.size()) break;
            boolean movedType = false;
            for (int sourceMenuSlot : entry.getValue()) {
                if (destinationIndex >= destinations.size()) break;
                int destinationMenuSlot = destinations.get(destinationIndex++);
                Slot destinationSlot = menu.slots.get(destinationMenuSlot);
                int inventoryIndex = destinationSlot.getContainerSlot();

                minecraft.gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, minecraft.player);
                minecraft.gameMode.handleContainerInput(menu.containerId, destinationMenuSlot, 0, ContainerInput.PICKUP, minecraft.player);

                batchCargoSlots.add(inventoryIndex);
                movedType = true;
            }
            if (movedType) batchTypes.add(entry.getKey());
        }

        batchTypeIndex = 0;
        previousMenuId = menu.containerId;
        minecraft.player.closeContainer();
        transition(State.WAITING_FOR_PLAYER_MENU);
    }

    private static void waitForPlayerMenu(Minecraft minecraft) {
        if (minecraft.player.containerMenu instanceof InventoryMenu) {
            transition(State.RESTORE_SOURCE_SLOT);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not return to the player inventory after closing the source shulker.");
    }

    private static void restoreSourceSlot(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();

        if (originalInventoryIndex == STAGING_HOTBAR_INDEX) {
            if (originalSelectedHotbar >= 0) inventory.setSelectedSlot(originalSelectedHotbar);
            rebuildBatchTypes(inventory);
            if (batchCargoSlots.isEmpty()) transition(State.STAGE_SOURCE);
            else transition(State.START_BATCH_TYPE);
            return;
        }

        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int destinationMenuSlot = playerMenuSlotByInventoryIndex(menu, originalInventoryIndex);
        if (destinationMenuSlot < 0) {
            fail(minecraft, "Could not restore the source shulker to its original inventory slot.");
            return;
        }

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                destinationMenuSlot,
                STAGING_HOTBAR_INDEX,
                ContainerInput.SWAP,
                minecraft.player
        );
        transition(State.WAITING_FOR_SOURCE_RESTORE);
    }

    private static void waitForSourceRestore(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        if (ShulkerBoxSupport.isShulkerBox(inventory.getItem(originalInventoryIndex))) {
            if (originalSelectedHotbar >= 0) inventory.setSelectedSlot(originalSelectedHotbar);
            rebuildBatchTypes(inventory);
            if (batchCargoSlots.isEmpty()) transition(State.STAGE_SOURCE);
            else transition(State.START_BATCH_TYPE);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not restore the source shulker to its original inventory slot.");
    }

    private static void startBatchType(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();

        while (batchTypeIndex < batchTypes.size()) {
            Item item = batchTypes.get(batchTypeIndex);
            Set<Integer> typeSlots = cargoSlotsForType(inventory, item);
            if (!typeSlots.isEmpty()) {
                ItemStack sample = inventory.getItem(typeSlots.iterator().next()).copy();
                depositCompletionSerial = ContainerDepositor.completionSerial();
                if (ContainerDepositor.startForBatchType(
                        minecraft,
                        sample,
                        Set.of(originalInventoryIndex),
                        typeSlots,
                        true
                )) {
                    transition(State.WAITING_FOR_BATCH_TYPE);
                    return;
                }
                if (ticksInState >= OPEN_TIMEOUT_TICKS) fail(minecraft, "Could not start depositing the current shulker-content type.");
                return;
            }
            batchTypeIndex++;
        }

        transition(State.STAGE_SOURCE);
    }

    private static void waitForBatchType(Minecraft minecraft) {
        if (ContainerDepositor.isDepositing()) return;
        if (ContainerDepositor.completionSerial() == depositCompletionSerial) return;

        Inventory inventory = minecraft.player.getInventory();
        Item item = batchTypes.get(batchTypeIndex);
        Set<Integer> remaining = cargoSlotsForType(inventory, item);
        if (!remaining.isEmpty()) {
            fail(minecraft, "Could not deposit all items of the current shulker-content type.");
            return;
        }

        batchCargoSlots.removeIf(index -> inventory.getItem(index).isEmpty());
        batchTypeIndex++;
        transition(State.START_BATCH_TYPE);
    }

    private static Set<Integer> cargoSlotsForType(Inventory inventory, Item item) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int inventoryIndex : batchCargoSlots) {
            ItemStack stack = inventory.getItem(inventoryIndex);
            if (!stack.isEmpty() && stack.getItem() == item) result.add(inventoryIndex);
        }
        return result;
    }

    private static List<Integer> emptyPlayerMenuSlots(AbstractContainerMenu menu, Inventory inventory) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != inventory) continue;
            int inventoryIndex = slot.getContainerSlot();
            if (inventoryIndex == STAGING_HOTBAR_INDEX || inventoryIndex == originalInventoryIndex) continue;
            if (slot.getItem().isEmpty()) result.add(i);
        }
        return result;
    }

    private static void rebuildBatchTypes(Inventory inventory) {
        batchTypes.clear();
        Set<Item> seen = new LinkedHashSet<>();
        batchCargoSlots.removeIf(index -> inventory.getItem(index).isEmpty());
        for (int inventoryIndex : batchCargoSlots) {
            ItemStack stack = inventory.getItem(inventoryIndex);
            if (!stack.isEmpty() && seen.add(stack.getItem())) batchTypes.add(stack.getItem());
        }
        batchTypeIndex = 0;
    }

    private static int playerMenuSlotByInventoryIndex(AbstractContainerMenu menu, int inventoryIndex) {
        if (!(menu instanceof InventoryMenu)) return -1;
        if (inventoryIndex >= 0 && inventoryIndex <= 8) {
            int menuSlot = 36 + inventoryIndex;
            return menuSlot < menu.slots.size() ? menuSlot : -1;
        }
        if (inventoryIndex >= 9 && inventoryIndex <= 35) {
            return inventoryIndex < menu.slots.size() ? inventoryIndex : -1;
        }
        return -1;
    }

    private static int resolveInventoryIndex(Inventory inventory, Slot slot) {
        ItemStack stack = slot.getItem();
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (inventory.getItem(i) == stack) return i;
        }
        int raw = slot.getContainerSlot();
        if (raw >= 0 && raw < Math.min(36, inventory.getContainerSize())) return raw;
        return -1;
    }

    private static void clearBatch() {
        batchCargoSlots.clear();
        batchTypes.clear();
        batchTypeIndex = 0;
    }

    private static void finish(Minecraft minecraft) {
        if (originalSelectedHotbar >= 0 && minecraft.player != null) minecraft.player.getInventory().setSelectedSlot(originalSelectedHotbar);
        abort();
        AutoChestChat.success(minecraft, "Deposited shulker contents.");
    }

    private static void fail(Minecraft minecraft, String message) {
        AutoChestChat.warning(minecraft, message);
        abort();
    }

    private static void transition(State next) {
        state = next;
        ticksInState = 0;
    }

    private static void abort() {
        state = State.IDLE;
        ticksInState = 0;
        originalInventoryIndex = -1;
        originalSelectedHotbar = -1;
        previousMenuId = -1;
        clearBatch();
    }
}

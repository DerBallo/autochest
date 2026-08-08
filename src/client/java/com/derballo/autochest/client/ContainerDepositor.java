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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContainerDepositor {
    private enum State {
        IDLE,
        SELECT_TARGET,
        WAITING_FOR_OUTER_MENU,
        WAITING_FOR_SHULKER_IN_HOTBAR,
        OPEN_SHULKER,
        WAITING_FOR_SHULKER_MENU,
        PACK_SELECT_SOURCE,
        PACK_WAITING_FOR_SOURCE_MENU,
        PACK_WAITING_FOR_SOURCE_TRANSFER,
        SELECT_SHULKER_STORAGE,
        WAITING_FOR_STORAGE_MENU,
        WAITING_FOR_SHULKER_STORED,
        OPEN_STAGING_ORIGIN,
        WAITING_FOR_STAGING_ORIGIN_MENU,
        WAITING_FOR_HOTBAR_RESTORED
    }

    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static State state = State.IDLE;
    private static int ticksInState;
    private static ItemStack selected = ItemStack.EMPTY;
    private static TargetChoice current;
    private static final Set<AttemptKey> attempted = new HashSet<>();
    private static int startingCount;
    private static ScreenReturnContext returnContext = ScreenReturnContext.none();
    private static final int STAGING_HOTBAR_INDEX = 8;
    private static int stagingHotbarIndex = STAGING_HOTBAR_INDEX;
    private static int originalSelectedHotbar = -1;
    private static ItemStack modifiedShulker = ItemStack.EMPTY;
    private static int pendingPackedMoved;
    private static TargetChoice packSource;
    private static int[] packInventoryBeforeCounts = new int[36];
    private static int pendingPackSourceMoved;
    private static TargetChoice storageTarget;
    private static int storageDestinationOrdinal = -1;
    private static ItemStack originalStagingHotbarStack = ItemStack.EMPTY;
    private static int previousMenuId = -1;
    private static Set<Integer> reservedInventorySlots = Set.of();
    private static Set<Integer> cargoInventorySlots = Set.of();
    private static final Set<Integer> packingPulledInventorySlots = new HashSet<>();
    private static boolean restrictCargoSlots;
    private static boolean allowShulkerTargets = true;
    private static long completionSerial;

    private ContainerDepositor() {}

    public static boolean isDepositing() {
        return state != State.IDLE;
    }

    public static long completionSerial() {
        return completionSerial;
    }

    public static boolean startForItem(Minecraft minecraft, ItemStack stack) {
        return startForItem(minecraft, stack, Set.of(), true);
    }

    public static boolean startForItem(Minecraft minecraft, ItemStack stack, Set<Integer> reservedSlots, boolean shulkerTargetsAllowed) {
        return startForItem(minecraft, stack, reservedSlots, Set.of(), false, shulkerTargetsAllowed);
    }

    public static boolean startForBatchType(Minecraft minecraft, ItemStack stack, Set<Integer> reservedSlots, Set<Integer> cargoSlots, boolean shulkerTargetsAllowed) {
        return startForItem(minecraft, stack, reservedSlots, cargoSlots, true, shulkerTargetsAllowed);
    }

    public static boolean startForItem(Minecraft minecraft, ItemStack stack, Set<Integer> reservedSlots, Set<Integer> cargoSlots, boolean restrictToCargoSlots, boolean shulkerTargetsAllowed) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) return false;
        if (isDepositing() || stack == null || stack.isEmpty() || ContainerIndex.size() == 0) return false;

        selected = stack.copy();
        reservedInventorySlots = reservedSlots == null ? Set.of() : Set.copyOf(reservedSlots);
        cargoInventorySlots = cargoSlots == null ? Set.of() : Set.copyOf(cargoSlots);
        packingPulledInventorySlots.clear();
        restrictCargoSlots = restrictToCargoSlots;
        allowShulkerTargets = shulkerTargetsAllowed;
        attempted.clear();
        current = null;
        startingCount = countSelectedInInventory(minecraft.player.getInventory());
        if (startingCount <= 0) {
            reservedInventorySlots = Set.of();
            cargoInventorySlots = Set.of();
            restrictCargoSlots = false;
            allowShulkerTargets = true;
            return false;
        }

        returnContext = ScreenReturnContext.none();
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
        transition(State.SELECT_TARGET);
        return true;
    }

    public static boolean startFromHoveredSlot(Minecraft minecraft, AbstractContainerScreen<?> screen) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) return false;
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
        reservedInventorySlots = Set.of();
        cargoInventorySlots = Set.of();
        packingPulledInventorySlots.clear();
        restrictCargoSlots = false;
        allowShulkerTargets = true;
        attempted.clear();
        current = null;
        startingCount = countSelectedInInventory(inventory);
        if (startingCount <= 0) return false;

        returnContext = ScreenReturnContext.capture(minecraft, screen);
        returnContext.prepare(minecraft);
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
            case WAITING_FOR_OUTER_MENU -> waitForOuterMenu(minecraft);
            case WAITING_FOR_SHULKER_IN_HOTBAR -> waitForShulkerInHotbar(minecraft);
            case OPEN_SHULKER -> openStagedShulker(minecraft);
            case WAITING_FOR_SHULKER_MENU -> waitForShulkerMenu(minecraft);
            case PACK_SELECT_SOURCE -> packSelectSource(minecraft);
            case PACK_WAITING_FOR_SOURCE_MENU -> packWaitForSourceMenu(minecraft);
            case PACK_WAITING_FOR_SOURCE_TRANSFER -> packWaitForSourceTransfer(minecraft);
            case SELECT_SHULKER_STORAGE -> selectShulkerStorage(minecraft);
            case WAITING_FOR_STORAGE_MENU -> waitForStorageMenu(minecraft);
            case WAITING_FOR_SHULKER_STORED -> waitForShulkerStored(minecraft);
            case OPEN_STAGING_ORIGIN -> openStagingOrigin(minecraft);
            case WAITING_FOR_STAGING_ORIGIN_MENU -> waitForStagingOriginMenu(minecraft);
            case WAITING_FOR_HOTBAR_RESTORED -> waitForHotbarRestored(minecraft);
            case IDLE -> { }
        }
    }

    private static void selectAndOpenTarget(Minecraft minecraft) {
        int remaining = countSelectedInInventory(minecraft.player.getInventory());
        if (remaining <= 0 && !shouldPackIntoShulker()) {
            finish(minecraft, 0);
            return;
        }

        TargetChoice choice = chooseTarget(minecraft);
        if (choice == null) {
            finish(minecraft, remaining);
            return;
        }

        current = choice;
        if (choice.shulkerSlot() < 0) attempted.add(new AttemptKey(choice.container().key(), choice.shulkerSlot()));
        openBlock(minecraft, choice.interactionPos());
        transition(State.WAITING_FOR_OUTER_MENU);
    }

    private static void waitForOuterMenu(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            if (current != null && current.shulkerSlot() >= 0) extractShulker(minecraft);
            else transferIntoOpenMenu(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            current = null;
            transition(State.SELECT_TARGET);
        }
    }

    private static void transferIntoOpenMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        if (menu instanceof InventoryMenu || current == null) {
            transition(State.SELECT_TARGET);
            return;
        }

        int before = countSelectedInInventory(inventory);
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory || !isCargoInventorySlot(slot.getContainerSlot()) || !isSelectedType(slot.getItem())) continue;
            minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        }

        int after = countSelectedInInventory(inventory);
        int moved = Math.max(0, before - after);
        BlockPos completedKey = current.container().key();
        ContainerIndexSynchronizer.forgetForModMutation(completedKey);
        minecraft.player.closeContainer();
        if (moved > 0) ContainerIndex.applyDeposited(completedKey, selected, moved);
        current = null;
        if (after <= 0) finish(minecraft, 0);
        else transition(State.SELECT_TARGET);
    }

    private static void extractShulker(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        ItemStack indexedShulker = current.shulkerSlot() >= 0 && current.shulkerSlot() < current.container().slots().size()
                ? current.container().slots().get(current.shulkerSlot())
                : ItemStack.EMPTY;
        boolean requireEmpty = ShulkerBoxSupport.isEmpty(indexedShulker);
        int sourceMenuSlot = findLiveShulkerMenuSlot(menu, inventory, current.shulkerSlot(), requireEmpty);
        if (sourceMenuSlot < 0) {
            minecraft.player.closeContainer();
            current = null;
            transition(State.SELECT_TARGET);
            return;
        }

        originalSelectedHotbar = inventory.getSelectedSlot();
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalStagingHotbarStack = inventory.getItem(stagingHotbarIndex).copy();
        previousMenuId = menu.containerId;

        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                sourceMenuSlot,
                stagingHotbarIndex,
                ContainerInput.SWAP,
                minecraft.player
        );

        transition(State.WAITING_FOR_SHULKER_IN_HOTBAR);
    }

    private static void waitForShulkerInHotbar(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        ItemStack staged = inventory.getItem(stagingHotbarIndex);

        if (ShulkerBoxSupport.isShulkerBox(staged)) {
            ContainerIndexSynchronizer.forgetForModMutation(current.container().key());
            minecraft.player.closeContainer();
            inventory.setSelectedSlot(stagingHotbarIndex);
            transition(State.OPEN_SHULKER);
            return;
        }

        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            minecraft.player.closeContainer();
            failPackedShulker(minecraft);
        }
    }
    private static void openStagedShulker(Minecraft minecraft) {
        ItemStack held = minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!ShulkerBoxSupport.isShulkerBox(held)) {
            failPackedShulker(minecraft);
            return;
        }
        minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        transition(State.WAITING_FOR_SHULKER_MENU);
    }

    private static void waitForShulkerMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != previousMenuId) {
            fillOpenShulker(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) failPackedShulker(minecraft);
    }

    private static void fillOpenShulker(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        ItemStack stagedBefore = inventory.getItem(stagingHotbarIndex).copy();
        ItemStack packingState = modifiedShulker.isEmpty() ? stagedBefore : modifiedShulker;
        int capacityBefore = ShulkerBoxSupport.remainingCapacityFor(packingState, selected);
        int availableBefore = countSelectedForPackingExcludingStaging(inventory);
        int plannedMoved = Math.min(capacityBefore, availableBefore);

        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory || slot.getContainerSlot() == STAGING_HOTBAR_INDEX || !isPackingCargoInventorySlot(slot.getContainerSlot()) || !isSelectedType(slot.getItem())) continue;
            minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        }

        pendingPackedMoved += plannedMoved;
        modifiedShulker = ShulkerBoxSupport.applyDeposited(packingState, selected, plannedMoved);
        packingPulledInventorySlots.clear();
        previousMenuId = menu.containerId;
        minecraft.player.closeContainer();

        if (ShulkerBoxSupport.countItem(modifiedShulker, selected.getItem()) >= ShulkerBoxSupport.capacityFor(selected)) {
            transition(State.SELECT_SHULKER_STORAGE);
            return;
        }

        transition(State.PACK_SELECT_SOURCE);
    }

    private static void packSelectSource(Minecraft minecraft) {
        TargetChoice source = choosePackingSource(minecraft);
        if (source == null) {
            transition(State.SELECT_SHULKER_STORAGE);
            return;
        }

        packSource = source;
        int safeSelected = originalSelectedHotbar == STAGING_HOTBAR_INDEX ? 0 : originalSelectedHotbar;
        if (safeSelected >= 0) minecraft.player.getInventory().setSelectedSlot(safeSelected);
        openBlock(minecraft, source.interactionPos());
        transition(State.PACK_WAITING_FOR_SOURCE_MENU);
    }

    private static void packWaitForSourceMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != previousMenuId) {
            Inventory inventory = minecraft.player.getInventory();
            int shulkerRemaining = ShulkerBoxSupport.remainingCapacityFor(modifiedShulker, selected);
            int freeCapacity = Math.min(freePlayerCapacityForPacking(inventory), shulkerRemaining);
            int plannedMoved = 0;

            for (int i = 0; i < packInventoryBeforeCounts.length; i++) {
                ItemStack stack = inventory.getItem(i);
                packInventoryBeforeCounts[i] = isSelectedType(stack) ? stack.getCount() : 0;
            }

            if (freeCapacity > 0) {
                for (int menuSlot = 0; menuSlot < menu.slots.size() && freeCapacity > 0; menuSlot++) {
                    Slot slot = menu.slots.get(menuSlot);
                    if (slot.container == inventory) continue;
                    ItemStack source = slot.getItem();
                    if (!isSelectedType(source)) continue;

                    int sourceCount = source.getCount();
                    int wanted = Math.min(sourceCount, freeCapacity);
                    if (wanted <= 0) continue;

                    if (wanted == sourceCount) {
                        minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
                    } else {
                        pickup(minecraft, menu, menuSlot, 0);
                        int placed = 0;
                        for (int i = 0; i < wanted; i++) {
                            int destination = findPlayerDestinationSlotForPacking(menu, inventory, source);
                            if (destination < 0) break;
                            pickup(minecraft, menu, destination, 1);
                            placed++;
                        }
                        pickup(minecraft, menu, menuSlot, 0);
                        wanted = placed;
                    }

                    plannedMoved += wanted;
                    freeCapacity -= wanted;
                }
            }

            if (plannedMoved <= 0) {
                previousMenuId = menu.containerId;
                minecraft.player.closeContainer();
                packSource = null;
                transition(State.SELECT_SHULKER_STORAGE);
                return;
            }

            pendingPackSourceMoved = plannedMoved;
            transition(State.PACK_WAITING_FOR_SOURCE_TRANSFER);
            return;
        }

        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            packSource = null;
            transition(State.SELECT_SHULKER_STORAGE);
        }
    }

    private static void packWaitForSourceTransfer(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        int moved = 0;
        packingPulledInventorySlots.clear();

        for (int i = 0; i < packInventoryBeforeCounts.length; i++) {
            if (i == STAGING_HOTBAR_INDEX || isReservedInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            int now = isSelectedType(stack) ? stack.getCount() : 0;
            int delta = Math.max(0, now - packInventoryBeforeCounts[i]);
            if (delta > 0) {
                moved += delta;
                packingPulledInventorySlots.add(i);
            }
        }

        if (moved < pendingPackSourceMoved && ticksInState < OPEN_TIMEOUT_TICKS) return;

        if (moved > 0 && packSource != null) {
            ContainerIndexSynchronizer.forgetForModMutation(packSource.container().key());
            ContainerIndex.applyRetrieved(packSource.container().key(), selected.getItem(), moved);
        }

        previousMenuId = minecraft.player.containerMenu.containerId;
        minecraft.player.closeContainer();
        packSource = null;
        pendingPackSourceMoved = 0;

        if (moved <= 0) {
            transition(State.SELECT_SHULKER_STORAGE);
            return;
        }

        inventory.setSelectedSlot(STAGING_HOTBAR_INDEX);
        transition(State.OPEN_SHULKER);
    }

    private static void selectShulkerStorage(Minecraft minecraft) {
        storageTarget = chooseShulkerStorageTarget(minecraft);
        if (storageTarget == null) {
            AutoChestChat.warning(minecraft, "Could not find space to store the filled shulker.");
            failPackedShulker(minecraft);
            return;
        }
        openBlock(minecraft, storageTarget.interactionPos());
        transition(State.WAITING_FOR_STORAGE_MENU);
    }

    private static void waitForStorageMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != previousMenuId) {
            Inventory inventory = minecraft.player.getInventory();
            int excludedOrdinal = storageTarget != null && current != null
                    && storageTarget.container().key().equals(current.container().key())
                    ? current.shulkerSlot() : -1;
            int destinationMenuSlot = findEmptyContainerMenuSlot(menu, inventory, excludedOrdinal);
            if (destinationMenuSlot < 0) {
                previousMenuId = menu.containerId;
                minecraft.player.closeContainer();
                storageTarget = null;
                transition(State.SELECT_SHULKER_STORAGE);
                return;
            }

            storageDestinationOrdinal = containerOrdinalForMenuSlot(menu, inventory, destinationMenuSlot);
            minecraft.gameMode.handleContainerInput(
                    menu.containerId,
                    destinationMenuSlot,
                    stagingHotbarIndex,
                    ContainerInput.SWAP,
                    minecraft.player
            );
            transition(State.WAITING_FOR_SHULKER_STORED);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            storageTarget = null;
            transition(State.SELECT_SHULKER_STORAGE);
        }
    }

    private static void waitForShulkerStored(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        if (!ShulkerBoxSupport.isShulkerBox(inventory.getItem(stagingHotbarIndex))) {
            if (storageTarget != null && storageDestinationOrdinal >= 0) {
                ContainerIndexSynchronizer.forgetForModMutation(storageTarget.container().key());
                ContainerIndex.replaceSlot(storageTarget.container().key(), storageDestinationOrdinal, modifiedShulker);
            }

            if (originalStagingHotbarStack.isEmpty()) {
                if (current != null) {
                    ContainerIndexSynchronizer.forgetForModMutation(current.container().key());
                    ContainerIndex.replaceSlot(current.container().key(), current.shulkerSlot(), ItemStack.EMPTY);
                }
                minecraft.player.closeContainer();
                finishPackedCycle(minecraft);
                return;
            }

            if (storageTarget != null && current != null && storageTarget.container().key().equals(current.container().key())) {
                restoreHotbarFromOpenOrigin(minecraft);
                return;
            }

            previousMenuId = minecraft.player.containerMenu.containerId;
            minecraft.player.closeContainer();
            transition(State.OPEN_STAGING_ORIGIN);
            return;
        }

        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Filled shulker storage was not confirmed.");
            failPackedShulker(minecraft);
        }
    }

    private static void openStagingOrigin(Minecraft minecraft) {
        if (current == null) {
            failPackedShulker(minecraft);
            return;
        }
        openBlock(minecraft, current.interactionPos());
        transition(State.WAITING_FOR_STAGING_ORIGIN_MENU);
    }

    private static void waitForStagingOriginMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != previousMenuId) {
            restoreHotbarFromOpenOrigin(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Could not reopen the staging chest to restore hotbar slot 9.");
            failPackedShulker(minecraft);
        }
    }

    private static void restoreHotbarFromOpenOrigin(Minecraft minecraft) {
        if (current == null) {
            failPackedShulker(minecraft);
            return;
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        int originMenuSlot = nthContainerMenuSlot(menu, inventory, current.shulkerSlot());
        if (originMenuSlot < 0) {
            AutoChestChat.warning(minecraft, "Could not locate the original shulker slot to restore hotbar slot 9.");
            failPackedShulker(minecraft);
            return;
        }
        minecraft.gameMode.handleContainerInput(
                menu.containerId,
                originMenuSlot,
                stagingHotbarIndex,
                ContainerInput.SWAP,
                minecraft.player
        );
        transition(State.WAITING_FOR_HOTBAR_RESTORED);
    }

    private static void waitForHotbarRestored(Minecraft minecraft) {
        ItemStack restored = minecraft.player.getInventory().getItem(stagingHotbarIndex);
        if (sameStack(restored, originalStagingHotbarStack)) {
            if (current != null) {
                ContainerIndexSynchronizer.forgetForModMutation(current.container().key());
                ContainerIndex.replaceSlot(current.container().key(), current.shulkerSlot(), ItemStack.EMPTY);
            }
            minecraft.player.closeContainer();
            finishPackedCycle(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Hotbar slot 9 restoration was not confirmed.");
            failPackedShulker(minecraft);
        }
    }

    private static void finishPackedCycle(Minecraft minecraft) {
        if (originalSelectedHotbar >= 0) minecraft.player.getInventory().setSelectedSlot(originalSelectedHotbar);
        if (current != null) attempted.add(new AttemptKey(current.container().key(), current.shulkerSlot()));
        current = null;
        storageTarget = null;
        storageDestinationOrdinal = -1;
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        originalStagingHotbarStack = ItemStack.EMPTY;
        modifiedShulker = ItemStack.EMPTY;
        pendingPackedMoved = 0;
        int remaining = countSelectedInInventory(minecraft.player.getInventory());
        if (remaining <= 0 && !shouldPackIntoShulker()) finish(minecraft, 0);
        else transition(State.SELECT_TARGET);
    }

    private static void failPackedShulker(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
        if (originalSelectedHotbar >= 0) minecraft.player.getInventory().setSelectedSlot(originalSelectedHotbar);
        current = null;
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        modifiedShulker = ItemStack.EMPTY;
        pendingPackedMoved = 0;
        transition(State.SELECT_TARGET);
    }

    private static TargetChoice chooseShulkerStorageTarget(Minecraft minecraft) {
        ShulkerStorageRouter.Origin origin = current == null
                ? null
                : new ShulkerStorageRouter.Origin(current.container().key(), current.shulkerSlot());
        ShulkerStorageRouter.Destination destination = ShulkerStorageRouter.choose(minecraft, modifiedShulker, origin);
        if (destination == null) return null;
        return new TargetChoice(destination.container(), destination.interactionPos(), -1);
    }

    private static int findEmptyContainerMenuSlot(AbstractContainerMenu menu, Inventory inventory, int excludedOrdinal) {
        int ordinal = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            if (ordinal != excludedOrdinal && slot.getItem().isEmpty()) return i;
            ordinal++;
        }
        return -1;
    }

    private static int containerOrdinalForMenuSlot(AbstractContainerMenu menu, Inventory inventory, int menuSlot) {
        int ordinal = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            if (i == menuSlot) return ordinal;
            ordinal++;
        }
        return -1;
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    private static TargetChoice chooseTarget(Minecraft minecraft) {
        if (allowShulkerTargets) {
            TargetChoice partialShulker = chooseNonFullMatchingShulker(minecraft);
            if (partialShulker != null) return partialShulker;
        }

        List<TargetChoice> matchingChests = new ArrayList<>();
        List<TargetChoice> emptyChests = new ArrayList<>();

        for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
            AttemptKey attemptKey = new AttemptKey(indexed.key(), -1);
            if (attempted.contains(attemptKey)) continue;
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
                if (isSelectedType(stack)) containsType = true;
                if (ItemStack.isSameItemSameComponents(stack, selected)) {
                    capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
                }
            }

            TargetChoice choice = new TargetChoice(indexed, interactionPos, -1);
            if (containsType && capacity > 0) matchingChests.add(choice);
            if (completelyEmpty && capacity > 0) emptyChests.add(choice);
        }

        if (allowShulkerTargets && shouldPackIntoShulker()) {
            TargetChoice emptyShulker = chooseEmptyShulker(minecraft);
            if (emptyShulker != null) return emptyShulker;
        }

        matchingChests.sort(Comparator.comparingInt((TargetChoice choice) -> directItemCount(choice.container(), selected.getItem())).reversed());
        if (!matchingChests.isEmpty()) return matchingChests.getFirst();

        return emptyChests.isEmpty() ? null : emptyChests.getFirst();
    }

    private static TargetChoice chooseNonFullMatchingShulker(Minecraft minecraft) {
        List<TargetChoice> candidates = new ArrayList<>();
        for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;
            for (int i = 0; i < indexed.slots().size(); i++) {
                AttemptKey key = new AttemptKey(indexed.key(), i);
                if (attempted.contains(key)) continue;
                ItemStack stack = indexed.slots().get(i);
                if (ShulkerBoxSupport.hasItemAndCapacity(stack, selected)) {
                    candidates.add(new TargetChoice(indexed, interactionPos, i));
                }
            }
        }
        candidates.sort(
                Comparator.comparingInt((TargetChoice choice) -> singleTypeShulkerCount(choice.container(), selected.getItem())).reversed()
                        .thenComparing(Comparator.comparingInt((TargetChoice choice) -> itemCountInsideSingleTypeShulkers(choice.container(), selected.getItem())).reversed())
                        .thenComparing(Comparator.comparingInt((TargetChoice choice) -> ShulkerBoxSupport.countItem(choice.container().slots().get(choice.shulkerSlot()), selected.getItem())).reversed())
        );
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static boolean shouldPackIntoShulker() {
        if (!allowShulkerTargets || selected.isEmpty() || ShulkerBoxSupport.isShulkerBox(selected)) return false;
        int stored = ContainerIndex.countItemDirect(selected.getItem());
        return stored + currentPlayerSelectedCount() >= ShulkerBoxSupport.capacityFor(selected);
    }

    private static int currentPlayerSelectedCount() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return 0;
        return countSelectedInInventory(minecraft.player.getInventory());
    }

    private static TargetChoice choosePackingSource(Minecraft minecraft) {
        List<TargetChoice> candidates = new ArrayList<>();
        for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;
            if (directItemCount(indexed, selected.getItem()) > 0) {
                candidates.add(new TargetChoice(indexed, interactionPos, -1));
            }
        }
        candidates.sort(Comparator.comparingInt((TargetChoice choice) -> directItemCount(choice.container(), selected.getItem())));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static TargetChoice chooseEmptyShulker(Minecraft minecraft) {
        List<TargetChoice> candidates = new ArrayList<>();
        for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;
            for (int i = 0; i < indexed.slots().size(); i++) {
                AttemptKey key = new AttemptKey(indexed.key(), i);
                if (attempted.contains(key)) continue;
                if (ShulkerBoxSupport.isEmpty(indexed.slots().get(i))) {
                    candidates.add(new TargetChoice(indexed, interactionPos, i));
                }
            }
        }
        candidates.sort(
                Comparator.comparingInt((TargetChoice choice) -> singleTypeShulkerCount(choice.container(), selected.getItem())).reversed()
                        .thenComparing(Comparator.comparingInt((TargetChoice choice) -> emptyShulkerCount(choice.container())).reversed())
        );
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static int directItemCount(IndexedContainer indexed, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : indexed.slots()) {
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static int singleTypeShulkerCount(IndexedContainer indexed, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : indexed.slots()) {
            if (ShulkerBoxSupport.containsOnlyItemType(stack, item)) count++;
        }
        return count;
    }

    private static int itemCountInsideSingleTypeShulkers(IndexedContainer indexed, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : indexed.slots()) {
            if (ShulkerBoxSupport.containsOnlyItemType(stack, item)) count += ShulkerBoxSupport.countItem(stack, item);
        }
        return count;
    }

    private static int emptyShulkerCount(IndexedContainer indexed) {
        int count = 0;
        for (ItemStack stack : indexed.slots()) {
            if (ShulkerBoxSupport.isEmpty(stack)) count++;
        }
        return count;
    }

    private static BlockPos firstReachableBlock(Minecraft minecraft, IndexedContainer indexed) {
        for (BlockPos pos : indexed.blocks()) {
            if (minecraft.player.isWithinBlockInteractionRange(pos, 0.0)) return pos;
        }
        return null;
    }

    private static void openBlock(Minecraft minecraft, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
    }

    private static void pickup(Minecraft minecraft, AbstractContainerMenu menu, int menuSlot, int button) {
        minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, button, ContainerInput.PICKUP, minecraft.player);
    }

    private static int findLiveShulkerMenuSlot(AbstractContainerMenu menu, Inventory inventory, int indexedOrdinal, boolean requireEmpty) {
        int preferred = nthContainerMenuSlot(menu, inventory, indexedOrdinal);
        if (preferred >= 0) {
            ItemStack stack = menu.slots.get(preferred).getItem();
            if (requireEmpty && ShulkerBoxSupport.isEmpty(stack)) return preferred;
            if (!requireEmpty && ShulkerBoxSupport.hasItemAndCapacity(stack, selected)) return preferred;
        }

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (requireEmpty && ShulkerBoxSupport.isEmpty(stack)) return i;
            if (!requireEmpty && ShulkerBoxSupport.hasItemAndCapacity(stack, selected)) return i;
        }

        return -1;
    }


    private static int nthContainerMenuSlot(AbstractContainerMenu menu, Inventory inventory, int ordinal) {
        int currentOrdinal = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            if (currentOrdinal == ordinal) return i;
            currentOrdinal++;
        }
        return -1;
    }

    private static int playerMenuSlotByInventoryIndex(AbstractContainerMenu menu, Inventory inventory, int inventoryIndex) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory && slot.index == inventoryIndex) return i;
        }
        return -1;
    }


    private static int freePlayerCapacityForSelected(Inventory inventory) {
        int capacity = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (i == STAGING_HOTBAR_INDEX || !isCargoInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                capacity += selected.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, selected)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    private static int freePlayerCapacityForPacking(Inventory inventory) {
        int capacity = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (i == STAGING_HOTBAR_INDEX || isReservedInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                capacity += selected.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, selected)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    private static int findPlayerDestinationSlot(AbstractContainerMenu menu, Inventory inventory, ItemStack sourceStack) {
        int firstEmpty = -1;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory || slot.getContainerSlot() == STAGING_HOTBAR_INDEX || !slot.mayPlace(sourceStack)) continue;
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


    private static int findPlayerDestinationSlotForPacking(AbstractContainerMenu menu, Inventory inventory, ItemStack sourceStack) {
        int firstEmpty = -1;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            int inventoryIndex = slot.getContainerSlot();
            if (slot.container != inventory || inventoryIndex == STAGING_HOTBAR_INDEX || isReservedInventorySlot(inventoryIndex) || !slot.mayPlace(sourceStack)) continue;
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

    private static Set<Integer> findPackingDestinationInventorySlots(AbstractContainerMenu menu, Inventory inventory, ItemStack sourceStack, int amount) {
        Set<Integer> result = new HashSet<>();
        int remaining = amount;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (Slot slot : menu.slots) {
                if (slot.container != inventory) continue;
                int inventoryIndex = slot.getContainerSlot();
                if (inventoryIndex == STAGING_HOTBAR_INDEX || isReservedInventorySlot(inventoryIndex) || !slot.mayPlace(sourceStack)) continue;
                ItemStack existing = slot.getItem();
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, sourceStack)) continue;
                    int free = Math.max(0, Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(sourceStack)) - existing.getCount());
                    if (free <= 0) continue;
                    result.add(inventoryIndex);
                    remaining -= Math.min(remaining, free);
                } else {
                    if (!existing.isEmpty()) continue;
                    result.add(inventoryIndex);
                    remaining -= Math.min(remaining, Math.min(sourceStack.getMaxStackSize(), slot.getMaxStackSize(sourceStack)));
                }
                if (remaining <= 0) break;
            }
        }
        return remaining <= 0 ? result : Set.of();
    }

    private static int countSelectedForPackingExcludingStaging(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (i == STAGING_HOTBAR_INDEX || !isPackingCargoInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int countSelectedInInventoryExcludingStaging(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (i == STAGING_HOTBAR_INDEX || !isCargoInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int countSelectedInInventory(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (!isCargoInventorySlot(i)) continue;
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isReservedInventorySlot(int index) {
        return reservedInventorySlots.contains(index);
    }

    private static boolean isCargoInventorySlot(int index) {
        if (isReservedInventorySlot(index)) return false;
        return !restrictCargoSlots || cargoInventorySlots.contains(index);
    }

    private static boolean isPackingCargoInventorySlot(int index) {
        if (isReservedInventorySlot(index)) return false;
        return isCargoInventorySlot(index) || packingPulledInventorySlots.contains(index);
    }

    private static boolean isSelectedType(ItemStack stack) {
        return !stack.isEmpty() && !selected.isEmpty() && stack.getItem() == selected.getItem();
    }

    private static void finish(Minecraft minecraft, int remaining) {
        int moved = Math.max(0, startingCount - remaining);
        if (minecraft.player != null) {
            if (remaining == 0) AutoChestChat.success(minecraft, "Deposited " + moved + " matching items.");
            else AutoChestChat.warning(minecraft, "Deposited " + moved + " items; " + remaining + " remain (no suitable indexed container).");
        }
        ScreenReturnContext context = returnContext;
        abort();
        completionSerial++;
        context.restore(minecraft);
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
        attempted.clear();
        startingCount = 0;
        returnContext = ScreenReturnContext.none();
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        modifiedShulker = ItemStack.EMPTY;
        pendingPackedMoved = 0;
        packSource = null;
        pendingPackSourceMoved = 0;
        packInventoryBeforeCounts = new int[36];
        storageTarget = null;
        storageDestinationOrdinal = -1;
        originalStagingHotbarStack = ItemStack.EMPTY;
        previousMenuId = -1;
        reservedInventorySlots = Set.of();
        cargoInventorySlots = Set.of();
        packingPulledInventorySlots.clear();
        restrictCargoSlots = false;
        allowShulkerTargets = true;
    }

    private record TargetChoice(IndexedContainer container, BlockPos interactionPos, int shulkerSlot) {}
    private record AttemptKey(BlockPos containerKey, int shulkerSlot) {}
}

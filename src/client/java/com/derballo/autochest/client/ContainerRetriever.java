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
    public enum AmountMode { ONE_STACK, MAX, COUNT }
    private enum State {
        IDLE,
        SELECT_TARGET,
        WAITING_FOR_OUTER_MENU,
        WAITING_FOR_SHULKER_IN_HOTBAR,
        OPEN_SHULKER,
        WAITING_FOR_SHULKER_MENU,
        SELECT_SHULKER_STORAGE,
        WAITING_FOR_STORAGE_MENU,
        WAITING_FOR_SHULKER_STORED,
        OPEN_STAGING_ORIGIN,
        WAITING_FOR_STAGING_ORIGIN_MENU,
        WAITING_FOR_HOTBAR_RESTORED,
        RETURN_TO_ORIGIN_FALLBACK,
        WAITING_FOR_FALLBACK_RETURN,
        RESTORE_PREVIOUS
    }

    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static State state = State.IDLE;
    private static int ticksInState;
    private static Item selectedItem;
    private static ItemStack sample = ItemStack.EMPTY;
    private static AmountMode amountMode = AmountMode.ONE_STACK;
    private static TargetChoice current;
    private static final Set<AttemptKey> attempted = new HashSet<>();
    private static int retrievedTotal;
    private static int requestedCount;
    private static boolean hotbarPreferencePending;
    private static ScreenReturnContext returnContext = ScreenReturnContext.none();
    private static final int STAGING_HOTBAR_INDEX = 8;
    private static int stagingHotbarIndex = STAGING_HOTBAR_INDEX;
    private static int originalSelectedHotbar = -1;
    private static ItemStack modifiedShulker = ItemStack.EMPTY;
    private static int pendingNestedMoved;
    private static int outerMenuId = -1;
    private static int originalOuterShulkerMenuSlot = -1;
    private static ItemStack originalStagingStack = ItemStack.EMPTY;
    private static TargetChoice shulkerStorageTarget;
    private static int shulkerStorageOrdinal = -1;

    private ContainerRetriever() {}

    public static boolean isRetrieving() {
        return state != State.IDLE;
    }

    public static boolean start(Minecraft minecraft, ItemStack requested, AmountMode mode) {
        return start(minecraft, requested, mode, ScreenReturnContext.none());
    }

    public static boolean start(Minecraft minecraft, ItemStack requested, AmountMode mode, ScreenReturnContext context) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) return false;
        if (requested == null || requested.isEmpty() || ContainerIndex.size() == 0 || isRetrieving()) return false;

        selectedItem = requested.getItem();
        sample = requested.copy();
        sample.setCount(1);
        amountMode = mode;
        attempted.clear();
        current = null;
        retrievedTotal = 0;
        requestedCount = mode == AmountMode.ONE_STACK ? sample.getMaxStackSize() : 0;
        hotbarPreferencePending = true;
        returnContext = context == null ? ScreenReturnContext.none() : context;
        returnContext.prepare(minecraft);
        transition(State.SELECT_TARGET);
        return true;
    }

    public static boolean start(Minecraft minecraft, ItemStack requested, int amount) {
        return start(minecraft, requested, amount, ScreenReturnContext.none());
    }

    public static boolean start(Minecraft minecraft, ItemStack requested, int amount, ScreenReturnContext context) {
        if (amount <= 0) return false;
        if (!start(minecraft, requested, AmountMode.COUNT, context)) return false;
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
            case WAITING_FOR_OUTER_MENU -> waitForOuterMenu(minecraft);
            case WAITING_FOR_SHULKER_IN_HOTBAR -> waitForShulkerInHotbar(minecraft);
            case OPEN_SHULKER -> openStagedShulker(minecraft);
            case WAITING_FOR_SHULKER_MENU -> waitForShulkerMenu(minecraft);
            case SELECT_SHULKER_STORAGE -> selectShulkerStorage(minecraft);
            case WAITING_FOR_STORAGE_MENU -> waitForStorageMenu(minecraft);
            case WAITING_FOR_SHULKER_STORED -> waitForShulkerStored(minecraft);
            case OPEN_STAGING_ORIGIN -> openStagingOrigin(minecraft);
            case WAITING_FOR_STAGING_ORIGIN_MENU -> waitForStagingOriginMenu(minecraft);
            case WAITING_FOR_HOTBAR_RESTORED -> waitForHotbarRestored(minecraft);
            case RETURN_TO_ORIGIN_FALLBACK -> returnToOriginFallback(minecraft);
            case WAITING_FOR_FALLBACK_RETURN -> waitForFallbackReturn(minecraft);
            case RESTORE_PREVIOUS -> restorePrevious(minecraft);
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

        current = choice;
        attempted.add(new AttemptKey(choice.container().key(), choice.shulkerSlot()));
        openBlock(minecraft, choice.interactionPos());
        transition(State.WAITING_FOR_OUTER_MENU);
    }

    private static void waitForOuterMenu(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            if (current != null && current.shulkerSlot() >= 0) extractShulker(minecraft);
            else retrieveFromOuterMenu(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            current = null;
            transition(State.SELECT_TARGET);
        }
    }

    private static void retrieveFromOuterMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        if (menu instanceof InventoryMenu || current == null) {
            transition(State.SELECT_TARGET);
            return;
        }

        int beforeInventoryCount = countSelectedInInventory(inventory);
        boolean handledOneStack = false;

        if (hotbarPreferencePending) {
            int hotbarSlot = findEmptyHotbarMenuSlot(menu, inventory);
            hotbarPreferencePending = false;
            if (hotbarSlot >= 0) {
                int limit = amountMode == AmountMode.COUNT
                        ? Math.max(0, requestedCount - retrievedTotal)
                        : Integer.MAX_VALUE;
                int movedToHotbar = moveFirstMatchingStackToSlot(minecraft, menu, inventory, hotbarSlot, limit);
                handledOneStack = movedToHotbar > 0 && amountMode == AmountMode.ONE_STACK;
            }
        }

        if (!handledOneStack) {
            int alreadyMoved = Math.max(0, countSelectedInInventory(inventory) - beforeInventoryCount);
            switch (amountMode) {
                case ONE_STACK -> retrieveOneStack(minecraft, menu, inventory);
                case MAX -> retrieveMaximum(minecraft, menu, inventory);
                case COUNT -> retrieveUpTo(minecraft, menu, inventory, Math.max(0, requestedCount - retrievedTotal - alreadyMoved));
            }
        }

        int moved = Math.max(0, countSelectedInInventory(inventory) - beforeInventoryCount);
        BlockPos completedKey = current.container().key();
        ContainerIndexSynchronizer.forgetForModMutation(completedKey);
        minecraft.player.closeContainer();
        if (moved > 0) {
            ContainerIndex.applyRetrieved(completedKey, selectedItem, moved);
            retrievedTotal += moved;
        }
        current = null;
        if (amountMode == AmountMode.ONE_STACK) finish(minecraft);
        else transition(State.SELECT_TARGET);
    }

    private static void extractShulker(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        int sourceMenuSlot = findLiveShulkerMenuSlot(menu, inventory, current.shulkerSlot(), false);
        if (sourceMenuSlot < 0) {
            minecraft.player.closeContainer();
            current = null;
            transition(State.SELECT_TARGET);
            return;
        }

        originalSelectedHotbar = inventory.getSelectedSlot();
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalOuterShulkerMenuSlot = sourceMenuSlot;
        originalStagingStack = inventory.getItem(stagingHotbarIndex).copy();
        outerMenuId = menu.containerId;

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
            failNestedAndRestoreHotbar(minecraft);
        }
    }
    private static void openStagedShulker(Minecraft minecraft) {
        ItemStack held = minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!ShulkerBoxSupport.isShulkerBox(held)) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        transition(State.WAITING_FOR_SHULKER_MENU);
    }

    private static void waitForShulkerMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != outerMenuId) {
            retrieveFromShulkerMenu(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) failNestedAndRestoreHotbar(minecraft);
    }

    private static void retrieveFromShulkerMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        int remaining = amountMode == AmountMode.COUNT
                ? Math.max(0, requestedCount - retrievedTotal)
                : amountMode == AmountMode.ONE_STACK ? sample.getMaxStackSize() : Integer.MAX_VALUE;

        int plannedMoved = 0;
        if (hotbarPreferencePending) {
            int hotbarSlot = findEmptyHotbarMenuSlot(menu, inventory);
            hotbarPreferencePending = false;
            if (hotbarSlot >= 0 && remaining > 0) {
                int movedToHotbar = planFirstMatchingStackToSlot(minecraft, menu, inventory, hotbarSlot, remaining);
                plannedMoved += movedToHotbar;
                remaining = Math.max(0, remaining - movedToHotbar);
            }
        }

        if (amountMode == AmountMode.ONE_STACK) {
            if (plannedMoved == 0) plannedMoved += planOneStack(minecraft, menu, inventory, remaining);
        } else if (amountMode == AmountMode.COUNT) {
            plannedMoved += planRetrieveUpTo(minecraft, menu, inventory, remaining);
        } else {
            plannedMoved += planRetrieveMaximum(minecraft, menu, inventory);
        }

        pendingNestedMoved = plannedMoved;
        ItemStack stagedBefore = inventory.getItem(stagingHotbarIndex).copy();
        modifiedShulker = ShulkerBoxSupport.applyRetrieved(stagedBefore, selectedItem, plannedMoved);
        outerMenuId = menu.containerId;
        minecraft.player.closeContainer();
        transition(State.SELECT_SHULKER_STORAGE);
    }

    private static void selectShulkerStorage(Minecraft minecraft) {
        if (current == null || modifiedShulker.isEmpty()) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        ShulkerStorageRouter.Origin origin = new ShulkerStorageRouter.Origin(current.container().key(), current.shulkerSlot());
        ShulkerStorageRouter.Destination destination = ShulkerStorageRouter.choose(minecraft, modifiedShulker, origin);
        if (destination == null) {
            transition(State.RETURN_TO_ORIGIN_FALLBACK);
            return;
        }
        shulkerStorageTarget = new TargetChoice(destination.container(), destination.interactionPos(), -1);
        openBlock(minecraft, shulkerStorageTarget.interactionPos());
        transition(State.WAITING_FOR_STORAGE_MENU);
    }

    private static void waitForStorageMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != outerMenuId) {
            Inventory inventory = minecraft.player.getInventory();
            int excludedOrdinal = shulkerStorageTarget != null && current != null
                    && shulkerStorageTarget.container().key().equals(current.container().key())
                    ? current.shulkerSlot() : -1;
            int destinationMenuSlot = findEmptyContainerMenuSlot(menu, inventory, excludedOrdinal);
            if (destinationMenuSlot < 0) {
                outerMenuId = menu.containerId;
                minecraft.player.closeContainer();
                shulkerStorageTarget = null;
                transition(State.SELECT_SHULKER_STORAGE);
                return;
            }
            shulkerStorageOrdinal = containerOrdinalForMenuSlot(menu, inventory, destinationMenuSlot);
            minecraft.gameMode.handleContainerInput(menu.containerId, destinationMenuSlot, stagingHotbarIndex, ContainerInput.SWAP, minecraft.player);
            transition(State.WAITING_FOR_SHULKER_STORED);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            shulkerStorageTarget = null;
            transition(State.SELECT_SHULKER_STORAGE);
        }
    }

    private static void waitForShulkerStored(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        if (!ShulkerBoxSupport.isShulkerBox(inventory.getItem(stagingHotbarIndex))) {
            if (shulkerStorageTarget != null && shulkerStorageOrdinal >= 0) {
                ContainerIndexSynchronizer.forgetForModMutation(shulkerStorageTarget.container().key());
                ContainerIndex.replaceSlot(shulkerStorageTarget.container().key(), shulkerStorageOrdinal, modifiedShulker);
            }
            if (originalStagingStack.isEmpty()) {
                clearOriginIndexAfterMove();
                minecraft.player.closeContainer();
                finishNestedReturn(minecraft);
                return;
            }
            if (shulkerStorageTarget != null && current != null
                    && shulkerStorageTarget.container().key().equals(current.container().key())) {
                restoreHotbarFromOpenOrigin(minecraft);
                return;
            }
            outerMenuId = minecraft.player.containerMenu.containerId;
            minecraft.player.closeContainer();
            transition(State.OPEN_STAGING_ORIGIN);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Could not confirm shulker storage.");
            transition(State.RETURN_TO_ORIGIN_FALLBACK);
        }
    }

    private static void openStagingOrigin(Minecraft minecraft) {
        if (current == null) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        openBlock(minecraft, current.interactionPos());
        transition(State.WAITING_FOR_STAGING_ORIGIN_MENU);
    }

    private static void waitForStagingOriginMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != outerMenuId) {
            restoreHotbarFromOpenOrigin(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Could not reopen staging chest to restore hotbar slot 9.");
            failNestedAndRestoreHotbar(minecraft);
        }
    }

    private static void restoreHotbarFromOpenOrigin(Minecraft minecraft) {
        if (current == null) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory inventory = minecraft.player.getInventory();
        int originMenuSlot = nthContainerMenuSlot(menu, inventory, current.shulkerSlot());
        if (originMenuSlot < 0) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, originMenuSlot, stagingHotbarIndex, ContainerInput.SWAP, minecraft.player);
        transition(State.WAITING_FOR_HOTBAR_RESTORED);
    }

    private static void waitForHotbarRestored(Minecraft minecraft) {
        ItemStack restored = minecraft.player.getInventory().getItem(stagingHotbarIndex);
        if (sameStack(restored, originalStagingStack)) {
            clearOriginIndexAfterMove();
            minecraft.player.closeContainer();
            finishNestedReturn(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            AutoChestChat.warning(minecraft, "Could not confirm restoration of hotbar slot 9.");
            failNestedAndRestoreHotbar(minecraft);
        }
    }

    private static void returnToOriginFallback(Minecraft minecraft) {
        if (current == null) {
            failNestedAndRestoreHotbar(minecraft);
            return;
        }
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
        openBlock(minecraft, current.interactionPos());
        transition(State.WAITING_FOR_FALLBACK_RETURN);
    }

    private static void waitForFallbackReturn(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (!(menu instanceof InventoryMenu) && menu.containerId != outerMenuId) {
            Inventory inventory = minecraft.player.getInventory();
            int originMenuSlot = nthContainerMenuSlot(menu, inventory, current.shulkerSlot());
            if (originMenuSlot < 0) {
                failNestedAndRestoreHotbar(minecraft);
                return;
            }
            minecraft.gameMode.handleContainerInput(menu.containerId, originMenuSlot, stagingHotbarIndex, ContainerInput.SWAP, minecraft.player);
            if (sameStack(inventory.getItem(stagingHotbarIndex), originalStagingStack)) {
                ContainerIndexSynchronizer.forgetForModMutation(current.container().key());
                ContainerIndex.replaceSlot(current.container().key(), current.shulkerSlot(), modifiedShulker);
                minecraft.player.closeContainer();
                finishNestedReturn(minecraft);
                return;
            }
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) failNestedAndRestoreHotbar(minecraft);
    }

    private static void clearOriginIndexAfterMove() {
        if (current == null) return;
        ContainerIndexSynchronizer.forgetForModMutation(current.container().key());
        ContainerIndex.replaceSlot(current.container().key(), current.shulkerSlot(), ItemStack.EMPTY);
    }

    private static void finishNestedReturn(Minecraft minecraft) {
        if (originalSelectedHotbar >= 0) minecraft.player.getInventory().setSelectedSlot(originalSelectedHotbar);
        retrievedTotal += pendingNestedMoved;
        current = null;
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        modifiedShulker = ItemStack.EMPTY;
        pendingNestedMoved = 0;
        outerMenuId = -1;
        originalOuterShulkerMenuSlot = -1;
        originalStagingStack = ItemStack.EMPTY;
        shulkerStorageTarget = null;
        shulkerStorageOrdinal = -1;
        if (amountMode == AmountMode.ONE_STACK && retrievedTotal > 0) finish(minecraft);
        else transition(State.SELECT_TARGET);
    }

    private static void failNestedAndRestoreHotbar(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
        if (originalSelectedHotbar >= 0) minecraft.player.getInventory().setSelectedSlot(originalSelectedHotbar);
        current = null;
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        modifiedShulker = ItemStack.EMPTY;
        pendingNestedMoved = 0;
        outerMenuId = -1;
        originalOuterShulkerMenuSlot = -1;
        originalStagingStack = ItemStack.EMPTY;
        shulkerStorageTarget = null;
        shulkerStorageOrdinal = -1;
        transition(State.SELECT_TARGET);
    }

    private static TargetChoice chooseTarget(Minecraft minecraft) {
        Map<BlockPos, IndexedContainer> snapshot = ContainerIndex.snapshot();
        for (IndexedContainer indexed : snapshot.values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;
            AttemptKey directKey = new AttemptKey(indexed.key(), -1);
            if (!attempted.contains(directKey) && containsSelectedDirect(indexed)) {
                return new TargetChoice(indexed, interactionPos, -1);
            }
        }
        for (IndexedContainer indexed : snapshot.values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null) continue;
            for (int i = 0; i < indexed.slots().size(); i++) {
                AttemptKey key = new AttemptKey(indexed.key(), i);
                if (attempted.contains(key)) continue;
                ItemStack stack = indexed.slots().get(i);
                if (ShulkerBoxSupport.countItem(stack, selectedItem) > 0) {
                    return new TargetChoice(indexed, interactionPos, i);
                }
            }
        }
        return null;
    }

    private static boolean containsSelectedDirect(IndexedContainer indexed) {
        for (ItemStack stack : indexed.slots()) {
            if (isSelectedType(stack)) return true;
        }
        return false;
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
            if (ShulkerBoxSupport.isShulkerBox(stack) && (!requireEmpty || ShulkerBoxSupport.isEmpty(stack))) {
                return preferred;
            }
        }

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (!ShulkerBoxSupport.isShulkerBox(stack)) continue;
            if (requireEmpty && !ShulkerBoxSupport.isEmpty(stack)) continue;
            if (!requireEmpty && ShulkerBoxSupport.countItem(stack, selectedItem) <= 0) continue;
            return i;
        }

        return -1;
    }

    private static int findReturnMenuSlot(AbstractContainerMenu menu, Inventory inventory, int indexedOrdinal) {
        int preferred = nthContainerMenuSlot(menu, inventory, indexedOrdinal);
        if (preferred >= 0 && menu.slots.get(preferred).getItem().isEmpty()) return preferred;

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) continue;
            if (slot.getItem().isEmpty()) return i;
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


    private static int planFirstMatchingStackToSlot(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory, int destinationMenuSlot, int limit) {
        if (limit <= 0) return 0;
        int sourceMenuSlot = findFirstMatchingContainerSlot(menu, inventory);
        if (sourceMenuSlot < 0) return 0;
        ItemStack source = menu.slots.get(sourceMenuSlot).getItem();
        int destinationCapacity = slotCapacityFor(menu.slots.get(destinationMenuSlot), source);
        int wanted = Math.min(Math.min(source.getCount(), limit), destinationCapacity);
        if (wanted <= 0) return 0;
        pickup(minecraft, menu, sourceMenuSlot, 0);
        if (wanted >= source.getCount()) {
            pickup(minecraft, menu, destinationMenuSlot, 0);
        } else {
            for (int i = 0; i < wanted; i++) pickup(minecraft, menu, destinationMenuSlot, 1);
            pickup(minecraft, menu, sourceMenuSlot, 0);
        }
        return wanted;
    }

    private static int planOneStack(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory, int limit) {
        if (limit <= 0) return 0;
        int sourceMenuSlot = findFirstMatchingContainerSlot(menu, inventory);
        if (sourceMenuSlot < 0) return 0;
        ItemStack source = menu.slots.get(sourceMenuSlot).getItem();
        int capacity = freePlayerCapacityForStack(inventory, source);
        int wanted = Math.min(Math.min(source.getCount(), limit), capacity);
        if (wanted <= 0) return 0;
        if (wanted == source.getCount()) {
            minecraft.gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        } else {
            retrievePartialFromSlot(minecraft, menu, inventory, sourceMenuSlot, wanted);
        }
        return wanted;
    }

    private static int planRetrieveUpTo(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory, int remainingWanted) {
        int remaining = Math.max(0, remainingWanted);
        int planned = 0;
        int capacity = freePlayerCapacityForStack(inventory, sample);
        for (int menuSlot = 0; menuSlot < menu.slots.size() && remaining > 0 && capacity > 0; menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack source = slot.getItem();
            if (!isSelectedType(source)) continue;
            int wanted = Math.min(Math.min(source.getCount(), remaining), capacity);
            if (wanted <= 0) continue;
            if (wanted == source.getCount()) {
                minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
            } else {
                retrievePartialFromSlot(minecraft, menu, inventory, menuSlot, wanted);
            }
            planned += wanted;
            remaining -= wanted;
            capacity -= wanted;
        }
        return planned;
    }

    private static int planRetrieveMaximum(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory) {
        int planned = 0;
        int capacity = freePlayerCapacityForStack(inventory, sample);
        for (int menuSlot = 0; menuSlot < menu.slots.size() && capacity > 0; menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack source = slot.getItem();
            if (!isSelectedType(source)) continue;
            int wanted = Math.min(source.getCount(), capacity);
            if (wanted == source.getCount()) {
                minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
            } else {
                retrievePartialFromSlot(minecraft, menu, inventory, menuSlot, wanted);
            }
            planned += wanted;
            capacity -= wanted;
        }
        return planned;
    }

    private static int freePlayerCapacityForStack(Inventory inventory, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int capacity = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) capacity += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(existing, stack)) capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
        }
        return capacity;
    }

    private static int slotCapacityFor(Slot slot, ItemStack stack) {
        if (!slot.mayPlace(stack)) return 0;
        ItemStack existing = slot.getItem();
        if (existing.isEmpty()) return Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
        if (!ItemStack.isSameItemSameComponents(existing, stack)) return 0;
        return Math.max(0, Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(stack)) - existing.getCount());
    }

    private static int moveFirstMatchingStackToSlot(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory, int destinationMenuSlot, int limit) {
        if (limit <= 0) return 0;
        int sourceMenuSlot = findFirstMatchingContainerSlot(menu, inventory);
        if (sourceMenuSlot < 0) return 0;
        ItemStack source = menu.slots.get(sourceMenuSlot).getItem();
        int wanted = Math.min(source.getCount(), limit);
        int before = countSelectedInInventory(inventory);
        pickup(minecraft, menu, sourceMenuSlot, 0);
        if (wanted >= source.getCount()) {
            pickup(minecraft, menu, destinationMenuSlot, 0);
        } else {
            for (int i = 0; i < wanted; i++) pickup(minecraft, menu, destinationMenuSlot, 1);
            pickup(minecraft, menu, sourceMenuSlot, 0);
        }
        return Math.max(0, countSelectedInInventory(inventory) - before);
    }

    private static int findEmptyHotbarMenuSlot(AbstractContainerMenu menu, Inventory inventory) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory && slot.index >= 0 && slot.index < 9 && slot.getItem().isEmpty()) return menuSlot;
        }
        return -1;
    }

    private static void retrieveOneStack(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory) {
        int sourceMenuSlot = findFirstMatchingContainerSlot(menu, inventory);
        if (sourceMenuSlot < 0) return;
        minecraft.gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
    }

    private static void retrieveUpTo(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory, int remainingWanted) {
        int remaining = Math.max(0, remainingWanted);
        for (int menuSlot = 0; menuSlot < menu.slots.size() && remaining > 0; menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack source = slot.getItem();
            if (!isSelectedType(source)) continue;
            int sourceCount = source.getCount();
            if (sourceCount <= remaining) {
                int before = countSelectedInInventory(inventory);
                minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
                int moved = Math.max(0, countSelectedInInventory(inventory) - before);
                remaining -= moved;
                if (moved == 0) break;
            } else {
                remaining -= retrievePartialFromSlot(minecraft, menu, inventory, menuSlot, remaining);
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

        ItemStack source = menu.slots.get(sourceMenuSlot).getItem().copy();
        if (!isSelectedType(source)) return 0;

        int before = countSelectedInInventory(inventory);

        pickup(minecraft, menu, sourceMenuSlot, 0);

        for (int i = 0; i < wanted; i++) {
            if (menu.getCarried().isEmpty()) break;

            int destination = findPlayerDestinationSlot(
                    menu,
                    inventory,
                    menu.getCarried()
            );

            if (destination < 0) break;

            pickup(minecraft, menu, destination, 1);
        }

        if (!menu.getCarried().isEmpty()) {
            pickup(minecraft, menu, sourceMenuSlot, 0);
        }

        return Math.max(
                0,
                countSelectedInInventory(inventory) - before
        );
    }

    private static void retrieveMaximum(Minecraft minecraft, AbstractContainerMenu menu, Inventory inventory) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory || !isSelectedType(slot.getItem())) continue;
            minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        }
    }

    private static int findFirstMatchingContainerSlot(AbstractContainerMenu menu, Inventory inventory) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory && isSelectedType(slot.getItem())) return menuSlot;
        }
        return -1;
    }

    private static int findPlayerDestinationSlot(AbstractContainerMenu menu, Inventory inventory, ItemStack sourceStack) {
        int firstEmpty = -1;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container != inventory || !slot.mayPlace(sourceStack)) continue;
            ItemStack existing = slot.getItem();

            if (
                    ItemStack.isSameItemSameComponents(existing, sourceStack)
                            && existing.getCount() < Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(sourceStack))
            ){
                return menuSlot;
            }

            if (existing.isEmpty()) {
                if (firstEmpty < 0) firstEmpty = menuSlot;
            }
        }
        return firstEmpty;
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

    private static int countSelectedInInventory(Inventory inventory) {
        if (selectedItem == null) return 0;
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSelectedType(stack)) count += stack.getCount();
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
            if ((amountMode == AmountMode.COUNT || amountMode == AmountMode.ONE_STACK) && retrievedTotal < requestedCount) {
                AutoChestChat.debug(minecraft, "Requested " + requestedCount + " but only " + retrievedTotal + " could be retrieved.");
            }
            if (!(minecraft.player.containerMenu instanceof InventoryMenu)) minecraft.player.closeContainer();
        }
        transition(State.RESTORE_PREVIOUS);
    }

    private static void restorePrevious(Minecraft minecraft) {
        ScreenReturnContext context = returnContext;
        abort();
        context.restore(minecraft);
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
        attempted.clear();
        retrievedTotal = 0;
        requestedCount = 0;
        hotbarPreferencePending = false;
        returnContext = ScreenReturnContext.none();
        stagingHotbarIndex = STAGING_HOTBAR_INDEX;
        originalSelectedHotbar = -1;
        modifiedShulker = ItemStack.EMPTY;
        pendingNestedMoved = 0;
        outerMenuId = -1;
        originalOuterShulkerMenuSlot = -1;
        originalStagingStack = ItemStack.EMPTY;
        shulkerStorageTarget = null;
        shulkerStorageOrdinal = -1;
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    private record TargetChoice(IndexedContainer container, BlockPos interactionPos, int shulkerSlot) {}
    private record AttemptKey(BlockPos containerKey, int shulkerSlot) {}
}

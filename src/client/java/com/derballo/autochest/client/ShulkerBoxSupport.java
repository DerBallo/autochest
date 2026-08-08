package com.derballo.autochest.client;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

public final class ShulkerBoxSupport {
    public static final int SLOT_COUNT = 27;

    private ShulkerBoxSupport() {}

    public static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static List<ItemStack> contents(ItemStack stack) {
        if (!isShulkerBox(stack)) return List.of();
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null) contents.copyInto(items);
        List<ItemStack> copy = new ArrayList<>(SLOT_COUNT);
        for (ItemStack item : items) copy.add(item.copy());
        return List.copyOf(copy);
    }

    public static boolean isEmpty(ItemStack stack) {
        if (!isShulkerBox(stack)) return false;
        for (ItemStack item : contents(stack)) {
            if (!item.isEmpty()) return false;
        }
        return true;
    }


    public static Item singleItemType(ItemStack shulker) {
        if (!isShulkerBox(shulker)) return null;
        Item type = null;
        for (ItemStack stack : contents(shulker)) {
            if (stack.isEmpty()) continue;
            if (type == null) type = stack.getItem();
            else if (stack.getItem() != type) return null;
        }
        return type;
    }

    public static boolean containsOnlyItemType(ItemStack shulker, Item item) {
        if (!isShulkerBox(shulker) || item == null) return false;
        boolean found = false;
        for (ItemStack stack : contents(shulker)) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() != item) return false;
            found = true;
        }
        return found;
    }

    public static int countItem(ItemStack shulker, Item item) {
        int count = 0;
        for (ItemStack stack : contents(shulker)) {
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    public static ItemStack withMenuContents(ItemStack shulker, AbstractContainerMenu menu, Inventory inventory) {
        ItemStack copy = shulker.copy();
        if (!isShulkerBox(copy)) return copy;
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        int index = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory) continue;
            if (index >= SLOT_COUNT) break;
            items.set(index++, slot.getItem().copy());
        }
        copy.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return copy;
    }


    public static int remainingCapacityFor(ItemStack shulker, ItemStack sample) {
        if (!isShulkerBox(shulker) || sample == null || sample.isEmpty()) return 0;
        int capacity = 0;
        for (ItemStack stack : contents(shulker)) {
            if (stack.isEmpty()) {
                capacity += sample.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stack, sample)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    public static boolean hasItemAndCapacity(ItemStack shulker, ItemStack sample) {
        if (!isShulkerBox(shulker) || sample == null || sample.isEmpty()) return false;
        boolean contains = false;
        for (ItemStack stack : contents(shulker)) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() == sample.getItem()) contains = true;
        }
        return contains && remainingCapacityFor(shulker, sample) > 0;
    }

    public static ItemStack applyRetrieved(ItemStack shulker, Item item, int amount) {
        ItemStack copy = shulker.copy();
        if (!isShulkerBox(copy) || item == null || amount <= 0) return copy;
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ItemContainerContents contents = copy.get(DataComponents.CONTAINER);
        if (contents != null) contents.copyInto(items);

        int remaining = amount;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            int moved = Math.min(stack.getCount(), remaining);
            stack.shrink(moved);
            remaining -= moved;
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
        }

        copy.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return copy;
    }

    public static ItemStack applyDeposited(ItemStack shulker, ItemStack sample, int amount) {
        ItemStack copy = shulker.copy();
        if (!isShulkerBox(copy) || sample == null || sample.isEmpty() || amount <= 0) return copy;
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ItemContainerContents contents = copy.get(DataComponents.CONTAINER);
        if (contents != null) contents.copyInto(items);

        int remaining = amount;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, sample)) continue;
            int room = Math.max(0, stack.getMaxStackSize() - stack.getCount());
            if (room <= 0) continue;
            int moved = Math.min(room, remaining);
            stack.grow(moved);
            remaining -= moved;
        }

        for (int i = 0; i < items.size() && remaining > 0; i++) {
            if (!items.get(i).isEmpty()) continue;
            int moved = Math.min(sample.getMaxStackSize(), remaining);
            ItemStack placed = sample.copy();
            placed.setCount(moved);
            items.set(i, placed);
            remaining -= moved;
        }

        copy.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return copy;
    }

    public static int capacityFor(ItemStack sample) {
        return SLOT_COUNT * Math.max(1, sample.getMaxStackSize());
    }
}

package com.derballo.autochest.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContainerIndex {
    private static final Map<BlockPos, IndexedContainer> BY_KEY = new LinkedHashMap<>();

    private ContainerIndex() {}

    public static void clear() {
        BY_KEY.clear();
    }

    public static void put(IndexedContainer container) {
        BY_KEY.put(container.key(), container);
    }

    public static IndexedContainer get(BlockPos key) {
        return BY_KEY.get(key);
    }

    public static boolean contains(BlockPos key) {
        return BY_KEY.containsKey(key);
    }

    public static BlockPos findKeyByBlock(BlockPos blockPos) {
        for (IndexedContainer container : BY_KEY.values()) {
            if (container.blocks().contains(blockPos)) {
                return container.key();
            }
        }
        return null;
    }

    public static boolean refreshFromMenu(
            BlockPos key,
            AbstractContainerMenu menu,
            Inventory playerInventory
    ) {
        IndexedContainer old = BY_KEY.get(key);
        if (old == null) return false;

        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) continue;
            contents.add(slot.getItem().copy());
        }

        put(new IndexedContainer(old.key(), old.blocks(), contents));
        return true;
    }

    public static void applyDeposited(BlockPos key, ItemStack depositedStack, int amount) {
        IndexedContainer old = BY_KEY.get(key);
        if (old == null || depositedStack.isEmpty() || amount <= 0) return;

        List<ItemStack> slots = old.slots().stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int remaining = amount;

        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(stack, depositedStack)) continue;

            int room = Math.max(0, stack.getMaxStackSize() - stack.getCount());
            if (room <= 0) continue;

            int moved = Math.min(room, remaining);
            stack.grow(moved);
            remaining -= moved;
        }

        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            if (!slots.get(i).isEmpty()) continue;

            int moved = Math.min(depositedStack.getMaxStackSize(), remaining);
            ItemStack placed = depositedStack.copy();
            placed.setCount(moved);
            slots.set(i, placed);
            remaining -= moved;
        }

        put(new IndexedContainer(old.key(), old.blocks(), slots));
    }

    public static void applyRetrieved(BlockPos key, net.minecraft.world.item.Item item, int amount) {
        IndexedContainer old = BY_KEY.get(key);
        if (old == null || item == null || amount <= 0) return;

        List<ItemStack> slots = old.slots().stream()
                .map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int remaining = amount;

        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;

            int removed = Math.min(stack.getCount(), remaining);
            stack.shrink(removed);
            remaining -= removed;

            if (stack.isEmpty()) {
                slots.set(i, ItemStack.EMPTY);
            }
        }

        put(new IndexedContainer(old.key(), old.blocks(), slots));
    }

    public static Map<BlockPos, IndexedContainer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BY_KEY));
    }

    public static int size() {
        return BY_KEY.size();
    }
}

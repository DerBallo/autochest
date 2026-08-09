package com.derballo.autochest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ShulkerStorageRouter {
    private ShulkerStorageRouter() {}

    public static Destination choose(Minecraft minecraft, ItemStack shulker, Origin origin) {
        if (minecraft.player == null || !ShulkerBoxSupport.isShulkerBox(shulker)) return null;

        if (ShulkerBoxSupport.isEmpty(shulker)) {
            List<Destination> grouped = candidates(minecraft, origin, false);
            grouped.removeIf(destination -> emptyShulkerCount(destination.container(), origin) <= 0);
            grouped.sort(Comparator.comparingInt((Destination destination) -> emptyShulkerCount(destination.container(), origin)).reversed());
            if (!grouped.isEmpty()) return grouped.getFirst();

            Destination emptyChest = bestEmptyChest(minecraft, origin);
            return emptyChest != null ? emptyChest : bestAnyFree(minecraft, origin, null);
        }

        Item singleType = ShulkerBoxSupport.singleItemType(shulker);
        if (singleType != null) {
            List<Destination> grouped = candidates(minecraft, origin, false);
            grouped.removeIf(destination -> singleTypeShulkerCount(destination.container(), origin, singleType) <= 0);
            grouped.sort(
                    Comparator.comparingInt((Destination destination) -> singleTypeShulkerCount(destination.container(), origin, singleType)).reversed()
                            .thenComparing(Comparator.comparingInt((Destination destination) -> itemCountInsideSingleTypeShulkers(destination.container(), origin, singleType)).reversed())
            );
            if (!grouped.isEmpty()) return grouped.getFirst();

            Destination emptyChest = bestEmptyChest(minecraft, origin);
            return emptyChest != null ? emptyChest : bestAnyFree(minecraft, origin, singleType);
        }

        Destination emptyChest = bestEmptyChest(minecraft, origin);
        return emptyChest != null ? emptyChest : bestAnyFree(minecraft, origin, null);
    }

    private static List<Destination> candidates(Minecraft minecraft, Origin origin, boolean emptyOnly) {
        List<Destination> result = new ArrayList<>();
        for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
            BlockPos interactionPos = firstReachableBlock(minecraft, indexed);
            if (interactionPos == null || !hasFreeSlot(indexed, origin)) continue;
            if (emptyOnly && !isEffectivelyEmpty(indexed, origin)) continue;
            result.add(new Destination(indexed, interactionPos));
        }
        return result;
    }

    private static Destination bestEmptyChest(Minecraft minecraft, Origin origin) {
        List<Destination> candidates = candidates(minecraft, origin, true);
        candidates.sort(Comparator.comparingInt((Destination destination) -> emptySlotCount(destination.container(), origin)).reversed());
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static Destination bestAnyFree(Minecraft minecraft, Origin origin, Item singleType) {
        List<Destination> candidates = candidates(minecraft, origin, false);
        candidates.sort(Comparator.comparingInt((Destination destination) -> emptySlotCount(destination.container(), origin)).reversed());
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static int emptyShulkerCount(IndexedContainer indexed, Origin origin) {
        int count = 0;
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            if (ShulkerBoxSupport.isEmpty(indexed.slots().get(i))) count++;
        }
        return count;
    }

    private static int singleTypeShulkerCount(IndexedContainer indexed, Origin origin, Item item) {
        int count = 0;
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            if (ShulkerBoxSupport.containsOnlyItemType(indexed.slots().get(i), item)) count++;
        }
        return count;
    }

    private static int itemCountInsideSingleTypeShulkers(IndexedContainer indexed, Origin origin, Item item) {
        int count = 0;
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            ItemStack stack = indexed.slots().get(i);
            if (ShulkerBoxSupport.containsOnlyItemType(stack, item)) count += ShulkerBoxSupport.countItem(stack, item);
        }
        return count;
    }

    private static int emptySlotCount(IndexedContainer indexed, Origin origin) {
        int count = 0;
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            if (indexed.slots().get(i).isEmpty()) count++;
        }
        return count;
    }

    private static boolean hasFreeSlot(IndexedContainer indexed, Origin origin) {
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            if (indexed.slots().get(i).isEmpty()) return true;
        }
        return false;
    }

    private static boolean isEffectivelyEmpty(IndexedContainer indexed, Origin origin) {
        for (int i = 0; i < indexed.slots().size(); i++) {
            if (isOriginSlot(indexed, origin, i)) continue;
            if (!indexed.slots().get(i).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isOriginSlot(IndexedContainer indexed, Origin origin, int slot) {
        return origin != null && indexed.key().equals(origin.containerKey()) && slot == origin.slotOrdinal();
    }

    private static BlockPos firstReachableBlock(Minecraft minecraft, IndexedContainer indexed) {
        for (BlockPos pos : indexed.blocks()) {
            if (minecraft.player.isWithinBlockInteractionRange(pos, 0.0)) return pos;
        }
        return null;
    }

    public record Origin(BlockPos containerKey, int slotOrdinal) {}
    public record Destination(IndexedContainer container, BlockPos interactionPos) {}
}

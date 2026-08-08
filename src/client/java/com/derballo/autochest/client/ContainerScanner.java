package com.derballo.autochest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContainerScanner {
    private enum State { IDLE, OPEN_NEXT, WAITING_FOR_MENU }

    private static final ArrayDeque<ContainerTarget> QUEUE = new ArrayDeque<>();
    private static State state = State.IDLE;
    private static ContainerTarget current;
    private static int ticksInState;

    private static final int OPEN_TIMEOUT_TICKS = 20;
    private ContainerScanner() {}

    public static boolean isScanning() {
        return state != State.IDLE;
    }

    public static void start(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) return;
        ContainerIndex.clear();
        QUEUE.clear();
        current = null;

        List<ContainerTarget> targets = discoverTargets(minecraft);
        targets.sort(Comparator.comparingDouble(t -> distanceSqToNearestBlock(minecraft, t)));

        QUEUE.addAll(targets);
        state = State.OPEN_NEXT;
        ticksInState = 0;

        AutoChestChat.info(minecraft, "Indexing " + targets.size() + " nearby containers...");
    }

    public static void tick(Minecraft minecraft) {
        if (state == State.IDLE) return;
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            abort();
            return;
        }

        ticksInState++;
        switch (state) {
            case OPEN_NEXT -> openNext(minecraft);
            case WAITING_FOR_MENU -> waitForMenu(minecraft);
            case IDLE -> { }
        }
    }

    private static List<ContainerTarget> discoverTargets(Minecraft minecraft) {
        double range = minecraft.player.blockInteractionRange();
        int radius = (int) Math.ceil(range) + 1;
        BlockPos center = minecraft.player.blockPosition();

        Map<BlockPos, ContainerTarget> unique = new LinkedHashMap<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!minecraft.player.isWithinBlockInteractionRange(pos, 0.0)) continue;

            BlockEntity be = minecraft.level.getBlockEntity(pos);
            if (!(be instanceof Container)) continue;

            ContainerTarget.fromBlockEntity(minecraft.level, be)
                    .ifPresent(target -> unique.putIfAbsent(target.key(), target));
        }

        return new ArrayList<>(unique.values());
    }

    private static void openNext(Minecraft minecraft) {
        current = QUEUE.pollFirst();
        if (current == null) {
            finish(minecraft);
            return;
        }

        if (!minecraft.player.isWithinBlockInteractionRange(current.interactionPos(), 0.0)) {
            transition(State.OPEN_NEXT);
            return;
        }

        BlockPos pos = current.interactionPos();
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.UP, pos, false);

        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
        transition(State.WAITING_FOR_MENU);
    }

    private static void waitForMenu(Minecraft minecraft) {
        if (!(minecraft.player.containerMenu instanceof InventoryMenu)) {
            readMenu(minecraft);
            return;
        }
        if (ticksInState >= OPEN_TIMEOUT_TICKS) {
            transition(State.OPEN_NEXT);
        }
    }

    private static void readMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu instanceof InventoryMenu) {
            transition(State.OPEN_NEXT);
            return;
        }
        ContainerIndex.put(new IndexedContainer(current.key(), current.blocks(), List.of()));
        ContainerIndex.refreshFromMenu(current.key(), menu, minecraft.player.getInventory());
        minecraft.player.closeContainer();
        current = null;
        transition(State.OPEN_NEXT);
    }

    private static double distanceSqToNearestBlock(Minecraft minecraft, ContainerTarget target) {
        Vec3 eye = minecraft.player.getEyePosition();
        return target.blocks().stream()
                .mapToDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos)))
                .min().orElse(Double.POSITIVE_INFINITY);
    }

    private static void transition(State next) {
        state = next;
        ticksInState = 0;
    }

    private static void finish(Minecraft minecraft) {
        state = State.IDLE;
        current = null;
        QUEUE.clear();
        if (minecraft.player != null) {
            int containers = ContainerIndex.size();
            int totalSlots = 0;
            int nonEmptyStacks = 0;
            int totalItems = 0;

            for (IndexedContainer indexed : ContainerIndex.snapshot().values()) {
                totalSlots += indexed.slots().size();
                for (ItemStack stack : indexed.slots()) {
                    if (stack.isEmpty()) continue;
                    nonEmptyStacks++;
                    totalItems += stack.getCount();
                    if (ShulkerBoxSupport.isShulkerBox(stack)) {
                        for (ItemStack nested : ShulkerBoxSupport.contents(stack)) {
                            if (nested.isEmpty()) continue;
                            nonEmptyStacks++;
                            totalItems += nested.getCount();
                        }
                    }
                }
            }

            AutoChestChat.debug(minecraft, "Indexed " + containers
                    + " containers | " + totalSlots
                    + " slots | " + nonEmptyStacks
                    + " non-empty stacks | " + totalItems + " items");
        }
    }

    private static void abort() {
        state = State.IDLE;
        current = null;
        QUEUE.clear();
    }
}
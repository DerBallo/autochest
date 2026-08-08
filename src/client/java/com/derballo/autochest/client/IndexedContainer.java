package com.derballo.autochest.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public record IndexedContainer(BlockPos key, List<BlockPos> blocks, List<ItemStack> slots) {
    public IndexedContainer {
        key = key.immutable();
        blocks = blocks.stream().map(BlockPos::immutable).toList();
        slots = slots.stream().map(ItemStack::copy).toList();
    }
}

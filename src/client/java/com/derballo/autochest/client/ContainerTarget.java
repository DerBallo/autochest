package com.derballo.autochest.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record ContainerTarget(BlockPos key, BlockPos interactionPos, List<BlockPos> blocks) {
    private static final Comparator<BlockPos> POS_ORDER = Comparator.naturalOrder();

    public ContainerTarget {
        key = key.immutable();
        interactionPos = interactionPos.immutable();
        blocks = blocks.stream().map(BlockPos::immutable).sorted(POS_ORDER).toList();
    }

    public static Optional<ContainerTarget> fromBlockEntity(Level level, BlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = blockEntity.getBlockState();

        // ChestBlock covers normal and trapped chests. Ender chests are deliberately excluded:
        // their inventory belongs to the player, not the block entity.
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                BlockPos key = POS_ORDER.compare(pos, other) <= 0 ? pos : other;
                return Optional.of(new ContainerTarget(key, pos, List.of(pos, other)));
            }
        }

        return Optional.of(new ContainerTarget(pos, pos, List.of(pos)));
    }
}

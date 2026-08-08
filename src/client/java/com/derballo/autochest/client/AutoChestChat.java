package com.derballo.autochest.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class AutoChestChat {
    private AutoChestChat() {}

    public static void info(Minecraft minecraft, String message) {
        send(minecraft, "[AutoChest] ", message, ChatFormatting.GOLD);
    }

    public static void success(Minecraft minecraft, String message) {
        send(minecraft, "[AutoChest] ", message, ChatFormatting.GREEN);
    }

    public static void warning(Minecraft minecraft, String message) {
        send(minecraft, "[AutoChest] ", message, ChatFormatting.YELLOW);
    }

    public static void debug(Minecraft minecraft, String message) {
        send(minecraft, "[AutoChest] ", message, ChatFormatting.GRAY);
    }

    private static void send(
            Minecraft minecraft,
            String prefix,
            String message,
            ChatFormatting messageColor
    ) {
        if (minecraft.player == null) return;

        MutableComponent component = Component.literal(prefix).append(Component.literal(message).withStyle(messageColor));
        minecraft.player.sendSystemMessage(component);
    }
}
package com.derballo.autochest.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;

public final class AutoChestClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("autochest", "autochest")
    );

    private static final KeyMapping INDEX_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.autochest.index",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_I,
                    CATEGORY
            )
    );

    private static final KeyMapping DEPOSIT_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.autochest.deposit_matching",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_V,
                    CATEGORY
            )
    );

    private static final KeyMapping SEARCH_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.autochest.search_retrieve",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_B,
                    CATEGORY
            )
    );

    @Override
    public void onInitializeClient() {
        ContainerIndexSynchronizer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (INDEX_KEY.consumeClick()) {
                if (!isBusy()) {
                    ContainerScanner.start(client);
                }
            }

            while (SEARCH_KEY.consumeClick()) {
                if (client.player == null || isBusy()) continue;

                if (ContainerIndex.size() == 0) {
                    AutoChestChat.warning(client, "No indexed containers. Index nearby containers first.");
                } else {
                    client.gui.setScreen(new AutoChestSearchScreen());
                }
            }

            ContainerScanner.tick(client);
            ContainerDepositor.tick(client);
            ContainerRetriever.tick(client);
            ContainerIndexSynchronizer.tick(client);
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;

            ScreenKeyboardEvents.allowKeyPress(screen).register((currentScreen, event) -> {
                if (!DEPOSIT_KEY.matches(event)) return true;
                if (!(currentScreen instanceof AbstractContainerScreen<?> containerScreen)) return true;
                if (isBusy()) return true;

                return !ContainerDepositor.startFromHoveredSlot(client, containerScreen);
            });
        });
    }

    private static boolean isBusy() {
        return ContainerScanner.isScanning()
                || ContainerDepositor.isDepositing()
                || ContainerRetriever.isRetrieving();
    }
}
package com.lerdorf;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameQueryModClient implements ClientModInitializer {
    public static final String MOD_ID = "gamequery";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private QueryClient queryClient;
    
    @Override
    public void onInitializeClient() {
        LOGGER.info("GameQuery client mod initializing...");
        
        // Start the query server when joining a world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onWorldJoined(client);
        });
        
        // Stop the query server when leaving a world
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            onWorldLeft(client);
        });
        
        LOGGER.info("GameQuery client mod initialized!");
    }
    
    private void onWorldJoined(MinecraftClient client) {
        if (queryClient == null) {
            queryClient = new QueryClient(client, 25566); // Port 25566
            queryClient.start();
            LOGGER.info("Client query server started on port 25566");
        }
    }
    
    private void onWorldLeft(MinecraftClient client) {
        if (queryClient != null) {
            queryClient.stop();
            queryClient = null;
            LOGGER.info("Client query server stopped");
        }
    }
}
package com.lerdorf;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;

public class GameQueryModClient implements ClientModInitializer {
    public static final String MOD_ID = "gamequery";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static boolean carlsFaceBar = false;
	public static boolean baritone = false;
	
	
	//public static Class<?> locatorBarDataClass;
    
    private QueryClient queryClient;
    
    private boolean wasInWorld = false;
    
    private void showToast(String title, String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.getToastManager().add(new SystemToast(
            SystemToast.Type.PERIODIC_NOTIFICATION, 
            Text.literal(title), 
            Text.literal(message)
        ));
    }
    
    /*
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
        
        // Start the query server when any world (SP or MP) is loaded
        ClientWorldEvents.LOAD.register((client, world) -> {
            onWorldJoined(MinecraftClient.getInstance());
        });

        // Stop the query server when any world is unloaded
        ClientWorldEvents.UNLOAD.register((client, world) -> {
            onWorldLeft(MinecraftClient.getInstance());
        });
        
        LOGGER.info("GameQuery client mod initialized!");
    }
    */
    @Override
    public void onInitializeClient() {
        LOGGER.info("GameQuery client mod initializing...");
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean isInWorld = client.world != null && client.player != null;

            if (isInWorld && !wasInWorld) {
                onWorldJoined(client);
            } else if (!isInWorld && wasInWorld) {
                onWorldLeft(client);
            }

            wasInWorld = isInWorld;
        });
        
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleChatMessage(message.getString(), sender, receptionTimestamp);
        });
        
        registerDamageHandler();

        LOGGER.info("GameQuery client mod initialized!");
    }
    
    private void handleChatMessage(String message, GameProfile sender, Instant timestamp) {
        //System.out.println("Chat: " + message);
    	QueryClient.unreadChat.add(new QueryClient.FancyMessage(message, sender, timestamp));
        
    }

    
    private void onWorldJoined(MinecraftClient client) {
        if (queryClient == null) {
        	
        	if (FabricLoader.getInstance().isModLoaded("carlsfacebar")) {
			    // safe to call LocatorBarData methods using reflection or direct calls if you have access
        		carlsFaceBar = true;
        		
			} else {
				LOGGER.info("[CarlsFaceBar integration] carlsfacebar not found");
			}
        	
        	if (FabricLoader.getInstance().isModLoaded("baritone")) {
			    // safe to call Baritone methods using reflection or direct calls if you have access
        		baritone = true;
        		
			} else {
				LOGGER.info("[Baritone integration] baritone not found");
			}
        	
            queryClient = new QueryClient(client, 25566); // Port 25566
            ClientTickEvents.END_CLIENT_TICK.register(tickclient -> {
            	if (queryClient != null)
            		queryClient.tick();
            });
            queryClient.start();
            showToast("GameQuery", "Client mod initialized!");
            LOGGER.info("Client query server started on port 25566");
        }
    }
    
    private void onWorldLeft(MinecraftClient client) {
        if (queryClient != null) {
            queryClient.stop();
            queryClient = null;
            showToast("GameQuery", "Client mod de-initialized!");
            LOGGER.info("Client query server stopped");
        }
    }
    
    private static float lastHealth = -1;
    
    public static void registerDamageHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                float currentHealth = client.player.getHealth();

                if (lastHealth != -1 && currentHealth < lastHealth) {
                    //System.out.println("Player took damage! New health: " + currentHealth);

                    // Try getting the attacker (will usually be null on client)
                    {
                        LivingEntity damager = (LivingEntity) client.player.getAttacker();
                        if (damager != null) {
                            //System.out.println("Damaged by: " + attacker.getName().getString());
                        	QueryClient.attacker = damager.getUuid();
                        } else {
                            //System.out.println("Attacker unknown (null)");
                        }
                    }
                }

                lastHealth = currentHealth;
            }
        });
    }
}
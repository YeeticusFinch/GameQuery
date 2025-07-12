package com.lerdorf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameQueryMod implements ModInitializer {
public static final String MOD_ID = "gamequery";
public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
//public static boolean carlsFaceBar = false;

//public static Class<?> locatorBarDataClass;

private QueryServer queryServer;
	
	@Override
	public void onInitialize() {
			LOGGER.info("GameQuery mod initializing...");
			
			// Start the query server when the server starts
			ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
			ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
			LOGGER.info("GameQuery mod initialized!");
		}
		
		private void onServerStarted(MinecraftServer server) {
			/*
			if (FabricLoader.getInstance().isModLoaded("carlsfacebar")) {
			    // safe to call LocatorBarData methods using reflection or direct calls if you have access
				try {
					locatorBarDataClass = Class.forName("me.cortex.facebar.mixin.LocatorBarData");
					carlsFaceBar = true;
					LOGGER.info("[CarlsFaceBar integration] Found LocatorBarDataClass");
				} catch (Exception e) {
					LOGGER.info("[CarlsFaceBar integration] LocatorBarData class not found!");
				}
			} else {
				LOGGER.info("[CarlsFaceBar integration] carlsfacebar not found");
			}*/
			queryServer = new QueryServer(server, 25566); // Port 25566
			queryServer.start();
			LOGGER.info("Query server started on port 25566");
		}
		
		private void onServerStopping(MinecraftServer server) {
			if (queryServer != null) {
			queryServer.stop();
			LOGGER.info("Query server stopped");
		}
	}
}
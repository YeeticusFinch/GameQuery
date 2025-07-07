package com.lerdorf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameQueryMod implements ModInitializer {
public static final String MOD_ID = "gamequery";
public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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
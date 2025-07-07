package com.lerdorf;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentMap;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueryServer {
    private final MinecraftServer server;
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private boolean running;
    private final Gson gson = new Gson();
    
    public QueryServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
        this.executor = Executors.newCachedThreadPool();
    }
    
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            
            executor.submit(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executor.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            GameQueryMod.LOGGER.error("Error accepting client connection", e);
                        }
                    }
                }
            });
        } catch (IOException e) {
            GameQueryMod.LOGGER.error("Failed to start query server", e);
        }
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            GameQueryMod.LOGGER.error("Error closing server socket", e);
        }
        executor.shutdown();
    }
    
    private void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            String queryJson = reader.readLine();
            if (queryJson != null) {
                JsonObject query = gson.fromJson(queryJson, JsonObject.class);
                JsonObject response = processQuery(query);
                writer.println(gson.toJson(response));
            }
        } catch (Exception e) {
            GameQueryMod.LOGGER.error("Error handling client", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                GameQueryMod.LOGGER.error("Error closing client socket", e);
            }
        }
    }
    
    private JsonObject processQuery(JsonObject query) {
        JsonObject response = new JsonObject();
        
        try {
            String type = query.get("type").getAsString();
            String playerName = query.has("player") ? query.get("player").getAsString() : null;
            
            ServerPlayerEntity player = null;
            if (playerName != null) {
                player = server.getPlayerManager().getPlayer(playerName);
                if (player == null) {
                    response.addProperty("error", "Player not found: " + playerName);
                    return response;
                }
            }
            
            switch (type) {
                case "inventory":
                    response.add("inventory", getPlayerInventory(player));
                    break;
                case "blocks":
                    int range = query.has("range") ? query.get("range").getAsInt() : 5;
                    response.add("blocks", getBlocksAroundPlayer(player, range));
                    break;
                case "mobs":
                    int mobRange = query.has("range") ? query.get("range").getAsInt() : 10;
                    response.add("mobs", getMobsAroundPlayer(player, mobRange));
                    break;
                default:
                    response.addProperty("error", "Unknown query type: " + type);
            }
        } catch (Exception e) {
            response.addProperty("error", "Error processing query: " + e.getMessage());
        }
        
        return response;
    }
    
    private JsonObject getPlayerInventory(ServerPlayerEntity player) {
        JsonObject inventoryData = new JsonObject();
        List<JsonObject> items = new ArrayList<>();
        
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", i);
                item.addProperty("type", stack.getItem().toString());
                item.addProperty("name", stack.getName().getString());
                item.addProperty("count", stack.getCount());
                
                // Add NBT data
                if (stack.getComponents() != null) {
                    ComponentMap nbt = stack.getComponents();
                    item.addProperty("nbt", nbt.toString());
                }
                
                items.add(item);
            }
        }
        
        inventoryData.add("items", gson.toJsonTree(items));
        return inventoryData;
    }
    
    private JsonObject getBlocksAroundPlayer(ServerPlayerEntity player, int range) {
        JsonObject blocksData = new JsonObject();
        List<JsonObject> blocks = new ArrayList<>();
        
        BlockPos playerPos = player.getBlockPos();
        World world = player.getWorld();
        
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    
                    JsonObject blockData = new JsonObject();
                    blockData.addProperty("x", pos.getX());
                    blockData.addProperty("y", pos.getY());
                    blockData.addProperty("z", pos.getZ());
                    blockData.addProperty("type", world.getBlockState(pos).getBlock().toString());
                    
                    // Check if it's a container
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof Inventory) {
                        Inventory inventory = (Inventory) blockEntity;
                        List<JsonObject> contents = new ArrayList<>();
                        
                        for (int i = 0; i < inventory.size(); i++) {
                            ItemStack stack = inventory.getStack(i);
                            if (!stack.isEmpty()) {
                                JsonObject item = new JsonObject();
                                item.addProperty("slot", i);
                                item.addProperty("type", stack.getItem().toString());
                                item.addProperty("name", stack.getName().getString());
                                item.addProperty("count", stack.getCount());
                                contents.add(item);
                            }
                        }
                        
                        blockData.add("contents", gson.toJsonTree(contents));
                    }
                    
                    blocks.add(blockData);
                }
            }
        }
        
        blocksData.add("blocks", gson.toJsonTree(blocks));
        return blocksData;
    }
    
    private JsonObject getMobsAroundPlayer(ServerPlayerEntity player, int range) {
        JsonObject mobsData = new JsonObject();
        List<JsonObject> mobs = new ArrayList<>();
        
        BlockPos playerPos = player.getBlockPos();
        World world = player.getWorld();
        
        Box searchBox = new Box(playerPos).expand(range);
        List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, searchBox, entity -> !(entity instanceof ServerPlayerEntity));
        
        for (LivingEntity entity : entities) {
            JsonObject mobData = new JsonObject();
            mobData.addProperty("type", entity.getType().toString());
            mobData.addProperty("name", entity.getName().getString());
            mobData.addProperty("x", entity.getX());
            mobData.addProperty("y", entity.getY());
            mobData.addProperty("z", entity.getZ());
            mobData.addProperty("health", entity.getHealth());
            mobData.addProperty("maxHealth", entity.getMaxHealth());
            
            if (entity instanceof MobEntity) {
                MobEntity mob = (MobEntity) entity;
                boolean hostile = entity instanceof net.minecraft.entity.mob.HostileEntity;
                boolean passive = entity instanceof net.minecraft.entity.passive.PassiveEntity;
                boolean neutral = entity instanceof net.minecraft.entity.mob.Angerable;
                mobData.addProperty("hostile", hostile);
                mobData.addProperty("passive", passive);
                mobData.addProperty("neutral", neutral);
            }
            
            mobs.add(mobData);
        }
        
        mobsData.add("mobs", gson.toJsonTree(mobs));
        return mobsData;
    }
}
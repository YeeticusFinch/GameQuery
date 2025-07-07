package com.lerdorf;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.Direction;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueryClient {
	private final MinecraftClient client;
	private final int port;
	private ServerSocket serverSocket;
	private ExecutorService executor;
	private boolean running;
	private final Gson gson = new Gson();

	public QueryClient(MinecraftClient client, int port) {
		this.client = client;
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
			GameQueryMod.LOGGER.error("Failed to start client query server", e);
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
			// Execute on the main thread to avoid concurrency issues
			JsonObject result = client.submit(() -> {
				JsonObject syncResponse = new JsonObject();

				if (client.player == null || client.world == null) {
					syncResponse.addProperty("error", "Player not in world");
					return syncResponse;
				}

				String type = query.get("type").getAsString();

				switch (type) {
				case "inventory":
					syncResponse.add("inventory", getPlayerInventory());
					break;
				case "position":
					syncResponse.add("position", getPlayerPosition());
					break;
				case "blocks":
					int range = query.has("range") ? query.get("range").getAsInt() : 5;
					syncResponse.add("blocks", getBlocksAroundPlayer(range));
					break;
				case "entities":
					int entityRange = query.has("range") ? query.get("range").getAsInt() : 10;
					syncResponse.add("entities", getEntitiesAroundPlayer(entityRange));
					break;
				case "world_info":
					syncResponse.add("world_info", getWorldInfo());
					break;
				case "send_chat":
					String message = query.has("message") ? query.get("message").getAsString() : "";
					syncResponse.add("result", sendChatMessage(message));
					break;
				case "drop_item":
					if (query.has("slot")) {
						int slot = query.get("slot").getAsInt();
						syncResponse.add("result", dropItemFromSlot(slot));
					} else if (query.has("name")) {
						String itemName = query.get("name").getAsString();
						syncResponse.add("result", dropItemsByName(itemName));
					} else {
						syncResponse.addProperty("error", "drop_item requires either 'slot' or 'name' parameter");
					}
					break;
				case "rotate":
					float yaw = query.has("yaw") ? query.get("yaw").getAsFloat() : client.player.getYaw();
					float pitch = query.has("pitch") ? query.get("pitch").getAsFloat() : client.player.getPitch();
					syncResponse.add("result", rotatePlayer(yaw, pitch));
					break;
				case "point_to_entity":
                     String entityUuid = query.has("uuid") ? query.get("uuid").getAsString() : "";
                     syncResponse.add("result", pointToEntity(entityUuid));
                     break;
				case "point_to_xyz":
                    float x = query.get("x").getAsFloat();
	                float y = query.get("y").getAsFloat();
	                float z = query.get("z").getAsFloat();
                    syncResponse.add("result", pointToXYZ(x, y, z));
                    break;
				case "get_screen_pos":
					if (query.has("slot")) {
						int slot = query.get("slot").getAsInt();
						syncResponse.add("result", getInventorySlotScreenPosition(slot, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), client.getWindow().getScaleFactor()));
					} else {
						syncResponse.addProperty("error", "get_screen_pos requires either 'slot' parameter");
					}
					break;
				default:
					syncResponse.addProperty("error", "Unknown query type: " + type);
				}

				return syncResponse;
			}).get();

			return result;

		} catch (Exception e) {
			response.addProperty("error", "Error processing query: " + e.getMessage());
			return response;
		}
	}
	
	private JsonObject pointToXYZ(float x, float y, float z) {
		JsonObject result = new JsonObject();
        
        try {
            ClientPlayerEntity player = client.player;
            ClientWorld world = client.world;
            
            if (player != null && world != null) {
                //UUID targetUuid = UUID.fromString(entityUuid);
                
                    // Calculate the yaw and pitch needed to look at the entity
                    double deltaX = x - player.getX();
                    double deltaY = y - player.getEyeY();
                    double deltaZ = z - player.getZ();
                    
                    // Calculate yaw (horizontal rotation)
                    double yaw = Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI - 90.0;
                    
                    // Calculate pitch (vertical rotation)
                    double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                    double pitch = -Math.atan2(deltaY, horizontalDistance) * 180.0 / Math.PI;
                    
                    // Normalize yaw to 0-360 range
                    yaw = yaw % 360.0;
                    if (yaw < 0) {
                        yaw += 360.0;
                    }
                    
                    // Clamp pitch to valid range (-90 to 90)
                    pitch = Math.max(-90.0, Math.min(90.0, pitch));
                    
                    // Set the player's rotation
                    player.setYaw((float) yaw);
                    player.setPitch((float) pitch);
                    
                    // Calculate distance for additional info
                    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                    
                    result.addProperty("success", true);
                    result.addProperty("message", "Pointed to location: " + x + " " + y + " " + z);
                    result.addProperty("distance", Math.round(distance * 100.0) / 100.0);
                    result.addProperty("yaw", Math.round(yaw * 100.0) / 100.0);
                    result.addProperty("pitch", Math.round(pitch * 100.0) / 100.0);
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "Player not available");
            }
        } catch (IllegalArgumentException e) {
            result.addProperty("success", false);
            result.addProperty("error", "Invalid format");
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", "Failed to point to XYZ: " + e.getMessage());
        }
        
        return result;
	}

	private JsonObject pointToEntity(String entityUuid) {
        JsonObject result = new JsonObject();
        
        if (entityUuid == null || entityUuid.trim().isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "Entity UUID cannot be empty");
            return result;
        }
        
        try {
            ClientPlayerEntity player = client.player;
            ClientWorld world = client.world;
            
            if (player != null && world != null) {
                //UUID targetUuid = UUID.fromString(entityUuid);
                
                // Search for the entity in a large area around the player
                Box searchBox = new Box(player.getBlockPos()).expand(100);
                List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, searchBox, 
                    entity -> entity.getUuidAsString().equals(entityUuid));
                
                if (!entities.isEmpty()) {
                    LivingEntity targetEntity = entities.get(0);
                    
                    // Calculate the yaw and pitch needed to look at the entity
                    double deltaX = targetEntity.getX() - player.getX();
                    double deltaY = targetEntity.getY() + targetEntity.getHeight() / 2 - player.getEyeY();
                    double deltaZ = targetEntity.getZ() - player.getZ();
                    
                    // Calculate yaw (horizontal rotation)
                    double yaw = Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI - 90.0;
                    
                    // Calculate pitch (vertical rotation)
                    double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                    double pitch = -Math.atan2(deltaY, horizontalDistance) * 180.0 / Math.PI;
                    
                    // Normalize yaw to 0-360 range
                    yaw = yaw % 360.0;
                    if (yaw < 0) {
                        yaw += 360.0;
                    }
                    
                    // Clamp pitch to valid range (-90 to 90)
                    pitch = Math.max(-90.0, Math.min(90.0, pitch));
                    
                    // Set the player's rotation
                    player.setYaw((float) yaw);
                    player.setPitch((float) pitch);
                    
                    // Calculate distance for additional info
                    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                    
                    result.addProperty("success", true);
                    result.addProperty("message", "Pointed to entity: " + targetEntity.getName().getString());
                    result.addProperty("entityName", sanitizeString(targetEntity.getName().getString()));
                    result.addProperty("entityType", sanitizeString(targetEntity.getType().toString()));
                    result.addProperty("distance", Math.round(distance * 100.0) / 100.0);
                    result.addProperty("yaw", Math.round(yaw * 100.0) / 100.0);
                    result.addProperty("pitch", Math.round(pitch * 100.0) / 100.0);
                    result.addProperty("entityX", targetEntity.getX());
                    result.addProperty("entityY", targetEntity.getY());
                    result.addProperty("entityZ", targetEntity.getZ());
                } else {
                    result.addProperty("success", false);
                    result.addProperty("error", "Entity with UUID " + entityUuid + " not found within 100 blocks");
                }
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "Player not available");
            }
        } catch (IllegalArgumentException e) {
            result.addProperty("success", false);
            result.addProperty("error", "Invalid UUID format: " + entityUuid);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", "Failed to point to entity: " + e.getMessage());
        }
        
        return result;
    }
	
	private JsonObject getInventorySlotScreenPosition(int slotIndex, int screenWidth, int screenHeight, float guiScale) {
	    JsonObject pos = new JsonObject();

	    // Assume player inventory screen is 176x166, standard for most GUIs
	    int guiWidth = 176;
	    int guiHeight = 166;

	    // Screen center
	    int centerX = screenWidth / 2;
	    int centerY = screenHeight / 2;

	    int guiLeft = centerX - guiWidth / 2;
	    int guiTop = centerY - guiHeight / 2;

	    int slotX = 0;
	    int slotY = 0;

	    if (slotIndex >= 0 && slotIndex < 9) {
	        // Hotbar: 9 slots
	        slotX = guiLeft + 8 + slotIndex * 18;
	        slotY = guiTop + 142;
	    } else if (slotIndex >= 9 && slotIndex < 36) {
	        // Main inventory: 27 slots (3 rows)
	        int index = slotIndex - 9;
	        int row = index / 9;
	        int col = index % 9;

	        slotX = guiLeft + 8 + col * 18;
	        slotY = guiTop + 84 + row * 18;
	    } else {
	        pos.addProperty("error", "Unsupported slot index");
	        return pos;
	    }

	    pos.addProperty("x", slotX);
	    pos.addProperty("y", slotY);
	    return pos;
	}

	private JsonObject getPlayerInventory() {
		JsonObject inventoryData = new JsonObject();
		List<JsonObject> items = new ArrayList<>();

		ClientPlayerEntity player = client.player;

		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) {
				JsonObject item = new JsonObject();
				item.addProperty("slot", i);
				item.addProperty("type", sanitizeString(stack.getItem().toString()));
				item.addProperty("name", sanitizeString(stack.getName().getString()));
				item.addProperty("count", stack.getCount());

				/*
				 * // Add component data safely if (stack.getComponents() != null) { try {
				 * ComponentMap components = stack.getComponents(); // Convert to string and
				 * escape properly for JSON String componentStr = components.toString(); //
				 * Remove or escape problematic characters componentStr =
				 * componentStr.replaceAll("[\r\n\t]", " "); componentStr =
				 * componentStr.replaceAll("\"", "\\\""); item.addProperty("components",
				 * componentStr); } catch (Exception e) { item.addProperty("components",
				 * "Error reading components"); } }
				 */

				// Add durability info if available
				if (stack.isDamageable()) {
					item.addProperty("damage", stack.getDamage());
					item.addProperty("maxDamage", stack.getMaxDamage());
					item.addProperty("durability", stack.getMaxDamage() - stack.getDamage());
				}

				// Add enchantments count
				if (stack.hasEnchantments()) {
					item.addProperty("enchanted", true);
				} else {
					item.addProperty("enchanted", false);
				}

				items.add(item);
			}
		}

		inventoryData.add("items", gson.toJsonTree(items));
		return inventoryData;
	}

	private JsonObject getPlayerPosition() {
		JsonObject positionData = new JsonObject();
		ClientPlayerEntity player = client.player;

		positionData.addProperty("x", player.getX());
		positionData.addProperty("y", player.getY());
		positionData.addProperty("z", player.getZ());
		positionData.addProperty("yaw", player.getYaw());
		positionData.addProperty("pitch", player.getPitch());
		positionData.addProperty("health", player.getHealth());
		positionData.addProperty("maxHealth", player.getMaxHealth());
		positionData.addProperty("food", player.getHungerManager().getFoodLevel());
		positionData.addProperty("saturation", player.getHungerManager().getSaturationLevel());
		positionData.addProperty("experience", player.totalExperience);
		positionData.addProperty("level", player.experienceLevel);

		return positionData;
	}

	private JsonObject getBlocksAroundPlayer(int range) {
		JsonObject blocksData = new JsonObject();
		List<JsonObject> blocks = new ArrayList<>();

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		BlockPos playerPos = player.getBlockPos();

		for (int x = -range; x <= range; x++) {
			for (int y = -range; y <= range; y++) {
				for (int z = -range; z <= range; z++) {
					BlockPos pos = playerPos.add(x, y, z);

					try {
						JsonObject blockData = new JsonObject();
						blockData.addProperty("x", pos.getX());
						blockData.addProperty("y", pos.getY());
						blockData.addProperty("z", pos.getZ());

						// Safely get block type with sanitization
						String blockType = sanitizeString(world.getBlockState(pos).getBlock().toString());
						blockData.addProperty("type", blockType);

						// Check if it's a container (only if we can access it)
						BlockEntity blockEntity = world.getBlockEntity(pos);
						if (blockEntity instanceof Inventory) {
							Inventory inventory = (Inventory) blockEntity;
							List<JsonObject> contents = new ArrayList<>();

							try {
								for (int i = 0; i < inventory.size(); i++) {
									ItemStack stack = inventory.getStack(i);
									if (!stack.isEmpty()) {
										JsonObject item = new JsonObject();
										item.addProperty("slot", i);
										item.addProperty("type", sanitizeString(stack.getItem().toString()));
										item.addProperty("name", sanitizeString(stack.getName().getString()));
										item.addProperty("count", stack.getCount());
										contents.add(item);
									}
								}

								blockData.add("contents", gson.toJsonTree(contents));
							} catch (Exception e) {
								// Skip container contents if there's an error
								blockData.addProperty("contents_error", "Could not read container contents");
							}
						}

						blocks.add(blockData);
					} catch (Exception e) {
						// Skip problematic blocks but continue processing others
						JsonObject errorBlock = new JsonObject();
						errorBlock.addProperty("x", pos.getX());
						errorBlock.addProperty("y", pos.getY());
						errorBlock.addProperty("z", pos.getZ());
						errorBlock.addProperty("type", "error");
						errorBlock.addProperty("error", "Could not read block data");
						blocks.add(errorBlock);
					}
				}
			}
		}

		blocksData.add("blocks", gson.toJsonTree(blocks));
		return blocksData;
	}

	private JsonObject getEntitiesAroundPlayer(int range) {
		JsonObject entitiesData = new JsonObject();
		List<JsonObject> entities = new ArrayList<>();

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;

		Box searchBox = new Box(player.getBlockPos()).expand(range);
		List<LivingEntity> livingEntities = world.getEntitiesByClass(LivingEntity.class, searchBox,
				entity -> !(entity instanceof ClientPlayerEntity));

		for (LivingEntity entity : livingEntities) {
			try {
				JsonObject entityData = new JsonObject();
				entityData.addProperty("type", sanitizeString(entity.getType().toString()));
				entityData.addProperty("name", sanitizeString(entity.getName().getString()));
				entityData.addProperty("x", entity.getX());
				entityData.addProperty("y", entity.getY());
				entityData.addProperty("z", entity.getZ());
				entityData.addProperty("health", entity.getHealth());
				entityData.addProperty("maxHealth", entity.getMaxHealth());
				entityData.addProperty("uuid", entity.getUuidAsString());

				if (entity instanceof MobEntity) {
					boolean hostile = entity instanceof net.minecraft.entity.mob.HostileEntity;
					boolean passive = entity instanceof net.minecraft.entity.passive.PassiveEntity;
					boolean neutral = entity instanceof net.minecraft.entity.mob.Angerable;
					entityData.addProperty("hostile", hostile);
					entityData.addProperty("passive", passive);
					entityData.addProperty("neutral", neutral);
				}

				if (entity instanceof PlayerEntity) {
					entityData.addProperty("isPlayer", true);
				} else {
					entityData.addProperty("isPlayer", false);
				}

				entities.add(entityData);
			} catch (Exception e) {
				// Skip problematic entities but continue processing others
			}
		}

		entitiesData.add("entities", gson.toJsonTree(entities));
		return entitiesData;
	}

	private JsonObject getWorldInfo() {
		JsonObject worldInfo = new JsonObject();
		ClientWorld world = client.world;

		worldInfo.addProperty("dimension", sanitizeString(world.getRegistryKey().getValue().toString()));
		worldInfo.addProperty("time", world.getTimeOfDay());
		worldInfo.addProperty("isDay", world.isDay());
		worldInfo.addProperty("isRaining", world.isRaining());
		worldInfo.addProperty("isThundering", world.isThundering());
		worldInfo.addProperty("difficulty", sanitizeString(world.getDifficulty().toString()));

		return worldInfo;
	}

	private JsonObject sendChatMessage(String message) {
		JsonObject result = new JsonObject();

		if (message == null || message.trim().isEmpty()) {
			result.addProperty("success", false);
			result.addProperty("error", "Message cannot be empty");
			return result;
		}

		try {
			ClientPlayerEntity player = client.player;
			if (player != null) {
				// Send chat message
				if (message.startsWith("/")) {
					// Command
					player.networkHandler.sendChatCommand(message.substring(1));
				} else {
					// Regular chat message
					player.networkHandler.sendChatMessage(message);
				}
				result.addProperty("success", true);
				result.addProperty("message", "Sent: " + message);
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "Player not available");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to send message: " + e.getMessage());
		}

		return result;
	}

	private JsonObject dropItemFromSlot(int slot) {
        JsonObject result = new JsonObject();
        
        try {
            ClientPlayerEntity player = client.player;
            if (player != null) {
            	if (slot >= 0 && slot < player.getInventory().size()) {
            	    ItemStack stack = player.getInventory().getStack(slot);
            	    if (!stack.isEmpty()) {
            	        player.getInventory().setSelectedSlot(slot); // This *does* inform the server

            	        // Now ask the client to drop selected item
            	        player.dropSelectedItem(false); // false = drop single item, true = drop whole stack

            	        result.addProperty("success", true);
            	        result.addProperty("message", "Dropped " + stack.getCount() + "x " + stack.getName().getString() + " from slot " + slot);
            	    }
            	} else {
                    result.addProperty("success", false);
                    result.addProperty("error", "Invalid slot number: " + slot + " (valid range: 0-" + (player.getInventory().size() - 1) + ")");
                }
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "Player not available");
            }
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", "Failed to drop item: " + e.getMessage());
        }
        
        return result;
    }

	private JsonObject dropItemsByName(String itemName) {
		JsonObject result = new JsonObject();

		try {
			ClientPlayerEntity player = client.player;
			if (player != null) {
				int totalDropped = 0;
				int slotsCleared = 0;
				List<Integer> slotsToProcess = new ArrayList<>();

				// Find all matching slots first
				for (int i = 0; i < player.getInventory().size(); i++) {
					ItemStack stack = player.getInventory().getStack(i);
					if (!stack.isEmpty()) {
						String stackName = stack.getName().getString().toLowerCase();
						String searchName = itemName.toLowerCase();

						// Check if name matches (partial match)
						if (stackName.contains(searchName)) {
							slotsToProcess.add(i);
							totalDropped += stack.getCount();
							slotsCleared++;
						}
					}
				}

				// Process each slot
				for (int slot : slotsToProcess) {
					dropItemFromSlot(slot);
				}

				if (totalDropped > 0) {
					result.addProperty("success", true);
					result.addProperty("message", "Dropped " + totalDropped + " items from " + slotsCleared
							+ " slots matching '" + itemName + "'");
				} else {
					result.addProperty("success", false);
					result.addProperty("error", "No items found matching '" + itemName + "'");
				}
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "Player not available");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to drop items: " + e.getMessage());
		}

		return result;
	}

	private JsonObject rotatePlayer(float yaw, float pitch) {
		JsonObject result = new JsonObject();

		try {
			ClientPlayerEntity player = client.player;
			if (player != null) {
				// Clamp pitch to valid range (-90 to 90)
				pitch = Math.max(-90.0f, Math.min(90.0f, pitch));

				// Normalize yaw to 0-360 range
				yaw = yaw % 360.0f;
				if (yaw < 0) {
					yaw += 360.0f;
				}

				// Set the player's rotation
				player.setYaw(yaw);
				player.setPitch(pitch);

				result.addProperty("success", true);
				result.addProperty("message", "Rotated player to yaw: " + yaw + "°, pitch: " + pitch + "°");
				result.addProperty("yaw", yaw);
				result.addProperty("pitch", pitch);
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "Player not available");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to rotate player: " + e.getMessage());
		}

		return result;
	}

	/**
	 * Sanitize strings to prevent JSON parsing issues
	 */
	private String sanitizeString(String input) {
		if (input == null)
			return "";

		return input.replaceAll("[\r\n\t]", " ") // Replace newlines and tabs with spaces
				.replaceAll("[\u0000-\u001f]", "") // Remove control characters
				.replaceAll("[\u007f-\u009f]", "") // Remove more control characters
				.trim();
	}
}
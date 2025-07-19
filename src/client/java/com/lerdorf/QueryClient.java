package com.lerdorf;

import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Unit;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.BlockStateRaycastContext;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerEntity.SleepFailureReason;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.block.DoorBlock;

import me.cortex.facebar.LocatorBarData;

import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttribute;

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
				case "get_chat":
					syncResponse.add("result", getUnreadChat());
					break;
				case "position":
					syncResponse.add("position", getPlayerPosition());
					break;
				case "blocks":
					int range = query.has("range") ? query.get("range").getAsInt() : 5;
					syncResponse.add("blocks", getBlocksAroundPlayer(range));
					break;
				case "entities":
				{
					int entityRange = query.has("range") ? query.get("range").getAsInt() : 10;
					syncResponse.add("entities", getEntitiesAroundPlayer(entityRange));
					break;
				}
				case "items":
				{
					int entityRange = query.has("range") ? query.get("range").getAsInt() : 10;
					syncResponse.add("entities", getItemsAroundPlayer(entityRange));
					break;
				}
				case "world_info":
					syncResponse.add("world_info", getWorldInfo());
					break;
				case "send_chat":
					String message = query.has("message") ? query.get("message").getAsString() : "";
					syncResponse.add("result", sendChatMessage(message));
					break;
				case "baritone_cmd":
					String cmd = query.has("cmd") ? query.get("cmd").getAsString() : "";
					syncResponse.add("result", sendBaritoneCommand(cmd));
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
				case "get_entity_position":
				{
					String entityUuid = query.has("uuid") ? query.get("uuid").getAsString() : "";
					 Entity entity = null;
					 try {
						 entity = getEntity(entityUuid);
					 } catch (Exception e) {
						 syncResponse.addProperty("error", e.getMessage());
					 }
					 if (entity != null) {
						 syncResponse.addProperty("result", "success");
						 syncResponse.addProperty("uuid", entity.getUuidAsString());
						 syncResponse.addProperty("name", entity.getName().getString());
						 syncResponse.addProperty("type", entity.getType().getName().getString());
						 syncResponse.addProperty("x", entity.getX());
						 syncResponse.addProperty("y", entity.getY());
						 syncResponse.addProperty("z", entity.getZ());
						 syncResponse.addProperty("yaw", entity.getYaw());
						 syncResponse.addProperty("pitch", entity.getPitch());
					 } else {
						 syncResponse.addProperty("result", "failure");
					 }
                    break;
				}
				case "get_entity_velocity":
				{
					String entityUuid = query.has("uuid") ? query.get("uuid").getAsString() : "";
					 Entity entity = null;
					 try {
						 entity = getEntity(entityUuid);
					 } catch (Exception e) {
						 syncResponse.addProperty("error", e.getMessage());
					 }
					 if (entity != null) {
						 syncResponse.addProperty("result", "success");
						 syncResponse.addProperty("uuid", entity.getUuidAsString());
						 syncResponse.addProperty("name", entity.getName().getString());
						 syncResponse.addProperty("type", entity.getType().getName().getString());
						 syncResponse.addProperty("vx", entity.getVelocity().getX());
						 syncResponse.addProperty("vy", entity.getVelocity().getY());
						 syncResponse.addProperty("vz", entity.getVelocity().getZ());
					 } else {
						 syncResponse.addProperty("result", "failure");
					 }
                    break;
				}
				case "get_entity":
				{
					 String entityUuid = query.has("uuid") ? query.get("uuid").getAsString() : "";
					 Entity entity = null;
					 try {
						 entity = getEntity(entityUuid);
					 } catch (Exception e) {
						 syncResponse.addProperty("error", e.getMessage());
					 }
					 if (entity != null) {
						 syncResponse.addProperty("result", "success");
						 syncResponse.addProperty("uuid", entity.getUuidAsString());
						 syncResponse.addProperty("name", entity.getName().getString());
						 syncResponse.addProperty("type", entity.getType().getName().getString());
						 syncResponse.addProperty("display_name", entity.getDisplayName().getString());
						 syncResponse.addProperty("custom_name", entity.getCustomName().getString());
						 syncResponse.addProperty("x", entity.getX());
						 syncResponse.addProperty("y", entity.getY());
						 syncResponse.addProperty("z", entity.getZ());
						 syncResponse.addProperty("vx", entity.getVelocity().getX());
						 syncResponse.addProperty("vy", entity.getVelocity().getY());
						 syncResponse.addProperty("vz", entity.getVelocity().getZ());
						 syncResponse.addProperty("yaw", entity.getYaw());
						 syncResponse.addProperty("pitch", entity.getPitch());
						 syncResponse.addProperty("age", entity.age);
						 syncResponse.addProperty("in_powder_snow", entity.inPowderSnow);
						 syncResponse.addProperty("on_ground", entity.isOnGround());
						 syncResponse.addProperty("submerged_in_water", entity.isSubmergedInWater());
						 syncResponse.addProperty("touching_water", entity.isTouchingWater());
						 syncResponse.addProperty("touching_water_or_rain", entity.isTouchingWaterOrRain());
						 syncResponse.addProperty("on_fire", entity.isOnFire());
						 syncResponse.addProperty("fire_ticks", entity.getFireTicks());
						 syncResponse.addProperty("alive", entity.isAlive());
						 
						 if (entity instanceof LivingEntity le) {
							 syncResponse.addProperty("living_entity", true);
							 syncResponse.addProperty("fall_distance", le.fallDistance);
							 syncResponse.addProperty("health", le.getHealth());
							 syncResponse.addProperty("max_health", le.getMaxHealth());
							 syncResponse.addProperty("left_hand", le.getStackInArm(Arm.LEFT) != null ? le.getStackInArm(Arm.LEFT).getItem().toString() : null);
							 syncResponse.addProperty("right_hand", le.getStackInArm(Arm.RIGHT) != null ? le.getStackInArm(Arm.RIGHT).getItem().toString() : null);
							 syncResponse.addProperty("helmet", le.getEquippedStack(EquipmentSlot.HEAD) != null ? le.getEquippedStack(EquipmentSlot.HEAD).getItem().toString() : null);
							 syncResponse.addProperty("chest", le.getEquippedStack(EquipmentSlot.CHEST) != null ? le.getEquippedStack(EquipmentSlot.CHEST).getItem().toString() : null);
							 syncResponse.addProperty("legs", le.getEquippedStack(EquipmentSlot.LEGS) != null ? le.getEquippedStack(EquipmentSlot.LEGS).getItem().toString() : null);
							 syncResponse.addProperty("feet", le.getEquippedStack(EquipmentSlot.FEET) != null ? le.getEquippedStack(EquipmentSlot.FEET).getItem().toString() : null);
							 syncResponse.addProperty("armor", le.getArmor());
							 syncResponse.addProperty("has_vehicle", le.hasVehicle());
							 if (le.hasVehicle())
								 syncResponse.addProperty("vehicle", le.getVehicle().getUuidAsString());
							 syncResponse.addProperty("has_passenger", le.hasPassengers());
							 if (le.hasPassengers())
								 syncResponse.addProperty("passengers", le.getPassengerList().toString());
							 
							 if (le instanceof MobEntity) {
									boolean hostile = entity instanceof net.minecraft.entity.mob.HostileEntity;
									boolean passive = entity instanceof net.minecraft.entity.passive.PassiveEntity;
									boolean neutral = entity instanceof net.minecraft.entity.mob.Angerable;
									syncResponse.addProperty("hostile", hostile);
									syncResponse.addProperty("passive", passive);
									syncResponse.addProperty("neutral", neutral);
							}

							if (le instanceof PlayerEntity p) {
								syncResponse.addProperty("player", true);
							} else {
								syncResponse.addProperty("player", false);
							}
						 } else {
							 syncResponse.addProperty("living_entity", false);
						 }
					 } else {
						 syncResponse.addProperty("result", "failure");
					 }
                     break;
				}
				case "point_to_entity":
				{
                     String entityUuid = query.has("uuid") ? query.get("uuid").getAsString() : "";
                     syncResponse.add("result", pointToEntity(entityUuid));
                     break;
				}
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
				case "get_block":
					if (query.has("x") && query.has("y") && query.has("z")) {
						int bx = (int)query.get("x").getAsFloat();
		                int by = (int)query.get("y").getAsFloat();
		                int bz = (int)query.get("z").getAsFloat();
		                
		                ClientPlayerEntity player = client.player;
		        		ClientWorld world = client.world;
		                
		                BlockPos pos = new BlockPos(bx, by, bz);
		                
		                // Safely get block type with sanitization
						String blockType = sanitizeString(world.getBlockState(pos).getBlock().toString());
						syncResponse.addProperty("type", blockType);

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

								syncResponse.add("contents", gson.toJsonTree(contents));
							} catch (Exception e) {
								// Skip container contents if there's an error
								syncResponse.addProperty("contents_error", "Could not read container contents");
							}
						}
	                    break;
					} else {
						syncResponse.addProperty("error", "get_block requires 'x' 'y' 'z' parameters");
					}
					break;
				case "left_click":
					syncResponse.add("result", performLeftClick());
					break;
				case "right_click":
					syncResponse.add("result", performRightClick());
					break;
				case "select_slot":
					if (query.has("slot")) {
						int slot = query.get("slot").getAsInt();
						syncResponse.add("result", selectHotbarSlot(slot));
					} else {
						syncResponse.addProperty("error", "select_slot requires 'slot' parameter");
					}
					break;
				case "hotbar":
					syncResponse.add("hotbar", getHotbarItems());
					break;
				case "open_container":
					syncResponse.add("result", openContainerUnderCrosshair());
					break;
				case "open_crafting_table":
					syncResponse.add("result", openTableUnderCrosshair());
					break;
				case "attack":
					syncResponse.add("result", attackEntityUnderCrosshair());
					break;
				case "shoot_bow":
	                float overshoot = query.has("overshoot") ? query.get("overshoot").getAsFloat() : 0;
					if (query.has("x") && query.has("y") && query.has("z")) {
						float tx = query.get("x").getAsFloat();
		                float ty = query.get("y").getAsFloat();
		                float tz = query.get("z").getAsFloat();
	                    syncResponse.add("result", shootBowAt(tx, ty, tz, overshoot));
	                    break;
					} else if (query.has("entity")) {
						String targetUuid = query.has("entity") ? query.get("entity").getAsString() : "";
						LivingEntity targetEntity = getLivingEntity(targetUuid);
						if (targetEntity != null) {
		                    syncResponse.add("result", shootBowAt(targetEntity, overshoot));
						} else {
							syncResponse.addProperty("error", "entity not found - " + targetUuid);
						}
						
					} else {
						syncResponse.addProperty("error", "shoot_bow requires either 'x' 'y' 'z' parameters or 'entity' parameter");
					}
					break;
				case "use_bed":
				{
					int tx = 0;
					int ty = -69420;
					int tz = 0;
					if (query.has("x") && query.has("y") && query.has("z")) {
						tx = (int)query.get("x").getAsFloat();
		                ty = (int)query.get("y").getAsFloat();
		                tz = (int)query.get("z").getAsFloat();
					}
					else if (client.crosshairTarget instanceof BlockHitResult hit) {
						tx = hit.getBlockPos().getX();
						ty = hit.getBlockPos().getY();
						tz = hit.getBlockPos().getZ();
					}
					if (ty != -69420) {
		                // Sleeps in the bed at the given coordinates
		                syncResponse.add("result", useBed(tx, ty, tz));
					} else {
						syncResponse.addProperty("error", "use_bed requires 'x' 'y' 'z' parameters, or a block under the crosshair");
					}
					break;
				}
				case "leave_bed":
				{
					syncResponse.add("result", leaveBed());
					break;
				}
				case "use_door":
				{
					int tx = 0;
					int ty = -69420;
					int tz = 0;
					if (query.has("x") && query.has("y") && query.has("z")) {
						tx = (int)query.get("x").getAsFloat();
		                ty = (int)query.get("y").getAsFloat();
		                tz = (int)query.get("z").getAsFloat();
					}
					else if (client.crosshairTarget instanceof BlockHitResult hit) {
						tx = hit.getBlockPos().getX();
						ty = hit.getBlockPos().getY();
						tz = hit.getBlockPos().getZ();
					}
					if (ty != -69420) {
						boolean targetState;
						if (query.has("state")) {
							targetState = query.get("state").getAsBoolean();
							syncResponse.add("result", toggleDoor(tx, ty, tz, targetState, false));
						}
						else
						{
							// set the targetState to the opposite of the current door state
							syncResponse.add("result", toggleDoor(tx, ty, tz, false, true));
						}
						
					} else {
						syncResponse.addProperty("error", "use_door requires 'x' 'y' 'z' parameters");
					}
					break;
				}
				case "exit_gui":
					// Exits whatever gui is currently open (like a chest or a villager or an inventory...)
					if (client.currentScreen != null) {
						client.execute(() -> client.setScreen(null));
						syncResponse.addProperty("result", "Closed GUI screen");
					} else {
						syncResponse.addProperty("result", "No GUI screen was open");
					}
					break;
				case "players":
				    syncResponse.add("players", getOnlinePlayers());
				    break;
				case "kill_aura":
				{
					if (query.has("target")) {
						String id = query.get("target").getAsString();
						Entity target = client.world.getEntity(UUID.fromString(id));
						if (target != null && target.isAlive()) {
							killauraTargets.add(target.getUuid());
							syncResponse.addProperty("target_result", "success");
						} else {
							syncResponse.addProperty("target_result", "failure");
						}
					}
					if (query.has("enable")) {
						killauraEnabled = query.get("enable").getAsBoolean();
					}
					syncResponse.addProperty("enabled", killauraEnabled);
					if (query.has("remove")) {
						String id = query.get("remove").getAsString();
						UUID uuid = UUID.fromString(id);
						if (uuid != null) {
							if (killauraTargets.contains(uuid)) {
								killauraTargets.remove(uuid);
								syncResponse.addProperty("remove_result", "success");
							} else {
								syncResponse.addProperty("remove_result", "failure - uuid not in killaura targets");
							}
						} else {
							syncResponse.addProperty("remove_result", "failure - unknown entity");
						}
					}
					if (query.has("use_bow")) {
						killauraBow = query.get("use_bow").getAsBoolean();
					}
					syncResponse.addProperty("use_bow", killauraBow);
					if (query.has("baritone") ) {
						killauraBaritone = query.get("baritone").getAsBoolean();
					}
					syncResponse.addProperty("baritone", killauraBaritone);
					if (query.has("defend") ) {
						defend = query.get("defend").getAsBoolean();
					}
					syncResponse.addProperty("defend", defend);
					if (query.has("clear") && query.get("clear").getAsBoolean()) {
						killauraTargets.clear();
					}
					syncResponse.addProperty("enabled", killauraEnabled);
					break;
				}
				case "kill_aura_targets":
				{
					syncResponse.addProperty("enabled", killauraEnabled);
					JsonArray targets = new JsonArray();
					for (UUID uuid : killauraTargets) {
					    Entity entity = client.world.getEntity(uuid);
					    if (entity != null) {
					        JsonObject entry = new JsonObject();
					        entry.addProperty("uuid", uuid.toString());
					        entry.addProperty("name", entity.getName().getString());
					        entry.addProperty("entity_type", entity.getType().toString());
					        targets.add(entry);
					    }
					}
					syncResponse.add("result", targets);
					break;
				}
				case "swap":
				{
					try {
						if (query.has("slot1") && query.has("slot2")) {
							int slot1 = query.get("slot1").getAsInt();
							int slot2 = query.get("slot2").getAsInt();
							boolean success = swapSlots(slot1, slot2);
							syncResponse.addProperty("success", success);
						} else {
							syncResponse.addProperty("error", "must include `slot1` and `slot2` integer parameters");
						}
					} catch (Exception e) {
						syncResponse.addProperty("error", e.getMessage());
					}
					break;
				}
				case "shield":
				{
					int ticks = 20;
					if (query.has("ticks"))
						ticks = query.get("ticks").getAsInt();
					syncResponse.addProperty("success", useShield(ticks, null));
					break;
				}
				case "eat":
				{
					syncResponse.addProperty("successn", eatUntilFull());
					break;
				}
				case "heal":
				{
					syncResponse.addProperty("successn", tryToHeal());
					break;
				}
				case "open_villager":
				{
					try {
						if (query.has("uuid")) {
							UUID id = UUID.fromString(query.get("uuid").getAsString());
							syncResponse.addProperty("success", openVillagerTradeByUuid(id));
						} else if (query.has("x") && query.has("y") && query.has("z")) {
							syncResponse.addProperty("success", openVillagerTradeAtPos(new BlockPos(query.get("x").getAsInt(), query.get("y").getAsInt(), query.get("z").getAsInt())));
						}
					} catch (Exception e) {
						syncResponse.addProperty("error", e.getMessage());
					}
					break;
				}
				case "trade":
				{
					try {
						if (query.has("output")) {
							syncResponse.addProperty("result", performTrade(query.get("output").getAsString(), null, -1));
						} else if (query.has("input")) {
							syncResponse.addProperty("result", performTrade(null, query.get("input").getAsString(), -1));
						} else if (query.has("index")) {
							syncResponse.addProperty("result", performTrade(null, null, query.get("index").getAsInt()));
						}
					} catch (Exception e) {
						syncResponse.addProperty("error", e.getMessage());
					}
					break;
				}
				case "break":
				{
					syncResponse.addProperty("success", breakBlockUnderCrosshair());
					break;
				}
				case "break_tree":
				{
					try {
						if (!(client.crosshairTarget instanceof BlockHitResult hit)) 
							syncResponse.addProperty("error", "No block on crosshair");
						else
							breakTreeAndReplant(hit.getBlockPos());
					} catch (Exception e) {
						
					}
				}
				case "is_breaking_tree":
				{
					syncResponse.addProperty("result", isChoppingTree && !logsToBreak.isEmpty());
					break;
				}
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
	
	private Queue<BlockPos> logsToBreak = new LinkedList<>();
	private List<BlockPos> replantSpot = null;
	private boolean isChoppingTree = false;
	
	private void plantSaplingAt(BlockPos pos) {
	    for (int i = 0; i < client.player.getInventory().size(); i++) {
	        ItemStack stack = client.player.getInventory().getStack(i);
	        if (stack.getItem().getTranslationKey().contains("sapling")) {
	            if (i < 9) {
	                client.player.getInventory().setSelectedSlot(i);
	            } else {
	                swapSlots(i, 5);
	                client.player.getInventory().setSelectedSlot(5);
	            }

	            pointToXYZ(pos.getX()+0.5f, pos.getY(), pos.getZ()+0.5f);
	            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos.down()), Direction.UP, pos.down(), false);
	            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
	            client.player.swingHand(Hand.MAIN_HAND);
	            sendChatMessage("Planted sapling at " + pos.toShortString());
	            return;
	        }
	    }

	    sendChatMessage("No sapling found to plant.");
	}

	public void breakTreeAndReplant(BlockPos basePos) {
	    if (client.world == null || client.player == null) return;

	    while (client.world.getBlockState(basePos.down()).getBlock().getTranslationKey().contains("log")) {
	    	basePos = basePos.down();
	    }
	    
	    BlockPos cursor = basePos;
	    BlockState state;
	    replantSpot.clear();
	    logsToBreak.clear();
	    replantSpot.add(basePos);
	    isChoppingTree = true;
	    
	    boolean north = false;
	    boolean northeast = false;
	    boolean northwest = false;
	    boolean east = false;
	    boolean west = false;
	    boolean south = false;
	    boolean southeast = false;
	    boolean southwest = false;

	    if (client.world.getBlockState(basePos.north()).getBlock().getTranslationKey().contains("log")) {
	    	north = true;
	    	replantSpot.add(basePos.north());
	    }
	    if (client.world.getBlockState(basePos.north().east()).getBlock().getTranslationKey().contains("log")) {
	    	northeast = true;
	    	replantSpot.add(basePos.north().east());
	    }
	    if (client.world.getBlockState(basePos.north().west()).getBlock().getTranslationKey().contains("log")) {
	    	northwest = true;
	    	replantSpot.add(basePos.north().west());
	    }
	    if (client.world.getBlockState(basePos.east()).getBlock().getTranslationKey().contains("log")) {
	    	east = true;
	    	replantSpot.add(basePos.east());
	    }
	    if (client.world.getBlockState(basePos.west()).getBlock().getTranslationKey().contains("log")) {
	    	west = true;
	    	replantSpot.add(basePos.west());
	    }
	    if (client.world.getBlockState(basePos.south()).getBlock().getTranslationKey().contains("log")) {
	    	south = true;
	    	replantSpot.add(basePos.south());
	    }
	    if (client.world.getBlockState(basePos.south().east()).getBlock().getTranslationKey().contains("log")) {
	    	southeast = true;
	    	replantSpot.add(basePos.south().east());
	    }
	    if (client.world.getBlockState(basePos.south().west()).getBlock().getTranslationKey().contains("log")) {
	    	southwest = true;
	    	replantSpot.add(basePos.south().west());
	    }
	    
	    // Climb up the log blocks
	    while (true) {
	        state = client.world.getBlockState(cursor);
	        if (!state.getBlock().getTranslationKey().contains("log")) break;

	        logsToBreak.add(cursor);
	        if (north) logsToBreak.add(cursor.north());
	        if (south) logsToBreak.add(cursor.south());
	        if (east) logsToBreak.add(cursor.east());
	        if (west) logsToBreak.add(cursor.west());
	        if (northeast) logsToBreak.add(cursor.north().east());
	        if (northwest) logsToBreak.add(cursor.north().west());
	        if (southeast) logsToBreak.add(cursor.north().east());
	        if (southwest) logsToBreak.add(cursor.north().west());
	        
	        cursor = cursor.up();
	    }
	}
	

    public void treeTick() {
    	if (isChoppingTree && !logsToBreak.isEmpty()) {
    	    BlockPos next = logsToBreak.peek();
    	    BlockState state = client.world.getBlockState(next);
    	    if (!(state.isAir() || state.isLiquid())) {
    	        // Use tool if needed
    	        int axeSlot = getHighestPowerSlot("_axe");
    	        if (axeSlot != -1) {
    	            if (axeSlot < 9) {
        	        	client.player.getInventory().setSelectedSlot(axeSlot);
    	            } else {
    	                swapSlots(axeSlot, 2);
    	                client.player.getInventory().setSelectedSlot(2);
    	            }
    	        }

    	        // Attack the block
    	        client.interactionManager.attackBlock(next, Direction.UP);
    	        client.player.swingHand(Hand.MAIN_HAND);
    	    } else {
    	        logsToBreak.poll(); // remove from queue
    	    }

    	    // Done chopping
    	    if (logsToBreak.isEmpty()) {
    	        isChoppingTree = false;
    	        if (replantSpot.size() > 0) {
	    	        delayedTask(() -> {
		    	        plantSaplingAt(replantSpot.get(0));
				    }, 10);
    	        }
    	        if (replantSpot.size() > 1) {
	    	        delayedTask(() -> {
		    	        plantSaplingAt(replantSpot.get(1));
				    }, 10);
    	        }
    	        if (replantSpot.size() > 2) {
	    	        delayedTask(() -> {
		    	        plantSaplingAt(replantSpot.get(2));
				    }, 10);
    	        }
    	        if (replantSpot.size() > 3) {
	    	        delayedTask(() -> {
		    	        plantSaplingAt(replantSpot.get(3));
				    }, 10);
    	        }
    	    }
    	}
    }
	
	public boolean breakBlockUnderCrosshair() {
	    if (!(client.crosshairTarget instanceof BlockHitResult hit)) return false;
	    if (client.player == null || client.interactionManager == null || client.world == null) return false;

	    BlockPos pos = hit.getBlockPos();
	    BlockState state = client.world.getBlockState(pos);
	    Block block = state.getBlock();

	    // Determine tool type
	    String toolType = "";
	    if (block.getDefaultState().isIn(BlockTags.PICKAXE_MINEABLE)) {
	        toolType = "pickaxe";
	    } else if (block.getDefaultState().isIn(BlockTags.AXE_MINEABLE)) {
	        toolType = "_axe";
	    } else if (block.getDefaultState().isIn(BlockTags.SHOVEL_MINEABLE)) {
	        toolType = "shovel";
	    } else if (block.getDefaultState().isIn(BlockTags.HOE_MINEABLE)) {
	        toolType = "hoe";
	    }

	    // Equip tool
	    if (!toolType.isEmpty()) {
	        int toolSlot = getHighestPowerSlot(toolType);
	        if (toolSlot != -1 && toolSlot != client.player.getInventory().getSelectedSlot()) {
	            if (toolSlot < 9) {
	                client.player.getInventory().setSelectedSlot(toolSlot);
	            } else {
	                swapSlots(toolSlot, 2);
	                client.player.getInventory().setSelectedSlot(2);
	            }
	        }
	    }

	    // Break block
	    client.interactionManager.attackBlock(pos, hit.getSide());
	    client.player.swingHand(Hand.MAIN_HAND);
	    breakingBlock = pos;
	    breakingDirection = hit.getSide();

	    return true;
	}

	
	public boolean openVillagerTradeByUuid(UUID villagerId) {
	    if (client.world == null || client.player == null) return false;

	    Entity entity = client.world.getEntity(villagerId);
	    if (entity instanceof VillagerEntity villager) {
	        return openVillagerTrade(villager);
	    }
	    return false;
	}

	public boolean openVillagerTradeAtPos(BlockPos pos) {
	    if (client.world == null || client.player == null) return false;

	    double rangeSq = 5 * 5;
	    for (Entity entity : client.world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(1), v -> true)) {
	        if (entity.squaredDistanceTo(client.player) <= rangeSq) {
	            return openVillagerTrade((VillagerEntity) entity);
	        }
	    }
	    return false;
	}
	
	private boolean openVillagerTrade(VillagerEntity villager) {
	    if (client.interactionManager == null || client.player == null) return false;

	    // Must be close enough
	    if (villager.squaredDistanceTo(client.player) > 25) return false;

	    client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
	    client.player.swingHand(Hand.MAIN_HAND);
	    return true;
	}
	
	public String performTrade(String matchOutput, String matchInput, int matchIndex) {
	    if (!(client.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
	        return "No trade screen open!";
	    }

	    List<TradeOffer> offers = handler.getRecipes();

	    for (int i = 0; i < offers.size(); i++) {
	        TradeOffer offer = offers.get(i);
	        ItemStack sell = offer.getSellItem();
	        ItemStack buyA = offer.getOriginalFirstBuyItem();
	        Optional<TradedItem> buyB = offer.getSecondBuyItem();

	        boolean matches = false;

	        if (matchOutput != null && sell.getName().getString().toLowerCase().contains(matchOutput.toLowerCase())) {
	            matches = true;
	        }

	        if (matchInput != null &&
	            (buyA.getName().getString().toLowerCase().contains(matchInput.toLowerCase()) ||
	             (buyB.isPresent() && buyB.get().itemStack().getName().getString().toLowerCase().contains(matchInput.toLowerCase())))) {
	            matches = true;
	        }

	        if (matchIndex >= 0 && i == matchIndex) {
	            matches = true;
	        }

	        if (matches) {
	            selectTrade(i);
	            completeTradeOutputClick();  // ⬅️ Perform the click to get the item
	            return "Selected and performed trade index: " + i;
	        }
	    }

	    return "No matching trade found.";
	}

	private void completeTradeOutputClick() {
	    if (!(client.player.currentScreenHandler instanceof MerchantScreenHandler handler)) return;

	    // The output slot is always index 2 in MerchantScreenHandler
	    int outputSlot = 2;

	    client.interactionManager.clickSlot(
	        handler.syncId,
	        outputSlot,
	        0,
	        SlotActionType.QUICK_MOVE,  // QUICK_MOVE simulates shift-clicking the result into your inventory
	        client.player
	    );
	}
	
	private void selectTrade(int index) {
	    if (!(client.player.currentScreenHandler instanceof MerchantScreenHandler handler)) return;

	    client.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket(index));
	}

	private static final Set<Item> FOOD_ITEMS = Set.of(
		    Items.APPLE,
		    Items.BREAD,
		    Items.COOKED_BEEF,
		    Items.COOKED_CHICKEN,
		    Items.COOKED_COD,
		    Items.COOKED_MUTTON,
		    Items.COOKED_PORKCHOP,
		    Items.COOKED_RABBIT,
		    Items.COOKED_SALMON,
		    Items.GOLDEN_APPLE,
		    Items.ENCHANTED_GOLDEN_APPLE,
		    Items.BAKED_POTATO,
		    Items.CARROT,
		    Items.GOLDEN_CARROT,
		    Items.BEEF,
		    Items.SUSPICIOUS_STEW,
		    Items.MUSHROOM_STEW,
		    Items.BEETROOT_SOUP,
		    Items.POTATO,
		    Items.COOKIE,
		    Items.MELON_SLICE,
		    Items.PUMPKIN_PIE,
		    Items.ROTTEN_FLESH,
		    Items.CHICKEN,
		    Items.COD,
		    Items.SALMON,
		    Items.RABBIT,
		    Items.PORKCHOP,
		    Items.MUTTON,
		    Items.DRIED_KELP
		    // Add more as needed
		);
	
	public boolean isFood(ItemStack stack) {
	    return FOOD_ITEMS.contains(stack.getItem());
	}
	
	public boolean eatUntilFull() {
	    if (client.player == null || client.interactionManager == null || client.world == null) return false;

	    // Stop if already full
	    if (client.player.getHungerManager().getFoodLevel() > 19) {
	        return false;
	    }

	    // Step 1: Find best food item (by stack size)
	    int bestSlot = -1;
	    int maxCount = 0;

	    for (int i = 0; i < client.player.getInventory().size(); i++) {
	        ItemStack stack = client.player.getInventory().getStack(i);
	        if (isFood(stack)) {
	            if (stack.getCount() > maxCount) {
	                maxCount = stack.getCount();
	                bestSlot = i;
	            }
	        }
	    }

	    if (bestSlot == -1) {
	        System.out.println("No food found in inventory.");
	        return false;
	    }

	    int hotbarSlot = 7; // You can choose any hotbar slot you want to use
	    if (bestSlot >= 9) {
	        swapSlots(bestSlot, hotbarSlot);
	    } else {
	        hotbarSlot = bestSlot;
	    }

	    client.player.getInventory().setSelectedSlot(hotbarSlot);

	    // Step 2: Eat until full
	    Runnable startEating = new Runnable() {
	        @Override
	        public void run() {
	            if (client.player == null || client.interactionManager == null) return;
	            if (client.player.getHungerManager().getFoodLevel() > 19) return;

	            ItemStack food = client.player.getMainHandStack();
	            if (!isFood(food) || food.isEmpty()) return;

	            // Start eating
	            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

	            // Schedule stop using item after 32 ticks (1.6s, enough for most foods)
	            delayedTask(() -> {
	                if (client.player != null && client.player.isUsingItem()) {
	                    client.interactionManager.stopUsingItem(client.player);

	                    // Schedule another bite if still hungry and food remains
	                    delayedTask(this, 5); // delay before next bite
	                }
	            }, 32);
	        }
	    };

	    delayedTask(startEating, 2);
	    
	    return true;
	}
	
	public boolean tryToHeal() {
	    if (client.player == null || client.interactionManager == null || client.world == null) return false;

	    float currentHealth = client.player.getHealth();
	    float maxHealth = client.player.getMaxHealth();
	    if (currentHealth >= maxHealth) return false;

	    int hotbarSlot = 6; // Slot to use for healing item

	    int healSlot = -1;
	    Item healItem = null;

	    // Priority list of healing items
	    List<Item> healingItems = List.of(
	        Items.ENCHANTED_GOLDEN_APPLE,
	        Items.GOLDEN_APPLE,
		    Items.POTION,
	        Items.SUSPICIOUS_STEW,
	        Items.MUSHROOM_STEW,
	        Items.RABBIT_STEW,
	        Items.BEETROOT_SOUP
	    );

	    // Step 1: Find healing item in inventory
	    for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
            	for (Item item : healingItems) {
        	        if (stack.getItem() == item) {
        	        	if (item == Items.POTION && !(stack.getName().getString().toLowerCase().contains("heal") || stack.getName().getString().toLowerCase().contains("regen"))) {
                            continue; // Skip potions that don't heal
                        }

                        healSlot = i;
                        healItem = item;
                        break;
        	        }
        	    }
            }
        }
	    

	    if (healSlot == -1) {
	        System.out.println("No healing item found.");
	        return false;
	    }

	    // Step 2: Swap to hotbar if needed
	    if (healSlot >= 9) {
	        swapSlots(healSlot, hotbarSlot);
	    } else {
	        hotbarSlot = healSlot;
	    }

	    client.player.getInventory().setSelectedSlot(hotbarSlot);

	    // Step 3: Use item
	    Runnable useItem = () -> {
	        if (client.player.getMainHandStack().isEmpty()) return;

	        // Begin using
	        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

	        // Stop using after 32 ticks (~1.6 seconds)
	        delayedTask(() -> {
	            if (client.player != null && client.player.isUsingItem()) {
	                client.interactionManager.stopUsingItem(client.player);
	            }
	        }, 32);
	    };

	    delayedTask(useItem, 2); // Slight delay to allow the swap
	    return true;
	}
	
	public JsonObject getOnlinePlayers() {
	    JsonObject response = new JsonObject();
	    JsonArray playersArray = new JsonArray();

	    ClientWorld world = client.world;
	    ClientPlayerEntity localPlayer = client.player;

	    if (world == null || localPlayer == null) {
	        response.addProperty("error", "Player or world not available");
	        return response;
	    }
	    
	    Map<String, Double[]> trackerBar = null;
        
        if (GameQueryModClient.carlsFaceBar) {
    		try {
        		//java.lang.reflect.Method method = GameQueryModClient.locatorBarDataClass.getMethod("getAllWaypointsData");
        		trackerBar = LocatorBarData.getAllWaypointsData();
    		} catch (Exception e) {
    			response.addProperty("error", e.getMessage());
    		}
    	}

	    Map<UUID, PlayerEntity> visiblePlayers = new HashMap<>();
	    for (PlayerEntity p : world.getPlayers()) {
	        visiblePlayers.put(p.getUuid(), p);
	    }

	    for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
	        JsonObject playerData = new JsonObject();

	        UUID uuid = entry.getProfile().getId();
	        String name = entry.getProfile().getName();
	        playerData.addProperty("uuid", uuid.toString());
	        playerData.addProperty("name", name);
	        
	        if (visiblePlayers.containsKey(uuid)) {
	            PlayerEntity entity = visiblePlayers.get(uuid);

	            // Position and Stats
	            playerData.addProperty("x", entity.getX());
	            playerData.addProperty("y", entity.getY());
	            playerData.addProperty("z", entity.getZ());
	            playerData.addProperty("health", entity.getHealth());
	            playerData.addProperty("food", entity.getHungerManager().getFoodLevel());
	            playerData.addProperty("level", entity.experienceLevel);
	            playerData.addProperty("inRenderDistance", true);

	            // Direction calculation
	            double dx = entity.getX() - localPlayer.getX();
	            double dy = (entity.getY() + entity.getStandingEyeHeight()) - localPlayer.getEyeY();
	            double dz = entity.getZ() - localPlayer.getZ();

	            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
	            double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	            double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

	            // Normalize angles
	            yaw = (yaw + 360.0) % 360.0;
	            pitch = Math.max(-90.0, Math.min(90.0, pitch));

	            playerData.addProperty("direction_yaw", Math.round(yaw * 100.0) / 100.0);
	            playerData.addProperty("direction_pitch", Math.round(pitch * 100.0) / 100.0);
	            playerData.addProperty("distance", Math.round(distance * 100.0) / 100.0);
	        } else {
	            // Not visible (out of render distance)
	        	// Use locator bar if possible
	        	
	        	if (trackerBar != null) {
	        		try {
		        		if (trackerBar.containsKey(uuid.toString())) {
		        			Double[] trackerData = trackerBar.get(uuid.toString());
		        			double active = trackerData[0];
		        			double yawAngle = trackerData[1];
		        			double distance = trackerData[2];
		        			double approx_x = trackerData[3];
		        			double approx_z = trackerData[4];
		        			playerData.addProperty("active", active > 0.1);
		        			playerData.addProperty("direction_yaw", yawAngle);
		    	            playerData.addProperty("distance", Math.round(distance * 100.0) / 100.0);
		    	            playerData.addProperty("x", approx_x);
		    	            playerData.addProperty("z", approx_z);
		        		}
	        		} catch (Exception e) {
	        			playerData.addProperty("error", e.getMessage());
	        		}
	        	}
	        	
	            playerData.addProperty("inRenderDistance", false);
	            //playerData.addProperty("direction", "unknown");
	        }

	        playersArray.add(playerData);
	    }

	    response.add("players", playersArray);
	    return response;
	}


	
	public JsonElement useBed(int x, int y, int z) {
	    JsonObject result = new JsonObject();
	    BlockPos bedPos = new BlockPos(x, y, z);
	    ClientPlayerEntity player = client.player;
	    ClientWorld world = client.world;

	    if (player == null || world == null) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Player or world not available");
	        return result;
	    }

	    if (!(world.getBlockState(bedPos).getBlock() instanceof BedBlock)) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Block at given position is not a bed");
	        return result;
	    }

	    // Check if it's night or thunderstorm (optional, Minecraft handles this itself)
	    if (!world.isNight() && !world.isThundering()) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Cannot sleep now (not night or thunderstorm)");
	        return result;
	    }

	    try {
	        // Send interaction packet to simulate right-clicking the bed
	    	client.interactionManager.interactBlock(
	    		    player,
	    		    net.minecraft.util.Hand.MAIN_HAND,
	    		    new net.minecraft.util.hit.BlockHitResult(
	    		        player.getPos(),
	    		        net.minecraft.util.math.Direction.UP,
	    		        bedPos,
	    		        false
	    		    )
	    		);

	        result.addProperty("success", true);
	        result.addProperty("message", "Sent bed use interaction to server at " + bedPos);
	    } catch (Exception e) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Failed to send use_bed interaction: " + e.getMessage());
	    }

	    return result;
	}


	public JsonElement leaveBed() {
	    JsonObject result = new JsonObject();
	    ClientPlayerEntity player = client.player;

	    if (player != null) {
	        if (player.isSleeping()) {
	            player.wakeUp(false, true); // wakeUp(immediately, updateSleepingPlayers)
	            result.addProperty("success", true);
	            result.addProperty("message", "Player left the bed.");
	        } else {
	            result.addProperty("success", false);
	            result.addProperty("error", "Player is not currently sleeping.");
	        }
	    } else {
	        result.addProperty("success", false);
	        result.addProperty("error", "Player not available");
	    }

	    return result;
	}
	
	public JsonElement toggleDoor(int x, int y, int z, boolean target_state, boolean ignore_target_state) {
	    JsonObject result = new JsonObject();
	    BlockPos doorPos = new BlockPos(x, y, z);
	    ClientPlayerEntity player = client.player;
	    ClientWorld world = client.world;

	    if (player == null || world == null) {
	        result.addProperty("error", "Player or world not available");
	        return result;
	    }

	    BlockState state = world.getBlockState(doorPos);

	    // Ensure it's the bottom half of the door
	    if (!(state.getBlock() instanceof DoorBlock)) {
	        result.addProperty("error", "Block at position is not a door");
	        return result;
	    }

	    if (state.get(DoorBlock.HALF) != net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
	        // Adjust to bottom half
	        doorPos = doorPos.down();
	        state = world.getBlockState(doorPos);
	        if (!(state.getBlock() instanceof DoorBlock)) {
	            result.addProperty("error", "Adjusted block is not a door");
	            return result;
	        }
	    }

	    boolean currentState = state.get(DoorBlock.OPEN);
	    boolean desiredState = !currentState;

	    if (!ignore_target_state) {
	        desiredState = target_state;
	    }

	    if (currentState == desiredState && !ignore_target_state) {
	        result.addProperty("result", "Door already " + (currentState ? "open" : "closed"));
	        return result;
	    }

	    try {
	        // Properly simulate block interaction at door center
	        client.interactionManager.interactBlock(
	            player,
	            Hand.MAIN_HAND,
	            new BlockHitResult(
	                Vec3d.ofCenter(doorPos),  // Precise hit on block center
	                Direction.UP,             // General face
	                doorPos,
	                false
	            )
	        );

	        result.addProperty("success", true);
	        result.addProperty("result", (desiredState ? "Opened" : "Closed") + " the door at " + doorPos.toShortString());
	    } catch (Exception e) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Exception while toggling door: " + e.getMessage());
	    }

	    return result;
	}


	
	double getBowPitch(double range) {
		if (range <= 24)
			return 0;
		if (range >= 118)
			return 40;
        double a = -0.431995;
        double n = 0.883404;
        double b = (-1.39985)*Math.pow(10, -22);
        double m = 11.15095;
        double c = 6.72811;
        return a*Math.pow(range, n) + b*Math.pow(range, m) + c;
	}
	
	public Map<UUID, float[]> histPos = new HashMap<>();
	float bowLead = 1.7f;
	
	public float[] getMovingTarget(LivingEntity target, float overshoot) {
		float[] result = {0, 0, 0};
		
		float distance = (float)client.player.getPos().distanceTo(target.getPos());
		float time = distance/52f; // time in seconds
		
		Vec3d prevPos = null;
		float prevPosTimestamp = -1;
		if (histPos.containsKey(target.getUuid())) {
			prevPosTimestamp = histPos.get(target.getUuid())[0];
			prevPos = new Vec3d(histPos.get(target.getUuid())[1], histPos.get(target.getUuid())[2], histPos.get(target.getUuid())[3]);
		}
		
		float timestamp = currentTick;
		
		histPos.put(target.getUuid(), new float[] {timestamp, (float)target.getPos().getX(), (float)target.getPos().getY(), (float)target.getPos().getZ()});
		
		double dx = 0;
		double dy = 0;
		double dz = 0;
		
		if (prevPos != null && timestamp - prevPosTimestamp < 2*20) {
			float timeDiff = (timestamp - prevPosTimestamp)/20;
			
			
			dx = bowLead*time*(target.getPos().getX() - prevPos.x)/timeDiff;
			dy = 0.4*bowLead*time*(target.getPos().getY() - prevPos.y)/timeDiff;
			dz = bowLead*time*(target.getPos().getZ() - prevPos.z)/timeDiff;

			if (chatDebug) sendChatMessage("timestamp: " + timestamp + " timediff: " + timeDiff + " v: " + dx + " " + dy + " " + dz);
		}
		
		result[0] = (float)(target.getX() + dx);
		result[1] = (float)(target.getY() + dy);
		result[2] = (float)(target.getZ() + dz);
		
		return result;
	}
	
	boolean chatDebug = false;
	boolean usingBow = false;
	
	public JsonElement shootBowAt(LivingEntity target, float overshoot) {
		usingBow = true;
		JsonObject result = new JsonObject();
	    ClientPlayerEntity player = client.player;

	    // Step 1: Find bow and switch to that slot
	    int bowSlot = -1;
	    bowSlot = getHighestPowerSlot("bow");
	    if (bowSlot == -1) {
	        System.out.println("No bow found in inventory.");
	        if (chatDebug) sendChatMessage("No bow found in inventory");
	        result.addProperty("error", "no bow found in inventory");
	        return result;
	    } else {
	    	if (bowSlot <= 8) {
	    		player.getInventory().setSelectedSlot(bowSlot);
	    	} else {
	    		player.getInventory().setSelectedSlot(1);
	    		swapSlots(bowSlot, 1);
	    	}
	    }

        result.addProperty("bow_slot", bowSlot);
	    int finalBowSlot = bowSlot;
	    
	    ItemStack heldItem = player.getMainHandStack();
	    boolean isCrossbow = heldItem.getItem().toString().toLowerCase().contains("crossbow");
	    
	    client.execute(() -> {
	        try {
	            player.getInventory().setSelectedSlot(finalBowSlot);

	            float[] pos = getMovingTarget(target, overshoot);
	            // Step 2: Aim at target
	            JsonObject rotationResult = pointToXYZ(pos[0], (float)(pos[1]+target.getHeight()), pos[2]);
	            if (!rotationResult.get("success").getAsBoolean()) {
	                System.out.println("Failed to rotate toward target.");
	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
	    	        result.addProperty("error", "failed to rotate player");
	    	        usingBow = false;
	                return;
	            }
	            
	            rotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot + Math.sqrt(Math.pow(player.getX()-pos[0], 2) + Math.pow(player.getZ()-pos[2], 2)))));
	            if (!rotationResult.get("success").getAsBoolean()) {
	                System.out.println("Failed to rotate toward target.");
	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
	    	        result.addProperty("error", "failed to rotate player");
	    	        usingBow = false;
	                return;
	            }
	            
	            if (!isCrossbow) {
	                // Bow logic (unchanged)
		            // Step 3: Start drawing the bow
		            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
	
		            // Continue aiming
		            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
		                client.execute(() -> {
		                	float[] newPos = getMovingTarget(target, overshoot);
		    	            // Step 2: Aim at target
		    	            JsonObject newRotationResult = pointToXYZ(newPos[0], (float)(newPos[1]+target.getHeight()), newPos[2]);
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		    	            
		    	            newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot + Math.sqrt(Math.pow(player.getX()-newPos[0], 2) + Math.pow(player.getZ()-newPos[2], 2)))));
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		                });
		            }, 900, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
		            
		            // Continue aiming
		            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
		                client.execute(() -> {
		                	float[] newPos = getMovingTarget(target, overshoot);
		    	            // Step 2: Aim at target
		    	            JsonObject newRotationResult = pointToXYZ(newPos[0], (float)(newPos[1]+target.getHeight()), newPos[2]);
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		    	            
		    	            newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot + Math.sqrt(Math.pow(player.getX()-newPos[0], 2) + Math.pow(player.getZ()-newPos[2], 2)))));
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		                });
		            }, 1050, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
		            
		            // Step 4 + 5: Wait and release
		            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
		                client.execute(() -> {
		                	JsonObject newRotationResult = pointToXYZ((float)target.getX(), (float)(target.getY()+target.getHeight()), (float)target.getZ());
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		    	            
		    	            newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot+Math.sqrt(Math.pow(player.getX()-target.getX(), 2) + Math.pow(player.getZ()-target.getZ(), 2)))));
		    	            if (!newRotationResult.get("success").getAsBoolean()) {
		    	                System.out.println("Failed to rotate toward target.");
		    	                if (chatDebug) sendChatMessage("Failed to rotate toward target");
		    	    	        result.addProperty("error", "failed to rotate player");
		    	    	        usingBow = false;
		    	                return;
		    	            }
		                    client.interactionManager.stopUsingItem(player);
			    	        result.addProperty("success", "arrow has been launched");
			    	        if (chatDebug) sendChatMessage("Arrow has been launched");
		                });
		            }, 1150, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
	            } else {
	            	// Crossbow logic
	                client.interactionManager.interactItem(player, Hand.MAIN_HAND); // Start charging

	                Executors.newSingleThreadScheduledExecutor().schedule(() -> {
	                    client.execute(() -> {
	                        // Aim again
	                        float[] aimPos = getMovingTarget(target, overshoot);
	                        JsonObject newRotationResult = pointToXYZ(aimPos[0], (float)(aimPos[1] + target.getHeight()), aimPos[2]);
	                        if (!newRotationResult.get("success").getAsBoolean()) {
	                        	if (chatDebug) sendChatMessage("Failed to rotate toward target");
	                            result.addProperty("error", "failed to rotate player");
	                            usingBow = false;
	                            return;
	                        }

	                        newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot + Math.sqrt(Math.pow(player.getX()-aimPos[0], 2) + Math.pow(player.getZ()-aimPos[2], 2)))));
	                        if (!newRotationResult.get("success").getAsBoolean()) {
	                            result.addProperty("error", "failed to rotate player");
	                            if (chatDebug) sendChatMessage("Failed to rotate toward target");
	                            usingBow = false;
	                            return;
	                        }

	                        // Fire the crossbow
	                        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
	                        result.addProperty("success", "arrow has been launched (crossbow)");
	                        if (chatDebug) sendChatMessage("Crossbow arrow has been launched");
	            	        usingBow = false;
	                    });
	                }, 1300, TimeUnit.MILLISECONDS); // Crossbow full charge
	            }
	        } catch (Exception e) {
	            e.printStackTrace();
	            if (chatDebug) sendChatMessage("Error: " + e.getMessage());
	        }
	    });
	    return result;
	}
	
	public JsonElement shootBowAt(float targetX, float targetY, float targetZ, float overshoot) {
		usingBow = true;
		JsonObject result = new JsonObject();
	    ClientPlayerEntity player = client.player;

	    // Step 1: Find bow and switch to that slot
	    int bowSlot = -1;
	    for (int i = 0; i < player.getInventory().size(); i++) {
	        ItemStack stack = player.getInventory().getStack(i);
	        if (stack.getItem() instanceof BowItem) {
	            bowSlot = i;
	            break;
	        }
	    }
	    if (bowSlot == -1) {
	        System.out.println("No bow found in inventory.");
	        result.addProperty("error", "no bow found in inventory");
	        usingBow = false;
	        return result;
	    }

        result.addProperty("bow_slot", bowSlot);
	    int finalBowSlot = bowSlot;
	    
	    client.execute(() -> {
	        try {
	            player.getInventory().setSelectedSlot(finalBowSlot);

	            // Step 2: Aim at target
	            JsonObject rotationResult = pointToXYZ(targetX, targetY, targetZ);
	            if (!rotationResult.get("success").getAsBoolean()) {
	                System.out.println("Failed to rotate toward target.");
	    	        result.addProperty("error", "failed to rotate player");
	    	        usingBow = false;
	                return;
	            }
	            
	            rotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot+Math.sqrt(Math.pow(player.getX()-targetX, 2) + Math.pow(player.getZ()-targetZ, 2)))));
	            if (!rotationResult.get("success").getAsBoolean()) {
	                System.out.println("Failed to rotate toward target.");
	    	        result.addProperty("error", "failed to rotate player");
	    	        usingBow = false;
	                return;
	            }
	            
	            
	            // Step 3: Start drawing the bow
	            client.interactionManager.interactItem(player, Hand.MAIN_HAND);

	            // Continue aiming
	            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
	                client.execute(() -> {
	                	JsonObject newRotationResult = pointToXYZ(targetX, targetY, targetZ);
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                	newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot+Math.sqrt(Math.pow(player.getX()-targetX, 2) + Math.pow(player.getZ()-targetZ, 2)))));
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                });
	            }, 900, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
	            
	            // Continue aiming
	            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
	                client.execute(() -> {
	                	JsonObject newRotationResult = pointToXYZ(targetX, targetY, targetZ);
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                	newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot+Math.sqrt(Math.pow(player.getX()-targetX, 2) + Math.pow(player.getZ()-targetZ, 2)))));
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                });
	            }, 1200, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
	            
	            // Step 4 + 5: Wait and release
	            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
	                client.execute(() -> {
	                	JsonObject newRotationResult = pointToXYZ(targetX, targetY, targetZ);
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                	newRotationResult = rotatePlayer(player.getYaw(), (float)(player.getPitch() + getBowPitch(overshoot+Math.sqrt(Math.pow(player.getX()-targetX, 2) + Math.pow(player.getZ()-targetZ, 2)))));
	    	            if (!newRotationResult.get("success").getAsBoolean()) {
	    	                System.out.println("Failed to rotate toward target.");
	    	    	        result.addProperty("error", "failed to rotate player");
	    	    	        usingBow = false;
	    	                return;
	    	            }
	                    client.interactionManager.stopUsingItem(player);
		    	        result.addProperty("success", "arrow has been launched");
		    	        usingBow = false;
	                });
	            }, 1300, TimeUnit.MILLISECONDS); // wait 1 second (full charge)
	        } catch (Exception e) {
	            e.printStackTrace();
	        }
	    });
	    return result;
	}
	
	boolean isHotbar(int slot) {
		return slot < 9;
	}
	
	private boolean swapSlots(int slot1, int slot2) {
	    if (client.player == null || client.interactionManager == null) return false;

	    ScreenHandler handler = client.player.currentScreenHandler;

	    // Validate slot indices
	    if (slot1 < 0 || slot2 < 0 || slot1 >= handler.slots.size() || slot2 >= handler.slots.size()) {
	        return false;
	    }
	    

	    boolean involvesOffhand = slot1 == 40 || slot2 == 40;
	    
	    if (involvesOffhand && (isHotbar(slot1) || isHotbar(slot2))) {
	    	int prevSelectedSlot = client.player.getInventory().getSelectedSlot();
	    	client.player.getInventory().setSelectedSlot(isHotbar(slot1) ? slot1 : slot2);
	    	delayedTask(() -> {
	    		client.getNetworkHandler().sendPacket(
		    	        new PlayerActionC2SPacket(
		    	            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
		    	            client.player.getBlockPos(),  // BlockPos is ignored for this action
		    	            client.player.getHorizontalFacing()
		    	        )
		    	    );
		    }, 5); // run 10 ticks later
	    	
	    	delayedTask(() -> {
		        client.player.getInventory().setSelectedSlot(prevSelectedSlot);
		    }, 10); // run 10 ticks later
	        return true;
	    }
	    Screen prevScreen = client.currentScreen;
	    
	    client.setScreen(new InventoryScreen(client.player));
	    

	    if (isHotbar(slot1) && !isHotbar(slot2)) {
	    	
	    	client.interactionManager.clickSlot(
	    	        client.player.currentScreenHandler.syncId,
	    	        slot2,
	    	        slot1,
	    	        SlotActionType.SWAP,
	    	        client.player
	    	    );
	    } else if (isHotbar(slot2) && !isHotbar(slot1)) {
	    	
		    	client.interactionManager.clickSlot(
		    	        client.player.currentScreenHandler.syncId,
		    	        slot1,
		    	        slot2,
		    	        SlotActionType.SWAP,
		    	        client.player
		    	    );
	    } else {
	
		    // Click slot1 to pick up the item
		    client.interactionManager.clickSlot(
		        handler.syncId,
		        slot1,
		        0, // button 0 (left click)
		        SlotActionType.PICKUP,
		        client.player
		    );
	
		    // Click slot2 to swap
		    client.interactionManager.clickSlot(
		        handler.syncId,
		        slot2,
		        0,
		        SlotActionType.PICKUP,
		        client.player
		    );
	
		    // Put the originally picked item back into slot1
		    client.interactionManager.clickSlot(
		        handler.syncId,
		        slot1,
		        0,
		        SlotActionType.PICKUP,
		        client.player
		    );
	    }
	    
	    
	    delayedTask(() -> {
	    	client.execute(() -> client.setScreen(prevScreen));
	    }, 1); // run 1 tick1 later
	    

    	//client.execute(() -> client.setScreen(prevScreen));

	    return true;
	}
	
	Map<Runnable, Integer> delayedTasks = new HashMap<>();
	
	private void delayedTask(Runnable task, int delay) {
		
		delayedTasks.put(task, delay);

	}

	boolean isType(ItemStack stack, String type) {
	    if (stack == null || stack.isEmpty()) return false;

	    Item item = stack.getItem();
	    String itemName = item.getTranslationKey().toLowerCase();
	    type = type.toLowerCase();

	    // Bows & Arrows
	    if (type.contains("bow") && item == Items.BOW) return true;
	    if (type.contains("crossbow") && item == Items.CROSSBOW) return true;
	    if (type.contains("arrow") && (
	        item == Items.ARROW ||
	        item == Items.TIPPED_ARROW ||
	        item == Items.SPECTRAL_ARROW)) return true;

	    // Swords, Axes, Pickaxes, Shovels, Hoes
	    if (type.contains("sword") && itemName.contains("sword")) return true;
	    if (type.contains("axe") && itemName.contains("axe") && !itemName.contains("pickaxe")) return true;
	    if (type.contains("pickaxe") && itemName.contains("pickaxe")) return true;
	    if (type.contains("shovel") && itemName.contains("shovel")) return true;
	    if (type.contains("hoe") && itemName.contains("hoe")) return true;

	    // Armor
	    if (type.contains("helmet") && itemName.contains("helmet")) return true;
	    if (type.contains("chestplate") && itemName.contains("chestplate")) return true;
	    if (type.contains("leggings") && itemName.contains("leggings")) return true;
	    if (type.contains("boots") && itemName.contains("boots")) return true;

	    // Generic fallback (optional)
	    if (itemName.contains(type)) return true;

	    return false;
	}

	
	private int getHighestPowerSlot(String type) {
		float power = -1;
		int slot = -1;
		//ItemStack targetStack = null;
		type = type.toLowerCase();
		
		for (int i = 0; i < client.player.getInventory().size(); i++) {
			ItemStack stack = client.player.getInventory().getStack(i);
			if (!stack.isEmpty() && isType(stack, type)) {
				if (chatDebug) sendChatMessage("Found a " + type);
				float currentPower = 0;
				String item = stack.getItem().toString().toLowerCase();
				if (item.contains("wood")) {
					currentPower = 1;
				} else if (item.contains("stone")) {
					currentPower = 2;
				} else if (item.contains("gold") || item.contains("chainmail")) {
					currentPower = 3;
				} else if (item.contains("iron")) {
					currentPower = 4;
				} else if (item.contains("diamond")) {
					currentPower = 5;
				}
				if (stack.hasEnchantments()) {
					currentPower += 0.2 * stack.getEnchantments().getSize();
				}
				if (currentPower > power) {
					slot = i;
					power = currentPower;
					//targetStack = stack;
				}
			}
		}
		return slot;
	}
	
	private JsonElement openContainerUnderCrosshair() {
		JsonObject result = new JsonObject();
		try {
			if (client.crosshairTarget instanceof BlockHitResult hit) {
				ActionResult action = client.interactionManager.interactBlock(
					client.player, client.player.getActiveHand(), hit);
				client.player.swingHand(client.player.getActiveHand());

				result.addProperty("success", true);
				result.addProperty("message", "Interacted with block at " + hit.getBlockPos() + " (result: " + action + ")");
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "Not pointing at a block");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to open container: " + e.getMessage());
		}
		return result;
	}
	
	private JsonElement openTableUnderCrosshair() {
	    JsonObject result = new JsonObject();

	    try {
	        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
	            result.addProperty("success", false);
	            result.addProperty("error", "Not pointing at a block");
	            return result;
	        }

	        BlockPos pos = hit.getBlockPos();
	        BlockState state = client.world.getBlockState(pos);
	        Block block = state.getBlock();

	        if (block != Blocks.CRAFTING_TABLE) {
	            result.addProperty("success", false);
	            result.addProperty("error", "Not pointing at a crafting table (pointing at: " + block.toString() + ")");
	            return result;
	        }

	        // Ensure player is close enough
	        if (client.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 6 * 6) {
	            result.addProperty("success", false);
	            result.addProperty("error", "Too far away from crafting table");
	            return result;
	        }

	        // Send interaction packet to server
	        client.interactionManager.interactBlock(
	            client.player,
	            Hand.MAIN_HAND,
	            hit
	        );

	        client.player.swingHand(Hand.MAIN_HAND);

	        result.addProperty("success", true);
	        result.addProperty("message", "Opened crafting table at " + pos);

	    } catch (Exception e) {
	        result.addProperty("success", false);
	        result.addProperty("error", "Exception: " + e.getMessage());
	    }

	    return result;
	}
	
	private boolean performAttack(Entity target) {
        if (target == null || client.player == null || client.interactionManager == null) return false;

        client.interactionManager.attackEntity(client.player, target);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
	
	public boolean defend = false;
	public boolean killauraEnabled = false;
	public boolean killauraBow = false;
	public boolean killauraBaritone = false;
    public List<UUID> killauraTargets = new ArrayList<>();
	
    boolean useCrits = true;

	public static UUID attacker = null;
    
    int c = 0;
    int hitCountDown = 0;
    int baritoneCooldown = 0;
    
    public boolean useShield(int ticks, LivingEntity target) {
        if (client.player == null || client.interactionManager == null) return false;

        ItemStack offhand = client.player.getOffHandStack();
        if (!(offhand.getItem() instanceof ShieldItem)) {
            if (chatDebug) sendChatMessage("Equipping Shield");
            offhand = equipShieldIfAvailable();
            if (!(offhand.getItem() instanceof ShieldItem)) {
                if (chatDebug) sendChatMessage("No shield in offhand");
                return false;
            }
        }

        if (!isOnCooldown(client.player, offhand)) {
            // Aim at target for realism
            if (target != null) {
                lookAtEntity(client.player, target);
                delayedTask(() -> lookAtEntity(client.player, target), (int)(ticks * 0.3));
                delayedTask(() -> lookAtEntity(client.player, target), (int)(ticks * 0.6));
            }

            // Actually block: server-side action
            client.player.setCurrentHand(Hand.OFF_HAND);
            client.interactionManager.interactItem(client.player, Hand.OFF_HAND);

            // Stop after `ticks`
            delayedTask(() -> {
                if (client.player.isUsingItem() && client.player.getActiveHand() == Hand.OFF_HAND) {
                    client.interactionManager.stopUsingItem(client.player);
                }
            }, ticks);

            return true;
        }

        return false;
    }

    int bowCooldown = 0;
    long currentTick = 0;
    int shieldCooldown = 0;
    int attackPhase = 0;
    
    private BlockPos breakingBlock = null;
    private Direction breakingDirection = null;
    
	public void tick() {
		currentTick++;
		treeTick();
		if (breakingBlock != null && breakingDirection != null) {
		    BlockState state = client.world.getBlockState(breakingBlock);
		    if (!(state.isAir() || state.isLiquid())) {
		        client.interactionManager.attackBlock(breakingBlock, breakingDirection);
		        client.player.swingHand(Hand.MAIN_HAND);
		    } else {
		        breakingBlock = null;
		        breakingDirection = null;
		    }
		}
		if (delayedTasks.size() > 0) {
			ArrayList<Runnable> remove = new ArrayList<>();
			for (Runnable task : delayedTasks.keySet()) {
				if (delayedTasks.get(task) == 0) {
					task.run();
					remove.add(task);
				} else {
					delayedTasks.put(task, delayedTasks.get(task)-1);
				}
			}
			for (Runnable task : remove) {
				delayedTasks.remove(task);
			}
		}
        if (!killauraEnabled || client.player == null || client.world == null) return;
        if (defend && attacker != null) {
        	killauraTargets.add(attacker);
        	attacker = null;
        }
        c++;
        if (c % 3 == 0) {
        	if (hitCountDown > 0) {
        		hitCountDown--;
        	} else {
        		useCrits = true;
        	}
        	if (bowCooldown > 0) {
        		bowCooldown--;
        		return;
        	} 
        	if (shieldCooldown > 0) {
        		shieldCooldown--;
        		if (!isBlockingWithShield(client.player))
        			shieldCooldown = 9;
        		if (shieldCooldown > 10)
        			return;
        	}
	        boolean hit = false;
	        ArrayList<UUID> remove = new ArrayList<>();
	        LivingEntity closestTarget = null;
	        double distance = -1;
	        boolean hasLineOfSight = false;
	        for (UUID uuid : killauraTargets) {
	            Entity target = client.world.getEntity(uuid);
	            if (target != null && target.isAlive() && target instanceof LivingEntity le) {
	            	boolean hasLineOfSightTemp = lineOfSight(target);
		            if (hasLineOfSightTemp || closestTarget == null) {
		            	double distanceSq = client.player.squaredDistanceTo(le);
		                if (closestTarget == null || distanceSq < distance) {
		                	distance = distanceSq;
		                	closestTarget = le;
		                	hasLineOfSight = hasLineOfSightTemp;
		                }
		            }
	            } else {
	            	remove.add(uuid);
	            }
	        }
	        if (closestTarget != null) {
	        	if (distance < 9*9 || !killauraBow) {
	        		if (chatDebug) sendChatMessage("Going in for the melee");
	        		if (c % 6 == 0 && Math.random() > 0.9 && shieldCooldown == 0 && attackPhase < 2) {
	        			if (chatDebug) sendChatMessage("Trying to block");
		        		shield = equipShieldIfAvailable();
		        		if (shield == null) {
		        			if (chatDebug) sendChatMessage("No shield found");
		        		}
		        		else if (!isOnCooldown(client.player, shield)) {
		        			if (chatDebug) sendChatMessage("Using shield");
		        			int shieldDuration = (int)(10*Math.random()*30);
	        				sendBaritoneCommand("stop");
		        			if (useShield(shieldDuration, closestTarget)) {
		        				shieldCooldown = (int)(shieldDuration*0.3) + 10;
		        			}
		        			return;
		        		}
	        		}
	        		if (attackPhase > 0)
	        			attackPhase--;
			        hit = attack(closestTarget, useCrits);
		            if (hit) {
		            	hitCountDown = 5;
		            	useCrits = false;
		            }
	        	} else if (attackPhase == 0 && killauraBow && hasLineOfSight && distance < 114*114 && bowCooldown == 0 && Math.random() > 0.8 && !(closestTarget instanceof PlayerEntity p && isBlockingWithShield(p))) {
	        		if (chatDebug) sendChatMessage("Attempting to shoot bow");
	        		boolean hasArrows = false;
	        		for (int i = 0; i < client.player.getInventory().size(); i++) {
	        			ItemStack stack = client.player.getInventory().getStack(i);
	        			if (stack != null) {
		        			Item item = stack.getItem();
		        			if (item == Items.ARROW ||
		        			    item == Items.TIPPED_ARROW ||
		        			    item == Items.SPECTRAL_ARROW) {
		        			    hasArrows = true;
		        			    break;
		        			}
	        			}
	        		}
	        		if (hasArrows) {
		        		if (chatDebug) sendChatMessage("Shooting the bow, cooldown = " + bowCooldown);
	        			bowCooldown = 8;
	        			//sendChatMessage("new bow cooldown = " + bowCooldown);
	        			sendBaritoneCommand("stop");
	        			shootBowAt(closestTarget, (float)Math.random()*2);
	        			return;
	        		} else {
		        		if (chatDebug) sendChatMessage("No arrows");
	        		}
	        	}
	        	if (killauraBaritone && c % 9 == 0) {
	        		if (baritoneCooldown > 0) {
	        			baritoneCooldown--;
	        		} else {
		        		if (closestTarget instanceof PlayerEntity targetPlayer) {
		        			sendBaritoneCommand("follow player " + targetPlayer.getName().getString());
			        		baritoneCooldown = 10;
		        		} else {
		        			sendBaritoneCommand("goto " + closestTarget.getBlockX() + " " + closestTarget.getBlockY() + " " + closestTarget.getBlockZ());
		        			baritoneCooldown = 5;
		        		}
	        		}
	        	}
	        }
	        for (UUID uuid : remove) {
	        	killauraTargets.remove(uuid);
	        }
        }
    }
	
	private boolean lineOfSight(Entity target) {
		// Line of sight check
        Vec3d eyePos = client.player.getCameraPosVec(1.0F);
        Vec3d targetPos = target.getPos().add(0, target.getHeight() / 2.0, 0);
        HitResult hit = client.world.raycast(new RaycastContext(
            eyePos, targetPos,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return hit.getType() == HitResult.Type.MISS || hit instanceof EntityHitResult;
	}
	
    private boolean canAttack(Entity target) {
        if (target == null || !target.isAlive() || target == client.player) return false;
        if (client.player.getAttackCooldownProgress(0.0f) < 1.0f) return false;

        // Basic range check (3 blocks)
        double distanceSq = client.player.squaredDistanceTo(target);
        if (distanceSq > 9) return false;

        return lineOfSight(target);
    }
    
    public boolean isBlockingWithShield(PlayerEntity player) {
        if (player == null) return false;

        return player.isUsingItem()
            && player.getActiveItem().getItem() instanceof ShieldItem;
    }
	
    private boolean hasMeleeWeapon(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        String name = main.getItem().getTranslationKey().toLowerCase();
        return name.contains("sword") || name.contains("axe");
    }

    private boolean isOnCooldown(ClientPlayerEntity player, ItemStack stack) {
        return player.getItemCooldownManager().isCoolingDown(stack);
    }

    private ItemStack equipShieldIfAvailable() {
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() instanceof ShieldItem) {
            	swapSlots(i, 40);
                // Swap with offhand
                
                return stack;
            }
        }
        return null;
    }
    
    boolean swapped = false;
    
    ItemStack shield = null;
    
    int shieldTimer = 0;
    
	public boolean attack(Entity target, boolean isCrit) {
        if (client.player == null || client.world == null || target == null) return false;

        // Face target
        lookAtEntity(client.player, target);
        
        if (target.getPos().distanceTo(client.player.getPos()) < 2.5 && Math.random() > 0.3) isCrit = false; // Don't crit if too close

        boolean useAxe = isCrit && Math.random() > 0.5;
        
        if (target instanceof PlayerEntity p) {
            if (isBlockingWithShield(p)) {
                useAxe = true;
                //sendChatMessage("Using the axe to break the shield");
            }
        } else {
        	//sendChatMessage("Not a player");
        }
        
        if (!swapped) {
	        int axeSlot = getHighestPowerSlot("_axe");
	        int swordSlot = getHighestPowerSlot("sword");
	        if (swordSlot == -1)
	        	swordSlot = getHighestPowerSlot("pickaxe");
	        if (swordSlot == -1)
	        	swordSlot = getHighestPowerSlot("shovel");
	        if (swordSlot == -1)
	        	swordSlot = getHighestPowerSlot("hoe");
	        
	        if (axeSlot == -1) {
	        	useAxe = false;
	        	//sendChatMessage("No axe in inventory");
	        }
	        
	        int targetSlot = useAxe ? axeSlot : swordSlot;
	        if (targetSlot == -1)
	        	targetSlot = 0;
	        
	        if (targetSlot <= 8) {
	        	client.player.getInventory().setSelectedSlot(targetSlot);
	        	swapped = true;
	        } else {
	        	swapSlots(targetSlot, 0);
	        	client.player.getInventory().setSelectedSlot(0);
	        	swapped = true;
	        }
        }
        
        if (!canAttack(target)) return false;

        if (isCrit) {
        	if (client.player.isOnGround()) {
	            client.player.jump();  // trigger jump
	            attackPhase = 10;
        	} else if (client.player.getVelocity().y < 0) { // Wait until falling (this example assumes this is in a tick loop or similar)
                swapped = false;
        		return performAttack(target);
            }
        } else {
        	swapped = false;
            return performAttack(target);
        }
        return false;
    }
	
	private void lookAtEntity(ClientPlayerEntity player, Entity target) {
        Vec3d delta = target.getPos().add(0, target.getHeight() / 2.0, 0).subtract(player.getEyePos());
        double distXZ = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F);
        float pitch = (float)(-Math.toDegrees(Math.atan2(delta.y, distXZ)));

        player.setYaw(yaw);
        player.setPitch(pitch);
    }
	
	private JsonElement attackEntityUnderCrosshair() {
		JsonObject result = new JsonObject();
		try {
			if (client.crosshairTarget instanceof EntityHitResult hit) {
				client.interactionManager.attackEntity(client.player, hit.getEntity());
				client.player.swingHand(client.player.getActiveHand());

				result.addProperty("success", true);
				result.addProperty("message", "Attacked entity: " + hit.getEntity().getName().getString());
				result.addProperty("uuid", hit.getEntity().getUuidAsString());
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "Not pointing at an entity");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to attack entity: " + e.getMessage());
		}
		return result;
	}
	
	private JsonElement performLeftClick() {
		JsonObject result = new JsonObject();
		try {
			if (client.crosshairTarget != null && client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
				BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
				client.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());
				client.player.swingHand(client.player.getActiveHand()); // Animation
				result.addProperty("success", true);
				result.addProperty("message", "Attacked block at " + hit.getBlockPos());
			} else if (client.crosshairTarget != null && client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY) {
				EntityHitResult hit = (EntityHitResult) client.crosshairTarget;
				client.interactionManager.attackEntity(client.player, hit.getEntity());
				client.player.swingHand(client.player.getActiveHand()); // Animation
				result.addProperty("success", true);
				result.addProperty("message", "Attacked entity: " + hit.getEntity().getName().getString());
			} else {
				result.addProperty("success", false);
				result.addProperty("error", "No block or entity targeted for left click");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed left click: " + e.getMessage());
		}
		return result;
	}
	
	private JsonElement performRightClick() {
		JsonObject result = new JsonObject();
		try {
			if (client.crosshairTarget != null && client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
				BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
				ActionResult action = client.interactionManager.interactBlock(client.player, client.player.getActiveHand(), hit);
				client.player.swingHand(client.player.getActiveHand());
				result.addProperty("success", true);
				result.addProperty("message", "Right-clicked block at " + hit.getBlockPos() + " (result: " + action + ")");
			} else if (client.crosshairTarget != null && client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY) {
				EntityHitResult hit = (EntityHitResult) client.crosshairTarget;
				ActionResult action = client.interactionManager.interactEntity(client.player, hit.getEntity(), client.player.getActiveHand());
				client.player.swingHand(client.player.getActiveHand());
				result.addProperty("success", true);
				result.addProperty("message", "Right-clicked entity: " + hit.getEntity().getName().getString() + " (result: " + action + ")");
			} else {
				// Default to using the item in the air
				ActionResult action = client.interactionManager.interactItem(client.player, client.player.getActiveHand());
				client.player.swingHand(client.player.getActiveHand());
				result.addProperty("success", true);
				result.addProperty("message", "Used item in air (result: " + action + ")");
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed right click: " + e.getMessage());
		}
		return result;
	}

	
	private JsonObject selectHotbarSlot(int slot) {
		JsonObject result = new JsonObject();
		ClientPlayerEntity player = client.player;

		if (player == null) {
			result.addProperty("success", false);
			result.addProperty("error", "Player not available");
			return result;
		}

		if (slot < 0 || slot > 8) {
			result.addProperty("success", false);
			result.addProperty("error", "Slot must be between 0 and 8");
			return result;
		}

		player.getInventory().setSelectedSlot(slot);
		result.addProperty("success", true);
		result.addProperty("message", "Selected hotbar slot " + slot);
		return result;
	}
	
	private JsonObject getHotbarItems() {
		JsonObject hotbarData = new JsonObject();
		List<JsonObject> items = new ArrayList<>();

		ClientPlayerEntity player = client.player;

		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getStack(i);
			JsonObject item = new JsonObject();
			item.addProperty("slot", i);
			item.addProperty("type", stack.isEmpty() ? "empty" : sanitizeString(stack.getItem().toString()));
			item.addProperty("name", stack.isEmpty() ? "empty" : sanitizeString(stack.getName().getString()));
			item.addProperty("count", stack.getCount());
			items.add(item);
		}

		hotbarData.add("items", gson.toJsonTree(items));
		return hotbarData;
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
	
	private Entity getEntity(String entityUuid) {
		if (entityUuid == null || entityUuid.trim().isEmpty()) {
            return null;
        }
        
        try {
            ClientPlayerEntity player = client.player;
            ClientWorld world = client.world;
            
            if (player != null && world != null) {
                //UUID targetUuid = UUID.fromString(entityUuid);
                
                // Search for the entity in a large area around the player
                Box searchBox = new Box(player.getBlockPos()).expand(200);
                List<Entity> entities = world.getEntitiesByClass(Entity.class, searchBox, 
                    entity -> entity.getUuidAsString().equals(entityUuid));
                
                if (!entities.isEmpty()) {
                    Entity targetEntity = entities.get(0);
                    
                    return targetEntity;
                }
            } 
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
        }
        
        return null;
	}
	
	private LivingEntity getLivingEntity(String entityUuid) {
		if (entityUuid == null || entityUuid.trim().isEmpty()) {
            return null;
        }
        
        try {
            ClientPlayerEntity player = client.player;
            ClientWorld world = client.world;
            
            if (player != null && world != null) {
                //UUID targetUuid = UUID.fromString(entityUuid);
                
                // Search for the entity in a large area around the player
                Box searchBox = new Box(player.getBlockPos()).expand(200);
                List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, searchBox, 
                    entity -> entity.getUuidAsString().equals(entityUuid));
                
                if (!entities.isEmpty()) {
                    LivingEntity targetEntity = entities.get(0);
                    
                    return targetEntity;
                }
            } 
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
        }
        
        return null;
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

	private JsonObject getItemsAroundPlayer(int range) {
		JsonObject entitiesData = new JsonObject();
		List<JsonObject> entities = new ArrayList<>();

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;

		Box searchBox = new Box(player.getBlockPos()).expand(range);
		List<ItemEntity> itemEntities = world.getEntitiesByClass(ItemEntity.class, searchBox,
				entity -> (entity instanceof ItemEntity));

		for (ItemEntity entity : itemEntities) {
			try {
				JsonObject entityData = new JsonObject();
				entityData.addProperty("type", sanitizeString(entity.getType().toString()));
				entityData.addProperty("name", sanitizeString(entity.getName().getString()));
				entityData.addProperty("x", entity.getX());
				entityData.addProperty("y", entity.getY());
				entityData.addProperty("z", entity.getZ());
				entityData.addProperty("uuid", entity.getUuidAsString());

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
	
	public static class FancyMessage {
		public String text;
		public GameProfile sender;
		public Instant timestamp;
		
		public FancyMessage(String text, GameProfile sender, Instant timestamp) {
			this.text = text;
			this.sender = sender;
			this.timestamp = timestamp;
		}
	}
	
	public static List<FancyMessage> unreadChat = new ArrayList<>();

	private JsonObject getUnreadChat() {
		JsonObject result = new JsonObject();
		List<JsonObject> messages = new ArrayList<>();
		
		try {
			if (unreadChat.size() > 0) {
				result.addProperty("new_chat", true);
				for (FancyMessage msg : unreadChat) {
					JsonObject msgObject = new JsonObject();
					msgObject.addProperty("text", msg.text);
					msgObject.addProperty("sender", msg.sender.getName());
					msgObject.addProperty("timestamp", msg.timestamp.toString());
					messages.add(msgObject);
				}
				unreadChat.clear();
				result.add("messages", gson.toJsonTree(messages));
			} else {
				result.addProperty("new_chat", false);
				result.add("messages", null);
			}
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "Failed to read chat: " + e.getMessage());
		}
		
		return result;
	}
	
	private JsonObject sendBaritoneCommand(String cmd) {
		JsonObject result = new JsonObject();
		if (cmd == null || cmd.trim().isEmpty()) {
			result.addProperty("success", false);
			result.addProperty("error", "Command cannot be empty");
			return result;
		}
		/*
		if (GameQueryModClient.baritone) {
			try {
				boolean r = BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(cmd);
				result.addProperty("success", r);
			} catch (Exception e) {
				result.addProperty("success", false);
				result.addProperty("error", e.getMessage());
			}
		} else {*/
			return sendChatMessage("#" + cmd);
		//}
		//return result;
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
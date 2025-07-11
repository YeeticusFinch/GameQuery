# GameQuery Fabric Mod for Minecraft 1.21.6

**GameQuery** is a Fabric mod for Minecraft 1.21.6 that exposes a local query interface on port **25566**, allowing external scripts and applications to retrieve live game data or control certain in-game actions. This is ideal for AI agents, bots, automation scripts, or integration with custom tooling.

---

## 🔧 Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.6.
2. Install [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).
3. Drop the GameQuery mod JAR into your `.minecraft/mods` folder.
4. Launch the game.
5. The query server will start automatically on `localhost:25566`.

---

## Running

The query server will start automatically on `localhost:25566` whenever you enter a game (joining a singleplayer or multiplayer world.

---

## 📡 Query Protocol

Queries are sent to `localhost:25566` as JSON lines (1 JSON object per line). Responses are returned in the same format.

### Example request:
```json
{ "type": "inventory" }
```

### Example response:
```json
{ "inventory": { "slots": [...], "selected": 0 } }
```

## 📘 Available Queries
### inventory
Get the player's inventory contents.
```json
{ "type": "inventory" }
```
Returns:
```json
{
  "inventory": {
    "items": [
      {
        "slot": 0,
        "type": "minecraft:diamond_sword",
        "name": "Super Fancy Sword",
        "count": 1,
        "damage": 17,
        "maxDamage": 990,
        "durability": 973,
        "enchanted": true
      },
      {
        "slot": 1,
        "type": "minecraft:diamond_shovel",
        "name": "My Favorite Spoon",
        "count": 1,
        "damage": 3,
        "maxDamage": 990,
        "durability": 987,
        "enchanted": true
      },
      ...
    ],
    "selected": 0 
  }
}
```
### position
Get the player's current position and rotation.
```json
{ "type": "position" }
```
Returns:
```json
{
  "position": {
    "x": 123.0,
    "y": 64.0,
    "z": 456.0,
    "yaw": 90.0,
    "pitch": 0.0
  }
}
```

### blocks
Get a 3D array of blocks around the player.
```json
{ "type": "blocks", "range": 5 }
```
Optional:

range: Integer radius (default: 5)

Returns:
```json
{
  "blocks": {
    "blocks": [
      {
        "x": -182,
        "y": 102,
        "z": 12,
        "type": "Block{minecraft:spruce_slab}"
      },
      {
        "x": -182,
        "y": 102,
        "z": 14,
        "type": "Block{minecraft:crafting_table}"
      },
      {
        "x": -182,
        "y": 103,
        "z": 12,
        "type": "Block{minecraft:air}"
      }
    ]
  }
}
```

### entities
Get nearby entities and their data.
```json
{ "type": "entities", "range": 10 }
```
Optional:

range: Integer radius (default: 10)

Returns:
```json
{
  "entities": {
    "entities": [
      {
        "type": "entity.minecraft.villager",
        "name": "Fletcher",
        "x": -182.30000001192093,
        "y": 103.0,
        "z": 15.667358260097222,
        "health": 20.0,
        "maxHealth": 20.0,
        "uuid": "2e290bd5-81f5-4166-bc25-806818f1d958",
        "hostile": false,
        "passive": true,
        "neutral": false,
        "isPlayer": false
      }
    ]
  }
}
```

### world_info
Get general information about the world (dimension, time, etc.).

```json
{ "type": "world_info" }
```
Returns:
```json
{
  "world_info": {
    "dimension": "minecraft:overworld",
    "time": 4154384,
    "isDay": true,
    "isRaining": false,
    "isThundering": false,
    "difficulty": "NORMAL"
  }
}
```
### send_chat
Send a message into the in-game chat.

```json
{ "type": "send_chat", "message": "Hello World!" }
```
Returns:
```json
{
  "result": {
    "success": true,
    "message": "Sent: Hello World!"
  }
}
```

### drop_item (TO BE IMPLEMENTED)
Drop an item from inventory.

By slot:
```json
{ "type": "drop_item", "slot": 3 }
```
By item name:
```json
{ "type": "drop_item", "name": "minecraft:stone" }
```
### rotate
Rotate the player’s view.
```json
{
  "type": "rotate",
  "yaw": 90.0,
  "pitch": 0.0
}
```
Returns:
```json
{
  "result": {
    "success": true,
    "message": "Rotated player to yaw: 90.0\u00b0, pitch: 0.0\u00b0",
    "yaw": 90.0,
    "pitch": 0.0
  }
}
```

### point_to_entity
Rotates the player to point at an entity with a specific UUID (hint: the `entities` query returns UUIDs). This search for an entity of that UUID within 100 blocks, and it will point the crosshair at the center of that entity (entity's height divided by 2).
```json
{ "type": "point_to_entity", "uuid": "2e290bd5-81f5-4166-bc25-806818f1d958" }
```
Returns:
```json
{
  "result": {
    "success": true,
    "message": "Pointed to entity: Fletcher",
    "entityName": "Fletcher",
    "entityType": "entity.minecraft.villager",
    "distance": 2.93,
    "yaw": 28.57,
    "pitch": 12.7,
    "entityX": -182.30000001192093,
    "entityY": 103.0,
    "entityZ": 15.667358260097222
  }
}
```

### point_to_xyz
Rotates the player to point the crosshair at a target coordinate
```json
{ "type": "point_to_xyz", "x": 42, "y": 69, "z": -42.2 }
```
As you can see from the above example, the coordinates can accept both integers and floats.

Example Response:
```json
{
  "result": {
    "success": true,
    "message": "Pointed to location: 42.0 69.0 -42.2",
    "distance": 232.45,
    "yaw": 256.06,
    "pitch": 8.81
  }
}
```

### get_screen_pos (WIP)
Gets the screen position of a specific inventory slot. Screen position is returned in pixels. This is still a work in progress, so approach this one with caution.
```json
{ "type": "get_screen_pos", "slot": 5 }
```
Example Response:
```json
{
  "result": {
    "x": 223,
    "y": 179
  }
}
```

### get_block
Gets the block and some other information about the block at a specific coordinate in the same world as the player.
```json
{ "type": "get_block", "x": 42, "y": 69, "z": 42 }
```
Example Response:
```json
{
  "type": "Block{minecraft:chest}",
  "contents": []
}
```

### left_click
Performs a generic left click
```json
{"type": "left_click"}
```
Example Response (if it's a block on the crosshair):
```json
{
  "result": {
    "success": true,
    "message": "Attacked block at class_2339{x=-190, y=115, z=110}"
  }
}
```
Example Response (if it's an entity on the crosshair):
```json
{
  "result": {
    "success": true,
    "message": "Attacked entity: Armor Stand"
  }
}
```

### right_click
Performs a generic right click
```json
{"type": "right_click"}
```
Example Response:
```json
{
  "result": {
    "success": true,
    "message": "Right-clicked entity: Armor Stand (result: class_9859[])"
  }
}
```

### select_slot
Selects the hotbar slot by slot number (starting from 0, so numbers from 0-8 inclusive).
```json
{"type": "select_slot", "slot":1}
```
Example Response:
```json
{
  "result": {
    "success": true,
    "message": "Selected hotbar slot 1"
  }
}
```

### hotbaar
Returns the items in the player's hotbar.
```json
{"type": "hotbar"}
```
Example Response:
```json
{
  "hotbar": {
    "items": [
      {
        "slot": 0,
        "type": "minecraft:iron_sword",
        "name": "Iron Sword",
        "count": 1
      },
      {
        "slot": 1,
        "type": "minecraft:water_bucket",
        "name": "Water Bucket",
        "count": 1
      },
      {
        "slot": 2,
        "type": "minecraft:bow",
        "name": "Bow",
        "count": 1
      },
      {
        "slot": 3,
        "type": "minecraft:stone_pickaxe",
        "name": "Stone Pickaxe",
        "count": 1
      },
      {
        "slot": 4,
        "type": "minecraft:stone_axe",
        "name": "Stone Axe",
        "count": 1
      },
      {
        "slot": 5,
        "type": "minecraft:spruce_boat",
        "name": "Spruce Boat",
        "count": 1
      },
      {
        "slot": 6,
        "type": "minecraft:cobblestone_slab",
        "name": "Cobblestone Slab",
        "count": 2
      },
      {
        "slot": 7,
        "type": "minecraft:carrot",
        "name": "Carrot",
        "count": 36
      },
      {
        "slot": 8,
        "type": "minecraft:cobblestone",
        "name": "Cobblestone",
        "count": 52
      }
    ]
  }
}
```

### open_container
Opens the container on the crosshair
```json
{"type": "open_container"}
```
Example Response (if there is a container at the crosshair):
```json
{
  "result": {
    "success": true,
    "message": "Interacted with block at class_2339{x=-190, y=115, z=110} (result: class_9860[swingSource=CLIENT, itemContext=class_9858[wasItemInteraction=true, heldItemTransformedTo=null]])"
  }
}
```
Example Response (if no container):
```json
{
  "result": {
    "success": true,
    "message": "Interacted with block at class_2339{x=-193, y=114, z=109} (result: class_9859[])"
  }
}
```
(ps. I'm sorry that it still shows success=true for this, I'll fix this in future versions)

### attack
Attacks whatever is on the crosshair using the currently selected item in the mainhand.
```json
{"type": "attack"}
```
Example Response:
```json
{
  "result": {
    "success": true,
    "message": "Attacked entity: Sheep",
    "uuid": "44a7eb0a-ddde-43c3-873c-594a94aa1cd1"
  }
}
```

### shoot_bow
Selects a bow from the hotbar, and shoots it at a target coordinate or entity, accurate up to 114 blocks range for stationary targets
Optional overshoot parameter, to overshoot by a certain range (ie. shoot this many blocks further than the target)
```json
{"type": "shoot_bow", "x": 42, "y": 69, "z": 42}
```
```json
{"type": "shoot_bow", "x": 42, "y": 69, "z": 42, "overshoot": 5}
```
```json
{"type": "shoot_bow", "entity": "44a7eb0a-ddde-43c3-873c-594a94aa1cd1"}
```
```json
{"type": "shoot_bow", "entity": "44a7eb0a-ddde-43c3-873c-594a94aa1cd1", "overshoot": 3}
```

Example Response:
```json
{
  "result": {
    "bow_slot": 2
  }
}
```

### use_bed
Tries to sleep in the bed that the client is facing, or a bed at a provided coordinate.
Using bed on crosshair:
```json
{"type": "use_bed"}
```
Using bed at a coordinate:
```json
{"type": "use_bed", "x": 42, "y": 69, "z": 42}
```
Example Response (if client can't sleep because it isn't night time or thunderstorm):
```json
{
  "result":
  {
    "success": false,
    "error": "Cannot sleep now (not night or thunderstorm)"
  }
}
```

Example Response (success):
```json
{
  "result":
  {
    "success": true,
    "message": "Sent bed use interaction to server at class_2338{x=42, y=69, z=42}"
  }
}
```

Important Note: Success state doesn't guarantee that the client actually went to sleep, it just meant that the bed interaction packet was sent.

For example, if there are monsters nearby, or if the player is too far away from the bed, then the server will deny the bed interaction packet, but you will still see the success state.

### leave_bed
Leaves the bed that the client is currently sleeping in
```json
{"type": "leave_bed"}
```

### use_door
Opens or closes the door on the crosshair or on the providede coordinates.

This one will interact with the door on the crosshair (if it is open, it sets it closes the door, if the door is closed, it opens the door):
```json
{"type": "use_door"}
```

This one opens the door on the crosshair (it will only interact with the door if it is currently closed, in which case it will open it):
```json
{"type": "use_door", "state": true}
```

This one closes the door at coordinates 42 69 42
```json
{"type": "use_door", "state": false, "x": 42, "y": 69, "z": 42}
```

### exit_gui
Exits whatever gui is currently active (container gui like a chest, inventory, villager interaction...)
```json
{"type": "exit_gui"}
```

### players
Gets a list of all online players
```json
{"type": "players"}
```
Example Response:
```json
{
  "success": true,
  "players": [
    {
      "uuid": "f84c6a79-31e7-49cf-a5d9-b0c4c7b531df",
      "name": "Steve",
      "x": 123.4,
      "y": 65.0,
      "z": -45.2,
      "dimension": "minecraft:overworld",
      "health": 20.0,
      "maxHealth": 20.0,
      "food": 18,
      "saturation": 5.0,
      "level": 3
    }
  ]
}
```

The elements in the players array can look very different depending on circumstances.

When a player is outside of render distance, but still in the same dimension, it will try to extract information from the player tracker, if those are enabled on the server. Specifically, it will try connecting to the CarlsFaceBar Fabric mod (see it in GitHub releases).

Example Response for a player that is outside of render distance, but still in the same dimension:
```json
      {
        "uuid": "e048e80f-aced-4884-82f8-ae2addffdce9",
        "name": "carl_alex",
        "active": true,
        "direction_yaw": 44.493186950683594,
        "distance": 9.223372036854776e+16,
        "x": Infinity,
        "z": Infinity,
        "inRenderDistance": false
      }
```

When a player is in a different dimension, it will use the last waypoint data

Example Response for a player in a different dimension:
```json
      {
        "uuid": "e048e80f-aced-4884-82f8-ae2addffdce9",
        "name": "carl_alex",
        "active": false,
        "direction_yaw": 44.493186950683594,
        "distance": 9.223372036854776e+16,
        "x": Infinity,
        "z": Infinity,
        "inRenderDistance": false
      }
```

However, if a player has logged in on a different dimension or world than the client, and they have never been in the same world as the client, then there will be no waypoint data.

Example Response for a player in a different dimension that hasn't ever been on the same dimension as the client:
```json
      {
        "uuid": "e048e80f-aced-4884-82f8-ae2addffdce9",
        "name": "carl_alex",
        "inRenderDistance": false
      }
```


## 🐍 Sample Python Client
```python
import socket
import json
from typing import Any, Dict, Optional

class GameQueryClient:
    def __init__(self, host="localhost", port=25566):
        self.host = host
        self.port = port

    def send_query(self, query: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Send a query to the Minecraft client and return the response."""
        try:
            with socket.create_connection((self.host, self.port), timeout=5) as sock:
                query_json = json.dumps(query) + "\n"
                sock.sendall(query_json.encode('utf-8'))

                with sock.makefile('r', encoding='utf-8') as f:
                    response_line = f.readline()
                    return json.loads(response_line.strip())

        except socket.timeout:
            print(f"❌ Timeout connecting to {self.host}:{self.port}")
            return None
        except ConnectionRefusedError:
            print(f"❌ Connection refused to {self.host}:{self.port}")
            print("   Make sure Minecraft is running with the GameQuery mod loaded")
            return None
        except Exception as e:
            print(f"❌ Error: {e}")
            return None

# Example usage:
if __name__ == "__main__":
    client = GameQueryClient()
    response = client.send_query({"type": "position"})
    print(response)
```

### use_door
Opens or closes the door at the crosshair or at a defined coordinate. If `state` is defined, then it will set the door to that state (open = true, closed = false). Else it will just toggle the door to the opposite state of what it is currently at.
Query:
```json
{"type":"use_door"}
```
Response:
```json
{
  "result": {
    "result": "closed the door at class_2338{x=-178, y=104, z=14}"
  }
}
```
Query:
```json
{"type":"use_door", "state":"closed"}
```
Response:
```json
{
  "result": {
    "result": "door already closed"
  }
}
```
Query:
```json
{"type":"use_door", "state":true}
```
Response:
```json
{
  "result": {
    "result": "opened the door at class_2338{x=-178, y=103, z=14}"
  }
}
```
Query:
```json
{"type":"use_door", "x":-178, "y":103, "z":14, "state":true}
```
Response:
```json
{
  "result": {
    "result": "opened the door at class_2338{x=-178, y=103, z=14}"
  }
}
```

### use_bed
Tries to sleep in the bed at the defined position or the bed on the crosshair (if no position is defined).
```json
{"type": "use_bed"}
```
```json
{"type": "use_bed",  "x":-172, "y":102, "z":19}
```
Response:
```json
{
  "result": {
    "success": true,
    "message": "Sent bed use interaction to server at class_2338{x=-172, y=102, z=19}"
  }
}
```

Response if the block isn't a bed:
```json
{
  "result": {
    "success": false,
    "error": "Block at given position is not a bed"
  }
}
```

Response if it is daytime or not a thunderstorm (sleep failed):
```json
{
  "result": {
    "success": false,
    "error": "Cannot sleep now (not night or thunderstorm)"
  }
}
```

### leave_bed
Leaves the bed that the player is currently sleeping in (sometimes `use_bed` will put the player in a glitched state where they can't leave the bed normally)
```json
{"type": "leave_bed"}
```

## 🧪 Testing
Once Minecraft is running with the mod:

```bash
curl -X POST --data '{"type": "position"}' localhost:25566
```
Or use the Python script above for richer integration.

## 🛡️ Security Warning
This mod exposes internal game data via an open TCP port. Only run this mod on a trusted single-player or LAN-only environment. Do not expose the port to the internet.

📜 License
MIT License — Free to use and modify.

✨ Contributions
Pull requests welcome! Add more query types, optimize performance, or support additional Minecraft features.

Check out this project on Modrinth: https://modrinth.com/mod/gamequery

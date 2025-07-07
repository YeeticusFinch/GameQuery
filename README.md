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
#### inventory
Get the player's inventory contents.
```json
{ "type": "inventory" }
position
Get the player's current position and rotation.
```
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

#### blocks
Get a 3D array of blocks around the player.
```json
{ "type": "blocks", "range": 5 }
```
Optional:

range: Integer radius (default: 5)

#### entities
Get nearby entities and their data.
```json
{ "type": "entities", "range": 10 }
```
Optional:

range: Integer radius (default: 10)

#### world_info
Get general information about the world (dimension, time, etc.).

```json
{ "type": "world_info" }
```
#### send_chat
Send a message into the in-game chat.

```json
{ "type": "send_chat", "message": "Hello from Python!" }
```

#### drop_item (TO BE IMPLEMENTED)
Drop an item from inventory.

By slot:
```json
{ "type": "drop_item", "slot": 3 }
```
By item name:
```json
{ "type": "drop_item", "name": "minecraft:stone" }
```
#### rotate
Rotate the player’s view.
```json
{
  "type": "rotate",
  "yaw": 90.0,
  "pitch": 0.0
}
```
Optional:

yaw, pitch: Defaults to current orientation if omitted.

#### point_to_entity
Rotate the player to face a specific entity by UUID.
```json
{ "type": "point_to_entity", "uuid": "a1b2c3d4-..." }
```
#### point_to_xyz
Rotate the player to face specific world coordinates.
```json
{
  "type": "point_to_xyz",
  "x": 123.0,
  "y": 64.0,
  "z": 456.0
}
```
#### get_screen_pos
Get the on-screen pixel position of a specific inventory slot.
```json
{ "type": "get_screen_pos", "slot": 0 }
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








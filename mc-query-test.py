#!/usr/bin/env python3
"""
Simple test client for the GameQuery Minecraft mod.
Make sure Minecraft is running with the mod loaded before running this script.
"""

import socket
import json
import time
import sys
from typing import Dict, Any, Optional

class GameQueryClient:
    def __init__(self, host: str = "localhost", port: int = 25566):
        self.host = host
        self.port = port
    
    def send_query(self, query: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Send a query to the Minecraft client and return the response."""
        try:
            # Create socket connection
            with socket.create_connection((self.host, self.port), timeout=5) as sock:
                # Send query as JSON
                query_json = json.dumps(query) + "\n"
                sock.sendall(query_json.encode('utf-8'))

                # Use a buffered reader to read the full line
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


    
    def test_inventory(self):
        """Test inventory query."""
        print("\n🎒 Testing inventory query...")
        response = self.send_query({"type": "inventory"})
        
        if response:
            if "error" in response:
                print(f"❌ Error: {response['error']}")
            else:
                inventory = response.get("inventory", {})
                items = inventory.get("items", [])
                print(f"✅ Found {len(items)} items in inventory:")
                for item in items[:5]:  # Show first 5 items
                    print(f"   Slot {item['slot']}: {item['name']} x{item['count']}")
                if len(items) > 5:
                    print(f"   ... and {len(items) - 5} more items")
    
    def test_position(self):
        """Test position query."""
        print("\n📍 Testing position query...")
        response = self.send_query({"type": "position"})
        
        if response:
            if "error" in response:
                print(f"❌ Error: {response['error']}")
            else:
                pos = response.get("position", {})
                print(f"✅ Player position:")
                print(f"   Location: ({pos.get('x', 0):.1f}, {pos.get('y', 0):.1f}, {pos.get('z', 0):.1f})")
                print(f"   Rotation: Yaw {pos.get('yaw', 0):.1f}°, Pitch {pos.get('pitch', 0):.1f}°")
                print(f"   Health: {pos.get('health', 0):.1f}/{pos.get('maxHealth', 0):.1f}")
                print(f"   Food: {pos.get('food', 0)}/20")
                print(f"   Level: {pos.get('level', 0)} (Total XP: {pos.get('experience', 0)})")
    
    def test_blocks(self, range_size: int = 3):
        """Test blocks query."""
        print(f"\n🧱 Testing blocks query (range: {range_size})...")
        response = self.send_query({"type": "blocks", "range": range_size})
        
        if response:
            if "error" in response:
                print(f"❌ Error: {response['error']}")
            else:
                blocks = response.get("blocks", {}).get("blocks", [])
                print(f"✅ Found {len(blocks)} blocks in range:")
                
                # Count block types
                block_counts = {}
                containers = []
                for block in blocks:
                    block_type = block.get("type", "unknown")
                    block_counts[block_type] = block_counts.get(block_type, 0) + 1
                    if "contents" in block:
                        containers.append(block)
                
                # Show top 5 block types
                sorted_blocks = sorted(block_counts.items(), key=lambda x: x[1], reverse=True)
                for block_type, count in sorted_blocks[:5]:
                    print(f"   {block_type}: {count}")
                
                if containers:
                    print(f"   Found {len(containers)} containers with items")
                
                print("\n   Here are some of the blocks:")
                for block in blocks[:5]:
                    type = block.get("type", "unknown")
                    x = block.get("x", 0)
                    y = block.get("y", 0)
                    z = block.get("z", 0)
                    print(f"   {type} at {x} {y} {z}")
    
    def test_entities(self, range_size: int = 10):
        """Test entities query."""
        print(f"\n🐄 Testing entities query (range: {range_size})...")
        response = self.send_query({"type": "entities", "range": range_size})
        
        if response:
            if "error" in response:
                print(f"❌ Error: {response['error']}")
            else:
                entities = response.get("entities", {}).get("entities", [])
                print(f"✅ Found {len(entities)} entities in range:")
                
                for entity in entities[:10]:  # Show first 10 entities
                    name = entity.get("name", "Unknown")
                    entity_type = entity.get("type", "unknown")
                    x, y, z = entity.get("x", 0), entity.get("y", 0), entity.get("z", 0)
                    health = entity.get("health", 0)
                    max_health = entity.get("maxHealth", 0)
                    is_player = entity.get("isPlayer", 0)
                    uuid = entity.get("uuid", "Unknown")
                    
                    status = ""
                    if entity.get("hostile"):
                        status = "🔴 Hostile"
                    elif entity.get("passive"):
                        status = "🟢 Passive"
                    elif entity.get("neutral"):
                        status = "🟡 Neutral"
                    elif entity.get("isPlayer"):
                        status = "👤 Player"
                    
                    print(f"   {name} ({entity_type}) at ({x:.1f}, {y:.1f}, {z:.1f}) - {health:.1f}/{max_health:.1f} HP {status} UUID {uuid} isPlayer {is_player}")
    
    def test_world_info(self):
        """Test world info query."""
        print("\n🌍 Testing world info query...")
        response = self.send_query({"type": "world_info"})
        
        if response:
            if "error" in response:
                print(f"❌ Error: {response['error']}")
            else:
                world = response.get("world_info", {})
                print(f"✅ World information:")
                print(f"   Dimension: {world.get('dimension', 'unknown')}")
                print(f"   Time: {world.get('time', 0)} ({'Day' if world.get('isDay') else 'Night'})")
                print(f"   Weather: {'Rain' if world.get('isRaining') else 'Clear'}{' + Thunder' if world.get('isThundering') else ''}")
                print(f"   Difficulty: {world.get('difficulty', 'unknown')}")
    
    def test_invalid_query(self):
        """Test invalid query to check error handling."""
        print("\n❓ Testing invalid query...")
        response = self.send_query({"type": "invalid_query_type"})
        
        if response:
            if "error" in response:
                print(f"✅ Error handling works: {response['error']}")
            else:
                print(f"❌ Expected error but got: {response}")
    
    def send_chat_message(self, message: str):
        """Send a chat message as the player."""
        print(f"\n💬 Sending chat message: '{message}'")
        response = self.send_query({"type": "send_chat", "message": message})
        
        if response:
            result = response.get("result", {})
            if result.get("success"):
                print(f"✅ {result.get('message', 'Message sent')}")
            else:
                print(f"❌ Failed: {result.get('error', 'Unknown error')}")
        return response
    
    def drop_item_from_slot(self, slot: int):
        """Drop an item from a specific inventory slot."""
        print(f"\n🗑️ Dropping item from slot {slot}...")
        response = self.send_query({"type": "drop_item", "slot": slot})
        
        if response:
            result = response.get("result", {})
            if result.get("success"):
                print(f"✅ {result.get('message', 'Item dropped')}")
            else:
                print(f"❌ Failed: {result.get('error', 'Unknown error')}")
        return response
    
    def drop_items_by_name(self, item_name: str):
        """Drop all items matching a name."""
        print(f"\n🗑️ Dropping items matching '{item_name}'...")
        response = self.send_query({"type": "drop_item", "name": item_name})
        
        if response:
            result = response.get("result", {})
            if result.get("success"):
                print(f"✅ {result.get('message', 'Items dropped')}")
            else:
                print(f"❌ Failed: {result.get('error', 'Unknown error')}")
        return response
    
    def rotate_player(self, yaw: float = None, pitch: float = None):
        """Rotate the player to a specific direction."""
        rotation_desc = []
        if yaw is not None:
            rotation_desc.append(f"yaw: {yaw}°")
        if pitch is not None:
            rotation_desc.append(f"pitch: {pitch}°")
        
        print(f"\n🔄 Rotating player ({', '.join(rotation_desc)})...")
        
        query = {"type": "rotate"}
        if yaw is not None:
            query["yaw"] = yaw
        if pitch is not None:
            query["pitch"] = pitch
            
        response = self.send_query(query)
        
        if response:
            result = response.get("result", {})
            if result.get("success"):
                print(f"✅ {result.get('message', 'Player rotated')}")
            else:
                print(f"❌ Failed: {result.get('error', 'Unknown error')}")
        return response

def action_demo():
    """Demonstrate the new action capabilities."""
    print("\n🎮 Action Demo Mode")
    print("==================")
    
    client = GameQueryClient()
    
    # Test connection first
    test_response = client.send_query({"type": "position"})
    if test_response is None:
        print("❌ Cannot connect to GameQuery server")
        return
    
    print("✅ Connected! Demonstrating actions...")
    
    try:
        # Demo 1: Send a chat message
        print("\n--- Demo 1: Chat Message ---")
        client.send_chat_message("Hello from Python! 🐍")
        time.sleep(1)
        
        # Demo 2: Rotate player
        print("\n--- Demo 2: Player Rotation ---")
        client.rotate_player(yaw=0, pitch=0)    # Look north and level
        time.sleep(1)
        client.rotate_player(yaw=90, pitch=-45) # Look east and up
        time.sleep(1)
        client.rotate_player(yaw=180, pitch=0)  # Look south and level
        time.sleep(1)
        client.rotate_player(yaw=270, pitch=45) # Look west and down
        time.sleep(1)
        
        # Demo 3: Get inventory and potentially drop items
        print("\n--- Demo 3: Inventory Management ---")
        inv_response = client.send_query({"type": "inventory"})
        if inv_response and "inventory" in inv_response:
            items = inv_response["inventory"].get("items", [])
            if items:
                print(f"Found {len(items)} items in inventory")
                
                # Ask if user wants to drop something
                print("\nAvailable items:")
                for i, item in enumerate(items[:10]):  # Show first 10
                    print(f"  {i+1}. Slot {item['slot']}: {item['name']} x{item['count']}")
                
                choice = input("\nDrop an item? Enter slot number (or 'n' to skip): ").strip()
                if choice.isdigit():
                    slot = int(choice)
                    if 0 <= slot < 100:  # Reasonable slot range
                        client.drop_item_from_slot(slot)
                
                # Demo dropping by name
                item_name = input("Drop items by name? Enter item name (or 'n' to skip): ").strip()
                if item_name.lower() != 'n' and item_name:
                    client.drop_items_by_name(item_name)
            else:
                print("No items in inventory to demonstrate dropping")
        
        print("\n✅ Action demo completed!")
        
    except KeyboardInterrupt:
        print("\n\n👋 Demo interrupted by user")
    print("🎮 GameQuery Minecraft Mod Test Client")
    print("=====================================")
    
    client = GameQueryClient()
    
    # Test connection
    print("\n🔗 Testing connection...")
    test_response = client.send_query({"type": "position"})
    if test_response is None:
        print("❌ Cannot connect to GameQuery server")
        print("   Make sure:")
        print("   1. Minecraft is running")
        print("   2. GameQuery mod is loaded")
        print("   3. You're in a world (not main menu)")
        sys.exit(1)
    
    print("✅ Connected to GameQuery server!")
    
    # Run all tests
    try:
        client.test_position()
        client.test_inventory()
        client.test_world_info()
        client.test_blocks(range_size=3)
        client.test_entities(range_size=15)
        client.test_invalid_query()
        
        print("\n✅ All tests completed!")
        
    except KeyboardInterrupt:
        print("\n\n👋 Tests interrupted by user")
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")

def interactive_mode():
    """Interactive mode for custom queries."""
    print("\n🎮 Interactive Query Mode")
    print("========================")
    print("Enter JSON queries (or 'quit' to exit):")
    print("Examples:")
    print('  {"type": "inventory"}')
    print('  {"type": "position"}')
    print('  {"type": "blocks", "range": 5}')
    print('  {"type": "entities", "range": 10}')
    print('  {"type": "world_info"}')
    print()
    
    client = GameQueryClient()
    
    while True:
        try:
            query_input = input("Query> ").strip()
            
            if query_input.lower() in ['quit', 'exit', 'q']:
                print("👋 Goodbye!")
                break
            
            if not query_input:
                continue
            
            try:
                query = json.loads(query_input)
                response = client.send_query(query)
                
                if response:
                    print(json.dumps(response, indent=2))
                else:
                    print("❌ No response received")
                    
            except json.JSONDecodeError:
                print("❌ Invalid JSON format")
                
        except KeyboardInterrupt:
            print("\n👋 Goodbye!")
            break
        except EOFError:
            print("\n👋 Goodbye!")
            break
        
def main():
    print("🎮 GameQuery Minecraft Mod Test Client")
    print("=====================================")
    
    client = GameQueryClient()
    
    # Test connection
    print("\n🔗 Testing connection...")
    test_response = client.send_query({"type": "position"})
    if test_response is None:
        print("❌ Cannot connect to GameQuery server")
        print("   Make sure:")
        print("   1. Minecraft is running")
        print("   2. GameQuery mod is loaded")
        print("   3. You're in a world (not main menu)")
        sys.exit(1)
    
    print("✅ Connected to GameQuery server!")
    
    # Run all tests
    try:
        client.test_position()
        client.test_inventory()
        client.test_world_info()
        client.test_blocks(range_size=3)
        client.test_entities(range_size=15)
        client.test_invalid_query()
        
        print("\n✅ All tests completed!")
        
    except KeyboardInterrupt:
        print("\n\n👋 Tests interrupted by user")
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")

def interactive_mode():
    """Interactive mode for custom queries."""
    print("\n🎮 Interactive Query Mode")
    print("========================")
    print("Enter JSON queries (or 'quit' to exit):")
    print("Query Examples:")
    print('  {"type": "inventory"}')
    print('  {"type": "position"}')
    print('  {"type": "blocks", "range": 5}')
    print('  {"type": "entities", "range": 10}')
    print('  {"type": "world_info"}')
    print("\nAction Examples:")
    print('  {"type": "send_chat", "message": "Hello World!"}')
    print('  {"type": "drop_item", "slot": 0}')
    print('  {"type": "drop_item", "name": "dirt"}')
    print('  {"type": "rotate", "yaw": 90, "pitch": 0}')
    print()
    
    client = GameQueryClient()
    
    while True:
        try:
            query_input = input("Query> ").strip()
            
            if query_input.lower() in ['quit', 'exit', 'q']:
                print("👋 Goodbye!")
                break
            
            if not query_input:
                continue
            
            try:
                query = json.loads(query_input)
                response = client.send_query(query)
                
                if response:
                    print(json.dumps(response, indent=2))
                else:
                    print("❌ No response received")
                    
            except json.JSONDecodeError:
                print("❌ Invalid JSON format")
                
        except KeyboardInterrupt:
            print("\n👋 Goodbye!")
            break
        except EOFError:
            print("\n👋 Goodbye!")
            break

if __name__ == "__main__":
    if len(sys.argv) > 1:
        if sys.argv[1] == "interactive":
            interactive_mode()
        elif sys.argv[1] == "demo":
            action_demo()
        else:
            print("Usage: python test_queries.py [interactive|demo]")
    else:
        main()
        
        # Ask if user wants interactive mode or demo
        try:
            print("\nWhat would you like to do next?")
            print("1. Interactive mode (send custom queries)")
            print("2. Action demo (demonstrate new features)")
            print("3. Exit")
            
            choice = input("Enter choice (1-3): ").strip()
            
            if choice == "1":
                interactive_mode()
            elif choice == "2":
                action_demo()
            elif choice == "3":
                print("👋 Goodbye!")
            else:
                print("Invalid choice. Goodbye!")
                
        except (KeyboardInterrupt, EOFError):
            print("\n👋 Goodbye!")
            

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "interactive":
        interactive_mode()
    else:
        main()

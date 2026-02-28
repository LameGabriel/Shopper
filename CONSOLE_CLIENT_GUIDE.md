# Console Client Conversion Guide

## Overview

This guide explains how to convert the ShopNavigator Fabric mod into a console/headless client that can run without booting up the full Minecraft game client.

## Current Architecture

The ShopNavigator is currently a **Fabric mod** that requires:
- Full Minecraft client running
- GUI rendering and screen interactions
- Player keyboard input (K, L, J keys)
- Client-side tick events
- Access to Minecraft's rendering pipeline

**Key Dependencies:**
```java
net.minecraft.client.MinecraftClient
net.minecraft.client.gui.screen.ingame.HandledScreen
net.minecraft.screen.GenericContainerScreenHandler
net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
```

## Conversion Approaches

### Option 1: Headless Minecraft Client (Easier)

Run Minecraft in headless mode without rendering:

**Advantages:**
- Minimal code changes required
- Keeps existing logic intact
- Uses official Minecraft client code

**Implementation:**
1. Use Xvfb (X Virtual Frame Buffer) on Linux:
   ```bash
   Xvfb :99 -screen 0 1024x768x24 &
   export DISPLAY=:99
   java -jar minecraft.jar
   ```

2. Or use Java's headless mode:
   ```bash
   java -Djava.awt.headless=true -jar minecraft.jar
   ```

3. Add console commands instead of key bindings:
   - Create a command listener thread
   - Read from System.in or socket
   - Trigger actions programmatically

**Code Changes:**
```java
// Replace key bindings with command interface
public class ConsoleCommandHandler extends Thread {
    public void run() {
        Scanner scanner = new Scanner(System.in);
        while (running) {
            String command = scanner.nextLine();
            switch(command.toLowerCase()) {
                case "shop":
                    ShopNavigatorClient.startShopping();
                    break;
                case "craft":
                    ShopNavigatorClient.startCrafting();
                    break;
                case "stop":
                    ShopNavigatorClient.forceStop();
                    break;
            }
        }
    }
}
```

### Option 2: Minecraft Bot Framework (Major Rewrite)

Use a bot library to connect to Minecraft servers without the client.

**Recommended Libraries:**

1. **MCProtocolLib** (Java)
   - https://github.com/GeyserMC/MCProtocolLib
   - Implements Minecraft protocol
   - Handles authentication, packets, etc.

2. **Mineflayer** (Node.js)
   - https://github.com/PrismarineJS/mineflayer
   - Full-featured bot framework
   - Would require porting to JavaScript

**Major Changes Required:**

#### 1. Replace MinecraftClient
```java
// OLD (Fabric Mod)
MinecraftClient client = MinecraftClient.getInstance();
PlayerInventory inventory = client.player.getInventory();

// NEW (Bot Framework)
MinecraftProtocol protocol = new MinecraftProtocol("username");
Client client = new Client("server.address", 25565, protocol);
BotInventory inventory = new BotInventory(client);
```

#### 2. Replace GUI Interactions with Packets
```java
// OLD (GUI-based)
GenericContainerScreenHandler handler = getGenericContainerIfOpen(client);
client.interactionManager.clickSlot(
    handler.syncId,
    slotId,
    0,
    SlotActionType.PICKUP,
    client.player
);

// NEW (Packet-based)
client.send(new ServerboundContainerClickPacket(
    windowId,
    stateId,
    slotId,
    button,
    actionType,
    carriedItem,
    changedSlots
));

// Listen for packet responses
client.addListener(new PacketAdapter() {
    @Override
    public void packetReceived(PacketReceivedEvent event) {
        if (event.getPacket() instanceof ClientboundContainerSetContentPacket) {
            // Update internal inventory state
            handleInventoryUpdate((ClientboundContainerSetContentPacket) event.getPacket());
        }
    }
});
```

#### 3. Replace Screen Detection with Packet Tracking
```java
// OLD (Screen-based)
if (client.currentScreen instanceof GenericContainerScreen) {
    String title = handler.getTitle().getString();
    if (title.contains("Shop")) {
        // Process shop
    }
}

// NEW (Packet-based)
private String currentWindowTitle = null;
private int currentWindowId = -1;

client.addListener(new PacketAdapter() {
    public void packetReceived(PacketReceivedEvent event) {
        if (event.getPacket() instanceof ClientboundOpenScreenPacket) {
            ClientboundOpenScreenPacket packet = (ClientboundOpenScreenPacket) event.getPacket();
            currentWindowId = packet.getContainerId();
            currentWindowTitle = packet.getTitle().getString();
            
            if (currentWindowTitle.contains("Shop")) {
                handleShopWindow();
            }
        }
    }
});
```

#### 4. Replace Tick Events with Thread-based Loop
```java
// OLD (Fabric tick event)
ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);

// NEW (Thread-based)
public class BotMainLoop extends Thread {
    private static final int TICK_RATE_MS = 50; // 20 TPS
    
    public void run() {
        while (running) {
            long startTime = System.currentTimeMillis();
            
            // Process state machine
            tickStateMachine();
            
            // Sleep to maintain tick rate
            long elapsed = System.currentTimeMillis() - startTime;
            long sleepTime = Math.max(0, TICK_RATE_MS - elapsed);
            Thread.sleep(sleepTime);
        }
    }
}
```

#### 5. Create Console Interface
```java
public class ConsoleInterface {
    public static void main(String[] args) {
        System.out.println("ShopNavigator Bot v1.0");
        System.out.println("Connecting to server...");
        
        // Initialize bot
        ShopNavigatorBot bot = new ShopNavigatorBot("username", "password");
        bot.connect("server.address", 25565);
        
        // Start command interface
        Scanner scanner = new Scanner(System.in);
        System.out.println("Ready. Commands: shop, craft, stop, status, config");
        
        while (bot.isRunning()) {
            System.out.print("> ");
            String command = scanner.nextLine().trim();
            
            switch (command.toLowerCase()) {
                case "shop":
                    bot.startShopping();
                    break;
                case "craft":
                    bot.startCrafting();
                    break;
                case "stop":
                    bot.stop();
                    break;
                case "status":
                    bot.printStatus();
                    break;
                case "config":
                    bot.reloadConfig();
                    break;
                default:
                    System.out.println("Unknown command: " + command);
            }
        }
    }
}
```

## Required Code Changes Summary

### High-Level Architecture

```
OLD (Fabric Mod):
┌─────────────────────┐
│  Minecraft Client   │
│   ┌─────────────┐   │
│   │ ShopNav Mod │   │
│   │  - GUI Hook │   │
│   │  - Keybinds │   │
│   │  - Screens  │   │
│   └─────────────┘   │
└─────────────────────┘
         │
         ▼
  Minecraft Server

NEW (Console Bot):
┌─────────────────────┐
│   Console App       │
│  ┌──────────────┐   │
│  │ ShopNav Bot  │   │
│  │  - Packets   │   │
│  │  - Commands  │   │
│  │  - State     │   │
│  └──────────────┘   │
└─────────────────────┘
         │
         ▼
  Minecraft Server
```

### Files to Change

1. **Remove:**
   - All Fabric mod dependencies
   - GUI/Screen handling code
   - KeyBinding code
   - ClientTickEvents

2. **Replace:**
   - `MinecraftClient` → `BotClient`
   - Screen handlers → Packet handlers
   - Tick events → Thread loop
   - Key bindings → Console commands

3. **Add:**
   - MCProtocolLib dependency
   - Packet listeners
   - Console command parser
   - Main method/entry point
   - Inventory state tracking

4. **Keep (mostly unchanged):**
   - State machine logic
   - Shopping algorithm
   - Crafting logic
   - Configuration system
   - Math/calculation code

### Estimated Effort

- **Lines of Code Changed:** ~60-80%
- **Time Estimate:** 40-80 hours for experienced developer
- **Difficulty:** High (requires understanding Minecraft protocol)

## Step-by-Step Conversion Process

### Phase 1: Setup New Project (4-8 hours)

1. Create new Gradle/Maven project
2. Add MCProtocolLib dependency:
   ```gradle
   dependencies {
       implementation 'com.github.steveice10:mcprotocollib:1.20.2-1'
   }
   ```
3. Create main entry point
4. Set up basic server connection

### Phase 2: Core Bot Infrastructure (8-16 hours)

1. Implement bot client wrapper around MCProtocolLib
2. Create inventory state tracking system
3. Implement packet listeners for:
   - Window open/close
   - Slot updates
   - Chat messages
4. Create console command interface

### Phase 3: Port Shopping Logic (12-20 hours)

1. Convert GUI-based shop navigation to packet-based
2. Implement slot clicking via packets
3. Port page navigation logic
4. Handle quantity selection
5. Test shopping flow end-to-end

### Phase 4: Port Crafting Logic (12-20 hours)

1. Convert crafting table interactions to packets
2. Implement grid filling via packets
3. Port conversion operations (blocks→ingots→nuggets)
4. Implement retry/recovery logic
5. Test crafting flow end-to-end

### Phase 5: Testing & Polish (4-8 hours)

1. Test multi-batch scenarios
2. Test error recovery
3. Add logging and status monitoring
4. Performance optimization
5. Documentation

## Alternative: Hybrid Approach

Keep the mod but add remote control:

```java
// Add HTTP/Socket API to existing mod
public class RemoteControlServer extends Thread {
    private ServerSocket server;
    
    public void run() {
        server = new ServerSocket(8080);
        while (running) {
            Socket client = server.accept();
            BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream())
            );
            String command = in.readLine();
            
            // Execute command in Minecraft thread
            MinecraftClient.getInstance().execute(() -> {
                handleCommand(command);
            });
        }
    }
}
```

Then control via curl/scripts:
```bash
curl http://localhost:8080/shop
curl http://localhost:8080/craft
curl http://localhost:8080/status
```

## Resources

### Libraries & Tools
- **MCProtocolLib:** https://github.com/GeyserMC/MCProtocolLib
- **Minecraft Protocol Docs:** https://wiki.vg/Protocol
- **Mineflayer (Node.js):** https://github.com/PrismarineJS/mineflayer

### Learning Resources
- **Minecraft Protocol Guide:** https://wiki.vg/
- **Packet Inspector:** https://github.com/Pokechu22/Burger
- **Bot Examples:** https://github.com/topics/minecraft-bot

## Conclusion

Converting to a console client is **possible but requires significant effort**. The recommended approach depends on your goals:

- **Quick Solution:** Use headless Minecraft with Xvfb
- **Full Autonomy:** Rewrite using MCProtocolLib (40-80 hours)
- **Remote Control:** Add HTTP API to existing mod (4-8 hours)

For most use cases, the **headless Minecraft** or **HTTP API** approaches provide the best effort-to-value ratio while maintaining the existing, tested codebase.

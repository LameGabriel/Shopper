# GTS Auto-Buyer Implementation Guide

## Overview

This document describes the implementation of the GTS (Global Trade Station) auto-buyer feature for the ShopNavigator mod.

## Requirements

1. **Keybind**: Press "I" to toggle the GTS auto-buyer on/off
2. **Chat Monitoring**: Detect GTS listings from chat messages
3. **Price Filtering**: Only buy items priced at $1,000 or less (configurable)
4. **Safety Limit**: Never buy items over $1,500 (hard cap)
5. **Instant Purchase**: Automatically buy qualifying items immediately
6. **GUI Integration**: Works with /gts command and clicking chat messages

## Chat Message Format

```
GTS: <seller> listed <itemName> for $<price>
```

Example:
```
GTS: Soggymen listed Remoraid ♂ (Shiny) for $1,000
```

## Implementation Components

### 1. Configuration

Add to `ShopNavigatorConfig`:

```java
// GTS Auto-Buyer settings
public boolean gtsAutoBuyEnabled = false;
public int gtsMaxPrice = 1000;        // Auto-buy items at or below this price
public int gtsHardCap = 1500;         // Never buy above this price (safety)
public long gtsCooldownMs = 500;      // Delay between actions
public String gtsCommand = "gts";     // Command to open GTS
```

### 2. Chat Pattern Matching

```java
// GTS: <seller> listed <item> for $<price>
private static final Pattern GTS_LISTING_PATTERN = 
    Pattern.compile("GTS:\\s*([\\w]+)\\s+listed\\s+(.+?)\\s+for\\s+\\$([\\d,]+)");
```

### 3. GTS State Machine

```java
private enum GTSState {
    IDLE,              // Waiting for GTS listing
    OPENING_GTS,       // Executing /gts command
    WAITING_FOR_GUI,   // Waiting for GTS GUI to open
    FINDING_ITEM,      // Scanning GTS GUI for the item
    CONFIRMING_BUY,    // Clicking confirm purchase button
    DONE,              // Purchase complete
    FAILED             // Error occurred
}
```

### 4. Instance Variables

```java
// GTS Auto-Buyer state
private GTSState gtsState = GTSState.IDLE;
private KeyBinding gtsToggleKey;
private boolean gtsEnabled = false;
private String gtsTargetItem = "";
private int gtsTargetPrice = 0;
private String gtsTargetSeller = "";
private long gtsNextActionMs = 0;
```

### 5. Chat Message Listener

Need to add Fabric's message receive event:

```java
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

// In onInitializeClient()
ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
    if (!overlay && CONFIG.gtsAutoBuyEnabled && gtsEnabled) {
        onChatMessage(message.getString());
    }
});
```

### 6. Core Logic Flow

```
Chat Message Received
    ↓
Parse with GTS_LISTING_PATTERN
    ↓
Extract: seller, item, price
    ↓
Check: price <= gtsMaxPrice AND price <= gtsHardCap
    ↓
If YES: Store target info, execute /gts
    ↓
Wait for GTS GUI to open
    ↓
Scan GUI slots for matching item
    ↓
Click item to open purchase confirmation
    ↓
Verify price in confirmation GUI
    ↓
Click "Confirm Purchase" button
    ↓
DONE
```

### 7. Safety Checks

1. **Double Price Check**: Verify price both in chat and in GUI
2. **Hard Cap**: Never exceed $1,500 regardless of config
3. **Cooldown**: Prevent spam buying
4. **Toggle State**: Only active when enabled
5. **Balance Check**: Could add money check (optional)

### 8. GUI Interaction

GTS GUI Structure (estimated):
- Generic container with item slots
- Items displayed in grid
- Click item → Opens purchase confirmation
- Confirmation GUI has price display and confirm button

Need to:
1. Identify GTS GUI by title/screen type
2. Scan slots for matching item name
3. Parse price from item lore/description
4. Find and click confirm button

### 9. Keybinding Registration

```java
gtsToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
    "key.shopnavigator.gtstoggle",
    InputUtil.Type.KEYSYM,
    GLFW.GLFW_KEY_I,
    "category.shopnavigator"
));
```

### 10. Toggle Logic

```java
// In onEndTick()
while (gtsToggleKey.wasPressed()) {
    gtsEnabled = !gtsEnabled;
    String status = gtsEnabled ? "ENABLED" : "DISABLED";
    msg(client, "GTS Auto-Buyer: " + status + " (max price: $" + CONFIG.gtsMaxPrice + ")");
}
```

## Implementation Steps

### Phase 1: Basic Structure (30 min)
1. Add GTS config fields
2. Add GTS state enum
3. Add instance variables
4. Register keybinding

### Phase 2: Chat Listener (30 min)
1. Add message receive event
2. Implement pattern matching
3. Extract seller, item, price
4. Price validation logic

### Phase 3: State Machine (1 hour)
1. Implement OPENING_GTS state
2. Implement WAITING_FOR_GUI state
3. Implement FINDING_ITEM state
4. Implement CONFIRMING_BUY state

### Phase 4: GUI Interaction (1 hour)
1. Identify GTS GUI
2. Scan slots for item
3. Parse price from lore
4. Click purchase button

### Phase 5: Testing & Polish (30 min)
1. Test with various prices
2. Test safety limits
3. Test toggle functionality
4. Add logging/debugging

## Code Example

```java
private void handleGTSListing(String message) {
    Matcher m = GTS_LISTING_PATTERN.matcher(message);
    if (m.find()) {
        String seller = m.group(1);
        String item = m.group(2);
        String priceStr = m.group(3).replace(",", "");
        int price = Integer.parseInt(priceStr);
        
        // Safety checks
        if (price > CONFIG.gtsHardCap) {
            msg(client, "GTS: Item too expensive ($" + price + " > $" + CONFIG.gtsHardCap + ")");
            return;
        }
        
        if (price <= CONFIG.gtsMaxPrice) {
            gtsTargetSeller = seller;
            gtsTargetItem = item;
            gtsTargetPrice = price;
            gtsState = GTSState.OPENING_GTS;
            msg(client, "GTS: Auto-buying " + item + " from " + seller + " for $" + price);
            sendCommand(client, CONFIG.gtsCommand);
        }
    }
}
```

## Configuration Example

```json
{
  "gtsAutoBuyEnabled": true,
  "gtsMaxPrice": 1000,
  "gtsHardCap": 1500,
  "gtsCooldownMs": 500,
  "gtsCommand": "gts"
}
```

## User Experience

1. User presses "I" to enable GTS auto-buyer
2. Chat shows: "GTS Auto-Buyer: ENABLED (max price: $1,000)"
3. GTS listing appears in chat: "GTS: Player1 listed Pikachu for $800"
4. Mod instantly executes /gts command
5. Scans GUI, finds Pikachu, clicks it
6. Confirms price is $800, clicks purchase
7. Chat shows: "GTS: Purchased Pikachu for $800"
8. Mod returns to idle state, ready for next listing

## Future Enhancements

1. **Item Whitelist/Blacklist**: Only buy specific Pokemon/items
2. **Max Quantity**: Limit how many items to buy total
3. **Balance Check**: Don't buy if insufficient funds
4. **Notification Sound**: Play sound on successful purchase
5. **Statistics**: Track total spent, items bought
6. **GUI Feedback**: Overlay showing auto-buyer status

## Testing Checklist

- [ ] Keybind "I" toggles feature on/off
- [ ] Chat messages are parsed correctly
- [ ] Prices are extracted correctly (with commas)
- [ ] Items at exactly $1,000 are purchased
- [ ] Items at $1,001 are NOT purchased
- [ ] Items over $1,500 are NEVER purchased
- [ ] /gts command executes correctly
- [ ] GUI opens and item is found
- [ ] Purchase confirmation works
- [ ] Cooldown prevents spam
- [ ] Multiple listings are handled correctly
- [ ] Toggle persists across sessions (or doesn't, by design)

## Notes

- The feature should be disabled by default (gtsAutoBuyEnabled = false)
- Toggle key "I" does NOT persist (resets on restart for safety)
- Hard cap of $1,500 cannot be configured (safety feature)
- All purchases are logged to chat for transparency

package com.gabriel.shopnavigator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopNavigatorClient implements ClientModInitializer {

    // Runtime config (loaded from json, hot-reloadable)
    private static ShopNavigatorConfig CONFIG = ShopNavigatorConfig.load();

    private static final Pattern PAGE_PATTERN = Pattern.compile("(?i)\\(\\s*page\\s*(\\d+)\\s*\\)");

    // Cached item constants for performance (avoids repeated registry lookups)
    private static final Item IRON_BLOCK = Items.IRON_BLOCK;
    private static final Item IRON_INGOT = Items.IRON_INGOT;
    private static final Item IRON_NUGGET = Items.IRON_NUGGET;
    private static final Item NOTE_BLOCK = Items.NOTE_BLOCK;

    private static final int SLOT_START = 10; // First inventory slot after grid
    private static final int MAX_STACK_SIZE = 64;
    private static final int INGOTS_PER_BLOCK = 9;
    private static final int NUGGETS_PER_INGOT = 9;
    private static final double NUGGET_TO_INGOT = 1.0 / 9.0; // 0.111111...
    private static final double INGOT_COST_PER_CRAFT = 46.0 / 9.0; // ~5.111 ingots per craft

    // Crafting grid constants (3x3 crafting table). Slot 0 is result in ScreenHandler.
    private static final int OUT_SLOT = 0;
    private static final int[] INGOT_SLOTS = {2, 4, 6, 7, 9};
    private static final int NUGGET_SLOT = 5;
    private static final int NOTE_SLOT = 8;
    private static final int MIN_ACTION_COOLDOWN_TICKS = 1; // maximum speed for crafting operations
    
    // Server desync prevention: slow down processing starting at this batch index
    private static final int DESYNC_PREVENTION_BATCH_THRESHOLD = 14;
    private static final int BATCH_DELAY_MULTIPLIER = 3; // multiply delays by this factor for batches >= threshold
    private static final int FAST_FILL_SLOTS_PER_TICK = 5; // number of slots to fill per tick for batches < threshold
    private static final int SLOW_FILL_SLOTS_PER_TICK = 1; // number of slots to fill per tick for batches >= threshold
    
    // Precomputed optimal block/nugget conversions per batch (64 items per batch).
    // Format: {crafts, blocksToBreak, ingotsToNuggets}
    // For 64 crafts: 320 ingots + 64 nuggets needed. 64 nuggets = 8 ingots, so 328 ingots total = 37 blocks
    private static final int[][] BATCH_PLAN = new int[][]{
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8},
            {64, 37, 8}
    };

    // ============================================================================
    // ENUMS - State Machine & Operation Types
    // ============================================================================

    private enum State {
        IDLE,
        SEND_SHOP,
        WAIT_FOR_LISTING_6ROWS,
        SCAN_PAGE_FOR_ITEM,
        CLICK_NEXT_PAGE,
        WAIT_PAGE_CHANGE,
        WAIT_FOR_QUANTITY_3ROWS,
        CLICK_QUANTITY,
        DONE,
        FAILED
    }
    
    private enum GTSState {
        IDLE,
        OPENING_GTS,
        WAITING_FOR_GUI,
        FINDING_ITEM,
        VERIFYING_PRICE,  // Verify price from GUI before confirming
        CONFIRMING_BUY,
        DONE
    }

    // ============================================================================
    // INSTANCE FIELDS - State & Configuration
    // ============================================================================

    // Shopping state machine
    private State state = State.IDLE;
    private State lastState = State.IDLE;
    private long lastStateChangeMs = 0;
    private int stateTimeoutRetries = 0;
    private static final long STATE_TIMEOUT_MS = 7000; // 7 seconds (reduced from 10 for faster stuck detection)
    private static final int MAX_STATE_TIMEOUT_RETRIES = 3;

    private int lastPageNumber = -1;
    private long nextActionAtMs = 0;
    private String lastAction = "";
    private int planIndex = 0;
    private int activeQuantity = 16;
    private int activeRemaining = 16;
    private int currentStage = 1;
    private String currentTargetItemId = "minecraft:stone_bricks";
    private String currentShopCommand = "shop";
    private int[] currentPlanQuantities = new int[]{16};
    private boolean loggedMissingStageItems = false;
    private boolean currentUsesPagination = true;

    private KeyBinding toggleKey;
    private KeyBinding craftAutoKey;
    private KeyBinding craftTableKey;
    private KeyBinding forceStopKey;
    private KeyBinding gtsToggleKey;  // New: GTS auto-buyer toggle
    
    private boolean craftAwaiting = false;
    private int craftAwaitTicks = 0;
    private boolean craftRunning = false;
    private int craftTickCooldown = 0;
    private int craftCrafted = 0;
    private int craftTarget = 0;
    private int placeFailStreak = 0;
    private net.minecraft.recipe.RecipeEntry<?> cachedMetronomeRecipe = null;
    private int recipeBookPendingTicks = 0;
    private int recipeBookPrevCount = 0;
    private int recipeBookAttempts = 0;
    private Identifier lastWorldKey = null;
    private boolean queueCraftAfterClose = false;
    private boolean queueSellAfterClose = false;
    private boolean queueShopAfterDelay = false;
    private boolean craftStartedOnce = false;

    private enum LoopPhase {
        IDLE, SHOPPING, WAIT_CLOSE_SHOP, CRAFTING, WAIT_CLOSE_CRAFT, DELAY
    }

    private LoopPhase loopPhase = LoopPhase.IDLE;
    private long phaseReadyAtMs = 0;

    private void forceCloseScreen(MinecraftClient client) {
        if (client == null) return;
        client.execute(() -> {
            if (client.player != null) client.player.closeHandledScreen();
            client.setScreen(null);
        });
    }
    // Grid-batch crafting state
    private boolean gridLoaded = false;
    private int gridBatchTarget = 0;
    private long gridReadyAtMs = 0;
    // Post-grid delay (ms) before crafting starts
    private long postGridDelayMs = 500; // default, can be overridden by config
    // Throttle placement between recipe slots to avoid spamming the server
    private long perSlotPlaceDelayMs = 200; // ms between placing into different slots
    private long lastSlotPlaceAtMs = 0;
    private int craftBatchIndex = 0;
    // Grid filling state - for non-blocking incremental filling
    private int gridFillStep = 0; // 0=not started, 1-5=ingot slots, 6=nugget, 7=note block, 8=done
    private int gridFillNeed = 0; // number of items needed per slot
    private int planBlocksRemaining = 0;
    private int planIngotsToNuggetsRemaining = 0;
    private final ArrayDeque<ConversionOp> conversionQueue = new ArrayDeque<>();
    private ConversionOp pendingConversion = null;
    private int pendingBeforeBlocks = 0;
    private int pendingBeforeIngots = 0;
    private int pendingBeforeNuggets = 0;
    private long pendingSinceMs = 0;
    private int pendingRetries = 0;
    private static final int BLOCKS_TO_INGOTS_UNITS_PER_OP = 64; // increased from 14 to support larger batch sizes
    private static final int INGOTS_TO_NUGGETS_UNITS_PER_OP = 64; // increased from 14 to support larger batch sizes
    private long craftFinishAtMs = 0; // tracks when post-craft delay expires
    
    // GTS Auto-Buyer state
    private GTSState gtsState = GTSState.IDLE;
    private String gtsTargetSeller = "";
    private String gtsTargetItem = "";
    private int gtsTargetPrice = 0;
    private long gtsNextActionMs = 0;
    private static final Pattern GTS_LISTING_PATTERN = Pattern.compile("GTS:\\s*([\\w]+)\\s+listed\\s+(.+?)\\s+for\\s+\\$([\\d,]+)");

    private enum ConversionKind {
        BREAK_BLOCK_TO_INGOTS,
        INGOT_TO_NUGGETS
    }

    private static class ConversionOp {
        final ConversionKind kind;
        final int units;

        ConversionOp(ConversionKind kind, int units) {
            this.kind = kind;
            this.units = units;
        }
    }

    private void stopCrafting(MinecraftClient client, String reason) {
        craftAwaiting = false;
        craftRunning = false;
        craftTickCooldown = 0;
        craftCrafted = 0;
        craftTarget = 0;
        placeFailStreak = 0;
        queueCraftAfterClose = false;
        queueSellAfterClose = false;
        queueShopAfterDelay = false;

        gridLoaded = false;
        gridBatchTarget = 0;
        craftBatchIndex = 0;
        gridFillStep = 0; // reset grid fill state
        planBlocksRemaining = 0;
        planIngotsToNuggetsRemaining = 0;
        conversionQueue.clear();
        pendingConversion = null;
        pendingRetries = 0;
        craftFinishAtMs = 0;
        stashGridAndCursor(client);
        forceCloseScreen(client);
        nextActionAtMs = System.currentTimeMillis() + 1000;
        msg(client, "AutoCraft: stopped (" + reason + ")");
    }
    private int computeCraftsPossible(MinecraftClient client) {
        recalcInventory(client, true); // include grid contents for resource math only
        double ingotEquiv = ironBlocks * INGOTS_PER_BLOCK + ingots + nuggets * NUGGET_TO_INGOT;
        int craftsByIron = (int) (ingotEquiv * INGOTS_PER_BLOCK / INGOT_COST_PER_CRAFT);
        return Math.min(noteBlocks, craftsByIron);
    }

    // ============================================================================
    // LIFECYCLE METHODS - Initialization & Main Tick Loop
    // ============================================================================

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shopnavigator.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                "category.shopnavigator"
        ));
        craftAutoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shopnavigator.craftauto",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                "category.shopnavigator"
        ));
        craftTableKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shopnavigator.crafttable",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "category.shopnavigator"
        ));
        forceStopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shopnavigator.forcestop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.shopnavigator"
        ));
        gtsToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shopnavigator.gtstoggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "category.shopnavigator"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        
        // Register chat message listener for GTS listings
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return; // Ignore overlay messages
            String text = message.getString();
            handleChatMessage(text);
        });
    }
    
    // ============================================================================
    // GTS AUTO-BUYER METHODS
    // ============================================================================
    
    private void handleChatMessage(String text) {
        if (!CONFIG.gtsEnabled || gtsState != GTSState.IDLE) return;
        
        Matcher m = GTS_LISTING_PATTERN.matcher(text);
        if (m.find()) {
            String seller = m.group(1);
            String item = m.group(2);
            int price = Integer.parseInt(m.group(3).replace(",", ""));
            
            // Hard cap - never buy over this price regardless of config
            if (price > CONFIG.gtsHardCap) {
                return;
            }
            
            // Check if price is within configured max
            if (price <= CONFIG.gtsMaxPrice) {
                gtsTargetSeller = seller;
                gtsTargetItem = item;
                gtsTargetPrice = price;
                gtsState = GTSState.OPENING_GTS;
                MinecraftClient client = MinecraftClient.getInstance();
                msg(client, "GTS: Detected " + item + " for $" + price + " - auto-buying...");
            }
        }
    }
    
    private void tickGTSStateMachine(MinecraftClient client) {
        // Check cooldown
        if (System.currentTimeMillis() < gtsNextActionMs) return;
        
        switch (gtsState) {
            case IDLE:
                // Nothing to do
                break;
                
            case OPENING_GTS:
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatCommand(CONFIG.gtsCommand);
                    msg(client, "Sent /" + CONFIG.gtsCommand + " to open GTS");
                }
                gtsState = GTSState.WAITING_FOR_GUI;
                gtsNextActionMs = System.currentTimeMillis() + CONFIG.gtsCooldownMs;
                break;
                
            case WAITING_FOR_GUI:
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    ScreenHandler handler = screen.getScreenHandler();
                    if (handler instanceof GenericContainerScreenHandler) {
                        gtsState = GTSState.FINDING_ITEM;
                        gtsNextActionMs = System.currentTimeMillis() + 200;
                    }
                }
                break;
                
            case FINDING_ITEM:
                if (System.currentTimeMillis() >= gtsNextActionMs) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        ScreenHandler handler = screen.getScreenHandler();
                        if (handler instanceof GenericContainerScreenHandler containerHandler) {
                            // Click slot 9 (first purchasable item in second row)
                            // First row (slots 0-8) contains settings/navigation
                            clickSlot(client, containerHandler, 9);
                            msg(client, "GTS: Clicking slot 9 to select item");
                            gtsState = GTSState.VERIFYING_PRICE;  // Changed to verify price before buying
                            gtsNextActionMs = System.currentTimeMillis() + CONFIG.gtsCooldownMs;
                        }
                    } else {
                        // GUI closed unexpectedly
                        gtsState = GTSState.DONE;
                    }
                }
                break;

            case VERIFYING_PRICE:
                if (System.currentTimeMillis() >= gtsNextActionMs) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        ScreenHandler handler = screen.getScreenHandler();
                        if (handler instanceof GenericContainerScreenHandler containerHandler) {
                            // Verify the actual price from the GUI before confirming
                            int actualPrice = verifyGTSPrice(client, containerHandler);
                            
                            if (actualPrice <= 0) {
                                // Could not read price - cancel for safety
                                msg(client, "GTS: CANCELLED - Could not verify price in GUI (safety measure)");
                                forceCloseScreen(client);
                                gtsState = GTSState.IDLE;
                            } else if (actualPrice > CONFIG.gtsHardCap) {
                                // Price exceeds hard cap - cancel to prevent scam
                                msg(client, String.format("GTS: CANCELLED - Price $%,d exceeds hard cap of $%,d", actualPrice, CONFIG.gtsHardCap));
                                forceCloseScreen(client);
                                gtsState = GTSState.IDLE;
                            } else {
                                // Price verified and within limit - safe to proceed
                                msg(client, String.format("GTS: Price verified at $%,d (within $%,d limit)", actualPrice, CONFIG.gtsHardCap));
                                gtsState = GTSState.CONFIRMING_BUY;
                                gtsNextActionMs = System.currentTimeMillis() + CONFIG.gtsCooldownMs;
                            }
                        }
                    } else {
                        // GUI closed unexpectedly
                        gtsState = GTSState.DONE;
                    }
                }
                break;

            case CONFIRMING_BUY:
                if (System.currentTimeMillis() >= gtsNextActionMs) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        ScreenHandler handler = screen.getScreenHandler();
                        if (handler instanceof GenericContainerScreenHandler containerHandler) {
                            // Click slot 11 to confirm the purchase
                            clickSlot(client, containerHandler, 11);
                            msg(client, "GTS: Clicking slot 11 to confirm purchase of " + gtsTargetItem + " for $" + gtsTargetPrice);
                            gtsState = GTSState.DONE;
                            gtsNextActionMs = System.currentTimeMillis() + CONFIG.gtsCooldownMs;
                        }
                    } else {
                        // GUI closed unexpectedly
                        gtsState = GTSState.DONE;
                    }
                }
                break;
                
            case DONE:
                // Reset state
                gtsState = GTSState.IDLE;
                gtsTargetSeller = "";
                gtsTargetItem = "";
                gtsTargetPrice = 0;
                break;
        }
    }
    
    /**
     * Verify the actual price from the GTS GUI to prevent scams.
     * Reads the item from slot 9 and parses the price from its display name or lore.
     * @return The actual price in dollars, or -1 if price cannot be determined
     */
    private int verifyGTSPrice(MinecraftClient client, GenericContainerScreenHandler handler) {
        if (handler == null || handler.slots.size() <= 9) {
            return -1;
        }
        
        // Get the item from slot 9 (the item we just clicked)
        ItemStack stack = handler.slots.get(9).getStack();
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        
        // Try to extract price from display name
        if (stack.hasCustomName()) {
            String name = stack.getName().getString();
            int price = extractPrice(name);
            if (price > 0) {
                return price;
            }
        }
        
        // Try to extract price from lore/tooltip
        if (client.player != null) {
            try {
                var tooltip = stack.getTooltip(client.player, net.minecraft.client.item.TooltipContext.BASIC);
                for (var line : tooltip) {
                    String text = line.getString();
                    int price = extractPrice(text);
                    if (price > 0) {
                        return price;
                    }
                }
            } catch (Exception e) {
                // Tooltip extraction failed
            }
        }
        
        return -1; // Could not find price
    }
    
    /**
     * Extract a price value from text, handling various formats like $1,000 or 1000
     * @param text The text to parse
     * @return The price as an integer, or -1 if no price found
     */
    private int extractPrice(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        
        // Remove Minecraft color codes (§x)
        String clean = text.replaceAll("§.", "");
        
        // Look for price patterns: $1,000 or $1000 or 1,000 or 1000
        // Try to match numbers that look like prices
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$?([\\d,]+)");
        java.util.regex.Matcher matcher = pattern.matcher(clean);
        
        while (matcher.find()) {
            String priceStr = matcher.group(1).replace(",", "");
            try {
                int price = Integer.parseInt(priceStr);
                // Sanity check: price should be reasonable (between $1 and $1,000,000)
                if (price >= 1 && price <= 1000000) {
                    return price;
                }
            } catch (NumberFormatException e) {
                // Not a valid number, continue searching
            }
        }
        
        return -1; // No price found
    }
    
    private void onEndTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Stop crafting safely on dimension/world change
        var worldKey = client.world.getRegistryKey().getValue();
        if (craftRunning && lastWorldKey != null && !lastWorldKey.equals(worldKey)) {
            stopCrafting(client, "stopped: world changed");
        }
        lastWorldKey = worldKey;

        // Respect global delay
        if (nextActionAtMs > System.currentTimeMillis()) return;

        // Phase controller
        switch (loopPhase) {
            case SHOPPING -> {
                // shopping state machine runs below
            }
            case WAIT_CLOSE_SHOP -> {
                if (client.currentScreen != null) {
                    forceCloseScreen(client);
                    nextActionAtMs = System.currentTimeMillis() + 1000;
                    return;
                }
                if (System.currentTimeMillis() >= phaseReadyAtMs) {
                    loopPhase = LoopPhase.CRAFTING;
                    craftStartedOnce = false;
                    autoCraftMetronomes(client);
                    nextActionAtMs = System.currentTimeMillis() + 1000;
                }
            }
            case CRAFTING -> {
                if (craftRunning) craftStartedOnce = true;
                if (craftStartedOnce && !craftRunning && !craftAwaiting) {
                    loopPhase = LoopPhase.WAIT_CLOSE_CRAFT;
                    phaseReadyAtMs = System.currentTimeMillis() + 1000;
                    forceCloseScreen(client);
                    nextActionAtMs = System.currentTimeMillis() + 1000;
                }
            }
            case WAIT_CLOSE_CRAFT -> {
                if (client.currentScreen != null) {
                    forceCloseScreen(client);
                    nextActionAtMs = System.currentTimeMillis() + 1000;
                    return;
                }
                if (System.currentTimeMillis() >= phaseReadyAtMs) {
                    // Check if more metronomes can be crafted with remaining materials
                    // This properly accounts for iron blocks, ingots, nuggets AND note blocks
                    // Returns 0 if insufficient materials, otherwise the number of craftable metronomes
                    int craftsPossible = computeCraftsPossible(client);
                    
                    if (craftsPossible > 0) {
                        // Materials exist for at least one more craft - restart crafting instead of selling
                        msg(client, String.format("Restarting crafting for %d more metronome(s)", craftsPossible));
                        loopPhase = LoopPhase.CRAFTING;
                        autoCraftMetronomes(client);
                    } else {
                        // No materials - proceed with selling
                        sendSellAllCommand(client);
                        loopPhase = LoopPhase.DELAY;
                        phaseReadyAtMs = System.currentTimeMillis() + 1000;
                    }
                    nextActionAtMs = System.currentTimeMillis() + 1000;
                }
            }
            case DELAY -> {
                if (System.currentTimeMillis() >= phaseReadyAtMs && client.currentScreen == null && !craftRunning && !craftAwaiting) {
                    loopPhase = LoopPhase.SHOPPING;
                    start(client);
                }
            }
            case IDLE -> {}
        }

        // Respect post-craft delay before closing screen and selling
        if (queueSellAfterClose && !craftRunning && craftFinishAtMs > System.currentTimeMillis()) {
            return; // still waiting for finish delay
        }

        // If we queued selling after crafting, close any open screen then send /sell all
        // But first check if there are materials remaining that could be crafted
        if (queueSellAfterClose && client.currentScreen == null && !craftRunning && !craftAwaiting) {
            queueSellAfterClose = false;
            
            // Check if more metronomes can be crafted with remaining materials
            // This properly accounts for iron blocks, ingots, nuggets AND note blocks
            // Returns 0 if insufficient materials, otherwise the number of craftable metronomes
            int craftsPossible = computeCraftsPossible(client);
            
            if (craftsPossible > 0) {
                // Materials exist for at least one more craft - try to craft instead of selling
                msg(client, String.format("Found materials for %d more metronome(s) - attempting to craft instead of selling", craftsPossible));
                autoCraftMetronomes(client);
            } else {
                // No materials - proceed with selling
                sendSellAllCommand(client);
            }
            
            // wait 1 second before any new loop/command
            nextActionAtMs = System.currentTimeMillis() + 1000;
        }

        while (toggleKey.wasPressed()) {
            if (loopPhase == LoopPhase.IDLE) {
                loopPhase = LoopPhase.SHOPPING;
                start(client);
            } else {
                stop(client, "Stopped by user.");
                stopCrafting(client, "Stopped by user.");
                loopPhase = LoopPhase.IDLE;
            }
        }

        while (craftAutoKey.wasPressed()) {
            if (craftRunning || craftAwaiting) {
                stopCrafting(client, "toggle off");
            } else {
                autoCraftMetronomes(client);
            }
        }

        while (craftTableKey.wasPressed()) {
            if (craftRunning || craftAwaiting) {
                stopCrafting(client, "toggle off");
            } else {
                craftAwaiting = false;
                craftRunning = false;
                craftCrafted = 0;
                craftTarget = Math.min(CONFIG.craftTargetCount, CONFIG.craftHardCap);
                if (client.player != null && client.player.currentScreenHandler != null && client.player.currentScreenHandler.slots.size() >= 10) {
                    startCraftRun(client);
                } else {
                    msg(client, "AutoCraft: open a crafting table first, then press F6.");
                }
            }
        }

        if (craftAwaiting) {
            if (client.currentScreen instanceof HandledScreen<?> && client.player.currentScreenHandler != null && client.player.currentScreenHandler.slots.size() >= 10) {
                craftAwaiting = false;
                craftAwaitTicks = 0;
                startCraftRun(client);
            } else if (craftAwaitTicks-- <= 0) {
                craftAwaiting = false;
                msg(client, "AutoCraft: /craft did not open in time.");
            }
        }

        while (forceStopKey.wasPressed()) {
            stop(client, "Force stop");
            stopCrafting(client, "Force stop");
            queueCraftAfterClose = false;
            queueSellAfterClose = false;
            queueShopAfterDelay = false;
            craftFinishAtMs = 0;
            loopPhase = LoopPhase.IDLE;
        }
        
        // GTS Auto-Buyer toggle
        while (gtsToggleKey.wasPressed()) {
            CONFIG.gtsEnabled = !CONFIG.gtsEnabled;
            String status = CONFIG.gtsEnabled ? "ENABLED" : "DISABLED";
            msg(client, "GTS Auto-Buyer: " + status + " (max price: $" + CONFIG.gtsMaxPrice + ", hard cap: $" + CONFIG.gtsHardCap + ")");
            CONFIG.save();
        }
        
        // Run GTS state machine if enabled
        if (CONFIG.gtsEnabled) {
            tickGTSStateMachine(client);
        }

        if (craftRunning) {
            if (craftTickCooldown > 0) {
                craftTickCooldown--;
            } else {
                tickCraftRun(client);
            }
        }

        if (state == State.IDLE || state == State.DONE || state == State.FAILED) return;

        long now = System.currentTimeMillis();
        
        // Detect if state machine is stuck (no state change for too long)
        if (state != State.IDLE && state != State.DONE && state != State.FAILED) {
            long timeSinceStateChange = now - lastStateChangeMs;
            if (timeSinceStateChange > STATE_TIMEOUT_MS) {
                msg(client, "WARNING: State machine stuck in " + state + " for " + (timeSinceStateChange / 1000) + "s. Attempting recovery...");
                
                if (stateTimeoutRetries >= MAX_STATE_TIMEOUT_RETRIES) {
                    fail(client, "State machine stuck after " + stateTimeoutRetries + " recovery attempts. State: " + state);
                    stateTimeoutRetries = 0;
                    return;
                }
                
                stateTimeoutRetries++;
                forceCloseScreen(client);
                setState(State.SEND_SHOP);
                cooldown(CONFIG.cooldownSendShopMs * 2); // Extra delay after recovery
                msg(client, "Recovery attempt " + stateTimeoutRetries + "/" + MAX_STATE_TIMEOUT_RETRIES + ": Restarting from SEND_SHOP");
                return;
            }
        }
        
        if (now < nextActionAtMs) return;

        try {
            tickStateMachine(client);
        } catch (Throwable t) {
            stop(client, "Error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            state = State.FAILED;
        }
    }

    private void start(MinecraftClient client) {
        lastPageNumber = -1;
        nextActionAtMs = 0;
        currentStage = 1;
        loadStageConfig(currentStage);
        planIndex = 0;
        stateTimeoutRetries = 0; // Reset retry counter when starting fresh shopping
        
        // Calculate initial shopping quantity based on what's already in inventory
        int plannedQuantity = CONFIG.usePlan ? currentPlanQuantities[Math.min(planIndex, currentPlanQuantities.length - 1)] : CONFIG.targetQuantity;
        
        // Check current inventory to avoid buying too many items
        recalcInventory(client);
        int currentlyOwned = 0;
        
        // Determine what item we're shopping for and how much we already have
        Item targetItem = getTargetItem();
        if (targetItem != null) {
            if (targetItem == NOTE_BLOCK) {
                currentlyOwned = noteBlocks;
            } else if (targetItem == IRON_BLOCK) {
                currentlyOwned = ironBlocks;
            } else {
                // For other items, count them in inventory
                currentlyOwned = countItem(client.player.getInventory(), targetItem);
            }
        }
        
        // Adjust quantity based on what we already have
        // Only buy what we don't already have (up to the planned amount for this batch)
        activeQuantity = Math.max(0, plannedQuantity - currentlyOwned);
        activeRemaining = activeQuantity;
        
        loggedMissingStageItems = false;
        stateTimeoutRetries = 0;
        
        if (activeQuantity <= 0) {
            msg(client, "ShopNavigator: Started. Stage " + currentStage + " Target=" + currentTargetItemId + 
                    " - Already have " + currentlyOwned + " items, skipping to crafting.");
            // Skip shopping, go directly to crafting
            done(client, "Already have enough items for stage " + currentStage);
            loopPhase = LoopPhase.WAIT_CLOSE_SHOP;
            phaseReadyAtMs = System.currentTimeMillis() + 1000;
            nextActionAtMs = phaseReadyAtMs;
        } else {
            setState(State.SEND_SHOP);
            msg(client, "ShopNavigator: Started. Stage " + currentStage + " Target=" + currentTargetItemId + 
                    " qty=" + activeQuantity + " (have " + currentlyOwned + ", need " + plannedQuantity + ")" +
                    (CONFIG.usePlan ? " (plan " + currentPlanQuantities.length + " batches)" : ""));
        }
    }

    private void stop(MinecraftClient client, String reason) {
        setState(State.IDLE);
        nextActionAtMs = 0;
        stateTimeoutRetries = 0;
        msg(client, "ShopNavigator: " + reason);
    }

    private void autoPauseOnConversionFailure(MinecraftClient client, String reason) {
        stop(client, "Auto-paused: " + reason);
        stopCrafting(client, "auto-paused: " + reason);
        loopPhase = LoopPhase.IDLE;
        msg(client, "AutoCraft: auto-paused on conversion failure. Press L to resume.");
    }

    private void done(MinecraftClient client, String reason) {
        setState(State.DONE);
        stateTimeoutRetries = 0;
        msg(client, "ShopNavigator: DONE. " + reason);
    }

    private void fail(MinecraftClient client, String reason) {
        setState(State.FAILED);
        stateTimeoutRetries = 0;
        msg(client, "ShopNavigator: FAILED. " + reason);
    }

    private void setState(State newState) {
        if (this.state != newState) {
            this.lastState = this.state;
            this.state = newState;
            this.lastStateChangeMs = System.currentTimeMillis();
        }
    }

    // Close GUI and then start the auto-crafting flow (same as F10) once the GUI is actually closed.
    private void finishShoppingAndCraft(MinecraftClient client) {
        if (client == null) return;
        forceCloseScreen(client);
        queueCraftAfterClose = true; // picked up after screen is null
    }

    private void tickStateMachine(MinecraftClient client) {
        switch (state) {
            case SEND_SHOP -> {
                // Send /shop, then wait for listing GUI
                sendShopCommand(client);
                setState(State.WAIT_FOR_LISTING_6ROWS);
                cooldown(CONFIG.cooldownSendShopMs);
            }

            case WAIT_FOR_LISTING_6ROWS -> {
                GenericContainerScreenHandler g = getGenericContainerIfOpen(client);
                if (g == null) return;

                int rows = g.getRows();
                String title = getHandledTitle(client);

                if (currentUsesPagination) {
                    // Some servers show a 5-row menu first; wait until we reach the 6-row listing.
                    if (rows == 6 && title != null && title.contains(CONFIG.listingTitleMustContain)) {
                        lastPageNumber = parsePageNumber(title);
                        setState(State.SCAN_PAGE_FOR_ITEM);
                        msg(client, "Listing detected. Title=\"" + title + "\" page=" + lastPageNumber);
                        cooldown(CONFIG.cooldownPageMs / 2);
                    }
                } else {
                    // No pagination for this stage: proceed immediately
                    lastPageNumber = 1;
                    setState(State.SCAN_PAGE_FOR_ITEM);
                    msg(client, "Listing detected (no pagination). Title=\"" + title + "\" rows=" + rows);
                    cooldown(CONFIG.cooldownPageMs / 2);
                }
            }

            case SCAN_PAGE_FOR_ITEM -> {
                GenericContainerScreenHandler g = getGenericContainerIfOpen(client);
                if (g == null) return;
                // Scan only the container slots (exclude player inventory)
                ScreenHandler h = client.player.currentScreenHandler;
                Item target = getTargetItem();
                if (target == null) {
                    fail(client, "Invalid target item id: " + currentTargetItemId);
                    return;
                }

                int containerSlots = g.getRows() * 9; // GenericContainer rows * 9
                containerSlots = Math.min(containerSlots, h.slots.size());
                int foundSlot = findTargetInSlots(h, 0, containerSlots, target);

                if (foundSlot != -1) {
                    clickSlot(client, h, foundSlot);
                    msg(client, "Clicked target item at slot " + foundSlot + ". Waiting for quantity selector...");
                    setState(State.WAIT_FOR_QUANTITY_3ROWS);
                    cooldown(CONFIG.cooldownQuantityMs);
                    return;
                }

                if (!loggedMissingStageItems) {
                    loggedMissingStageItems = true;
                    msg(client, "Target not found in container slots; visible slots:");
                    for (int s = 0; s < containerSlots; s++) {
                        ItemStack stack = h.getSlot(s).getStack();
                        msg(client, " slot " + s + ": " + (stack.isEmpty() ? "empty" : stack.getItem() + " x" + stack.getCount()));
                    }
                }

                if (!currentUsesPagination) {
                    fail(client, "Target not found and pagination disabled for this stage.");
                    return;
                }

                // Not found -> click next page (configurable slot) but verify expected item
                if (CONFIG.nextPageSlot >= h.slots.size()) {
                    fail(client, "Next-page slot out of range; item not found.");
                    return;
                }

                ItemStack next = h.getSlot(CONFIG.nextPageSlot).getStack();
                Item expectedNext = CONFIG.nextPageItemId == null ? Items.ARROW : Registries.ITEM.getOrEmpty(Identifier.tryParse(CONFIG.nextPageItemId)).orElse(Items.ARROW);
                if (next.isEmpty() || !next.isOf(expectedNext)) {
                    fail(client, "Next-page item not present at slot " + CONFIG.nextPageSlot + "; item not found.");
                    return;
                }

                // Store current page so we can wait for change
                String title = getHandledTitle(client);
                lastPageNumber = parsePageNumber(title);
                clickSlot(client, h, CONFIG.nextPageSlot);
                setState(State.WAIT_PAGE_CHANGE);
                cooldown(CONFIG.cooldownPageMs);
            }

            case WAIT_PAGE_CHANGE -> {
                GenericContainerScreenHandler g = getGenericContainerIfOpen(client);
                if (g == null) return;
                if (g.getRows() != 6) return;

                String title = getHandledTitle(client);
                int page = parsePageNumber(title);

                // If title parsing fails, fall back to a short delay and rescan (still works).
                if (page == -1) {
                    setState(State.SCAN_PAGE_FOR_ITEM);
                    cooldown(CONFIG.cooldownPageMs);
                    return;
                }

                if (page != lastPageNumber) {
                    lastPageNumber = page;
                    msg(client, "Page changed -> " + page);
                    setState(State.SCAN_PAGE_FOR_ITEM);
                    cooldown(CONFIG.cooldownPageMs / 2);
                }
            }

            case WAIT_FOR_QUANTITY_3ROWS -> {
                GenericContainerScreenHandler g = getGenericContainerIfOpen(client);
                if (g == null) return;

                if (g.getRows() == 3) {
                    setState(State.CLICK_QUANTITY);
                    cooldown(CONFIG.cooldownQuantityMs / 2);
                }
            }

            case CLICK_QUANTITY -> {
                GenericContainerScreenHandler g = getGenericContainerIfOpen(client);
                if (g == null) return;
                if (g.getRows() != 3) return;

                ScreenHandler h = client.player.currentScreenHandler;

                // Search slots 0..26 for TARGET_ITEM with count == TARGET_QUANTITY
                Item target = getTargetItem();
                if (target == null) {
                    fail(client, "Invalid target item id: " + currentTargetItemId);
                    return;
                }
                int selectedAmount = selectBestAvailableQuantity(h, target, activeRemaining);
                if (selectedAmount <= 0) {
                    fail(client, "Could not find quantity option " + activeRemaining + " (or smaller fallback) in selector.");
                    return;
                }

                activeRemaining -= selectedAmount;

                if (activeRemaining > 0) {
                    msg(client, "Partial fill: picked " + selectedAmount + ", remaining " + activeRemaining + " for this batch");
                    // Need to reopen listing and item
                    setState(State.SCAN_PAGE_FOR_ITEM);
                    cooldown(CONFIG.cooldownQuantityMs);
                } else if (CONFIG.usePlan && planIndex + 1 < currentPlanQuantities.length) {
                    planIndex++;
                    activeQuantity = currentPlanQuantities[planIndex];
                    activeRemaining = activeQuantity;
                    loggedMissingStageItems = false;
                    stateTimeoutRetries = 0; // Reset retry counter on successful batch completion
                    msg(client, "Batch " + planIndex + "/" + (currentPlanQuantities.length - 1) + " done; next qty " + activeQuantity + " — reopening shop");
                    setState(State.SEND_SHOP); // reopen in case GUI closed after purchase
                    cooldown(CONFIG.cooldownSendShopMs);
                } else {
                    // Stage completion
                    if (CONFIG.useTwoStagePlan && currentStage == 1) {
                        currentStage = 2;
                        loadStageConfig(currentStage);
                        planIndex = 0;
                        
                        // Check current inventory before stage 2 to avoid buying if already have items
                        recalcInventory(client);
                        int currentlyOwned = 0;
                        Item targetItem = getTargetItem();
                        if (targetItem != null) {
                            if (targetItem == NOTE_BLOCK) {
                                currentlyOwned = noteBlocks;
                            } else if (targetItem == IRON_BLOCK) {
                                currentlyOwned = ironBlocks;
                            } else {
                                currentlyOwned = countItem(client.player.getInventory(), targetItem);
                            }
                        }
                        
                        // For stage 2, check against TOTAL needed across all batches, not just first batch
                        int totalNeeded = 0;
                        for (int qty : currentPlanQuantities) {
                            totalNeeded += qty;
                        }
                        
                        if (currentlyOwned >= totalNeeded) {
                            // Already have enough items for entire stage 2 - go to crafting
                            msg(client, "Stage 1 complete. Stage 2 target=" + currentTargetItemId + 
                                    " - already have enough items (" + currentlyOwned + " >= " + totalNeeded + "), going to crafting");
                            done(client, "All items already available");
                            forceCloseScreen(client);
                            loopPhase = LoopPhase.WAIT_CLOSE_SHOP;
                            phaseReadyAtMs = System.currentTimeMillis() + 1000;
                            nextActionAtMs = phaseReadyAtMs;
                        } else {
                            // Need to shop for stage 2 - start with first batch
                            activeQuantity = currentPlanQuantities[Math.min(planIndex, currentPlanQuantities.length - 1)];
                            activeRemaining = activeQuantity;
                            loggedMissingStageItems = false;
                            stateTimeoutRetries = 0; // Reset retry counter on successful stage transition
                            msg(client, "Stage 1 complete. Switching to Stage 2 target=" + currentTargetItemId + 
                                    " qty=" + activeQuantity + " (have " + currentlyOwned + ", need total " + totalNeeded + ")");
                            setState(State.SEND_SHOP);
                            cooldown(CONFIG.cooldownSendShopMs);
                        }
                    } else {
                        // final stage complete: transition to crafting phase
                        done(client, "Completed final batch.");
                        forceCloseScreen(client);
                        loopPhase = LoopPhase.WAIT_CLOSE_SHOP;
                        phaseReadyAtMs = System.currentTimeMillis() + 1000;
                        nextActionAtMs = phaseReadyAtMs;
                    }
                }
            }

            default -> { /* no-op */ }
        }
    }

    // ===== Helpers =====

    private GenericContainerScreenHandler getGenericContainerIfOpen(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?>)) return null;
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler g)) return null;
        return g;
    }

    private String getHandledTitle(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> hs) {
            return hs.getTitle().getString();
        }
        return null;
    }

    private int parsePageNumber(String title) {
        if (title == null) return -1;
        Matcher m = PAGE_PATTERN.matcher(title);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int findTargetInSlots(ScreenHandler handler, int startInclusive, int endExclusive, Item target) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty() && s.isOf(target)) return i;
        }
        return -1;
    }

    private int findQuantityOption(ScreenHandler handler, int startInclusive, int endExclusive, Item target, int qty) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            if (s.isOf(target) && s.getCount() == qty) return i;
        }
        return -1;
    }

    private int findByNameContains(ScreenHandler handler, int startInclusive, int endExclusive, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            String name = s.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(n)) return i;
        }
        return -1;
    }

    private int countItem(PlayerInventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private void clickSlotBtn(MinecraftClient client, ScreenHandler handler, int slotIndex, int button) {
        // button 0 = left, 1 = right
        client.interactionManager.clickSlot(handler.syncId, slotIndex, button, SlotActionType.PICKUP, client.player);
        cooldown(CONFIG.craftPlaceCooldownMs); // use full craft cooldown for all clicks to prevent desync
    }

    private void clickSlot(MinecraftClient client, ScreenHandler handler, int slotIndex) {
        clickSlotBtn(client, handler, slotIndex, 0);
    }

    private void clickSlotGrid(MinecraftClient client, ScreenHandler handler, int slotIndex, int button) {
        client.interactionManager.clickSlot(handler.syncId, slotIndex, button, SlotActionType.PICKUP, client.player);
        // Slow down for batch 14 onwards to prevent server desync during conversions
        long delay = CONFIG.craftPlaceCooldownMs;
        if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
            delay *= BATCH_DELAY_MULTIPLIER;
        }
        cooldown(delay);
    }

    private void cooldown(long ms) {
        nextActionAtMs = System.currentTimeMillis() + ms;
        lastAction = "Cooldown " + ms + "ms";
    }

    private void debug(MinecraftClient client, String text) {
        if (CONFIG == null || !CONFIG.debugMode) return;
        if (text == null || text.trim().isEmpty()) return; // Skip empty debug messages
        // Debug messages only go to log file, not chat (to avoid spam)
        // Uncomment the line below if you want debug messages in chat:
        // if (client != null && client.player != null) client.player.sendMessage(Text.literal("[DEBUG] " + text), false);
        try {
            Path cfgDir = null;
            try { cfgDir = ShopNavigatorConfig.PATH.getParent(); } catch (Throwable ignored) {}
            if (cfgDir == null) cfgDir = FabricLoader.getInstance().getConfigDir();
            Path log = cfgDir.resolve("shopnavigator-debug.log");
            Files.writeString(log, Instant.now().toString() + " [DEBUG] " + text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private void debugException(MinecraftClient client, Throwable t, String context) {
        if (CONFIG == null || !CONFIG.debugMode) return;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        debug(client, context + ": " + t.getClass().getSimpleName() + " " + t.getMessage());
        try {
            Path cfgDir = null;
            try { cfgDir = ShopNavigatorConfig.PATH.getParent(); } catch (Throwable ignored) {}
            if (cfgDir == null) cfgDir = FabricLoader.getInstance().getConfigDir();
            Path log = cfgDir.resolve("shopnavigator-debug.log");
            Files.writeString(log, Instant.now().toString() + " [EX] " + context + "\n" + trace + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private void debugDumpState(MinecraftClient client, ScreenHandler h, int gridBatchTarget) {
        if (CONFIG == null || !CONFIG.debugMode) return;
        try {
            recalcInventory(client, true);
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("GridTarget=").append(gridBatchTarget).append(" inv: blocks=").append(ironBlocks)
                .append(" ingots=").append(ingots).append(" nuggets=").append(nuggets).append(" notes=").append(noteBlocks).append("\n");
        sb.append("Player inv slots:\n");
        try {
            PlayerInventory inv = client.player.getInventory();
            for (int i = 0; i < Math.min(inv.size(), 36); i++) {
                ItemStack s = inv.getStack(i);
                sb.append(" slot[").append(i).append("]: ").append(s.isEmpty() ? "empty" : s.getItem() + " x" + s.getCount()).append("\n");
            }
        } catch (Throwable ignored) {}
        sb.append("Grid slots 1-9:\n");
        try {
            for (int s = 1; s <= 9; s++) {
                ItemStack g = h.getSlot(s).getStack();
                sb.append(" slot[").append(s).append("]: ").append(g.isEmpty() ? "empty" : g.getItem() + " x" + g.getCount()).append("\n");
            }
        } catch (Throwable ignored) {}
        sb.append("Handler slots 10+ (container -> player map):\n");
        try {
            int limit = Math.min(h.slots.size(), 10 + 40);
            for (int i = 10; i < limit; i++) {
                ItemStack s = h.getSlot(i).getStack();
                sb.append(" slot[").append(i).append("]: ").append(s.isEmpty() ? "empty" : s.getItem() + " x" + s.getCount()).append("\n");
            }
        } catch (Throwable ignored) {}
        debug(client, sb.toString());
    }

    private void sendShopCommand(MinecraftClient client) {
        var nh = client.getNetworkHandler();
        if (nh == null) {
            fail(client, "No network handler; are you in a world?");
            return;
        }

        // 1. Current (1.20.5+/1.21+) name
        try {
            nh.sendCommand(currentShopCommand);
            msg(client, "Sent /" + currentShopCommand + " via sendCommand");
            return;
        } catch (Throwable ignored) {}

        // 2. Older Yarn name
        try {
            nh.sendChatCommand(currentShopCommand);
            msg(client, "Sent /" + currentShopCommand + " via sendChatCommand");
            return;
        } catch (Throwable ignored) {}

        // 3. Raw chat message fallback
        try {
            nh.sendChatMessage("/" + currentShopCommand);
            msg(client, "Sent /" + currentShopCommand + " as chat message");
            return;
        } catch (Throwable t) {
            fail(client, "Could not send /" + currentShopCommand + ". " + t.getClass().getSimpleName());
        }
    }

    private void sendSellAllCommand(MinecraftClient client) {
        var nh = client.getNetworkHandler();
        if (nh == null) return;
        try {
            nh.sendCommand("sell all");
            msg(client, "Sent /sell all");
        } catch (Throwable ignored) {
            try {
                nh.sendChatCommand("sell all");
                msg(client, "Sent /sell all");
            } catch (Throwable ignored2) {
                nh.sendChatMessage("/sell all");
                msg(client, "Sent /sell all");
            }
        }
    }

    // ============================================================================
    // UI & MESSAGING METHODS - Chat, Debug, Logging
    // ============================================================================

    private void msg(MinecraftClient client, String text) {
        if (text == null || text.trim().isEmpty()) return; // Skip empty messages
        if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal(text), false);
    }

    private void setCraftCooldown() {
        int cooldown = Math.max(CONFIG.craftTickCooldown, MIN_ACTION_COOLDOWN_TICKS);
        // Slow down for batch 14 onwards (triple the cooldown)
        if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
            cooldown *= BATCH_DELAY_MULTIPLIER;
        }
        craftTickCooldown = cooldown;
    }


    private Item getTargetItem() {
        Identifier id = Identifier.tryParse(currentTargetItemId);
        if (id == null) return null;
        return Registries.ITEM.getOrEmpty(id).orElse(null);
    }

    /**
     * Pick the best available quantity option: exact match first, then descending fallbacks.
     * Returns the amount actually selected (0 if none).
     */
    private int selectBestAvailableQuantity(ScreenHandler h, Item target, int desired) {
        int slot = findQuantityOption(h, 0, 27, target, desired);
        if (slot != -1) {
            clickSlot(MinecraftClient.getInstance(), h, slot);
            return desired;
        }

        // Try by name patterns (e.g., "x256", "256", "256x", "buy 256")
        String[] needles = new String[]{
                "x" + desired,
                String.valueOf(desired),
                desired + "x",
                "buy " + desired
        };
        for (String needle : needles) {
            slot = findByNameContains(h, 0, 27, needle);
            if (slot != -1) {
                clickSlot(MinecraftClient.getInstance(), h, slot);
                return desired;
            }
        }

        int[] fallbacks = new int[]{256, 128, 64, 32, 16, 8, 4, 2, 1};
        for (int fb : fallbacks) {
            if (fb >= desired) continue; // only smaller amounts
            int s1 = findQuantityOption(h, 0, 27, target, fb);
            if (s1 != -1) {
                clickSlot(MinecraftClient.getInstance(), h, s1);
                return fb;
            }
            int s2 = findByNameContains(h, 0, 27, "x" + fb);
            if (s2 != -1) {
                clickSlot(MinecraftClient.getInstance(), h, s2);
                return fb;
            }
        }
        return 0;
    }

    // === Auto-crafting helpers ===

    // Inventory counts (cached for performance)
    private int ironBlocks = 0;
    private int ingots = 0;
    private int nuggets = 0;
    private int noteBlocks = 0;
    private int emptySlots = 0;
    private int ingotStackRoom = 0;
    private int nuggetStackRoom = 0;
    private long lastInventoryCalcMs = 0; // Track when inventory was last calculated
    private int lastInventoryHash = 0; // Track if inventory changed

    private void recalcInventory(MinecraftClient client) {
        recalcInventory(client, false);
    }

    private void recalcInventory(MinecraftClient client, boolean includeGrid) {
        ironBlocks = ingots = nuggets = noteBlocks = 0;
        emptySlots = 0;
        ingotStackRoom = 0;
        nuggetStackRoom = 0;
        PlayerInventory inv = client.player.getInventory();
        final int invSize = inv.size(); // Cache size
        for (int i = 0; i < invSize; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (s.isOf(IRON_BLOCK)) ironBlocks += s.getCount();
            else if (s.isOf(IRON_INGOT)) {
                ingots += s.getCount();
                ingotStackRoom += Math.max(0, MAX_STACK_SIZE - s.getCount());
            } else if (s.isOf(IRON_NUGGET)) {
                nuggets += s.getCount();
                nuggetStackRoom += Math.max(0, MAX_STACK_SIZE - s.getCount());
            } else if (s.isOf(NOTE_BLOCK)) {
                noteBlocks += s.getCount();
            }
        }
        // Empty slots can hold new stacks
        ingotStackRoom += emptySlots * MAX_STACK_SIZE;
        nuggetStackRoom += emptySlots * MAX_STACK_SIZE;

        if (!includeGrid) return;

        // Optionally include items sitting in the crafting grid when counting resources.
        if (client.player.currentScreenHandler != null && client.player.currentScreenHandler.slots.size() >= 10) {
            ScreenHandler h = client.player.currentScreenHandler;
            for (int s = 1; s <= 9; s++) {
                ItemStack g = h.getSlot(s).getStack();
                if (g.isEmpty()) continue;
                if (g.isOf(IRON_BLOCK)) ironBlocks += g.getCount();
                else if (g.isOf(IRON_INGOT)) ingots += g.getCount();
                else if (g.isOf(IRON_NUGGET)) nuggets += g.getCount();
                else if (g.isOf(NOTE_BLOCK)) noteBlocks += g.getCount();
            }
        }
        lastInventoryCalcMs = System.currentTimeMillis();
    }

    private int findSlotWithItem(ScreenHandler h, Item item) {
        int best = -1;
        int bestCount = 0;
        final int slotCount = h.slots.size(); // Cache size to avoid repeated calls
        // Prefer the fullest stack (up to 64) to avoid scattering partials.
        for (int i = SLOT_START; i < slotCount; i++) { // skip result+grid
            ItemStack s = h.getSlot(i).getStack();
            if (s.isEmpty() || !s.isOf(item)) continue;
            int c = s.getCount();
            if (c > bestCount) {
                bestCount = c;
                best = i;
                if (bestCount >= MAX_STACK_SIZE) break; // optimal
            }
        }
        return best;
    }

    private int findSmallestSlotWithItem(ScreenHandler h, Item item, int excludeSlot) {
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        final int slotCount = h.slots.size(); // Cache size
        for (int i = SLOT_START; i < slotCount; i++) {
            if (i == excludeSlot) continue;
            ItemStack s = h.getSlot(i).getStack();
            if (s.isEmpty() || !s.isOf(item)) continue;
            int c = s.getCount();
            if (c < bestCount) {
                bestCount = c;
                best = i;
                if (bestCount <= 1) break;
            }
        }
        return best;
    }

    private int findEmptySlot(ScreenHandler h) {
        final int slotCount = h.slots.size(); // Cache size
        for (int i = SLOT_START; i < slotCount; i++) {
            if (h.getSlot(i).getStack().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasRoomForOutput(MinecraftClient client, ItemStack stack) {
        PlayerInventory inv = client.player.getInventory();
        // check existing partial stacks
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && ItemStack.areItemsAndComponentsEqual(s, stack) && s.getCount() < s.getMaxCount()) {
                return true;
            }
        }
        // check for empty slot
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    private net.minecraft.recipe.RecipeEntry<?> getMetronomeRecipe(MinecraftClient client) {
        if (!CONFIG.useRecipeBook) return null;
        if (cachedMetronomeRecipe != null) return cachedMetronomeRecipe;
        Item target = Registries.ITEM.get(Identifier.of(CONFIG.craftOutputItemId));
        if (target == Items.AIR) return null;
        var manager = client.world != null ? client.world.getRecipeManager() : null;
        if (manager == null) return null;
        for (net.minecraft.recipe.RecipeEntry<?> entry : manager.listAllOfType(RecipeType.CRAFTING)) {
            Recipe<?> r = entry.value();
            ItemStack result = r.getResult(client.world.getRegistryManager());
            if (!result.isEmpty() && result.isOf(target)) {
                cachedMetronomeRecipe = entry;
                return entry;
            }
        }
        return null;
    }

    private void convertBlocksToIngots(MinecraftClient client, ScreenHandler h, int maxBlocks) {
        int slot = findSlotWithItem(h, IRON_BLOCK);
        if (slot == -1) return;
        clearGrid(client, h);
        clickSlotGrid(client, h, slot, 0); // pick up stack
        int placed = 0;
        // place up to maxBlocks one by one with right-clicks
        for (; placed < maxBlocks && !h.getCursorStack().isEmpty(); placed++) {
            clickSlotGrid(client, h, 1, 1);
        }
        quickMoveSlot(client, h, 0); // take ingots (9 per block)
        if (!h.getCursorStack().isEmpty()) {
            clickSlotGrid(client, h, slot, 0); // return remainder
        }
        clearGrid(client, h);
    }

    private void convertIngotsToNuggets(MinecraftClient client, ScreenHandler h, int maxIngots) {
        int slot = findSlotWithItem(h, IRON_INGOT);
        if (slot == -1) return;
        clearGrid(client, h);
        clickSlotGrid(client, h, slot, 0); // pick up ingot stack
        int placed = 0;
        for (; placed < maxIngots && !h.getCursorStack().isEmpty(); placed++) {
            clickSlotGrid(client, h, 1, 1); // place one ingot each right-click
        }
        quickMoveSlot(client, h, 0); // take nuggets (9 per ingot)
        if (!h.getCursorStack().isEmpty()) {
            clickSlotGrid(client, h, slot, 0); // return remainder
        }
        clearGrid(client, h);
    }

    private boolean conversionProgressed(ConversionOp op) {
        return switch (op.kind) {
            case BREAK_BLOCK_TO_INGOTS -> ironBlocks < pendingBeforeBlocks || ingots > pendingBeforeIngots;
            case INGOT_TO_NUGGETS -> ingots < pendingBeforeIngots || nuggets > pendingBeforeNuggets;
        };
    }

    private boolean executeConversionOp(MinecraftClient client, ScreenHandler h, ConversionOp op) {
        recalcInventory(client);
        if (op.kind == ConversionKind.BREAK_BLOCK_TO_INGOTS) {
            if (ingotStackRoom < INGOTS_PER_BLOCK || ironBlocks <= 0) return false;
            int units = op.units;
            if (units <= 0) return false;
            if (ironBlocks < units) return false;
            if (ingotStackRoom < units * INGOTS_PER_BLOCK) return false;
            pendingBeforeBlocks = ironBlocks;
            pendingBeforeIngots = ingots;
            pendingBeforeNuggets = nuggets;
            convertBlocksToIngots(client, h, units);
        } else {
            if (nuggetStackRoom < NUGGETS_PER_INGOT || ingots <= 0) return false;
            int units = op.units;
            if (units <= 0) return false;
            if (ingots < units) return false;
            if (nuggetStackRoom < units * NUGGETS_PER_INGOT) return false;
            pendingBeforeBlocks = ironBlocks;
            pendingBeforeIngots = ingots;
            pendingBeforeNuggets = nuggets;
            convertIngotsToNuggets(client, h, units);
        }
        return true;
    }

    // Runs queued conversions with acknowledgment and bounded retries.
    private boolean processConversionQueue(MinecraftClient client, ScreenHandler h) {
        long now = System.currentTimeMillis();
        if (pendingConversion != null) {
            recalcInventory(client);
            if (conversionProgressed(pendingConversion)) {
                pendingConversion = null;
                pendingRetries = 0;
                setCraftCooldown();
                return true;
            }
            // Slow down for batch 14 onwards to give server more time to recognize conversions
            // Increased timeout from 200ms to 500ms to handle server lag better
            long conversionTimeout = craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD ? 500 : 20;
            if (now - pendingSinceMs < conversionTimeout) {
                setCraftCooldown();
                return true;
            }
            // Increased max retries from 4 to 10 for batch 14+ to handle server lag
            int maxRetries = craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD ? 10 : 4;
            if (pendingRetries >= maxRetries) {
                String kind = pendingConversion.kind == ConversionKind.BREAK_BLOCK_TO_INGOTS ? "break blocks" : "convert ingots to nuggets";
                autoPauseOnConversionFailure(client, "failed to " + kind + " after " + maxRetries + " retries; ingotRoom=" + ingotStackRoom + " nuggetRoom=" + nuggetStackRoom + " emptySlots=" + emptySlots);
                return true;
            }
            if (!executeConversionOp(client, h, pendingConversion)) {
                String reason = pendingConversion.kind == ConversionKind.BREAK_BLOCK_TO_INGOTS
                        ? "not enough room/resources to break blocks"
                        : "not enough room/resources to convert nuggets";
                autoPauseOnConversionFailure(client, reason + "; ingotRoom=" + ingotStackRoom + " nuggetRoom=" + nuggetStackRoom + " emptySlots=" + emptySlots);
                return true;
            }
            pendingRetries++;
            pendingSinceMs = now;
            setCraftCooldown();
            return true;
        }

        if (conversionQueue.isEmpty()) return false;
        ConversionOp op = conversionQueue.removeFirst();
        if (!executeConversionOp(client, h, op)) {
            String reason = op.kind == ConversionKind.BREAK_BLOCK_TO_INGOTS
                    ? "not enough room/resources to break blocks"
                    : "not enough room/resources to convert nuggets";
            autoPauseOnConversionFailure(client, reason + "; ingotRoom=" + ingotStackRoom + " nuggetRoom=" + nuggetStackRoom + " emptySlots=" + emptySlots);
            return true;
        }
        pendingConversion = op;
        pendingRetries = 0;
        pendingSinceMs = now;
        setCraftCooldown();
        return true;
    }

    private void compressIngotsToBlock(MinecraftClient client, ScreenHandler h) {
        // takes 9 ingots -> 1 block to free slots
        int ingotSlot = findSlotWithItem(h, IRON_INGOT);
        if (ingotSlot == -1) return;
        clearGrid(client, h);
        // place one ingot in each of 9 grid slots
        int placed = 0;
        for (int s = 1; s <= 9 && placed < INGOTS_PER_BLOCK; s++) {
            int src = findSlotWithItem(h, IRON_INGOT);
            if (src == -1) break;
            clickSlot(client, h, src);
            clickSlot(client, h, s);
            placed++;
        }
        if (placed == INGOTS_PER_BLOCK) {
            quickMoveSlot(client, h, 0); // take block
        }
        clearGrid(client, h);
    }

    private void compressNuggetsToIngot(MinecraftClient client, ScreenHandler h) {
        int nugSlot = findSlotWithItem(h, IRON_NUGGET);
        if (nugSlot == -1) return;
        clearGrid(client, h);
        int placed = 0;
        for (int s = 1; s <= 9 && placed < 9; s++) {
            int src = findSlotWithItem(h, IRON_NUGGET);
            if (src == -1) break;
            clickSlot(client, h, src);
            clickSlot(client, h, s);
            placed++;
        }
        if (placed == 9) {
            quickMoveSlot(client, h, 0); // take ingot
        }
        clearGrid(client, h);
    }

    private boolean ensureGridFilled(MinecraftClient client, ScreenHandler h) {
        clearGrid(client, h); // start clean before placing recipe

        int[] ingotSlots = new int[]{2, 4, 6, 7, 9};
        int nuggetSlot = 5;
        int noteSlot = 8;

        for (int s : ingotSlots) {
            if (h.getSlot(s).getStack().isEmpty()) {
                int src = findSlotWithItem(h, IRON_INGOT);
                if (src == -1) return false;
                clickSlot(client, h, src);
                clickSlot(client, h, s);
            }
        }

        if (h.getSlot(nuggetSlot).getStack().isEmpty()) {
            int src = findSlotWithItem(h, IRON_NUGGET);
            if (src == -1) return false;
            clickSlot(client, h, src);
            clickSlot(client, h, nuggetSlot);
        }

        if (h.getSlot(noteSlot).getStack().isEmpty()) {
            int src = findSlotWithItem(h, NOTE_BLOCK);
            if (src == -1) return false;
            clickSlot(client, h, src);
            clickSlot(client, h, noteSlot);
        }
        return true;
    }

    private void quickMoveSlot(MinecraftClient client, ScreenHandler handler, int slotIndex) {
        client.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
        // Slow down for batch 14 onwards to prevent server desync during conversions
        long delay = CONFIG.craftPlaceCooldownMs;
        if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
            delay *= BATCH_DELAY_MULTIPLIER;
        }
        cooldown(delay); // significantly longer delay for server sync
    }

    private boolean dumpCursorToInventory(MinecraftClient client, ScreenHandler h) {
        ItemStack cursor = h.getCursorStack();
        if (cursor.isEmpty()) return true;

        // 1) Try to merge into partially filled stacks of the same item first.
        for (int i = 10; i < h.slots.size(); i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(s, cursor) && s.getCount() < s.getMaxCount()) {
                clickSlot(client, h, i); // attempt to merge/swap
                cursor = h.getCursorStack();
                if (cursor.isEmpty()) return true;
            }
        }

        // 2) Otherwise, place into any empty slot.
        for (int i = 10; i < h.slots.size(); i++) {
            if (h.getSlot(i).getStack().isEmpty()) {
                clickSlot(client, h, i);
                return h.getCursorStack().isEmpty();
            }
        }
        return h.getCursorStack().isEmpty();
    }

    private void clearGrid(MinecraftClient client, ScreenHandler h) {
        for (int s = 1; s <= 9; s++) {
            ItemStack stack = h.getSlot(s).getStack();
            if (!stack.isEmpty()) {
                quickMoveSlot(client, h, s);
            }
        }
    }

    private boolean placeRecipe(MinecraftClient client, ScreenHandler h) {
        // recipe slots: ingots 2,4,6,7,9; nugget 5; note 8
        int[] ingotSlots = new int[]{2, 4, 6, 7, 9};
        for (int s : ingotSlots) {
            if (!moveStackToSlot(client, h, IRON_INGOT, s, false)) return false;
        }
        if (!moveStackToSlot(client, h, IRON_NUGGET, 5, false)) return false;
        if (!moveStackToSlot(client, h, NOTE_BLOCK, 8, false)) return false;
        return true;
    }

    private boolean moveStackToSlot(MinecraftClient client, ScreenHandler h, Item item, int targetSlot) {
        return moveStackToSlot(client, h, item, targetSlot, false);
    }

    private boolean moveStackToSlot(MinecraftClient client, ScreenHandler h, Item item, int targetSlot, boolean slow) {
        if (!h.getSlot(targetSlot).getStack().isEmpty()) {
            // Clear slot manually without shift-click
            clickSlot(client, h, targetSlot);  // pick up
            int emptySlot = findEmptySlot(h);
            if (emptySlot != -1) {
                clickSlot(client, h, emptySlot);  // place in empty slot
            } else {
                return false;
            }
            if (!h.getSlot(targetSlot).getStack().isEmpty()) return false;
        }
        int src = findSlotWithItem(h, item);
        if (src == -1) return false;
        // always place entire stack for reliability (recipe consumes only needed count)
        clickSlot(client, h, src);      // pick up stack
        clickSlot(client, h, targetSlot); // place stack
        return h.getSlot(targetSlot).getStack().isOf(item);
    }

    // === Grid load & burst helpers ===
    private boolean slotHas(ScreenHandler h, int slot, Item item) {
        ItemStack s = h.getSlot(slot).getStack();
        return !s.isEmpty() && s.isOf(item);
    }

    private boolean ensureSlotHasItem(MinecraftClient client, ScreenHandler h, int targetSlot, Item item) {
        if (slotHas(h, targetSlot, item)) return true;

        if (!h.getSlot(targetSlot).getStack().isEmpty()) {
            // Clear slot manually without shift-click
            clickSlot(client, h, targetSlot);  // pick up
            int emptySlot = findEmptySlot(h);
            if (emptySlot != -1) {
                clickSlot(client, h, emptySlot);  // place in empty slot
            } else {
                return false;
            }
            if (!h.getSlot(targetSlot).getStack().isEmpty()) return false;
        }
        int src = findSlotWithItem(h, item);
        if (src == -1) return false;
        clickSlot(client, h, src);      // pick up
        clickSlot(client, h, targetSlot); // place
        return slotHas(h, targetSlot, item);
    }

    private void stashGridAndCursor(MinecraftClient client) {
        if (client.player == null) return;
        ScreenHandler h = client.player.currentScreenHandler;
        if (h == null || h.slots.size() < 10) return;
        clearGrid(client, h);
        dumpCursorToInventory(client, h);
    }

    // Ensure target slot has at least minCount items of the given type (up to stack limit).
    // Merges multiple source stacks if needed; expects cursor to be empty on entry.
    private boolean ensureSlotHasCount(MinecraftClient client, ScreenHandler h, int targetSlot, Item item, int minCount) {
        if (slotHas(h, targetSlot, item) && h.getSlot(targetSlot).getStack().getCount() >= minCount) return true;
        if (!ensureSlotHasItem(client, h, targetSlot, item)) return false;
        while (true) {
            ItemStack t = h.getSlot(targetSlot).getStack();
            if (!t.isOf(item)) return false;
            if (t.getCount() >= minCount || t.getCount() >= t.getMaxCount()) return true;
            int src = findSlotWithItem(h, item);
            if (src == -1) return false;
            // pick up source stack, merge into target, return remainder (if any) back to source slot
            clickSlot(client, h, src);
            clickSlot(client, h, targetSlot);
            if (!h.getSlot(src).getStack().isEmpty()) {
                clickSlot(client, h, src); // put back remainder
            }
            // If no progress, bail to avoid infinite loop
            if (h.getSlot(targetSlot).getStack().getCount() == t.getCount()) {
                msg(client, "AutoCraft: stalled topping slot " + targetSlot + " for " + item.getName().getString());
                return false;
            }
        }
    }

    private boolean topUpSlot(MinecraftClient client, ScreenHandler h, int targetSlot, Item item, int minCount) {
        for (int attempt = 0; attempt < 16; attempt++) {
            ItemStack target = h.getSlot(targetSlot).getStack();
            int targetCount = target.isOf(item) ? target.getCount() : 0;
            if (targetCount >= minCount) return true;

            // Clear target slot if it has the wrong item
            if (!target.isEmpty() && !target.isOf(item)) {
                clickSlot(client, h, targetSlot);  // pick up the wrong item
                int emptySlot = findEmptySlot(h);
                if (emptySlot != -1) {
                    clickSlot(client, h, emptySlot);  // place in empty inventory slot
                } else {
                    return false;  // no room to clear
                }
                target = h.getSlot(targetSlot).getStack();
                targetCount = target.isOf(item) ? target.getCount() : 0;
            }

            int need = minCount - targetCount;
            if (need <= 0) return true;

            int src = findSlotWithItem(h, item);
            if (src == -1) {
                debug(client, "topUpSlot: no source for " + item + " slot=" + targetSlot + " need=" + need);
                return false;
            }

            ItemStack srcStack = h.getSlot(src).getStack();
            int available = srcStack.getCount();
            
            // Pick up source stack (left-click)
            clickSlot(client, h, src);
            
            ItemStack cursor = h.getCursorStack();
            if (cursor.isEmpty()) {
                debug(client, "topUpSlot: pickup from src=" + src + " failed (cursor empty)");
                return false;
            }
            
            int cursorCount = cursor.getCount();
            
            // If we have more on cursor than we need, place precisely
            if (targetCount == 0 && cursorCount > need) {
                // Target is empty and we have excess - use right-clicks to place exact amount
                for (int i = 0; i < need; i++) {
                    clickSlotBtn(client, h, targetSlot, 1);  // right-click places 1 item in crafting grid
                }
                // Put remaining items back
                if (!h.getCursorStack().isEmpty()) {
                    clickSlot(client, h, src);  // put back in source
                }
            } else {
                // Use left-click to merge/place the stack
                clickSlot(client, h, targetSlot);
                
                // Check if we placed too many
                ItemStack afterPlace = h.getSlot(targetSlot).getStack();
                int afterCount = afterPlace.isOf(item) ? afterPlace.getCount() : 0;
                
                if (afterCount > minCount) {
                    // We placed too many, take back the excess
                    int excess = afterCount - minCount;
                    clickSlot(client, h, targetSlot);  // pick up all
                    
                    // Place back only what we need using right-clicks
                    for (int i = 0; i < minCount; i++) {
                        clickSlotBtn(client, h, targetSlot, 1);
                    }
                    
                    // Put excess back in inventory
                    if (!h.getCursorStack().isEmpty()) {
                        // Try original source slot first
                        ItemStack srcSlotNow = h.getSlot(src).getStack();
                        if (srcSlotNow.isEmpty() || (srcSlotNow.isOf(item) && srcSlotNow.getCount() < srcSlotNow.getMaxCount())) {
                            clickSlot(client, h, src);
                        } else {
                            int emptySlot = findEmptySlot(h);
                            if (emptySlot != -1) {
                                clickSlot(client, h, emptySlot);
                            }
                        }
                    }
                } else {
                    // Placed the right amount or less, handle cursor if needed
                    if (!h.getCursorStack().isEmpty()) {
                        // Try to put back in the original source slot first
                        ItemStack srcSlotNow = h.getSlot(src).getStack();
                        if (srcSlotNow.isEmpty() || (srcSlotNow.isOf(item) && srcSlotNow.getCount() < srcSlotNow.getMaxCount())) {
                            clickSlot(client, h, src);  // put back in source
                        }
                        
                        // If still have items on cursor, find another slot
                        if (!h.getCursorStack().isEmpty()) {
                            int emptySlot = findEmptySlot(h);
                            if (emptySlot != -1) {
                                clickSlot(client, h, emptySlot);  // place in empty slot
                            } else {
                                // Try to merge with another stack of the same item
                                int mergeSlot = findSlotWithItem(h, item);
                                if (mergeSlot != -1 && mergeSlot != targetSlot) {
                                    clickSlot(client, h, mergeSlot);
                                }
                            }
                        }
                    }
                }
            }
            
            ItemStack afterStack = h.getSlot(targetSlot).getStack();
            int after = afterStack.isOf(item) ? afterStack.getCount() : 0;
            debug(client, "topUpSlot attempt=" + attempt + " src=" + src + " target=" + targetSlot + " count was " + targetCount + " now " + after + " (needed " + need + ")");
            
            if (after >= minCount) {
                setCraftCooldown();  // Only set cooldown on success
                return true;
            }
            if (after > targetCount) {
                // Made progress, continue looping
                continue;
            }
            // No progress, would loop infinitely
            return false;
        }
        return false;  // couldn't fill after retries
    }


    // Move full stacks into the recipe grid in order: ingots -> nuggets -> notes.
    // Optimized to fill multiple slots per tick for faster, smoother filling
    private boolean loadGridOnce(MinecraftClient client, ScreenHandler h, int requiredPerSlot) {
        // Initialize on first call
        if (gridFillStep == 0) {
            if (!h.getCursorStack().isEmpty() && !dumpCursorToInventory(client, h)) return false;
            clearGrid(client, h);
            recalcInventory(client, false);
            gridFillNeed = Math.max(1, Math.min(64, requiredPerSlot));
            gridFillStep = 1;
            debug(client, "[GridFill] Starting grid fill: need=" + gridFillNeed);
            setCraftCooldown();
            return false;
        }

        // Fill ingot slots (steps 1-5)
        // For batches 14+, fill one slot at a time to prevent server desync
        // For earlier batches, fill all 5 slots in one tick for maximum safe speed
        int maxSlotsPerTick = craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD ? SLOW_FILL_SLOTS_PER_TICK : FAST_FILL_SLOTS_PER_TICK;
        int slotsFilledThisTick = 0;
        while (gridFillStep >= 1 && gridFillStep <= FAST_FILL_SLOTS_PER_TICK && slotsFilledThisTick < maxSlotsPerTick) {
            // Recalculate inventory before each slot fill for batches 14+ to ensure accuracy
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                recalcInventory(client, false);
            }
            
            int slotIndex = gridFillStep - 1;
            int targetSlot = INGOT_SLOTS[slotIndex];
            debug(client, "[GridFill] Filling ingot slot " + targetSlot + " (step " + gridFillStep + ")");
            if (!topUpSlot(client, h, targetSlot, IRON_INGOT, gridFillNeed)) {
                msg(client, "AutoCraft: need " + gridFillNeed + " ingots in slot " + targetSlot + " (have " + h.getSlot(targetSlot).getStack().getCount() + "), inv ingots=" + ingots + " blocks=" + ironBlocks);
                debugDumpState(client, h, gridFillNeed);
                gridFillStep = 0; // reset for retry
                return false;
            }
            gridFillStep++;
            slotsFilledThisTick++;
        }
        
        // If we filled some ingot slots but not all, return to continue next tick
        if (gridFillStep >= 1 && gridFillStep <= FAST_FILL_SLOTS_PER_TICK) {
            return false;
        }

        // Fill nugget slot (step 6)
        if (gridFillStep == 6) {
            // Recalculate inventory before filling for batches 14+ to ensure accuracy
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                recalcInventory(client, false);
            }
            
            debug(client, "[GridFill] Filling nugget slot " + NUGGET_SLOT);
            if (!topUpSlot(client, h, NUGGET_SLOT, IRON_NUGGET, gridFillNeed)) {
                msg(client, "AutoCraft: need " + gridFillNeed + " nuggets in slot " + NUGGET_SLOT + " (have " + h.getSlot(NUGGET_SLOT).getStack().getCount() + "), inv nuggets=" + nuggets + " ingots=" + ingots);
                debugDumpState(client, h, gridFillNeed);
                gridFillStep = 0; // reset for retry
                return false;
            }
            gridFillStep++;
            // For batches 14+, process nugget and note block on separate ticks
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                return false;
            }
            // Continue to note block slot in same tick for earlier batches
        }

        // Fill note block slot (step 7)
        if (gridFillStep == 7) {
            // Recalculate inventory before filling for batches 14+ to ensure accuracy
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                recalcInventory(client, false);
            }
            
            debug(client, "[GridFill] Filling note block slot " + NOTE_SLOT);
            if (!topUpSlot(client, h, NOTE_SLOT, NOTE_BLOCK, gridFillNeed)) {
                msg(client, "AutoCraft: need " + gridFillNeed + " note blocks in slot " + NOTE_SLOT + " (have " + h.getSlot(NOTE_SLOT).getStack().getCount() + "), inv notes=" + noteBlocks);
                debugDumpState(client, h, gridFillNeed);
                gridFillStep = 0; // reset for retry
                return false;
            }
            gridFillStep = 8; // mark as done
            return false;
        }

        // Done (step 8)
        if (gridFillStep == 8) {
            debugDumpState(client, h, gridFillNeed);
            gridFillStep = 0; // reset for next time
            return true;
        }

        return false;
    }

    private int burstTakeOutput(MinecraftClient client, ScreenHandler h, int maxTake) {
        int made = 0;
        // SINGLE item per tick to prevent server desync on multiplayer
        ItemStack out = h.getSlot(OUT_SLOT).getStack();
        if (!out.isEmpty() && hasRoomForOutput(client, out)) {
            quickMoveSlot(client, h, OUT_SLOT);
            made++;
        }
        return made;
    }

    // Tick-based crafting loop (manual grid batch crafting)
    private void tickCraftRun(MinecraftClient client) {
        if (!craftRunning) return;
        if (craftTickCooldown > 0) {
            craftTickCooldown--;
            return;
        }
        if (!(client.player.currentScreenHandler instanceof ScreenHandler h) || h.slots.size() < 10) {
            craftRunning = false;
            msg(client, "AutoCraft: handler missing, stopping.");
            return;
        }

        // keep cursor empty so we never drop items
        if (!h.getCursorStack().isEmpty()) {
            if (!dumpCursorToInventory(client, h)) {
                craftRunning = false;
                msg(client, "AutoCraft: no room to place cursor stack; stopping.");
                return;
            }
            setCraftCooldown();
            return;
        }

        // finished?
        if (craftCrafted >= craftTarget) {
            // Final sweep: if materials remain for at least one craft, do a tiny batch
            recalcInventory(client);
            int possible = computeCraftsPossible(client);
            int remaining = Math.min(possible, noteBlocks);
            if (remaining > 0) {
                gridLoaded = false;
                gridFillStep = 0; // reset grid fill state
                gridBatchTarget = Math.min(8, remaining);
                craftTarget = craftCrafted + gridBatchTarget; // extend just enough to finish leftovers
                setCraftCooldown();
            } else {
                stashGridAndCursor(client);
                craftRunning = false;
                craftFinishAtMs = System.currentTimeMillis() + CONFIG.craftFinishDelayMs;
                msg(client, "AutoCraft: finished " + craftCrafted + "/" + craftTarget + ", waiting " + CONFIG.craftFinishDelayMs + "ms before closing...");
                queueSellAfterClose = true; // trigger sell after window closes
                return;
            }
        }

        recalcInventory(client);
        // Only stop for space if we truly can't fit another metronome (partial stacks count)
        ItemStack sampleOut = new ItemStack(Registries.ITEM.get(Identifier.of(CONFIG.craftOutputItemId)));
        if (!hasRoomForOutput(client, sampleOut)) {
            // Try to free space by compressing ingots/nuggets once
            if (ingots >= 9) {
                compressIngotsToBlock(client, h);
                setCraftCooldown();
                return;
            }
            if (nuggets >= 81) {
                compressNuggetsToIngot(client, h);
                setCraftCooldown();
                return;
            }
            stashGridAndCursor(client);
            craftRunning = false;
            msg(client, "AutoCraft: no room for output; crafted " + craftCrafted);
            return;
        }

        int remainingCrafts = craftTarget - craftCrafted;
        if (remainingCrafts <= 0) {
            stashGridAndCursor(client);
            craftRunning = false;
            msg(client, "AutoCraft: finished " + craftCrafted + "/" + craftTarget);
            return;
        }

        // Never ask for more than we can actually craft with current materials.
        int craftsPossible = computeCraftsPossible(client);
        int feasible = Math.min(remainingCrafts, craftsPossible);
        if (feasible <= 0) {
            stopCrafting(client, "out of materials; crafted " + craftCrafted + "/" + craftTarget);
            return;
        }

        // Determine batch size from BATCH_PLAN if available, otherwise calculate dynamically
        int batch;
        if (craftBatchIndex < BATCH_PLAN.length) {
            // Use planned batch size (always 64 for metronomes)
            int[] plan = BATCH_PLAN[craftBatchIndex];
            batch = Math.min(plan[0], feasible); // Use plan batch size but don't exceed what's feasible
        } else {
            // Fallback to dynamic calculation if we've exhausted the plan
            batch = Math.min(CONFIG.craftBatchSize, feasible);
            batch = Math.min(batch, 64);
        }

        // choose burst based on free space (capped at 16 to prevent desync during collection)
        // Note: burst size stays at 16 even though batch is 64, because burst controls how many
        // outputs are collected at once. Higher values could cause server desync/lag.
        int burstPreferred = emptySlots >= 10 ? 16 : emptySlots >= 6 ? 12 : 8;

        if (!gridLoaded) {
            debug(client, "[PreCraft] Checking grid state before crafting batch " + craftBatchIndex + ", target=" + batch);
            // Initialize plan targets when entering a new batch.
            if (planBlocksRemaining == 0 && planIngotsToNuggetsRemaining == 0) {
                if (craftBatchIndex < BATCH_PLAN.length) {
                    // Use the precomputed plan for this batch
                    int[] plan = BATCH_PLAN[craftBatchIndex];
                    planBlocksRemaining = plan[1];
                    planIngotsToNuggetsRemaining = plan[2];
                    debug(client, "[Plan] Using BATCH_PLAN[" + craftBatchIndex + "]: batch=" + plan[0] + ", blocks=" + plan[1] + ", ingots2nuggets=" + plan[2]);
                } else {
                    // Calculate dynamically if no plan available
                    int requiredIngots = batch * 5;
                    int requiredNuggets = batch;
                    int nuggetsShort = Math.max(0, requiredNuggets - nuggets);
                    planIngotsToNuggetsRemaining = (int) Math.ceil(nuggetsShort / 9.0);
                    int ingotsNeededTotal = requiredIngots + planIngotsToNuggetsRemaining;
                    int ingotShort = Math.max(0, ingotsNeededTotal - ingots);
                    planBlocksRemaining = (int) Math.ceil(ingotShort / 9.0);
                }
            }

            // Re-evaluate actual deficits before converting to avoid over-breaking.
            int reqIngots = batch * 5;
            int reqNuggets = batch;
            int nugShort = Math.max(0, reqNuggets - nuggets);
            int ingotsNeededForNuggets = (int) Math.ceil(nugShort / 9.0);
            int ingotNeedTotal = reqIngots + ingotsNeededForNuggets;
            int ingotShortNow = Math.max(0, ingotNeedTotal - ingots);
            // If we already have enough ingots, skip breaking blocks.
            int maxBlocksNeededNow = (int) Math.ceil(ingotShortNow / 9.0);
            planBlocksRemaining = Math.min(planBlocksRemaining, Math.max(0, maxBlocksNeededNow));
            // If we already have enough nuggets, skip conversions.
            int nuggetShortNow = Math.max(0, reqNuggets - nuggets);
            int ingotsToNuggetsNeed = (int) Math.ceil(nuggetShortNow / 9.0);
            // IMPORTANT: Also check if we have room for the nuggets that will be created
            // Each ingot converts to 9 nuggets, so we need 9x room
            int maxIngotsBasedOnRoom = nuggetStackRoom / NUGGETS_PER_INGOT;
            ingotsToNuggetsNeed = Math.min(ingotsToNuggetsNeed, maxIngotsBasedOnRoom);
            planIngotsToNuggetsRemaining = Math.min(planIngotsToNuggetsRemaining, Math.max(0, ingotsToNuggetsNeed));

            // Build/execute conversion queue: one operation at a time, ACKed between actions.
            if (conversionQueue.isEmpty() && pendingConversion == null) {
                // For batch 14 ONLY (the last batch), use smaller units for extra safety
                // User feedback: Keep 64 units for all batches except the very last one
                int blockUnits = craftBatchIndex == 14 ? 8 : BLOCKS_TO_INGOTS_UNITS_PER_OP;
                int ingotUnits = craftBatchIndex == 14 ? 8 : INGOTS_TO_NUGGETS_UNITS_PER_OP;
                
                int blocksLeft = planBlocksRemaining;
                while (blocksLeft > 0) {
                    int units = Math.min(blockUnits, blocksLeft);
                    conversionQueue.addLast(new ConversionOp(ConversionKind.BREAK_BLOCK_TO_INGOTS, units));
                    blocksLeft -= units;
                }
                int nuggetsLeft = planIngotsToNuggetsRemaining;
                while (nuggetsLeft > 0) {
                    int units = Math.min(ingotUnits, nuggetsLeft);
                    conversionQueue.addLast(new ConversionOp(ConversionKind.INGOT_TO_NUGGETS, units));
                    nuggetsLeft -= units;
                }
            }
            if (processConversionQueue(client, h)) {
                if (pendingConversion == null && conversionQueue.isEmpty()) {
                    planBlocksRemaining = 0;
                    planIngotsToNuggetsRemaining = 0;
                }
                return;
            }

            gridBatchTarget = batch;

            // load recipe slots once with stacks (incremental, non-blocking)
            if (!loadGridOnce(client, h, gridBatchTarget)) {
                // Grid filling is in progress; will continue next tick
                return;
            }
            gridLoaded = true;
            // give the server a moment to settle the crafting grid before we start crafting
            // Use configurable post-grid delay, with increased delay for batch 14 onwards
            long delayToUse = (CONFIG != null && CONFIG.postGridDelayMs > 0 ? CONFIG.postGridDelayMs : postGridDelayMs);
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                delayToUse *= BATCH_DELAY_MULTIPLIER; // Slow down for batch 14 onwards (triple the delay)
            }
            gridReadyAtMs = System.currentTimeMillis() + delayToUse;
            craftBatchIndex++;
            planBlocksRemaining = 0;
            planIngotsToNuggetsRemaining = 0;
            setCraftCooldown();
            return;
        }

        // Ensure grid has had time to settle on the server before crafting
        if (System.currentTimeMillis() < gridReadyAtMs) {
            setCraftCooldown();
            return;
        }

        // grid already loaded: craft as many as resources in grid allow
        int craftsLeftInGrid = gridBatchTarget;
        // calculate min stack count across recipe slots
        int minIngots = Integer.MAX_VALUE;
        for (int s : INGOT_SLOTS) {
            minIngots = Math.min(minIngots, h.getSlot(s).getStack().getCount());
        }
        craftsLeftInGrid = Math.min(craftsLeftInGrid, minIngots);
        craftsLeftInGrid = Math.min(craftsLeftInGrid, h.getSlot(NUGGET_SLOT).getStack().getCount());
        craftsLeftInGrid = Math.min(craftsLeftInGrid, h.getSlot(NOTE_SLOT).getStack().getCount());
        if (craftsLeftInGrid <= 0) {
            stashGridAndCursor(client);
            gridLoaded = false;
            gridFillStep = 0; // reset grid fill state
            gridBatchTarget = 0;
            setCraftCooldown();
            return;
        }

        int burst = Math.min(Math.min(burstPreferred, craftsLeftInGrid), craftTarget - craftCrafted);
        int made = burstTakeOutput(client, h, burst);
        if (made > 0) {
            craftCrafted += made;
            gridBatchTarget -= made;
            if (craftCrafted % 32 == 0 || craftCrafted == craftTarget) {
                msg(client, "AutoCraft: crafted " + craftCrafted + "/" + craftTarget);
            }
            // Add delay after collecting output to prevent crashes/desync
            // Slow down for batch 14 onwards (triple the delay)
            int burstDelay = (int)(CONFIG.craftOutputBurstDelayMs / 50L);
            if (craftBatchIndex >= DESYNC_PREVENTION_BATCH_THRESHOLD) {
                burstDelay *= BATCH_DELAY_MULTIPLIER;
            }
            craftTickCooldown = Math.max(burstDelay, 1);
            return;
        }

        // no output taken; either output empty (grid consumed) or blocked space
        if (h.getSlot(OUT_SLOT).getStack().isEmpty() || gridBatchTarget <= 0) {
            stashGridAndCursor(client);
            gridLoaded = false;
            gridFillStep = 0; // reset grid fill state
            gridBatchTarget = 0;
            setCraftCooldown();
            return;
        }

        craftRunning = false;
        stashGridAndCursor(client);
        msg(client, "AutoCraft: output blocked; crafted " + craftCrafted + "/" + craftTarget);
        return;
        }

    private void loadStageConfig(int stage) {
        if (CONFIG.useTwoStagePlan && stage == 2) {
            currentTargetItemId = CONFIG.stage2TargetItemId;
            currentShopCommand = CONFIG.stage2Command;
            currentPlanQuantities = CONFIG.stage2PlanQuantities;
            currentUsesPagination = CONFIG.stage2UsesPagination;
        } else {
            currentTargetItemId = CONFIG.usePlan ? CONFIG.stage1TargetItemId : CONFIG.targetItemId;
            currentShopCommand = CONFIG.usePlan ? CONFIG.stage1Command : CONFIG.shopCommand;
            currentPlanQuantities = CONFIG.usePlan ? CONFIG.stage1PlanQuantities : CONFIG.planQuantities;
            currentUsesPagination = CONFIG.stage1UsesPagination;
        }
    }

    private void autoCraftMetronomes(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?>) || client.player.currentScreenHandler == null || client.player.currentScreenHandler.slots.size() < 10) {
            // send /craft and wait
            var nh = client.getNetworkHandler();
            if (nh != null) {
                try {
                    nh.sendCommand("craft");
                    craftAwaiting = true;
                    craftAwaitTicks = 40; // ~2 seconds at 20 tps
                    msg(client, "AutoCraft: sent /craft, waiting for window...");
                } catch (Exception e) {
                    msg(client, "AutoCraft: failed to send /craft (" + e.getClass().getSimpleName() + ")");
                }
            }
            return;
        }
        startCraftRun(client);
    }

    private void startCraftRun(MinecraftClient client) {
        if (!(client.player.currentScreenHandler instanceof ScreenHandler h) || h.slots.size() < 10) {
            msg(client, "AutoCraft: crafting screen unavailable.");
            return;
        }
        if (!h.getCursorStack().isEmpty()) {
            if (!dumpCursorToInventory(client, h)) {
                msg(client, "AutoCraft: please empty cursor (no item on mouse) and try again.");
                return;
            }
        }
        int possible = computeCraftsPossible(client);
        // Target is fixed from the BATCH_PLAN array: 15 batches of 64 crafts = 960 total
        craftTarget = Math.min(possible, 960);
        if (CONFIG.craftHardCap > 0) craftTarget = Math.min(craftTarget, CONFIG.craftHardCap);

        if (craftTarget <= 0) {
            msg(client, "AutoCraft: not enough resources to craft.");
            return;
        }
        craftCrafted = 0;
        // Short startup delay after GUI opens.
        craftTickCooldown = Math.max(8, MIN_ACTION_COOLDOWN_TICKS);
        placeFailStreak = 0;
        cachedMetronomeRecipe = null;
        recipeBookPendingTicks = 0;
        recipeBookPrevCount = 0;
        recipeBookAttempts = 0;
        craftRunning = true;
        craftBatchIndex = 0;
        planBlocksRemaining = 0;
        planIngotsToNuggetsRemaining = 0;
        conversionQueue.clear();
        pendingConversion = null;
        pendingRetries = 0;
        msg(client, "AutoCraft: started (target " + craftTarget + ").");
    }

    // ============================================================================
    // CONFIGURATION CLASS - Runtime Settings
    // ============================================================================

    // Simple JSON config
    private static class ShopNavigatorConfig {
        public String targetItemId = "minecraft:stone_bricks";
        public int targetQuantity = 16;
        public String listingTitleMustContain = "(Page";
        public int nextPageSlot = 53;
        public String nextPageItemId = "minecraft:arrow";
        public String shopCommand = "shop";
        public long cooldownSendShopMs = 350;
        public long cooldownPageMs = 250;
        public long cooldownQuantityMs = 200;
        public boolean overlayEnabled = true;
        public boolean debugMode = false;
        public int craftBatchSize = 4096;
        public int craftTargetCount = 0; // 0 = no cap, craft all possible
        public int craftHardCap = 0;     // 0 = no cap
        // ticks between actions; higher = slower/safer
        public int craftTickCooldown = 4; // reduced from 12 for faster crafting
        public int craftPlaceCooldownMs = 200; // reduced from 800 for faster item placement
        public long craftOutputBurstDelayMs = 100; // reduced to minimum safe delay for faster output collection
        public long craftFinishDelayMs = 500; // reduced to minimum safe delay before closing
        // === Added for safer grid fill ===
        public long gridPlaceDelayMs = 200; // reduced from 800 for faster grid filling
        public long postGridDelayMs = 200;  // reduced from 800 for faster crafting start
        public int recipeBookSettleTicks = 0;
        public boolean useRecipeBook = false;
        public String craftOutputItemId = "cobblemon:metronome";
        public boolean usePlan = false;
        public int[] planQuantities = new int[]{256, 256, 256, 128, 16, 8, 2, 2};
        public boolean useTwoStagePlan = false;
        public String stage1Command = "shop blocks";
        public String stage1TargetItemId = "minecraft:note_block";
        public int[] stage1PlanQuantities = new int[]{256, 256, 256, 128, 16, 8, 2, 2};
        public boolean stage1UsesPagination = true;
        public String stage2Command = "shop ores";
        public String stage2TargetItemId = "minecraft:iron_block";
        public int[] stage2PlanQuantities = new int[]{256, 256, 32, 8};
        public boolean stage2UsesPagination = false;
        
        // GTS Auto-Buyer Configuration
        public boolean gtsEnabled = false;
        public int gtsMaxPrice = 1000;
        public int gtsHardCap = 1500;
        public long gtsCooldownMs = 500;
        public String gtsCommand = "gts";

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("shopnavigator.json");

        static ShopNavigatorConfig load() {
            try {
                ShopNavigatorConfig cfg;
                if (Files.exists(PATH)) {
                    cfg = GSON.fromJson(Files.readString(PATH), ShopNavigatorConfig.class);
                } else {
                    cfg = new ShopNavigatorConfig();
                }
                // Ensure new fields have sensible defaults when loading older configs
                if (cfg.craftBatchSize <= 0) cfg.craftBatchSize = 4096;
                if (cfg.stage1PlanQuantities == null || cfg.stage1PlanQuantities.length == 0) {
                    cfg.stage1PlanQuantities = new int[]{256, 256, 256, 128, 16, 8, 2, 2};
                }
                if (cfg.stage2PlanQuantities == null || cfg.stage2PlanQuantities.length == 0) {
                    cfg.stage2PlanQuantities = new int[]{256, 256, 32, 8};
                }
                if (cfg.craftTickCooldown <= 0) cfg.craftTickCooldown = 4; // default to 4 for faster crafting
                if (cfg.craftTargetCount < 0) cfg.craftTargetCount = 0;
                if (cfg.craftHardCap < 0) cfg.craftHardCap = 0;
                if (cfg.craftOutputBurstDelayMs < 0) cfg.craftOutputBurstDelayMs = 100;
                if (cfg.craftFinishDelayMs < 0) cfg.craftFinishDelayMs = 500;
                // If pagination flags are missing, set defaults
                if (!cfg.useTwoStagePlan) {
                    // single-stage case: keep existing shopCommand/target/plan
                }
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, GSON.toJson(cfg));
                return cfg;
            } catch (IOException e) {
                return new ShopNavigatorConfig();
            }
        }

        void save() {
            try {
                Files.writeString(PATH, GSON.toJson(this));
            } catch (IOException ignored) {}
        }
    }
}
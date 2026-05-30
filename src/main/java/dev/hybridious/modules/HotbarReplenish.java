package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class HotbarReplenish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems = settings.createGroup("Items");

    // ---- General behaviour ----

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
            .name("threshold")
            .description("Start replenishing a hotbar slot once its stack count drops to or below this value.")
            .defaultValue(32)
            .range(1, 64)
            .sliderRange(1, 64)
            .build()
    );

    private final Setting<Boolean> onlyConfigured = sgGeneral.add(new BoolSetting.Builder()
            .name("only-configured-items")
            .description("If on, only the items in the 'Items' list below are replenished. If off, every hotbar slot is topped up using any matching item found in the inventory.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> fillEmptySlots = sgGeneral.add(new BoolSetting.Builder()
            .name("fill-empty-slots")
            .description("Also place items into hotbar slots that are completely empty (only works when a configured item is set, since an empty slot has no item to match).")
            .defaultValue(true)
            .visible(onlyConfigured::get)
            .build()
    );

    private final Setting<Boolean> dontReplaceHeld = sgGeneral.add(new BoolSetting.Builder()
            .name("dont-move-held")
            .description("Skip the slot you are currently holding to avoid swapping the item out of your hand mid-use.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("tick-delay")
            .description("Ticks to wait between each slot move. Higher is safer on anti-cheat servers like 2b2t. One move per this many ticks.")
            .defaultValue(2)
            .range(0, 20)
            .sliderRange(0, 20)
            .build()
    );

    private final Setting<Boolean> closeGui = sgGeneral.add(new BoolSetting.Builder()
            .name("only-when-no-screen")
            .description("Only act when no container/inventory screen is open. Recommended on for survival anarchy servers.")
            .defaultValue(true)
            .build()
    );

    // ---- Item selection ----

    private final Setting<List<Item>> items = sgItems.add(new ItemListSetting.Builder()
            .name("items")
            .description("The items to keep replenished, e.g. bone_meal. Leave empty and turn off 'only-configured-items' to replenish everything.")
            .build()
    );

    private int delayLeft = 0;

    public HotbarReplenish() {
        super(Hybridious.CATEGORY, "hotbar-replenish", "Refills hotbar slots from your inventory when they run low. Useful for keeping bone meal, blocks, etc. topped up.");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Respect open screens if asked to.
        if (closeGui.get() && mc.currentScreen != null) return;

        // Per-action delay so we don't fire a burst of clicks in one tick.
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // Walk the 9 hotbar slots (0-8) and refill the first one that needs it.
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().selectedSlot) continue;

            ItemStack stack = mc.player.getInventory().getStack(hotbar);

            if (stack.isEmpty()) {
                // Only fill empties when we know what item belongs there (configured mode).
                if (onlyConfigured.get() && fillEmptySlots.get()) {
                    if (tryFillEmpty(hotbar)) {
                        delayLeft = tickDelay.get();
                        return;
                    }
                }
                continue;
            }

            // Don't touch unstackable items - nothing to merge into.
            if (stack.getMaxCount() <= 1) continue;

            // Skip items not in our list when in configured mode.
            if (onlyConfigured.get() && !items.get().isEmpty() && !containsItem(stack.getItem())) continue;

            // Already full, or above threshold - leave it alone.
            if (stack.getCount() > threshold.get()) continue;
            if (stack.getCount() >= stack.getMaxCount()) continue;

            // Find a matching stack elsewhere in the inventory (not hotbar slots we're filling).
            int sourceSlot = findRefill(stack.getItem());
            if (sourceSlot == -1) continue;

            moveToHotbar(sourceSlot, hotbar);
            delayLeft = tickDelay.get();
            return;
        }
    }

    private boolean tryFillEmpty(int hotbar) {
        for (Item item : items.get()) {
            if (item == Items.AIR) continue;
            int sourceSlot = findRefill(item);
            if (sourceSlot != -1) {
                moveToHotbar(sourceSlot, hotbar);
                return true;
            }
        }
        return false;
    }

    private boolean containsItem(Item item) {
        for (Item i : items.get()) if (i == item) return true;
        return false;
    }

    /**
     * Finds the best inventory slot to pull a refill from for the given item.
     * Searches the main inventory (slots 9-35) only, so we never cannibalise another hotbar slot.
     * Prefers the smallest non-full stack so partial stacks get consolidated first.
     * Returns the inventory slot index, or -1 if none found.
     */
    private int findRefill(Item item) {
        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;

        // Main inventory occupies slots 9..35 in the player inventory indexing used by getStack.
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = mc.player.getInventory().getStack(slot);
            if (s.isEmpty() || !s.isOf(item)) continue;

            // Prefer the smallest stack to consolidate partials, but any works.
            if (s.getCount() < bestCount) {
                bestCount = s.getCount();
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    /**
     * Moves the stack at the given inventory slot index onto the target hotbar slot using
     * proper click packets, which is anti-cheat friendly on Paper/Folia servers.
     *
     * For an empty target we use a swap (pickup source, place on target) so the whole stack moves.
     * For an occupied target we left-click to merge, and if anything is left on the cursor we put it
     * back into the source slot so nothing is lost or dropped.
     */
    private void moveToHotbar(int fromInvSlot, int toHotbarSlot) {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        // Translate raw inventory indices into screen-handler slot ids for the survival inventory.
        int fromId = invIndexToSlotId(fromInvSlot);
        int toId = invIndexToSlotId(toHotbarSlot);
        if (fromId < 0 || toId < 0) return;

        // Pick up the source stack onto the cursor.
        clickSlot(fromId, 0, SlotActionType.PICKUP);

        // Place onto / merge into the target.
        clickSlot(toId, 0, SlotActionType.PICKUP);

        // If the target couldn't take everything (it hit max count), the remainder is still on the
        // cursor - put it back into the source slot so we never lose or drop items.
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            clickSlot(fromId, 0, SlotActionType.PICKUP);
        }
    }

    private void clickSlot(int slotId, int button, SlotActionType action) {
        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                slotId,
                button,
                action,
                mc.player
        );
    }

    /**
     * Converts a player-inventory index (0-8 hotbar, 9-35 main) into the slot id used by the
     * survival inventory screen handler. In that handler the main inventory is slot ids 9..35 (a
     * 1:1 match with the inventory index) and the hotbar 0..8 maps to ids 36..44.
     */
    private int invIndexToSlotId(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            // Hotbar: ids 36..44 in the player screen handler.
            return 36 + invIndex;
        } else if (invIndex >= 9 && invIndex <= 35) {
            // Main inventory: ids 9..35, same numbering.
            return invIndex;
        }
        return -1;
    }
}

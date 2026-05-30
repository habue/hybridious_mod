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
    private final SettingGroup sgItems   = settings.createGroup("Items");

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
            .description("If on, only items in the 'Items' list are managed. If off, every hotbar slot is topped up using any matching item found in the inventory.")
            .defaultValue(true)
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
            .description("Ticks to wait between each slot move. Higher is safer on anti-cheat servers.")
            .defaultValue(2)
            .range(0, 20)
            .sliderRange(0, 20)
            .build()
    );

    private final Setting<Boolean> closeGui = sgGeneral.add(new BoolSetting.Builder()
            .name("only-when-no-screen")
            .description("Only act when no container/inventory screen is open. Recommended for survival anarchy servers.")
            .defaultValue(true)
            .build()
    );

    // ---- Item selection ----

    private final Setting<List<Item>> items = sgItems.add(new ItemListSetting.Builder()
            .name("items")
            .description("Items to keep replenished. Assigned right-to-left: first item → slot 8, second → slot 7, etc.")
            .build()
    );

    private int delayLeft = 0;

    public HotbarReplenish() {
        super(Hybridious.CATEGORY, "hotbar-replenish",
                "Refills hotbar slots from your inventory when they run low. " +
                "In configured mode each listed item is pinned to a hotbar slot (slot 8 first, then 7, 6 …). " +
                "If the slot has the wrong item it is swapped out first.");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (closeGui.get() && mc.currentScreen != null) return;

        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        if (onlyConfigured.get()) {
            // Each item in the list is pinned to a hotbar slot:
            //   items[0] → slot 8, items[1] → slot 7, … items[8] → slot 0
            // For each pinned slot we check three cases:
            //   1. Slot is empty          → pull the required item in from main inventory
            //   2. Slot has the wrong item → swap it out: send wrong item to inventory, pull required item in
            //   3. Slot has the right item but count ≤ threshold → top it up from main inventory
            List<Item> configuredItems = items.get();
            int assignCount = Math.min(configuredItems.size(), 9);

            for (int i = 0; i < assignCount; i++) {
                int  hotbar   = 8 - i;
                Item required = configuredItems.get(i);
                if (required == Items.AIR) continue;
                if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().selectedSlot) continue;

                ItemStack stack = mc.player.getInventory().getStack(hotbar);

                if (stack.isEmpty()) {
                    // Case 1: empty slot — pull required item in if we have it.
                    int src = findRefill(required);
                    if (src == -1) continue;
                    moveStack(src, hotbar);
                    delayLeft = tickDelay.get();
                    return;
                }

                if (!stack.isOf(required)) {
                    // Case 2: wrong item occupying the slot.
                    // Find a free main-inventory slot to send the wrong item to,
                    // then pull the required item into the now-empty hotbar slot.
                    int src = findRefill(required);
                    if (src == -1) continue;        // nothing to pull in, skip
                    int dest = findEmptyInvSlot();
                    if (dest == -1) continue;       // no room to stash the wrong item, skip

                    // Step A: move the wrong item out to a free inventory slot.
                    moveStack(hotbar, dest);
                    delayLeft = tickDelay.get();
                    // Step B will happen on the next eligible tick once delayLeft expires.
                    // The hotbar slot will then be empty and Case 1 will handle the rest.
                    return;
                }

                // Case 3: correct item but below threshold — top up.
                if (stack.getCount() > threshold.get()) continue;
                if (stack.getCount() >= stack.getMaxCount()) continue;

                int src = findRefill(required);
                if (src == -1) continue;
                moveStack(src, hotbar);
                delayLeft = tickDelay.get();
                return;
            }
        } else {
            // Unfiltered mode: walk slots 8 → 0, top up any stackable item below threshold.
            for (int hotbar = 8; hotbar >= 0; hotbar--) {
                if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().selectedSlot) continue;

                ItemStack stack = mc.player.getInventory().getStack(hotbar);
                if (stack.isEmpty() || stack.getMaxCount() <= 1) continue;
                if (stack.getCount() > threshold.get()) continue;
                if (stack.getCount() >= stack.getMaxCount()) continue;

                int src = findRefill(stack.getItem());
                if (src == -1) continue;
                moveStack(src, hotbar);
                delayLeft = tickDelay.get();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the best main-inventory slot (indices 9–35) containing the given item.
     * Prefers the smallest stack so partials get consolidated first.
     */
    private int findRefill(Item item) {
        int bestSlot  = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = mc.player.getInventory().getStack(slot);
            if (s.isEmpty() || !s.isOf(item)) continue;
            if (s.getCount() < bestCount) {
                bestCount = s.getCount();
                bestSlot  = slot;
            }
        }
        return bestSlot;
    }

    /**
     * Finds an empty slot in the main inventory (indices 9–35).
     * Used to stash a wrong item out of a pinned hotbar slot.
     */
    private int findEmptyInvSlot() {
        for (int slot = 9; slot <= 35; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Click-packet helpers
    // -------------------------------------------------------------------------

    /**
     * Moves a stack between two inventory slots using pickup click packets.
     *
     * - If the destination is empty: picks up the source, places it on dest. Done.
     * - If the destination has the same item: merges (Minecraft handles the math).
     *   Any remainder still on the cursor is returned to the source slot.
     * - If the destination has a different item: the two stacks swap positions
     *   (pickup src → click dest picks up dest and drops src there → pickup to
     *   place the old-dest stack back on src). This correctly handles the
     *   wrong-item-in-pinned-slot case.
     */
    private void moveStack(int fromInvSlot, int toInvSlot) {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        int fromId = invIndexToSlotId(fromInvSlot);
        int toId   = invIndexToSlotId(toInvSlot);
        if (fromId < 0 || toId < 0) return;

        ItemStack fromStack = mc.player.getInventory().getStack(fromInvSlot);
        ItemStack toStack   = mc.player.getInventory().getStack(toInvSlot);

        if (toStack.isEmpty() || toStack.isOf(fromStack.getItem())) {
            // Empty or same item: standard pickup → place, return any leftover.
            clickSlot(fromId, 0, SlotActionType.PICKUP);
            clickSlot(toId,   0, SlotActionType.PICKUP);
            if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                clickSlot(fromId, 0, SlotActionType.PICKUP);
            }
        } else {
            // Different item: swap using SWAP action (hotbar key shortcut) when the
            // destination is a hotbar slot, otherwise do a three-click cursor swap.
            if (toInvSlot >= 0 && toInvSlot <= 8) {
                // SWAP action: button = hotbar index, works for any slot → hotbar.
                clickSlot(fromId, toInvSlot, SlotActionType.SWAP);
            } else if (fromInvSlot >= 0 && fromInvSlot <= 8) {
                // Inverse: source is hotbar, destination is main inventory.
                clickSlot(toId, fromInvSlot, SlotActionType.SWAP);
            } else {
                // Both in main inventory: three-click cursor swap.
                clickSlot(fromId, 0, SlotActionType.PICKUP); // pick up from
                clickSlot(toId,   0, SlotActionType.PICKUP); // place on to, pick up to's old stack
                clickSlot(fromId, 0, SlotActionType.PICKUP); // put to's old stack into from's slot
            }
        }
    }

    private void clickSlot(int slotId, int button, SlotActionType action) {
        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                slotId, button, action,
                mc.player
        );
    }

    /**
     * Converts a player-inventory index (0–8 = hotbar, 9–35 = main) to the slot id
     * used by the survival screen handler (hotbar 0–8 → ids 36–44; main 9–35 → ids 9–35).
     */
    private int invIndexToSlotId(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8)  return 36 + invIndex;
        if (invIndex >= 9 && invIndex <= 35) return invIndex;
        return -1;
    }
}

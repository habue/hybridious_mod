package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;

import java.util.*;

public class HotbarReplenish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems   = settings.createGroup("Items");


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
            .defaultValue(false)
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
            .description("Ticks to wait between each slot move. Higher is safer on anti-cheat servers like 2b2t.")
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

    private final Setting<Boolean> lockSlots = sgGeneral.add(new BoolSetting.Builder()
            .name("lock-slots")
            .description("Actively restore managed items to their assigned hotbar slots if another mod or action moves them away.")
            .defaultValue(true)
            .build()
    );


    private final Setting<List<Item>> items = sgItems.add(new ItemListSetting.Builder()
            .name("items")
            .description("Items to keep replenished (e.g. bone_meal). Leave empty and disable 'only-configured-items' to replenish everything.")
            .build()
    );

    private static final int[] SLOT_PRIORITY = {8, 7, 6, 5, 4, 3, 2, 1, 0};


    private final Map<Item, Integer> assignedSlot = new LinkedHashMap<>();

    private int delayLeft = 0;

    private final Item[] prevHotbar = new Item[9];

    public HotbarReplenish() {
        super(Hybridious.CATEGORY, "hotbar-replenish",
                "Refills hotbar slots from your inventory when they run low and locks managed items to their assigned slots.");
    }

    @Override
    public void onActivate() {
        delayLeft = 0;
        rebuildAssignments();
        snapshotHotbar();
    }

    @Override
    public void onDeactivate() {
        assignedSlot.clear();
    }


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;
        if (closeGui.get() && mc.screen != null) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        rebuildAssignments();

        if (delayLeft > 0) {
            delayLeft--;
            snapshotHotbar();
            return;
        }

        if (lockSlots.get() && onlyConfigured.get()) {
            for (Map.Entry<Item, Integer> entry : assignedSlot.entrySet()) {
                Item managedItem = entry.getKey();
                int  hotbar      = entry.getValue();

                if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().getSelectedSlot()) continue;

                ItemStack current = mc.player.getInventory().getItem(hotbar);

                if (!current.isEmpty() && !current.is(managedItem)) {
                    int freeMain = findFreeMainSlot();
                    if (freeMain != -1) {
                        pickUpAndPlace(invIndexToSlotId(hotbar), invIndexToSlotId(freeMain));
                        delayLeft = tickDelay.get();
                        snapshotHotbar();
                        return;
                    }
                }

            }
        }

        if (onlyConfigured.get()) {
            for (int priority = 0; priority < SLOT_PRIORITY.length; priority++) {
                int hotbar = SLOT_PRIORITY[priority];

                if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().getSelectedSlot()) continue;

                Item assigned = getItemForSlot(hotbar);
                ItemStack current = mc.player.getInventory().getItem(hotbar);

                if (assigned == null) continue;

                if (current.isEmpty()) {
                    if (fillEmptySlots.get()) {
                        int sourceSlot = findRefill(assigned);
                        if (sourceSlot != -1) {
                            moveToHotbar(sourceSlot, hotbar);
                            delayLeft = tickDelay.get();
                            snapshotHotbar();
                            return;
                        }
                    }
                    continue;
                }

                if (!current.is(assigned)) continue;

                if (current.getMaxStackSize() <= 1) continue;
                if (current.getCount() > threshold.get()) continue;
                if (current.getCount() >= current.getMaxStackSize()) continue;

                int sourceSlot = findRefill(assigned);
                if (sourceSlot == -1) continue;

                moveToHotbar(sourceSlot, hotbar);
                delayLeft = tickDelay.get();
                snapshotHotbar();
                return;
            }
        } else {
            for (int hotbar = 0; hotbar < 9; hotbar++) {
                if (dontReplaceHeld.get() && hotbar == mc.player.getInventory().getSelectedSlot()) continue;

                ItemStack stack = mc.player.getInventory().getItem(hotbar);

                if (stack.isEmpty()) {
                    continue;
                }

                if (stack.getMaxStackSize() <= 1) continue;
                if (stack.getCount() > threshold.get()) continue;
                if (stack.getCount() >= stack.getMaxStackSize()) continue;

                int sourceSlot = findRefill(stack.getItem());
                if (sourceSlot == -1) continue;

                moveToHotbar(sourceSlot, hotbar);
                delayLeft = tickDelay.get();
                snapshotHotbar();
                return;
            }
        }

        snapshotHotbar();
    }

    private void rebuildAssignments() {
        List<Item> configured = items.get();

        List<Item> valid = new ArrayList<>();
        for (Item item : configured) {
            if (item != Items.AIR) valid.add(item);
        }

        if (assignedSlot.keySet().equals(new LinkedHashSet<>(valid))) return;

        assignedSlot.clear();
        for (int i = 0; i < valid.size() && i < SLOT_PRIORITY.length; i++) {
            assignedSlot.put(valid.get(i), SLOT_PRIORITY[i]);
        }
    }

    private Item getItemForSlot(int hotbar) {
        for (Map.Entry<Item, Integer> e : assignedSlot.entrySet()) {
            if (e.getValue() == hotbar) return e.getKey();
        }
        return null;
    }


    private boolean containsItem(Item item) {
        for (Item i : items.get()) if (i == item) return true;
        return false;
    }

    private int findRefill(Item item) {
        int bestSlot  = -1;
        int bestCount = Integer.MAX_VALUE;

        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (s.isEmpty() || !s.is(item)) continue;
            if (s.getCount() < bestCount) {
                bestCount = s.getCount();
                bestSlot  = slot;
            }
        }
        return bestSlot;
    }

    private int findFreeMainSlot() {
        for (int slot = 9; slot <= 35; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private void moveToHotbar(int fromInvSlot, int toHotbarSlot) {
        int fromId = invIndexToSlotId(fromInvSlot);
        int toId   = invIndexToSlotId(toHotbarSlot);
        if (fromId < 0 || toId < 0) return;

        pickUpAndPlace(fromId, toId);
    }

    private void pickUpAndPlace(int fromSlotId, int toSlotId) {
        clickSlot(fromSlotId, 0, ContainerInput.PICKUP);
        clickSlot(toSlotId,   0, ContainerInput.PICKUP);

        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            clickSlot(fromSlotId, 0, ContainerInput.PICKUP);
        }
    }

    private void clickSlot(int slotId, int button, ContainerInput action) {
        mc.gameMode.handleContainerInput(
                mc.player.containerMenu.containerId,
                slotId,
                button,
                action,
                mc.player
        );
    }

    private int invIndexToSlotId(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8)  return 36 + invIndex;
        if (invIndex >= 9 && invIndex <= 35) return invIndex;
        return -1;
    }

    private void snapshotHotbar() {
        if (mc.player == null) return;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            prevHotbar[i] = s.isEmpty() ? Items.AIR : s.getItem();
        }
    }
}

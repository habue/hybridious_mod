package dev.hybridious.modules;

import dev.hybridious.Hybridious;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories; // only if you fall back to a built-in category
import meteordevelopment.orbit.EventHandler;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class InventoryCleaner extends Module {
    // SettingGroup is the container Meteor renders in the GUI for this module
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    
    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
            .name("items")
            .description("Items to automatically drop from inventory and hotbar.")
            .defaultValue(
                    // Defaults requested earlier. List.of(...) is fine; Meteor copies it into its own mutable list.
                    Items.WHEAT_SEEDS,
                    Items.AZALEA,
                    Items.FLOWERING_AZALEA,
                    Items.MOSS_CARPET,
                    Items.STRING,
                    Items.SPIDER_EYE,
                    Items.NETHERRACK,
                    Items.COBBLESTONE,
                    Items.BONE                  // "bones" -> the item id is singular minecraft:bone
            )
            .build()
    );

    // Whether to also sweep the inventory immediately when the module is toggled on
    private final Setting<Boolean> cleanOnEnable = sgGeneral.add(new BoolSetting.Builder()
            .name("clean-on-enable")
            .description("Drop all listed items the moment the module is turned on.")
            .defaultValue(true)
            .build()
    );

    // Throttle: how many ticks between drop actions, so we don't spam clicks in one tick
    // (relevant on 2b2t — see notes below)
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay-ticks")
            .description("Ticks to wait between dropping individual stacks.")
            .defaultValue(2)
            .min(0)
            .sliderMax(20)
            .build()
    );

    private int tickCounter;

    public InventoryCleaner() {
        super(Hybridious.CATEGORY, "inventory-cleaner", "Drops blacklisted items from inventory and hotbar.");
    }

    // Exposed so the command can read/modify the same list this module uses.
    public Setting<List<Item>> getItemsSetting() {
        return items;
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        if (cleanOnEnable.get()) {
            // We don't drop here directly — mc.player may be mid-tick. We let the first TickEvent.Pre handle it.
            tickCounter = delay.get(); // force an immediate sweep on the next tick
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // mc is a protected field on Module (the MinecraftClient instance). Guard against null during world load.
        if (mc.player == null || mc.interactionManager == null) return;

        List<Item> list = items.get();
        if (list.isEmpty()) return;

        // Simple per-tick throttle
        if (tickCounter < delay.get()) {
            tickCounter++;
            return;
        }
        tickCounter = 0;

        // Main player inventory has 36 slots (0-8 hotbar, 9-35 main). We scan all of them.
        // We drop at most one stack per throttle window to stay gentle on the server.
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;

            if (list.contains(stack.getItem())) {
                dropSlot(slot);
                return; // one drop per window; next tick handles the next item
            }
        }
    }

    // Translate an inventory index (0-35) into the container-slot index the screen handler expects,
    // then throw the stack. This is the fiddly part — see notes.
    private void dropSlot(int inventorySlot) {
        // The player's own inventory screen handler maps slots differently from the raw inventory index.
        // For the survival inventory: hotbar slots 0-8 map to handler slots 36-44; main slots 9-35 map to 9-35.
        int handlerSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;

        // SlotActionType.THROW with button 1 drops the *entire* stack. Button 0 drops a single item.
        mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId, // syncId of the player's inventory container
                handlerSlot,
                1,                                    // 1 = drop whole stack
                SlotActionType.THROW,
                mc.player
        );
    }
}

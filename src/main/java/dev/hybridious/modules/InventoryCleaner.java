package dev.hybridious.modules;

import dev.hybridious.Hybridious;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories; 
import meteordevelopment.orbit.EventHandler;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class InventoryCleaner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
            .name("items")
            .description("Items to automatically drop from inventory and hotbar.")
            .defaultValue(
                    Items.WHEAT_SEEDS,
                    Items.AZALEA,
                    Items.FLOWERING_AZALEA,
                    Items.MOSS_CARPET,
                    Items.STRING,
                    Items.SPIDER_EYE,
                    Items.NETHERRACK,
                    Items.COBBLESTONE,
                    Items.BONE,
                    Items.EGG,
                    Items.DIRT,
                    Items.LEATHER_HELMET,        // "Leather Cap"
                    Items.LEATHER_CHESTPLATE,    // "Leather Tunic"
                    Items.LEATHER_LEGGINGS,      // "Leather Pants"
                    Items.LEATHER_BOOTS,
                    Items.GOLDEN_CHESTPLATE,
                    Items.GOLDEN_LEGGINGS,
                    Items.GOLDEN_HELMET,
                    Items.GOLDEN_BOOTS,
                    Items.ROTTEN_FLESH,
                    Items.DANDELION,
                    Items.SUNFLOWER,
                    Items.CORNFLOWER,
                    Items.SNOW,
                    Items.SNOWBALL,
                    Items.BOW,
                    Items.ARROW,
                    Items.IRON_INGOT,
                    Items.SANDSTONE,
                    Items.SAND,
                    Items.FLOWERING_AZALEA_LEAVES,
                    Items.AZALEA_LEAVES,
                    Items.PALE_MOSS_CARPET,     
                    Items.GUNPOWDER,
                    Items.OAK_LOG,
                    Items.POPPY,
                    Items.MUTTON,                // "Raw Mutton"
                    Items.COOKED_MUTTON,
                    Items.CROSSBOW,
                    Items.GOLDEN_SWORD               // "bones" -> the item id is singular minecraft:bone
            )
            .build()
    );


    private final Setting<Boolean> cleanOnEnable = sgGeneral.add(new BoolSetting.Builder()
            .name("clean-on-enable")
            .description("Drop all listed items the moment the module is turned on.")
            .defaultValue(true)
            .build()
    );


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


    public Setting<List<Item>> getItemsSetting() {
        return items;
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        if (cleanOnEnable.get()) {
              tickCounter = delay.get(); 
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        List<Item> list = items.get();
        if (list.isEmpty()) return;

        if (tickCounter < delay.get()) {
            tickCounter++;
            return;
        }
        tickCounter = 0;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;

            if (list.contains(stack.getItem())) {
                dropSlot(slot);
                return; // one drop per window; next tick handles the next item
            }
        }
    }

    private void dropSlot(int inventorySlot) {
        int handlerSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;

        mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId, // syncId of the player's inventory container
                handlerSlot,
                1,                                    // 1 = drop whole stack
                SlotActionType.THROW,
                mc.player
        );
    }
}

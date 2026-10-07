package dev.hybridious.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class InventoryUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static int countItemsInInventory(Item item) {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                if (stack.getItem() == item) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    public static int countEmptySlots() {
        int emptyCount = 0;
        assert mc.player != null;
        for (ItemStack itemStack : mc.player.getInventory().getNonEquipmentItems()) {
            if (itemStack.isEmpty()) emptyCount++;
        }
        return emptyCount;
    }

    public static int getSlotWithItem(Item item) {
        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }

    public static void moveStackBetweenSlots(int pickupSlot, int dumpSlot) {
        if (!(mc.screen instanceof InventoryScreen)) return;

        InventoryMenu handler = mc.player.inventoryMenu;

        mc.gameMode.handleContainerInput(handler.containerId, pickupSlot, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(handler.containerId, dumpSlot,   0, ContainerInput.PICKUP, mc.player);
    }

    public static void quickMove(Slot slot) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
    }

    public static void sendStartBreakBlockPacket(BlockPos pos) {
        if (mc.gameMode == null || mc.level == null) return;
        mc.gameMode.startDestroyBlock(pos, Direction.UP);
    }
}

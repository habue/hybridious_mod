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
        for (int i = 0; i < mc.player.getInventory().main.size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
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
        for (ItemStack itemStack : mc.player.getInventory().main) {
            if (itemStack.isEmpty()) emptyCount++;
        }
        return emptyCount;
    }

    public static int getSlotWithItem(Item item) {
        for (int i = 0; i < mc.player.getInventory().main.size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }

    public static void moveStackBetweenSlots(int pickupSlot, int dumpSlot) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;

        InventoryMenu handler = mc.player.playerScreenInteractionHandler;

        mc.interactionManager.clickSlot(handler.syncId, pickupSlot, 0, ContainerInput.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, dumpSlot,   0, ContainerInput.PICKUP, mc.player);
    }

    public static void quickMove(Slot slot) {
        mc.interactionManager.clickSlot(mc.player.currentScreenInteractionHandler.syncId, slot.getIndex(), 0, ContainerInput.QUICK_MOVE, mc.player);
    }

    public static void sendStartBreakBlockPacket(BlockPos pos) {
        if (mc.interactionManager == null || mc.world == null) return;
        mc.interactionManager.sendSequencedPacket(mc.world, (sequence) ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, sequence)
        );
    }
}

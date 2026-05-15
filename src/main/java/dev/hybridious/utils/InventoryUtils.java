package dev.hybridious.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class InventoryUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

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

        PlayerScreenHandler handler = mc.player.playerScreenHandler;

        mc.interactionManager.clickSlot(handler.syncId, pickupSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, dumpSlot,   0, SlotActionType.PICKUP, mc.player);
    }

    public static void quickMove(Slot slot) {
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot.getIndex(), 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    public static void sendStartBreakBlockPacket(BlockPos pos) {
        if (mc.interactionManager == null || mc.world == null) return;
        mc.interactionManager.sendSequencedPacket(mc.world, (sequence) ->
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, sequence)
        );
    }
}

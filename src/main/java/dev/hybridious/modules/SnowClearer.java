package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import dev.hybridious.utils.InventoryUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class SnowClearer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("range").description("The range to search for snow.")
            .defaultValue(5).sliderMin(1).sliderMax(6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay").description("Delay between breaking snow blocks in ticks.")
            .defaultValue(0).build());

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick").description("How many blocks to break per tick.")
            .defaultValue(5).sliderMin(1).sliderMax(25).build());

    private final Setting<Boolean> requireShovel = sgGeneral.add(new BoolSetting.Builder()
            .name("require-shovel")
            .description("Only break snow when a shovel is in the hotbar.")
            .defaultValue(true).build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
            .name("swap-back")
            .description("Restore previous hotbar slot after breaking.")
            .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate").description("Rotate towards snow blocks when breaking them.")
            .defaultValue(false).build());

    private int timer = 0;
    private List<BlockPos> cachedTargets = new ArrayList<>();

    public SnowClearer() {
        super(Hybridious.CATEGORY, "SnowClearer", "Automatically breaks snow layers around you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        tick();
    }

    /** Called directly by AutoMoss when it controls scheduling. */
    public void tick() {
        if (mc.player == null || mc.world == null) return;
        if (timer > 0) { timer--; return; }

        cachedTargets = findTargets();
        if (cachedTargets.isEmpty()) return;

        // Only check for shovel; don't swap unless we have something to break
        FindItemResult shovel = null;
        if (requireShovel.get()) {
            shovel = InvUtils.findInHotbar(
                    Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL,
                    Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL);
            if (!shovel.found()) return;
        }

        cachedTargets.sort((a, b) -> Double.compare(
                mc.player.squaredDistanceTo(Vec3d.ofCenter(a)),
                mc.player.squaredDistanceTo(Vec3d.ofCenter(b))));

        // Remember current slot so we can swap back
        int prevSlot = mc.player.getInventory().selectedSlot;
        boolean swappedThisTick = false;

        int count = 0;
        for (BlockPos pos : cachedTargets) {
            // Lazy swap: only switch to shovel right before first break
            if (requireShovel.get() && !swappedThisTick && shovel != null) {
                InvUtils.swap(shovel.slot(), false);
                swappedThisTick = true;
            }

            if (BlockUtils.canInstaBreak(pos)) {
                InventoryUtils.sendStartBreakBlockPacket(pos);
                count++;
            } else if (BlockUtils.breakBlock(pos, rotate.get())) {
                count++;
            }
            if (count >= bpt.get()) { timer = delay.get(); break; }
        }

        // Restore previous slot so player isn't stuck holding shovel
        if (swappedThisTick && swapBack.get()) {
            mc.player.getInventory().selectedSlot = prevSlot;
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.world == null) return targets;
        BlockPos origin = mc.player.getBlockPos();
        int r = range.get();
        double rangeSq = (double) r * r;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x*x + y*y + z*z > rangeSq) continue;
                    BlockPos pos = origin.add(x, y, z);
                    net.minecraft.block.Block block = mc.world.getBlockState(pos).getBlock();
                    if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK)
                        targets.add(pos);
                }
            }
        }
        return targets;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null || mc.world == null) return null;
        return String.valueOf(cachedTargets.size());
    }
}

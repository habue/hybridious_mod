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
import net.minecraft.block.*;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class LawnMower extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("range")
            .description("The range to search for vegetation.")
            .defaultValue(5).sliderMin(1).sliderMax(6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay between breaking blocks in ticks.")
            .defaultValue(0).build());

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick")
            .description("How many blocks to break per tick.")
            .defaultValue(1).sliderMin(1).sliderMax(25).build());

    public final Setting<List<Block>> blocksToBreakList = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks-to-break")
            .description("Which blocks to break.")
            .defaultValue(
                    Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.DEAD_BUSH, Blocks.FERN, Blocks.LARGE_FERN,
                    Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.POPPY, Blocks.DANDELION, Blocks.BLUE_ORCHID,
                    Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP,
                    Blocks.PINK_TULIP, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY,
                    Blocks.WITHER_ROSE, Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY,
                    Blocks.ACACIA_SAPLING, Blocks.BAMBOO_SAPLING, Blocks.BIRCH_SAPLING, Blocks.CHERRY_SAPLING,
                    Blocks.OAK_SAPLING, Blocks.DARK_OAK_SAPLING, Blocks.JUNGLE_SAPLING, Blocks.PALE_OAK_SAPLING)
            .build());

    private final Setting<Boolean> switchToShears = sgGeneral.add(new BoolSetting.Builder()
            .name("switch-to-shears")
            .description("Automatically switch to shears when breaking vegetation.")
            .defaultValue(false).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotate towards blocks when breaking them.")
            .defaultValue(false).build());

    private int timer = 0;
    private List<BlockPos> cachedTargets = new ArrayList<>();

    public LawnMower() {
        super(Hybridious.CATEGORY, "LawnMower", "Automatically breaks grass, flowers, and saplings around you.");
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

        cachedTargets.sort((a, b) -> Double.compare(
                mc.player.squaredDistanceTo(Vec3d.ofCenter(a)),
                mc.player.squaredDistanceTo(Vec3d.ofCenter(b))));

        if (switchToShears.get()) {
            FindItemResult shears = InvUtils.find(Items.SHEARS);
            if (shears.found()) InvUtils.swap(shears.slot(), false);
        }

        int count = 0;
        for (BlockPos pos : cachedTargets) {
            if (BlockUtils.canInstaBreak(pos)) {
                InventoryUtils.sendStartBreakBlockPacket(pos);
                count++;
            } else if (BlockUtils.breakBlock(pos, rotate.get())) {
                count++;
            }
            if (count >= bpt.get()) { timer = delay.get(); break; }
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        BlockPos origin = mc.player.getBlockPos();
        int r = range.get();
        double rangeSq = (double) r * r;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x*x + y*y + z*z > rangeSq) continue;
                    BlockPos pos = origin.add(x, y, z);
                    if (blocksToBreakList.get().contains(mc.world.getBlockState(pos).getBlock()))
                        targets.add(pos);
                }
            }
        }
        return targets;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(cachedTargets.size());
    }
}

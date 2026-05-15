package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import dev.hybridious.utils.InventoryUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class SnowClearer extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("range")
            .description("The range to search for snow.")
            .defaultValue(5)
            .sliderMin(1)
            .sliderMax(6)
            .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay between breaking snow blocks in ticks.")
            .defaultValue(0)
            .build()
    );

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick")
            .description("How many blocks you are allowed to break in a single tick")
            .defaultValue(5)
            .sliderMin(1)
            .sliderMax(25)
            .build()
    );

    private final Setting<Boolean> requireShovel = sgGeneral.add(new BoolSetting.Builder()
            .name("require-shovel")
            .description("Only break snow when a shovel is available in the hotbar.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotate towards snow blocks when breaking them.")
            .defaultValue(false)
            .build()
    );

    private int timer = 0;

    public SnowClearer() {
        super(Hybridious.CATEGORY, "SnowClearer", "Automatically breaks snow layers around you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // Find and switch to shovel if required
        if (requireShovel.get()) {
            FindItemResult shovel = InvUtils.findInHotbar(
                    Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL,
                    Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL
            );
            if (!shovel.found()) return;
            InvUtils.swap(shovel.slot(), false);
        }

        List<BlockPos> targets = findTargets();
        if (targets.isEmpty()) return;

        targets.sort((a, b) -> Double.compare(
                mc.player.squaredDistanceTo(Vec3d.ofCenter(a)),
                mc.player.squaredDistanceTo(Vec3d.ofCenter(b))
        ));

        int count = 0;
        for(BlockPos pos : targets) {
            if (BlockUtils.canInstaBreak(pos)) {
                InventoryUtils.sendStartBreakBlockPacket(pos);
                count++;
            } else if (BlockUtils.breakBlock(pos, rotate.get())) {
                count++;
            }
            if (count >= bpt.get()) {
                timer = delay.get();
                break;
            }
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();
        int r = range.get();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    Block block = mc.world.getBlockState(pos).getBlock();
                    if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK) {
                        targets.add(pos);
                    }
                }
            }
        }

        return targets;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null || mc.world == null) return null;
        return String.valueOf(findTargets().size());
    }
}

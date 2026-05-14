package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class automoss extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMoss = settings.createGroup("Moss");
    private final SettingGroup sgTrees = settings.createGroup("Trees");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("The range to search for blocks to bonemeal.")
            .defaultValue(4.5)
            .min(1)
            .sliderMax(6)
            .build()
    );

    // Thx codysmile11 for the idea!
    private final Setting<Boolean> fullAuto = sgGeneral.add(new BoolSetting.Builder()
            .name("full-auto")
            .description("Enables full auto mode: starts LawnMower, SnowClearer, and baritone grass mining on activation.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> breakGrassForMoss = sgGeneral.add(new BoolSetting.Builder()
            .name("Toggle-LawnMower")
            .description("Tells LawnMower to break grass so moss can spread more effectively.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> flipFlop = sgGeneral.add(new BoolSetting.Builder()
            .name("flip-flop")
            .description("Alternates between breaking snow (via SnowClearer) and placing moss to reduce glitchiness.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> flipFlopTicks = sgGeneral.add(new IntSetting.Builder()
            .name("flip-flop-ticks")
            .description("How many ticks to spend in each flip-flop phase (snow break vs moss place).")
            .defaultValue(10)
            .min(1)
            .sliderMax(40)
            .build()
    );

    private final Setting<Boolean> inventoryAllow = sgGeneral.add(new BoolSetting.Builder()
            .name("inventory-allow")
            .description("Take bone meal from inventory when hotbar is empty.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay between bone meal uses in ticks.")
            .defaultValue(2)
            .min(0)
            .sliderMax(20)
            .build()
    );

    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("max-uses-per-tick")
            .description("Maximum number of bone meal uses per tick.")
            .defaultValue(1)
            .min(1)
            .sliderMax(5)
            .build()
    );

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
            .name("moss-cooldown")
            .description("Cooldown in ticks before bone mealing the same moss block again.")
            .defaultValue(100)
            .min(20)
            .sliderMax(200)
            .build()
    );

    private final Setting<Boolean> makeTrees = sgTrees.add(new BoolSetting.Builder()
            .name("make-trees")
            .description("Use bone meal on azalea bushes and saplings to grow them into trees.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> azaleaTreeFraction = sgTrees.add(new IntSetting.Builder()
            .name("azalea-tree-fraction")
            .description("1-in-10 chance per roll to bonemeal an azalea bush. E.g. 3 = 3/10 chance.")
            .defaultValue(10)
            .min(1)
            .sliderMax(10)
            .build()
    );

    private final Setting<Integer> azaleaCooldownSetting = sgTrees.add(new IntSetting.Builder()
            .name("azalea-cooldown")
            .description("Ticks to wait before bonemealing the same azalea bush again. Higher = slower.")
            .defaultValue(200)
            .min(20)
            .sliderMax(10000)
            .build()
    );

    private boolean flipFlopPhase = false;
    private int flipFlopTimer = 0;
    private boolean baritoneStopped = false;
    private boolean eatingBaritoneStop = false;
    private boolean wasEating = false;
    private int delayTimer = 0;
    private final Map<BlockPos, Integer> recentlyUsedMoss = new HashMap<>();
    private final Map<BlockPos, Integer> azaleaCooldownMap = new HashMap<>();

    public automoss() {
        super(Hybridious.CATEGORY, "AutoMoss", "Automatically uses bone meal on specific blocks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        flipFlopPhase = false;
        flipFlopTimer = 0;
        baritoneStopped = false;
        eatingBaritoneStop = false;
        wasEating = false;

        LawnMower lawnMower = Modules.get().get(LawnMower.class);
        if (lawnMower != null) {
            lawnMower.breakGrass.set(breakGrassForMoss.get());
        }

        if (fullAuto.get()) {
            if (lawnMower != null && !lawnMower.isActive()) lawnMower.toggle();

            SnowClearer snowClearer = Modules.get().get(SnowClearer.class);
            if (snowClearer != null && !snowClearer.isActive()) snowClearer.toggle();

            mc.player.networkHandler.sendChatMessage("#settings acceptableThrowawayItems");
            mc.player.networkHandler.sendChatMessage("#mine grass_block");
        }
    }

    @Override
    public void onDeactivate() {
        if (fullAuto.get()) {
            LawnMower lawnMower = Modules.get().get(LawnMower.class);
            if (lawnMower != null && lawnMower.isActive()) lawnMower.toggle();

            SnowClearer snowClearer = Modules.get().get(SnowClearer.class);
            if (snowClearer != null && snowClearer.isActive()) snowClearer.toggle();

            if (mc.player != null) mc.player.networkHandler.sendChatMessage("#stop");
        }
        recentlyUsedMoss.clear();
        azaleaCooldownMap.clear();
        baritoneStopped = false;
        eatingBaritoneStop = false;
        wasEating = false;
    }

    private boolean isEatingProtectedFood() {
        if (mc.player == null) return false;
        if (mc.player.isUsingItem()) {
            net.minecraft.item.Item item = mc.player.getActiveItem().getItem();
            return item == Items.ENCHANTED_GOLDEN_APPLE ||
                    item == Items.GOLDEN_CARROT ||
                    item == Items.COOKED_BEEF;
        }
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Pause all actions while eating a protected food item
        if (isEatingProtectedFood()) {
            wasEating = true;
            if (fullAuto.get() && !eatingBaritoneStop && !baritoneStopped) {
                mc.player.networkHandler.sendChatMessage("#stop");
                eatingBaritoneStop = true;
                baritoneStopped = true;
            }
            return;
        }

        // Just finished eating — explicitly restart baritone
        if (wasEating) {
            wasEating = false;
            if (eatingBaritoneStop) {
                eatingBaritoneStop = false;
                if (fullAuto.get() && countBoneMeal() > 0) {
                    baritoneStopped = false;
                    mc.player.networkHandler.sendChatMessage("#mine grass_block");
                }
            }
        }

        // Always check if player is clipped into an azalea bush
        checkAndBreakStuckBlock();

        // Sync LawnMower grass setting
        LawnMower lawnMower = Modules.get().get(LawnMower.class);
        if (lawnMower != null) {
            lawnMower.breakGrass.set(breakGrassForMoss.get());
        }

        // Manage baritone based on bone meal supply
        if (fullAuto.get() && !eatingBaritoneStop) {
            manageBaritoneBoneMeal();
        }

        // Handle flip-flop phasing
        if (flipFlop.get()) {
            flipFlopTimer--;
            if (flipFlopTimer <= 0) {
                flipFlopPhase = !flipFlopPhase;
                flipFlopTimer = flipFlopTicks.get();

                SnowClearer snowClearer = Modules.get().get(SnowClearer.class);
                if (snowClearer != null) {
                    if (flipFlopPhase && !snowClearer.isActive()) {
                        snowClearer.toggle();
                    } else if (!flipFlopPhase && snowClearer.isActive()) {
                        snowClearer.toggle();
                    }
                }
            }

            if (flipFlopPhase) return;
        }

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        updateMossCooldowns();
        updateAzaleaCooldowns();

        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;

        int uses = 0;
        List<BlockPos> targets = findTargets();

        for (BlockPos blockPos : targets) {
            if (uses >= maxUsesPerTick.get()) break;

            BlockState state = mc.world.getBlockState(blockPos);
            Block block = state.getBlock();
            boolean isMoss = block.getTranslationKey().contains("moss_block");

            if (isMoss && recentlyUsedMoss.containsKey(blockPos)) continue;

            if (BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, blockPos)) {
                Vec3d hitPos = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, blockPos, false);

                int prevSelectedSlot = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = boneMealSlot;

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);

                mc.player.getInventory().selectedSlot = prevSelectedSlot;

                if (isMoss) {
                    recentlyUsedMoss.put(new BlockPos(blockPos), mossSpreadCooldown.get());
                }

                uses++;
                delayTimer = delay.get();
            }
        }
    }

    private void checkAndBreakStuckBlock() {
        if (mc.player == null || mc.world == null) return;

        BlockPos feetPos = mc.player.getBlockPos();
        BlockPos headPos = feetPos.up();

        for (BlockPos checkPos : new BlockPos[]{feetPos, headPos}) {
            BlockState state = mc.world.getBlockState(checkPos);

            if (state.isAir()) continue;

            String blockName = state.getBlock().getTranslationKey().toLowerCase();

            boolean isAzaleaBush = blockName.equals("block.minecraft.azalea") ||
                    blockName.equals("block.minecraft.flowering_azalea");

            if (!isAzaleaBush) continue;

            net.minecraft.util.math.Box blockBox = new net.minecraft.util.math.Box(
                    checkPos.getX(), checkPos.getY(), checkPos.getZ(),
                    checkPos.getX() + 1, checkPos.getY() + 1, checkPos.getZ() + 1
            );

            if (mc.player.getBoundingBox().intersects(blockBox)) {
                mc.interactionManager.attackBlock(checkPos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                break;
            }
        }
    }

    private void manageBaritoneBoneMeal() {
        if (mc.player == null) return;

        int totalBoneMeal = countBoneMeal();

        if (totalBoneMeal == 0 && !baritoneStopped) {
            mc.player.networkHandler.sendChatMessage("#stop");
            baritoneStopped = true;
        } else if (totalBoneMeal > 0 && baritoneStopped) {
            baritoneStopped = false;
            mc.player.networkHandler.sendChatMessage("#mine grass_block");
        }
    }

    private int countBoneMeal() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) {
                total += mc.player.getInventory().getStack(i).getCount();
            }
        }
        return total;
    }

    private void updateMossCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> it = recentlyUsedMoss.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) it.remove();
            else entry.setValue(cooldown);
        }
    }

    private void updateAzaleaCooldowns() {
        Iterator<Map.Entry<BlockPos, Integer>> it = azaleaCooldownMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int cooldown = entry.getValue() - 1;
            if (cooldown <= 0) it.remove();
            else entry.setValue(cooldown);
        }
    }

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.world == null) return targets;

        double rangeSq = range.get() * range.get();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int x = (int) -range.get(); x <= range.get(); x++) {
            for (int y = (int) -range.get(); y <= range.get(); y++) {
                for (int z = (int) -range.get(); z <= range.get(); z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (pos.getSquaredDistance(playerPos) > rangeSq) continue;
                    if (!hasLineOfSight(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    String blockName = block.getTranslationKey().toLowerCase();

                    if (makeTrees.get()) {
                        boolean isAzalea = blockName.contains("azalea") && !blockName.contains("tree");
                        boolean isSapling = blockName.contains("sapling");

                        if (isAzalea) {
                            if (!azaleaCooldownMap.containsKey(pos)) {
                                int roll = (int)(Math.random() * 10);
                                if (roll < azaleaTreeFraction.get()) {
                                    targets.add(pos);
                                }
                                azaleaCooldownMap.put(pos, azaleaCooldownSetting.get());
                            }
                            continue;
                        }

                        if (isSapling) {
                            targets.add(pos);
                            continue;
                        }
                    }

                    boolean isMoss = blockName.contains("moss_block");
                    if (isMoss && hasValidNeighbor(pos)) {
                        targets.add(pos);
                    }
                }
            }
        }

        return targets;
    }

    private boolean hasValidNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();
            String blockName = neighborBlock.getTranslationKey().toLowerCase();

            if (blockName.contains("azalea") ||
                    blockName.contains("tall_grass") ||
                    blockName.contains("grass") && !blockName.contains("block") ||
                    blockName.contains("moss_block") ||
                    blockName.contains("moss_carpet")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d blockPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        RaycastContext context = new RaycastContext(
                eyePos, blockPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
        );

        BlockHitResult result = mc.world.raycast(context);
        return result.getBlockPos().equals(pos);
    }

    private int findBoneMealSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) {
                return i;
            }
        }

        if (inventoryAllow.get()) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) {
                    int emptySlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getStack(j).isEmpty()) {
                            emptySlot = j;
                            break;
                        }
                    }
                    if (emptySlot != -1) {
                        mc.interactionManager.clickSlot(0, i, emptySlot, SlotActionType.SWAP, mc.player);
                        return emptySlot;
                    }
                    break;
                }
            }
        }

        return -1;
    }
}

package dev.hybridious.modules;

import baritone.api.BaritoneAPI;import dev.hybridious.Hybridious;import dev.hybridious.utils.InventoryUtils;import meteordevelopment.meteorclient.events.world.TickEvent;import meteordevelopment.meteorclient.settings.*;import meteordevelopment.meteorclient.systems.modules.Module;import meteordevelopment.meteorclient.systems.modules.Modules;import meteordevelopment.meteorclient.utils.player.Rotations;import meteordevelopment.orbit.EventHandler;import net.minecraft.block.Block;import net.minecraft.block.BlockState;import net.minecraft.block.Blocks;import net.minecraft.client.gui.screen.ingame.InventoryScreen;import net.minecraft.entity.EntityPose;import net.minecraft.item.BlockItem;import net.minecraft.item.BoneMealItem;import net.minecraft.item.Items;import net.minecraft.item.SwordItem;import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;import net.minecraft.registry.Registries;import net.minecraft.screen.slot.SlotActionType;import net.minecraft.util.Hand;import net.minecraft.util.hit.BlockHitResult;import net.minecraft.util.math.BlockPos;import net.minecraft.util.math.Direction;import net.minecraft.util.math.Vec3d;import net.minecraft.world.RaycastContext;

import java.util.ArrayList;import java.util.HashMap;import java.util.HashSet;import java.util.Iterator;import java.util.List;import java.util.Map;import java.util.Set;

public class automoss extends Module {private final SettingGroup sgGeneral = settings.getDefaultGroup();private final SettingGroup sgMoss    = settings.createGroup("Moss");private final SettingGroup sgTrees   = settings.createGroup("Trees");private final SettingGroup sgUnstuck = settings.createGroup("Unstuck");private final SettingGroup sgConfine = settings.createGroup("Confine");private final SettingGroup sgReset   = settings.createGroup("Reset");

    private boolean pendingResetAll = false;

    private final Setting<Boolean> resetAllSettings = sgReset.add(new BoolSetting.Builder()
            .name("reset-all-settings")
            .description("Click to reset every AutoMoss setting back to its default.")
            .defaultValue(false)
            .onChanged(v -> { if (v) pendingResetAll = true; })
            .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Range to search for blocks to bonemeal.")
            .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Boolean> fullAuto = sgGeneral.add(new BoolSetting.Builder()
            .name("full-auto")
            .description("Starts Baritone roaming/mining and the roam-only helpers on activation.")
            .defaultValue(true).build());

    private final Setting<List<Block>> pathfindBlocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("pathfind-blocks")
            .description("Surface block types to roam across.")
            .defaultValue(new ArrayList<>(List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.DIRT)))
            .visible(fullAuto::get).build());

    private final Setting<Boolean> scanByRenderDistance = sgGeneral.add(new BoolSetting.Builder()
            .name("scan-by-render-distance")
            .description("Derive the pathfind scan radius from the client render distance.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> pathfindScanRadius = sgGeneral.add(new IntSetting.Builder()
            .name("pathfind-scan-radius")
            .description("Fixed maximum horizontal radius (blocks) to scan for pathfind targets.")
            .defaultValue(16).min(2).sliderMax(256)
            .visible(() -> fullAuto.get() && !scanByRenderDistance.get()).build());

    private final Setting<Boolean> adaptiveScan = sgGeneral.add(new BoolSetting.Builder()
            .name("adaptive-scan")
            .description("Grow/shrink the effective scan radius based on local work density.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> adaptiveScanMin = sgGeneral.add(new IntSetting.Builder()
            .name("adaptive-scan-min")
            .description("Smallest radius the adaptive scan will shrink to.")
            .defaultValue(4).min(2).sliderMax(24)
            .visible(() -> fullAuto.get() && adaptiveScan.get()).build());

    private final Setting<Integer> adaptiveScanStep = sgGeneral.add(new IntSetting.Builder()
            .name("adaptive-scan-step")
            .description("How many blocks the effective radius changes per adjustment.")
            .defaultValue(2).min(1).sliderMax(8)
            .visible(() -> fullAuto.get() && adaptiveScan.get()).build());

    private final Setting<Integer> adaptiveScanInterval = sgGeneral.add(new IntSetting.Builder()
            .name("adaptive-scan-interval")
            .description("Ticks between adaptive radius adjustments.")
            .defaultValue(20).min(5).sliderMax(100)
            .visible(() -> fullAuto.get() && adaptiveScan.get()).build());

    private final Setting<Integer> adaptiveDenseTargets = sgGeneral.add(new IntSetting.Builder()
            .name("adaptive-dense-threshold")
            .description("Target count threshold to consider the area dense.")
            .defaultValue(12).min(1).sliderMax(60)
            .visible(() -> fullAuto.get() && adaptiveScan.get()).build());

    private final Setting<Integer> pathfindVerticalScan = sgGeneral.add(new IntSetting.Builder()
            .name("pathfind-vertical-scan")
            .description("Vertical range (blocks up/down) to search for the surface block.")
            .defaultValue(6).min(1).sliderMax(20)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> surfaceOnly = sgGeneral.add(new BoolSetting.Builder()
            .name("surface-only")
            .description("Only path to blocks open to the sky.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> maxDescend = sgGeneral.add(new IntSetting.Builder()
            .name("max-descend")
            .description("Ignore surface targets more than this many blocks below the player.")
            .defaultValue(4).min(1).sliderMax(32)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> allowBreak = sgGeneral.add(new BoolSetting.Builder()
            .name("allow-break")
            .description("Let Baritone break blocks while pathing.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> keepMoving = sgGeneral.add(new BoolSetting.Builder()
            .name("keep-moving")
            .description("Work the NEAREST unworked surface first.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> mowBeforeMoving = sgGeneral.add(new BoolSetting.Builder()
            .name("mow-before-moving")
            .description("Stay until the local area is fully mossed before moving on.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> settleTicks = sgGeneral.add(new IntSetting.Builder()
            .name("settle-ticks")
            .description("Consecutive idle ticks before the area counts as done.")
            .defaultValue(5).min(2).sliderMax(100)
            .visible(() -> fullAuto.get() && mowBeforeMoving.get()).build());

    private final Setting<Integer> settleMaxTicks = sgGeneral.add(new IntSetting.Builder()
            .name("settle-max-ticks")
            .description("Safety cap: max ticks to wait at one stop. 0 = no cap.")
            .defaultValue(200).min(0).sliderMax(1200)
            .visible(() -> fullAuto.get() && mowBeforeMoving.get()).build());

    private final Setting<Integer> roamRestartCooldown = sgGeneral.add(new IntSetting.Builder()
            .name("roam-restart-cooldown")
            .description("Ticks to wait after arriving before heading to the next target.")
            .defaultValue(2).min(0).sliderMax(100)
            .visible(() -> fullAuto.get() && !mowBeforeMoving.get()).build());

    private final Setting<Integer> visitedRadius = sgGeneral.add(new IntSetting.Builder()
            .name("visited-radius")
            .description("Columns within this radius of a reached target count as visited.")
            .defaultValue(3).min(0).sliderMax(10)
            .visible(() -> fullAuto.get() && keepMoving.get()).build());

    private final Setting<Boolean> preferPatches = sgGeneral.add(new BoolSetting.Builder()
            .name("prefer-patches")
            .description("Head for bigger clusters of mossable surface.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> minPatchSize = sgGeneral.add(new IntSetting.Builder()
            .name("min-patch-size")
            .description("Minimum cluster size to target.")
            .defaultValue(4).min(1).sliderMax(25)
            .visible(() -> fullAuto.get() && preferPatches.get()).build());

    private final Setting<Integer> patchRadius = sgGeneral.add(new IntSetting.Builder()
            .name("patch-radius")
            .description("Radius around a candidate to count neighbouring mossable surfaces.")
            .defaultValue(3).min(1).sliderMax(8)
            .visible(() -> fullAuto.get() && preferPatches.get()).build());

    private final Setting<Boolean> stopWhenOutOfMeal = sgGeneral.add(new BoolSetting.Builder()
            .name("stop-when-out-of-meal")
            .description("Stop pathing when out of bone meal.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> jumpBeforeBaritone = sgGeneral.add(new BoolSetting.Builder()
            .name("jump-before-baritone")
            .description("Jump once before issuing a new #goto to escape a stuck position.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> toggleLawnMower = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-lawnMower")
            .description("Toggle LawnMower so grass is cleared for moss spreading.")
            .defaultValue(true).build());

    private final Setting<Boolean> toggleInventoryCleaner = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-inventory-cleaner")
            .description("Toggle InventoryCleaner while AutoMoss is active.")
            .defaultValue(true).build());

    private final Setting<Boolean> toggleHotbarReplenish = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-hotbar-replenish")
            .description("Toggle HotbarReplenish while AutoMoss is active.")
            .defaultValue(true).build());

    private final Setting<Boolean> clearSnow = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-snow")
            .description("Toggle SnowClearer while AutoMoss is active.")
            .defaultValue(false).build());

    private final Setting<Boolean> inventoryAllow = sgGeneral.add(new BoolSetting.Builder()
            .name("inventory-allow")
            .description("Move bone meal from inventory to hotbar when hotbar is empty.")
            .defaultValue(true).build());

    private final Setting<Boolean> craftBoneMeal = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-craft-bonemeal")
            .description("Craft bone meal from bone blocks when out of bone meal.")
            .defaultValue(true).build());

    private final Setting<Integer> craftingDelay = sgGeneral.add(new IntSetting.Builder()
            .name("auto-craft-delay")
            .description("Ticks to wait between crafting slot interactions.")
            .defaultValue(12).min(2).sliderMax(40).build());

    private final Setting<Boolean> keepHotbarStocked = sgGeneral.add(new BoolSetting.Builder()
            .name("keep-hotbar-stocked")
            .description("After crafting, force a full stack of bone meal into the 9th hotbar slot.")
            .defaultValue(true).build());

    private final Setting<Boolean> restockFromShulkers = sgGeneral.add(new BoolSetting.Builder()
            .name("restock-from-shulkers")
            .description("When completely out of supplies, toggle ShulkerRestock to refill.")
            .defaultValue(true).build());

    private final Setting<Integer> restockTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("restock-timeout")
            .description("Max ticks to wait for ShulkerRestock. 0 = wait indefinitely.")
            .defaultValue(0).min(0).sliderMax(2400)
            .visible(restockFromShulkers::get).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks between bone meal uses.")
            .defaultValue(1).min(0).sliderMax(20).build());

    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("max-uses-per-tick")
            .description("Max bone meal uses per tick.")
            .defaultValue(2).min(1).sliderMax(5).build());

    private final Setting<Boolean> syncRotationBonemeal = sgGeneral.add(new BoolSetting.Builder()
            .name("sync-rotation-bonemeal")
            .description("Server-sync the look direction before each bone meal use.")
            .defaultValue(false).build());

    private final Setting<Integer> movingRotationPriority = sgGeneral.add(new IntSetting.Builder()
            .name("moving-rotation-priority")
            .description("Rotation priority while moving. Only used when sync-rotation-bonemeal is ON.")
            .defaultValue(10).min(0).sliderMax(100)
            .visible(syncRotationBonemeal::get).build());

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
            .name("moss-cooldown")
            .description("Ticks before re-bonemealing the same moss block.")
            .defaultValue(100).min(20).sliderMax(200).build());

    private final Setting<Boolean> requireSkyAccess = sgMoss.add(new BoolSetting.Builder()
            .name("require-sky-access")
            .description("Skip blocks buried under a ceiling.")
            .defaultValue(true).build());

    private final Setting<Integer> skyAccessDepth = sgMoss.add(new IntSetting.Builder()
            .name("sky-access-depth")
            .description("Max solid blocks allowed above a target before it is considered buried.")
            .defaultValue(5).min(1).sliderMax(20)
            .visible(requireSkyAccess::get).build());

    private final Setting<Boolean> bonemealSideFaces = sgMoss.add(new BoolSetting.Builder()
            .name("bonemeal-all-faces")
            .description("Bone meal moss on any reachable face, not just the top.")
            .defaultValue(true).build());

    private final Setting<Boolean> placeMoss = sgMoss.add(new BoolSetting.Builder()
            .name("place-moss")
            .description("When no moss is in range, place a moss block at your feet to seed spreading.")
            .defaultValue(true).build());

    private final Setting<Integer> placeMossDelay = sgMoss.add(new IntSetting.Builder()
            .name("place-moss-delay")
            .description("Ticks to wait after placing a seed moss block.")
            .defaultValue(20).min(5).sliderMax(200)
            .visible(placeMoss::get).build());

    private final Setting<Boolean> mineMoss = sgMoss.add(new BoolSetting.Builder()
            .name("mine-moss-refill")
            .description("When moss blocks run low, dispatch Baritone to mine more.")
            .defaultValue(false)
            .visible(fullAuto::get).build());

    private final Setting<Integer> mossLowThreshold = sgMoss.add(new IntSetting.Builder()
            .name("moss-low-threshold")
            .description("Trigger #mine moss_block when total moss blocks drop to/below this.")
            .defaultValue(2).min(0).sliderMax(32)
            .visible(() -> fullAuto.get() && mineMoss.get()).build());

    private final Setting<Integer> mossRefillTarget = sgMoss.add(new IntSetting.Builder()
            .name("moss-refill-target")
            .description("Stop mining moss once total moss blocks reach this amount.")
            .defaultValue(16).min(1).sliderMax(64)
            .visible(() -> fullAuto.get() && mineMoss.get()).build());

    private final Setting<Boolean> makeTrees = sgTrees.add(new BoolSetting.Builder()
            .name("make-trees")
            .description("Use bone meal on azalea bushes and saplings to grow trees.")
            .defaultValue(true).build());

    private final Setting<Integer> azaleaTreeFraction = sgTrees.add(new IntSetting.Builder()
            .name("azalea-tree-fraction")
            .description("X/10 chance to bonemeal an azalea per cooldown roll.")
            .defaultValue(4).min(1).sliderMax(10).build());

    private final Setting<Integer> azaleaCooldownSetting = sgTrees.add(new IntSetting.Builder()
            .name("azalea-cooldown")
            .description("Ticks before re-rolling the same azalea bush.")
            .defaultValue(200).min(20).sliderMax(10000).build());

    private final Setting<Boolean> unstuckEnabled = sgUnstuck.add(new BoolSetting.Builder()
            .name("enabled")
            .description("Detect when the bot is wedged and actively free itself.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> breakAboveWhenProne = sgUnstuck.add(new BoolSetting.Builder()
            .name("break-above-when-prone")
            .description("Break the block above to open headroom when in prone/crawling pose.")
            .defaultValue(true)
            .visible(() -> fullAuto.get() && unstuckEnabled.get()).build());

    private final Setting<Integer> stuckTicksBeforeAction = sgUnstuck.add(new IntSetting.Builder()
            .name("stuck-ticks")
            .description("Ticks motionless before unstuck logic kicks in.")
            .defaultValue(40).min(10).sliderMax(200)
            .visible(() -> fullAuto.get() && unstuckEnabled.get()).build());

    private final Setting<Double> stuckMoveThreshold = sgUnstuck.add(new DoubleSetting.Builder()
            .name("move-threshold")
            .description("Movement in blocks under which the player counts as stuck.")
            .defaultValue(0.6).min(0.05).sliderMax(3.0)
            .visible(() -> fullAuto.get() && unstuckEnabled.get()).build());

    private final Setting<Boolean> pillarOutOfWater = sgUnstuck.add(new BoolSetting.Builder()
            .name("pillar-out-of-water")
            .description("Pillar up out of water when stuck.")
            .defaultValue(true)
            .visible(() -> fullAuto.get() && unstuckEnabled.get()).build());

    private final Setting<Integer> pillarMaxHeight = sgUnstuck.add(new IntSetting.Builder()
            .name("pillar-max-height")
            .description("Max blocks to pillar up before giving up and re-pathing.")
            .defaultValue(4).min(1).sliderMax(16)
            .visible(() -> fullAuto.get() && unstuckEnabled.get()).build());

    private final Setting<List<Block>> pillarBlocks = sgUnstuck.add(new BlockListSetting.Builder()
            .name("pillar-blocks")
            .description("Block types usable for pillaring out.")
            .defaultValue(new ArrayList<>(List.of(Blocks.MOSS_BLOCK, Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.NETHERRACK)))
            .visible(() -> fullAuto.get() && unstuckEnabled.get() && pillarOutOfWater.get()).build());

    private final Setting<Integer> pillarStepDelay = sgUnstuck.add(new IntSetting.Builder()
            .name("pillar-step-delay")
            .description("Ticks between pillar block placements.")
            .defaultValue(4).min(1).sliderMax(20)
            .visible(() -> fullAuto.get() && unstuckEnabled.get() && pillarOutOfWater.get()).build());

    private enum SectorColumn { A, B, C, D, E, F, G, H, I, J }

    private final Setting<Boolean> confineEnabled = sgConfine.add(new BoolSetting.Builder()
            .name("confine-to-sector")
            .description("Keep Baritone inside a single 1000x1000 map sector.")
            .defaultValue(false)
            .visible(fullAuto::get).build());

    private final Setting<SectorColumn> sectorColumn = sgConfine.add(new EnumSetting.Builder<SectorColumn>()
            .name("sector-column")
            .description("Map column (A-J) along the X axis.")
            .defaultValue(SectorColumn.A)
            .visible(() -> fullAuto.get() && confineEnabled.get()).build());

    private final Setting<Integer> sectorRow = sgConfine.add(new IntSetting.Builder()
            .name("sector-row")
            .description("Map row (1-10) along the Z axis.")
            .defaultValue(4).min(1).max(10).sliderMin(1).sliderMax(10)
            .visible(() -> fullAuto.get() && confineEnabled.get()).build());

    private final Setting<Integer> sectorMargin = sgConfine.add(new IntSetting.Builder()
            .name("sector-margin")
            .description("Shrink the usable sector box by this many blocks on every side.")
            .defaultValue(8).min(0).sliderMax(64)
            .visible(() -> fullAuto.get() && confineEnabled.get()).build());

    private final Setting<Boolean> sectorRecenter = sgConfine.add(new BoolSetting.Builder()
            .name("recenter-if-outside")
            .description("Issue a #goto to the sector centre when the bot drifts out.")
            .defaultValue(true)
            .visible(() -> fullAuto.get() && confineEnabled.get()).build());

    private enum CraftState {
        IDLE, OPEN_SCREEN, WAIT_SCREEN_OPEN, CLEAR_CURSOR,
        MOVE_BATCH, CRAFT_BATCH, VERIFY_GAIN, CLEAR_GRID, STOCK_HOTBAR, CLOSE
    }

    private CraftState craftState           = CraftState.IDLE;
    private CraftState lastCraftState       = CraftState.IDLE;
    private int        craftTick            = 0;
    private int        craftBlocksNeeded    = 0;
    private int        craftBatchSize       = 0;
    private int        craftFailCount       = 0;
    private int        craftStuckTicks      = 0;
    private int        reservedLeftoverSlot = -1;
    private int        craftMealBefore      = 0;
    private int        craftVerifyTicks     = 0;
    private int        screenOpenWaitTicks  = 0;
    private int        consecutiveCraftFails = 0;

    private boolean baritoneRunning     = false;
    private boolean wasEating           = false;
    private boolean stoppedForEat       = false;
    private int     delayTimer          = 0;
    private int     placeMossTimer      = 0;
    private int     gotoRestartCooldown = 0;
    private int     pendingGotoTimer    = 0;
    private int     baritoneStallTicks  = 0;
    private int     outOfMealTicks      = 0;
    private int     idleSinceWorkTicks  = 0;
    private int     arrivalAgeTicks     = 0;

    private Boolean lastAllowBreakSent = null;

    private static final int GOTO_GRACE_TICKS    = 3;
    private static final int OUT_OF_MEAL_GRACE   = 20;
    private static final int INV_FIRST           = 9;
    private static final int INV_LAST            = 44;
    private static final int HOTBAR_FIRST_HANDLER = 36;
    private static final int BONE_MEAL_PER_BLOCK  = 9;
    private static final int MIN_EMPTY_TO_CRAFT  = 3;
    private static final int CRAFT_STUCK_LIMIT   = 60;

    private final Map<BlockPos, Integer> recentlyUsedMoss  = new HashMap<>();
    private final Map<BlockPos, Integer> azaleaCooldownMap = new HashMap<>();
    private final Set<Long>              visitedColumns     = new HashSet<>();

    private BlockPos currentGotoTarget = null;

    private Vec3d   lastStuckCheckPos  = null;
    private int     stuckSampleTicks   = 0;
    private int     immobileTicks      = 0;
    private boolean escaping           = false;
    private int     pillarPlaced       = 0;
    private int     pillarStepTimer    = 0;
    private int     pillarPhase        = 0;
    private int     escapeBaseY        = 0;
    private int     breakAboveCooldown = 0;

    private boolean mineMossRunning = false;

    private boolean restockRunning        = false;
    private int     restockWaitTicks      = 0;
    private int     restockWarmup         = 0;
    private boolean restockSeenActive     = false;
    private int     restockInactiveStreak = 0;

    private static final int RESTOCK_WARMUP_TICKS    = 20;
    private static final int RESTOCK_INACTIVE_NEEDED = 15;

    private int      miningProgressTicks  = 0;
    private int      miningStallTicks     = 0;
    private BlockPos lastMiningTarget     = null;
    private int      miningRecoverCooldown = 0;

    private int effectiveScanRadius = 0;
    private int adaptiveScanTimer   = 0;

    private int sectorConfineCooldown = 0;

    private double lastRotYaw   = Double.NaN;
    private double lastRotPitch = Double.NaN;
    private static final double ROT_EPSILON = 1.0;

    // -----------------------------------------------------------------------
    // Target cache — eliminates the per-jump O(r^2 * vert) world scan that
    // caused visible lag spikes.  Both pickRoamTarget() and tickAdaptiveScan()
    // read from this cache; findSurfaceTargets() and computePatchSizes() are
    // only called when the TTL expires.
    // -----------------------------------------------------------------------
    private List<BlockPos>        cachedTargets    = new ArrayList<>();
    private Map<BlockPos,Integer> cachedPatchSizes = new HashMap<>();
    private int                   targetCacheTTL   = 0;
    private static final int      TARGET_CACHE_TICKS = 40; // ~2 s at 20 tps

    private Block mossBlockRef;

    public automoss() {
        super(Hybridious.CATEGORY, "AutoMoss", "Automatically uses bone meal on specific blocks.");
    }

    // -----------------------------------------------------------------------
    // Helper-module toggles
    // -----------------------------------------------------------------------

    private void enableHelper(Class<? extends Module> type, boolean wanted) {
        if (!wanted) return;
        Module m = Modules.get().get(type);
        if (m != null && !m.isActive()) m.toggle();
    }

    private void disableHelper(Class<? extends Module> type) {
        Module m = Modules.get().get(type);
        if (m != null && m.isActive()) m.toggle();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        mossBlockRef = Blocks.MOSS_BLOCK;

        craftState            = CraftState.IDLE;
        baritoneRunning       = false;
        wasEating             = false;
        stoppedForEat         = false;
        delayTimer            = 0;
        placeMossTimer        = 0;
        gotoRestartCooldown   = 0;
        pendingGotoTimer      = 0;
        baritoneStallTicks    = 0;
        outOfMealTicks        = 0;
        idleSinceWorkTicks    = 0;
        arrivalAgeTicks       = 0;
        lastAllowBreakSent    = null;
        craftBlocksNeeded     = 0;
        craftBatchSize        = 0;
        craftFailCount        = 0;
        craftStuckTicks       = 0;
        lastCraftState        = CraftState.IDLE;
        reservedLeftoverSlot  = -1;
        craftMealBefore       = 0;
        craftVerifyTicks      = 0;
        screenOpenWaitTicks   = 0;
        consecutiveCraftFails = 0;

        visitedColumns.clear();
        currentGotoTarget = null;

        lastStuckCheckPos  = mc.player.getPos();
        stuckSampleTicks   = 0;
        immobileTicks      = 0;
        escaping           = false;
        pillarPlaced       = 0;
        pillarStepTimer    = 0;
        pillarPhase        = 0;
        escapeBaseY        = 0;
        breakAboveCooldown = 0;

        mineMossRunning       = false;
        restockRunning        = false;
        restockWaitTicks      = 0;
        restockWarmup         = 0;
        restockSeenActive     = false;
        restockInactiveStreak = 0;

        miningProgressTicks   = 0;
        miningStallTicks      = 0;
        lastMiningTarget      = null;
        miningRecoverCooldown = 0;

        lastRotYaw   = Double.NaN;
        lastRotPitch = Double.NaN;

        effectiveScanRadius   = maxScanRadius();
        adaptiveScanTimer     = adaptiveScanInterval.get();
        sectorConfineCooldown = 0;

        // Clear target cache
        cachedTargets    = new ArrayList<>();
        cachedPatchSizes = new HashMap<>();
        targetCacheTTL   = 0;

        enableHelper(LawnMower.class,        toggleLawnMower.get());
        enableHelper(InventoryCleaner.class, toggleInventoryCleaner.get());
        enableHelper(HotbarReplenish.class,  toggleHotbarReplenish.get());
        enableHelper(SnowClearer.class,      clearSnow.get());

        if (fullAuto.get()) startBaritone();
    }

    @Override
    public void onDeactivate() {
        stopBaritone();

        disableHelper(LawnMower.class);
        disableHelper(InventoryCleaner.class);
        disableHelper(HotbarReplenish.class);
        disableHelper(SnowClearer.class);
        disableHelper(ShulkerRestock.class);

        recentlyUsedMoss.clear();
        azaleaCooldownMap.clear();
        visitedColumns.clear();
        currentGotoTarget    = null;
        craftState           = CraftState.IDLE;
        lastCraftState       = CraftState.IDLE;
        craftStuckTicks      = 0;
        reservedLeftoverSlot = -1;
        craftMealBefore      = 0;
        craftVerifyTicks     = 0;
        screenOpenWaitTicks  = 0;
        consecutiveCraftFails = 0;

        escaping              = false;
        mineMossRunning       = false;
        restockRunning        = false;
        restockWaitTicks      = 0;
        restockWarmup         = 0;
        restockSeenActive     = false;
        restockInactiveStreak = 0;

        lastRotYaw   = Double.NaN;
        lastRotPitch = Double.NaN;

        // Clear target cache
        cachedTargets    = new ArrayList<>();
        cachedPatchSizes = new HashMap<>();
        targetCacheTTL   = 0;
    }

    // -----------------------------------------------------------------------
    // Baritone control
    // -----------------------------------------------------------------------

    private void startBaritone() {
        if (baritoneRunning || mc.player == null) return;

        if (jumpBeforeBaritone.get() && mc.player.isOnGround()) {
            mc.player.jump();
            pendingGotoTimer = 2;
            baritoneRunning  = true;
            // Invalidate the cache so the scan runs AFTER the jump animation tick,
            // not synchronously inside this tick — that was the root cause of the
            // jump lag spike.
            targetCacheTTL = 0;
            return;
        }

        baritoneRunning = sendGoto();
    }

    private boolean sendGoto() {
        if (mc.player == null || mc.world == null) return false;

        if (confineEnabled.get() && !playerInsideSector()) {
            if (sectorRecenter.get()) {
                BlockPos c = sectorCenter();
                currentGotoTarget = c;
                syncAllowBreakIfChanged();
                mc.player.networkHandler.sendChatMessage(
                        "#goto " + c.getX() + " " + c.getY() + " " + c.getZ());
                return true;
            } else {
                gotoRestartCooldown = 40;
                return false;
            }
        }

        BlockPos surface = pickRoamTarget();
        if (surface == null) {
            gotoRestartCooldown = 40;
            return false;
        }

        BlockPos stand = surface.up();

        if (confineEnabled.get()) {
            stand = new BlockPos(clampX(stand.getX()), stand.getY(), clampZ(stand.getZ()));
        }

        currentGotoTarget = stand;
        syncAllowBreakIfChanged();
        mc.player.networkHandler.sendChatMessage(
                "#goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
        return true;
    }

    private void syncAllowBreakIfChanged() {
        if (mc.player == null) return;
        boolean want = allowBreak.get();
        if (lastAllowBreakSent != null && lastAllowBreakSent == want) return;
        mc.player.networkHandler.sendChatMessage("#allowBreak " + (want ? "true" : "false"));
        lastAllowBreakSent = want;
    }

    // -----------------------------------------------------------------------
    // Target cache — the core fix for the jump lag spike
    // -----------------------------------------------------------------------

    /**
     * Returns cached surface targets, recomputing (and caching patch sizes) only
     * when the TTL has expired.  This prevents the O(r^2 * vert) world scan and
     * the O(n^2) patch-size computation from running on every tick — and
     * specifically from running synchronously on the tick that includes a jump.
     */
    private List<BlockPos> getSurfaceTargets() {
        if (targetCacheTTL > 0) {
            targetCacheTTL--;
            return cachedTargets;
        }
        cachedTargets = findSurfaceTargets();
        cachedPatchSizes = preferPatches.get()
                ? computePatchSizes(cachedTargets)
                : new HashMap<>();
        targetCacheTTL = TARGET_CACHE_TICKS;
        return cachedTargets;
    }

    // -----------------------------------------------------------------------
    // Roam-target selection
    // -----------------------------------------------------------------------

    private BlockPos pickRoamTarget() {
        List<BlockPos> surfaces = getSurfaceTargets(); // uses cache
        if (surfaces.isEmpty()) return null;

        BlockPos origin = mc.player.getBlockPos();

        // Reuse patch sizes already computed in getSurfaceTargets() — no second pass.
        Map<BlockPos, Integer> patchSize = cachedPatchSizes;

        List<BlockPos> eligible = surfaces;
        if (preferPatches.get()) {
            int minSize = minPatchSize.get();
            if (minSize > 1) {
                List<BlockPos> filtered = new ArrayList<>();
                for (BlockPos p : surfaces) {
                    if (patchSize.getOrDefault(p, 1) >= minSize) filtered.add(p);
                }
                if (!filtered.isEmpty()) eligible = filtered;
            }
        }

        if (!keepMoving.get()) return pickBest(eligible, origin, patchSize, null);

        BlockPos best = pickBest(eligible, origin, patchSize, visitedColumns);
        if (best != null) return best;

        visitedColumns.clear();
        return pickBest(eligible, origin, patchSize, null);
    }

    private BlockPos pickBest(List<BlockPos> candidates, BlockPos origin,
                              Map<BlockPos, Integer> patchSize, Set<Long> skipVisited) {
        BlockPos best      = null;
        double   bestScore = Double.MAX_VALUE;
        for (BlockPos p : candidates) {
            if (skipVisited != null && skipVisited.contains(columnKey(p))) continue;
            double distSq = p.getSquaredDistance(origin);
            double score  = distSq;
            if (patchSize != null) {
                int    size     = patchSize.getOrDefault(p, 1);
                double discount = Math.min(0.5, (size - 1) * 0.04);
                score = distSq * (1.0 - discount);
            }
            if (score < bestScore) { bestScore = score; best = p; }
        }
        return best;
    }

    private Map<BlockPos, Integer> computePatchSizes(List<BlockPos> surfaces) {
        Map<BlockPos, Integer> sizes = new HashMap<>();
        int r   = patchRadius.get();
        int rSq = r * r;
        for (int i = 0; i < surfaces.size(); i++) {
            BlockPos a = surfaces.get(i);
            int count = 0;
            for (int j = 0; j < surfaces.size(); j++) {
                BlockPos b  = surfaces.get(j);
                int      dx = a.getX() - b.getX();
                int      dz = a.getZ() - b.getZ();
                if (dx * dx + dz * dz <= rSq) count++;
            }
            sizes.put(a, count);
        }
        return sizes;
    }

    private static long columnKey(BlockPos p) {
        return (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
    }

    // -----------------------------------------------------------------------
    // Sector confinement
    // -----------------------------------------------------------------------

    private static final int SECTOR_SIZE    = 1000;
    private static final int GRID_MIN_COORD = -5000;

    private int sectorMinX() { return GRID_MIN_COORD + sectorColumn.get().ordinal() * SECTOR_SIZE + sectorMargin.get(); }
    private int sectorMaxX() { return GRID_MIN_COORD + sectorColumn.get().ordinal() * SECTOR_SIZE + SECTOR_SIZE - 1 - sectorMargin.get(); }
    private int sectorMinZ() { return GRID_MIN_COORD + (sectorRow.get() - 1) * SECTOR_SIZE + sectorMargin.get(); }
    private int sectorMaxZ() { return GRID_MIN_COORD + (sectorRow.get() - 1) * SECTOR_SIZE + SECTOR_SIZE - 1 - sectorMargin.get(); }

    private boolean insideSector(int x, int z) {
        return x >= sectorMinX() && x <= sectorMaxX() && z >= sectorMinZ() && z <= sectorMaxZ();
    }

    private boolean playerInsideSector() {
        if (!confineEnabled.get() || mc.player == null) return true;
        BlockPos b = mc.player.getBlockPos();
        return insideSector(b.getX(), b.getZ());
    }

    private int clampX(int x) { return Math.max(sectorMinX(), Math.min(x, sectorMaxX())); }
    private int clampZ(int z) { return Math.max(sectorMinZ(), Math.min(z, sectorMaxZ())); }

    private BlockPos sectorCenter() {
        int cx = (sectorMinX() + sectorMaxX()) / 2;
        int cz = (sectorMinZ() + sectorMaxZ()) / 2;
        int cy = mc.player != null ? mc.player.getBlockPos().getY() : 64;
        return new BlockPos(cx, cy, cz);
    }

    // -----------------------------------------------------------------------
    // Scan radius
    // -----------------------------------------------------------------------

    private int maxScanRadius() {
        if (scanByRenderDistance.get()) {
            int chunks = mc.options != null ? mc.options.getViewDistance().getValue() : 8;
            return Math.max(16, (chunks - 1) * 16);
        }
        return pathfindScanRadius.get();
    }

    private List<BlockPos> findSurfaceTargets() {
        List<BlockPos> out    = new ArrayList<>();
        List<Block>    wanted = pathfindBlocks.get();
        if (wanted == null || wanted.isEmpty()) return out;

        BlockPos origin    = mc.player.getBlockPos();
        int      horiz     = currentScanRadius();
        int      vert      = pathfindVerticalScan.get();
        int      minTargetY = origin.getY() - 1 - maxDescend.get();

        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -horiz; dx <= horiz; dx++) {
            for (int dz = -horiz; dz <= horiz; dz++) {
                if (confineEnabled.get()
                        && !insideSector(origin.getX() + dx, origin.getZ() + dz)) continue;

                for (int dy = vert; dy >= -vert; dy--) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    Block b = mc.world.getBlockState(p).getBlock();
                    if (!wanted.contains(b)) continue;
                    if (p.getY() < minTargetY) continue;
                    if (!mc.world.getBlockState(p.up()).isAir()) continue;
                    if (surfaceOnly.get() && !isOutdoorSurface(p)) continue;
                    out.add(p.toImmutable());
                    break;
                }
            }
        }
        return out;
    }

    private int currentScanRadius() {
        int max = maxScanRadius();
        if (!adaptiveScan.get()) return max;
        if (effectiveScanRadius <= 0) effectiveScanRadius = max;
        int min = Math.min(adaptiveScanMin.get(), max);
        return Math.max(min, Math.min(effectiveScanRadius, max));
    }

    /**
     * Adaptive scan: invalidates the cache so getSurfaceTargets() performs a fresh
     * scan this tick, then reads the result — ensuring the scan happens at most once
     * per adaptive interval rather than twice (old code called findSurfaceTargets()
     * independently here AND inside pickRoamTarget()).
     */
    private void tickAdaptiveScan() {
        if (!adaptiveScan.get()) return;
        if (mc.player == null || mc.world == null) return;
        int max = maxScanRadius();
        if (effectiveScanRadius <= 0) effectiveScanRadius = max;

        if (--adaptiveScanTimer > 0) return;
        adaptiveScanTimer = adaptiveScanInterval.get();

        int min  = Math.min(adaptiveScanMin.get(), max);
        int step = adaptiveScanStep.get();

        // Force exactly one rescan this tick; getSurfaceTargets() caches the result.
        targetCacheTTL = 0;
        int density = getSurfaceTargets().size();

        if (density >= adaptiveDenseTargets.get()) {
            effectiveScanRadius = Math.max(min, effectiveScanRadius - step);
        } else {
            effectiveScanRadius = Math.min(max, effectiveScanRadius + step);
        }
    }

    private boolean isOutdoorSurface(BlockPos pos) {
        if (mc.world == null) return false;
        if (mc.world.isSkyVisible(pos.up())) return true;
        for (int dy = 1; dy <= 64; dy++) {
            BlockState st = mc.world.getBlockState(pos.up(dy));
            if (st.isAir()) continue;
            if (!st.getFluidState().isEmpty()) continue;
            String n = st.getBlock().getTranslationKey();
            boolean passable = n.contains("grass") || n.contains("fern") || n.contains("flower")
                    || n.contains("vine") || n.contains("sapling") || n.contains("moss_carpet")
                    || n.contains("snow") || n.contains("leaves");
            if (passable) continue;
            return false;
        }
        return true;
    }

    private void markVisited(BlockPos surface) {
        if (surface == null) return;
        int r = visitedRadius.get();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                visitedColumns.add(columnKey(surface.add(dx, 0, dz)));
    }

    private void stopBaritone() {
        if (!baritoneRunning || mc.player == null) return;
        mc.player.networkHandler.sendChatMessage("#stop");
        baritoneRunning  = false;
        pendingGotoTimer = 0;
    }

    // -----------------------------------------------------------------------
    // Mining stall detection
    // -----------------------------------------------------------------------

    private boolean baritoneIsMining() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!baritoneRunning) return false;
        if (!BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) return false;

        if (mc.interactionManager.isBreakingBlock()) {
            if (mc.crosshairTarget != null
                    && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK)
                lastMiningTarget = ((net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget).getBlockPos();
            return true;
        }

        if (mc.crosshairTarget != null
                && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && mc.player.handSwinging) {
            net.minecraft.util.hit.BlockHitResult bhr =
                    (net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget;
            BlockPos looked = bhr.getBlockPos();
            BlockState st = mc.world.getBlockState(looked);
            if (st.isAir() || !st.getFluidState().isEmpty()) return false;
            if (st.getHardness(mc.world, looked) < 0) return false;
            lastMiningTarget = looked;
            return true;
        }
        return false;
    }

    private boolean tickMiningYield() {
        if (miningRecoverCooldown > 0) miningRecoverCooldown--;
        boolean mining = baritoneIsMining();
        if (mining) {
            miningProgressTicks++;
            if (++miningStallTicks > miningStallLimit()) {
                if (miningRecoverCooldown <= 0 && mc.player != null) {
                    baritoneRunning = false;
                    mc.player.networkHandler.sendChatMessage("#stop");
                    gotoRestartCooldown   = 6;
                    miningStallTicks      = 0;
                    miningProgressTicks   = 0;
                    miningRecoverCooldown = 40;
                    lastMiningTarget      = null;
                }
                return true;
            }
            return true;
        }
        miningProgressTicks = 0;
        miningStallTicks    = 0;
        return false;
    }

    private int miningStallLimit() { return 100; }

    // -----------------------------------------------------------------------
    // Main tick
    // -----------------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (breakAboveCooldown > 0) breakAboveCooldown--;

        if (isEatingProtectedFood()) {
            if (!wasEating) {
                wasEating = true;
                if (baritoneRunning) { stopBaritone(); stoppedForEat = true; }
            }
            return;
        }
        if (wasEating) {
            wasEating = false;
            if (stoppedForEat && countBoneMeal() > 0) {
                stoppedForEat = false;
                if (fullAuto.get()) startBaritone();
            }
        }

        if (pendingResetAll) {
            pendingResetAll = false;
            settings.reset();
            enableHelper(LawnMower.class,        toggleLawnMower.get());
            enableHelper(InventoryCleaner.class, toggleInventoryCleaner.get());
            enableHelper(HotbarReplenish.class,  toggleHotbarReplenish.get());
            enableHelper(SnowClearer.class,      clearSnow.get());
            return;
        }

        if (tickRestockWait()) return;
        if (fullAuto.get() && tickMiningYield()) return;
        if (fullAuto.get() && unstuckEnabled.get() && tickUnstuck()) return;

        if (fullAuto.get() && confineEnabled.get() && !playerInsideSector()) {
            if (sectorConfineCooldown > 0) {
                sectorConfineCooldown--;
            } else {
                if (baritoneRunning) stopBaritone();
                if (craftState != CraftState.IDLE) {
                    mc.player.closeHandledScreen();
                    craftState = CraftState.IDLE;
                    lastCraftState = CraftState.IDLE;
                }
                baritoneRunning = sendGoto();
                sectorConfineCooldown = 20;
            }
            return;
        }

        if (pendingGotoTimer > 0) {
            if (--pendingGotoTimer == 0 && fullAuto.get()) baritoneRunning = sendGoto();
            return;
        }

        if (craftState != CraftState.IDLE) { tickCrafting(); return; }
        if (tryStartRestock()) return;
        if (fullAuto.get() && tickMossMining()) return;

        if (craftBoneMeal.get()
                && countBoneMeal() == 0
                && InventoryUtils.countItemsInInventory(Items.BONE_BLOCK) > 0
                && inventoryEmptySlots() >= MIN_EMPTY_TO_CRAFT) {
            if (baritoneRunning) stopBaritone();
            int available = InventoryUtils.countItemsInInventory(Items.BONE_BLOCK);
            int wantToCraft = available > 1 ? available - 1 : available;
            if (wantToCraft < 1) return;
            craftBlocksNeeded   = wantToCraft;
            craftState          = CraftState.OPEN_SCREEN;
            craftTick           = 0;
            craftFailCount      = 0;
            craftStuckTicks     = 0;
            reservedLeftoverSlot = -1;
            craftMealBefore     = 0;
            craftVerifyTicks    = 0;
            screenOpenWaitTicks = 0;
            return;
        }

        checkAndBreakStuckBlock();
        if (fullAuto.get()) tickAdaptiveScan();

        if (fullAuto.get() && !stoppedForEat) {
            boolean actuallyPathing = BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().isPathing();

            if (stopWhenOutOfMeal.get() && countBoneMeal() == 0) {
                if (baritoneRunning && ++outOfMealTicks >= OUT_OF_MEAL_GRACE) {
                    stopBaritone(); outOfMealTicks = 0;
                }
                baritoneStallTicks = 0;
            } else if (baritoneRunning) {
                outOfMealTicks = 0;
                if (actuallyPathing) {
                    baritoneStallTicks = 0;
                } else if (++baritoneStallTicks >= GOTO_GRACE_TICKS) {
                    if (currentGotoTarget != null) markVisited(currentGotoTarget.down());
                    baritoneRunning    = false;
                    baritoneStallTicks = 0;
                    idleSinceWorkTicks = GOTO_GRACE_TICKS;
                    arrivalAgeTicks    = GOTO_GRACE_TICKS;
                    gotoRestartCooldown = mowBeforeMoving.get()
                            ? 0 : (keepMoving.get() ? roamRestartCooldown.get() : 60);
                }
            } else {
                outOfMealTicks = 0;
                if (mowBeforeMoving.get()) {
                    arrivalAgeTicks++;
                    boolean areaDone  = idleSinceWorkTicks >= settleTicks.get();
                    boolean cappedOut = settleMaxTicks.get() > 0 && arrivalAgeTicks >= settleMaxTicks.get();
                    if (areaDone || cappedOut) {
                        idleSinceWorkTicks = 0;
                        arrivalAgeTicks    = 0;
                        startBaritone();
                    }
                } else if (gotoRestartCooldown > 0) {
                    gotoRestartCooldown--;
                } else {
                    startBaritone();
                }
            }
        }

        if (toggleLawnMower.get()) {
            LawnMower lm = Modules.get().get(LawnMower.class);
            if (lm != null) lm.tick();
        }

        boolean workedThisTick = false;

        if (placeMossTimer > 0) placeMossTimer--;
        if (placeMoss.get() && !isMossInRange()) {
            int before = placeMossTimer;
            trySeedMoss();
            if (placeMossTimer > before) workedThisTick = true;
        }

        if (delayTimer > 0) { delayTimer--; idleSinceWorkTicks = 0; return; }

        tickCooldowns();

        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) {
            if (workedThisTick) idleSinceWorkTicks = 0;
            return;
        }

        boolean moving = isMovingNow();
        int     uses   = 0;
        for (BlockPos pos : findTargets()) {
            if (uses >= maxUsesPerTick.get()) break;

            BlockState state  = mc.world.getBlockState(pos);
            boolean    isMoss = state.getBlock() == mossBlockRef;

            if (isMoss && recentlyUsedMoss.containsKey(pos)) continue;
            if (!BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, pos))
                continue;

            FaceHit fh = pickBonemealFace(pos);
            if (fh == null) continue;

            final Vec3d     hitVec = fh.hit();
            final Direction face   = fh.dir();

            if (!syncRotationBonemeal.get()) {
                applyBonemeal(boneMealSlot, pos, hitVec, face);
            } else {
                final BlockPos  posF         = pos;
                final int       boneMealSlotF = boneMealSlot;
                final Direction faceF        = face;
                final Vec3d     hitVecF      = hitVec;
                double[] yp       = lookAt(hitVec);
                int      priority = moving ? movingRotationPriority.get() : 100;
                rotateOnce(yp[0], yp[1], priority, () -> {
                    if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                    if (mc.player.getInventory().getStack(boneMealSlotF).getItem() != Items.BONE_MEAL) return;
                    if (!BoneMealItem.useOnFertilizable(
                            mc.player.getInventory().getStack(boneMealSlotF), mc.world, posF)) return;
                    applyBonemeal(boneMealSlotF, posF, hitVecF, faceF);
                });
            }

            if (isMoss) recentlyUsedMoss.put(pos, mossSpreadCooldown.get());
            uses++;
            workedThisTick = true;
            delayTimer = delay.get();
        }

        if (workedThisTick) idleSinceWorkTicks = 0;
        else                idleSinceWorkTicks++;
    }

    // -----------------------------------------------------------------------
    // Hotbar helpers
    // -----------------------------------------------------------------------

    private int selectHotbarSynced(int slot) {
        int prev = mc.player.getInventory().selectedSlot;
        if (slot < 0 || slot > 8 || slot == prev) return prev;
        mc.player.getInventory().selectedSlot = slot;
        if (mc.player.networkHandler != null)
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        return prev;
    }

    private void restoreHotbarSynced(int prevSlot) {
        if (prevSlot < 0 || prevSlot > 8) return;
        if (mc.player.getInventory().selectedSlot == prevSlot) return;
        mc.player.getInventory().selectedSlot = prevSlot;
        if (mc.player.networkHandler != null)
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
    }

    private void applyBonemeal(int boneMealSlot, BlockPos pos, Vec3d hitVec, Direction face) {
        if (mc.player == null || mc.interactionManager == null) return;
        BlockHitResult hit  = new BlockHitResult(hitVec, face, pos, false);
        int            prev = selectHotbarSynced(boneMealSlot);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        restoreHotbarSynced(prev);
    }

    // -----------------------------------------------------------------------
    // Unstuck
    // -----------------------------------------------------------------------

    private boolean tickUnstuck() {
        if (mc.player == null || mc.world == null) return false;

        boolean prone = isProne();
        if (prone && breakAboveWhenProne.get() && breakAboveCooldown <= 0) {
            if (breakBlockAbove()) { breakAboveCooldown = 4; return true; }
        }

        Vec3d now = mc.player.getPos();
        if (lastStuckCheckPos == null) lastStuckCheckPos = now;

        if (++stuckSampleTicks >= stuckTicksBeforeAction.get()) {
            double moved = horizontalDistance(lastStuckCheckPos, now)
                    + Math.abs(now.y - lastStuckCheckPos.y);
            if (moved < stuckMoveThreshold.get()) immobileTicks += stuckSampleTicks;
            else { immobileTicks = 0; escaping = false; pillarPlaced = 0; }
            lastStuckCheckPos = now;
            stuckSampleTicks  = 0;
        }

        boolean genuinelyStuck = immobileTicks >= stuckTicksBeforeAction.get();
        if (genuinelyStuck && pillarOutOfWater.get() && (touchingWater() || prone))
            return runPillarEscape();
        if (escaping) return runPillarEscape();
        return false;
    }

    private boolean isProne() {
        if (mc.player == null) return false;
        EntityPose pose = mc.player.getPose();
        return pose == EntityPose.SWIMMING || mc.player.isCrawling();
    }

    private boolean touchingWater() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isTouchingWater() || mc.player.isSubmergedInWater()) return true;
        BlockPos feet = mc.player.getBlockPos();
        return !mc.world.getFluidState(feet).isEmpty()
                || !mc.world.getFluidState(feet.up()).isEmpty();
    }

    private boolean breakBlockAbove() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        for (BlockPos above : new BlockPos[]{ feet.up(), feet.up(2) }) {
            BlockState st = mc.world.getBlockState(above);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (st.getHardness(mc.world, above) < 0) continue;
            final BlockPos target = above;
            Vec3d    hitVec = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            double[] yp     = lookAt(hitVec);
            rotateOnce(yp[0], yp[1], 100, true, () -> {
                if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                BlockState now = mc.world.getBlockState(target);
                if (now.isAir() || !now.getFluidState().isEmpty()) return;
                mc.interactionManager.attackBlock(target, Direction.DOWN);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
            return true;
        }
        return false;
    }

    private boolean runPillarEscape() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!escaping) {
            escaping = true; pillarPlaced = 0; escapeBaseY = mc.player.getBlockPos().getY();
            pillarStepTimer = 0; pillarPhase = 0;
            if (baritoneRunning) stopBaritone();
        }

        boolean climbedOut = !touchingWater() && !isProne() && mc.player.isOnGround();
        if (climbedOut || pillarPlaced >= pillarMaxHeight.get()) {
            escaping = false; pillarPlaced = 0; pillarPhase = 0; immobileTicks = 0;
            stuckSampleTicks = 0; lastStuckCheckPos = mc.player.getPos();
            gotoRestartCooldown = Math.max(gotoRestartCooldown, 10);
            return false;
        }

        int pillarSlot = findPillarBlockSlot();
        if (pillarSlot < 0) { escaping = false; pillarPhase = 0; return false; }

        if (pillarPhase == 1) {
            if (pillarStepTimer > 0) { pillarStepTimer--; return true; }
            pillarPhase = 2;
        }

        if (pillarPhase == 0) {
            rotateOnce(mc.player.getYaw(), 90, 100, () -> {});
            if (mc.player.isOnGround() || touchingWater()) mc.player.jump();
            pillarPhase = 1; pillarStepTimer = pillarStepDelay.get();
            return true;
        }

        BlockPos feet    = mc.player.getBlockPos();
        BlockPos against = null;
        for (int d = 1; d <= 3; d++) {
            BlockPos p = feet.down(d);
            BlockState s = mc.world.getBlockState(p);
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) { against = p; break; }
        }
        if (against == null) { pillarPhase = 1; pillarStepTimer = pillarStepDelay.get(); return true; }

        BlockPos   placeAt     = against.up();
        BlockState placeState  = mc.world.getBlockState(placeAt);
        if (!placeState.isAir() && !placeState.isReplaceable()) {
            pillarPhase = 1; pillarStepTimer = pillarStepDelay.get(); return true;
        }

        final BlockPos againstF   = against;
        final int      pillarSlotF = pillarSlot;
        Vec3d    hitVec  = new Vec3d(againstF.getX() + 0.5, againstF.getY() + 1.0, againstF.getZ() + 0.5);
        double[] yp      = lookAt(hitVec);
        final int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = pillarSlot;

        rotateOnce(yp[0], yp[1], 100, true, () -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            Block held = blockOfStack(pillarSlotF);
            if (held == null) return;
            BlockState a = mc.world.getBlockState(againstF);
            if (a.isAir() || a.isReplaceable() || !a.getFluidState().isEmpty()) return;
            BlockState at = mc.world.getBlockState(againstF.up());
            if (!at.isAir() && !at.isReplaceable()) return;
            Vec3d hv = new Vec3d(againstF.getX() + 0.5, againstF.getY() + 1.0, againstF.getZ() + 0.5);
            if (mc.player.getEyePos().squaredDistanceTo(hv) > 4.4 * 4.4) return;
            BlockHitResult hit = new BlockHitResult(hv, Direction.UP, againstF, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        });

        mc.player.getInventory().selectedSlot = prevSlot;
        pillarPlaced++; pillarPhase = 0; pillarStepTimer = pillarStepDelay.get();
        return true;
    }

    private int findPillarBlockSlot() {
        if (mc.player == null) return -1;
        List<Block> usable = pillarBlocks.get();
        if (usable == null || usable.isEmpty()) return -1;
        for (int i = 0; i < 9; i++) {
            Block b = blockOfStack(i);
            if (b != null && usable.contains(b)) return i;
        }
        if (inventoryAllow.get()) {
            for (int inv = 9; inv < 36; inv++) {
                Block b = blockOfStack(inv);
                if (b == null || !usable.contains(b)) continue;
                for (int hot = 0; hot < 9; hot++) {
                    if (!mc.player.getInventory().getStack(hot).isEmpty()) continue;
                    int handlerSlot = playerInvToHandlerSlot(inv);
                    mc.interactionManager.clickSlot(
                            mc.player.playerScreenHandler.syncId, handlerSlot, hot,
                            SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break;
            }
        }
        return -1;
    }

    private Block blockOfStack(int invIndex) {
        if (mc.player == null) return null;
        var item = mc.player.getInventory().getStack(invIndex).getItem();
        if (item instanceof BlockItem bi) return bi.getBlock();
        return null;
    }

    private double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // -----------------------------------------------------------------------
    // Moss mining
    // -----------------------------------------------------------------------

    private boolean tickMossMining() {
        if (!mineMoss.get()) { if (mineMossRunning) endMossMining(); return false; }
        int mossCount = countMossBlocks();
        if (!mineMossRunning) {
            if (mossCount <= mossLowThreshold.get()) {
                if (baritoneRunning) stopBaritone();
                escaping = false;
                mc.player.networkHandler.sendChatMessage("#mine moss_block");
                mineMossRunning = true;
                return true;
            }
            return false;
        }
        if (mossCount >= mossRefillTarget.get()) { endMossMining(); return false; }
        boolean pathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
        if (!pathing && !mossNearby()) { endMossMining(); return false; }
        return true;
    }

    private void endMossMining() {
        if (mc.player != null) mc.player.networkHandler.sendChatMessage("#stop");
        mineMossRunning = false; baritoneRunning = false;
        gotoRestartCooldown = Math.max(gotoRestartCooldown, 10);
    }

    private int countMossBlocks() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.MOSS_BLOCK)
                total += mc.player.getInventory().getStack(i).getCount();
        return total;
    }

    private boolean mossNearby() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos origin = mc.player.getBlockPos();
        int r = Math.min(maxScanRadius(), 24);
        int v = Math.max(pathfindVerticalScan.get(), 6);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -v; dy <= v; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (mc.world.getBlockState(p).getBlock() == mossBlockRef) return true;
                }
        return false;
    }

    // -----------------------------------------------------------------------
    // Shulker restock
    // -----------------------------------------------------------------------

    private boolean tickRestockWait() {
        if (!restockRunning) return false;
        if (!restockFromShulkers.get()) { endShulkerRestock(); return false; }
        if (baritoneRunning) stopBaritone();
        escaping = false;

        boolean active = shulkerRestockActive();
        if (active) restockSeenActive = true;

        if (restockWarmup > 0) restockWarmup--;
        if (restockWarmup > 0 || !restockSeenActive) {
            restockInactiveStreak = 0;
            if (restockTimeout.get() > 0 && ++restockWaitTicks >= restockTimeout.get()) {
                endShulkerRestock(); return false;
            }
            return true;
        }

        if (active) {
            restockInactiveStreak = 0;
        } else if (++restockInactiveStreak >= RESTOCK_INACTIVE_NEEDED) {
            restockRunning = false; restockWaitTicks = 0; restockInactiveStreak = 0;
            gotoRestartCooldown = Math.max(gotoRestartCooldown, 10);
            return false;
        }

        if (restockTimeout.get() > 0 && ++restockWaitTicks >= restockTimeout.get()) {
            endShulkerRestock(); return false;
        }
        return true;
    }

    private boolean tryStartRestock() {
        if (!restockFromShulkers.get() || restockRunning) return false;
        if (countBoneMeal() == 0 && countBoneBlocks() == 0) {
            if (baritoneRunning) stopBaritone();
            escaping = false;
            enableHelper(ShulkerRestock.class, true);
            restockRunning = true; restockWaitTicks = 0; restockWarmup = RESTOCK_WARMUP_TICKS;
            restockSeenActive = false; restockInactiveStreak = 0;
            return true;
        }
        return false;
    }

    private boolean shulkerRestockActive() {
        Module m = Modules.get().get(ShulkerRestock.class);
        return m != null && m.isActive();
    }

    private void endShulkerRestock() {
        disableHelper(ShulkerRestock.class);
        restockRunning = false; restockWaitTicks = 0; restockWarmup = 0;
        restockSeenActive = false; restockInactiveStreak = 0;
        gotoRestartCooldown = Math.max(gotoRestartCooldown, 10);
    }

    private int countBoneBlocks() { return InventoryUtils.countItemsInInventory(Items.BONE_BLOCK); }

    // -----------------------------------------------------------------------
    // Moss placement helpers
    // -----------------------------------------------------------------------

    private boolean isMossInRange() {
        if (mc.player == null || mc.world == null) return false;
        double   rangeSq = range.get() * range.get();
        BlockPos origin  = mc.player.getBlockPos();
        int      r       = (int) Math.ceil(range.get());
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (p.getSquaredDistance(origin) > rangeSq) continue;
                    if (mc.world.getBlockState(p).getBlock() == mossBlockRef) return true;
                }
        return false;
    }

    private boolean isMossableSurface(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.STONE
                || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT
                || b == Blocks.PODZOL || b == Blocks.MYCELIUM
                || b == Blocks.GRANITE || b == Blocks.DIORITE || b == Blocks.ANDESITE
                || b == Blocks.TUFF || b == Blocks.DEEPSLATE || b == Blocks.MOSS_BLOCK;
    }

    private void trySeedMoss() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (placeMossTimer > 0) return;
        int mossSlot = findMossBlockSlot();
        if (mossSlot < 0 || mossSlot >= 9) return;

        BlockPos feet = mc.player.getBlockPos();
        BlockPos[] floors = {
                feet.down(), feet.down().north(), feet.down().south(),
                feet.down().east(), feet.down().west(),
                feet.down().north().east(), feet.down().north().west(),
                feet.down().south().east(), feet.down().south().west()
        };

        Vec3d        eye        = mc.player.getEyePos();
        final double maxReach   = Math.min(range.get(), 4.4);
        final double maxReachSq = maxReach * maxReach;

        BlockPos bestFloor  = null;
        Vec3d    bestHit    = null;
        double   bestDistSq = Double.MAX_VALUE;

        for (BlockPos floor : floors) {
            if (!isMossableSurface(mc.world.getBlockState(floor))) continue;
            BlockPos   placeAt  = floor.up();
            BlockState atState  = mc.world.getBlockState(placeAt);
            if (!atState.isAir() && !atState.isReplaceable()) continue;
            if (placeAt.equals(feet) || placeAt.equals(feet.up())) continue;
            Vec3d  hitVec = topFaceHitToward(floor, eye);
            double distSq = eye.squaredDistanceTo(hitVec);
            if (distSq > maxReachSq) continue;
            if (!hasLineOfSightTo(floor, hitVec)) continue;
            if (distSq < bestDistSq) { bestDistSq = distSq; bestFloor = floor; bestHit = hitVec; }
        }
        if (bestFloor == null) return;
        placeMossNow(bestFloor, bestHit, mossSlot);
    }

    private Vec3d topFaceHitToward(BlockPos floor, Vec3d eye) {
        double cx = floor.getX() + 0.5, cz = floor.getZ() + 0.5;
        return new Vec3d(
                floor.getX() + clamp01face(0.5 + Math.signum(eye.x - cx) * 0.25),
                floor.getY() + 1.0,
                floor.getZ() + clamp01face(0.5 + Math.signum(eye.z - cz) * 0.25));
    }

    private static double clamp01face(double v) { return Math.max(0.15, Math.min(0.85, v)); }

    private boolean hasLineOfSightTo(BlockPos block, Vec3d point) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d          eye = mc.player.getEyePos();
        RaycastContext ctx = new RaycastContext(eye, point,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        BlockPos hit = mc.world.raycast(ctx).getBlockPos();
        return hit.equals(block) || hit.equals(block.up());
    }

    private void placeMossNow(BlockPos floor, Vec3d hitVec, int mossSlot) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mossSlot < 0 || mossSlot >= 9) return;
        if (mc.player.getInventory().getStack(mossSlot).getItem() != Items.MOSS_BLOCK) return;
        double[] yp = lookAt(hitVec);
        final BlockPos floorF   = floor;
        final Vec3d    hitVecF  = hitVec;
        final int      mossSlotF = mossSlot;
        int priority = isMovingNow() ? movingRotationPriority.get() : 100;
        rotateOnce(yp[0], yp[1], priority, true, () -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            if (!isMossableSurface(mc.world.getBlockState(floorF))) return;
            BlockState at = mc.world.getBlockState(floorF.up());
            if (!at.isAir() && !at.isReplaceable()) return;
            if (mc.player.getInventory().getStack(mossSlotF).getItem() != Items.MOSS_BLOCK) return;
            if (mc.player.getEyePos().squaredDistanceTo(hitVecF) > 4.4 * 4.4) return;
            int prev = selectHotbarSynced(mossSlotF);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(hitVecF, Direction.UP, floorF, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            restoreHotbarSynced(prev);
        });
        placeMossTimer = placeMossDelay.get();
    }

    private int findMossBlockSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.MOSS_BLOCK) return i;
        if (inventoryAllow.get()) {
            for (int inv = 9; inv < 36; inv++) {
                if (mc.player.getInventory().getStack(inv).getItem() != Items.MOSS_BLOCK) continue;
                for (int hot = 0; hot < 9; hot++) {
                    if (!mc.player.getInventory().getStack(hot).isEmpty()) continue;
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                            playerInvToHandlerSlot(inv), hot, SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break;
            }
        }
        return -1;
    }

    private int playerInvToHandlerSlot(int invIndex) {
        return invIndex >= 9 ? invIndex : 36 + invIndex;
    }

    // -----------------------------------------------------------------------
    // Crafting state machine
    // -----------------------------------------------------------------------

    private void tickCrafting() {
        craftTick++;
        int syncId = mc.player.playerScreenHandler.syncId;
        if (craftState != lastCraftState) { craftStuckTicks = 0; lastCraftState = craftState; }
        int stuckLimit = Math.max(CRAFT_STUCK_LIMIT, craftingDelay.get() * 12);
        if (++craftStuckTicks > stuckLimit) { emergencyCraftAbort(syncId); return; }

        switch (craftState) {
            case OPEN_SCREEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen))
                    mc.setScreen(new InventoryScreen(mc.player));
                screenOpenWaitTicks = 0;
                craftState = CraftState.WAIT_SCREEN_OPEN; craftTick = 0;
            }
            case WAIT_SCREEN_OPEN -> {
                screenOpenWaitTicks++;
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player)); screenOpenWaitTicks = 0; return;
                }
                if (screenOpenWaitTicks < craftingDelay.get()) return;
                craftState = CraftState.CLEAR_CURSOR; craftTick = 0;
            }
            case CLEAR_CURSOR -> {
                if (craftTick < craftingDelay.get()) return;
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                    int dest = findEmptyInventoryScreenSlotExcluding(-1);
                    if (dest != -1) mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                    else { emergencyCraftAbort(syncId); return; }
                    craftTick = 0; return;
                }
                craftState = CraftState.MOVE_BATCH; craftTick = 0;
            }
            case MOVE_BATCH -> {
                if (craftTick < craftingDelay.get()) return;
                if (slotItem(1) == Items.BONE_BLOCK) {
                    craftBatchSize = mc.player.playerScreenHandler.getSlot(1).getStack().getCount();
                    craftMealBefore = countBoneMeal();
                    craftState = CraftState.CRAFT_BATCH; craftTick = 0; return;
                }
                if (craftBlocksNeeded <= 0) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }
                int maxBatch = consecutiveCraftFails > 0
                        ? Math.max(8, 64 >> Math.min(consecutiveCraftFails, 3)) : 64;
                int free = countEmptyInventoryScreenSlotsExcluding(-1);
                if (free < 1) { emergencyCraftAbort(syncId); return; }
                int srcSlot = findBoneBlockScreenSlot(-1);
                if (srcSlot == -1) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }
                int stackCount = mc.player.playerScreenHandler.getSlot(srcSlot).getStack().getCount();
                int batch = Math.min(Math.min(maxBatch, Math.min(free * 6, craftBlocksNeeded)),
                        Math.min(64, stackCount));
                if (batch < 1) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }
                if (batch >= stackCount) {
                    mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                    mc.interactionManager.clickSlot(syncId, 1,       0, SlotActionType.PICKUP, mc.player);
                } else {
                    mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                    for (int i = 0; i < batch; i++)
                        mc.interactionManager.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, mc.player);
                    if (!mc.player.playerScreenHandler.getCursorStack().isEmpty())
                        mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                }
                craftBatchSize = batch; craftMealBefore = countBoneMeal();
                craftState = CraftState.CRAFT_BATCH; craftTick = 0;
            }
            case CRAFT_BATCH -> {
                if (craftTick < craftingDelay.get()) return;
                if (slotItem(1) != Items.BONE_BLOCK) {
                    craftState = CraftState.VERIFY_GAIN; craftVerifyTicks = 0; craftTick = 0; return;
                }
                if (slotItem(0) != Items.BONE_MEAL) {
                    if (++craftFailCount > 16) {
                        consecutiveCraftFails = Math.min(consecutiveCraftFails + 1, 4);
                        craftFailCount = 0; craftState = CraftState.CLEAR_GRID; craftTick = 0;
                    }
                    return;
                }
                craftMealBefore = countBoneMeal();
                mc.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                craftState = CraftState.VERIFY_GAIN; craftVerifyTicks = 0; craftTick = 0;
            }
            case VERIFY_GAIN -> {
                int now = countBoneMeal(), gained = now - craftMealBefore;
                int expected = craftBatchSize * BONE_MEAL_PER_BLOCK;
                int verifyTimeout = Math.max(40, craftingDelay.get() * 5 + craftBatchSize * 2);
                craftVerifyTicks++;
                boolean gridEmpty = slotItem(1) != Items.BONE_BLOCK;
                boolean fullBatch = expected > 0 && gained >= expected;
                boolean partialOk = gridEmpty && gained >= BONE_MEAL_PER_BLOCK;
                if (fullBatch || partialOk) {
                    int confirmed = gained / BONE_MEAL_PER_BLOCK;
                    craftBlocksNeeded = Math.max(0, craftBlocksNeeded - confirmed);
                    craftFailCount = 0; consecutiveCraftFails = 0; craftBatchSize = 0; craftMealBefore = now;
                    craftState = (craftBlocksNeeded > 0 && countBoneBlocks() > 0)
                            ? CraftState.MOVE_BATCH : CraftState.CLEAR_GRID;
                    craftTick = 0; return;
                }
                if (craftVerifyTicks >= verifyTimeout) {
                    if (gained >= BONE_MEAL_PER_BLOCK)
                        craftBlocksNeeded = Math.max(0, craftBlocksNeeded - gained / BONE_MEAL_PER_BLOCK);
                    consecutiveCraftFails = Math.min(consecutiveCraftFails + 1, 4);
                    craftFailCount = 0; craftBatchSize = 0; craftState = CraftState.CLEAR_GRID; craftTick = 0;
                }
            }
            case CLEAR_GRID -> {
                if (craftTick < craftingDelay.get()) return;
                for (int s = 1; s <= 4; s++) {
                    if (!mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) {
                        int dest = findEmptyInventoryScreenSlotExcluding(-1);
                        if (dest != -1) {
                            mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                        } else {
                            mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.QUICK_MOVE, mc.player);
                        }
                        craftTick = 0; return;
                    }
                }
                if (slotItem(0) == Items.BONE_MEAL) {
                    int dest = findEmptyInventoryScreenSlotExcluding(-1);
                    if (dest != -1) {
                        mc.interactionManager.clickSlot(syncId, 0,    0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                    } else {
                        mc.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                    }
                    craftTick = 0; return;
                }
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                    int dest = findEmptyInventoryScreenSlotExcluding(-1);
                    if (dest != -1) { mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player); craftTick = 0; return; }
                }
                craftState = keepHotbarStocked.get() ? CraftState.STOCK_HOTBAR : CraftState.CLOSE;
                craftTick = 0;
            }
            case STOCK_HOTBAR -> {
                if (craftTick < craftingDelay.get()) return;
                int targetHotbarHandler = HOTBAR_FIRST_HANDLER + 8;
                if (mc.player.playerScreenHandler.getSlot(targetHotbarHandler).getStack().getItem() == Items.BONE_MEAL) {
                    craftState = CraftState.CLOSE; craftTick = 0; return;
                }
                int mealSrc = findBoneMealScreenSlot(targetHotbarHandler);
                if (mealSrc != -1)
                    mc.interactionManager.clickSlot(syncId, mealSrc, 8, SlotActionType.SWAP, mc.player);
                craftState = CraftState.CLOSE; craftTick = 0;
            }
            case CLOSE -> {
                if (craftTick < craftingDelay.get()) return;
                mc.player.closeHandledScreen();
                craftState = CraftState.IDLE; lastCraftState = CraftState.IDLE;
                craftTick = 0; craftStuckTicks = 0; reservedLeftoverSlot = -1;
                craftVerifyTicks = 0; screenOpenWaitTicks = 0;
                gotoRestartCooldown = 20;
            }
            default -> craftState = CraftState.IDLE;
        }
    }

    private void emergencyCraftAbort(int syncId) {
        if (mc.player != null) {
            for (int s = 0; s <= 4; s++)
                if (!mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty())
                    mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.QUICK_MOVE, mc.player);
            if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                int dest = findEmptyInventoryScreenSlotExcluding(-1);
                if (dest != -1)
                    mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
            }
            mc.player.closeHandledScreen();
        }
        craftState = CraftState.IDLE; lastCraftState = CraftState.IDLE;
        craftTick = 0; craftStuckTicks = 0; craftBlocksNeeded = 0; craftBatchSize = 0;
        craftVerifyTicks = 0; screenOpenWaitTicks = 0; craftFailCount = 0; reservedLeftoverSlot = -1;
        consecutiveCraftFails = Math.min(consecutiveCraftFails + 1, 4);
        gotoRestartCooldown = 40;
    }

    // -----------------------------------------------------------------------
    // Screen-slot helpers
    // -----------------------------------------------------------------------

    private net.minecraft.item.Item slotItem(int slot) {
        return mc.player.playerScreenHandler.getSlot(slot).getStack().getItem();
    }

    private int findBoneBlockScreenSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().getItem() == Items.BONE_BLOCK) return s;
        }
        return -1;
    }

    private int findBoneMealScreenSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        int bestSlot = -1, bestCount = -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            var stack = mc.player.playerScreenHandler.getSlot(s).getStack();
            if (stack.getItem() != Items.BONE_MEAL) continue;
            if (stack.getCount() > bestCount) { bestCount = stack.getCount(); bestSlot = s; }
        }
        return bestSlot;
    }

    private int findEmptyInventoryScreenSlotExcluding(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) return s;
        }
        return -1;
    }

    private int countEmptyInventoryScreenSlotsExcluding(int excludeSlot) {
        if (mc.player == null) return 0;
        int n = 0;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) n++;
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // Bonemeal target finding
    // -----------------------------------------------------------------------

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.world == null) return targets;

        double   rangeSq  = range.get() * range.get();
        BlockPos origin   = mc.player.getBlockPos();
        int      r        = (int) Math.ceil(range.get());
        boolean  trees    = makeTrees.get();
        boolean  sideFaces = bonemealSideFaces.get();

        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    mp.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mp.getSquaredDistance(origin) > rangeSq) continue;

                    BlockState state     = mc.world.getBlockState(mp);
                    Block      block     = state.getBlock();
                    boolean    isMoss   = block == mossBlockRef;

                    if (!isMoss && trees) {
                        String  blockName = block.getTranslationKey();
                        boolean isAzalea  = blockName.contains("azalea") && !blockName.contains("tree");
                        boolean isSapling = blockName.contains("sapling");
                        if (isAzalea) {
                            BlockPos pos = mp.toImmutable();
                            if (!hasAnyVisibleFace(pos)) continue;
                            if (!azaleaCooldownMap.containsKey(pos)) {
                                if ((int)(Math.random() * 10) < azaleaTreeFraction.get())
                                    targets.add(pos);
                                azaleaCooldownMap.put(pos, azaleaCooldownSetting.get());
                            }
                            continue;
                        }
                        if (isSapling) {
                            BlockPos pos = mp.toImmutable();
                            if (hasAnyVisibleFace(pos)) targets.add(pos);
                            continue;
                        }
                        continue;
                    }

                    if (!isMoss) continue;

                    BlockPos pos = mp.toImmutable();
                    if (!hasValidNeighbor(pos)) continue;
                    if (!hasSkyAccess(pos)) continue;

                    if (sideFaces) { if (hasAnyVisibleFace(pos)) targets.add(pos); }
                    else           { if (!isObstructedAbove(pos) && hasLineOfSight(pos)) targets.add(pos); }
                }
            }
        }
        return targets;
    }

    private boolean hasValidNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            String n = mc.world.getBlockState(pos.offset(dir)).getBlock().getTranslationKey();
            if (n.contains("azalea") || n.contains("tall_grass")
                    || (n.contains("grass") && !n.contains("block"))
                    || n.contains("moss_block") || n.contains("moss_carpet")) continue;
            return true;
        }
        return false;
    }

    private boolean isObstructedAbove(BlockPos pos) {
        BlockState above = mc.world.getBlockState(pos.up());
        if (!above.getFluidState().isEmpty()) return true;
        String n = above.getBlock().getTranslationKey();
        return n.contains("torch") || n.contains("lantern") || n.contains("sign")
                || n.contains("lava") || n.contains("water");
    }

    private boolean hasSkyAccess(BlockPos pos) {
        if (!requireSkyAccess.get()) return true;
        int depth = skyAccessDepth.get();
        for (int dy = 1; dy <= depth; dy++) {
            BlockState state = mc.world.getBlockState(pos.up(dy));
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) return false;
            String  n        = state.getBlock().getTranslationKey();
            boolean passable = n.contains("grass") || n.contains("fern") || n.contains("flower")
                    || n.contains("azalea") || n.contains("moss_carpet")
                    || n.contains("sapling") || n.contains("vine");
            if (passable) continue;
            return false;
        }
        return true;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Vec3d          eye    = mc.player.getEyePos();
        Vec3d          center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        RaycastContext ctx    = new RaycastContext(eye, center,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        return mc.world.raycast(ctx).getBlockPos().equals(pos);
    }

    private boolean hasAnyVisibleFace(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d        eye        = mc.player.getEyePos();
        final double maxReachSq = Math.min(range.get(), 4.4) * Math.min(range.get(), 4.4);
        for (Direction dir : Direction.values()) {
            Vec3d hit = faceCenter(pos, dir);
            if (eye.squaredDistanceTo(hit) > maxReachSq) continue;
            if (faceVisible(pos, dir, hit, eye)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Face picking
    // -----------------------------------------------------------------------

    private record FaceHit(Vec3d hit, Direction dir) {}

    private Vec3d faceCenter(BlockPos pos, Direction dir) {
        return new Vec3d(
                pos.getX() + 0.5 + dir.getOffsetX() * 0.5,
                pos.getY() + 0.5 + dir.getOffsetY() * 0.5,
                pos.getZ() + 0.5 + dir.getOffsetZ() * 0.5);
    }

    private FaceHit pickBonemealFace(BlockPos pos) { return pickReachableFace(pos, bonemealSideFaces.get()); }
    private FaceHit pickReachableFace(BlockPos pos) { return pickReachableFace(pos, true); }

    private FaceHit pickReachableFace(BlockPos pos, boolean allFaces) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d        eye        = mc.player.getEyePos();
        final double maxReachSq = Math.min(range.get(), 4.4) * Math.min(range.get(), 4.4);
        Direction[]  faces      = allFaces ? Direction.values() : new Direction[]{ Direction.UP };
        FaceHit      best       = null;
        double       bestDistSq = Double.MAX_VALUE;
        for (Direction dir : faces) {
            Vec3d  hit    = faceCenter(pos, dir);
            double distSq = eye.squaredDistanceTo(hit);
            if (distSq > maxReachSq) continue;
            if (!faceVisible(pos, dir, hit, eye)) continue;
            if (distSq < bestDistSq) { bestDistSq = distSq; best = new FaceHit(hit, dir); }
        }
        return best;
    }

    private boolean faceVisible(BlockPos pos, Direction dir, Vec3d faceCenter, Vec3d eye) {
        if (mc.world == null) return false;
        BlockPos   neighbor = pos.offset(dir);
        BlockState ns       = mc.world.getBlockState(neighbor);
        if (!ns.isAir() && ns.getFluidState().isEmpty()) {
            net.minecraft.util.shape.VoxelShape shape = ns.getCollisionShape(mc.world, neighbor);
            if (!shape.isEmpty()) {
                net.minecraft.util.math.Box bb = shape.getBoundingBox();
                if ((bb.maxX - bb.minX) >= 0.999 && (bb.maxY - bb.minY) >= 0.999
                        && (bb.maxZ - bb.minZ) >= 0.999 && ns.isOpaque()) return false;
            }
        }
        RaycastContext ctx    = new RaycastContext(eye, faceCenter,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        BlockPos       hitPos = mc.world.raycast(ctx).getBlockPos();
        return hitPos.equals(pos) || hitPos.equals(neighbor);
    }

    // -----------------------------------------------------------------------
    // Rotation helpers
    // -----------------------------------------------------------------------

    private double[] lookAt(Vec3d target) {
        Vec3d  eye   = mc.player.getEyePos();
        double dx    = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return new double[]{ Math.toDegrees(Math.atan2(dz, dx)) - 90.0,
                -Math.toDegrees(Math.atan2(dy, horiz)) };
    }

    private void rotateOnce(double yaw, double pitch, int priority, Runnable action) {
        rotateOnce(yaw, pitch, priority, false, action);
    }

    private void rotateOnce(double yaw, double pitch, int priority, boolean force, Runnable action) {
        double  dYaw      = wrapDegrees(yaw - lastRotYaw);
        double  dPitch    = pitch - lastRotPitch;
        boolean unchanged = !Double.isNaN(lastRotYaw)
                && Math.abs(dYaw) < ROT_EPSILON && Math.abs(dPitch) < ROT_EPSILON;
        lastRotYaw   = yaw;
        lastRotPitch = pitch;
        if (unchanged && !force) { if (action != null) action.run(); return; }
        Rotations.rotate(yaw, pitch, priority, action);
    }

    private static double wrapDegrees(double d) {
        if (Double.isNaN(d)) return d;
        d %= 360.0;
        if (d <= -180.0) d += 360.0;
        if (d >   180.0) d -= 360.0;
        return d;
    }

    private boolean isMovingNow() {
        if (mc.player == null) return false;
        if (baritoneRunning) {
            try { if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) return true; }
            catch (Throwable ignored) {}
        }
        Vec3d v = mc.player.getVelocity();
        return (v.x * v.x + v.z * v.z) > 0.0025;
    }

    // -----------------------------------------------------------------------
    // Misc helpers
    // -----------------------------------------------------------------------

    private void checkAndBreakStuckBlock() {
        if (mc.player == null || mc.world == null) return;
        BlockPos feet = mc.player.getBlockPos();
        for (BlockPos check : new BlockPos[]{ feet, feet.up() }) {
            BlockState state = mc.world.getBlockState(check);
            String     name  = state.getBlock().getTranslationKey();
            boolean isAzaleaBush = name.equals("block.minecraft.azalea")
                    || name.equals("block.minecraft.flowering_azalea");
            boolean isMossCarpet = name.equals("block.minecraft.moss_carpet");
            if (!isAzaleaBush && !isMossCarpet) continue;
            if (isAzaleaBush) {
                net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                        check.getX(), check.getY(), check.getZ(),
                        check.getX() + 1, check.getY() + 1, check.getZ() + 1);
                if (!mc.player.getBoundingBox().intersects(box)) continue;
            }
            mc.interactionManager.attackBlock(check, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            break;
        }
    }

    private boolean isEatingProtectedFood() {
        if (mc.player == null || !mc.player.isUsingItem()) return false;
        net.minecraft.item.Item item = mc.player.getActiveItem().getItem();
        return item == Items.ENCHANTED_GOLDEN_APPLE
                || item == Items.GOLDEN_CARROT
                || item == Items.COOKED_BEEF;
    }

    private void tickCooldowns() {
        recentlyUsedMoss.entrySet().removeIf(e -> { e.setValue(e.getValue() - 1); return e.getValue() <= 0; });
        azaleaCooldownMap.entrySet().removeIf(e -> { e.setValue(e.getValue() - 1); return e.getValue() <= 0; });
    }

    private int inventoryEmptySlots() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++)
            if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
        return empty;
    }

    private int countBoneMeal() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL)
                total += mc.player.getInventory().getStack(i).getCount();
        return total;
    }

    private int findBoneMealSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL) return i;
        if (inventoryAllow.get()) {
            for (int inv = 9; inv < 36; inv++) {
                if (mc.player.getInventory().getStack(inv).getItem() != Items.BONE_MEAL) continue;
                for (int hot = 0; hot < 9; hot++) {
                    if (!mc.player.getInventory().getStack(hot).isEmpty()) continue;
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                            playerInvToHandlerSlot(inv), hot, SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break;
            }
        }
        return -1;
    }
}

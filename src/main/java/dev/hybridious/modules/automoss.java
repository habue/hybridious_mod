package dev.hybridious.modules;

import baritone.api.BaritoneAPI;
import dev.hybridious.Hybridious;
import dev.hybridious.utils.InventoryUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.RaycastContext;

import java.util.*;

/**
 * AutoMoss — fully automated moss spreading with boustrophedon (snake-row) coverage.
 */
public class automoss extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgPattern  = settings.createGroup("Pattern");
    private final SettingGroup sgMoss     = settings.createGroup("Moss");
    private final SettingGroup sgTrees    = settings.createGroup("Trees");
    private final SettingGroup sgCraft    = settings.createGroup("Crafting");
    private final SettingGroup sgUnstuck  = settings.createGroup("Unstuck");
    private final SettingGroup sgConfine  = settings.createGroup("Confine");
    private final SettingGroup sgCompat   = settings.createGroup("Compat");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Reach radius for bone-meal interactions.")
            .defaultValue(4.0).min(1.0).sliderMax(5.0).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay").description("Ticks between bone-meal uses.")
            .defaultValue(1).min(0).sliderMax(10).build());
    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("max-uses-per-tick").description("Bone-meal uses allowed per tick.")
            .defaultValue(2).min(1).sliderMax(5).build());
    private final Setting<Integer> packetBudget = sgGeneral.add(new IntSetting.Builder()
            .name("packet-budget").description("Max total action packets per tick.")
            .defaultValue(6).min(2).sliderMax(20).build());
    private final Setting<Boolean> syncRotationBonemeal = sgGeneral.add(new BoolSetting.Builder()
            .name("sync-rotation-bonemeal").description("Server-sync look direction before each bone-meal use.")
            .defaultValue(false).build());
    private final Setting<Integer> movingRotationPriority = sgGeneral.add(new IntSetting.Builder()
            .name("rotation-priority").description("Rotation request priority.")
            .defaultValue(10).min(0).sliderMax(100).visible(syncRotationBonemeal::get).build());
    private final Setting<Boolean> toggleLawnMower = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-lawnMower").description("Enable LawnMower while active.")
            .defaultValue(true).build());
    private final Setting<Boolean> toggleInventoryCleaner = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-inventory-cleaner").description("Enable InventoryCleaner while active.")
            .defaultValue(true).build());
    private final Setting<Boolean> toggleHotbarReplenish = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-hotbar-replenish").description("Enable HotbarReplenish while active.")
            .defaultValue(true).build());
    private final Setting<Boolean> clearSnow = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-snow").description("Enable SnowClearer while active.")
            .defaultValue(false).build());

    private final Setting<Boolean> toggleFreeLook = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-free-look")
            .description("Enable FreeLook (render category) while AutoMoss is active.")
            .defaultValue(true).build());
    private final Setting<Boolean> inventoryAllow = sgGeneral.add(new BoolSetting.Builder()
            .name("inventory-allow").description("Swap bone meal / moss blocks from inventory to hotbar when needed.")
            .defaultValue(true).build());
    private final Setting<Boolean> stopWhenOutOfMeal = sgGeneral.add(new BoolSetting.Builder()
            .name("stop-when-out-of-meal").description("Pause movement when out of bone meal.")
            .defaultValue(true).build());
    private final Setting<Boolean> disconnectWhenOutOfBoneSupply = sgGeneral.add(new BoolSetting.Builder()
            .name("disconnect-when-out-of-bone-supply")
            .description("Run the Meteor .disconnect command when fully out of bone supply.")
            .defaultValue(false).build());

    private final Setting<Integer> scanRadius = sgPattern.add(new IntSetting.Builder()
            .name("scan-radius").description("Horizontal radius to scan for mossable surface columns.")
            .defaultValue(24).min(4).sliderMax(64).build());
    private final Setting<Integer> rowSpacing = sgPattern.add(new IntSetting.Builder()
            .name("row-spacing").description("Distance between boustrophedon rows (Z stride).")
            .defaultValue(3).min(1).sliderMax(8).build());
    private final Setting<Integer> pathSegmentLength = sgPattern.add(new IntSetting.Builder()
            .name("path-segment-length").description("Minimum distance between long-path anchors.")
            .defaultValue(96).min(8).sliderMax(256).build());
    private final Setting<Integer> waypointRadius = sgPattern.add(new IntSetting.Builder()
            .name("waypoint-arrive-radius").description("How close the player must be to count a waypoint as reached.")
            .defaultValue(3).min(1).sliderMax(12).build());
    private final Setting<Integer> workTicksAtWaypoint = sgPattern.add(new IntSetting.Builder()
            .name("work-ticks-at-waypoint").description("Ticks to spend bonemealing at each waypoint.")
            .defaultValue(8).min(2).sliderMax(40).build());
    private final Setting<Integer> rescanPause = sgPattern.add(new IntSetting.Builder()
            .name("rescan-pause").description("Ticks to pause after completing the full pattern.")
            .defaultValue(40).min(10).sliderMax(200).build());
    private final Setting<Integer> maxDescend = sgPattern.add(new IntSetting.Builder()
            .name("max-descend").description("Ignore surface targets this many blocks below the player.")
            .defaultValue(6).min(1).sliderMax(24).build());
    private final Setting<Boolean> surfaceOnly = sgPattern.add(new BoolSetting.Builder()
            .name("surface-only").description("Only include columns open to the sky.")
            .defaultValue(true).build());
    private final Setting<Boolean> allowBreak = sgPattern.add(new BoolSetting.Builder()
            .name("allow-break").description("Let Baritone break blocks when travelling.")
            .defaultValue(true).build());
    private final Setting<Boolean> ignoreWaterIceIslands = sgPattern.add(new BoolSetting.Builder()
            .name("ignore-water-ice-islands").description("Skip tiny mossable clusters surrounded by water or ice.")
            .defaultValue(true).build());
    private final Setting<Integer> waterIceIslandMaxSize = sgPattern.add(new IntSetting.Builder()
            .name("water-ice-island-max-size").description("Largest cluster that can be ignored.")
            .defaultValue(12).min(1).sliderMax(64).visible(ignoreWaterIceIslands::get).build());
    private final Setting<Integer> waterIceBorderPercent = sgPattern.add(new IntSetting.Builder()
            .name("water-ice-border-percent").description("Percent of cluster border that must be water/ice.")
            .defaultValue(55).min(25).max(100).sliderMin(25).sliderMax(100).visible(ignoreWaterIceIslands::get).build());
    private final Setting<Boolean> avoidWaterTravel = sgPattern.add(new BoolSetting.Builder()
            .name("avoid-water-travel").description("Prefer path anchors on dry landmass.")
            .defaultValue(true).build());
    private final Setting<Integer> dryLandMaxStep = sgPattern.add(new IntSetting.Builder()
            .name("dry-land-max-step").description("Max height change when detecting dry landmass.")
            .defaultValue(2).min(1).sliderMax(6).visible(avoidWaterTravel::get).build());
    private final Setting<Integer> maxRouteWaterColumns = sgPattern.add(new IntSetting.Builder()
            .name("max-route-water-columns").description("Max water/ice columns on direct line to next waypoint.")
            .defaultValue(2).min(0).sliderMax(16).visible(avoidWaterTravel::get).build());
    private final Setting<Integer> waterSafeWaypointSearch = sgPattern.add(new IntSetting.Builder()
            .name("water-safe-waypoint-search").description("How many waypoints to scan for a dry-line alternative.")
            .defaultValue(48).min(4).sliderMax(160).visible(avoidWaterTravel::get).build());
    private final Setting<Boolean> preferLargeMossableAreas = sgPattern.add(new BoolSetting.Builder()
            .name("prefer-large-mossable-areas").description("Prefer large connected mossable regions.")
            .defaultValue(true).build());
    private final Setting<Integer> minimumMossableClusterSize = sgPattern.add(new IntSetting.Builder()
            .name("minimum-mossable-cluster-size").description("Smallest cluster kept when larger terrain exists.")
            .defaultValue(24).min(1).sliderMax(256).visible(preferLargeMossableAreas::get).build());

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
            .name("moss-cooldown").description("Ticks before re-bonemealing the same moss block.")
            .defaultValue(80).min(20).sliderMax(200).build());
    private final Setting<Boolean> requireSkyAccess = sgMoss.add(new BoolSetting.Builder()
            .name("require-sky-access").description("Skip moss blocks buried under a solid ceiling.")
            .defaultValue(true).build());
    private final Setting<Integer> skyAccessDepth = sgMoss.add(new IntSetting.Builder()
            .name("sky-access-depth").description("Max solid blocks above a target before it is buried.")
            .defaultValue(5).min(1).sliderMax(20).visible(requireSkyAccess::get).build());
    private final Setting<Boolean> bonemealSideFaces = sgMoss.add(new BoolSetting.Builder()
            .name("bonemeal-all-faces").description("Bone-meal moss on any reachable face, not just the top.")
            .defaultValue(true).build());
    private final Setting<Boolean> placeMoss = sgMoss.add(new BoolSetting.Builder()
            .name("place-moss").description("Place a moss block on dry mossable terrain when needed.")
            .defaultValue(true).build());
    private final Setting<Integer> placeMossDelay = sgMoss.add(new IntSetting.Builder()
            .name("place-moss-delay").description("Ticks to wait after a confirmed seed placement.")
            .defaultValue(20).min(5).sliderMax(200).visible(placeMoss::get).build());
    private final Setting<Integer> placeMossRetryDelay = sgMoss.add(new IntSetting.Builder()
            .name("place-moss-retry-delay").description("Ticks to wait after a failed seed placement.")
            .defaultValue(3).min(1).sliderMax(20).visible(placeMoss::get).build());
    private final Setting<Integer> placeMossVerifyTicks = sgMoss.add(new IntSetting.Builder()
            .name("place-moss-verify-ticks").description("Ticks to wait for the world to confirm placement.")
            .defaultValue(4).min(1).sliderMax(20).visible(placeMoss::get).build());
    private final Setting<Boolean> onlySeedWhenNoReachableMoss = sgMoss.add(new BoolSetting.Builder()
            .name("only-seed-when-no-reachable-moss").description("Only place seed when no reachable moss can be bone-mealed.")
            .defaultValue(true).visible(placeMoss::get).build());
    private final Setting<Boolean> preferLargeSeedAreas = sgMoss.add(new BoolSetting.Builder()
            .name("prefer-large-seed-areas").description("Prefer broad dry mossable terrain for seed placement.")
            .defaultValue(true).visible(placeMoss::get).build());
    private final Setting<Integer> seedAreaScanRadius = sgMoss.add(new IntSetting.Builder()
            .name("seed-area-scan-radius").description("Radius used to score nearby terrain for seed placement.")
            .defaultValue(4).min(2).sliderMax(8).visible(() -> placeMoss.get() && preferLargeSeedAreas.get()).build());
    private final Setting<Integer> minimumSeedAreaScore = sgMoss.add(new IntSetting.Builder()
            .name("minimum-seed-area-score").description("Preferred minimum nearby dry mossable columns.")
            .defaultValue(8).min(1).sliderMax(96).visible(() -> placeMoss.get() && preferLargeSeedAreas.get()).build());
    private final Setting<Boolean> restockFromShulkers = sgMoss.add(new BoolSetting.Builder()
            .name("restock-from-shulkers").description("Toggle ShulkerRestock when out of bone meal and bone blocks.")
            .defaultValue(true).build());
    private final Setting<Integer> restockTimeout = sgMoss.add(new IntSetting.Builder()
            .name("restock-timeout").description("Max ticks to wait for ShulkerRestock. 0 = indefinite.")
            .defaultValue(0).min(0).sliderMax(2400).visible(restockFromShulkers::get).build());

    private final Setting<Boolean> makeTrees = sgTrees.add(new BoolSetting.Builder()
            .name("make-trees").description("Use bone meal on azalea bushes and saplings to grow trees.")
            .defaultValue(true).build());
    private final Setting<Integer> azaleaTreeFraction = sgTrees.add(new IntSetting.Builder()
            .name("azalea-tree-fraction").description("X/10 chance per cooldown roll to bonemeal an azalea bush.")
            .defaultValue(4).min(1).sliderMax(10).visible(makeTrees::get).build());
    private final Setting<Integer> azaleaCooldown = sgTrees.add(new IntSetting.Builder()
            .name("azalea-cooldown").description("Ticks before re-rolling the same azalea.")
            .defaultValue(200).min(20).sliderMax(10000).visible(makeTrees::get).build());
    private final Setting<Boolean> craftBoneMeal = sgCraft.add(new BoolSetting.Builder()
            .name("auto-craft-bonemeal").description("Craft bone meal from bone blocks when out of bone meal.")
            .defaultValue(true).build());
    private final Setting<Integer> craftDelay = sgCraft.add(new IntSetting.Builder()
            .name("craft-delay").description("Ticks to wait between crafting interactions.")
            .defaultValue(2).min(1).sliderMax(20).build());
    private final Setting<Boolean> keepHotbarStocked = sgCraft.add(new BoolSetting.Builder()
            .name("keep-hotbar-stocked").description("After crafting, move bone meal to hotbar slot 9.")
            .defaultValue(true).build());

    private final Setting<Boolean> unstuckEnabled = sgUnstuck.add(new BoolSetting.Builder()
            .name("enabled").description("Detect when the bot is wedged and actively free itself.")
            .defaultValue(true).build());
    private final Setting<Integer> stuckTicks = sgUnstuck.add(new IntSetting.Builder()
            .name("stuck-ticks").description("Consecutive ticks with no progress before unstuck triggers.")
            .defaultValue(60).min(10).sliderMax(200).visible(unstuckEnabled::get).build());
    private final Setting<Double> stuckThreshold = sgUnstuck.add(new DoubleSetting.Builder()
            .name("move-threshold").description("Minimum movement (blocks) to count as progress.")
            .defaultValue(0.5).min(0.05).sliderMax(3.0).visible(unstuckEnabled::get).build());
    private final Setting<Boolean> pillarOutOfWater = sgUnstuck.add(new BoolSetting.Builder()
            .name("pillar-out-of-water").description("Place blocks beneath feet to climb out of water when stuck.")
            .defaultValue(true).visible(unstuckEnabled::get).build());
    private final Setting<Integer> pillarMaxHeight = sgUnstuck.add(new IntSetting.Builder()
            .name("pillar-max-height").description("Max blocks to pillar up before giving up.")
            .defaultValue(4).min(1).sliderMax(16).visible(() -> unstuckEnabled.get() && pillarOutOfWater.get()).build());
    private final Setting<List<Block>> pillarBlocks = sgUnstuck.add(new BlockListSetting.Builder()
            .name("pillar-blocks").description("Block types usable for pillaring.")
            .defaultValue(new ArrayList<>(List.of(Blocks.MOSS_BLOCK, Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.NETHERRACK)))
            .visible(() -> unstuckEnabled.get() && pillarOutOfWater.get()).build());
    private final Setting<Integer> pillarStepDelay = sgUnstuck.add(new IntSetting.Builder()
            .name("pillar-step-delay").description("Ticks between pillar block placements.")
            .defaultValue(4).min(1).sliderMax(20).visible(() -> unstuckEnabled.get() && pillarOutOfWater.get()).build());

    private enum SectorColumn { A, B, C, D, E, F, G, H, I, J }

    private final Setting<Boolean> confineEnabled = sgConfine.add(new BoolSetting.Builder()
            .name("confine-to-sector").description("Restrict the coverage pattern to a 1000x1000 map sector.")
            .defaultValue(false).build());
    private final Setting<SectorColumn> sectorColumn = sgConfine.add(new EnumSetting.Builder<SectorColumn>()
            .name("sector-column").description("Map column (A-J) along the X axis.")
            .defaultValue(SectorColumn.A).visible(confineEnabled::get).build());
    private final Setting<Integer> sectorRow = sgConfine.add(new IntSetting.Builder()
            .name("sector-row").description("Map row (1-10) along the Z axis.")
            .defaultValue(4).min(1).max(10).sliderMin(1).sliderMax(10).visible(confineEnabled::get).build());
    private final Setting<Integer> sectorMargin = sgConfine.add(new IntSetting.Builder()
            .name("sector-margin").description("Shrink the usable sector box by this many blocks on every side.")
            .defaultValue(8).min(0).sliderMax(64).visible(confineEnabled::get).build());

    private final Setting<Boolean> killAuraCompat = sgCompat.add(new BoolSetting.Builder()
            .name("killaura-compat").description("Pause the stall clock when a KillAura swing is detected.")
            .defaultValue(true).build());
    private final Setting<Boolean> suppressRotationConflict = sgCompat.add(new BoolSetting.Builder()
            .name("suppress-rotation-conflict").description("Skip AutoMoss rotation requests while KillAura is active.")
            .defaultValue(true).visible(killAuraCompat::get).build());


    private static final int SECTOR_SIZE              = 1000;
    private static final int GRID_MIN_COORD           = -5000;
    private static final int CRAFT_OUTPUT_SLOT        = 0;   // output
    private static final int CRAFT_GRID_SLOT          = 1;   // top-left (only used slot in 2×2)
    private static final int INV_FIRST                = 9;   // first inventory slot in handler
    private static final int INV_LAST                 = 44;  // last hotbar slot in handler
    private static final int HOTBAR_FIRST_HANDLER     = 36;  // handler index of hotbar slot 0
    private static final int BONE_MEAL_PER_BLOCK      = 9;
    private static final int KA_SCAN_INTERVAL         = 3;
    private static final double ROT_EPSILON           = 1.0;
    private static final int FOOT_CLEAR_COOLDOWN      = 4;
    private static final int GOTO_COMMAND_COOLDOWN    = 10;
    private static final int SAME_TARGET_RETRY_TICKS  = 300;
    private static final int ROLLING_DISCOVER_TICKS   = 240;
    private static final int VISITED_WAYPOINT_RADIUS  = 12;
    // Max ticks to wait per crafting state before aborting
    private static final int CRAFT_STATE_TIMEOUT      = 120;
    // Ticks we must see the screen open before trusting it
    private static final int SCREEN_STABLE_TICKS      = 3;
    private record CoverageCell(int x, int y, int z) {
        long key() { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
    }

    private enum PatternState { IDLE, PLANNING, EXECUTING, RESCAN_PAUSE }

    private PatternState        patternState         = PatternState.IDLE;
    private List<CoverageCell>  waypoints            = new ArrayList<>();
    private final Set<Long>     visitedWaypointKeys  = new LinkedHashSet<>();
    private int                 waypointIndex        = 0;
    private int                 workTimer            = 0;
    private int                 rescanTimer          = 0;
    private boolean             baritoneRunning      = false;
    private Boolean             lastAllowBreakSent   = null;
    private BlockPos            currentGotoTarget    = null;
    private int                 gotoCommandCooldown  = 0;
    private int                 sameTargetTicks      = 0;
    private int                 rollingDiscoverTimer = 0;

    private int tickPacketsUsed = 0;

    private boolean tryConsumePacket(int count) {
        if (tickPacketsUsed + count > packetBudget.get()) return false;
        tickPacketsUsed += count;
        return true;
    }

    private List<BlockPos> cachedBoneMealTargets  = new ArrayList<>();
    private int            boneMealTargetCacheTTL = 0;
    private BlockPos       lastTargetCacheOrigin  = null;
    private static final int BONEMEAL_TTL         = 4;

    private boolean cachedMossInRange    = false;
    private int     mossInRangeCacheTTL  = 0;
    private static final int MOSS_RANGE_TTL = 6;

    private boolean cachedKillAuraResult   = false;
    private int     killAuraScanCooldown   = 0;
    private boolean killAuraActiveThisTick = false;

    private Vec3d lastProgressPos    = null;
    private int   noProgressTicks    = 0;
    private long  lastBonemealMillis = 0;

    private boolean escaping           = false;
    private int     pillarPlaced       = 0;
    private int     pillarStepTimer    = 0;
    private int     pillarPhase        = 0;
    private int     breakAboveCooldown = 0;
    private int     footClearCooldown  = 0;

    private int      placeMossTimer        = 0;
    private BlockPos pendingSeedPlaceAt    = null;
    private int      pendingSeedVerifyTicks = 0;

    private final Map<BlockPos, Integer> recentlyUsedMoss  = new HashMap<>();
    private final Map<BlockPos, Integer> azaleaCooldownMap = new HashMap<>();

    private int delayTimer = 0;

    private double lastRotYaw   = Double.NaN;
    private double lastRotPitch = Double.NaN;

    private enum CraftState {
        IDLE,
        OPEN,        // requesting screen open on main thread
        WAIT_OPEN,   // waiting for InventoryScreen to be stable
        PLACE_ONE,   // place exactly 1 bone block into craft grid slot 1
        COLLECT,     // shift-click output slot 0 to collect 9 bone meal
        CLEANUP,     // clear grid slot 1 and cursor if needed
        STOCK,       // optionally move bone meal to hotbar slot 9
        CLOSE        // close screen cleanly
    }

    private CraftState craftState             = CraftState.IDLE;
    private int        craftTick              = 0;       // ticks spent in current state
    private int        craftStateTimeout      = 0;       // counts up; aborts if >= CRAFT_STATE_TIMEOUT
    private CraftState craftLastState         = CraftState.IDLE;
    private int        craftSyncId            = -1;      // syncId when screen was confirmed open
    private int        craftScreenStable      = 0;       // consecutive ticks screen confirmed open
    private int        craftBlocksRemaining   = 0;       // bone blocks still to convert this session
    private int        craftSrcSlot          = -1;      // handler slot of current bone block source
    private boolean    craftOpenScheduled     = false;   // have we called mc.execute(open)?
    private boolean    craftCloseScheduled    = false;   // have we called mc.execute(close)?

    private boolean restockRunning         = false;
    private int     restockWaitTicks       = 0;
    private boolean restockSeenActive      = false;
    private int     restockInactiveStreak  = 0;
    private static final int RESTOCK_WARMUP_TICKS    = 20;
    private static final int RESTOCK_INACTIVE_NEEDED = 15;
    private int     restockWarmup          = 0;
    private boolean disconnectCommandSent  = false;

    private boolean wasEating    = false;
    private boolean stoppedForEat = false;

    private Block mossBlockRef;

    public automoss() {
        super(Hybridious.CATEGORY, "AutoMoss",
                "Automatically spreads moss using a boustrophedon (snake-row) coverage pattern.");
    }

    private void enableHelper(Class<? extends Module> type) {
        Module m = Modules.get().get(type);
        if (m != null && !m.isActive()) m.toggle();
    }

    private void disableHelper(Class<? extends Module> type) {
        Module m = Modules.get().get(type);
        if (m != null && m.isActive()) m.toggle();
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        mossBlockRef = Blocks.MOSS_BLOCK;
        resetAllState();
        if (toggleLawnMower.get())        enableHelper(LawnMower.class);
        if (toggleInventoryCleaner.get()) enableHelper(InventoryCleaner.class);
        if (toggleHotbarReplenish.get())  enableHelper(HotbarReplenish.class);
        if (clearSnow.get())              enableHelper(SnowClearer.class);
        if (toggleFreeLook.get()) enableHelper(FreeLook.class);
        patternState = PatternState.IDLE;
    }

    @Override
    public void onDeactivate() {
        forceStopBaritone();
        abortCraftingClean();
        disableHelper(LawnMower.class);
        disableHelper(InventoryCleaner.class);
        disableHelper(HotbarReplenish.class);
        disableHelper(SnowClearer.class);
        if (toggleFreeLook.get()) disableHelper(FreeLook.class);
        disableHelper(ShulkerRestock.class);
        resetAllState();
    }

    private void forceStopBaritone() {
        if (mc.player == null) return;
        try { mc.player.networkHandler.sendChatMessage("#stop"); } catch (Throwable ignored) {}
        baritoneRunning   = false;
        currentGotoTarget = null;
        sameTargetTicks   = 0;
    }

    private void abortCraftingClean() {
        if (mc.player == null) return;
        mc.execute(() -> {
            if (mc.player == null) return;
            int useSyncId = (craftSyncId >= 0)
                    ? craftSyncId
                    : mc.player.playerScreenHandler.syncId;
            // Flush grid slot 1
            try {
                ItemStack grid = mc.player.playerScreenHandler.getSlot(CRAFT_GRID_SLOT).getStack();
                if (!grid.isEmpty())
                    mc.interactionManager.clickSlot(useSyncId, CRAFT_GRID_SLOT, 0,
                            SlotActionType.QUICK_MOVE, mc.player);
            } catch (Throwable ignored) {}
            // Drop cursor
            try {
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty())
                    mc.interactionManager.clickSlot(useSyncId, CRAFT_GRID_SLOT, 0,
                            SlotActionType.PICKUP, mc.player);
            } catch (Throwable ignored) {}
            // Close
            try { mc.player.closeHandledScreen(); } catch (Throwable ignored) {}
        });
        craftState           = CraftState.IDLE;
        craftTick            = 0;
        craftStateTimeout    = 0;
        craftLastState       = CraftState.IDLE;
        craftSyncId          = -1;
        craftScreenStable    = 0;
        craftBlocksRemaining = 0;
        craftSrcSlot         = -1;
        craftOpenScheduled   = false;
        craftCloseScheduled  = false;
    }

    private void resetAllState() {
        patternState          = PatternState.IDLE;
        waypoints             = new ArrayList<>();
        visitedWaypointKeys.clear();
        waypointIndex         = 0;
        workTimer             = 0;
        rescanTimer           = 0;
        baritoneRunning       = false;
        lastAllowBreakSent    = null;
        currentGotoTarget     = null;
        gotoCommandCooldown   = 0;
        sameTargetTicks       = 0;
        rollingDiscoverTimer  = 0;
        tickPacketsUsed       = 0;
        cachedBoneMealTargets = new ArrayList<>();
        boneMealTargetCacheTTL = 0;
        lastTargetCacheOrigin = null;
        cachedMossInRange     = false;
        mossInRangeCacheTTL   = 0;
        cachedKillAuraResult  = false;
        killAuraScanCooldown  = 0;
        killAuraActiveThisTick = false;
        lastProgressPos       = null;
        noProgressTicks       = 0;
        lastBonemealMillis    = 0;
        escaping              = false;
        pillarPlaced          = 0;
        pillarStepTimer       = 0;
        pillarPhase           = 0;
        breakAboveCooldown    = 0;
        footClearCooldown     = 0;
        placeMossTimer        = 0;
        pendingSeedPlaceAt    = null;
        pendingSeedVerifyTicks = 0;
        delayTimer            = 0;
        lastRotYaw            = Double.NaN;
        lastRotPitch          = Double.NaN;
        craftState            = CraftState.IDLE;
        craftTick             = 0;
        craftStateTimeout     = 0;
        craftLastState        = CraftState.IDLE;
        craftSyncId           = -1;
        craftScreenStable     = 0;
        craftBlocksRemaining  = 0;
        craftSrcSlot          = -1;
        craftOpenScheduled    = false;
        craftCloseScheduled   = false;
        restockRunning        = false;
        restockWaitTicks      = 0;
        restockSeenActive     = false;
        restockInactiveStreak = 0;
        restockWarmup         = 0;
        disconnectCommandSent = false;
        wasEating             = false;
        stoppedForEat         = false;
        recentlyUsedMoss.clear();
        azaleaCooldownMap.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        tickPacketsUsed = 0;
        killAuraActiveThisTick = detectKillAura();

        if (breakAboveCooldown > 0) breakAboveCooldown--;
        if (footClearCooldown > 0)  footClearCooldown--;
        if (gotoCommandCooldown > 0) gotoCommandCooldown--;

        if (unstuckEnabled.get() && footClearCooldown <= 0 && clearPlayerSpaceObstruction()) {
            footClearCooldown = FOOT_CLEAR_COOLDOWN;
            return;
        }

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
                if (patternState == PatternState.EXECUTING) resumeBaritoneToWaypoint();
            }
        }

        if (tickRestockWait()) return;
        if (unstuckEnabled.get() && tickUnstuck()) return;

        // Crafting takes full priority — stop movement and handle the state machine.
        if (craftState != CraftState.IDLE) {
            if (baritoneRunning) stopBaritone();
            tickCrafting();
            return;
        }

        if (tryDisconnectWhenOutOfBoneSupply()) return;
        if (tryStartRestock()) return;

        if (stopWhenOutOfMeal.get() && countBoneMeal() == 0 && countBoneBlocks() == 0) {
            if (baritoneRunning) stopBaritone();
            noProgressTicks = 0;
            lastProgressPos = mc.player.getPos();
            return;
        }

        // Trigger crafting when out of bone meal but have bone blocks.
        if (craftBoneMeal.get() && countBoneMeal() == 0 && countBoneBlocks() > 0) {
            int toConvert = computeBoneBlocksToConvert();
            if (toConvert > 0) {
                if (baritoneRunning) stopBaritone();
                if (toggleInventoryCleaner.get()) enableHelper(InventoryCleaner.class);
                craftBlocksRemaining = toConvert;
                craftState           = CraftState.OPEN;
                craftTick            = 0;
                craftStateTimeout    = 0;
                craftLastState       = CraftState.IDLE;
                craftSyncId          = -1;
                craftScreenStable    = 0;
                craftSrcSlot         = -1;
                craftOpenScheduled   = false;
                craftCloseScheduled  = false;
                return;
            }
        }

        if (toggleLawnMower.get()) {
            LawnMower lm = Modules.get().get(LawnMower.class);
            if (lm != null) lm.tick();
        }

        if (patternState == PatternState.EXECUTING) tickStallCheck();
        tickPattern();

        tickPendingSeedPlacement();
        if (placeMossTimer > 0) placeMossTimer--;
        if (placeMoss.get() && shouldPlaceSeedMoss()) trySeedMoss();

        if (delayTimer > 0) { delayTimer--; return; }
        tickCooldowns();
        if (mc.player == null || mc.world == null) return;
        tickBoneMeal();
    }

    private void tickPattern() {
        if (mc.player == null) return;
        switch (patternState) {
            case IDLE, PLANNING -> {
                planBoustrophedon();
                if (waypoints.isEmpty()) {
                    stopBaritone();
                    patternState = PatternState.RESCAN_PAUSE;
                    rescanTimer  = rescanPause.get();
                    return;
                }
                waypointIndex = nearestWaypointIndex();
                advanceWaypointPastNearbyCells();
                if (waypointIndex >= waypoints.size()) waypointIndex = nearestWaypointIndex();
                patternState = PatternState.EXECUTING;
                workTimer = 0;
                rollingDiscoverTimer = ROLLING_DISCOVER_TICKS;
                resumeBaritoneToWaypoint();
            }
            case EXECUTING -> {
                advanceWaypointPastNearbyCells();
                if (waypointIndex >= waypoints.size()) {
                    refreshRollingWaypoints(true);
                    if (waypoints.isEmpty() || waypointIndex >= waypoints.size()) {
                        stopBaritone();
                        patternState = PatternState.RESCAN_PAUSE;
                        rescanTimer  = rescanPause.get();
                        return;
                    }
                }
                CoverageCell target = waypoints.get(waypointIndex);
                BlockPos stand = standPosFor(target);
                if (!baritoneRunning || currentGotoTarget == null || !currentGotoTarget.equals(stand))
                    resumeBaritoneToWaypoint();
                tickSameTargetGuard();
            }
            case RESCAN_PAUSE -> {
                if (baritoneRunning) stopBaritone();
                if (--rescanTimer <= 0) patternState = PatternState.IDLE;
            }
        }
    }

    private void refreshRollingWaypoints(boolean forceNearest) {
        if (mc.player == null) return;
        List<CoverageCell> old  = new ArrayList<>(waypoints);
        Set<Long>          seen = new LinkedHashSet<>();
        for (CoverageCell c : old) seen.add(c.key());
        planBoustrophedon();
        List<CoverageCell> scanned = new ArrayList<>(waypoints);
        List<CoverageCell> merged  = new ArrayList<>();
        if (!forceNearest) {
            for (int i = Math.max(0, waypointIndex); i < old.size(); i++) {
                CoverageCell c = old.get(i);
                if (!isVisitedWaypointArea(c.x(), c.z()) && !atWaypoint(c)) merged.add(c);
            }
        }
        for (CoverageCell c : scanned) {
            if (isVisitedWaypointArea(c.x(), c.z())) continue;
            if (seen.add(c.key()) || forceNearest) merged.add(c);
        }
        waypoints     = merged;
        waypointIndex = forceNearest ? nearestUnvisitedWaypointIndex() : 0;
        advanceWaypointPastNearbyCells();
    }

    private BlockPos standPosFor(CoverageCell cell) {
        BlockPos stand = new BlockPos(cell.x(), cell.y() + 1, cell.z());
        if (confineEnabled.get())
            stand = new BlockPos(clampX(stand.getX()), stand.getY(), clampZ(stand.getZ()));
        return stand;
    }

    private int nearestWaypointIndex() { return nearestUnvisitedWaypointIndex(); }

    private int nearestUnvisitedWaypointIndex() {
        if (mc.player == null || waypoints.isEmpty()) return 0;
        int best = 0; double bestSq = Double.MAX_VALUE; boolean found = false;
        for (int i = 0; i < waypoints.size(); i++) {
            CoverageCell c = waypoints.get(i);
            if (isVisitedWaypointArea(c.x(), c.z())) continue;
            double dx = mc.player.getX() - (c.x() + 0.5), dz = mc.player.getZ() - (c.z() + 0.5);
            double dSq = dx * dx + dz * dz;
            if (dSq < bestSq) { bestSq = dSq; best = i; found = true; }
        }
        return found ? best : waypoints.size();
    }

    private void advanceWaypointPastNearbyCells() {
        if (mc.player == null) return;
        while (waypointIndex < waypoints.size()) {
            CoverageCell c = waypoints.get(waypointIndex);
            if (isVisitedWaypointArea(c.x(), c.z())) { waypointIndex++; continue; }
            if (!atWaypoint(c)) break;
            markWaypointVisited(c); waypointIndex++;
        }
    }

    private void markWaypointVisited(CoverageCell cell) { visitedWaypointKeys.add(cell.key()); }

    private boolean isVisitedWaypointArea(int x, int z) {
        int radius = Math.max(VISITED_WAYPOINT_RADIUS, waypointRadius.get() * 3);
        int radiusSq = radius * radius;
        for (long key : visitedWaypointKeys) {
            int vx = (int)(key >> 32), vz = (int)key;
            int dx = x - vx, dz = z - vz;
            if (dx * dx + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    private void tickSameTargetGuard() {
        if (mc.player == null || currentGotoTarget == null) return;
        if (mc.player.getBlockPos().getManhattanDistance(currentGotoTarget) <= waypointRadius.get() + 2)
            sameTargetTicks++;
        else sameTargetTicks = 0;
        if (sameTargetTicks >= SAME_TARGET_RETRY_TICKS) {
            sameTargetTicks = 0; baritoneRunning = false;
            if (waypointIndex < waypoints.size()) markWaypointVisited(waypoints.get(waypointIndex));
            waypointIndex = Math.min(waypointIndex + 1, waypoints.size());
            advanceWaypointPastNearbyCells();
            if (waypointIndex < waypoints.size()) resumeBaritoneToWaypoint();
        }
    }

    private void resumeBaritoneToWaypoint() {
        if (mc.player == null || waypointIndex >= waypoints.size()) return;
        waypointIndex = chooseWaterSafeWaypointIndex(waypointIndex);
        if (waypointIndex >= waypoints.size()) return;
        syncAllowBreak(); sendGotoCmd(standPosFor(waypoints.get(waypointIndex)));
    }

    private boolean atWaypoint(CoverageCell cell) {
        if (mc.player == null) return false;
        double dx = mc.player.getX() - (cell.x() + 0.5), dz = mc.player.getZ() - (cell.z() + 0.5);
        return Math.sqrt(dx * dx + dz * dz) <= waypointRadius.get();
    }

    private void planBoustrophedon() {
        if (mc.player == null || mc.world == null) return;
        BlockPos origin = mc.player.getBlockPos();
        int r = scanRadius.get(), stride = Math.max(1, rowSpacing.get());
        Map<Long, CoverageCell> cells = new LinkedHashMap<>();
        BlockPos.Mutable mp = new BlockPos.Mutable();
        int minY = origin.getY() - maxDescend.get() - 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int wx = origin.getX() + dx, wz = origin.getZ() + dz;
                if (confineEnabled.get() && !insideSector(wx, wz)) continue;
                for (int dy = 2; dy >= -maxDescend.get(); dy--) {
                    mp.set(wx, origin.getY() + dy, wz);
                    BlockState state = mc.world.getBlockState(mp);
                    if (!isMossableBlock(state.getBlock())) continue;
                    if (!mc.world.getBlockState(mp.up()).isAir()) continue;
                    if (mp.getY() < minY) continue;
                    if (surfaceOnly.get() && !isOutdoorSurface(mp)) continue;
                    long key = ((long) wx << 32) ^ (wz & 0xFFFFFFFFL);
                    if (isVisitedWaypointArea(wx, wz)) break;
                    cells.put(key, new CoverageCell(wx, mp.getY(), wz)); break;
                }
            }
        }
        if (ignoreWaterIceIslands.get())    removeWaterIceIslands(cells);
        if (avoidWaterTravel.get())         filterToCurrentDryLandmass(cells, origin);
        if (preferLargeMossableAreas.get()) filterToLargeMossableClusters(cells, origin);
        if (cells.isEmpty()) { waypoints = new ArrayList<>(); return; }
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (CoverageCell c : cells.values()) {
            xMin = Math.min(xMin, c.x()); xMax = Math.max(xMax, c.x());
            zMin = Math.min(zMin, c.z()); zMax = Math.max(zMax, c.z());
        }
        boolean startNearZ = Math.abs(origin.getZ() - zMin) <= Math.abs(origin.getZ() - zMax);
        boolean startNearX = Math.abs(origin.getX() - xMin) <= Math.abs(origin.getX() - xMax);
        int zStart = startNearZ ? zMin : zMax, zEnd = startNearZ ? zMax : zMin, zStep = startNearZ ? stride : -stride;
        List<CoverageCell> plan = new ArrayList<>();
        boolean leftToRight = startNearX;
        for (int zRow = zStart; startNearZ ? zRow <= zEnd : zRow >= zEnd; zRow += zStep) {
            List<CoverageCell> rowCells = new ArrayList<>();
            for (CoverageCell c : cells.values()) { if (Math.abs(c.z() - zRow) < stride) rowCells.add(c); }
            rowCells.sort(Comparator.comparingInt(CoverageCell::x));
            if (!leftToRight) Collections.reverse(rowCells);
            appendLongRowAnchors(rowCells, plan);
            leftToRight = !leftToRight;
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<CoverageCell> deduped = new ArrayList<>();
        for (CoverageCell c : plan) { if (seen.add(c.key())) deduped.add(c); }
        waypoints = deduped;
    }

    private void appendLongRowAnchors(List<CoverageCell> rowCells, List<CoverageCell> plan) {
        if (rowCells == null || rowCells.isEmpty()) return;
        int spacing = Math.max(8, pathSegmentLength.get());
        CoverageCell lastAnchor = rowCells.get(0); plan.add(lastAnchor);
        for (int i = 1; i < rowCells.size() - 1; i++) {
            CoverageCell c = rowCells.get(i);
            int dx = c.x() - lastAnchor.x(), dz = c.z() - lastAnchor.z();
            if ((dx * dx + dz * dz) >= spacing * spacing) { plan.add(c); lastAnchor = c; }
        }
        CoverageCell end = rowCells.get(rowCells.size() - 1);
        if (end.key() != lastAnchor.key()) plan.add(end);
    }
    private int chooseWaterSafeWaypointIndex(int preferredIndex) {
        if (!avoidWaterTravel.get() || mc.player == null || waypoints.isEmpty()) return preferredIndex;
        if (preferredIndex < 0 || preferredIndex >= waypoints.size()) return preferredIndex;
        BlockPos from = mc.player.getBlockPos();
        if (!routeCrossesTooMuchWater(from, standPosFor(waypoints.get(preferredIndex)))) return preferredIndex;
        int end = Math.min(waypoints.size(), preferredIndex + Math.max(4, waterSafeWaypointSearch.get()));
        int bestIndex = preferredIndex; double bestScore = Double.MAX_VALUE;
        for (int i = preferredIndex + 1; i < end; i++) {
            CoverageCell candidate = waypoints.get(i);
            if (isVisitedWaypointArea(candidate.x(), candidate.z())) continue;
            BlockPos stand = standPosFor(candidate);
            if (routeCrossesTooMuchWater(from, stand)) continue;
            double dx = from.getX() - stand.getX(), dz = from.getZ() - stand.getZ();
            double score = dx * dx + dz * dz + (i - preferredIndex) * 64.0;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        if (bestIndex != preferredIndex) return bestIndex;
        for (int i = 0; i < waypoints.size(); i++) {
            CoverageCell candidate = waypoints.get(i);
            if (isVisitedWaypointArea(candidate.x(), candidate.z())) continue;
            BlockPos stand = standPosFor(candidate);
            if (routeCrossesTooMuchWater(from, stand)) continue;
            double dx = from.getX() - stand.getX(), dz = from.getZ() - stand.getZ();
            double score = dx * dx + dz * dz;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex;
    }

    private boolean routeCrossesTooMuchWater(BlockPos from, BlockPos to) {
        if (mc.world == null) return false;
        int maxWater = Math.max(0, maxRouteWaterColumns.get());
        int waterColumns = 0, consecutiveWater = 0;
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps <= 1) return false;
        BlockPos.Mutable check = new BlockPos.Mutable();
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            if (hasWaterOrIceNearColumn(x, y, z, check)) {
                waterColumns++; consecutiveWater++;
                if (waterColumns > maxWater || consecutiveWater > maxWater) return true;
            } else { consecutiveWater = 0; }
        }
        return false;
    }

    private void filterToCurrentDryLandmass(Map<Long, CoverageCell> cells, BlockPos origin) {
        if (mc.world == null || cells == null || cells.isEmpty() || origin == null) return;
        Set<Long> dryReachable = collectCurrentDryLandmass(origin);
        if (dryReachable.isEmpty()) return;
        int before = cells.size();
        cells.entrySet().removeIf(entry -> !dryReachable.contains(entry.getKey()));
        if (cells.isEmpty() && before > 0) planBoustrophedonFallbackWithoutDryFilter(cells, origin);
    }

    private void planBoustrophedonFallbackWithoutDryFilter(Map<Long, CoverageCell> cells, BlockPos origin) {
        cells.clear();
        int r = scanRadius.get(), minY = origin.getY() - maxDescend.get() - 1;
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int wx = origin.getX() + dx, wz = origin.getZ() + dz;
                if (confineEnabled.get() && !insideSector(wx, wz)) continue;
                for (int dy = 2; dy >= -maxDescend.get(); dy--) {
                    mp.set(wx, origin.getY() + dy, wz);
                    BlockState state = mc.world.getBlockState(mp);
                    if (!isMossableBlock(state.getBlock())) continue;
                    if (!mc.world.getBlockState(mp.up()).isAir()) continue;
                    if (mp.getY() < minY) continue;
                    if (surfaceOnly.get() && !isOutdoorSurface(mp)) continue;
                    if (isVisitedWaypointArea(wx, wz)) break;
                    cells.put(columnKey(wx, wz), new CoverageCell(wx, mp.getY(), wz)); break;
                }
            }
        }
        if (ignoreWaterIceIslands.get()) removeWaterIceIslands(cells);
    }

    private Set<Long> collectCurrentDryLandmass(BlockPos origin) {
        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = findNearestDrySurfaceColumn(origin);
        if (start == null) return visited;
        int r = scanRadius.get();
        queue.add(start); visited.add(columnKey(start.getX(), start.getZ()));
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                int nx = current.getX() + dir.getOffsetX(), nz = current.getZ() + dir.getOffsetZ();
                if (Math.abs(nx - origin.getX()) > r || Math.abs(nz - origin.getZ()) > r) continue;
                long key = columnKey(nx, nz);
                if (visited.contains(key)) continue;
                Integer ny = findDryWalkableSurfaceY(nx, nz, origin.getY());
                if (ny == null) continue;
                if (Math.abs(ny - current.getY()) > dryLandMaxStep.get()) continue;
                visited.add(key); queue.add(new BlockPos(nx, ny, nz));
            }
        }
        return visited;
    }

    private BlockPos findNearestDrySurfaceColumn(BlockPos origin) {
        Integer ownY = findDryWalkableSurfaceY(origin.getX(), origin.getZ(), origin.getY());
        if (ownY != null) return new BlockPos(origin.getX(), ownY, origin.getZ());
        int search = Math.min(6, scanRadius.get()), bestDist = Integer.MAX_VALUE;
        BlockPos best = null;
        for (int dx = -search; dx <= search; dx++) {
            for (int dz = -search; dz <= search; dz++) {
                int dist = dx * dx + dz * dz;
                if (dist >= bestDist) continue;
                Integer y = findDryWalkableSurfaceY(origin.getX() + dx, origin.getZ() + dz, origin.getY());
                if (y == null) continue;
                bestDist = dist; best = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
            }
        }
        return best;
    }

    private Integer findDryWalkableSurfaceY(int x, int z, int originY) {
        if (mc.world == null) return null;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dy = 3; dy >= -maxDescend.get() - 2; dy--) {
            p.set(x, originY + dy, z);
            if (isDryWalkableFloor(p, mc.world.getBlockState(p))) return p.getY();
        }
        return null;
    }

    private boolean isDryWalkableFloor(BlockPos pos, BlockState floor) {
        if (mc.world == null || floor == null) return false;
        if (floor.isAir() || !floor.getFluidState().isEmpty() || isWaterOrIce(floor)) return false;
        BlockState feet = mc.world.getBlockState(pos.up()), head = mc.world.getBlockState(pos.up(2));
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        if (isWaterOrIce(feet) || isWaterOrIce(head)) return false;
        return (feet.isAir() || feet.isReplaceable()) && (head.isAir() || head.isReplaceable());
    }

    private void filterToLargeMossableClusters(Map<Long, CoverageCell> cells, BlockPos origin) {
        if (cells == null || cells.isEmpty()) return;
        int minSize = Math.max(1, minimumMossableClusterSize.get());
        Set<Long> visited = new HashSet<>();
        List<List<CoverageCell>> clusters = new ArrayList<>();
        for (CoverageCell start : cells.values()) {
            long startKey = start.key();
            if (visited.contains(startKey)) continue;
            List<CoverageCell> cluster = new ArrayList<>();
            ArrayDeque<CoverageCell> queue = new ArrayDeque<>();
            queue.add(start); visited.add(startKey);
            while (!queue.isEmpty()) {
                CoverageCell current = queue.poll(); cluster.add(current);
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    long neighborKey = columnKey(current.x() + dir.getOffsetX(), current.z() + dir.getOffsetZ());
                    CoverageCell neighbor = cells.get(neighborKey);
                    if (neighbor == null || visited.contains(neighborKey)) continue;
                    visited.add(neighborKey); queue.add(neighbor);
                }
            }
            clusters.add(cluster);
        }
        if (clusters.size() <= 1) return;
        List<CoverageCell> largest = null; int largestSize = 0;
        for (List<CoverageCell> cluster : clusters) {
            if (cluster.size() > largestSize) { largestSize = cluster.size(); largest = cluster; }
        }
        Set<Long> keep = new HashSet<>();
        for (List<CoverageCell> cluster : clusters) {
            if (cluster.size() >= minSize) for (CoverageCell cell : cluster) keep.add(cell.key());
        }
        if (keep.isEmpty() && largest != null) for (CoverageCell cell : largest) keep.add(cell.key());
        cells.entrySet().removeIf(entry -> !keep.contains(entry.getKey()));
    }

    private void removeWaterIceIslands(Map<Long, CoverageCell> cells) {
        if (mc.world == null || cells == null || cells.isEmpty()) return;
        Set<Long> visited = new HashSet<>();
        List<Long> toRemove = new ArrayList<>();
        for (CoverageCell start : cells.values()) {
            long startKey = start.key();
            if (visited.contains(startKey)) continue;
            List<CoverageCell> cluster = new ArrayList<>();
            ArrayDeque<CoverageCell> queue = new ArrayDeque<>();
            queue.add(start); visited.add(startKey);
            while (!queue.isEmpty()) {
                CoverageCell current = queue.poll(); cluster.add(current);
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    long neighborKey = columnKey(current.x() + dir.getOffsetX(), current.z() + dir.getOffsetZ());
                    CoverageCell neighbor = cells.get(neighborKey);
                    if (neighbor == null || visited.contains(neighborKey)) continue;
                    visited.add(neighborKey); queue.add(neighbor);
                }
            }
            if (cluster.size() > waterIceIslandMaxSize.get()) continue;
            if (!isClusterBorderMostlyWaterOrIce(cluster, cells)) continue;
            for (CoverageCell cell : cluster) toRemove.add(cell.key());
        }
        for (Long key : toRemove) cells.remove(key);
    }

    private boolean isClusterBorderMostlyWaterOrIce(List<CoverageCell> cluster, Map<Long, CoverageCell> cells) {
        if (mc.world == null || cluster == null || cluster.isEmpty()) return false;
        int borderChecks = 0, waterIceChecks = 0;
        BlockPos.Mutable check = new BlockPos.Mutable();
        for (CoverageCell cell : cluster) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                int nx = cell.x() + dir.getOffsetX(), nz = cell.z() + dir.getOffsetZ();
                if (cells.containsKey(columnKey(nx, nz))) continue;
                borderChecks++;
                if (hasWaterOrIceNearColumn(nx, cell.y(), nz, check)) waterIceChecks++;
            }
        }
        if (borderChecks == 0) return false;
        return waterIceChecks * 100 >= borderChecks * waterIceBorderPercent.get();
    }

    private boolean hasWaterOrIceNearColumn(int x, int y, int z, BlockPos.Mutable check) {
        if (mc.world == null) return false;
        for (int dy = -1; dy <= 1; dy++) {
            check.set(x, y + dy, z);
            if (isWaterOrIce(mc.world.getBlockState(check))) return true;
        }
        return false;
    }

    private boolean isWaterOrIce(BlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        return b == Blocks.WATER || b == Blocks.ICE || b == Blocks.PACKED_ICE
                || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE || !state.getFluidState().isEmpty();
    }

    private long columnKey(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }

    private boolean isMossableBlock(Block b) {
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.STONE
                || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.PODZOL
                || b == Blocks.MYCELIUM || b == Blocks.GRANITE || b == Blocks.DIORITE
                || b == Blocks.ANDESITE || b == Blocks.TUFF || b == Blocks.DEEPSLATE || b == mossBlockRef;
    }

    private void sendGotoCmd(BlockPos pos) {
        if (mc.player == null) return;
        if (baritoneRunning && pos.equals(currentGotoTarget)) return;
        if (gotoCommandCooldown > 0) return;
        if (!tryConsumePacket(1)) return;
        mc.player.networkHandler.sendChatMessage("#goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        baritoneRunning = true; currentGotoTarget = pos;
        gotoCommandCooldown = GOTO_COMMAND_COOLDOWN; sameTargetTicks = 0;
    }

    private void stopBaritone() {
        if (!baritoneRunning || mc.player == null) return;
        if (!tryConsumePacket(1)) return;
        mc.player.networkHandler.sendChatMessage("#stop");
        baritoneRunning = false; currentGotoTarget = null;
        gotoCommandCooldown = Math.max(gotoCommandCooldown, 2); sameTargetTicks = 0;
    }

    private void syncAllowBreak() {
        if (mc.player == null) return;
        boolean want = allowBreak.get();
        if (lastAllowBreakSent != null && lastAllowBreakSent == want) return;
        if (!tryConsumePacket(1)) return;
        mc.player.networkHandler.sendChatMessage("#allowBreak " + (want ? "true" : "false"));
        lastAllowBreakSent = want;
    }

    private void tickBoneMeal() {
        int boneMealSlot = findBoneMealSlot();
        if (boneMealSlot == -1) return;
        if (stopWhenOutOfMeal.get() && countBoneMeal() == 0) return;
        int uses = 0;
        for (BlockPos pos : getCachedBoneMealTargets()) {
            if (uses >= maxUsesPerTick.get()) break;
            if (!tryConsumePacket(1)) break;
            BlockState state = mc.world.getBlockState(pos);
            boolean isMoss = state.getBlock() == mossBlockRef;
            if (isMoss && recentlyUsedMoss.containsKey(pos)) continue;
            if (!BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, pos)) continue;
            FaceHit fh = pickBonemealFace(pos);
            if (fh == null) continue;
            boolean kaActive = killAuraActiveThisTick && killAuraCompat.get();
            boolean skipRot  = !syncRotationBonemeal.get() || (kaActive && suppressRotationConflict.get());
            if (skipRot) {
                applyBonemeal(boneMealSlot, pos, fh.hit(), fh.dir());
            } else {
                final int slot = boneMealSlot; final BlockPos posF = pos;
                final Vec3d hitF = fh.hit(); final Direction faceF = fh.dir();
                double[] yp = lookAt(hitF);
                int priority = isMoving() ? movingRotationPriority.get() : 100;
                rotateOnce(yp[0], yp[1], priority, () -> {
                    if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                    if (mc.player.getInventory().getStack(slot).getItem() != Items.BONE_MEAL) return;
                    if (!BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(slot), mc.world, posF)) return;
                    applyBonemeal(slot, posF, hitF, faceF);
                });
            }
            if (isMoss) recentlyUsedMoss.put(pos, mossSpreadCooldown.get());
            lastBonemealMillis = System.currentTimeMillis();
            uses++; delayTimer = delay.get(); boneMealTargetCacheTTL = 0;
        }
    }

    private void applyBonemeal(int slot, BlockPos pos, Vec3d hitVec, Direction face) {
        if (mc.player == null || mc.interactionManager == null) return;
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
        int prev = selectHotbarSynced(slot);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        restoreHotbarSynced(prev);
    }

    private List<BlockPos> getCachedBoneMealTargets() {
        if (mc.player == null) return cachedBoneMealTargets;
        BlockPos origin = mc.player.getBlockPos();
        boolean moved = lastTargetCacheOrigin == null || lastTargetCacheOrigin.getManhattanDistance(origin) > 1;
        if (!moved && boneMealTargetCacheTTL > 0) { boneMealTargetCacheTTL--; return cachedBoneMealTargets; }
        cachedBoneMealTargets = findBoneMealTargets();
        boneMealTargetCacheTTL = BONEMEAL_TTL; lastTargetCacheOrigin = origin;
        return cachedBoneMealTargets;
    }

    private List<BlockPos> findBoneMealTargets() {
        List<BlockPos> out = new ArrayList<>();
        if (mc.player == null || mc.world == null) return out;
        double rangeSq = range.get() * range.get();
        BlockPos origin = mc.player.getBlockPos();
        int r = (int) Math.ceil(range.get());
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    mp.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mp.getSquaredDistance(origin) > rangeSq) continue;
                    BlockState state = mc.world.getBlockState(mp);
                    Block block = state.getBlock();
                    boolean isMoss = block == mossBlockRef;
                    if (!isMoss && makeTrees.get()) {
                        if (isInPlayerSpace(mp)) continue;
                        String name = block.getTranslationKey();
                        boolean isAzalea = name.contains("azalea") && !name.contains("tree");
                        boolean isSapling = name.contains("sapling");
                        if (isAzalea) {
                            BlockPos pos = mp.toImmutable();
                            if (!hasAnyVisibleFace(pos)) continue;
                            if (!azaleaCooldownMap.containsKey(pos) && (int)(Math.random() * 10) < azaleaTreeFraction.get()) {
                                out.add(pos); azaleaCooldownMap.put(pos, azaleaCooldown.get());
                            }
                            continue;
                        }
                        if (isSapling) {
                            BlockPos pos = mp.toImmutable();
                            if (hasAnyVisibleFace(pos)) out.add(pos);
                            continue;
                        }
                        continue;
                    }
                    if (!isMoss) continue;
                    if (isInPlayerSpace(mp) || isInPlayerSpace(mp.up())) continue;
                    BlockPos pos = mp.toImmutable();
                    if (!hasValidNeighbor(pos)) continue;
                    if (!hasSkyAccess(pos)) continue;
                    if (bonemealSideFaces.get()) {
                        if (hasAnyVisibleFace(pos)) out.add(pos);
                    } else {
                        if (!isObstructedAbove(pos) && hasLineOfSight(pos)) out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    private record FaceHit(Vec3d hit, Direction dir) {}
    private record SeedPlace(BlockPos support, Vec3d hit, Direction dir, double distanceSq, boolean dry) {}

    private FaceHit pickBonemealFace(BlockPos pos) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        double maxReachSq = Math.min(range.get(), 4.4) * Math.min(range.get(), 4.4);
        Direction[] faces = bonemealSideFaces.get() ? Direction.values() : new Direction[]{Direction.UP};
        FaceHit best = null; double bestDSq = Double.MAX_VALUE;
        for (Direction dir : faces) {
            Vec3d fc = faceCenter(pos, dir); double dSq = eye.squaredDistanceTo(fc);
            if (dSq > maxReachSq) continue;
            if (!faceVisible(pos, dir, fc, eye)) continue;
            if (dSq < bestDSq) { bestDSq = dSq; best = new FaceHit(fc, dir); }
        }
        return best;
    }

    private Vec3d faceCenter(BlockPos pos, Direction dir) {
        return new Vec3d(pos.getX() + 0.5 + dir.getOffsetX() * 0.5,
                pos.getY() + 0.5 + dir.getOffsetY() * 0.5,
                pos.getZ() + 0.5 + dir.getOffsetZ() * 0.5);
    }

    private boolean faceVisible(BlockPos pos, Direction dir, Vec3d fc, Vec3d eye) {
        if (mc.world == null) return false;
        BlockPos neighbor = pos.offset(dir);
        BlockState ns = mc.world.getBlockState(neighbor);
        if (!ns.isAir() && ns.getFluidState().isEmpty()) {
            var shape = ns.getCollisionShape(mc.world, neighbor);
            if (!shape.isEmpty()) {
                var bb = shape.getBoundingBox();
                if ((bb.maxX - bb.minX) >= 0.999 && (bb.maxY - bb.minY) >= 0.999
                        && (bb.maxZ - bb.minZ) >= 0.999 && ns.isOpaque()) return false;
            }
        }
        RaycastContext ctx = new RaycastContext(eye, fc, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player);
        BlockPos hitPos = mc.world.raycast(ctx).getBlockPos();
        return hitPos.equals(pos) || hitPos.equals(neighbor);
    }

    private boolean hasAnyVisibleFace(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d eye = mc.player.getEyePos();
        double maxRSq = Math.min(range.get(), 4.4) * Math.min(range.get(), 4.4);
        for (Direction dir : Direction.values()) {
            Vec3d fc = faceCenter(pos, dir);
            if (eye.squaredDistanceTo(fc) > maxRSq) continue;
            if (faceVisible(pos, dir, fc, eye)) return true;
        }
        return false;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        RaycastContext ctx = new RaycastContext(mc.player.getEyePos(), center,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        return mc.world.raycast(ctx).getBlockPos().equals(pos);
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
            String n = state.getBlock().getTranslationKey();
            boolean passable = n.contains("grass") || n.contains("fern") || n.contains("flower")
                    || n.contains("azalea") || n.contains("moss_carpet") || n.contains("sapling") || n.contains("vine");
            if (passable) continue;
            return false;
        }
        return true;
    }

    private boolean isOutdoorSurface(BlockPos pos) {
        if (mc.world == null) return false;
        if (mc.world.isSkyVisible(pos.up())) return true;
        if (mc.world.getLightLevel(LightType.SKY, pos.up()) >= 12) return true;
        for (int dy = 1; dy <= 16; dy++) {
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

    private void tickStallCheck() {
        if (mc.player == null) return;
        if (touchingWater()) { lastProgressPos = mc.player.getPos(); noProgressTicks = 0; return; }
        if (killAuraActiveThisTick && killAuraCompat.get()) { lastProgressPos = mc.player.getPos(); noProgressTicks = 0; return; }
        Vec3d now = mc.player.getPos();
        if (lastProgressPos == null) { lastProgressPos = now; return; }
        double moved = now.distanceTo(lastProgressPos);
        boolean madeProgress = moved >= stuckThreshold.get() || (System.currentTimeMillis() - lastBonemealMillis) < 2000L;
        if (madeProgress) { lastProgressPos = now; noProgressTicks = 0; }
        else { noProgressTicks++; }
        if (noProgressTicks >= stuckTicks.get()) {
            if (hasPlayerSpaceObstruction() && clearPlayerSpaceObstruction()) {
                noProgressTicks = 0; lastProgressPos = mc.player.getPos(); return;
            }
            stopBaritone(); noProgressTicks = 0; lastProgressPos = null;
            if (patternState == PatternState.EXECUTING) {
                waypointIndex = Math.min(waypointIndex + 1, waypoints.size());
                if (waypointIndex < waypoints.size()) resumeBaritoneToWaypoint();
                else { patternState = PatternState.RESCAN_PAUSE; rescanTimer = rescanPause.get(); }
            } else { patternState = PatternState.IDLE; }
        }
    }

    private boolean isMossInRange() {
        if (mc.player == null || mc.world == null) return false;
        if (mossInRangeCacheTTL > 0) { mossInRangeCacheTTL--; return cachedMossInRange; }
        mossInRangeCacheTTL = MOSS_RANGE_TTL;
        double rSq = range.get() * range.get(); BlockPos origin = mc.player.getBlockPos();
        int r = (int) Math.ceil(range.get());
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (p.getSquaredDistance(origin) > rSq) continue;
            if (mc.world.getBlockState(p).getBlock() == mossBlockRef) { cachedMossInRange = true; return true; }
        }
        cachedMossInRange = false; return false;
    }

    private boolean shouldPlaceSeedMoss() {
        if (!placeMoss.get()) return false;
        if (!onlySeedWhenNoReachableMoss.get()) return !isMossInRange();
        return !hasReachableMossToBonemeal();
    }

    private boolean hasReachableMossToBonemeal() {
        if (mc.player == null || mc.world == null) return false;
        for (BlockPos pos : getCachedBoneMealTargets()) {
            if (mc.world.getBlockState(pos).getBlock() == mossBlockRef) return true;
        }
        return false;
    }

    private void tickPendingSeedPlacement() {
        if (pendingSeedPlaceAt == null || mc.world == null) return;
        if (mc.world.getBlockState(pendingSeedPlaceAt).getBlock() == mossBlockRef) {
            pendingSeedPlaceAt = null; pendingSeedVerifyTicks = 0;
            placeMossTimer = Math.max(placeMossTimer, placeMossDelay.get());
            boneMealTargetCacheTTL = 0; mossInRangeCacheTTL = 0; return;
        }
        if (--pendingSeedVerifyTicks <= 0) {
            pendingSeedPlaceAt = null; pendingSeedVerifyTicks = 0;
            placeMossTimer = Math.max(placeMossTimer, Math.max(1, placeMossRetryDelay.get()));
            boneMealTargetCacheTTL = 0; mossInRangeCacheTTL = 0;
        }
    }

    private void trySeedMoss() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (placeMossTimer > 0 || pendingSeedPlaceAt != null) return;
        if (onlySeedWhenNoReachableMoss.get() && hasReachableMossToBonemeal()) return;
        if (tickPacketsUsed + 3 > packetBudget.get()) return;
        int mossSlot = findMossBlockSlot();
        if (mossSlot < 0 || mossSlot >= 9) return;
        SeedPlace seed = findBestSeedPlacement();
        if (seed == null) { placeMossTimer = Math.max(1, placeMossRetryDelay.get()); return; }
        final BlockPos supportF = seed.support(), placeAtF = supportF.offset(seed.dir());
        final Vec3d hitF = seed.hit(); final Direction faceF = seed.dir(); final int slotF = mossSlot;
        double[] yp = lookAt(hitF);
        int priority = isMoving() ? movingRotationPriority.get() : 100;
        placeMossTimer = Math.max(1, placeMossRetryDelay.get());
        rotateOnce(yp[0], yp[1], priority, true, () -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            if (mc.player.getInventory().getStack(slotF).getItem() != Items.MOSS_BLOCK) return;
            if (!isReliableSeedSupport(supportF)) return;
            if (!canPlaceSeedAt(placeAtF, mc.player.getBlockPos())) return;
            if (ignoreWaterIceIslands.get() && isLocalWaterIceIsland(supportF)) return;
            int prev = selectHotbarSynced(slotF);
            if (!tryConsumePacket(1)) { restoreHotbarSynced(prev); return; }
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(hitF, faceF, supportF, false));
            mc.player.swingHand(Hand.MAIN_HAND); restoreHotbarSynced(prev);
            pendingSeedPlaceAt = placeAtF; pendingSeedVerifyTicks = Math.max(1, placeMossVerifyTicks.get());
            boneMealTargetCacheTTL = 0; mossInRangeCacheTTL = 0;
        });
    }

    private SeedPlace findBestSeedPlacement() {
        SeedPlace strict = findBestSeedPlacementPass(true);
        return strict != null ? strict : findBestSeedPlacementPass(false);
    }

    private SeedPlace findBestSeedPlacementPass(boolean enforceLargeAreaMinimum) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos(); BlockPos feet = mc.player.getBlockPos();
        double maxReach = Math.min(range.get(), 4.35), maxReachSq = maxReach * maxReach;
        SeedPlace best = null; double bestScore = Double.MAX_VALUE;
        int horizontal = Math.max(3, Math.min(5, (int) Math.ceil(range.get())));
        BlockPos.Mutable support = new BlockPos.Mutable();
        for (int y = 1; y >= -3; y--) {
            for (int x = -horizontal; x <= horizontal; x++) {
                for (int z = -horizontal; z <= horizontal; z++) {
                    support.set(feet.getX() + x, feet.getY() + y, feet.getZ() + z);
                    if (support.getSquaredDistance(feet) > maxReachSq + 4.0) continue;
                    BlockPos supportPos = support.toImmutable();
                    if (!isReliableSeedSupport(supportPos)) continue;
                    if (ignoreWaterIceIslands.get() && isLocalWaterIceIsland(supportPos)) continue;
                    int areaScore = scoreNearbyMossableSeedArea(supportPos);
                    if (preferLargeSeedAreas.get() && enforceLargeAreaMinimum
                            && areaScore < Math.max(1, minimumSeedAreaScore.get())) continue;
                    SeedPlace candidate = raycastSeedPlacement(supportPos, eye, feet, maxReachSq);
                    if (candidate == null) continue;
                    double score = candidate.distanceSq();
                    if (supportPos.equals(feet.down())) score -= 4.0;
                    if (supportPos.getY() == feet.getY() - 1) score -= 1.5;
                    if (preferLargeSeedAreas.get()) score -= Math.min(36.0, areaScore * 1.1);
                    if (score < bestScore) { best = candidate; bestScore = score; }
                }
            }
        }
        return best;
    }

    private int scoreNearbyMossableSeedArea(BlockPos center) {
        if (mc.world == null || center == null) return 0;
        int radius = Math.max(2, seedAreaScanRadius.get()), score = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distanceSq = dx * dx + dz * dz;
                if (distanceSq > radius * radius) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState floor = mc.world.getBlockState(p);
                    if (!isMossableBlock(floor.getBlock())) continue;
                    if (!floor.getFluidState().isEmpty() || isWaterOrIce(floor)) continue;
                    BlockState above = mc.world.getBlockState(p.up());
                    if (!above.getFluidState().isEmpty() || isWaterOrIce(above)) continue;
                    if (!above.isAir() && !above.isReplaceable()) continue;
                    score += distanceSq <= 4 ? 3 : 1; break;
                }
            }
        }
        return score;
    }

    private SeedPlace raycastSeedPlacement(BlockPos support, Vec3d eye, BlockPos feet, double maxReachSq) {
        BlockPos placeAt = support.up(); Vec3d aim = insetFaceHit(support, Direction.UP, eye);
        double distSq = eye.squaredDistanceTo(aim);
        if (distSq > maxReachSq) return null;
        if (!canPlaceSeedAt(placeAt, feet)) return null;
        RaycastContext ctx = new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, mc.player);
        BlockHitResult hit = mc.world.raycast(ctx);
        if (hit.getBlockPos().equals(support) && hit.getSide() == Direction.UP)
            return new SeedPlace(support, hit.getPos(), Direction.UP, distSq, true);
        if (hit.getBlockPos().equals(placeAt)) {
            BlockState hitState = mc.world.getBlockState(placeAt);
            if (hitState.isReplaceable() && hitState.getFluidState().isEmpty())
                return new SeedPlace(support, aim, Direction.UP, distSq, true);
        }
        return null;
    }

    private Vec3d insetFaceHit(BlockPos pos, Direction dir, Vec3d eye) {
        double x = pos.getX() + 0.5 + dir.getOffsetX() * 0.5;
        double y = pos.getY() + 0.5 + dir.getOffsetY() * 0.5;
        double z = pos.getZ() + 0.5 + dir.getOffsetZ() * 0.5;
        if (dir.getAxis() != Direction.Axis.X) {
            double cx = pos.getX() + 0.5;
            x = Math.max(pos.getX() + 0.18, Math.min(pos.getX() + 0.82, cx + Math.signum(eye.x - cx) * 0.22));
        }
        if (dir.getAxis() != Direction.Axis.Y) {
            double cy = pos.getY() + 0.5;
            y = Math.max(pos.getY() + 0.18, Math.min(pos.getY() + 0.82, cy + Math.signum(eye.y - cy) * 0.22));
        }
        if (dir.getAxis() != Direction.Axis.Z) {
            double cz = pos.getZ() + 0.5;
            z = Math.max(pos.getZ() + 0.18, Math.min(pos.getZ() + 0.82, cz + Math.signum(eye.z - cz) * 0.22));
        }
        return new Vec3d(x, y, z);
    }

    private boolean isReliableSeedSupport(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        if (!isNaturalSeedSupportBlock(state.getBlock())) return false;
        if (!state.getFluidState().isEmpty() || isWaterOrIce(state)) return false;
        if (!state.isSolidBlock(mc.world, pos) && state.getCollisionShape(mc.world, pos).isEmpty()) return false;
        BlockState above = mc.world.getBlockState(pos.up());
        if (!above.getFluidState().isEmpty() || isWaterOrIce(above)) return false;
        if (!above.isAir() && !above.isReplaceable()) return false;
        return isDrySeedColumn(pos);
    }

    private boolean isNaturalSeedSupportBlock(Block block) {
        return block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT || block == Blocks.PODZOL || block == Blocks.MYCELIUM
                || block == Blocks.STONE || block == Blocks.GRANITE || block == Blocks.DIORITE
                || block == Blocks.ANDESITE || block == Blocks.TUFF || block == Blocks.DEEPSLATE || block == mossBlockRef;
    }

    private boolean canPlaceSeedAt(BlockPos placeAt, BlockPos feet) {
        if (mc.world == null || placeAt == null) return false;
        if (placeAt.equals(feet) || placeAt.equals(feet.up())) return false;
        if (isInPlayerSpace(placeAt) || isInPlayerSpace(placeAt.up())) return false;
        BlockState at = mc.world.getBlockState(placeAt);
        if (!at.getFluidState().isEmpty() || isWaterOrIce(at)) return false;
        if (!at.isAir() && !at.isReplaceable()) return false;
        return isReliableSeedSupport(placeAt.down());
    }

    private boolean isDrySeedColumn(BlockPos support) {
        if (mc.world == null || support == null) return false;
        BlockPos placeAt = support.up();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockState state = mc.world.getBlockState(placeAt.offset(dir));
            if (!state.getFluidState().isEmpty() || isWaterOrIce(state)) return false;
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockState above = mc.world.getBlockState(placeAt.up(dy));
            if (!above.getFluidState().isEmpty() || isWaterOrIce(above)) return false;
        }
        return true;
    }

    private boolean isLocalWaterIceIsland(BlockPos center) {
        if (mc.world == null || center == null) return false;
        int connectedMossable = 0, borderChecks = 0, waterIceChecks = 0, radius = 2;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                p.set(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (isMossableBlock(mc.world.getBlockState(p).getBlock())) { connectedMossable++; continue; }
                if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                    borderChecks++;
                    if (hasWaterOrIceNearColumn(p.getX(), center.getY(), p.getZ(), p)) waterIceChecks++;
                }
            }
        }
        if (connectedMossable > waterIceIslandMaxSize.get()) return false;
        if (borderChecks == 0) return false;
        return waterIceChecks * 100 >= borderChecks * waterIceBorderPercent.get();
    }

    private int computeBoneBlocksToConvert() {
        if (mc.player == null) return 0;
        int boneBlocks = countBoneBlocks();
        if (boneBlocks <= 0) return 0;

        int capacity = boneMealAbsorptionCapacity();
        if (capacity < BONE_MEAL_PER_BLOCK) return 0;

        int maxByCapacity = capacity / BONE_MEAL_PER_BLOCK;
        return Math.min(boneBlocks, maxByCapacity);
    }


    private int boneMealAbsorptionCapacity() {
        if (mc.player == null) return 0;
        int partial = 0, emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (stack.getItem() == Items.BONE_MEAL && stack.getCount() < stack.getMaxCount()) {
                partial += stack.getMaxCount() - stack.getCount();
            }
        }
        int usableEmpty = Math.max(0, emptySlots - 1);
        return partial + usableEmpty * Items.BONE_MEAL.getMaxCount();
    }


    private boolean hasEmptySlotForCraftResult() {
        if (mc.player == null) return false;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
        }

        return empty >= 1;
    }

    private void tickCrafting() {
        if (mc.player == null) return;

        craftTick++;

        // Per-state timeout watchdog.
        if (craftState != craftLastState) {
            craftStateTimeout = 0;
            craftLastState    = craftState;
        }
        craftStateTimeout++;
        if (craftStateTimeout > CRAFT_STATE_TIMEOUT) {
            abortCraftingClean();
            return;
        }

        if (craftState == CraftState.PLACE_ONE
                || craftState == CraftState.COLLECT
                || craftState == CraftState.STOCK
                || craftState == CraftState.CLOSE) {

            if (!(mc.currentScreen instanceof InventoryScreen)) {
                craftScreenStable    = 0;
                craftOpenScheduled   = false;
                craftState           = CraftState.OPEN;
                craftTick            = 0;
                craftStateTimeout    = 0;
                return;
            }

            int liveSyncId = mc.player.playerScreenHandler.syncId;
            if (craftSyncId >= 0 && liveSyncId != craftSyncId) {
                craftSyncId       = -1;
                craftScreenStable = 0;
                craftState        = CraftState.OPEN;
                craftTick         = 0;
                craftStateTimeout = 0;
                return;
            }

            craftScreenStable++;
        }

        switch (craftState) {

            case OPEN -> {
                if (!craftOpenScheduled) {
                    craftOpenScheduled = true;
                    mc.execute(() -> {
                        if (mc.player == null) return;
                        if (!(mc.currentScreen instanceof InventoryScreen))
                            mc.setScreen(new InventoryScreen(mc.player));
                    });
                }
                craftState        = CraftState.WAIT_OPEN;
                craftTick         = 0;
                craftStateTimeout = 0;
                craftScreenStable = 0;
            }


            case WAIT_OPEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    if (craftTick % 4 == 0) {
                        mc.execute(() -> {
                            if (mc.player == null) return;
                            if (!(mc.currentScreen instanceof InventoryScreen))
                                mc.setScreen(new InventoryScreen(mc.player));
                        });
                    }
                    craftScreenStable = 0;
                    return;
                }
                craftScreenStable++;
                if (craftScreenStable < SCREEN_STABLE_TICKS) return;

                craftSyncId       = mc.player.playerScreenHandler.syncId;
                craftScreenStable = 0;
                craftState        = CraftState.PLACE_ONE;
                craftTick         = 0;
                craftStateTimeout = 0;
            }

            case PLACE_ONE -> {
                if (craftTick < craftDelay.get()) return;

                if (craftBlocksRemaining <= 0 || countBoneBlocks() == 0) {
                    craftState = keepHotbarStocked.get() ? CraftState.STOCK : CraftState.CLOSE;
                    craftTick  = 0; craftStateTimeout = 0;
                    return;
                }
                if (!hasEmptySlotForCraftResult()) {
                    craftState = keepHotbarStocked.get() ? CraftState.STOCK : CraftState.CLOSE;
                    craftTick  = 0; craftStateTimeout = 0;
                    return;
                }

                int srcSlot = findBoneBlockHandlerSlot();
                if (srcSlot == -1) {
                    craftState = keepHotbarStocked.get() ? CraftState.STOCK : CraftState.CLOSE;
                    craftTick  = 0; craftStateTimeout = 0;
                    return;
                }
                craftSrcSlot = srcSlot;

                final int sid   = craftSyncId;
                final int ssrc  = srcSlot;

                mc.execute(() -> {
                    if (mc.player == null || mc.interactionManager == null) return;

                    if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                        mc.interactionManager.clickSlot(sid, ssrc, 0,
                                SlotActionType.PICKUP, mc.player);
                    }

                    mc.interactionManager.clickSlot(sid, ssrc, 1,
                            SlotActionType.PICKUP, mc.player);

                    mc.interactionManager.clickSlot(sid, CRAFT_GRID_SLOT, 0,
                            SlotActionType.PICKUP, mc.player);
                });

                craftState        = CraftState.COLLECT;
                craftTick         = 0;
                craftStateTimeout = 0;
            }

            case COLLECT -> {
                if (craftTick < craftDelay.get()) return;

                final int sid = craftSyncId;

                mc.execute(() -> {
                    if (mc.player == null || mc.interactionManager == null) return;
                    ItemStack output = mc.player.playerScreenHandler.getSlot(CRAFT_OUTPUT_SLOT).getStack();
                    if (!output.isEmpty()) {
                        mc.interactionManager.clickSlot(sid, CRAFT_OUTPUT_SLOT, 0,
                                SlotActionType.QUICK_MOVE, mc.player);
                    }
                    ItemStack grid = mc.player.playerScreenHandler.getSlot(CRAFT_GRID_SLOT).getStack();
                    if (!grid.isEmpty()) {
                        mc.interactionManager.clickSlot(sid, CRAFT_GRID_SLOT, 0,
                                SlotActionType.QUICK_MOVE, mc.player);
                    }
                    if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                        int dropBack = (craftSrcSlot >= 0) ? craftSrcSlot : INV_FIRST;
                        mc.interactionManager.clickSlot(sid, dropBack, 0,
                                SlotActionType.PICKUP, mc.player);
                    }
                });

                craftBlocksRemaining--;
                craftTick         = 0;
                craftStateTimeout = 0;

                if (craftBlocksRemaining > 0
                        && countBoneBlocks() > 0
                        && hasEmptySlotForCraftResult()
                        && boneMealAbsorptionCapacity() >= BONE_MEAL_PER_BLOCK) {
                    craftState = CraftState.PLACE_ONE;
                } else {
                    craftState = keepHotbarStocked.get() ? CraftState.STOCK : CraftState.CLOSE;
                }
            }

            case STOCK -> {
                if (craftTick < craftDelay.get()) return;

                final int sid         = craftSyncId;
                final int targetSlot  = HOTBAR_FIRST_HANDLER + 8; // hotbar slot 9

                mc.execute(() -> {
                    if (mc.player == null || mc.interactionManager == null) return;
                    ItemStack inTarget = mc.player.playerScreenHandler.getSlot(targetSlot).getStack();
                    if (inTarget.getItem() == Items.BONE_MEAL) return; // already has bone meal
                    int bestSrc = -1, bestCount = 0;
                    for (int s = INV_FIRST; s <= INV_LAST; s++) {
                        if (s == targetSlot) continue;
                        ItemStack st = mc.player.playerScreenHandler.getSlot(s).getStack();
                        if (st.getItem() == Items.BONE_MEAL && st.getCount() > bestCount) {
                            bestCount = st.getCount(); bestSrc = s;
                        }
                    }
                    if (bestSrc == -1) return;
                    int hotbarButton = targetSlot - HOTBAR_FIRST_HANDLER;
                    mc.interactionManager.clickSlot(sid, bestSrc, hotbarButton,
                            SlotActionType.SWAP, mc.player);
                });

                craftState        = CraftState.CLOSE;
                craftTick         = 0;
                craftStateTimeout = 0;
            }

            case CLOSE -> {
                if (craftTick < craftDelay.get()) return;

                final int sid = craftSyncId;

                mc.execute(() -> {
                    if (mc.player == null || mc.interactionManager == null) return;
                    ItemStack out = mc.player.playerScreenHandler.getSlot(CRAFT_OUTPUT_SLOT).getStack();
                    if (!out.isEmpty())
                        mc.interactionManager.clickSlot(sid, CRAFT_OUTPUT_SLOT, 0,
                                SlotActionType.QUICK_MOVE, mc.player);
                    ItemStack grid = mc.player.playerScreenHandler.getSlot(CRAFT_GRID_SLOT).getStack();
                    if (!grid.isEmpty())
                        mc.interactionManager.clickSlot(sid, CRAFT_GRID_SLOT, 0,
                                SlotActionType.QUICK_MOVE, mc.player);
                    if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                        mc.interactionManager.clickSlot(sid, INV_FIRST, 0,
                                SlotActionType.PICKUP, mc.player);
                    }
                    mc.player.closeHandledScreen();
                });

                craftState           = CraftState.IDLE;
                craftLastState       = CraftState.IDLE;
                craftTick            = 0;
                craftStateTimeout    = 0;
                craftSyncId          = -1;
                craftScreenStable    = 0;
                craftBlocksRemaining = 0;
                craftSrcSlot         = -1;
                craftOpenScheduled   = false;
                craftCloseScheduled  = false;
            }

            default -> craftState = CraftState.IDLE;
        }
    }

    private boolean tryDisconnectWhenOutOfBoneSupply() {
        if (!disconnectWhenOutOfBoneSupply.get() || disconnectCommandSent) return false;
        if (mc.player == null) return false;
        if (countBoneMeal() > 0) return false;
        if (countBoneBlocks() > 0) return false;
        if (hasBoneBlocksInInventoryShulkers()) return false;
        if (baritoneRunning) stopBaritone();
        disableHelper(ShulkerRestock.class);
        restockRunning = false; disconnectCommandSent = true;
        ChatUtils.sendPlayerMsg(".disconnect");
        return true;
    }

    private boolean hasBoneBlocksInInventoryShulkers() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isShulkerBoxStack(stack)) continue;
            if (shulkerContainsBoneBlocks(stack)) return true;
        }
        return false;
    }

    private boolean isShulkerBoxStack(ItemStack stack) {
        return stack != null && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean shulkerContainsBoneBlocks(ItemStack shulkerStack) {
        ContainerComponent container = shulkerStack.getOrDefault(
                DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        return container.streamNonEmpty().anyMatch(stack -> stack.getItem() == Items.BONE_BLOCK);
    }

    private boolean tickRestockWait() {
        if (!restockRunning) return false;
        if (!restockFromShulkers.get()) { endShulkerRestock(); return false; }
        if (baritoneRunning) stopBaritone();
        boolean active = shulkerRestockActive();
        if (active) restockSeenActive = true;
        if (restockWarmup > 0) { restockWarmup--; return true; }
        if (!restockSeenActive) {
            if (restockTimeout.get() > 0 && ++restockWaitTicks >= restockTimeout.get()) {
                endShulkerRestock(); return false;
            }
            return true;
        }
        if (active) {
            restockInactiveStreak = 0;
        } else if (++restockInactiveStreak >= RESTOCK_INACTIVE_NEEDED) {
            endShulkerRestock(); return false;
        }
        if (restockTimeout.get() > 0 && ++restockWaitTicks >= restockTimeout.get()) {
            endShulkerRestock(); return false;
        }
        return true;
    }

    private boolean tryStartRestock() {
        if (!restockFromShulkers.get() || restockRunning) return false;
        if (countBoneMeal() == 0 && countBoneBlocks() == 0) {
            if (!hasBoneBlocksInInventoryShulkers()) return false;
            if (baritoneRunning) stopBaritone();
            enableHelper(ShulkerRestock.class);
            restockRunning = true; restockWaitTicks = 0;
            restockWarmup = RESTOCK_WARMUP_TICKS;
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
        restockRunning = false; restockWaitTicks = 0;
        restockWarmup = 0; restockSeenActive = false; restockInactiveStreak = 0;
    }

    private boolean tickUnstuck() {
        if (mc.player == null || mc.world == null) return false;
        boolean inWater = touchingWater();
        if (inWater) {
            if (escaping) { escaping = false; pillarPlaced = 0; pillarPhase = 0; }
            noProgressTicks = 0; lastProgressPos = mc.player.getPos(); return false;
        }
        boolean blockedAtFeet = hasPlayerSpaceObstruction(), prone = isProne();
        if ((blockedAtFeet || prone) && breakAboveCooldown <= 0) {
            if (clearPlayerSpaceObstruction()) {
                breakAboveCooldown = 4; noProgressTicks = 0; lastProgressPos = mc.player.getPos(); return true;
            }
            if (prone && breakBlockAbove()) {
                breakAboveCooldown = 4; noProgressTicks = 0; lastProgressPos = mc.player.getPos(); return true;
            }
        }
        if (escaping) return runPillarEscape();
        if ((prone || blockedAtFeet) && noProgressTicks >= stuckTicks.get()) {
            escaping = true; pillarPlaced = 0; pillarPhase = 0;
            if (baritoneRunning) stopBaritone();
        }
        return false;
    }

    private boolean hasPlayerSpaceObstruction() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        return isBreakablePlayerSpaceBlock(feet) || isBreakablePlayerSpaceBlock(feet.up());
    }

    private boolean clearPlayerSpaceObstruction() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        for (BlockPos pos : new BlockPos[]{ feet, feet.up() }) {
            if (!isBreakablePlayerSpaceBlock(pos)) continue;
            if (!tryConsumePacket(1)) return false;
            mc.interactionManager.attackBlock(pos, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            boneMealTargetCacheTTL = 0; mossInRangeCacheTTL = 0; return true;
        }
        return false;
    }

    private boolean isBreakablePlayerSpaceBlock(BlockPos pos) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.getHardness(mc.world, pos) < 0) return false;
        Block block = state.getBlock();
        if (block == Blocks.MOSS_CARPET || block == Blocks.AZALEA || block == Blocks.FLOWERING_AZALEA) return true;
        String name = block.getTranslationKey();
        return name.contains("moss_carpet") || name.contains("azalea")
                || name.contains("tall_grass") || name.contains("short_grass")
                || (name.contains("grass") && !name.contains("block"))
                || name.contains("fern") || name.contains("flower")
                || name.contains("sapling") || name.contains("bush");
    }

    private boolean isInPlayerSpace(BlockPos pos) {
        if (mc.player == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        return pos.equals(feet) || pos.equals(feet.up());
    }

    private boolean isProne() {
        if (mc.player == null) return false;
        EntityPose p = mc.player.getPose();
        return p == EntityPose.SWIMMING || mc.player.isCrawling();
    }

    private boolean touchingWater() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isTouchingWater() || mc.player.isSubmergedInWater()) return true;
        BlockPos feet = mc.player.getBlockPos();
        return !mc.world.getFluidState(feet).isEmpty() || !mc.world.getFluidState(feet.up()).isEmpty();
    }

    private boolean breakBlockAbove() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        for (BlockPos above : new BlockPos[]{ feet.up(), feet.up(2) }) {
            BlockState st = mc.world.getBlockState(above);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (st.getHardness(mc.world, above) < 0) continue;
            Vec3d hv = new Vec3d(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
            double[] yp = lookAt(hv); final BlockPos aboveF = above;
            rotateOnce(yp[0], yp[1], 100, true, () -> {
                if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                BlockState now = mc.world.getBlockState(aboveF);
                if (now.isAir() || !now.getFluidState().isEmpty()) return;
                mc.interactionManager.attackBlock(aboveF, Direction.DOWN);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
            return true;
        }
        return false;
    }

    private boolean runPillarEscape() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        boolean climbedOut = !touchingWater() && !isProne() && mc.player.isOnGround();
        if (climbedOut || pillarPlaced >= pillarMaxHeight.get()) {
            escaping = false; pillarPlaced = 0; pillarPhase = 0;
            noProgressTicks = 0; lastProgressPos = mc.player.getPos(); return false;
        }
        int pillarSlot = findPillarBlockSlot();
        if (pillarSlot < 0) { escaping = false; return false; }
        if (pillarPhase == 1) {
            if (pillarStepTimer > 0) { pillarStepTimer--; return true; }
            pillarPhase = 2;
        }
        if (pillarPhase == 0) {
            rotateOnce(mc.player.getYaw(), 90, 100, () -> {});
            if (mc.player.isOnGround() || touchingWater()) mc.player.jump();
            pillarPhase = 1; pillarStepTimer = pillarStepDelay.get(); return true;
        }
        BlockPos feet = mc.player.getBlockPos(), against = null;
        for (int d = 1; d <= 3; d++) {
            BlockPos p = feet.down(d); BlockState s = mc.world.getBlockState(p);
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) { against = p; break; }
        }
        if (against == null) { pillarPhase = 1; pillarStepTimer = pillarStepDelay.get(); return true; }
        BlockPos placeAt = against.up();
        if (!mc.world.getBlockState(placeAt).isAir() && !mc.world.getBlockState(placeAt).isReplaceable()) {
            pillarPhase = 1; pillarStepTimer = pillarStepDelay.get(); return true;
        }
        final BlockPos againstF = against; final int slotF = pillarSlot;
        Vec3d hv = new Vec3d(againstF.getX() + 0.5, againstF.getY() + 1.0, againstF.getZ() + 0.5);
        double[] yp = lookAt(hv);
        int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = pillarSlot;
        rotateOnce(yp[0], yp[1], 100, true, () -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            Block held = blockOfStack(slotF); if (held == null) return;
            BlockState a = mc.world.getBlockState(againstF);
            if (a.isAir() || a.isReplaceable() || !a.getFluidState().isEmpty()) return;
            BlockState at = mc.world.getBlockState(againstF.up());
            if (!at.isAir() && !at.isReplaceable()) return;
            if (mc.player.getEyePos().squaredDistanceTo(hv) > 4.4 * 4.4) return;
            if (!tryConsumePacket(1)) return;
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(hv, Direction.UP, againstF, false));
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
                    if (!tryConsumePacket(1)) break;
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                            playerInvToHandlerSlot(inv), hot, SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break;
            }
        }
        return -1;
    }


    private boolean detectKillAura() {
        if (mc.player == null || mc.world == null || !killAuraCompat.get()) return false;
        if (mc.player.handSwinging || mc.player.handSwingTicks > 0) {
            net.minecraft.util.hit.HitResult hit = mc.crosshairTarget;
            if (hit instanceof net.minecraft.util.hit.EntityHitResult ehr
                    && ehr.getEntity() instanceof net.minecraft.entity.LivingEntity) {
                cachedKillAuraResult = true; killAuraScanCooldown = KA_SCAN_INTERVAL; return true;
            }
        }
        if (killAuraScanCooldown > 0) { killAuraScanCooldown--; return cachedKillAuraResult; }
        killAuraScanCooldown = KA_SCAN_INTERVAL;
        if (mc.player.handSwinging || mc.player.handSwingTicks > 0) {
            net.minecraft.item.Item held = mc.player.getMainHandStack().getItem();
            boolean melee = held instanceof net.minecraft.item.SwordItem
                    || held instanceof net.minecraft.item.AxeItem
                    || held instanceof net.minecraft.item.PickaxeItem;
            if (melee) {
                for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                    if (!(e instanceof net.minecraft.entity.LivingEntity)) continue;
                    if (e == mc.player) continue;
                    if (mc.player.squaredDistanceTo(e) <= 36.0) { cachedKillAuraResult = true; return true; }
                }
            }
        }
        cachedKillAuraResult = false; return false;
    }


    private int sectorMinX() { return GRID_MIN_COORD + sectorColumn.get().ordinal() * SECTOR_SIZE + sectorMargin.get(); }
    private int sectorMaxX() { return GRID_MIN_COORD + sectorColumn.get().ordinal() * SECTOR_SIZE + SECTOR_SIZE - 1 - sectorMargin.get(); }
    private int sectorMinZ() { return GRID_MIN_COORD + (sectorRow.get() - 1) * SECTOR_SIZE + sectorMargin.get(); }
    private int sectorMaxZ() { return GRID_MIN_COORD + (sectorRow.get() - 1) * SECTOR_SIZE + SECTOR_SIZE - 1 - sectorMargin.get(); }

    private boolean insideSector(int x, int z) {
        return x >= sectorMinX() && x <= sectorMaxX() && z >= sectorMinZ() && z <= sectorMaxZ();
    }

    private int clampX(int x) { return Math.max(sectorMinX(), Math.min(x, sectorMaxX())); }
    private int clampZ(int z) { return Math.max(sectorMinZ(), Math.min(z, sectorMaxZ())); }


    private double[] lookAt(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return new double[]{ Math.toDegrees(Math.atan2(dz, dx)) - 90.0, -Math.toDegrees(Math.atan2(dy, horiz)) };
    }

    private void rotateOnce(double yaw, double pitch, int priority, Runnable action) {
        rotateOnce(yaw, pitch, priority, false, action);
    }

    private void rotateOnce(double yaw, double pitch, int priority, boolean force, Runnable action) {
        double dYaw = wrapDegrees(yaw - lastRotYaw), dPitch = pitch - lastRotPitch;
        boolean unchanged = !Double.isNaN(lastRotYaw)
                && Math.abs(dYaw) < ROT_EPSILON && Math.abs(dPitch) < ROT_EPSILON;
        lastRotYaw = yaw; lastRotPitch = pitch;
        if (unchanged && !force) { if (action != null) action.run(); return; }
        Rotations.rotate(yaw, pitch, priority, action);
    }

    private static double wrapDegrees(double d) {
        if (Double.isNaN(d)) return d;
        d %= 360.0;
        if (d <= -180.0) d += 360.0;
        if (d > 180.0) d -= 360.0;
        return d;
    }

    private boolean isMoving() {
        if (mc.player == null) return false;
        if (baritoneRunning) {
            try {
                if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) return true;
            } catch (Throwable ignored) {}
        }
        Vec3d v = mc.player.getVelocity();
        return (v.x * v.x + v.z * v.z) > 0.0025;
    }


    private int selectHotbarSynced(int slot) {
        int prev = mc.player.getInventory().selectedSlot;
        if (slot < 0 || slot > 8 || slot == prev) return prev;
        mc.player.getInventory().selectedSlot = slot;
        if (mc.player.networkHandler != null && tryConsumePacket(1))
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        return prev;
    }

    private void restoreHotbarSynced(int prev) {
        if (prev < 0 || prev > 8 || mc.player.getInventory().selectedSlot == prev) return;
        mc.player.getInventory().selectedSlot = prev;
        if (mc.player.networkHandler != null && tryConsumePacket(1))
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev));
    }


    private int countBoneMeal() {
        if (mc.player == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.BONE_MEAL)
                n += mc.player.getInventory().getStack(i).getCount();
        return n;
    }

    private int countBoneBlocks() { return InventoryUtils.countItemsInInventory(Items.BONE_BLOCK); }

    private int findBoneBlockHandlerSlot() {
        if (mc.player == null) return -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (mc.player.playerScreenHandler.getSlot(s).getStack().getItem() == Items.BONE_BLOCK)
                return s;
        }
        return -1;
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
                    if (!tryConsumePacket(1)) break;
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                            playerInvToHandlerSlot(inv), hot, SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break;
            }
        }
        return -1;
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
                    if (!tryConsumePacket(1)) break;
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

    private Block blockOfStack(int invIndex) {
        if (mc.player == null) return null;
        var item = mc.player.getInventory().getStack(invIndex).getItem();
        return item instanceof BlockItem bi ? bi.getBlock() : null;
    }


    private void tickCooldowns() {
        recentlyUsedMoss .entrySet().removeIf(e -> { e.setValue(e.getValue() - 1); return e.getValue() <= 0; });
        azaleaCooldownMap.entrySet().removeIf(e -> { e.setValue(e.getValue() - 1); return e.getValue() <= 0; });
    }

    private boolean isEatingProtectedFood() {
        if (mc.player == null || !mc.player.isUsingItem()) return false;
        net.minecraft.item.Item item = mc.player.getActiveItem().getItem();
        return item == Items.ENCHANTED_GOLDEN_APPLE
                || item == Items.GOLDEN_CARROT
                || item == Items.COOKED_BEEF;
    }
}

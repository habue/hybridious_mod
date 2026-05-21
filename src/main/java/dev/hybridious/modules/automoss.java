package dev.hybridious.modules;

import baritone.api.BaritoneAPI;
import dev.hybridious.Hybridious;
import dev.hybridious.utils.InventoryUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class automoss extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMoss    = settings.createGroup("Moss");
    private final SettingGroup sgTrees   = settings.createGroup("Trees");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Range to search for blocks to bonemeal.")
            .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Boolean> fullAuto = sgGeneral.add(new BoolSetting.Builder()
            .name("full-auto")
            .description("Starts LawnMower, SnowClearer, and Baritone block-mining on activation.")
            .defaultValue(true).build());

    private final Setting<List<Block>> pathfindBlocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("pathfind-blocks")
            .description("Surface block types to roam across (e.g. stone, grass_block, dirt).")
            .defaultValue(new ArrayList<>(List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.DIRT)))
            .visible(fullAuto::get).build());

    private final Setting<Integer> pathfindScanRadius = sgGeneral.add(new IntSetting.Builder()
            .name("pathfind-scan-radius")
            .description("Horizontal radius (blocks) to scan for pathfind targets.")
            .defaultValue(16).min(2).sliderMax(48)
            .visible(fullAuto::get).build());

    private final Setting<Integer> pathfindVerticalScan = sgGeneral.add(new IntSetting.Builder()
            .name("pathfind-vertical-scan")
            .description("Vertical range (blocks up/down) to search for the surface block in each column.")
            .defaultValue(6).min(1).sliderMax(20)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> surfaceOnly = sgGeneral.add(new BoolSetting.Builder()
            .name("surface-only")
            .description("Only path to blocks open to the sky. Prevents the bot from heading into caves/underground.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Integer> maxDescend = sgGeneral.add(new IntSetting.Builder()
            .name("max-descend")
            .description("Ignore surface targets more than this many blocks BELOW the player, so it won't chase ground far down a cliff/pillar into caves.")
            .defaultValue(4).min(1).sliderMax(32)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> allowBreak = sgGeneral.add(new BoolSetting.Builder()
            .name("allow-break")
            .description("Let Baritone break blocks while pathing. OFF stops it from digging holes when it stops.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> keepMoving = sgGeneral.add(new BoolSetting.Builder()
            .name("keep-moving")
            .description("Constantly roam to far un-visited surface, doubling back to missed spots only when none remain. OFF reverts to nearest-target with a pause.")
            .defaultValue(true)
            .visible(fullAuto::get).build());

    private final Setting<Boolean> serpentine = sgGeneral.add(new BoolSetting.Builder()
            .name("serpentine")
            .description("Mow the field in straight back-and-forth lanes (boustrophedon) instead of jumping to the farthest unvisited spot. Cleaner full-coverage pattern. Requires keep-moving.")
            .defaultValue(false)
            .visible(() -> fullAuto.get() && keepMoving.get()).build());

    private final Setting<Integer> laneWidth = sgGeneral.add(new IntSetting.Builder()
            .name("lane-width")
            .description("Spacing between serpentine lanes (blocks). Smaller = more thorough coverage but slower. ~3-4 suits the bonemeal range.")
            .defaultValue(3).min(1).sliderMax(8)
            .visible(() -> fullAuto.get() && keepMoving.get() && serpentine.get()).build());

    private final Setting<Integer> laneStep = sgGeneral.add(new IntSetting.Builder()
            .name("lane-step")
            .description("How far ahead (blocks) to aim the next #goto along a lane. Larger = longer uninterrupted strides.")
            .defaultValue(6).min(2).sliderMax(24)
            .visible(() -> fullAuto.get() && keepMoving.get() && serpentine.get()).build());

    private final Setting<Integer> roamRestartCooldown = sgGeneral.add(new IntSetting.Builder()
            .name("roam-restart-cooldown")
            .description("Ticks to wait after arriving before heading to the next target. Low = near-constant movement.")
            .defaultValue(5).min(0).sliderMax(100)
            .visible(fullAuto::get).build());

    private final Setting<Integer> visitedRadius = sgGeneral.add(new IntSetting.Builder()
            .name("visited-radius")
            .description("Columns within this radius of a reached target count as visited (won't immediately re-target).")
            .defaultValue(3).min(0).sliderMax(10)
            .visible(() -> fullAuto.get() && keepMoving.get()).build());

    private final Setting<Boolean> stopWhenOutOfMeal = sgGeneral.add(new BoolSetting.Builder()
            .name("stop-when-out-of-meal")
            .description("Stop pathing when out of bone meal (lets auto-craft run). OFF keeps roaming regardless.")
            .defaultValue(true).build());

    private final Setting<Boolean> jumpBeforeBaritone = sgGeneral.add(new BoolSetting.Builder()
            .name("jump-before-baritone")
            .description("Jump once before issuing a new #goto to escape a stuck position.")
            .defaultValue(true).build());

    private final Setting<Boolean> toggleLawnMower = sgGeneral.add(new BoolSetting.Builder()
            .name("toggle-lawnMower")
            .description("Toggle LawnMower so grass is cleared for moss spreading.")
            .defaultValue(true).build());

    private final Setting<Boolean> flipFlop = sgGeneral.add(new BoolSetting.Builder()
            .name("flip-flop")
            .description("Alternate between SnowClearer and moss-placing phases.")
            .defaultValue(false).build());

    private final Setting<Integer> flipFlopTicks = sgGeneral.add(new IntSetting.Builder()
            .name("flip-flop-ticks")
            .description("Ticks per flip-flop phase.")
            .defaultValue(20).min(1).sliderMax(40).build());

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
            .description("Ticks to wait between crafting slot interactions. On laggy servers (2b2t/Folia) keep this high (~12-15) so window-sync packets arrive before the next click.")
            .defaultValue(12).min(2).sliderMax(40).build());

    private final Setting<Boolean> keepHotbarStocked = sgGeneral.add(new BoolSetting.Builder()
            .name("keep-hotbar-stocked")
            .description("After crafting, force a full stack of bone meal into the 9th hotbar slot so the module never stalls for lack of usable meal.")
            .defaultValue(true).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay").description("Ticks between bone meal uses. On laggy servers (2b2t) ~3-4 avoids outrunning server-side validation and wasting meal.")
            .defaultValue(3).min(0).sliderMax(20).build());

    private final Setting<Integer> maxUsesPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("max-uses-per-tick").description("Max bone meal uses per tick.")
            .defaultValue(1).min(1).sliderMax(5).build());

    private final Setting<Boolean> syncRotationBonemeal = sgGeneral.add(new BoolSetting.Builder()
            .name("sync-rotation-bonemeal")
            .description("Server-sync the look direction before each bone meal use. Helps acceptance while moving on strict servers, BUT fights Baritone's own movement packets and can cause rubberbanding. Leave OFF unless bone meal is being rejected while standing still — bone meal on existing moss is validated loosely (mostly on reach), so it usually works without this.")
            .defaultValue(false).build());

    private final Setting<Integer> mossSpreadCooldown = sgMoss.add(new IntSetting.Builder()
            .name("moss-cooldown")
            .description("Ticks before re-bonemealing the same moss block.")
            .defaultValue(100).min(20).sliderMax(200).build());

    private final Setting<Boolean> requireSkyAccess = sgMoss.add(new BoolSetting.Builder()
            .name("require-sky-access")
            .description("Skip blocks buried under a thin ceiling (common on 2b2t). Requires open sky within the depth below.")
            .defaultValue(true).build());

    private final Setting<Integer> skyAccessDepth = sgMoss.add(new IntSetting.Builder()
            .name("sky-access-depth")
            .description("Max thickness of solid blocks allowed above a target before it's considered buried.")
            .defaultValue(5).min(1).sliderMax(20)
            .visible(requireSkyAccess::get).build());

    private final Setting<Boolean> placeMoss = sgMoss.add(new BoolSetting.Builder()
            .name("place-moss")
            .description("When no moss is in range, place ONE moss block at your feet (on a mossable block) to seed spreading.")
            .defaultValue(true).build());

    private final Setting<Integer> placeMossDelay = sgMoss.add(new IntSetting.Builder()
            .name("place-moss-delay")
            .description("Ticks to wait after placing a seed moss block before placing another.")
            .defaultValue(40).min(5).sliderMax(200)
            .visible(placeMoss::get).build());

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

    // --- Crafting state machine (player 2x2 inventory grid, no crafting table needed) ---
    // Strategy: place a bone block stack into crafting slot 1, then shift-click the output
    // --- Crafting state machine (player 2x2 inventory grid, no crafting table needed) ---
    // 2b2t-safe BATCH design: each batch loads only as many bone blocks into crafting slot 1
    // as the resulting bone meal will fit in current free inventory space (free_slots * 64 / 9
    // blocks), then a single shift-click on the output drains the whole grid at once. Because
    // the batch is sized to fit, the grid empties completely every time — there is never a
    // stranded multi-block leftover (the old deadlock). It then loops for the next batch until
    // the target count is met or space runs out. Fast (whole-grid craft per batch) and safe.
    private enum CraftState { IDLE, OPEN_SCREEN, MOVE_BATCH, CRAFT_BATCH, CLEAR_GRID, STOCK_HOTBAR, CLOSE }

    private CraftState craftState        = CraftState.IDLE;
    private CraftState lastCraftState     = CraftState.IDLE; // for detecting real progress (stuck-breaker)
    private int        craftTick         = 0;
    private int        craftBlocksNeeded = 0;  // remaining bone blocks we still want to convert
    private int        craftBatchSize    = 0;  // blocks loaded into the grid for the current batch
    private int        craftFailCount    = 0;  // safety counter if the recipe won't register
    private int        craftStuckTicks   = 0;  // hard deadlock breaker: forces screen close if a state can't progress
    private int        reservedLeftoverSlot = -1; // unused now; kept for compatibility/reset

    // --- Runtime state ---
    private boolean flipFlopPhase   = false;
    private int     flipFlopTimer   = 0;
    private boolean baritoneRunning = false;   // our desired state, not polled every tick
    private boolean wasEating       = false;
    private boolean stoppedForEat   = false;
    private int     delayTimer      = 0;
    private int     placeMossTimer  = 0;       // cooldown between seeding moss blocks
    private int     gotoRestartCooldown = 0;   // ticks to wait before reissuing #goto after arrival
    private int     pendingGotoTimer    = 0;   // ticks to wait after a pre-baritone jump before sending #goto
    private int     baritoneStallTicks  = 0;   // consecutive non-pathing ticks since #goto was issued
    private int     outOfMealTicks      = 0;   // consecutive ticks bone meal has read zero (debounce)

    // --- Serpentine (boustrophedon) roaming state ---
    // Sweep axis = the direction we mow along a lane; cross axis = the perpendicular shift.
    // Both are unit BlockPos steps (one of ±X or ±Z). Locked on first target pick so the
    // pattern stays axis-aligned and predictable instead of drifting diagonally.
    private int sweepDirX = 0, sweepDirZ = 0;   // primary mow direction (set on first run)
    private int crossDirX = 0, crossDirZ = 0;   // lane-shift direction (perpendicular)
    private boolean serpentineInit = false;     // have we locked the axes yet?
    private int laneAnchorPerp = 0;             // perpendicular coordinate of the current lane

    /** Grace ticks after issuing #goto before a non-pathing state counts as arrival (covers path-start lag). */
    private static final int GOTO_GRACE_TICKS = 10; // ~0.5 seconds

    /** Ticks bone meal must stay at zero before we cancel an in-flight #goto (avoids goto→stop thrash). */
    private static final int OUT_OF_MEAL_GRACE = 20; // ~1 second

    // --- Vanilla PlayerScreenHandler slot layout (inventory screen) ---
    //   Slot 0      = crafting output
    //   Slots 1-4   = crafting grid (2x2)
    //   Slots 5-8   = armor
    //   Slots 9-35  = main inventory (3 rows of 9)
    //   Slots 36-44 = hotbar
    //   Slot 45     = offhand
    // The bone-block scan therefore covers handler slots 9..44 (main inventory + hotbar),
    // which INCLUDES the top-left inventory slot (handler 9) — the slot the bone block sits
    // in in the screenshot. The old 10..45 range skipped handler 9 and wrongly grabbed the
    // offhand (45), which is why crafting failed for a block in that corner.
    private static final int INV_FIRST = 9;   // first main-inventory handler slot
    private static final int INV_LAST  = 44;  // last hotbar handler slot
    private static final int HOTBAR_FIRST_HANDLER = 36; // handler slot of hotbar index 0
    private static final int BONE_MEAL_PER_BLOCK  = 9;  // 1 bone block crafts into 9 bone meal

    /** Need at least this many empty inv slots before we'll even open the crafting screen. */
    private static final int MIN_EMPTY_TO_CRAFT = 3;
    /** Ticks a single crafting state may fail to make progress before we force-close the screen. */
    private static final int CRAFT_STUCK_LIMIT = 60; // ~3s — escape hatch, never deadlock

    private final Map<BlockPos, Integer> recentlyUsedMoss  = new HashMap<>();
    private final Map<BlockPos, Integer> azaleaCooldownMap = new HashMap<>();

    // Roaming: columns we've already reached, packed as (x,z) longs so we sweep forward
    // and only double back to gaps once the un-visited frontier is exhausted.
    private final Set<Long> visitedColumns = new HashSet<>();
    private BlockPos currentGotoTarget = null; // air position we last issued #goto to

    public automoss() {
        super(Hybridious.CATEGORY, "AutoMoss", "Automatically uses bone meal on specific blocks.");
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        craftState      = CraftState.IDLE;
        flipFlopPhase   = false;
        flipFlopTimer   = 0;
        baritoneRunning = false;
        wasEating       = false;
        stoppedForEat   = false;
        delayTimer      = 0;
        placeMossTimer  = 0;
        gotoRestartCooldown = 0;
        pendingGotoTimer = 0;
        baritoneStallTicks = 0;
        outOfMealTicks = 0;
        craftBlocksNeeded = 0;
        craftBatchSize = 0;
        craftFailCount = 0;
        craftStuckTicks = 0;
        lastCraftState = CraftState.IDLE;
        reservedLeftoverSlot = -1;
        visitedColumns.clear();
        serpentineInit = false;
        currentGotoTarget = null;

        startBaritone();

        if (fullAuto.get()) {
            LawnMower lawnMower = Modules.get().get(LawnMower.class);
            if (toggleLawnMower.get() && lawnMower != null && !lawnMower.isActive())
                lawnMower.toggle();

            SnowClearer sc = Modules.get().get(SnowClearer.class);
            if (flipFlop.get() && sc != null && !sc.isActive()) sc.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        stopBaritone();

        LawnMower lawnMower = Modules.get().get(LawnMower.class);
        if (lawnMower != null && lawnMower.isActive()) lawnMower.toggle();

        SnowClearer sc = Modules.get().get(SnowClearer.class);
        if (sc != null && sc.isActive()) sc.toggle();

        recentlyUsedMoss.clear();
        azaleaCooldownMap.clear();
        visitedColumns.clear();
        serpentineInit = false;
        currentGotoTarget = null;
        craftState = CraftState.IDLE;
        lastCraftState = CraftState.IDLE;
        craftStuckTicks = 0;
        reservedLeftoverSlot = -1;
    }

    // -------------------------------------------------------------------------
    // Baritone helpers — send commands only when state actually changes
    // -------------------------------------------------------------------------

    private void startBaritone() {
        if (baritoneRunning || mc.player == null) return;

        // Jump once before pathing to pop out of a stuck position (e.g. wedged in a 1-block
        // dip, snagged on a carpet edge, or stuck against a slab). We jump now and defer the
        // actual #goto by a couple ticks via pendingGotoTimer so the hop isn't immediately
        // cancelled by Baritone taking control of movement.
        if (jumpBeforeBaritone.get() && mc.player.isOnGround()) {
            mc.player.jump();
            pendingGotoTimer = 2;     // ticks to wait before sending #goto
            baritoneRunning  = true;  // mark running so we don't re-enter and double-jump
            return;
        }

        baritoneRunning = sendGoto(); // only "running" if a target was actually issued
    }

    /**
     * Issues a Baritone #goto to a standable position above the next surface target.
     * Returns true if a command was sent, false if no target was found nearby (in which
     * case a short retry cooldown is set so the supervisor tries again later).
     */
    private boolean sendGoto() {
        if (mc.player == null || mc.world == null) return false;

        BlockPos surface = pickRoamTarget();
        if (surface == null) {
            // Nothing matching nearby — don't issue a command; retry after a short cooldown.
            gotoRestartCooldown = 40;
            return false;
        }

        // Target the AIR block on top of the surface, not the solid block itself. Pointing
        // #goto at a solid block means "dig down to here" (the hole problem); pointing it at
        // the standable air above means "walk on top of here".
        BlockPos stand = surface.up();
        currentGotoTarget = stand;

        if (allowBreak.get()) {
            mc.player.networkHandler.sendChatMessage("#allowBreak true");
        } else {
            // Explicitly forbid breaking so Baritone never tunnels/holes to reach a spot.
            mc.player.networkHandler.sendChatMessage("#allowBreak false");
        }
        // #goto takes exactly ONE destination, so we feed it explicit coordinates of the
        // standable position above the chosen surface block.
        mc.player.networkHandler.sendChatMessage(
                "#goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
        return true;
    }

    /**
     * Chooses the next roam destination among surface columns in range.
     *
     * keep-moving ON: prefer the FARTHEST un-visited surface column so the player sweeps
     * outward and keeps moving instead of oscillating around its feet. When every column in
     * range is already visited, the visited set is cleared so it doubles back over missed
     * areas on a fresh pass.
     *
     * keep-moving OFF: classic nearest-target behavior.
     *
     * Returns the SURFACE block position (the solid block); the caller targets the air above it.
     */
    private BlockPos pickRoamTarget() {
        List<BlockPos> surfaces = findSurfaceTargets();
        if (surfaces.isEmpty()) return null;

        BlockPos origin = mc.player.getBlockPos();

        if (!keepMoving.get()) {
            // Nearest target.
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos p : surfaces) {
                double d = p.getSquaredDistance(origin);
                if (d < bestDistSq) { bestDistSq = d; best = p; }
            }
            return best;
        }

        // --- Serpentine lane mowing (boustrophedon) ---
        if (serpentine.get()) {
            BlockPos s = pickSerpentineTarget(surfaces, origin);
            if (s != null) return s;
            // Fall through to farthest-unvisited if serpentine can't find a step
            // (e.g. truly enclosed) so the bot never deadlocks.
        }

        // --- Roaming: farthest UN-VISITED column ---
        BlockPos farthestUnvisited = null;
        double farDistSq = -1;
        for (BlockPos p : surfaces) {
            if (visitedColumns.contains(columnKey(p))) continue;
            double d = p.getSquaredDistance(origin);
            if (d > farDistSq) { farDistSq = d; farthestUnvisited = p; }
        }

        if (farthestUnvisited != null) return farthestUnvisited;

        // Everything in range is visited → start a fresh sweep (double back over missed spots).
        visitedColumns.clear();
        BlockPos farthest = null;
        double maxDistSq = -1;
        for (BlockPos p : surfaces) {
            double d = p.getSquaredDistance(origin);
            if (d > maxDistSq) { maxDistSq = d; farthest = p; }
        }
        return farthest;
    }

    /**
     * Boustrophedon ("ox-turning" / lawnmower) target picker.
     *
     * Locks two perpendicular axis-aligned directions on first use:
     *   - sweep axis: the direction we mow along a lane (chosen from the LONGER reachable
     *     extent of valid surface so the first lane runs along the field, not across it)
     *   - cross axis: perpendicular; the direction we shift over to start the next lane.
     *
     * Each call it tries, in order:
     *   1. Continue down the current lane: a surface column laneStep blocks ahead along the
     *      sweep axis that's still within the scanned set.
     *   2. If the lane is exhausted (edge of field / nothing ahead), shift over by laneWidth
     *      on the cross axis, FLIP the sweep direction, and aim into the new lane.
     *   3. If neither works (boxed in on this side), it returns null so the caller falls back
     *      to farthest-unvisited and the pattern re-seeds on the next lock.
     *
     * Works purely off what findSurfaceTargets() returns each scan, so it naturally follows
     * the real shape of the terrain rather than assuming a perfect rectangle.
     */
    private BlockPos pickSerpentineTarget(List<BlockPos> surfaces, BlockPos origin) {
        // Fast lookup of which columns are valid surface this scan.
        Set<Long> valid = new HashSet<>();
        for (BlockPos p : surfaces) valid.add(columnKey(p));

        // Lock the sweep/cross axes once, based on which way the field is longer.
        if (!serpentineInit) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : surfaces) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            int extentX = maxX - minX;
            int extentZ = maxZ - minZ;

            if (extentX >= extentZ) {
                // Mow along X, shift along Z.
                sweepDirX = 1; sweepDirZ = 0;
                crossDirX = 0; crossDirZ = 1;
            } else {
                // Mow along Z, shift along X.
                sweepDirX = 0; sweepDirZ = 1;
                crossDirX = 1; crossDirZ = 0;
            }
            laneAnchorPerp = perpCoord(origin);
            serpentineInit = true;
        }

        // 1. Try to continue along the current lane (look a few distances ahead, nearest first).
        for (int dist = 2; dist <= laneStep.get(); dist++) {
            BlockPos ahead = origin.add(sweepDirX * dist, 0, sweepDirZ * dist);
            // Keep the candidate roughly on the current lane (within half a lane-width perpendicular).
            if (Math.abs(perpCoord(ahead) - laneAnchorPerp) > Math.max(1, laneWidth.get() / 2)) continue;
            BlockPos surf = nearestSurfaceInColumn(ahead, valid, surfaces);
            if (surf != null) return surf;
        }

        // 2. Lane exhausted → shift to the next lane and reverse the sweep direction.
        sweepDirX = -sweepDirX;
        sweepDirZ = -sweepDirZ;
        laneAnchorPerp += isPerpX() ? crossDirX * laneWidth.get()
                : crossDirZ * laneWidth.get();

        // Aim into the new lane: step over on the cross axis, slightly into the new sweep dir.
        for (int over = laneWidth.get(); over <= laneWidth.get() * 2; over++) {
            BlockPos shifted = origin.add(crossDirX * over, 0, crossDirZ * over);
            BlockPos entry = shifted.add(sweepDirX * 2, 0, sweepDirZ * 2);
            BlockPos surf = nearestSurfaceInColumn(entry, valid, surfaces);
            if (surf != null) return surf;
            // Try the lane entry without the forward nudge too.
            surf = nearestSurfaceInColumn(shifted, valid, surfaces);
            if (surf != null) return surf;
        }

        // 3. Couldn't advance or shift — let the caller fall back.
        return null;
    }

    /** The coordinate along the CROSS (lane-shift) axis for a position. */
    private int perpCoord(BlockPos p) {
        return isPerpX() ? p.getX() : p.getZ();
    }

    /** True if the cross (perpendicular/shift) axis is the X axis. */
    private boolean isPerpX() {
        return crossDirX != 0;
    }

    /**
     * Finds the actual scanned surface column closest to a target (x,z), within a small search
     * box, returning the real surface BlockPos (with its correct Y). Lets the serpentine logic
     * aim at an idealized lane coordinate while still snapping to a genuine reachable block.
     */
    private BlockPos nearestSurfaceInColumn(BlockPos target, Set<Long> valid, List<BlockPos> surfaces) {
        // Exact column hit first.
        long key = columnKey(target);
        if (valid.contains(key)) {
            for (BlockPos p : surfaces)
                if (p.getX() == target.getX() && p.getZ() == target.getZ()) return p;
        }
        // Otherwise the nearest surface within a 2-block box of the aim point.
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos p : surfaces) {
            int dx = Math.abs(p.getX() - target.getX());
            int dz = Math.abs(p.getZ() - target.getZ());
            if (dx > 2 || dz > 2) continue;
            double d = p.getSquaredDistance(target);
            if (d < bestDistSq) { bestDistSq = d; best = p; }
        }
        return best;
    }

    /** Packs a block's x,z into a single long key for the visited-columns set. */
    private static long columnKey(BlockPos p) {
        return (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
    }

    /**
     * Scans the horizontal area around the player and returns every surface column's top
     * matching block. "Surface" here means OUTDOOR surface: the block has air directly above
     * AND open sky all the way up (no solid ceiling). Without the sky check, cave stone with
     * a pocket of cave-air above it counts as "surface" and the bot paths down into caves.
     */
    private List<BlockPos> findSurfaceTargets() {
        List<BlockPos> out = new ArrayList<>();
        List<Block> wanted = pathfindBlocks.get();
        if (wanted == null || wanted.isEmpty()) return out;

        BlockPos origin = mc.player.getBlockPos();
        int horiz = pathfindScanRadius.get();
        int vert  = pathfindVerticalScan.get();
        int floorY = origin.getY() - 1;                  // block we're standing on
        int minTargetY = floorY - maxDescend.get();      // don't chase ground far below us

        for (int dx = -horiz; dx <= horiz; dx++) {
            for (int dz = -horiz; dz <= horiz; dz++) {
                for (int dy = vert; dy >= -vert; dy--) {
                    BlockPos p = origin.add(dx, dy, dz);
                    Block b = mc.world.getBlockState(p).getBlock();

                    if (!wanted.contains(b)) continue;
                    if (p.getY() < minTargetY) continue;                     // too far below → avoids descending into caves
                    if (!mc.world.getBlockState(p.up()).isAir()) continue;   // walkable air above
                    if (surfaceOnly.get() && !isOutdoorSurface(p)) continue; // skip cave/buried blocks

                    out.add(p);
                    break; // surface block for this column found; next column
                }
            }
        }
        return out;
    }

    /**
     * True only if the block is the genuine outdoor surface: the space above it is open to the
     * sky with no solid ceiling. This rejects cave stone (which has rock above) so pathfinding
     * never heads underground.
     *
     * Primary test is World#isSkyVisible on the air block above (portable across versions and
     * cheap). A short upward solid-block scan backs it up in case sky light is momentarily
     * unreliable (e.g. just-loaded chunks).
     */
    private boolean isOutdoorSurface(BlockPos pos) {
        if (mc.world == null) return false;

        BlockPos above = pos.up();

        // Direct sky-exposure check: is the standable space above this block open to the sky?
        if (mc.world.isSkyVisible(above)) return true;

        // Fallback: scan upward a bounded distance; any solid (non-air, non-fluid, non-plant)
        // block overhead means it's covered (cave or buried) → not an outdoor surface.
        int ceilingScan = 64; // plenty to clear thin caps without scanning the whole column
        for (int dy = 1; dy <= ceilingScan; dy++) {
            BlockState st = mc.world.getBlockState(pos.up(dy));
            if (st.isAir()) continue;
            if (!st.getFluidState().isEmpty()) continue; // ignore water/lava
            String n = st.getBlock().getTranslationKey().toLowerCase();
            boolean passable = n.contains("grass") || n.contains("fern") || n.contains("flower")
                    || n.contains("vine") || n.contains("sapling") || n.contains("moss_carpet")
                    || n.contains("snow") || n.contains("leaves");
            if (passable) continue;
            return false; // solid block overhead → buried/cave
        }
        return true;
    }

    /** Marks the column we just reached (and a small radius around it) as visited. */
    private void markVisited(BlockPos surface) {
        if (surface == null) return;
        int r = visitedRadius.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                visitedColumns.add(columnKey(surface.add(dx, 0, dz)));
            }
        }
    }

    private void stopBaritone() {
        if (!baritoneRunning || mc.player == null) return;
        mc.player.networkHandler.sendChatMessage("#stop");
        baritoneRunning  = false;
        pendingGotoTimer = 0;
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // --- Eating guard ---
        if (isEatingProtectedFood()) {
            if (!wasEating) {
                wasEating = true;
                if (baritoneRunning) {
                    stopBaritone();
                    stoppedForEat = true;
                }
            }
            return;
        }
        if (wasEating) {
            wasEating = false;
            if (stoppedForEat && countBoneMeal() > 0) {
                stoppedForEat = false;
                startBaritone();
            }
        }

        // --- Deferred #goto after a pre-baritone jump ---
        // startBaritone() jumps first and sets pendingGotoTimer; once it elapses we send
        // the actual pathing command so the hop has had time to clear the stuck spot.
        if (pendingGotoTimer > 0) {
            if (--pendingGotoTimer == 0) {
                baritoneRunning = sendGoto(); // false if no target found → supervisor retries
            }
            return; // let the jump/goto settle before doing anything else this tick
        }

        // --- Crafting state machine ---
        if (craftState != CraftState.IDLE) {
            tickCrafting();
            return; // don't bonemeal while crafting
        }

        // --- Trigger crafting only when COMPLETELY out of bone meal ---
        // Batch crafting: each batch places only as many blocks into the grid as the resulting
        // meal will FIT in free inventory space, so a shift-craft drains the grid completely
        // with no stranded leftovers, then repeats for the next batch. Fast (whole-grid craft
        // per batch) and deadlock-proof (the grid never holds more than fits).
        if (craftBoneMeal.get()
                && countBoneMeal() == 0
                && InventoryUtils.countItemsInInventory(Items.BONE_BLOCK) > 0
                && inventoryEmptySlots() >= MIN_EMPTY_TO_CRAFT) {

            if (baritoneRunning) stopBaritone();

            int available = InventoryUtils.countItemsInInventory(Items.BONE_BLOCK);

            // Convert as many blocks as we have, but always keep ONE bone block in reserve so
            // the supply never fully depletes. The per-batch sizing in MOVE_BATCH caps each
            // grid load to what the free space can absorb, so no overfill / no stranding.
            int wantToCraft = available > 1 ? available - 1 : available;
            if (wantToCraft < 1) return;

            craftBlocksNeeded = wantToCraft;
            craftState     = CraftState.OPEN_SCREEN;
            craftTick      = 0;
            craftFailCount = 0;
            craftStuckTicks = 0;
            reservedLeftoverSlot = -1;
            return;
        }

        checkAndBreakStuckBlock();

        // --- Baritone (#goto) supervision ---
        // #goto <coords> walks to one destination and stops pathing on arrival, so a
        // non-pathing tick means we've arrived (or it gave up). We allow a short startup
        // grace window after issuing the command, because isPathing() is briefly false
        // between sending #goto and Baritone actually computing/starting the path — without
        // it we'd falsely detect "arrival" one tick after issuing and thrash.
        if (!stoppedForEat) {
            boolean actuallyPathing = BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().isPathing();

            if (stopWhenOutOfMeal.get() && countBoneMeal() == 0) {
                // Out of bone meal — but DON'T cancel an in-flight #goto on a single zero
                // reading. Right after issuing #goto, isPathing() is briefly false while
                // Baritone computes the path; a momentary stop here was cancelling the goto
                // one tick after sending it (the goto→stop→goto loop). Require the empty
                // state to persist for a few ticks before we actually stop.
                if (baritoneRunning && ++outOfMealTicks >= OUT_OF_MEAL_GRACE) {
                    stopBaritone();
                    outOfMealTicks = 0;
                }
                baritoneStallTicks = 0;
            } else if (baritoneRunning) {
                outOfMealTicks = 0;
                if (actuallyPathing) {
                    baritoneStallTicks = 0; // path engaged / en route
                } else if (++baritoneStallTicks >= GOTO_GRACE_TICKS) {
                    // Sustained non-pathing after the grace window — arrived or gave up.
                    // Mark this column visited so the roam sweep moves on instead of
                    // re-picking it, then use the (short) roam cooldown before the next goto.
                    if (currentGotoTarget != null) markVisited(currentGotoTarget.down());
                    baritoneRunning    = false;
                    baritoneStallTicks = 0;
                    gotoRestartCooldown = keepMoving.get() ? roamRestartCooldown.get() : 60;
                }
            } else { // !baritoneRunning && have bone meal
                outOfMealTicks = 0;
                if (gotoRestartCooldown > 0) {
                    gotoRestartCooldown--;
                } else {
                    startBaritone();
                }
            }
        }

        // --- LawnMower runs every tick AutoMoss is active (not gated by flip-flop) ---
        if (toggleLawnMower.get()) {
            LawnMower lawnMower = Modules.get().get(LawnMower.class);
            if (lawnMower != null) lawnMower.tick();
        }

        // --- Moss seeding (runs every tick, even while pathing / in the SnowClearer phase) ---
        // Single-tick placement: if there's no moss in reach, place one seed on a reachable
        // floor block right now (look packet flushed inline so it works while moving fast).
        // Done here, before the flip-flop/delay early-returns below, so it isn't starved while
        // walking or snow-clearing.
        if (placeMossTimer > 0) placeMossTimer--;
        if (placeMoss.get() && !isMossInRange()) {
            trySeedMoss();
        }

        // --- Flip-flop: alternate between SnowClearer ticks and moss-placing ticks ---
        if (flipFlop.get()) {
            if (--flipFlopTimer <= 0) {
                flipFlopPhase = !flipFlopPhase;
                flipFlopTimer = flipFlopTicks.get();
            }
            if (flipFlopPhase) {
                SnowClearer sc = Modules.get().get(SnowClearer.class);
                if (sc != null) sc.tick();
                return;
            }
        }

        if (delayTimer > 0) { delayTimer--; return; }

        tickCooldowns();

        int boneMealSlot = findBoneMealSlot();

        // No bone meal usable this tick — nothing more to do (seeding handled above).
        if (boneMealSlot == -1) {
            return;
        }

        int uses = 0;
        for (BlockPos pos : findTargets()) {
            if (uses >= maxUsesPerTick.get()) break;

            BlockState state = mc.world.getBlockState(pos);
            boolean isMoss   = state.getBlock().getTranslationKey().contains("moss_block");

            if (isMoss && recentlyUsedMoss.containsKey(pos)) continue;

            if (!BoneMealItem.useOnFertilizable(mc.player.getInventory().getStack(boneMealSlot), mc.world, pos))
                continue;

            final Vec3d hitVec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

            if (!syncRotationBonemeal.get()) {
                // DEFAULT: interact directly without touching rotation. Bone meal on an existing
                // moss block is a use-item validated loosely (mostly on reach), so it's accepted
                // without aiming — and critically this does NOT fight Baritone's movement/look
                // packets, which is what caused rubberbanding when we force-rotated every use.
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
                int prevSlot = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = boneMealSlot;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.getInventory().selectedSlot = prevSlot;
            } else {
                // OPT-IN: server-sync the look first (for servers that DO validate use-item look
                // strictly). Snaps rotation toward the target via Meteor's Rotations, so it can
                // fight Baritone and cause rubberbanding — that's why it's off by default.
                final BlockPos posF = pos;
                final int boneMealSlotF = boneMealSlot;

                Vec3d eye = mc.player.getEyePos();
                double dx = hitVec.x - eye.x, dy = hitVec.y - eye.y, dz = hitVec.z - eye.z;
                double horiz = Math.sqrt(dx * dx + dz * dz);
                double yaw   = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
                double pitch = -Math.toDegrees(Math.atan2(dy, horiz));

                Rotations.rotate(yaw, pitch, 100, () -> {
                    if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                    if (mc.player.getInventory().getStack(boneMealSlotF).getItem() != Items.BONE_MEAL) return;
                    if (!BoneMealItem.useOnFertilizable(
                            mc.player.getInventory().getStack(boneMealSlotF), mc.world, posF)) return;

                    BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, posF, false);
                    int prevSlot = mc.player.getInventory().selectedSlot;
                    mc.player.getInventory().selectedSlot = boneMealSlotF;
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    mc.player.getInventory().selectedSlot = prevSlot;
                });
            }

            if (isMoss) recentlyUsedMoss.put(pos, mossSpreadCooldown.get());

            uses++;
            delayTimer = delay.get();
        }
    }

    // -------------------------------------------------------------------------
    // Seed-moss placement: drop ONE moss block at the player's feet when no moss is
    // in range, so bone meal has a spreading point. Only places on/next to mossable
    // surface blocks (dirt, stone, grass_block, etc.) and respects a cooldown so it
    // seeds one block at a time rather than carpeting the ground.
    // -------------------------------------------------------------------------

    /** True if any moss block exists within the configured range of the player. */
    private boolean isMossInRange() {
        if (mc.player == null || mc.world == null) return false;
        double rangeSq  = range.get() * range.get();
        BlockPos origin = mc.player.getBlockPos();
        int r           = (int) Math.ceil(range.get());

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (pos.getSquaredDistance(origin) > rangeSq) continue;
                    if (mc.world.getBlockState(pos).getBlock().getTranslationKey()
                            .toLowerCase().contains("moss_block")) return true;
                }
            }
        }
        return false;
    }

    /** Is this block one moss naturally spreads onto / can be placed against as a surface? */
    private boolean isMossableSurface(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.STONE
                || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT
                || b == Blocks.PODZOL || b == Blocks.MYCELIUM
                || b == Blocks.GRANITE || b == Blocks.DIORITE || b == Blocks.ANDESITE
                || b == Blocks.TUFF || b == Blocks.DEEPSLATE || b == Blocks.MOSS_BLOCK;
    }

    /**
     * Places a single moss block from the inventory near the player's feet to seed spreading.
     * You can't place into the space you occupy, so it targets the floor blocks immediately
     * around the player: an adjacent mossable floor block (dirt/stone/grass/...) with a free
     * (air/replaceable) space above it, placing the moss on that floor block's top face.
     *
     * 2b2t runs Paper/Folia, which validates placement strictly against the look direction the
     * SERVER last received — and the server only learns your new look from a rotation/movement
     * packet. The previous approaches failed because the use-item packet was sent (inside
     * interactBlock) BEFORE the client's own movement packet for that tick, so on a Paper
     * server the place arrived carrying the STALE rotation from the prior tick. While standing
     * still that rotation is already correct, so it worked; while Baritone moves, it's wrong and
     * the server silently drops the place.
     *
     * Fix: route the place through Meteor's Rotations utility, which sends a correctly-versioned
     * look packet to the server immediately and then runs our callback. By the time the callback
     * fires the interaction, the server has already processed the new look, so Paper's reach/look
     * check passes even at full Baritone speed. We also pick the CLOSEST reachable floor block
     * and reach-check it live so we never aim at a spot we've already slid past.
     * Places only ONE per call, then starts a cooldown so it seeds one block at a time.
     */
    private void trySeedMoss() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (placeMossTimer > 0) return; // still cooling down from the last seed

        int mossSlot = findMossBlockSlot();
        if (mossSlot < 0 || mossSlot >= 9) return; // need moss usable from the hotbar

        BlockPos feet = mc.player.getBlockPos();
        // Floor ring around the player (orthogonal first, then diagonals). Not directly under
        // us — that space holds our legs and can't be placed into.
        BlockPos[] floors = new BlockPos[]{
                feet.down().north(), feet.down().south(),
                feet.down().east(),  feet.down().west(),
                feet.down().north().east(), feet.down().north().west(),
                feet.down().south().east(), feet.down().south().west()
        };

        Vec3d eye = mc.player.getEyePos();
        // 2b2t/Paper enforces ~4.5 reach from the server-side position, which lags the client
        // when moving fast. Stay well under it so a fast slide doesn't push the real packet
        // past the limit between our check and the server's.
        final double maxReach = 3.5;
        final double maxReachSq = maxReach * maxReach;

        BlockPos bestFloor = null;
        Vec3d    bestHit   = null;
        double   bestDistSq = Double.MAX_VALUE;

        for (BlockPos floor : floors) {
            if (!isMossableSurface(mc.world.getBlockState(floor))) continue;

            BlockPos placeAt = floor.up(); // where the moss will go
            BlockState atState = mc.world.getBlockState(placeAt);
            // Accept air or replaceable plants (grass/fern/etc.) as a free space.
            if (!atState.isAir() && !atState.isReplaceable()) continue;
            // Don't place into the exact block the player body occupies.
            if (placeAt.equals(feet) || placeAt.equals(feet.up())) continue;

            // Aim at the CENTER of the floor block's top face (the face we're placing against).
            Vec3d hitVec = new Vec3d(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);

            // Reach check against the CURRENT eye position — skip anything we can't legitimately
            // reach right now, which is the common failure when sprinting/pathing past a spot.
            double distSq = eye.squaredDistanceTo(hitVec);
            if (distSq > maxReachSq) continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestFloor  = floor;
                bestHit    = hitVec;
            }
        }

        if (bestFloor == null) return; // nothing reachable this tick; try again next tick

        placeMossNow(bestFloor, bestHit, mossSlot);
    }

    /**
     * Places the moss block with a SERVER-SYNCED rotation so Paper/Folia (2b2t) accepts it while
     * moving. Select the moss slot first (so the held item is correct), compute the look angles
     * toward the hit point, then hand off to Rotations.rotate() — Meteor sends the look packet to
     * the server right away and invokes our callback once the server has the new rotation. We do
     * the actual interactBlock inside that callback, guaranteeing the place packet is validated
     * against the correct (already-sent) look rather than the stale one from the previous tick.
     */
    private void placeMossNow(BlockPos floor, Vec3d hitVec, int mossSlot) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mossSlot < 0 || mossSlot >= 9) return;
        if (mc.player.getInventory().getStack(mossSlot).getItem() != Items.MOSS_BLOCK) return;

        // Compute look angles toward the hit point from the current eye position.
        Vec3d eye = mc.player.getEyePos();
        double dx = hitVec.x - eye.x;
        double dy = hitVec.y - eye.y;
        double dz = hitVec.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yaw   = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, horiz));

        final BlockPos floorF  = floor;
        final Vec3d    hitVecF  = hitVec;
        final int      mossSlotF = mossSlot;

        // Rotations.rotate sends the look packet to the server NOW (correctly versioned by
        // Meteor) and runs the callback after, so the interaction below is validated against a
        // look the server already knows. Priority is high so this rotation isn't overridden by
        // another module the same tick.
        Rotations.rotate(yaw, pitch, 100, () -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            // Re-validate at place time: still mossable, still free, still holding moss.
            if (!isMossableSurface(mc.world.getBlockState(floorF))) return;
            BlockState at = mc.world.getBlockState(floorF.up());
            if (!at.isAir() && !at.isReplaceable()) return;
            if (mc.player.getInventory().getStack(mossSlotF).getItem() != Items.MOSS_BLOCK) return;
            // Reach can change between scheduling and firing while moving — re-check it.
            if (mc.player.getEyePos().squaredDistanceTo(hitVecF) > 4.4 * 4.4) return;

            int prevSlot = mc.player.getInventory().selectedSlot;
            mc.player.getInventory().selectedSlot = mossSlotF;

            BlockHitResult hit = new BlockHitResult(hitVecF, Direction.UP, floorF, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);

            mc.player.getInventory().selectedSlot = prevSlot;
        });

        placeMossTimer = placeMossDelay.get();
    }

    /**
     * Finds a hotbar slot holding a moss block. If none in the hotbar and inventory-allow is
     * on, swaps one up from the main inventory into an empty hotbar slot using a proper hotbar
     * SWAP (button = destination hotbar index, slot = source screen-handler slot). Returns the
     * resulting hotbar slot, or -1 if no moss is available.
     */
    private int findMossBlockSlot() {
        if (mc.player == null) return -1;

        // Already in the hotbar?
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.MOSS_BLOCK) return i;

        if (inventoryAllow.get()) {
            // Find moss in main inventory (player-inventory indices 9-35) and a free hotbar slot.
            for (int inv = 9; inv < 36; inv++) {
                if (mc.player.getInventory().getStack(inv).getItem() != Items.MOSS_BLOCK) continue;
                for (int hot = 0; hot < 9; hot++) {
                    if (!mc.player.getInventory().getStack(hot).isEmpty()) continue;
                    // Convert player-inventory index (9-35) to PlayerScreenHandler slot (9-35 → 10-36 area):
                    // main inventory occupies handler slots 9..35 for indices 9..35 in the
                    // player-inventory screen; the hotbar SWAP button is the destination hotbar index.
                    int handlerSlot = playerInvToHandlerSlot(inv);
                    mc.interactionManager.clickSlot(
                            mc.player.playerScreenHandler.syncId, handlerSlot, hot,
                            SlotActionType.SWAP, mc.player);
                    return hot;
                }
                break; // found moss but no free hotbar slot
            }
        }
        return -1;
    }

    /** Maps a player-inventory index (0-35) to its PlayerScreenHandler slot index. */
    private int playerInvToHandlerSlot(int invIndex) {
        // Player inventory screen layout: hotbar (inv 0-8) → handler 36-44; main (inv 9-35) → handler 9-35.
        if (invIndex >= 9) return invIndex;      // main inventory: 9-35 map 1:1
        return 36 + invIndex;                    // hotbar: 0-8 → 36-44
    }

    // -------------------------------------------------------------------------
    // Crafting: bone block → bone meal via 2×2 player inventory grid (no table needed)
    //
    // PlayerScreenHandler slot layout (inventory screen):
    //   Slot 0      = crafting output
    //   Slots 1-4   = crafting grid (2×2), top-left → top-right → bottom-left → bottom-right
    //   Slots 5-8   = armor
    //   Slots 9-35  = main inventory (3 rows of 9)
    //   Slots 36-44 = hotbar
    //   Slot 45     = offhand
    //
    // 2b2t-safe batch flow (never strands blocks in the grid):
    //   OPEN_SCREEN  → open the inventory screen.
    //   MOVE_BATCH   → load slot 1 with as many blocks as the resulting meal will FIT in free
    //                  space (free_slots*64/9), capped at one stack and the remaining target.
    //                  The rest of the stack stays in the inventory. Bails to CLEAR_GRID when
    //                  done or out of room.
    //   CRAFT_BATCH  → one shift-click drains the whole grid stack into the inventory at once
    //                  (vanilla auto-crafts the full stack). Sized to fit, so the grid empties
    //                  completely; credits the count and loops back to MOVE_BATCH for the next
    //                  batch. Handles partial drains and lag gracefully.
    //   CLEAR_GRID   → safety sweep: pull anything still in grid slots 1-4 / output back into
    //                  the inventory so closing never drops items on the ground.
    //   STOCK_HOTBAR → guarantee a full stack of bone meal sits in hotbar slot 9 (index 8)
    //                  so the module always has usable meal and never stalls.
    //   CLOSE        → close the screen and set a cooldown before Baritone resumes.
    //
    // A hard deadlock breaker (emergencyCraftAbort) force-closes if any single state can't
    // progress within a scaled time limit, so the module can never freeze with the screen open.
    // -------------------------------------------------------------------------

    private void tickCrafting() {
        craftTick++;
        int syncId = mc.player.playerScreenHandler.syncId;

        // Reset the deadlock counter whenever we actually advanced to a new state last tick;
        // it only accumulates while a single state spins in place.
        if (craftState != lastCraftState) {
            craftStuckTicks = 0;
            lastCraftState  = craftState;
        }

        // --- Hard deadlock breaker ---
        // If any single state churns for too long without progressing (e.g. the inventory
        // filled completely and the meal/blocks can't be moved), salvage what we can and force
        // the screen shut so the module never freezes. CLEAR_GRID does several legitimate moves
        // within one state (each spaced by craftingDelay), so we scale the ceiling to the delay
        // and allow plenty of sub-steps before declaring a true deadlock.
        int stuckLimit = Math.max(CRAFT_STUCK_LIMIT, craftingDelay.get() * 10);
        if (++craftStuckTicks > stuckLimit) {
            emergencyCraftAbort(syncId);
            return;
        }

        switch (craftState) {
            case OPEN_SCREEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen))
                    mc.setScreen(new InventoryScreen(mc.player));
                craftState = CraftState.MOVE_BATCH;
                craftTick  = 0;
            }

            // Load a SAFE batch of bone blocks into crafting slot 1: as many as the resulting
            // meal will fit in current free space, capped at one stack (64) and the remaining
            // target. The rest of the source stack stays in the inventory the whole time.
            case MOVE_BATCH -> {
                if (craftTick < craftingDelay.get()) return;

                // Cursor must be empty before we start juggling slots; park it if not.
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                    int dest = findEmptyInventoryScreenSlotExcluding(-1);
                    if (dest != -1)
                        mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                    craftTick = 0;
                    return;
                }

                // If slot 1 already holds a batch (e.g. resumed after a retry), craft it.
                if (slotItem(1) == Items.BONE_BLOCK) {
                    craftBatchSize = mc.player.playerScreenHandler.getSlot(1).getStack().getCount();
                    craftState = CraftState.CRAFT_BATCH;
                    craftTick  = 0;
                    return;
                }

                // Nothing more to do?
                if (craftBlocksNeeded <= 0) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }

                // How many blocks' worth of meal fits right now? Each free slot holds 64 meal,
                // each block makes 9 meal → blocks that fit = free_slots * 64 / 9. We size the
                // batch to that so the shift-craft drains the grid fully with no overflow.
                int free = countEmptyInventoryScreenSlotsExcluding(-1);
                int fitBySpace = (free * 64) / BONE_MEAL_PER_BLOCK;
                if (fitBySpace < 1) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }

                int srcSlot = findBoneBlockScreenSlot(-1);
                if (srcSlot == -1) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }

                int stackCount = mc.player.playerScreenHandler.getSlot(srcSlot).getStack().getCount();
                // Batch = min(what fits, what's left to craft, one stack, the source stack).
                int batch = Math.min(Math.min(fitBySpace, craftBlocksNeeded), Math.min(64, stackCount));
                if (batch < 1) { craftState = CraftState.CLEAR_GRID; craftTick = 0; return; }

                if (batch >= stackCount) {
                    // Taking the whole stack — pick it up and drop it all into slot 1.
                    mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                    mc.interactionManager.clickSlot(syncId, 1,       0, SlotActionType.PICKUP, mc.player);
                } else {
                    // Taking part of the stack: pick it up, RIGHT-CLICK 'batch' times into slot 1
                    // to deposit exactly that many blocks (each right-click drops one), then
                    // return the remainder of the held stack to its slot. The grid thus holds
                    // exactly what fits; everything else stays safely in the inventory.
                    mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                    for (int i = 0; i < batch; i++)
                        mc.interactionManager.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, mc.player);
                    if (!mc.player.playerScreenHandler.getCursorStack().isEmpty())
                        mc.interactionManager.clickSlot(syncId, srcSlot, 0, SlotActionType.PICKUP, mc.player);
                }

                craftBatchSize = batch;
                craftState = CraftState.CRAFT_BATCH;
                craftTick  = 0;
            }

            // Shift-click the output once: vanilla auto-crafts the WHOLE grid stack in a single
            // interaction, dumping all the meal into the inventory at once. Because the batch was
            // sized to fit, the grid drains completely.
            case CRAFT_BATCH -> {
                if (craftTick < craftingDelay.get()) return;

                // Grid already empty (craft completed / nothing loaded) → next batch.
                if (slotItem(1) != Items.BONE_BLOCK) {
                    craftBlocksNeeded -= craftBatchSize;
                    if (craftBlocksNeeded < 0) craftBlocksNeeded = 0;
                    craftBatchSize = 0;
                    craftFailCount = 0;
                    craftState = CraftState.MOVE_BATCH;
                    craftTick  = 0;
                    return;
                }

                // Wait for the recipe output to register (2b2t server round-trip can lag).
                if (slotItem(0) != Items.BONE_MEAL) {
                    if (++craftFailCount > 8) { craftState = CraftState.CLEAR_GRID; craftTick = 0; }
                    return;
                }

                int before = mc.player.playerScreenHandler.getSlot(1).getStack().getCount();
                mc.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                int after = slotItem(1) == Items.BONE_BLOCK
                        ? mc.player.playerScreenHandler.getSlot(1).getStack().getCount() : 0;

                if (after == 0) {
                    // Whole grid drained — batch complete.
                    craftBlocksNeeded -= craftBatchSize;
                    if (craftBlocksNeeded < 0) craftBlocksNeeded = 0;
                    craftBatchSize = 0;
                    craftFailCount = 0;
                    craftState = CraftState.MOVE_BATCH;
                    craftTick  = 0;
                } else if (after < before) {
                    // Partial drain (inventory filled before the grid emptied — rare since we
                    // sized to fit, but lag/rounding can cause it). Credit what was crafted and
                    // loop CRAFT_BATCH again to drain the rest into any space that opened.
                    craftBlocksNeeded -= (before - after);
                    if (craftBlocksNeeded < 0) craftBlocksNeeded = 0;
                    craftBatchSize = after; // remaining blocks still in the grid
                    craftFailCount = 0;
                    craftTick = 0;
                } else if (++craftFailCount > 8) {
                    // No progress at all — clean up and bail.
                    craftState = CraftState.CLEAR_GRID;
                    craftTick  = 0;
                } else {
                    craftTick = 0; // retry next interval (lag)
                }
            }

            // Pull anything still sitting in the crafting grid / output back into the inventory
            // before closing, so closing the screen never drops items on the ground.
            case CLEAR_GRID -> {
                if (craftTick < craftingDelay.get()) return;

                for (int s = 1; s <= 4; s++) {
                    if (!mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) {
                        // Prefer explicit pickup→empty-slot (reliable when same-item stacks exist),
                        // fall back to shift-move if nothing's free.
                        int dest = findEmptyInventoryScreenSlotExcluding(-1);
                        if (dest != -1) {
                            mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                        } else {
                            mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.QUICK_MOVE, mc.player);
                        }
                        craftTick = 0;
                        return; // one slot per cycle; re-check next interval
                    }
                }

                // Clear the output slot too.
                if (slotItem(0) == Items.BONE_MEAL) {
                    mc.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                    craftTick = 0;
                    return;
                }

                // Dump the cursor if anything is held.
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                    int dest = findEmptyInventoryScreenSlotExcluding(-1);
                    if (dest != -1) {
                        mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
                        craftTick = 0;
                        return;
                    }
                }

                craftState = keepHotbarStocked.get() ? CraftState.STOCK_HOTBAR : CraftState.CLOSE;
                craftTick  = 0;
            }
            case STOCK_HOTBAR -> {
                if (craftTick < craftingDelay.get()) return;

                // Ensure the 9th hotbar slot (hotbar index 8 → handler slot 44) holds a stack
                // of bone meal so the module always has usable meal in hand and never stalls.
                int targetHotbarHandler = HOTBAR_FIRST_HANDLER + 8; // 9th hotbar slot
                net.minecraft.item.Item in9th =
                        mc.player.playerScreenHandler.getSlot(targetHotbarHandler).getStack().getItem();

                if (in9th == Items.BONE_MEAL) {
                    // Already stocked — done.
                    craftState = CraftState.CLOSE;
                    craftTick  = 0;
                    return;
                }

                // Find the fullest bone-meal stack we just crafted (anywhere in inv/hotbar,
                // except the target slot itself) and SWAP it into the 9th hotbar slot. A SWAP
                // (button = destination hotbar index 8) cleanly exchanges contents even if the
                // 9th slot currently holds something else, without needing it empty first.
                int mealSrc = findBoneMealScreenSlot(targetHotbarHandler);
                if (mealSrc == -1) {
                    // No bone meal found at all (shouldn't happen right after crafting) — just close.
                    craftState = CraftState.CLOSE;
                    craftTick  = 0;
                    return;
                }

                // SWAP uses the destination HOTBAR INDEX (0-8) as the button; the 9th slot is index 8.
                mc.interactionManager.clickSlot(syncId, mealSrc, 8, SlotActionType.SWAP, mc.player);

                craftState = CraftState.CLOSE;
                craftTick  = 0;
            }
            case CLOSE -> {
                if (craftTick < craftingDelay.get()) return;
                mc.player.closeHandledScreen();
                craftState = CraftState.IDLE;
                lastCraftState = CraftState.IDLE;
                craftTick  = 0;
                craftStuckTicks = 0;
                reservedLeftoverSlot = -1;
                gotoRestartCooldown = 20; // brief pause before Baritone resumes
            }
            default -> craftState = CraftState.IDLE;
        }
    }

    /**
     * Last-resort escape from a stuck crafting screen. Best-effort salvage: shift any bone
     * blocks/meal out of the crafting grid (slots 1-4) and the output (slot 0) so nothing is
     * lost in the grid, then close the screen and fully reset the state machine. Called by the
     * deadlock breaker so the module can NEVER freeze with the inventory open (the screenshot
     * bug). Anything that genuinely won't move is left in the grid for the player, but the
     * module recovers and resumes instead of spinning forever.
     */
    private void emergencyCraftAbort(int syncId) {
        if (mc.player != null) {
            // Try to clear the grid + output back into the inventory (works whenever there's
            // any room or a mergeable stack; harmless no-op otherwise).
            for (int s = 0; s <= 4; s++) {
                if (!mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty())
                    mc.interactionManager.clickSlot(syncId, s, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
            // Drop the cursor into any empty slot so we don't close holding an item.
            if (!mc.player.playerScreenHandler.getCursorStack().isEmpty()) {
                int dest = findEmptyInventoryScreenSlotExcluding(-1);
                if (dest != -1)
                    mc.interactionManager.clickSlot(syncId, dest, 0, SlotActionType.PICKUP, mc.player);
            }
            mc.player.closeHandledScreen();
        }
        craftState        = CraftState.IDLE;
        lastCraftState    = CraftState.IDLE;
        craftTick         = 0;
        craftStuckTicks   = 0;
        craftBlocksNeeded = 0;
        craftBatchSize    = 0;
        craftFailCount    = 0;
        reservedLeftoverSlot = -1;
        gotoRestartCooldown  = 40; // longer pause; inventory is probably full, give it a beat
    }

    /** Item in a player-screen-handler slot, or null/empty item if none. */
    private net.minecraft.item.Item slotItem(int slot) {
        return mc.player.playerScreenHandler.getSlot(slot).getStack().getItem();
    }

    /**
     * First bone block stack in the inventory/hotbar region (handler slots 9-44), excluding
     * an optional reserved slot. Slot 9 is the top-left main-inventory slot (where the bone
     * block sits in the screenshot), so this now correctly finds blocks in that corner.
     */
    private int findBoneBlockScreenSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().getItem() == Items.BONE_BLOCK) return s;
        }
        return -1;
    }

    /**
     * First (and ideally fullest) bone-meal stack in the inventory/hotbar region
     * (handler slots 9-44), excluding an optional slot. Prefers the largest stack so the 9th
     * hotbar slot ends up with as full a stack as possible.
     */
    private int findBoneMealScreenSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        int bestCount = -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            var stack = mc.player.playerScreenHandler.getSlot(s).getStack();
            if (stack.getItem() != Items.BONE_MEAL) continue;
            if (stack.getCount() > bestCount) { bestCount = stack.getCount(); bestSlot = s; }
        }
        return bestSlot;
    }

    /** First empty slot in the inventory/hotbar region (handler 9-44), excluding one slot, or -1. */
    private int findEmptyInventoryScreenSlotExcluding(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) return s;
        }
        return -1;
    }

    /** Count of empty slots in the inventory/hotbar region (handler 9-44), excluding one slot. */
    private int countEmptyInventoryScreenSlotsExcluding(int excludeSlot) {
        if (mc.player == null) return 0;
        int n = 0;
        for (int s = INV_FIRST; s <= INV_LAST; s++) {
            if (s == excludeSlot) continue;
            if (mc.player.playerScreenHandler.getSlot(s).getStack().isEmpty()) n++;
        }
        return n;
    }

    // -------------------------------------------------------------------------
    // Block targeting
    // -------------------------------------------------------------------------

    private List<BlockPos> findTargets() {
        List<BlockPos> targets = new ArrayList<>();
        if (mc.player == null || mc.world == null) return targets;

        double rangeSq    = range.get() * range.get();
        BlockPos origin   = mc.player.getBlockPos();
        int r             = (int) Math.ceil(range.get());

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (pos.getSquaredDistance(origin) > rangeSq) continue;
                    if (!hasLineOfSight(pos)) continue;

                    BlockState state  = mc.world.getBlockState(pos);
                    String blockName  = state.getBlock().getTranslationKey().toLowerCase();

                    if (makeTrees.get()) {
                        boolean isAzalea = blockName.contains("azalea") && !blockName.contains("tree");
                        boolean isSapling = blockName.contains("sapling");

                        if (isAzalea) {
                            if (!azaleaCooldownMap.containsKey(pos)) {
                                if ((int)(Math.random() * 10) < azaleaTreeFraction.get())
                                    targets.add(pos);
                                azaleaCooldownMap.put(pos, azaleaCooldownSetting.get());
                            }
                            continue;
                        }
                        if (isSapling) { targets.add(pos); continue; }
                    }

                    if (blockName.contains("moss_block") && hasValidNeighbor(pos)
                            && !isObstructedAbove(pos) && hasSkyAccess(pos))
                        targets.add(pos);
                }
            }
        }
        return targets;
    }

    private boolean hasValidNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            String n = mc.world.getBlockState(pos.offset(dir)).getBlock().getTranslationKey().toLowerCase();
            if (n.contains("azalea") || n.contains("tall_grass") ||
                    (n.contains("grass") && !n.contains("block")) ||
                    n.contains("moss_block") || n.contains("moss_carpet")) continue;
            return true;
        }
        return false;
    }

    /**
     * Moss won't spread upward if the block directly above is occupied by something that
     * blocks growth: lava, water (any fluid), torches (any variant), or signs (any type).
     * Skip these targets so we don't waste bone meal on blocks that can't sprout.
     */
    private boolean isObstructedAbove(BlockPos pos) {
        BlockState above = mc.world.getBlockState(pos.up());

        // Any fluid sitting on top (lava/water, flowing or source) blocks spread.
        if (!above.getFluidState().isEmpty()) return true;

        String n = above.getBlock().getTranslationKey().toLowerCase();
        return n.contains("torch")           // torch, wall_torch, soul_torch, redstone_torch, lantern-ish
                || n.contains("lantern")
                || n.contains("sign")         // sign, wall_sign, hanging_sign (any wood type)
                || n.contains("lava")
                || n.contains("water");
    }

    /**
     * On servers like 2b2t, moss is often buried under a thin cap of solid blocks (~5 thick)
     * with open sky just beyond. We only want targets with genuine sky access, so scan the
     * column directly above: if any solid (non-air, non-fluid, non-plant) block is found
     * within skyAccessDepth, treat the target as buried under a thin ceiling and skip it.
     */
    private boolean hasSkyAccess(BlockPos pos) {
        if (!requireSkyAccess.get()) return true;

        int depth = skyAccessDepth.get();
        for (int dy = 1; dy <= depth; dy++) {
            BlockPos above = pos.up(dy);
            BlockState state = mc.world.getBlockState(above);

            if (state.isAir()) continue;                       // open air — keep scanning up
            if (!state.getFluidState().isEmpty()) return false; // fluid cap blocks spread anyway

            String n = state.getBlock().getTranslationKey().toLowerCase();
            // Vegetation / passable decoration doesn't count as a ceiling.
            boolean passable = n.contains("grass") || n.contains("fern")
                    || n.contains("flower") || n.contains("azalea")
                    || n.contains("moss_carpet") || n.contains("sapling")
                    || n.contains("vine");
            if (passable) continue;

            // Hit a genuine solid block within the thin-cap depth → buried, skip it.
            return false;
        }
        return true;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Vec3d eye   = mc.player.getEyePos();
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        RaycastContext ctx = new RaycastContext(eye, center,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        return mc.world.raycast(ctx).getBlockPos().equals(pos);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private void checkAndBreakStuckBlock() {
        if (mc.player == null || mc.world == null) return;
        BlockPos feet = mc.player.getBlockPos();
        for (BlockPos check : new BlockPos[]{feet, feet.up()}) {
            BlockState state = mc.world.getBlockState(check);
            String name = state.getBlock().getTranslationKey().toLowerCase();

            boolean isAzaleaBush = name.equals("block.minecraft.azalea")
                    || name.equals("block.minecraft.flowering_azalea");
            boolean isMossCarpet = name.equals("block.minecraft.moss_carpet");

            if (!isAzaleaBush && !isMossCarpet) continue;

            // For azalea: only break if physically intersecting
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
        recentlyUsedMoss.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });
        azaleaCooldownMap.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });
    }

    private int hotbarEmptySlots() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
        return empty;
    }

    /** Empty slots across the whole main inventory + hotbar (0-35). */
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
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() != Items.BONE_MEAL) continue;
                for (int j = 0; j < 9; j++) {
                    if (mc.player.getInventory().getStack(j).isEmpty()) {
                        mc.interactionManager.clickSlot(0, i, j, SlotActionType.SWAP, mc.player);
                        return j;
                    }
                }
                break;
            }
        }
        return -1;
    }
}

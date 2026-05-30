package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ShulkerRestock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgTiming = settings.createGroup("Timing & Retries");





    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
            .name("target-item")
            .description("The item to restock from your shulker boxes.")
            .defaultValue(Items.BONE_BLOCK)
            .build()
    );

    private final Setting<Boolean> fillHalf = sgGeneral.add(new BoolSetting.Builder()
            .name("fill-empty-half")
            .description("Instead of a fixed stack count, target half of your empty inventory + hotbar slots.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> stacksToRestock = sgGeneral.add(new IntSetting.Builder()
            .name("stacks-to-restock")
            .description("How many stacks to target (used only when 'fill-empty-half' is off).")
            .defaultValue(9)
            .min(1)
            .sliderRange(1, 27)
            .visible(() -> !fillHalf.get())
            .build()
    );

    private final Setting<Boolean> autoExtract = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-extract")
            .description("Automatically shift-click the target stacks out of the shulker. " +
                    "When off, you grab the stacks by hand and close the box yourself.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> autoMine = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-mine-with-baritone")
            .description("After the box is emptied, run Baritone 'mine minecraft:shulker_box' to break and collect it. " +
                    "The box is placed reachably (support-first) so Baritone can path to it.")
            .defaultValue(true)
            .build()
    );

    private final Setting<String> prefixOverride = sgGeneral.add(new StringSetting.Builder()
            .name("prefix-override")
            .description("Leave blank to use Baritone's configured prefix automatically. " +
                    "Set it (e.g. '#', '.b ', '.baritone ') only if auto-detection sends to the wrong place.")
            .defaultValue("")
            .visible(autoMine::get)
            .build()
    );

    private final Setting<Boolean> returnShulker = sgGeneral.add(new BoolSetting.Builder()
            .name("return-shulker-to-inventory")
            .description("After the empty shulker is picked up, move it back into a free upper-inventory slot.")
            .defaultValue(true)
            .build()
    );



    private final Setting<Boolean> preferReachable = sgPlacement.add(new BoolSetting.Builder()
            .name("prefer-reachable-placement")
            .description("Place the shulker on a real support surface FIRST so Baritone can always path to it and " +
                    "mine it. Air-place is only a fallback when no reachable support face is found nearby. " +
                    "Turn OFF to restore the old air-place-first behaviour (a floating box Baritone can't reach).")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
            .name("air-place")
            .description("Allow placing the shulker in mid-air facing you (no support block needed) so a block above " +
                    "it won't obstruct opening. With 'prefer-reachable-placement' ON this is only a FALLBACK when no " +
                    "support face exists. Uses an offhand-swap interaction split across ticks for Grim/Folia.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> placeDistance = sgPlacement.add(new DoubleSetting.Builder()
            .name("place-distance")
            .description("How far in front of you (blocks) to air-place the shulker (fallback only). " +
                    "Higher values place the box further away; multiple candidate distances are scanned around this value.")
            .defaultValue(3.5)
            .min(1.0)
            .sliderRange(1.0, 5.0)
            .visible(airPlace::get)
            .build()
    );

    private final Setting<Integer> placeRange = sgPlacement.add(new IntSetting.Builder()
            .name("support-place-range")
            .description("Max distance (blocks) to search for a support face for reachable placement.")
            .defaultValue(4)
            .min(1)
            .sliderRange(1, 5)
            .build()
    );

    private final Setting<Integer> rotationPriority = sgPlacement.add(new IntSetting.Builder()
            .name("rotation-priority")
            .description("Priority for rotation requests sent to the server. Raise it if another module " +
                    "(e.g. Killaura) is fighting for rotations and the open/place keeps failing.")
            .defaultValue(200)
            .min(0)
            .sliderRange(0, 400)
            .build()
    );



    private final Setting<Integer> actionDelay = sgTiming.add(new IntSetting.Builder()
            .name("action-delay")
            .description("Tick delay between actions (helps avoid desyncs on laggy servers like 2b2t).")
            .defaultValue(4)
            .min(0)
            .sliderRange(0, 20)
            .build()
    );

    private final Setting<Integer> maxRetries = sgTiming.add(new IntSetting.Builder()
            .name("max-retries")
            .description("How many times to retry ANY single step (place, open, mine, pickup, return) " +
                    "before aborting that step. Applies to every stage.")
            .defaultValue(5)
            .min(1)
            .sliderRange(1, 20)
            .build()
    );

    private final Setting<Integer> stepTimeout = sgTiming.add(new IntSetting.Builder()
            .name("step-timeout")
            .description("Ticks a single attempt waits for confirmation before counting as a failed try. " +
                    "20 ticks = 1s. Raise under heavy server lag.")
            .defaultValue(40)
            .min(10)
            .sliderRange(10, 200)
            .build()
    );

    private final Setting<Integer> pickupTimeout = sgTiming.add(new IntSetting.Builder()
            .name("pickup-timeout")
            .description("Ticks to wait for the broken shulker item to be picked up per mining attempt.")
            .defaultValue(300)
            .min(40)
            .sliderRange(40, 1200)
            .build()
    );

    private final Setting<Boolean> pauseForKillAura = sgTiming.add(new BoolSetting.Builder()
            .name("pause-for-killaura")
            .description("Pause ShulkerRestock while KillAura is active and a living entity is nearby. " +
                    "This prevents combat rotations/attacks from breaking placed-shulker open, extract, mine, and pickup recovery.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> combatPauseRange = sgTiming.add(new DoubleSetting.Builder()
            .name("combat-pause-range")
            .description("Entity range used to decide when KillAura combat can interfere with ShulkerRestock.")
            .defaultValue(6.0)
            .min(1.0)
            .sliderRange(1.0, 10.0)
            .visible(pauseForKillAura::get)
            .build()
    );

    private final Setting<Integer> combatSettleTicks = sgTiming.add(new IntSetting.Builder()
            .name("combat-settle-ticks")
            .description("Ticks to keep ShulkerRestock paused after nearby KillAura combat stops, so rotations and packets settle first.")
            .defaultValue(30)
            .min(0)
            .sliderRange(0, 100)
            .visible(pauseForKillAura::get)
            .build()
    );

    private final Setting<Boolean> waitForPickupBeforeDisable = sgTiming.add(new BoolSetting.Builder()
            .name("wait-for-pickup-before-disable")
            .description("Before the module turns itself off (whether it finished or hit an error), keep running " +
                    "until any dropped shulker box has been collected, so shulkers are never left on the ground.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> finalPickupTimeout = sgTiming.add(new IntSetting.Builder()
            .name("final-pickup-timeout")
            .description("Max ticks to keep trying to recover a dropped shulker before disabling anyway (20 ticks = 1s). " +
                    "Prevents the module hanging forever if the item is truly unreachable.")
            .defaultValue(400)
            .min(40)
            .sliderRange(40, 1200)
            .visible(waitForPickupBeforeDisable::get)
            .build()
    );





    private enum State {
        IDLE,
        STOP_BARITONE,
        MOVE_TO_HOTBAR,
        PLACE,
        AIR_SWAP_IN,
        AIR_INTERACT,
        AIR_SWAP_BACK,
        CONFIRM_PLACE,
        OPEN,
        CONFIRM_OPEN,
        EXTRACT,
        WAIT_CLOSE,
        MINE,
        WAIT_PICKUP,
        GOTO_DROPPED,
        RETURN,
        CONFIRM_RETURN,
        FINAL_PICKUP,
        DONE
    }

    private State state = State.IDLE;

    private int delayTimer = 0;
    private int attemptTimer = 0;
    private int retries = 0;

    private int shulkerHotbarSlot = -1;
    private BlockPos placedPos = null;
    private int targetCountBefore = 0;
    private int emptySlotsBeforePlace = 0;
    private int shulkerCountBeforePlace = 0;
    private Item shulkerItemPlaced = null;
    private String placedShulkerBlockId = null;
    private int itemsToExtract = 0;
    private int extractTargetCount = 0;
    private boolean triedSupportPlace = false;
    private boolean triedAirPlace = false;
    private boolean placedInAir = false;
    private int     extractCountAtClick = -1;
    private int     extractStuckClicks  = 0;


    private BlockHitResult airHit = null;
    private boolean        offhandSwapped = false;




    private int     combatPauseTicks = 0;
    private boolean combatPauseActive = false;















    private int     baritoneCmdCooldown  = 0;
    private boolean awaitingPathTeardown = false;
    private int     teardownWaitTicks    = 0;


    private static final int BARITONE_CMD_GAP    = 4;

    private static final int TEARDOWN_WAIT_LIMIT = 40;

    public ShulkerRestock() {
        super(Hybridious.CATEGORY, "ShulkerRestock",
                "Pulls a chosen item out of shulker boxes you carry: places the box on a reachable surface (air-place " +
                        "fallback), opens it, grabs stacks, breaks the box itself, and returns the empty shulker. " +
                        "Self-corrects at every step. Built for Folia/Paper + Grim.");
    }

    @Override
    public void onActivate() {
        reset();



        state = State.STOP_BARITONE;
        baritoneStop();
    }

    @Override
    public void onDeactivate() {
        if (state == State.MINE || state == State.WAIT_PICKUP || state == State.GOTO_DROPPED || state == State.FINAL_PICKUP) baritoneStop();


        if (offhandSwapped) {
            sendOffhandSwap();
            offhandSwapped = false;
        }
        reset();
    }

    private void reset() {
        state = State.IDLE;
        delayTimer = 0;
        attemptTimer = 0;
        retries = 0;
        shulkerHotbarSlot = -1;
        placedPos = null;
        targetCountBefore = 0;
        emptySlotsBeforePlace = 0;
        shulkerCountBeforePlace = 0;
        shulkerItemPlaced = null;
        placedShulkerBlockId = null;
        itemsToExtract = 0;
        extractTargetCount = 0;
        triedSupportPlace = false;
        triedAirPlace = false;
        placedInAir = false;
        extractCountAtClick = -1;
        extractStuckClicks = 0;
        airHit = null;
        offhandSwapped = false;
        combatPauseTicks = 0;
        combatPauseActive = false;
        baritoneCmdCooldown  = 0;
        awaitingPathTeardown = false;
        teardownWaitTicks    = 0;
    }


    private void enter(State next) {
        state = next;
        attemptTimer = 0;
        retries = 0;
    }


    private void retry(State step) {
        state = step;
        attemptTimer = 0;
    }


    private boolean failAttempt(String whatFailed) {
        retries++;
        attemptTimer = 0;
        if (retries >= maxRetries.get()) {
            error(whatFailed + " - giving up after " + retries + " tries.");
            return true;
        }
        warning(whatFailed + " - retrying (" + retries + "/" + maxRetries.get() + ").");
        return false;
    }

    private void abort() {
        baritoneStop();

        if (offhandSwapped) {
            sendOffhandSwap();
            offhandSwapped = false;
        }



        if (waitForPickupBeforeDisable.get() && hasUnfinishedShulkerRecovery()) {
            warning("Aborting, but shulker recovery isn't complete - finishing before disabling.");
            enter(State.FINAL_PICKUP);
            return;
        }
        toggle();
    }





    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;


        if (baritoneCmdCooldown > 0) baritoneCmdCooldown--;
        if (awaitingPathTeardown) {
            if (!baritonePathing()) {
                awaitingPathTeardown = false;
                teardownWaitTicks    = 0;
            } else if (++teardownWaitTicks >= TEARDOWN_WAIT_LIMIT) {
                awaitingPathTeardown = false;
                teardownWaitTicks    = 0;
            }
        }




        if (tickCombatPause()) {
            return;
        }

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        attemptTimer++;

        switch (state) {
            case STOP_BARITONE  -> stepStopBaritone();
            case MOVE_TO_HOTBAR -> stepMoveToHotbar();
            case PLACE          -> stepPlace();
            case AIR_SWAP_IN    -> stepAirSwapIn();
            case AIR_INTERACT   -> stepAirInteract();
            case AIR_SWAP_BACK  -> stepAirSwapBack();
            case CONFIRM_PLACE  -> stepConfirmPlace();
            case OPEN           -> stepOpen();
            case CONFIRM_OPEN   -> stepConfirmOpen();
            case EXTRACT        -> stepExtract();
            case WAIT_CLOSE     -> stepWaitClose();
            case MINE           -> stepMine();
            case WAIT_PICKUP    -> stepWaitPickup();
            case GOTO_DROPPED   -> stepGotoDropped();
            case RETURN         -> stepReturn();
            case CONFIRM_RETURN -> stepConfirmReturn();
            case FINAL_PICKUP   -> stepFinalPickup();
            case DONE           -> finish();
            default             -> {}
        }
    }


    private void finish() {
        if (waitForPickupBeforeDisable.get() && hasUnfinishedShulkerRecovery()) {
            warning("Finishing, but shulker recovery isn't complete - waiting before disabling.");
            enter(State.FINAL_PICKUP);
            return;
        }
        info("Finished.");
        toggle();
    }


    private boolean hasUnfinishedShulkerRecovery() {
        if (placedShulkerPresent()) return true;
        if (droppedShulkerNearby()) return true;




        return false;
    }



    private void stepStopBaritone() {
        if (baritoneCmdBusy()) {
            return;
        }

        enter(State.MOVE_TO_HOTBAR);
    }


    private void stepMoveToHotbar() {
        Item target = targetItem.get();

        int invShulkerSlot = findShulkerContaining(target);
        if (invShulkerSlot == -1) {
            if (failAttempt("No shulker holding " + nameOf(target) + " found")) { abort(); }
            delayTimer = actionDelay.get();
            return;
        }

        shulkerItemPlaced = mc.player.getInventory().getStack(invShulkerSlot).getItem();



        placedShulkerBlockId = resolveShulkerBlockIdFromItem(shulkerItemPlaced);
        if (placedShulkerBlockId != null) {
            info("Placing " + nameOf(shulkerItemPlaced) + " (mine target: " + placedShulkerBlockId + ").");
        }

        if (invShulkerSlot < 9) {
            shulkerHotbarSlot = invShulkerSlot;
            enter(State.PLACE);
            return;
        }

        int freeHotbar = firstFreeHotbarSlot();
        int destHotbar = (freeHotbar != -1) ? freeHotbar : mc.player.getInventory().selectedSlot;

        InvUtils.move().from(invShulkerSlot).to(destHotbar);
        shulkerHotbarSlot = destHotbar;
        delayTimer = actionDelay.get();
        enter(State.PLACE);
    }


    private void stepPlace() {
        if (shulkerHotbarSlot < 0 || shulkerHotbarSlot > 8) {
            error("Lost track of the shulker's hotbar slot.");
            abort();
            return;
        }

        InvUtils.swap(shulkerHotbarSlot, false);
        if (!isShulkerBox(mc.player.getMainHandStack())) {
            if (failAttempt("Hand isn't holding the shulker")) { abort(); }
            delayTimer = actionDelay.get();
            return;
        }

        targetCountBefore = countItemInInventory(target());





        emptySlotsBeforePlace = countEmptyInventorySlots() + 1;




        shulkerCountBeforePlace = Math.max(0, countAnyShulkerInInventory() - 1);

        if (preferReachable.get()) {
            if (!triedSupportPlace) {
                if (trySupportPlace()) return;
            }
            if (airPlace.get() && !triedAirPlace) {
                if (beginAirPlace()) return;
            }
            if (failAttempt("No reachable spot to place the shulker")) { abort(); return; }
            triedSupportPlace = false;
            triedAirPlace = false;
            delayTimer = actionDelay.get();
            return;
        }

        if (airPlace.get() && !triedAirPlace) {
            if (beginAirPlace()) return;
        }
        if (!triedSupportPlace) {
            if (trySupportPlace()) return;
        }
        if (failAttempt("No valid spot to place the shulker")) { abort(); return; }
        triedAirPlace = false;
        triedSupportPlace = false;
        delayTimer = actionDelay.get();
    }

    private boolean trySupportPlace() {
        BlockHitResult hit = findPlacement();
        if (hit == null) return false;

        triedSupportPlace = true;
        placedInAir = false;
        placedPos = hit.getBlockPos().offset(hit.getSide());
        Vec3d aim = hit.getPos();
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), rotationPriority.get(), () -> {
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        });
        delayTimer = actionDelay.get();
        enter(State.CONFIRM_PLACE);
        return true;
    }


    private boolean beginAirPlace() {
        BlockHitResult hit = findAirPlacement();
        if (hit == null) return false;

        triedAirPlace = true;
        placedInAir = true;
        placedPos = hit.getBlockPos();
        airHit = hit;


        sendOffhandSwap();
        offhandSwapped = true;
        Vec3d aim = hit.getPos();
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), rotationPriority.get());

        delayTimer = Math.max(1, actionDelay.get());
        enter(State.AIR_SWAP_IN);
        return true;
    }


    private void stepAirSwapIn() {
        if (airHit == null) {

            if (offhandSwapped) { sendOffhandSwap(); offhandSwapped = false; }
            retry(State.PLACE);
            return;
        }
        Vec3d aim = airHit.getPos();
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), rotationPriority.get());
        delayTimer = Math.max(1, actionDelay.get());
        enter(State.AIR_INTERACT);
    }


    private void stepAirInteract() {
        if (airHit == null) {
            if (offhandSwapped) { sendOffhandSwap(); offhandSwapped = false; }
            retry(State.PLACE);
            return;
        }
        final BlockHitResult hit = airHit;
        Vec3d aim = hit.getPos();
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), rotationPriority.get(), () -> {
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND, hit);
            if (result.isAccepted()) mc.player.swingHand(Hand.OFF_HAND);
        });
        delayTimer = Math.max(1, actionDelay.get());
        enter(State.AIR_SWAP_BACK);
    }



    private void stepAirSwapBack() {
        if (placedShulkerPresent()) {
            if (offhandSwapped) { sendOffhandSwap(); offhandSwapped = false; }
            airHit = null;
            enter(State.OPEN);
            return;
        }

        if (attemptTimer >= stepTimeout.get()) {

            if (offhandSwapped) { sendOffhandSwap(); offhandSwapped = false; }
            airHit = null;
            if (failAttempt("Air-place not confirmed")) { abort(); return; }

            if (preferReachable.get()) {
                triedSupportPlace = false;
            }
            triedAirPlace = false;
            retry(State.PLACE);
        }
    }

    private void stepConfirmPlace() {
        if (placedShulkerPresent()) {
            enter(State.OPEN);
            return;
        }

        if (attemptTimer >= stepTimeout.get()) {
            if (failAttempt("Placement not confirmed")) { abort(); return; }
            if (preferReachable.get()) {
                if (triedSupportPlace && !triedAirPlace && airPlace.get()) {

                } else {
                    triedSupportPlace = false;
                    triedAirPlace = false;
                }
            } else {
                if (triedAirPlace && !triedSupportPlace) {

                } else if (triedSupportPlace && airPlace.get()) {
                    triedAirPlace = false;
                }
            }
            retry(State.PLACE);
        }
    }

    private void stepOpen() {
        if (!placedShulkerPresent()) {
            if (failAttempt("Placed shulker vanished before opening")) { abort(); return; }
            triedSupportPlace = false;
            triedAirPlace = false;
            placedInAir = false;
            retry(State.PLACE);
            return;
        }

        if (isContainerScreenOpen()) {
            enter(State.CONFIRM_OPEN);
            return;
        }

        Direction side = bestVisibleSide(placedPos);
        Vec3d facePoint = faceHitPoint(placedPos, side);
        BlockHitResult openHit = new BlockHitResult(facePoint, side, placedPos, false);

        Rotations.rotate(Rotations.getYaw(facePoint), Rotations.getPitch(facePoint), rotationPriority.get(), () -> {
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, openHit);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        });

        delayTimer = actionDelay.get() + 1;
        enter(State.CONFIRM_OPEN);
    }

    private void stepConfirmOpen() {
        if (isContainerScreenOpen()) {
            int targetStacks = computeTargetStacks();
            if (autoExtract.get()) {




                int maxStack = Math.max(1, new ItemStack(target()).getMaxCount());
                int wanted = targetStacks * maxStack;
                itemsToExtract = wanted;
                extractTargetCount = targetCountBefore + wanted;
                extractCountAtClick = -1;
                extractStuckClicks = 0;
                info("Opened shulker - auto-extracting up to " + targetStacks + " stack(s) ("
                        + wanted + " items) of " + nameOf(target()) + ".");
                enter(State.EXTRACT);
            } else {
                info("Opened shulker - grab about " + targetStacks + " stack(s) of "
                        + nameOf(target()) + ", then close it.");
                enter(State.WAIT_CLOSE);
            }
            return;
        }

        if (attemptTimer >= stepTimeout.get()) {
            if (failAttempt("Open not confirmed")) {
                if (placedShulkerPresent() && autoMine.get()) {
                    warning("Couldn't open - recovering the box anyway.");
                    enter(recoverState());
                } else {
                    abort();
                }
                return;
            }
            retry(State.OPEN);
        }
    }

    private void stepExtract() {
        if (!isContainerScreenOpen()) {
            int gained = countItemInInventory(target()) - targetCountBefore;
            if (gained <= 0 && placedShulkerPresent()) {
                if (failAttempt("Box closed before extracting")) { enter(recoverState()); return; }
                retry(State.OPEN);
                return;
            }
            enter(recoverState());
            return;
        }

        int current = countItemInInventory(target());


        if (current >= extractTargetCount || firstFreeAnySlot() == -1) {
            int gained = current - targetCountBefore;
            if (gained > 0) info("Restocked " + gained + "x " + nameOf(target()) + ".");
            mc.player.closeHandledScreen();
            delayTimer = actionDelay.get();
            enter(recoverState());
            return;
        }







        if (extractCountAtClick >= 0) {
            if (current <= extractCountAtClick) {

                if (attemptTimer >= stepTimeout.get()) {
                    extractStuckClicks++;
                    extractCountAtClick = -1;
                    if (extractStuckClicks >= maxRetries.get()) {



                        int gained = current - targetCountBefore;
                        if (gained > 0) info("Restocked " + gained + "x " + nameOf(target())
                                + " (stopped early - no further progress).");
                        else warning("Couldn't pull " + nameOf(target()) + " from the shulker.");
                        mc.player.closeHandledScreen();
                        delayTimer = actionDelay.get();
                        enter(recoverState());
                    }
                }
                return;
            }

            extractCountAtClick = -1;
            extractStuckClicks = 0;
        }


        int containerSlots = mc.player.currentScreenHandler.slots.size() - 36;
        int slot = -1;
        for (int i = 0; i < containerSlots; i++) {
            if (mc.player.currentScreenHandler.getSlot(i).getStack().isOf(target())) {
                slot = i;
                break;
            }
        }

        if (slot == -1) {
            int gained = current - targetCountBefore;
            if (gained > 0) info("Restocked " + gained + "x " + nameOf(target()) + ".");
            else info("No " + nameOf(target()) + " left in the shulker.");
            mc.player.closeHandledScreen();
            delayTimer = actionDelay.get();
            enter(recoverState());
            return;
        }

        extractCountAtClick = current;
        InvUtils.shiftClick().slotId(slot);
        attemptTimer = 0;
        delayTimer = actionDelay.get();
    }

    private void stepWaitClose() {
        if (isContainerScreenOpen()) return;

        int gained = countItemInInventory(target()) - targetCountBefore;
        if (gained > 0) info("Restocked " + gained + "x " + nameOf(target()) + ".");

        enter(recoverState());
    }

    private State recoverState() {
        return autoMine.get() ? State.MINE : State.DONE;
    }






    private void stepMine() {
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            delayTimer = actionDelay.get();
            return;
        }

        if (!placedShulkerPresent() && countAnyShulkerInInventory() > shulkerCountBeforePlace) {
            enter(returnShulker.get() ? State.RETURN : State.DONE);
            return;
        }



        if (baritoneCmdBusy()) {
            return;
        }

        if (baritoneCmd("mine " + targetShulkerBlockId())) {
            info("Mining the shulker with Baritone...");
            enter(State.WAIT_PICKUP);
        }
    }





    private void stepWaitPickup() {
        boolean blockGone = !placedShulkerPresent();



        boolean haveShulker = countAnyShulkerInInventory() > shulkerCountBeforePlace;



        if (blockGone && haveShulker) {
            baritoneStop();
            info("Picked the shulker back up.");
            enter(returnShulker.get() ? State.RETURN : State.DONE);
            return;
        }





        if (blockGone && !haveShulker) {
            ItemEntity dropped = nearestDroppedShulker();
            if (dropped != null && !baritoneCmdBusy() && !baritonePathing()) {
                info("Shulker broke but wasn't collected - moving to the dropped item to pick it up.");
                retry(State.GOTO_DROPPED);
                return;
            }
        }

        if (attemptTimer >= pickupTimeout.get()) {
            baritoneStop();
            if (blockGone && haveShulker) {
                enter(returnShulker.get() ? State.RETURN : State.DONE);
                return;
            }

            ItemEntity dropped = nearestDroppedShulker();
            if (blockGone && dropped != null) {
                if (failAttempt("Broke the box but didn't recover the dropped item")) {
                    warning("A dropped shulker is still nearby, but pickup recovery ran out of retries.");
                    abort();
                    return;
                }
                retry(State.GOTO_DROPPED);
                return;
            }

            if (failAttempt(blockGone ? "Broke the box but didn't recover the item" : "Couldn't break the shulker")) {
                abort();
                return;
            }


            retry(State.MINE);
        }
    }



    private void stepGotoDropped() {
        boolean haveShulker = countAnyShulkerInInventory() > shulkerCountBeforePlace;
        if (haveShulker) {
            baritoneStop();
            info("Picked the shulker back up.");
            enter(returnShulker.get() ? State.RETURN : State.DONE);
            return;
        }

        ItemEntity dropped = nearestDroppedShulker();
        if (dropped == null) {


            retry(State.WAIT_PICKUP);
            return;
        }

        if (baritoneCmdBusy()) {
            return;
        }

        BlockPos pos = dropped.getBlockPos();
        if (baritoneCmd("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
            info("Moving to dropped shulker item with Baritone...");
            retry(State.WAIT_PICKUP);
        }
    }

    private void stepReturn() {


        int hotbarWithShulker = findShulkerInHotbar(null);
        if (hotbarWithShulker == -1) {
            if (countAnyShulkerInInventory() > shulkerCountBeforePlace) {

                enter(State.DONE);
            } else {
                if (failAttempt("Recovered shulker not found to stash")) { abort(); return; }
                delayTimer = actionDelay.get();
            }
            return;
        }

        int freeMain = firstFreeMainInventoryIndex();
        if (freeMain == -1) {
            info("No free upper-inventory slot to stash the shulker; leaving it in the hotbar.");
            enter(State.DONE);
            return;
        }

        InvUtils.move().from(hotbarWithShulker).to(freeMain);
        delayTimer = actionDelay.get();
        enter(State.CONFIRM_RETURN);
    }

    private void stepConfirmReturn() {
        if (findShulkerInHotbar(null) == -1) {
            enter(State.DONE);
            return;
        }
        if (attemptTimer >= stepTimeout.get()) {
            if (failAttempt("Stashing the shulker didn't take")) {
                info("Leaving the shulker in your hotbar.");
                enter(State.DONE);
                return;
            }
            retry(State.RETURN);
        }
    }










    private void stepFinalPickup() {
        boolean blockStillPlaced = placedShulkerPresent();
        boolean recovered = countAnyShulkerInInventory() > shulkerCountBeforePlace;
        ItemEntity dropped = nearestDroppedShulker();



        if (!blockStillPlaced && dropped == null && (recovered || placedPos == null)) {
            baritoneStop();
            if (recovered) info("Shulker recovered - disabling.");
            else info("Nothing left to recover - disabling.");
            toggle();
            return;
        }


        if (attemptTimer >= finalPickupTimeout.get()) {
            baritoneStop();
            String why = blockStillPlaced ? "the placed shulker is still standing"
                    : dropped != null  ? "the dropped shulker couldn't be reached"
                    :                    "the shulker couldn't be recovered";
            warning("Gave up after the final-pickup timeout - " + why + ". Disabling anyway.");
            toggle();
            return;
        }


        if (baritoneCmdBusy() || baritonePathing()) {
            return;
        }


        if (blockStillPlaced) {

            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
                delayTimer = actionDelay.get();
                return;
            }
            if (baritoneCmd("mine " + targetShulkerBlockId())) {
                info("Finishing the mine before disabling...");
            }
            return;
        }


        if (dropped != null) {
            BlockPos pos = dropped.getBlockPos();
            if (baritoneCmd("goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
                info("Recovering dropped shulker before disabling...");
            }
        }
    }





    private boolean tickCombatPause() {
        if (!pauseForKillAura.get()) {
            combatPauseTicks = 0;
            combatPauseActive = false;
            return false;
        }

        if (!stateCanBeInterruptedByCombat()) {
            combatPauseTicks = 0;
            combatPauseActive = false;
            return false;
        }

        boolean combatNow = killAuraActive() && nearbyLivingEntityForKillAura();
        if (combatNow) combatPauseTicks = Math.max(combatPauseTicks, combatSettleTicks.get());
        else if (combatPauseTicks > 0) combatPauseTicks--;

        if (combatNow || combatPauseTicks > 0) {
            attemptTimer = 0;
            extractCountAtClick = -1;
            extractStuckClicks = 0;




            if (!combatPauseActive && (state == State.MINE || state == State.WAIT_PICKUP || state == State.GOTO_DROPPED || state == State.FINAL_PICKUP)) {
                if (!baritoneCmdBusy() && baritonePathing()) baritoneStop();
            }

            if (!combatPauseActive) {
                warning("Paused for nearby KillAura combat; holding ShulkerRestock state " + state + ".");
            }
            combatPauseActive = true;
            return true;
        }

        if (combatPauseActive) {
            combatPauseActive = false;
            delayTimer = Math.max(delayTimer, actionDelay.get());
            info("Combat clear - resuming ShulkerRestock.");
        }
        return false;
    }

    private boolean stateCanBeInterruptedByCombat() {
        return switch (state) {
            case MOVE_TO_HOTBAR, PLACE, AIR_SWAP_IN, AIR_INTERACT, AIR_SWAP_BACK, CONFIRM_PLACE,
                 OPEN, CONFIRM_OPEN, EXTRACT, WAIT_CLOSE, MINE, WAIT_PICKUP, GOTO_DROPPED, RETURN, CONFIRM_RETURN, FINAL_PICKUP -> true;
            default -> false;
        };
    }

    private boolean killAuraActive() {
        try {
            KillAura killAura = Modules.get().get(KillAura.class);
            return killAura != null && killAura.isActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nearbyLivingEntityForKillAura() {
        double range = combatPauseRange.get();
        List<LivingEntity> entities = mc.world.getEntitiesByClass(
                LivingEntity.class,
                mc.player.getBoundingBox().expand(range),
                e -> e != mc.player
                        && !(e instanceof PlayerEntity)
                        && e.isAlive()
                        && !e.isSpectator()
                        && mc.player.squaredDistanceTo(e) <= range * range
        );
        return !entities.isEmpty();
    }





    private Item target() {
        return targetItem.get();
    }

    private int findShulkerContaining(Item target) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isShulkerBox(stack)) continue;
            if (shulkerContains(stack, target)) return i;
        }
        return -1;
    }

    private boolean shulkerContains(ItemStack shulker, Item target) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : container.iterateNonEmpty()) {
            if (inner.isOf(target)) return true;
        }
        return false;
    }

    private boolean isShulkerBox(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private int computeTargetStacks() {
        if (!fillHalf.get()) return stacksToRestock.get();


        int usable = emptySlotsBeforePlace > 0 ? emptySlotsBeforePlace : countEmptyInventorySlots();
        return Math.max(1, usable / 2);
    }

    private int countEmptyInventorySlots() {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
        }
        return empty;
    }

    private int countItemInInventory(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }


    private int countAnyShulkerInInventory() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isShulkerBox(stack)) count += stack.getCount();
        }
        return count;
    }

    private int firstFreeHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private int firstFreeMainInventoryIndex() {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private int firstFreeAnySlot() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private int findShulkerInHotbar(Item shulkerType) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (shulkerType != null ? stack.isOf(shulkerType) : isShulkerBox(stack)) return i;
        }
        return -1;
    }





    private boolean placedShulkerPresent() {
        return placedPos != null
                && mc.world.getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock;
    }


    private BlockHitResult findPlacement() {
        int r = placeRange.get();
        BlockPos origin = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange();

        List<BlockPos> candidates = new ArrayList<>();


        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    candidates.add(origin.add(dx, dy, dz));
                }
            }
        }


        candidates.sort(Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(eye)));

        for (BlockPos cell : candidates) {

            if (!mc.world.getBlockState(cell).isReplaceable()) continue;
            if (positionIntersectsPlayer(cell)) continue;



            for (Direction normal : Direction.values()) {
                BlockPos support = cell.offset(normal);
                if (!mc.world.getBlockState(support).isSolidBlock(mc.world, support)) continue;



                Direction clickedFace = normal.getOpposite();




                if (!canShulkerOpenAt(cell, clickedFace)) continue;

                Vec3d hitVec = Vec3d.ofCenter(support).add(
                        clickedFace.getOffsetX() * 0.5,
                        clickedFace.getOffsetY() * 0.5,
                        clickedFace.getOffsetZ() * 0.5);


                if (eye.distanceTo(hitVec) > reach + 0.3) continue;

                return new BlockHitResult(hitVec, clickedFace, support, false);
            }
        }
        return null;
    }


    private BlockHitResult tryFace(BlockPos support) {
        if (!mc.world.getBlockState(support).isSolidBlock(mc.world, support)) return null;

        BlockPos above = support.up();
        if (!mc.world.getBlockState(above).isReplaceable()) return null;
        if (positionIntersectsPlayer(above)) return null;

        Vec3d hitVec = Vec3d.ofCenter(support).add(0, 0.5, 0);
        if (mc.player.getEyePos().distanceTo(hitVec) > mc.player.getBlockInteractionRange() + 0.5) return null;

        return new BlockHitResult(hitVec, Direction.UP, support, false);
    }


    private BlockHitResult findAirPlacement() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        double base = placeDistance.get();
        double reach = mc.player.getBlockInteractionRange();



        double[] offsets = { 0.0, -0.5, -1.0, -1.5, 0.5, -2.0 };
        for (double off : offsets) {
            double dist = base + off;
            if (dist < 1.2) continue;
            if (dist > reach + 0.5) continue;

            Vec3d point = eye.add(look.multiply(dist));
            BlockPos pos = BlockPos.ofFloored(point);

            if (!mc.world.getBlockState(pos).isReplaceable()) continue;
            if (positionIntersectsPlayer(pos)) continue;
            if (positionIntersectsEntity(pos)) continue;


            Direction side = bestVisibleSide(pos);






            if (!canShulkerOpenAt(pos, side)) continue;

            Vec3d hitVec = faceHitPoint(pos, side);
            if (eye.distanceTo(hitVec) > reach + 0.3) continue;

            return new BlockHitResult(hitVec, side, pos, false);
        }
        return null;
    }


    private boolean positionIntersectsPlayer(BlockPos pos) {
        Box block = new Box(pos);
        return mc.player.getBoundingBox().intersects(block);
    }


    private boolean canShulkerOpenAt(BlockPos cell, Direction clickedFace) {
        BlockPos lidCell = cell.offset(clickedFace);
        return mc.world.getBlockState(lidCell).isReplaceable();
    }


    private boolean positionIntersectsEntity(BlockPos pos) {
        Box block = new Box(pos);
        List<LivingEntity> entities = mc.world.getEntitiesByClass(LivingEntity.class, block, e -> e.isAlive());
        return !entities.isEmpty();
    }

    private Direction bestVisibleSide(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = eyes.x - center.x;
        double dy = eyes.y - center.y;
        double dz = eyes.z - center.z;

        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        if (az >= ay) return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        return dy >= 0 ? Direction.UP : Direction.DOWN;
    }

    private Vec3d faceHitPoint(BlockPos pos, Direction side) {
        Vec3d c = Vec3d.ofCenter(pos);
        return c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
    }

    private boolean isContainerScreenOpen() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) return true;
        return mc.player.currentScreenHandler instanceof GenericContainerScreenHandler g
                && g.getRows() == 3
                && placedShulkerPresent();
    }

    private boolean droppedShulkerNearby() {
        return nearestDroppedShulker() != null;
    }

    private ItemEntity nearestDroppedShulker() {
        List<ItemEntity> items = mc.world.getEntitiesByClass(
                ItemEntity.class,
                mc.player.getBoundingBox().expand(12),
                e -> isShulkerBox(e.getStack())
        );

        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            double distance = mc.player.squaredDistanceTo(item);
            if (distance < nearestDistance) {
                nearest = item;
                nearestDistance = distance;
            }
        }
        return nearest;
    }






    private void sendOffhandSwap() {
        if (mc.player == null || mc.player.networkHandler == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }






    private boolean baritoneCmd(String command) {
        if (mc.getNetworkHandler() == null) return false;
        if (baritoneCmdCooldown > 0) return false;
        String prefix = prefixOverride.get();
        if (prefix == null || prefix.isEmpty()) {
            prefix = BaritoneUtils.IS_AVAILABLE ? BaritoneUtils.getPrefix() : "#";
        }
        ChatUtils.sendPlayerMsg(prefix + command);
        baritoneCmdCooldown = BARITONE_CMD_GAP;
        return true;
    }


    private void baritoneStop() {
        if (mc.getNetworkHandler() == null) return;
        String prefix = prefixOverride.get();
        if (prefix == null || prefix.isEmpty()) {
            prefix = BaritoneUtils.IS_AVAILABLE ? BaritoneUtils.getPrefix() : "#";
        }
        ChatUtils.sendPlayerMsg(prefix + "stop");
        baritoneCmdCooldown  = BARITONE_CMD_GAP;
        awaitingPathTeardown = true;
        teardownWaitTicks    = 0;
    }


    private boolean baritoneCmdBusy() {
        return baritoneCmdCooldown > 0 || awaitingPathTeardown;
    }


    private boolean baritonePathing() {
        try {
            return baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().isPathing();
        } catch (Throwable ignored) {
            return false;
        }
    }





    private String nameOf(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }


    private String targetShulkerBlockId() {

        if (placedShulkerBlockId != null) return placedShulkerBlockId;


        try {
            if (placedPos != null) {
                Block block = mc.world.getBlockState(placedPos).getBlock();
                if (block instanceof ShulkerBoxBlock) {
                    return Registries.BLOCK.getId(block).toString();
                }
            }
            if (shulkerItemPlaced instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                return Registries.BLOCK.getId(bi.getBlock()).toString();
            }
        } catch (Throwable ignored) {}



        return ALL_SHULKER_BLOCK_IDS;
    }


    private String resolveShulkerBlockIdFromItem(Item item) {
        try {
            if (item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                return Registries.BLOCK.getId(bi.getBlock()).toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }


    private static final String ALL_SHULKER_BLOCK_IDS =
            "minecraft:shulker_box " +
                    "minecraft:white_shulker_box minecraft:orange_shulker_box " +
                    "minecraft:magenta_shulker_box minecraft:light_blue_shulker_box " +
                    "minecraft:yellow_shulker_box minecraft:lime_shulker_box " +
                    "minecraft:pink_shulker_box minecraft:gray_shulker_box " +
                    "minecraft:light_gray_shulker_box minecraft:cyan_shulker_box " +
                    "minecraft:purple_shulker_box minecraft:blue_shulker_box " +
                    "minecraft:brown_shulker_box minecraft:green_shulker_box " +
                    "minecraft:red_shulker_box minecraft:black_shulker_box";

    private void info(String msg) {
        ChatUtils.info("ShulkerRestock: " + msg);
    }

    private void warning(String msg) {
        ChatUtils.warning("ShulkerRestock: " + msg);
    }

    private void error(String msg) {
        ChatUtils.error("ShulkerRestock: " + msg);
    }
}

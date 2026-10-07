package dev.hybridious.modules;

import dev.hybridious.Hybridious;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

public class DeathExplore extends Module {
    private boolean isDead = false;

    public DeathExplore() {
        super(Hybridious.CATEGORY, "DeathExplore", "Allows you to continue exploring after death.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        if (mc.player.isDeadOrDying()) {
            mc.player.setHealth(20.0f);
            mc.setScreen(null);
            isDead = true;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        isDead = false;
    }

    @Override
    public void onDeactivate() {
        if (isDead && mc.player != null) {
            mc.player.setHealth(0.0f);
            mc.setScreen(new DeathScreen(Component.literal("You died!"), mc.level.getLevelData().isHardcore(), mc.player));
            isDead = false;
        }
    }
}

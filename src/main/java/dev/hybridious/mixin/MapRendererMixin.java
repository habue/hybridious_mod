package dev.hybridious.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hybridious.modules.MapFilterModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

@Mixin(value = MapRenderer.class, priority = 1100)
public class MapRendererMixin {
    @Unique
    private final Map<MapRenderState, Integer> hybridious$mapIds = new WeakHashMap<>();

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void rememberMapId(MapId id, MapItemSavedData data, MapRenderState state, CallbackInfo ci) {
        hybridious$mapIds.put(state, id.id());
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void filterMap(MapRenderState state, PoseStack matrices, SubmitNodeCollector collector,
                           boolean hidePlayerIcons, int light, CallbackInfo ci) {
        MapFilterModule module = Modules.get().get(MapFilterModule.class);
        Integer id = hybridious$mapIds.get(state);
        if (module != null && module.isActive() && id != null && !module.shouldRenderMap(id)) ci.cancel();
    }
}
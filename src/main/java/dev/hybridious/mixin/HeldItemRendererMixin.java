package dev.hybridious.mixin;

import dev.hybridious.modules.MapFilterModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ItemInHandRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemInHandRenderer.class, priority = 1100)
public class HeldItemRendererMixin {

    @Inject(
            method = "renderMap",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRenderFirstPersonMap(PoseStack matrices, SubmitNodeCollector vertexConsumers,
                                        int swingProgress, ItemStack map, CallbackInfo ci) {
        try {
            MapFilterModule module = Modules.get().get(MapFilterModule.class);

            if (module != null && module.isActive()) {
                MapId mapId = map.get(DataComponents.MAP_ID);

                if (mapId != null) {
                    int id = mapId.id();

                    if (!module.shouldRenderMap(id)) {
                        ci.cancel();
                    }
                }
            }
        } catch (Exception e) {
            // Fail-safe: allow rendering on error
        }
    }
}

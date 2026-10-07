package dev.hybridious.mixin;

import dev.hybridious.modules.MapFilterModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemFrame.class)
public class ItemFrameMapFilterMixin {

    @Inject(method = "getItem", at = @At("RETURN"), cancellable = true)
    private void filterMapInFrame(CallbackInfoReturnable<ItemStack> cir) {
        MapFilterModule module = Modules.get().get(MapFilterModule.class);
        if (module == null || !module.isActive()) return;

        ItemStack stack = cir.getReturnValue();
        if (!stack.is(Items.FILLED_MAP)) return;

        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        int id = mapId.id();

        if (!module.shouldRenderMap(id)) {
            System.out.println("[MapFilter] [ItemFrame] BLOCKING map " + id + " in item frame");
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
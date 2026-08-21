package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.server.SimulationCommandBlocks;
import ml.pypals.simulatica.simulation.server.SimulationMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(Player.class)
public class PlayerOpenMenuMixin {

    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void simulatica$openSimulatedMenu(@Nullable MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (provider == null || !SimulationMenus.isInteracting()) return;
        if ((Object) this != Minecraft.getInstance().player) return;

        if (SimulationMenus.openFor(provider)) {
            cir.setReturnValue(OptionalInt.of(SimulationMenus.containerId()));
        }
    }

    @Inject(method = "openCommandBlock", at = @At("HEAD"), cancellable = true)
    private void simulatica$openSimulatedCommandBlock(CommandBlockEntity blockEntity, CallbackInfo ci) {
        if (!SimulationMenus.isInteracting()) return;
        if ((Object) this != Minecraft.getInstance().player) return;

        if (SimulationCommandBlocks.open(blockEntity)) {
            ci.cancel();
        }
    }
}

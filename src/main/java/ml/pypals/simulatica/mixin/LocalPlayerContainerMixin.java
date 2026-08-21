package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.server.SimulationMenus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(LocalPlayer.class)
public abstract class LocalPlayerContainerMixin {

    @Inject(method = "closeContainer", at = @At("HEAD"), cancellable = true)
    private void simulatica$closeSimulatedMenu(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!SimulationMenus.isSimulated(((Player) self).containerMenu)) return;

        SimulationMenus.close();
        self.clientSideCloseContainer();
        ci.cancel();
    }
}

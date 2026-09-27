package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.server.SimulationMenus;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - ClickType → ContainerInput、handleInventoryMouseClick → handleContainerInput
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMenuMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void simulatica$clickLocally(int containerId, int slotId, int button, ContainerInput input,
                                         Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!SimulationMenus.isSimulated(menu) || containerId != menu.containerId) return;

        menu.clicked(slotId, button, input, player);
        ci.cancel();
    }

    @Inject(method = "handleInventoryButtonClick", at = @At("HEAD"), cancellable = true)
    private void simulatica$buttonLocally(int containerId, int buttonId, CallbackInfo ci) {
        Player player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;

        AbstractContainerMenu menu = player.containerMenu;
        if (!SimulationMenus.isSimulated(menu) || containerId != menu.containerId) return;

        menu.clickMenuButton(player, buttonId);
        ci.cancel();
    }
}

package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.server.SimulationMenus;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMenuMixin {

    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
    private void simulatica$clickLocally(int containerId, int slotId, int button, ClickType clickType,
                                         Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!SimulationMenus.isSimulated(menu) || containerId != menu.containerId) return;

        menu.clicked(slotId, button, clickType, player);
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

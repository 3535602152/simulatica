package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.server.SimulationCommandBlocks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientPacketSendMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void simulatica$captureSimulatedEdit(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundSetCommandBlockPacket save && SimulationCommandBlocks.apply(save)) {
            ci.cancel();
        }
    }
}

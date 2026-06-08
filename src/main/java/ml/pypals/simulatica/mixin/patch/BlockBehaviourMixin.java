package ml.pypals.simulatica.mixin.patch;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ml.pypals.simulatica.simulation.SimulatedServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collections;
import java.util.List;

@Mixin(BlockBehaviour.class)
public class BlockBehaviourMixin {
   @WrapMethod(method = "getDrops")
   protected List<ItemStack> getDrops(BlockState blockState, LootParams.Builder builder, Operation<List<ItemStack>> original) {
       LootParams lootParams = builder.withParameter(LootContextParams.BLOCK_STATE, blockState).create(LootContextParamSets.BLOCK);
       ServerLevel serverLevel = lootParams.getLevel();
       if(serverLevel instanceof SimulatedServerLevel) return List.of();
       else return original.call(blockState, builder);
   }
}

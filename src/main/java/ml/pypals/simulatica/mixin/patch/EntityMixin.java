package ml.pypals.simulatica.mixin.patch;

import com.ibm.icu.impl.coll.UVector32;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import ml.pypals.simulatica.simulation.SimulatedServerLevel;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;


@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    private Level level;


    @Shadow
    protected abstract AABB makeBoundingBox(Vec3 vec3);

    @Shadow
    public abstract boolean isAlive();

    @Shadow
    public abstract boolean collidedWithShapeMovingFrom(Vec3 vec3, Vec3 vec32, List<AABB> list);

    @Shadow
    public abstract boolean collidedWithFluid(FluidState fluidState, BlockPos blockPos, Vec3 vec3, Vec3 vec32);

    @Shadow
    protected abstract void onInsideBlock(BlockState blockState);

    @Shadow
    public abstract void fillCrashReportCategory(CrashReportCategory crashReportCategory);

    @WrapMethod(method = "Lnet/minecraft/world/entity/Entity;checkInsideBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;Lit/unimi/dsi/fastutil/longs/LongSet;I)I")
    private int checkInsideBlocks(Vec3 vec3, Vec3 vec32, InsideBlockEffectApplier.StepBasedCollector stepBasedCollector, LongSet longSet, int i, Operation<Integer> original) {
        if(!(level instanceof SimulatedServerLevel)) return original.call(vec3, vec32, stepBasedCollector, longSet, i);
        AABB aABB = this.makeBoundingBox(vec32).deflate(1.0E-5F);
        boolean bl = vec3.distanceToSqr(vec32) > Mth.square(0.9999900000002526);
        AtomicInteger atomicInteger = new AtomicInteger();
        BlockGetter.forEachBlockIntersectedBetween(vec3, vec32, aABB, (blockPos, j) -> {
            if (!this.isAlive()) {
                return false;
            } else if (j >= i) {
                return false;
            } else {
                atomicInteger.set(j);
                BlockState blockState = this.level.getBlockState(blockPos);
                if (blockState.isAir()) {

                    return true;
                } else {
                    VoxelShape voxelShape = blockState.getEntityInsideCollisionShape(this.level, blockPos, (Entity)(Object)this);
                    boolean bl3 = voxelShape == Shapes.block() || this.collidedWithShapeMovingFrom(vec3, vec32, voxelShape.move(new Vec3(blockPos)).toAabbs());
                    boolean bl4 = this.collidedWithFluid(blockState.getFluidState(), blockPos, vec3, vec32);
                    if ((bl3 || bl4) && longSet.add(blockPos.asLong())) {
                        if (bl3) {
                            try {
                                boolean bl5 = bl || aABB.intersects(blockPos);
                                stepBasedCollector.advanceStep(j);
                                blockState.entityInside(this.level, blockPos, (Entity)(Object)this , stepBasedCollector, bl5);
                                this.onInsideBlock(blockState);
                            } catch (Throwable var20) {
                                CrashReport crashReport = CrashReport.forThrowable(var20, "Colliding entity with block");
                                CrashReportCategory crashReportCategory = crashReport.addCategory("Block being collided with");
                                CrashReportCategory.populateBlockDetails(crashReportCategory, this.level, blockPos, blockState);
                                CrashReportCategory crashReportCategory2 = crashReport.addCategory("Entity being checked for collision");
                                this.fillCrashReportCategory(crashReportCategory2);
                                throw new ReportedException(crashReport);
                            }
                        }

                        if (bl4) {
                            stepBasedCollector.advanceStep(j);
                            blockState.getFluidState().entityInside(this.level, blockPos, (Entity)(Object)this, stepBasedCollector);
                        }
                        return true;
                    } else {
                        return true;
                    }
                }
            }
        });
        return atomicInteger.get() + 1;
    }


}

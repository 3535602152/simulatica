package ml.pypals.simulatica.mixin;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.litematica.tool.ToolMode;
import ml.pypals.simulatica.SimulaticaClient;
import net.minecraft.util.StringRepresentable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Arrays;

@Mixin(value = ToolMode.class, remap = false)
public abstract class ToolModeMixin {

    @Shadow @Final @Mutable private static ToolMode[] $VALUES;
    @Shadow @Final @Mutable public static ImmutableList<ToolMode> VALUES;
    @Shadow @Final @Mutable public static StringRepresentable.EnumCodec<ToolMode> CODEC;

    @Invoker("<init>")
    public static ToolMode sim$create(String name, int ordinal, String configString, String unlocName, boolean creativeOnly, boolean usesSchematic, boolean usesBlockPrimary, boolean usesBlockSecondary) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void sim$onClassInit(CallbackInfo ci) {
        int ordinal = $VALUES.length;

        ToolMode simulate = sim$create("SIMULATE", ordinal, "simulate", "litematica.tool_mode.name.simulate", false, true, true, true);

        ToolMode[] extended = Arrays.copyOf($VALUES, ordinal + 1);
        extended[ordinal] = simulate;

        sim$putStatic("$VALUES", extended);
        sim$putStatic("VALUES", ImmutableList.copyOf(extended));
        sim$putStatic("CODEC", StringRepresentable.fromEnum(ToolMode::values));

        sim$clearEnumCache();
        SimulaticaClient.SIMULATE = simulate;
    }

    @Unique
    private static void sim$putStatic(String name, Object val) {
        try {
            Field f = ToolMode.class.getDeclaredField(name);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ToolMode.class, MethodHandles.lookup());
            VarHandle vh = lookup.unreflectVarHandle(f);
            vh.set(val);
        } catch (Exception e) {
            throw new RuntimeException("[Simulatica] Failed to set static field: ToolMode." + name, e);
        }
    }

    @Unique
    private static void sim$clearEnumCache() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Class.class, MethodHandles.lookup());
            for (String cache : new String[]{"enumConstants", "enumConstantDirectory"}) {
                try {
                    Field f = Class.class.getDeclaredField(cache);
                    VarHandle vh = lookup.unreflectVarHandle(f);
                    vh.set(ToolMode.class, null);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
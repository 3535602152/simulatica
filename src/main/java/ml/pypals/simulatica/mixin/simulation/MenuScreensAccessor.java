package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - MenuScreens.ScreenConstructor 在 26.x 私有化：改为暴露裸 Map<Object>，由调用方反射调用 create()
 */
@Mixin(MenuScreens.class)
public interface MenuScreensAccessor {

    // MenuScreens.ScreenConstructor is private in 26.x, so the map is exposed raw
    // and the factory method is invoked reflectively by the caller.
    @Accessor("SCREENS")
    static Map<MenuType<?>, Object> simulatica$screens() {
        throw new AssertionError();
    }
}

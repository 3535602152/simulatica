package ml.pypals.simulatica.counter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SIMULATICA-新增] 地毯漏斗计数器（参考 Carpet 的 hopperCounters）。
 *
 * <p>羊毛地毯放在漏斗的朝向（FACING）方向上时，漏斗弹出（ejectItems）的物品按地毯颜色累计计数，
 * 并被清空（吞掉），用于测量机器/农场的物品产出速率。每个颜色一个计数器，可单独或全部重置。</p>
 */
public final class HopperCounter {

    private static final Map<DyeColor, HopperCounter> COUNTERS = new EnumMap<>(DyeColor.class);

    private final DyeColor color;
    private final Map<Item, Long> counts = new HashMap<>();
    private long total;

    private HopperCounter(DyeColor color) {
        this.color = color;
    }

    public static HopperCounter getCounter(DyeColor color) {
        return COUNTERS.computeIfAbsent(color, HopperCounter::new);
    }

    /** 累计一个物品堆（按数量）。 */
    public void add(ItemStack stack) {
        long n = stack.getCount();
        this.counts.merge(stack.getItem(), n, Long::sum);
        this.total += n;
    }

    public void reset() {
        this.counts.clear();
        this.total = 0;
    }

    public static void resetAll() {
        COUNTERS.values().forEach(HopperCounter::reset);
    }

    public DyeColor color() {
        return this.color;
    }

    public long total() {
        return this.total;
    }

    /** 所有非零计数器的汇总文本，用于聊天栏/界面显示。 */
    public static List<Component> formatAll() {
        List<Component> out = new ArrayList<>();
        boolean any = false;
        for (HopperCounter counter : COUNTERS.values()) {
            if (counter.total == 0) {
                continue;
            }
            any = true;
            out.add(Component.literal(colorName(counter.color) + " 地毯: " + counter.total));
        }
        if (!any) {
            out.add(Component.literal("（暂无计数）"));
        }
        return out;
    }

    private static String colorName(DyeColor color) {
        return switch (color) {
            case WHITE -> "白色";
            case ORANGE -> "橙色";
            case MAGENTA -> "品红";
            case LIGHT_BLUE -> "淡蓝";
            case YELLOW -> "黄色";
            case LIME -> "黄绿";
            case PINK -> "粉色";
            case GRAY -> "灰色";
            case LIGHT_GRAY -> "淡灰";
            case CYAN -> "青色";
            case PURPLE -> "紫色";
            case BLUE -> "蓝色";
            case BROWN -> "棕色";
            case GREEN -> "绿色";
            case RED -> "红色";
            case BLACK -> "黑色";
        };
    }
}

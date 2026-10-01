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
 * [SIMULATICA-新增] 羊毛漏斗计数器（参考 Carpet 的 hopperCounters）。
 *
 * <p>羊毛方块放在漏斗的朝向（FACING）方向上时，漏斗弹出（ejectItems）的物品按羊毛颜色累计计数，
 * 并被清空（吞掉），用于测量机器/农场的物品产出速率。每个颜色一个计数器，可单独或全部重置。</p>
 */
public final class HopperCounter {

    private static final Map<DyeColor, HopperCounter> COUNTERS = new EnumMap<>(DyeColor.class);

    private final DyeColor color;
    private final Map<Item, Long> counts = new HashMap<>();
    private long total;
    private long startMillis = -1;

    private HopperCounter(DyeColor color) {
        this.color = color;
    }

    public static HopperCounter getCounter(DyeColor color) {
        return COUNTERS.computeIfAbsent(color, HopperCounter::new);
    }

    /** 累计一个物品堆（按数量），记录首个物品到达时间用于速率计算。 */
    public void add(ItemStack stack) {
        long n = stack.getCount();
        this.counts.merge(stack.getItem(), n, Long::sum);
        this.total += n;
        if (this.startMillis < 0) {
            this.startMillis = System.currentTimeMillis();
        }
    }

    public void reset() {
        this.counts.clear();
        this.total = 0;
        this.startMillis = -1;
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

    /**
     * 所有正在运行的计数器汇总（参考 Carpet 的 /counter 显示）：每个颜色一行，列出物品与数量
     * （按数量降序）、总数与每小时速率，字体用对应羊毛颜色渲染。
     */
    public static List<Component> formatAll() {
        List<Component> out = new ArrayList<>();
        for (DyeColor color : DyeColor.values()) {
            HopperCounter counter = COUNTERS.get(color);
            if (counter == null || counter.total == 0) {
                continue;
            }
            out.addAll(counter.formatLines());
        }
        if (out.isEmpty()) {
            out.add(Component.literal("尚未统计到任何物品。").withColor(0xFFAAAAAA));
        }
        return out;
    }

    private List<Component> formatLines() {
        List<Map.Entry<Item, Long>> entries = new ArrayList<>(this.counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        int textColor = this.color.getTextColor();
        List<Component> lines = new ArrayList<>();

        // 标题行：颜色名 + 总数 + 总速率
        lines.add(Component.literal(colorName(this.color) + ": 共 " + this.total
                + "，" + ratePerHour(this.total) + "/h").withColor(textColor));

        // 每种物品一行（竖式，参考 Carpet /counter）：物品名 x数量（该物品每小时产量）
        for (Map.Entry<Item, Long> entry : entries) {
            long count = entry.getValue();
            String itemName = Component.translatable(entry.getKey().getDescriptionId()).getString();
            lines.add(Component.literal("  " + itemName + " x" + count
                    + "（" + ratePerHour(count) + "/h）").withColor(textColor));
        }
        return lines;
    }

    /** 每小时物品速率（基于首个物品到达以来的实时时间）。 */
    private String ratePerHour(long count) {
        if (this.startMillis < 0) {
            return "0";
        }
        double hours = (System.currentTimeMillis() - this.startMillis) / 3_600_000.0;
        if (hours <= 0) {
            return "0";
        }
        return String.format("%.1f", count / hours);
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

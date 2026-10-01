package ml.pypals.simulatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.carpet.CarpetIntegration;
import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.simulation.server.SimulationSelfTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * [SIMULATICA-修改] 游戏内控制面板（裸输 /simulatica 打开）。
 *
 * <p>左右分类：左列「全局操作」+「世界调整」（折叠）+「假人全局」（Carpet 加载时显示，含批量清理/传送/
 * 停止动作）+ 底部指令输入栏，整列支持滚轮滚动与滑块拖动；右列「单个投影」启停列表（滚轮翻页 + 滑块）。</p>
 */
public final class SimulaticaMenuScreen extends Screen {

    private static final int PADDING = 16;
    private static final int ROW_HEIGHT = 22;
    private static final int LEFT_WIDTH = 200;
    private static final int LEFT_TOP = 40;
    private static final int BOTTOM_MARGIN = 44;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF55FFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_TRACK = 0x33000000;
    private static final int COLOR_THUMB = 0xCC888888;

    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final List<Button> placementButtons = new ArrayList<>();
    private final List<Button> configButtons = new ArrayList<>();

    // 左列滚动（按像素）
    private final List<Button> leftWidgets = new ArrayList<>();
    private final List<Integer> leftBaseY = new ArrayList<>();
    private final List<Header> leftHeaders = new ArrayList<>();
    private int leftNextY;
    private int leftContentHeight;
    private int leftScroll;
    private boolean worldExpanded = false;

    // 右列滚动（按行）
    private int scrollOffset;
    private int listX;
    private int listWidth;

    // 滑块拖拽状态
    private boolean draggingLeft = false;
    private boolean draggingRight = false;

    // 指令栏 TAB 补齐状态
    private EditBox commandField;
    private List<Suggestion> tabCandidates = List.of();
    private int tabCursor = 0;
    private String tabContext = null;

    private record Header(String text, int baseY) {}

    public SimulaticaMenuScreen() {
        super(Component.literal("Simulatica 控制面板"));
    }

    @Override
    protected void init() {
        this.placements.clear();
        this.placements.addAll(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        this.placementButtons.clear();
        this.configButtons.clear();
        this.leftWidgets.clear();
        this.leftBaseY.clear();
        this.leftHeaders.clear();
        this.leftNextY = LEFT_TOP;
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());

        this.listX = PADDING + LEFT_WIDTH + 24;
        this.listWidth = this.width - this.listX - PADDING;

        buildLeftColumn();
        this.leftScroll = Math.min(this.leftScroll, maxLeftScroll());
        buildCommandField();
        buildPlacementList();
        layoutLeft();
        layoutRows();
    }

    // ------------------------------------------------------------------
    // 左列构建（全局操作 + 世界调整 + 假人全局）
    // ------------------------------------------------------------------
    private void buildLeftColumn() {
        this.leftNextY = LEFT_TOP;
        this.leftHeaders.add(new Header("全局操作", this.leftNextY));

        addLeftButton("启动全部模拟", this.leftNextY, LEFT_WIDTH, b -> startAllPlacements());
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("停止全部模拟", this.leftNextY, LEFT_WIDTH, b -> {
            SimulationManager.getInstance().stopAll();
            refreshPlacementButtons();
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton(absorbLabel(), this.leftNextY, LEFT_WIDTH, b -> {
            SimulationManager.getInstance().setItemAbsorption(null);
            b.setMessage(absorbLabel());
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("清除越界实体", this.leftNextY, LEFT_WIDTH, b -> {
            int removed = SimulationManager.getInstance().purgeEscapedEntities();
            SimulaticaClient.sendFeedback(removed == 0
                    ? "No escaped entities found."
                    : "Purged " + removed + " escaped entities.");
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("运行自检诊断", this.leftNextY, LEFT_WIDTH,
                b -> SimulationSelfTest.run().forEach(SimulaticaClient::sendFeedback));
        this.leftNextY += ROW_HEIGHT;

        // 漏斗计数器
        this.leftNextY += 8;
        this.leftHeaders.add(new Header("漏斗计数器", this.leftNextY));
        addLeftButton("查看计数", this.leftNextY, LEFT_WIDTH, b -> {
            for (Component line : HopperCounter.formatAll()) {
                SimulaticaClient.sendFeedback(line.getString());
            }
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("重置计数", this.leftNextY, LEFT_WIDTH, b -> {
            HopperCounter.resetAll();
            SimulaticaClient.sendFeedback("已重置所有漏斗计数器。");
        });
        this.leftNextY += ROW_HEIGHT;

        // 世界调整（折叠）
        this.leftNextY += 8;
        this.leftHeaders.add(new Header("世界调整", this.leftNextY));
        addLeftButton(this.worldExpanded ? "收起世界调整" : "展开世界调整", this.leftNextY, LEFT_WIDTH, b -> {
            this.worldExpanded = !this.worldExpanded;
            rebuild();
        });
        this.leftNextY += ROW_HEIGHT;
        if (this.worldExpanded) {
            addWorldRow("时间:白天", "/time set day", "时间:夜晚", "/time set night", "时间:正午", "/time set noon");
            addWorldRow("时间:午夜", "/time set midnight", "难度:和平", "/difficulty peaceful", "难度:简单", "/difficulty easy");
            addWorldRow("难度:普通", "/difficulty normal", "难度:困难", "/difficulty hard", "天气:晴", "/weather clear");
            addWorldRow("天气:雨", "/weather rain", "天气:雷", "/weather thunder", null, null);
        }

        // 假人全局（仅 Carpet 加载时显示）
        if (CarpetIntegration.isLoaded()) {
            this.leftNextY += 8;
            this.leftHeaders.add(new Header("假人全局", this.leftNextY));
            addLeftButton("清理全部假人", this.leftNextY, LEFT_WIDTH, b -> {
                int n = BotManager.removeAllGlobally();
                SimulaticaClient.sendFeedback("已清理 " + n + " 个假人。");
                rebuild();
            });
            this.leftNextY += ROW_HEIGHT;
            addLeftButton("传送全部假人", this.leftNextY, LEFT_WIDTH, b -> {
                int n = BotManager.teleportAllToPlayer();
                SimulaticaClient.sendFeedback("已把 " + n + " 个假人传送到玩家位置。");
            });
            this.leftNextY += ROW_HEIGHT;
            addLeftButton("停止全部动作", this.leftNextY, LEFT_WIDTH, b -> BotManager.stopAllActionsGlobally());
            this.leftNextY += ROW_HEIGHT;
        }

        this.leftContentHeight = this.leftNextY - LEFT_TOP;
    }

    private Button addLeftButton(String label, int y, int width, Button.OnPress onPress) {
        return addLeftButton(Component.literal(label), y, width, onPress);
    }

    private Button addLeftButton(Component label, int y, int width, Button.OnPress onPress) {
        return addLeftButtonAt(label, PADDING, y, width, onPress);
    }

    private Button addLeftButtonAt(Component label, int x, int y, int width, Button.OnPress onPress) {
        Button b = Button.builder(label, onPress).bounds(x, y, width, 20).build();
        this.leftWidgets.add(b);
        this.leftBaseY.add(y);
        addRenderableWidget(b);
        return b;
    }

    private void addWorldRow(String l1, String c1, String l2, String c2, String l3, String c3) {
        int y = this.leftNextY;
        if (l1 != null) {
            addLeftButtonAt(Component.literal(l1), PADDING, y, 64, b -> SimulationCommands.execute(c1));
        }
        if (l2 != null) {
            addLeftButtonAt(Component.literal(l2), PADDING + 66, y, 64, b -> SimulationCommands.execute(c2));
        }
        if (l3 != null) {
            addLeftButtonAt(Component.literal(l3), PADDING + 132, y, 64, b -> SimulationCommands.execute(c3));
        }
        this.leftNextY += ROW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 指令输入栏（固定在底部）
    // ------------------------------------------------------------------
    private void buildCommandField() {
        int x = PADDING;
        int fieldY = this.height - 40;
        int buttonWidth = 46;

        this.commandField = new EditBox(this.font, x, fieldY, LEFT_WIDTH - buttonWidth - 4, 18,
                Component.literal(""));
        this.commandField.setHint(Component.literal("/指令"));
        this.commandField.setMaxLength(256);
        this.tabCandidates = List.of();
        this.tabCursor = 0;
        this.tabContext = null;
        addRenderableWidget(this.commandField);

        addRenderableWidget(Button.builder(Component.literal("发送"),
                        button -> {
                            String command = this.commandField.getValue().trim();
                            if (!command.isEmpty()) {
                                SimulationCommands.execute(command);
                                this.commandField.setValue("");
                            }
                        }).bounds(x + LEFT_WIDTH - buttonWidth, fieldY - 1, buttonWidth, 20).build());
    }

    // ------------------------------------------------------------------
    // 右列：单个投影列表
    // ------------------------------------------------------------------
    private void buildPlacementList() {
        for (SchematicPlacement placement : this.placements) {
            Button config = Button.builder(Component.literal("配置"),
                            button -> Minecraft.getInstance().gui.setScreen(new PlacementConfigScreen(placement)))
                    .bounds(this.listX + this.listWidth - 108, 0, 48, 20).build();
            this.configButtons.add(addRenderableWidget(config));

            Button row = Button.builder(toggleLabel(placement), button -> {
                SimulationManager manager = SimulationManager.getInstance();
                if (manager.isSimulating(placement)) {
                    manager.stopSimulation(placement);
                } else {
                    manager.startSimulation(placement);
                }
                button.setMessage(toggleLabel(placement));
            }).bounds(this.listX + this.listWidth - 56, 0, 56, 20).build();
            this.placementButtons.add(addRenderableWidget(row));
        }
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void startAllPlacements() {
        for (SchematicPlacement placement : this.placements) {
            SimulationManager.getInstance().startSimulation(placement);
        }
        refreshPlacementButtons();
    }

    private void refreshPlacementButtons() {
        for (int i = 0; i < this.placementButtons.size(); i++) {
            this.placementButtons.get(i).setMessage(toggleLabel(this.placements.get(i)));
        }
    }

    private static Component absorbLabel() {
        return Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关"));
    }

    private static Component toggleLabel(SchematicPlacement placement) {
        return Component.literal(SimulationManager.getInstance().isSimulating(placement) ? "停止" : "启动");
    }

    // ------------------------------------------------------------------
    // 滚动布局
    // ------------------------------------------------------------------
    private static final int LIST_TOP = 40;
    private static final int LIST_BOTTOM_MARGIN = 40;

    private int leftViewportHeight() {
        return Math.max(1, this.height - BOTTOM_MARGIN - LEFT_TOP);
    }

    private int maxLeftScroll() {
        return Math.max(0, this.leftContentHeight - leftViewportHeight());
    }

    private void layoutLeft() {
        int top = LEFT_TOP;
        int bottom = this.height - BOTTOM_MARGIN;
        for (int i = 0; i < this.leftWidgets.size(); i++) {
            Button w = this.leftWidgets.get(i);
            int y = this.leftBaseY.get(i) - this.leftScroll;
            w.setY(y);
            boolean shown = y + ROW_HEIGHT > top && y < bottom;
            w.visible = shown;
            w.active = shown;
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, this.placements.size() - visibleRows());
    }

    private void layoutRows() {
        int visible = visibleRows();
        for (int i = 0; i < this.placementButtons.size(); i++) {
            Button row = this.placementButtons.get(i);
            int slot = i - this.scrollOffset;
            boolean shown = slot >= 0 && slot < visible;
            row.visible = shown;
            row.active = shown;
            if (shown) {
                row.setY(LIST_TOP + slot * ROW_HEIGHT + 1);
            }
            this.configButtons.get(i).visible = shown;
            this.configButtons.get(i).active = shown;
            if (shown) {
                this.configButtons.get(i).setY(LIST_TOP + slot * ROW_HEIGHT + 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 鼠标交互（滚轮 + 滑块拖拽）
    // ------------------------------------------------------------------
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double wheel = Math.signum(scrollY);
        // 右列（投影列表）
        if (mouseX >= this.listX && maxScroll() > 0) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset - (int) wheel));
            layoutRows();
            return true;
        }
        // 左列
        if (mouseX < this.listX && maxLeftScroll() > 0) {
            this.leftScroll = Math.max(0, Math.min(maxLeftScroll(), this.leftScroll - (int) wheel * ROW_HEIGHT));
            layoutLeft();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean hasActiveButton) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            if (clickSuggestion(mx, my)) {
                return true;
            }
            if (hitLeftScrollbar(mx, my)) {
                this.draggingLeft = true;
                dragLeft(my);
                return true;
            }
            if (hitRightScrollbar(mx, my)) {
                this.draggingRight = true;
                dragRight(my);
                return true;
            }
        }
        return super.mouseClicked(event, hasActiveButton);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingLeft) {
            dragLeft((int) event.y());
            return true;
        }
        if (this.draggingRight) {
            dragRight((int) event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.draggingLeft = false;
        this.draggingRight = false;
        return super.mouseReleased(event);
    }

    // ------------------------------------------------------------------
    // 指令栏 TAB 补齐（像原版：命令名/参数补全、多候选循环、唯一候选直接补全）
    // ------------------------------------------------------------------
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB
                && this.commandField != null
                && this.commandField.isFocused()) {
            onTabComplete();
            return true;
        }
        // 非 TAB 按键（输入/删除/移动光标）→ 补全候选失效
        this.tabCandidates = List.of();
        this.tabContext = null;
        return super.keyPressed(event);
    }

    private void onTabComplete() {
        String value = this.commandField.getValue();
        int cursor = this.commandField.getCursorPosition();
        String prefix = value.substring(0, cursor);
        int start = completionStart(prefix);

        if (prefix.equals(this.tabContext) && !this.tabCandidates.isEmpty()) {
            // 连续 TAB：循环到下一个候选并补全
            this.tabCursor = (this.tabCursor + 1) % this.tabCandidates.size();
            applySuggestion(this.tabCandidates.get(this.tabCursor), value, cursor);
            return;
        }

        this.tabContext = prefix;
        this.tabCursor = 0;
        SimulationCommands.suggest(new SuggestionsBuilder(prefix, start)).thenAccept(suggestions ->
                Minecraft.getInstance().execute(() -> {
                    this.tabCandidates = new ArrayList<>(suggestions.getList());
                    if (this.tabCandidates.isEmpty()) {
                        return;
                    }
                    this.tabCursor = 0;
                    // 补全第一个候选（保留候选列表供鼠标点击选取）
                    applySuggestion(this.tabCandidates.get(0),
                            this.commandField.getValue(), this.commandField.getCursorPosition());
                }));
    }

    private static int completionStart(String prefix) {
        int space = prefix.lastIndexOf(' ');
        if (space >= 0) {
            return space + 1;
        }
        return prefix.startsWith("/") ? 1 : 0;
    }

    private void applySuggestion(Suggestion suggestion, String value, int cursor) {
        String prefix = value.substring(0, cursor);
        String newPrefix = prefix.substring(0, suggestion.getRange().getStart()) + suggestion.getText();
        String newValue = newPrefix + value.substring(cursor);
        this.commandField.setValue(newValue);
        this.commandField.setCursorPosition(newPrefix.length());
        this.commandField.setHighlightPos(newPrefix.length());
    }

    // ------------------------------------------------------------------
    // 指令补全候选列表（像原版聊天栏：TAB 弹出，鼠标点击选取）
    // ------------------------------------------------------------------
    private static final int SUGGEST_ROW = 14;
    private static final int SUGGEST_MAX = 8;

    /** 候选列表的底边 Y（指令栏上方 4px）。 */
    private int suggestionBottom() {
        return this.height - 44;
    }

    private void drawSuggestionList(GuiGraphicsExtractor extractor) {
        int x = PADDING;
        int width = LEFT_WIDTH - 50;
        int n = Math.min(this.tabCandidates.size(), SUGGEST_MAX);
        int bottom = suggestionBottom();
        int top = bottom - n * SUGGEST_ROW - 2;
        extractor.fill(x, top, x + width, bottom, 0xE0101010);
        for (int i = 0; i < n; i++) {
            int y = bottom - (i + 1) * SUGGEST_ROW;
            if (i == this.tabCursor) {
                extractor.fill(x, y, x + width, y + SUGGEST_ROW, 0x60484848);
            }
            extractor.text(this.font, this.tabCandidates.get(i).getText(), x + 4, y + 3,
                    i == this.tabCursor ? COLOR_TEXT : COLOR_DIM);
        }
    }

    private boolean clickSuggestion(int mx, int my) {
        if (this.tabCandidates.isEmpty()) {
            return false;
        }
        int x = PADDING;
        int width = LEFT_WIDTH - 50;
        int n = Math.min(this.tabCandidates.size(), SUGGEST_MAX);
        int bottom = suggestionBottom();
        int top = bottom - n * SUGGEST_ROW - 2;
        if (mx < x || mx > x + width || my < top || my > bottom) {
            return false;
        }
        int idx = (bottom - my - 1) / SUGGEST_ROW;
        if (idx >= 0 && idx < this.tabCandidates.size()) {
            applySuggestion(this.tabCandidates.get(idx),
                    this.commandField.getValue(), this.commandField.getCursorPosition());
            this.tabCandidates = List.of();
            this.tabContext = null;
            return true;
        }
        return false;
    }

    private int leftScrollbarX() {
        return PADDING + LEFT_WIDTH - SCROLLBAR_WIDTH;
    }

    private int rightScrollbarX() {
        return this.width - PADDING - SCROLLBAR_WIDTH;
    }

    private boolean hitLeftScrollbar(int mx, int my) {
        return maxLeftScroll() > 0
                && mx >= leftScrollbarX() && mx <= leftScrollbarX() + SCROLLBAR_WIDTH + 4
                && my >= LEFT_TOP && my <= this.height - BOTTOM_MARGIN;
    }

    private boolean hitRightScrollbar(int mx, int my) {
        return maxScroll() > 0
                && mx >= rightScrollbarX() && mx <= rightScrollbarX() + SCROLLBAR_WIDTH + 4
                && my >= LIST_TOP && my <= this.height - LIST_BOTTOM_MARGIN;
    }

    private void dragLeft(int mouseY) {
        int max = maxLeftScroll();
        if (max <= 0) {
            return;
        }
        int top = LEFT_TOP;
        int bottom = this.height - BOTTOM_MARGIN;
        int track = bottom - top;
        int thumb = Math.max(20, leftViewportHeight() * track / Math.max(1, this.leftContentHeight));
        int travel = track - thumb;
        double ratio = (mouseY - top - thumb / 2.0) / Math.max(1, travel);
        this.leftScroll = Math.max(0, Math.min(max, (int) Math.round(ratio * max)));
        layoutLeft();
    }

    private void dragRight(int mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int top = LIST_TOP;
        int bottom = this.height - LIST_BOTTOM_MARGIN;
        int track = bottom - top;
        int thumb = Math.max(20, visibleRows() * track / Math.max(1, this.placements.size()));
        int travel = track - thumb;
        double ratio = (mouseY - top - thumb / 2.0) / Math.max(1, travel);
        this.scrollOffset = Math.max(0, Math.min(max, (int) Math.round(ratio * max)));
        layoutRows();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------
    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(extractor);
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        SimulationManager manager = SimulationManager.getInstance();
        extractor.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 12, COLOR_TEXT);

        String status = "活动模拟 " + manager.getActiveCount()
                + " · 等待启动 " + manager.getPendingCount()
                + " · 投影总数 " + this.placements.size();
        extractor.text(this.font, status, this.width / 2 - this.font.width(status) / 2, 28, COLOR_DIM);

        // 左列：标题随内容滚动（用 scissor 裁剪）
        extractor.enableScissor(0, LEFT_TOP, this.listX, this.height - BOTTOM_MARGIN);
        for (Header header : this.leftHeaders) {
            extractor.text(this.font, header.text(), PADDING, header.baseY() - this.leftScroll - 12, COLOR_HEADER);
        }
        extractor.disableScissor();

        extractor.text(this.font, "模拟世界指令", PADDING, this.height - 40 - 12, COLOR_HEADER);
        extractor.text(this.font, "单个投影（滚轮翻页）", this.listX, LIST_TOP - 12, COLOR_HEADER);

        if (this.placements.isEmpty()) {
            extractor.text(this.font, "没有已加载的投影放置", this.listX, LIST_TOP + 4, COLOR_DIM);
        } else {
            extractor.enableScissor(this.listX, LIST_TOP, this.listX + this.listWidth, this.height - LIST_BOTTOM_MARGIN);
            int visible = visibleRows();
            for (int slot = 0; slot < visible; slot++) {
                int index = slot + this.scrollOffset;
                if (index >= this.placements.size()) {
                    break;
                }
                String name = this.font.plainSubstrByWidth(this.placements.get(index).getName(), this.listWidth - 118);
                boolean running = manager.isSimulating(this.placements.get(index));
                extractor.text(this.font, (running ? "● " : "○ ") + name,
                        this.listX + 2, LIST_TOP + slot * ROW_HEIGHT + 7,
                        running ? 0xFF55FF55 : COLOR_DIM);
            }
            extractor.disableScissor();
        }

        // 滑块
        drawScrollbar(extractor, leftScrollbarX(), LEFT_TOP, this.height - BOTTOM_MARGIN,
                this.leftScroll, maxLeftScroll(), leftViewportHeight(), Math.max(1, this.leftContentHeight));
        drawScrollbar(extractor, rightScrollbarX(), LIST_TOP, this.height - LIST_BOTTOM_MARGIN,
                this.scrollOffset * ROW_HEIGHT, maxScroll() * ROW_HEIGHT,
                visibleRows() * ROW_HEIGHT, Math.max(1, this.placements.size() * ROW_HEIGHT));

        // 指令补全候选列表（TAB 后显示，鼠标点击选取）
        if (!this.tabCandidates.isEmpty() && this.commandField != null && this.commandField.isFocused()) {
            drawSuggestionList(extractor);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor extractor, int x, int top, int bottom,
                               int scrollPx, int maxScrollPx, int viewportPx, int contentPx) {
        if (maxScrollPx <= 0) {
            return;
        }
        int track = bottom - top;
        int thumb = Math.max(20, viewportPx * track / contentPx);
        int travel = track - thumb;
        int thumbTop = top + travel * scrollPx / maxScrollPx;
        extractor.fill(x, top, x + SCROLLBAR_WIDTH, bottom, COLOR_TRACK);
        extractor.fill(x, thumbTop, x + SCROLLBAR_WIDTH, thumbTop + thumb, COLOR_THUMB);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

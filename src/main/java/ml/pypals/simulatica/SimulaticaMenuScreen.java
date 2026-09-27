package ml.pypals.simulatica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.SimulationSelfTest;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 游戏内控制面板（裸输 /simulatica 打开）：左列全局操作、右列单投影启停，采用 26.2 新的 extractRenderState 渲染管线。
 */
/**
 * The control panel opened by a bare {@code /simulatica}.
 *
 * <p>Left column: global actions (all placements + absorption + purge + selftest).
 * Right column: one row per loaded placement with its own start/stop toggle. The list scrolls
 * with the mouse wheel when it outgrows the panel. Drawn through the 26.2 render-state pipeline
 * ({@code extractRenderState}), not the old {@code render(GuiGraphics)} path.</p>
 */
public final class SimulaticaMenuScreen extends Screen {

    private static final int PADDING = 16;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 76;
    private static final int LIST_BOTTOM_MARGIN = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF55FFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;

    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final List<Button> placementButtons = new ArrayList<>();
    private Button absorbButton;
    private int scrollOffset;
    private int listX;
    private int listWidth;

    public SimulaticaMenuScreen() {
        super(Component.literal("Simulatica 控制面板"));
    }

    @Override
    protected void init() {
        this.placements.clear();
        this.placements.addAll(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        this.placementButtons.clear();
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());

        int leftX = PADDING;
        int leftWidth = 150;
        this.listX = leftX + leftWidth + 24;
        this.listWidth = this.width - this.listX - PADDING;

        int y = LIST_TOP;
        addRenderableWidget(Button.builder(Component.literal("启动全部模拟"),
                        button -> startAllPlacements())
                .bounds(leftX, y, leftWidth, 20).build());
        y += ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("停止全部模拟"),
                        button -> {
                            SimulationManager.getInstance().stopAll();
                            refreshPlacementButtons();
                        })
                .bounds(leftX, y, leftWidth, 20).build());
        y += ROW_HEIGHT;
        this.absorbButton = addRenderableWidget(Button.builder(absorbLabel(),
                        button -> {
                            SimulationManager.getInstance().setItemAbsorption(null);
                            button.setMessage(absorbLabel());
                        })
                .bounds(leftX, y, leftWidth, 20).build());
        y += ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("清除越界实体"),
                        button -> {
                            int removed = SimulationManager.getInstance().purgeEscapedEntities();
                            SimulaticaClient.sendFeedback(removed == 0
                                    ? "No escaped entities found."
                                    : "Purged " + removed + " escaped entities.");
                        })
                .bounds(leftX, y, leftWidth, 20).build());
        y += ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("运行自检诊断"),
                        button -> SimulationSelfTest.run().forEach(SimulaticaClient::sendFeedback))
                .bounds(leftX, y, leftWidth, 20).build());

        for (SchematicPlacement placement : this.placements) {
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
        layoutRows();
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
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && mouseX >= this.listX) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset - (int) Math.signum(scrollY)));
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

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

        extractor.text(this.font, "全局操作", PADDING, LIST_TOP - 14, COLOR_HEADER);
        extractor.text(this.font, "单个投影（滚轮翻页）", this.listX, LIST_TOP - 14, COLOR_HEADER);

        if (this.placements.isEmpty()) {
            extractor.text(this.font, "没有已加载的投影放置", this.listX, LIST_TOP + 4, COLOR_DIM);
            return;
        }

        extractor.enableScissor(this.listX, LIST_TOP, this.listX + this.listWidth, this.height - LIST_BOTTOM_MARGIN);
        int visible = visibleRows();
        for (int slot = 0; slot < visible; slot++) {
            int index = slot + this.scrollOffset;
            if (index >= this.placements.size()) {
                break;
            }
            String name = this.font.plainSubstrByWidth(this.placements.get(index).getName(), this.listWidth - 64);
            boolean running = manager.isSimulating(this.placements.get(index));
            extractor.text(this.font, (running ? "● " : "○ ") + name,
                    this.listX + 2, LIST_TOP + slot * ROW_HEIGHT + 7,
                    running ? 0xFF55FF55 : COLOR_DIM);
        }
        extractor.disableScissor();
    }

    /** The menu is a control panel: the simulation keeps running while it is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

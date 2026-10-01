package ml.pypals.simulatica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.carpet.CarpetIntegration;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/**
 * [SIMULATICA-新增] 单个投影的模拟配置界面。
 *
 * <p>承载该投影的假人联动（召唤、一键清理、传送、打开背包、动作、游戏模式）与 Simulatica 原生功能
 * （掉落物吸收、清除越界）。假人动作按钮过多时折叠，用「展开动作」按钮展开。</p>
 */
public final class PlacementConfigScreen extends Screen {

    private static final int PADDING = 16;
    private static final int ROW_HEIGHT = 22;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF55FFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;

    private final SchematicPlacement placement;
    private final List<Button> botButtons = new ArrayList<>();
    private final List<NameTag> botNameTags = new ArrayList<>();
    private boolean actionsExpanded = false;
    private int scrollOffset;
    private int botListTop;

    private int flowX;
    private int flowY;

    // 动作参数状态（0=关，-1=长按，>0=间隔 tick）
    private int attackInterval = 0;
    private int useInterval = 0;
    private int jumpInterval = 0;
    private boolean sneaking = false;
    private boolean sprinting = false;
    private boolean forward = false;

    private record NameTag(int x, int y, String text) {}

    public PlacementConfigScreen(SchematicPlacement placement) {
        super(Component.literal("投影配置 - " + placement.getName()));
        this.placement = placement;
    }

    @Override
    protected void init() {
        this.botButtons.clear();
        this.botNameTags.clear();
        this.scrollOffset = 0;
        this.flowX = PADDING;
        this.flowY = 36;

        buildBotTools();
        buildBotList();
        buildNative();
    }

    private void place(Button button, int width) {
        if (this.flowX + width > this.width - PADDING) {
            this.flowX = PADDING;
            this.flowY += ROW_HEIGHT;
        }
        button.setX(this.flowX);
        button.setY(this.flowY);
        button.setWidth(width);
        this.flowX += width + 4;
        addRenderableWidget(button);
    }

    private void newRow() {
        this.flowX = PADDING;
        this.flowY += ROW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 假人工具 + 折叠动作
    // ------------------------------------------------------------------
    private void buildBotTools() {
        if (!CarpetIntegration.isLoaded()) {
            place(Button.builder(Component.literal("假人联动需安装 Carpet 模组"),
                            button -> SimulaticaClient.sendFeedback("未检测到 Carpet 模组，无法使用假人联动。"))
                    .build(), 230);
            this.newRow();
            return;
        }

        place(Button.builder(Component.literal("召唤假人"), button -> {
                    if (spawnBot()) {
                        rebuild();
                    }
                }).build(), 80);
        place(Button.builder(Component.literal("一键清理"), button -> {
                    int n = BotManager.botsOf(this.placement).size();
                    BotManager.removeAll(this.placement);
                    SimulaticaClient.sendFeedback("已清理 " + n + " 个假人。");
                    rebuild();
                }).build(), 80);
        place(Button.builder(Component.literal("传送全部到玩家"), button -> {
                    for (BotManager.Bot bot : BotManager.botsOf(this.placement)) {
                        BotManager.teleportToPlayer(bot);
                    }
                    SimulaticaClient.sendFeedback("已把该投影全部假人传送到玩家位置。");
                }).build(), 130);

        this.newRow();
        place(Button.builder(Component.literal(this.actionsExpanded ? "收起动作" : "展开动作"),
                        button -> {
                            this.actionsExpanded = !this.actionsExpanded;
                            rebuild();
                        }).build(), 80);

        if (this.actionsExpanded) {
            place(Button.builder(Component.literal(intervalLabel("攻击间隔", this.attackInterval)), button -> {
                        this.attackInterval = nextInterval(this.attackInterval);
                        BotManager.actionAllInterval(this.placement, "ATTACK", this.attackInterval);
                        button.setMessage(Component.literal(intervalLabel("攻击间隔", this.attackInterval)));
                    }).build(), 90);
            place(Button.builder(Component.literal(intervalLabel("使用间隔", this.useInterval)), button -> {
                        this.useInterval = nextInterval(this.useInterval);
                        BotManager.actionAllInterval(this.placement, "USE", this.useInterval);
                        button.setMessage(Component.literal(intervalLabel("使用间隔", this.useInterval)));
                    }).build(), 90);
            place(Button.builder(Component.literal(intervalLabel("跳跃间隔", this.jumpInterval)), button -> {
                        this.jumpInterval = nextInterval(this.jumpInterval);
                        BotManager.actionAllInterval(this.placement, "JUMP", this.jumpInterval);
                        button.setMessage(Component.literal(intervalLabel("跳跃间隔", this.jumpInterval)));
                    }).build(), 90);
            this.newRow();
            place(Button.builder(Component.literal(this.sneaking ? "潜行:开" : "潜行:关"), button -> {
                        this.sneaking = !this.sneaking;
                        BotManager.setSneakingAll(this.placement, this.sneaking);
                        button.setMessage(Component.literal(this.sneaking ? "潜行:开" : "潜行:关"));
                    }).build(), 62);
            place(Button.builder(Component.literal(this.sprinting ? "疾跑:开" : "疾跑:关"), button -> {
                        this.sprinting = !this.sprinting;
                        BotManager.setSprintingAll(this.placement, this.sprinting);
                        button.setMessage(Component.literal(this.sprinting ? "疾跑:开" : "疾跑:关"));
                    }).build(), 62);
            place(Button.builder(Component.literal(this.forward ? "前进:开" : "前进:关"), button -> {
                        this.forward = !this.forward;
                        BotManager.setForwardAll(this.placement, this.forward ? 1.0F : 0.0F);
                        button.setMessage(Component.literal(this.forward ? "前进:开" : "前进:关"));
                    }).build(), 62);
            this.newRow();
            place(Button.builder(Component.literal("单击左键"), button ->
                    BotManager.actionAll(this.placement, "ATTACK", false)).build(), 70);
            place(Button.builder(Component.literal("单击右键"), button ->
                    BotManager.actionAll(this.placement, "USE", false)).build(), 70);
            place(Button.builder(Component.literal("丢弃"), button -> BotManager.actionAll(this.placement, "DROP_ITEM", false)).build(), 54);
            place(Button.builder(Component.literal("换手"), button -> BotManager.actionAll(this.placement, "SWAP_HANDS", false)).build(), 54);
            place(Button.builder(Component.literal("停止"), button -> BotManager.stopAllActions(this.placement)).build(), 54);
        }
    }

    private static final int[] INTERVALS = {0, -1, 1, 2, 5, 10, 20};

    private static int nextInterval(int current) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) {
                return INTERVALS[(i + 1) % INTERVALS.length];
            }
        }
        return 0;
    }

    private static String intervalLabel(String prefix, int interval) {
        String value = switch (interval) {
            case 0 -> "关";
            case -1 -> "长按";
            default -> interval + "t";
        };
        return prefix + ":" + value;
    }

    private boolean spawnBot() {
        int index = BotManager.botsOf(this.placement).size() + 1;
        String name;
        do {
            name = "Bot" + index++;
        } while (hasBotNamed(name));

        BotManager.Bot bot = BotManager.spawn(this.placement, name, GameType.CREATIVE);
        if (bot == null) {
            SimulaticaClient.sendFeedback("无法召唤假人：请先启动该投影的模拟。");
            return false;
        }
        SimulaticaClient.sendFeedback("已召唤假人 " + name + "。");
        return true;
    }

    private boolean hasBotNamed(String name) {
        for (BotManager.Bot bot : BotManager.botsOf(this.placement)) {
            if (bot.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 假人列表
    // ------------------------------------------------------------------
    private void buildBotList() {
        this.newRow();
        this.flowY += 4;
        this.botListTop = this.flowY;

        for (BotManager.Bot bot : BotManager.botsOf(this.placement)) {
            this.newRow();

            String name = bot.name();
            int nameWidth = this.font.width(name) + 6;
            this.botNameTags.add(new NameTag(this.flowX, this.flowY + 6, name));
            this.flowX += nameWidth + 4;

            Button backpack = Button.builder(Component.literal("背包"),
                            button -> Minecraft.getInstance().gui.setScreen(new BotInventoryScreen(bot.player())))
                    .bounds(this.flowX, 0, 46, 20).build();
            this.botButtons.add(addRenderableWidget(backpack));
            this.flowX += 50;

            Button teleport = Button.builder(Component.literal("传送"),
                            button -> BotManager.teleportToPlayer(bot))
                    .bounds(this.flowX, 0, 46, 20).build();
            this.botButtons.add(addRenderableWidget(teleport));
            this.flowX += 50;

            Button mode = Button.builder(Component.literal("模式:" + modeName(bot.player())),
                            button -> {
                                bot.player().setGameMode(nextMode(bot.player()));
                                button.setMessage(Component.literal("模式:" + modeName(bot.player())));
                            })
                    .bounds(this.flowX, 0, 70, 20).build();
            this.botButtons.add(addRenderableWidget(mode));
            this.flowX += 74;

            Button remove = Button.builder(Component.literal("移除"),
                            button -> {
                                BotManager.remove(this.placement, bot);
                                rebuild();
                            })
                    .bounds(this.flowX, 0, 46, 20).build();
            this.botButtons.add(addRenderableWidget(remove));
            this.flowX += 50;
        }
        layoutBotRows();
    }

    private void layoutBotRows() {
        int visible = visibleBotRows();
        for (int i = 0; i < this.botButtons.size(); i++) {
            int botIndex = i / 4;
            Button row = this.botButtons.get(i);
            int slot = botIndex - this.scrollOffset;
            boolean shown = slot >= 0 && slot < visible;
            row.visible = shown;
            row.active = shown;
            if (shown) {
                row.setY(this.botListTop + slot * ROW_HEIGHT + 1);
            }
        }
    }

    private int visibleBotRows() {
        return Math.max(1, (this.height - 20 - this.botListTop) / ROW_HEIGHT);
    }

    private int maxBotScroll() {
        int count = BotManager.botsOf(this.placement).size();
        return Math.max(0, count - visibleBotRows());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxBotScroll() > 0 && mouseY >= this.botListTop) {
            this.scrollOffset = Math.max(0, Math.min(maxBotScroll(), this.scrollOffset - (int) Math.signum(scrollY)));
            layoutBotRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // Simulatica 原生
    // ------------------------------------------------------------------
    private void buildNative() {
        this.newRow();
        this.flowY += 8;

        place(Button.builder(Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关")),
                        button -> {
                            SimulationManager.getInstance().setItemAbsorption(null);
                            button.setMessage(Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关")));
                        }).build(), 130);
        place(Button.builder(Component.literal("清除越界实体"),
                        button -> {
                            int removed = SimulationManager.getInstance().purgeEscapedEntities();
                            SimulaticaClient.sendFeedback(removed == 0
                                    ? "No escaped entities found."
                                    : "Purged " + removed + " escaped entities.");
                        }).build(), 110);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private static String modeName(ServerPlayer player) {
        return switch (player.gameMode.getGameModeForPlayer()) {
            case SURVIVAL -> "生存";
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
        };
    }

    private static GameType nextMode(ServerPlayer player) {
        return switch (player.gameMode.getGameModeForPlayer()) {
            case SURVIVAL -> GameType.CREATIVE;
            case CREATIVE -> GameType.ADVENTURE;
            case ADVENTURE -> GameType.SPECTATOR;
            case SPECTATOR -> GameType.SURVIVAL;
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(extractor);
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 12, COLOR_TEXT);

        extractor.text(this.font, "假人联动（Carpet，单个投影）", PADDING, 36 - 12, COLOR_HEADER);

        for (NameTag tag : this.botNameTags) {
            extractor.text(this.font, tag.text(), tag.x(), tag.y(), COLOR_TEXT);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

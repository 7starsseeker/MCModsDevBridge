package com.mcdev.probe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcdev.probe.ModCatalog;
import com.mcdev.probe.action.ActionException;
import com.mcdev.probe.action.ActionRequest;
import com.mcdev.probe.action.ClientActionBackend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/**
 * Client-side probe: UI tree, mouse clicks, screenshots.
 * Runs on the client's main (render) thread for all game access.
 */
public final class ClientProbeServer {
    private static final Gson GSON = new Gson();
    private static HttpServer server;
    private static ExecutorService executor;
    private static String token = "mcdev";
    private static String loader = "unknown";
    private static final java.util.LinkedHashSet<String> CAPS = new java.util.LinkedHashSet<>();
    /**
     * 语义动作层后端（架构 §8.2）。客户端这一份是「接近全量」的那一半：
     * 移动 / 挖掘 / 放置 / 交互 / 攻击 / 拾取 / 背包 / 模式 / 能力 / 旁观 / 重生 / 睡觉。
     */
    private static final ClientActionBackend ACTION_BACKEND = new ClientActionBackend();

    private ClientProbeServer() {
    }

    public static synchronized void start(int port, String tokenIn) {
        if (server != null) {
            return;
        }
        token = tokenIn == null ? "" : tokenIn;
        CAPS.clear();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            route("/status", ClientProbeServer::handleStatus, "core");
            route("/connect", ClientProbeServer::handleConnect, "net");
            // ---- 单人世界：客户端自己开世界（内置服务端），仅客户端 mod / 单机行为只在这里能验 ----
            route("/world/singleplayer", ClientProbeServer::handleWorldSingleplayer, "world");
            route("/world/leave", ClientProbeServer::handleWorldLeave, "world");
            route("/log/tail", ClientProbeServer::handleLogTail, "core");
            route("/ui/snapshot", ClientProbeServer::handleUiSnapshot, "client_ui");
            route("/ui/click", ClientProbeServer::handleUiClick, "client_ui");
            route("/ui/close", ClientProbeServer::handleUiClose, "client_ui");
            route("/ui/hover", ClientProbeServer::handleUiHover, "client_ui");
            route("/ui/hover_slot", ClientProbeServer::handleUiHoverSlot, "client_ui");
            route("/player/inventory", ClientProbeServer::handlePlayerInventory, "inventory");
            route("/command", ClientProbeServer::handleCommand, "command");
            route("/player/use", ClientProbeServer::handlePlayerUse, "player_action");
            route("/player/use_item", ClientProbeServer::handlePlayerUseItem, "player_action");
            route("/player/break", ClientProbeServer::handlePlayerBreak, "player_action");
            route("/player/mine", ClientProbeServer::handlePlayerMine, "player_action");
            route("/item/mainhand", ClientProbeServer::handleMainhandItem, "inventory");
            route("/entity/list", ClientProbeServer::handleEntityList, "entity");
            route("/screenshot", ClientProbeServer::handleScreenshot, "client_ui");
            route("/quit", ClientProbeServer::handleQuit, "core");
            // ---- P4 第二批：聊天与特殊方块 UI（架构 §8.1 的 out-of-scope 补回）----
            route("/chat/send", ClientProbeServer::handleChatSend, "chat");
        route("/mods", ClientProbeServer::handleMods, "core");
            route("/chat/tail", ClientProbeServer::handleChatTail, "chat_read");
            route("/block/sign", ClientProbeServer::handleBlockSign, "block_extra");
            route("/block/sign/edit", ClientProbeServer::handleBlockSignEdit, "block_extra");
            route("/block/command-block", ClientProbeServer::handleBlockCommandBlock, "block_extra");
            // ---- P4: 语义动作层（架构 §8.1）。路由由后端自描述派生，不手写第二份清单 ----
            for (String action : ACTION_BACKEND.actions()) {
                route("/action/" + action, ClientProbeServer::handleAction, "action");
            }
            executor = Executors.newFixedThreadPool(2);
            server.setExecutor(executor);
            server.start();
            ProbeLog.LOGGER.info("Mcdev Client Probe listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            ProbeLog.LOGGER.error("Failed to start Mcdev Client Probe", e);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 由平台入口在 start() 之前调用；共享库不判断加载器（架构 I1）。 */
    public static void setLoader(String name) {
        loader = name == null || name.isEmpty() ? "unknown" : name;
    }

    /** 注册一条路由并登记它提供的能力（架构 I3：能力由注册派生，禁止手写）。 */
    private static void route(String path, com.sun.net.httpserver.HttpHandler handler, String capability) {
        server.createContext(path, handler);
        CAPS.add(capability);
    }

    private static void handleConnect(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        // 地址既可以写在 query（?host=&port=），也可以写在 JSON body（{"host":..,"port":..}）。
        // 只认 query 曾让调用方以为能传 body：套件按 body 发 → 被静默忽略 → 连到默认 25566 → 永远进不了世界。
        // 两条通道都支持，body 优先（显式 > 默认）。
        String host = strQuery(ex, "host", "127.0.0.1");
        int port = intQuery(ex, "port", 25566);
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!raw.isEmpty()) {
            try {
                JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
                if (body.has("host") && !body.get("host").isJsonNull()) {
                    host = body.get("host").getAsString();
                }
                if (body.has("port") && !body.get("port").isJsonNull()) {
                    port = body.get("port").getAsInt();
                }
            } catch (RuntimeException e) {
                error(ex, 400, "invalid json body: " + e.getMessage());
                return;
            }
        }
        // lambda 只能捕获 final/effectively-final 的局部变量，而上面的 body 解析会改写 host/port
        final String targetHost = host;
        final int targetPort = port;
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("host", targetHost);
                d.put("port", targetPort);
                if (mc.player != null) {
                    d.put("alreadyInWorld", true);
                    return d;
                }
                ConnectScreen.startConnecting(new TitleScreen(), mc,
                        new ServerAddress(targetHost, targetPort),
                        new ServerData("mcdev", targetHost + ":" + targetPort, ServerData.Type.OTHER), false, null);
                d.put("connecting", true);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "connect failed: " + e.getMessage());
        }
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("side", "client");
                d.put("loader", loader);
                d.put("capabilities", new ArrayList<>(CAPS));
                d.put("actionBackend", ACTION_BACKEND.name());
                d.put("actions", new ArrayList<>(ACTION_BACKEND.actions()));
                // server-teleport 打的是哪个服务端探针（-Dmcdev.probe.server.url，默认 39001）
                d.put("serverProbeUrl", ClientActionBackend.serverProbeUrl());
                d.put("fps", mc.getFps());
                d.put("width", mc.getWindow().getGuiScaledWidth());
                d.put("height", mc.getWindow().getGuiScaledHeight());
                d.put("player", mc.player == null ? null : mc.player.getName().getString());
                d.put("playerPos", mc.player == null ? null : mc.player.position().toString());
                // 数值坐标：playerPos 是 Vec3.toString()，形如 "(53.5, 100.0, 8.0)"，
                // 按子串断言它得猜格式；分开给三个数，场景里就能直接断言 "playerPosX": "58.5"。
                if (mc.player != null) {
                    d.put("playerPosX", round2(mc.player.getX()));
                    d.put("playerPosY", round2(mc.player.getY()));
                    d.put("playerPosZ", round2(mc.player.getZ()));
                } else {
                    d.put("playerPosX", null);
                    d.put("playerPosY", null);
                    d.put("playerPosZ", null);
                }
                d.put("playerRot", mc.player == null ? null
                        : mc.player.getYRot() + "," + mc.player.getXRot());
                d.put("gamemode", mc.gameMode == null ? null : String.valueOf(mc.gameMode.getPlayerMode()));
                d.put("dimension", mc.level == null ? null
                        : mc.level.dimension().identifier().toString());
                // 连接状态：判断「被服务端踢了 / 还没连上」不能靠玩家名或坐标为 null
                // （在标题界面它们也是 null），给三个直接可断言的状态。
                //   linked  = 有 ClientPacketListener（连着某个服务端，哪怕还在下载地形）
                //   inWorld = 已进世界（level 与 player 都在）
                //   screen  = 当前界面类名（被踢会停在 DisconnectedScreen）
                d.put("linked", mc.getConnection() != null);
                d.put("inWorld", mc.level != null && mc.player != null);
                Screen screen = mc.gui.screen();
                d.put("screen", screen == null ? null : screen.getClass().getSimpleName());
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "status failed: " + e.getMessage());
        }
    }

    private static void handleLogTail(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int n = intQuery(ex, "n", 100);
        JsonObject data = new JsonObject();
        data.add("lines", ProbeLogs.tail(n));
        ok(ex, data);
    }

    /**
     * 读当前界面树。
     *
     * <p>P3 早期这里写过「26.3 移除了 Minecraft.screen，本端点无法实现」—— **那条结论是错的**，
     * 已在 2026-10-19 13:35 实测推翻：{@code Screen} 只是从 {@code Minecraft} 搬到了
     * {@code net.minecraft.client.gui.Gui}（{@code mc.gui.screen()}），五条 {@code /ui/*} 全部可用。
     * 现在本端点正常返回控件树，不再走 501 分支。
     */
    private static void handleUiSnapshot(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                Screen s = mc.gui.screen();
                d.put("overlay", mc.gui.overlay() == null
                        ? null : mc.gui.overlay().getClass().getSimpleName());
                if (s == null) {
                    d.put("screen", null);
                    d.put("title", null);
                    d.put("widgets", new ArrayList<>());
                    return d;
                }
                d.put("screen", s.getClass().getSimpleName());
                d.put("title", s.getTitle() == null ? null : s.getTitle().getString());
                d.put("pauseScreen", s.isPauseScreen());
                List<Map<String, Object>> widgets = new ArrayList<>();
                for (GuiEventListener child : s.children()) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("type", child.getClass().getSimpleName());
                    if (child instanceof AbstractWidget aw) {
                        w.put("x", aw.getX());
                        w.put("y", aw.getY());
                        w.put("w", aw.getWidth());
                        w.put("h", aw.getHeight());
                        w.put("label", aw.getMessage() == null ? null : aw.getMessage().getString());
                        w.put("active", aw.isActive());
                        w.put("hovered", aw.isHovered());
                    }
                    widgets.add(w);
                }
                d.put("widgets", widgets);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "ui/snapshot failed: " + e.getMessage());
        }
    }

    private static void handleUiClick(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        final double x;
        final double y;
        final int button;
        try {
            x = Double.parseDouble(requireQuery(ex, "x"));
            y = Double.parseDouble(requireQuery(ex, "y"));
            button = intQuery(ex, "button", 0);
        } catch (Exception e) {
            error(ex, 400, "x and y query params required (doubles)");
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Screen s = Minecraft.getInstance().gui.screen();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("screen", s == null ? null : s.getClass().getSimpleName());
                if (s == null) {
                    d.put("clicked", false);
                    d.put("reason", "no screen is showing");
                    return d;
                }
                s.mouseMoved(x, y);
                MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
                GuiEventListener hit = null;
                for (GuiEventListener child : s.children()) {
                    if (child.isMouseOver(x, y)) {
                        hit = child;
                        break;
                    }
                }
                d.put("hit", hit == null ? null : hit.getClass().getSimpleName());
                if (hit instanceof AbstractWidget aw) {
                    d.put("hitLabel", aw.getMessage() == null ? null : aw.getMessage().getString());
                }
                boolean handled = s.mouseClicked(ev, false);
                d.put("dispatched", handled);
                if (hit instanceof AbstractWidget aw) {
                    aw.mouseReleased(ev);
                    aw.onClick(ev, false);
                    handled = true;
                    d.put("activatedVia", "widget-onClick");
                } else if (handled) {
                    s.mouseReleased(ev);
                    d.put("activatedVia", "screen-dispatch");
                }
                d.put("clicked", handled);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "ui/click failed: " + e.getMessage());
        }
    }

    private static void handleUiHover(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        final double x;
        final double y;
        try {
            x = Double.parseDouble(requireQuery(ex, "x"));
            y = Double.parseDouble(requireQuery(ex, "y"));
        } catch (Exception e) {
            error(ex, 400, "x and y query params required (doubles)");
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Screen s = Minecraft.getInstance().gui.screen();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("screen", s == null ? null : s.getClass().getSimpleName());
                if (s == null) {
                    d.put("hovered", false);
                    d.put("reason", "no screen is showing");
                    return d;
                }
                s.mouseMoved(x, y);
                d.put("hovered", true);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "ui/hover failed: " + e.getMessage());
        }
    }

    private static void handleUiHoverSlot(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        final double x;
        final double y;
        try {
            x = Double.parseDouble(requireQuery(ex, "x"));
            y = Double.parseDouble(requireQuery(ex, "y"));
        } catch (Exception e) {
            error(ex, 400, "x and y query params required (doubles)");
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                Screen s = mc.gui.screen();
                d.put("screen", s == null ? null : s.getClass().getSimpleName());
                if (s instanceof AbstractContainerScreen<?> cs) {
                    d.put("container", true);
                    d.put("menu", cs.getMenu() == null
                            ? null : cs.getMenu().getClass().getSimpleName());
                    d.put("slots", cs.getMenu() == null ? 0 : cs.getMenu().slots.size());
                    s.mouseMoved(x, y);
                    d.put("hoverDispatched", true);
                    d.put("hoveredSlot",
                            "unavailable: AbstractContainerScreen.hoveredSlot is protected with no public accessor in 26.3");
                    return d;
                }
                d.put("container", false);
                s.mouseMoved(x, y);
                d.put("hoverDispatched", true);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "ui/hover_slot failed: " + e.getMessage());
        }
    }

    private static void handleUiClose(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screen s = mc.gui.screen();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("screen", s == null ? null : s.getClass().getSimpleName());
                if (s == null) {
                    d.put("closed", false);
                    d.put("reason", "no screen is showing");
                    return d;
                }
                s.onClose();
                d.put("closed", true);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "ui/close failed: " + e.getMessage());
        }
    }


    /**
     * 开一个单人世界：客户端自己起内置服务端。
     *
     * <p>为什么需要：专用服只能验证「装进服务端的那一半」。而「只在客户端生效的 mod」和「单机里的行为」
     * 只有单人世界能验——单人世界里内置服务端也会加载 {@code environment="*"} 的 mod，所以它证明的是
     * 「装在同一份客户端里时 mod 生效」，不等于「专用服没装这个 mod 时的表现」。
     *
     * <p>参数（query）：{@code name}（世界名，默认 {@code mcdev}）、{@code mode=normal|flat}
     * （flat 用 FLAT_ALL_DIMENSIONS 预设：平地、生成快，适合测试）、{@code gamemode=survival|creative|
     * adventure|spectator}（默认 creative）、{@code allowCommands=true|false}（默认 true）、
     * {@code seed}（可选；不给就随机）。世界名已存在时自动加 {@code -2}/{@code -3} 后缀，
     * 返回值 {@code renamed=true} 表示换过名字——调用方据此知道没有复用老世界目录。
     *
     * <p>返回后世界是异步加载的：用 {@code /status} 的 {@code inWorld} 轮询等它就绪。
     */
    /**
     * 打开「已有」世界时，26.3 会先弹 {@link BackupConfirmScreen}（备份再进 / 跳过直接进）。
     * 这是**通用界面**（不是某个 mod 的特性），但「要不要备份」属于使用者的选择，所以默认
     * {@code backup=ask} 什么都不点、只把当前界面报回去；要自动化的人显式传 {@code backup=skip}
     * （跳过备份直接进）或 {@code backup=do}（真做一次备份）。
     * 返回实际动作：no-screen / skipped / backed-up / still-blocked。
     */
    private static String waitBackupAndJoin(String policy) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            String clicked = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    return "world-ready";
                }
                Screen s = mc.gui.screen();
                if (!(s instanceof BackupConfirmScreen)) {
                    return null;
                }
                // 只有 BACKUP_AND_JOIN 是 public 常量；SKIP_AND_JOIN 是 private（javap 实测），
                // 所以「跳过」这一支按排除法找：BackupConfirmScreen 上除「备份」之外的那个按钮就是它。
                String backupLabel = BackupConfirmScreen.BACKUP_AND_JOIN.getString();
                for (GuiEventListener child : s.children()) {
                    if (child instanceof Button btn && btn.getMessage() != null) {
                        boolean isBackupButton = backupLabel.equals(btn.getMessage().getString());
                        boolean wanted = "skip".equals(policy) ? !isBackupButton : isBackupButton;
                        if (!wanted) {
                            continue;
                        }
                        double cx = btn.getX() + btn.getWidth() / 2.0;
                        double cy = btn.getY() + btn.getHeight() / 2.0;
                        s.mouseMoved(cx, cy);
                        btn.onClick(new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0)), false);
                        return btn.getMessage().getString();
                    }
                }
                return null;
            });
            if ("world-ready".equals(clicked)) {
                return "no-screen";
            }
            if (clicked != null) {
                return "skip".equals(policy) ? "skipped" : "backed-up";
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "still-blocked";
    }

    private static void handleWorldSingleplayer(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            String requested = strQuery(ex, "name", "mcdev");
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("name must not be empty");
            }
            String mode = strQuery(ex, "mode", "normal").toLowerCase(Locale.ROOT);
            if (!mode.equals("normal") && !mode.equals("flat")) {
                throw new IllegalArgumentException("mode must be 'normal' or 'flat', got '" + mode + "'");
            }
            String gamemode = strQuery(ex, "gamemode", "creative").toLowerCase(Locale.ROOT);
            GameType gameType = switch (gamemode) {
                case "survival" -> GameType.SURVIVAL;
                case "creative" -> GameType.CREATIVE;
                case "adventure" -> GameType.ADVENTURE;
                case "spectator" -> GameType.SPECTATOR;
                default -> throw new IllegalArgumentException("unknown gamemode '" + gamemode + "'");
            };
            boolean allowCommands = !"false".equalsIgnoreCase(strQuery(ex, "allowCommands", "true"));
            boolean reuse = "true".equalsIgnoreCase(strQuery(ex, "reuse", "false"));
            // 打开已有世界时会先弹 BackupConfirmScreen。要不要备份是使用者的决定，默认不碰。
            String backup = strQuery(ex, "backup", "ask").toLowerCase(Locale.ROOT);
            if (!backup.equals("ask") && !backup.equals("skip") && !backup.equals("do")) {
                throw new IllegalArgumentException("backup must be 'ask', 'skip' or 'do', got '" + backup + "'");
            }
            String seedRaw = strQuery(ex, "seed", "");
            boolean hasSeed = seedRaw != null && !seedRaw.isEmpty();
            long seed = 0L;
            if (hasSeed) {
                try {
                    seed = Long.parseLong(seedRaw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("seed must be a number, got '" + seedRaw + "'");
                }
            }
            final boolean useSeed = hasSeed;
            final long seedValue = seed;
            final String modeValue = mode;
            final String gamemodeValue = gamemode;
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    throw new IllegalStateException("already in a world (" + mc.level.dimension().identifier() + ")");
                }
                // 世界目录是 <gamedir>/saves/<levelId>（LevelStorageSource.createDefault 要的是 saves 这一层，
                // 传 gamedir 的话 levelExists 永远为 false，撞名时会给出错误的 levelId）。
                LevelStorageSource storage = LevelStorageSource.createDefault(mc.gameDirectory.toPath().resolve("saves"));
                // reuse=true 时优先打开同名的老世界（同一份测试数据反复用，不在 saves 里越堆越多）；
                // 没有才新建。新建撞名时自动加 -2/-3 后缀。
                boolean openedExisting = reuse && storage.levelExists(requested);
                String levelId = requested;
                if (!openedExisting) {
                    for (int i = 2; storage.levelExists(levelId) || !storage.isNewLevelIdAcceptable(levelId); i++) {
                        if (i > 999) {
                            throw new IllegalStateException("cannot find a free world id derived from '" + requested + "'");
                        }
                        levelId = requested + "-" + i;
                    }
                }
                final String openId = levelId;
                WorldOptions options = null;
                if (openedExisting) {
                    mc.createWorldOpenFlows().openWorld(openId, () -> ProbeLog.LOGGER.warn("mcdev probe: opening world '{}' failed", openId));
                } else {
                    LevelSettings settings = new LevelSettings(levelId, gameType,
                            LevelSettings.DifficultySettings.DEFAULT, allowCommands, WorldDataConfiguration.DEFAULT);
                    if (useSeed) {
                        options = new WorldOptions(seedValue, !modeValue.equals("flat"), false);
                    } else {
                        options = modeValue.equals("flat")
                                ? WorldOptions.testWorldWithRandomSeed()
                                : WorldOptions.defaultWithRandomSeed();
                    }
                    Function<HolderLookup.Provider, WorldDimensions> dimensions = modeValue.equals("flat")
                            ? WorldPresets::createTestWorldDimensions
                            : WorldPresets::createNormalWorldDimensions;
                    mc.createWorldOpenFlows().createFreshLevel(levelId, settings, options, dimensions, null);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("levelId", levelId);
                d.put("openedExisting", openedExisting);
                d.put("renamed", !levelId.equals(requested));
                d.put("mode", modeValue);
                d.put("gamemode", gamemodeValue);
                d.put("allowCommands", allowCommands);
                d.put("seed", options == null ? null : options.seed());
                d.put("worldDir", storage.getLevelPath(levelId).toString());
                d.put("backup", backup);
                return d;
            });
            // 只有「打开已有世界 + 调用方明确要求」时才去处理备份确认界面；
            // 新建世界不会弹这个界面，那就别白等。
            boolean openedExisting = Boolean.TRUE.equals(data.get("openedExisting"));
            if (openedExisting && !"ask".equals(backup)) {
                data.put("backupAction", waitBackupAndJoin(backup));
            } else {
                data.put("backupAction", "ask");
            }
            ok(ex, data);
        } catch (IllegalStateException e) {
            error(ex, 409, "world/singleplayer failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            error(ex, 400, "world/singleplayer failed: " + e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "world/singleplayer failed: " + e);
        }
    }

    /** 离开当前世界回到标题界面（单人世界会先存档）。用于一条场景里连开多个世界。 */
    private static void handleWorldLeave(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.level == null) {
                    d.put("left", false);
                    d.put("reason", "not in a world");
                    return d;
                }
                String was = mc.level.dimension().identifier().toString();
                mc.disconnectWithProgressScreen();
                d.put("left", true);
                d.put("was", was);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "world/leave failed: " + e);
        }
    }

    private static void handlePlayerInventory(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null) {
                    d.put("opened", false);
                    d.put("error", "player not in world");
                    return d;
                }
                var menu = mc.player.containerMenu;
                d.put("opened", true);
                d.put("menu", menu.getClass().getSimpleName());
                d.put("slotCount", menu.slots.size());
                // 槽位坐标口径：x / y 是「容器内相对坐标」（Slot.x / Slot.y，public final）。
                // 绝对屏幕坐标 = 相对坐标 + 容器原点（leftPos / topPos），原点在 26.3 是
                // protected 且无公开 accessor，暂不提供。将来若引入 access widener，追加
                // screenX / screenY 两个字段，绝不改写 x / y 的含义。
                List<Map<String, Object>> slots = new ArrayList<>();
                for (int i = 0; i < menu.slots.size(); i++) {
                    var slot = menu.slots.get(i);
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("index", slot.index);
                    s.put("containerSlot", slot.getContainerSlot());
                    s.put("x", slot.x);
                    s.put("y", slot.y);
                    s.put("active", slot.isActive());
                    var stack = slot.getItem();
                    if (stack == null || stack.isEmpty()) {
                        s.put("item", null);
                    } else {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                        item.put("count", stack.getCount());
                        s.put("item", item);
                    }
                    slots.add(s);
                }
                d.put("slots", slots);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "open inventory failed: " + e.getMessage());
        }
    }

    private static void handleCommand(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cmd;
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            cmd = obj.get("cmd").getAsString();
        } catch (Exception e) {
            error(ex, 400, "invalid json: " + e.getMessage());
            return;
        }
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        final String command = cmd;
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null) {
                    d.put("sent", false);
                    d.put("reason", "no player");
                    return d;
                }
                mc.player.connection.sendCommand(command);
                d.put("sent", true);
                d.put("cmd", command);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "command failed: " + e.getMessage());
        }
    }

    private static void handlePlayerUse(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final int x;
        final int y;
        final int z;
        String faceName = "up";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            x = obj.get("x").getAsInt();
            y = obj.get("y").getAsInt();
            z = obj.get("z").getAsInt();
            if (obj.has("face")) {
                faceName = obj.get("face").getAsString();
            }
        } catch (Exception e) {
            error(ex, 400, "invalid json: " + e.getMessage());
            return;
        }
        Direction parsed = Direction.byName(faceName);
        final Direction face = parsed == null ? Direction.UP : parsed;
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null) {
                    d.put("used", false);
                    d.put("reason", "no player");
                    return d;
                }
                BlockPos pos = new BlockPos(x, y, z);
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(pos), face, pos, false);
                InteractionResult result = mc.gameMode.useItemOn(
                        mc.player, InteractionHand.MAIN_HAND, hit);
                d.put("used", true);
                d.put("result", result.toString());
                d.put("pos", x + "," + y + "," + z);
                d.put("face", face.getName());
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "use failed: " + e.getMessage());
        }
    }

    private static void handlePlayerUseItem(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null) {
                    d.put("used", false);
                    d.put("reason", "no player");
                    return d;
                }
                InteractionResult result = mc.gameMode.useItem(
                        mc.player, InteractionHand.MAIN_HAND);
                d.put("used", true);
                d.put("result", result.toString());
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "use item failed: " + e.getMessage());
        }
    }

    private static void handlePlayerBreak(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null || mc.level == null || mc.gameMode == null) {
                    d.put("ok", false);
                    d.put("error", "player not in world");
                    return d;
                }
                BlockPos pos = new BlockPos(x, y, z);
                d.put("pos", x + "," + y + "," + z);
                d.put("before", String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock())));
                d.put("started", mc.gameMode.startDestroyBlock(pos, Direction.UP));
                boolean continued = false;
                for (int i = 0; i < 10; i++) {
                    continued = mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                }
                mc.gameMode.stopDestroyBlock();
                d.put("continued", continued);
                d.put("after", String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock())));
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "player break failed: " + e.getMessage());
        }
    }

    private static void handlePlayerMine(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        int timeoutMs = Math.min(15000, Math.max(1000, intQuery(ex, "timeoutMs", 6000)));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            error(ex, 409, "player not in world");
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pos", x + "," + y + "," + z);
        data.put("before", String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock())));
        data.put("connection", mc.getConnection() != null);
        if (mc.getConnection() != null) {
            mc.execute(() -> mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    pos, Direction.UP, 0)));
        }
        boolean broken = false;
        int ticks = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (mc.level.getBlockState(pos).isAir()) {
                    broken = true;
                    break;
                }
                ticks += 1;
                Thread.sleep(50L);
            }
            if (mc.getConnection() != null) {
                mc.execute(() -> mc.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        pos, Direction.UP, 0)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        data.put("ticks", ticks);
        data.put("broken", broken);
        data.put("playerCreative", mc.player.isCreative());
        data.put("localMode", mc.gameMode.getPlayerMode().getName());
        data.put("hitType", mc.hitResult == null ? null : mc.hitResult.getType().name());
        data.put("hitPos", mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos().toShortString() : null);
        data.put("playerPos", mc.player.blockPosition().toShortString());
        data.put("look", mc.player.getYRot() + "/" + mc.player.getXRot());
        data.put("after", String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock())));
        ok(ex, data);
    }

    private static void handleMainhandItem(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                if (mc.player == null) {
                    d.put("found", false);
                    return d;
                }
                ItemStack stack = mc.player.getMainHandItem();
                d.put("found", true);
                d.put("item", stack.isEmpty() ? null
                        : String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                d.put("count", stack.getCount());
                d.put("barVisible", stack.isBarVisible());
                d.put("barWidth", stack.getBarWidth());
                d.put("barColor", stack.getBarColor());
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "mainhand item query failed: " + e.getMessage());
        }
    }

    /**
     * {@code GET /entity/list?r=32} —— 列出玩家附近的实体。
     *
     * <p>这不是动作层本身，而是它的前置条件：{@code /action/attack}、{@code /action/spectate}、
     * {@code /action/use target=entity} 只认实体数值 id，而客户端没有其他公开的「按类型找实体」手段，
     * 测试就没法构造参数。补这一条比让调用方去猜 id 便宜。
     */
    private static void handleEntityList(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            int radius = intQuery(ex, "r", 32);
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Map<String, Object> d = new LinkedHashMap<>();
                List<Map<String, Object>> list = new ArrayList<>();
                d.put("radius", radius);
                if (mc.level == null || mc.player == null) {
                    d.put("inWorld", false);
                    d.put("count", 0);
                    d.put("entities", list);
                    return d;
                }
                Vec3 origin = mc.player.position();
                int scanned = 0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    scanned++;
                    if (e == mc.player) {
                        continue;
                    }
                    double distance = e.position().distanceTo(origin);
                    if (distance > radius) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("type", e.getType().toString());
                    m.put("uuid", e.getUUID().toString());
                    m.put("distance", Math.round(distance * 100.0) / 100.0);
                    m.put("pos", entityPos(e));
                    list.add(m);
                }
                d.put("inWorld", true);
                d.put("scanned", scanned);
                d.put("count", list.size());
                d.put("entities", list);
                return d;
            });
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "entity list failed: " + e.getMessage());
        }
    }

    /** 实体坐标 → JSON 友好的 map（{@code /entity/list} 用）。 */
    private static Map<String, Object> entityPos(Entity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", Math.round(e.getX() * 100.0) / 100.0);
        m.put("y", Math.round(e.getY() * 100.0) / 100.0);
        m.put("z", Math.round(e.getZ() * 100.0) / 100.0);
        return m;
    }

    /** 保留两位小数：坐标断言不该受浮点尾巴影响。 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
    private static void handleScreenshot(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            final long t0 = System.currentTimeMillis();
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screenshot.grab(mc, true);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("dir", new File(mc.gameDirectory, "screenshots").getAbsolutePath());
                d.put("width", mc.getWindow().getWidth());
                d.put("height", mc.getWindow().getHeight());
                return d;
            });
            File shotDir = new File((String) data.remove("dir"));
            File newest = null;
            for (int i = 0; i < 40; i++) {
                File[] files = shotDir.listFiles((dd, nm) -> nm.endsWith(".png"));
                if (files != null) {
                    for (File f : files) {
                        if (f.lastModified() >= t0 - 2000L
                                && (newest == null || f.lastModified() > newest.lastModified())) {
                            newest = f;
                        }
                    }
                }
                if (newest != null) {
                    break;
                }
                Thread.sleep(50);
            }
            // 截图文件是异步落盘的：刚拿到文件后有一小段时间 length() 还是 0（实测 01-in-world 抓到 0 字节）。
            // 等到「非空且大小连续两次相同」再返回，否则取证目录里会留下空图——空图比没图更糟（看起来像证据）。
            if (newest != null) {
                long last = -1L;
                for (int i = 0; i < 60; i++) {
                    long len = newest.length();
                    if (len > 0 && len == last) {
                        break;
                    }
                    last = len;
                    Thread.sleep(50);
                }
            }
            data.put("file", newest == null ? null : newest.getName());
            data.put("path", newest == null ? null : newest.getAbsolutePath());
            data.put("exists", newest != null);
            data.put("size", newest == null ? 0L : newest.length());
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "screenshot failed: " + e.getMessage());
        }
    }

    private static void handleQuit(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        ok(ex, Map.of("quitting", true));
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    /**
     * {@code POST /chat/send} —— 以本地玩家身份发一条聊天或命令。
     *
     * <p>为什么要单独一条路由：`/command` 走的是「客户端命令树」（单机/内嵌服），
     * 连到专用服时那条路不产生任何 serverbound 包；真正的聊天/命令要靠
     * {@link net.minecraft.client.multiplayer.ClientPacketListener#sendChat} /
     * {@code sendCommand}，它们分别发 {@code ServerboundChatPacket} 与
     * {@code ServerboundChatCommandPacket}。</p>
     *
     * <p>请求体 {@code {"message":"..."}}；以 {@code /} 开头按命令处理（去掉斜杠再发）。
     * 注意：聊天有服务端限速（默认 20 秒内的 spam 阈值），连续发会被静默丢弃 —— 这是
     * 服务端行为，不在这里兜底。</p>
     */
    /** 已加载 mod 清单；{@code source=loader} 表示真的从加载器读到了，{@code none} 表示没注册 provider。 */
    private static void handleMods(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        List<ModCatalog.Entry> mods = ModCatalog.list();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("side", "client");
        data.put("loader", loader);
        data.put("source", ModCatalog.hasProvider() ? "loader" : "none");
        data.put("count", mods.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ModCatalog.Entry m : mods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("version", m.version());
            row.put("name", m.name());
            rows.add(row);
            ids.add(m.id());
        }
        data.put("mods", rows);
        // 一行 id 便于 expect.contains 断言，不必数数组下标。
        data.put("ids", String.join(",", ids));
        ok(ex, data);
    }
    private static void handleChatSend(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        try {
            JsonObject body = readBody(ex);
            if (!body.has("message") || !body.get("message").isJsonPrimitive()) {
                error(ex, 400, "missing field: message");
                return;
            }
            String text = body.get("message").getAsString();
            if (text.isBlank()) {
                error(ex, 400, "message must not be blank");
                return;
            }
            boolean command = text.startsWith("/");
            String payload = command ? text.substring(1) : text;
            String via = onClient(() -> {
                var listener = Minecraft.getInstance().getConnection();
                if (listener == null) {
                    throw new IllegalStateException("client is not connected to a server");
                }
                if (command) {
                    listener.sendCommand(payload);
                    return "command";
                }
                listener.sendChat(payload);
                return "chat";
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sent", payload);
            data.put("via", via);
            data.put("packets", command ? "ServerboundChatCommandPacket" : "ServerboundChatPacket");
            ok(ex, data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(ex, 400, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "chat send failed: " + e);
        }
    }

    /**
     * {@code GET /chat/tail?n=30} —— 客户端**当前看到**的系统消息与 actionbar 文本。
     *
     * <p>为什么需要这条：服务端探针能证明「服务端发了什么」（{@code /log/tail} 里的
     * {@code System chat:}），但证明不了「玩家屏幕上确实出现了」——聊天被客户端过滤、
     * actionbar 只存在于客户端，这两件事服务端日志里都看不出来。这里读的是客户端 GUI
     * 的真状态，不缓冲、不重放，所以天然通用：任何 mod 往聊天/actionbar 写东西都断言得到。</p>
     *
     * <p>返回字段：{@code actionbar} 当前可见的 actionbar 文本（空串=没有）、
     * {@code actionbarSource} 取到它的反射路径（{@code by-name}/{@code by-order}，
     * 出问题时用来区分「没写」和「读错了字段」）、{@code count} 聊天历史条数、
     * {@code last} 最后一条、{@code lines} 最后 n 条（旧→新）、{@code text}
     * 把 lines 用换行连起来（场景里断言子串用这个最省事）。</p>
     *
     * <p>为什么不缓存成事件流：缓存要按加载器注册事件（三家 API 各不相同），而读当前状态
     * 只需反射同一组字段，三加载器共用一份代码；时序问题交给场景 DSL 的 {@code retry} 等。</p>
     */
    private static void handleChatTail(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int n = intQuery(ex, "n", 30);
        if (n <= 0) {
            error(ex, 400, "n must be positive");
            return;
        }
        try {
            Map<String, Object> data = onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gui == null || mc.gui.hud == null) {
                    throw new IllegalStateException("client GUI is not ready");
                }
                List<String> lines = readChatLines(mc.gui.hud.getChat());
                int from = Math.max(0, lines.size() - n);
                List<String> tail = new ArrayList<>(lines.subList(from, lines.size()));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("count", lines.size());
                d.put("lines", tail);
                d.put("last", tail.isEmpty() ? "" : tail.get(tail.size() - 1));
                d.put("text", String.join("\n", tail));
                Object[] overlay = readActionbar(mc.gui.hud);
                d.put("actionbar", overlay[0]);
                d.put("actionbarSource", overlay[1]);
                return d;
            });
            ok(ex, data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(ex, 400, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "chat tail failed: " + e);
        }
    }

    // ---- 聊天 / actionbar 的读取（{@link #handleChatTail} 专用）----
    // 两个字段都没有公开 getter，只能反射；缓存 Field 是因为每次读都 getDeclaredFields 太浪费。

    private static Field chatListField;
    private static Field actionbarField;
    private static String actionbarSource = "unknown";

    private static List<String> readChatLines(Object chatComponent) {
        if (chatListField == null) {
            chatListField = findChatListField(chatComponent.getClass());
        }
        Object raw;
        try {
            raw = chatListField.get(chatComponent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read chat history: " + e);
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof GuiMessage message) {
                    out.add(message.content().getString());
                }
            }
        }
        return out;
    }

    /**
     * 先按官方字段名 {@code allMessages}（Forge/NeoForge 运行时是官方名），找不到再按泛型参数
     * 为 {@link GuiMessage} 的 {@code List} 字段匹配 —— Fabric 运行时字段名被重映射成
     * {@code field_xxxx}，但**类型**会被一起重映射，所以按类型找是稳的。
     */
    private static Field findChatListField(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getName().equals("allMessages")) {
                f.setAccessible(true);
                return f;
            }
        }
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            if (f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getActualTypeArguments()[0] instanceof Class<?> arg
                    && GuiMessage.class.isAssignableFrom(arg)) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new IllegalStateException(
                "cannot find the chat history field on " + type.getName() + " (game version changed?)");
    }

    /** 返回 {@code {文本, 取到的路径}}：路径写进响应，读错字段时看得见，而不是静默返回空串。 */
    private static Object[] readActionbar(net.minecraft.client.gui.Hud hud) {
        if (actionbarField == null) {
            try {
                Field byName = hud.getClass().getDeclaredField("overlayMessageString");
                byName.setAccessible(true);
                actionbarField = byName;
                actionbarSource = "by-name";
            } catch (NoSuchFieldException e) {
                actionbarField = firstComponentField(hud.getClass());
                actionbarSource = "by-order";
            }
        }
        try {
            Object value = actionbarField.get(hud);
            String text = value instanceof Component c ? c.getString() : "";
            return new Object[]{text, actionbarSource};
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the action bar: " + e);
        }
    }

    /**
     * 按声明顺序取第一个非 static 的 {@link Component} 字段。{@code Hud} 里它依次是
     * actionbar、title、subtitle —— 重映射只改名，不改声明顺序。
     */
    private static Field firstComponentField(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == Component.class) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new IllegalStateException(
                "cannot find a Component field on " + type.getName() + " (game version changed?)");
    }

    /**
     * {@code GET /block/sign?x&y&z&side=front|back} —— 读告示牌文字（客户端视角）。
     *
     * <p>客户端读的是 {@link SignBlockEntity} 的方块实体数据，只有区块已加载时才有；
     * 权威读法在服务端探针的同名路由。`side` 默认 `front`。</p>
     */
    private static void handleBlockSign(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            BlockPos pos = requireBlockPosQuery(ex);
            String side = strQuery(ex, "side", "front");
            SignTextSlot slot = "back".equalsIgnoreCase(side) ? SignTextSlot.BACK : SignTextSlot.FRONT;
            Map<String, Object> data = onClient(() -> {
                if (Minecraft.getInstance().level == null) {
                    throw new IllegalStateException("client is not in a world");
                }
                BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
                if (!(be instanceof SignBlockEntity sign)) {
                    throw new IllegalStateException("no sign at " + coordLine(pos) + " (block entity: "
                            + (be == null ? "none" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())) + ")");
                }
                return describeSign(sign, slot);
            });
            ok(ex, data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(ex, 404, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "sign read failed: " + e);
        }
    }

    /**
     * {@code POST /block/sign/edit} —— 发 {@code ServerboundSignUpdatePacket} 改告示牌。
     *
     * <p>客户端不能直接改方块实体（那只有服务端能做），真实路径就是发这个包；服务端会校验
     * 玩家距离、是否被蜡封、是否有人在编辑，不满足时**静默忽略**。所以这里只报「包已发出」，
     * 要验证是否真写进去必须用服务端 `/block/sign` 回读 —— 响应里的 {@code hint} 写了这句。</p>
     *
     * <p>请求体：{@code {"pos":[x,y,z],"lines":["a","b","c","d"],"side":"front"}}；
     * lines 不足 4 行补空串，超过 4 行报 400。</p>
     */
    private static void handleBlockSignEdit(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        try {
            JsonObject body = readBody(ex);
            BlockPos pos = posFromBody(body);
            List<String> lines = new ArrayList<>();
            if (body.has("lines") && body.get("lines").isJsonArray()) {
                for (var element : body.getAsJsonArray("lines")) {
                    lines.add(element.getAsString());
                }
            }
            if (lines.size() > 4) {
                error(ex, 400, "lines must have at most 4 entries, got " + lines.size());
                return;
            }
            while (lines.size() < 4) {
                lines.add("");
            }
            String side = body.has("side") ? body.get("side").getAsString() : "front";
            SignTextSlot slot = "back".equalsIgnoreCase(side) ? SignTextSlot.BACK : SignTextSlot.FRONT;
            List<String> payload = List.copyOf(lines);
            // 26.3 的服务端只在「这是一次自己发起的编辑」时才接受 ServerboundSignUpdatePacket：
            // SignBlockEntity.updateSignText 的第一道守卫是 getPlayerWhoMayEdit() == player.getUUID()，
            // 而那个 UUID 只有玩家真的右键点过牌子（服务端跑 SignBlock.useWithoutItem → openTextEdit）才会被写上。
            // 所以这里先模拟一次右键（走 MultiPlayerGameMode.useItemOn，和真人完全同一条路径），
            // 等一个 tick 让 open-editor 往返完成，再发文本包。不做这一步的话，包会被服务端静默丢弃，
            // 只在日志留一行 "Player ... just tried to change non-editable sign"。
            boolean prepared = false;
            try {
                onClient(() -> {
                    var player = Minecraft.getInstance().player;
                    if (player == null) {
                        throw new IllegalStateException("client is not in a world");
                    }
                    var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                    return null;
                });
                prepared = true;
                Thread.sleep(250L);
            } catch (Exception ignored) {
                // 预处理失败不致命：包照样发，服务端会拒绝，回读路由能看出真相。
            }
            boolean[] screenClosed = {false};
            onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                var player = mc.player;
                if (player == null) {
                    throw new IllegalStateException("client is not in a world");
                }
                player.connection.send(new ServerboundSignUpdatePacket(pos, payload, slot));
                // 发完文本包必须把编辑界面关掉：SignEditScreen 是客户端真开了的界面，
                // 留它开着会让后续动作全部失真（isControlledCamera() 为假 ⇒ walk 一步不动；
                // 读方块/实体也落在界面上下文里）。第一版没关，导致下一个会话里
                // 整套 25 条用例里 9 条 FAIL，而 /ui/snapshot 一眼就能看到 screen=SignEditScreen。
                if (mc.gui.screen() != null) {
                    mc.setScreenAndShow(null);
                    screenClosed[0] = true;
                }
                return null;
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sent", true);
            data.put("prepared", prepared);
            data.put("screenClosed", screenClosed[0]);
            data.put("pos", coordLine(pos));
            data.put("side", slot.name().toLowerCase(java.util.Locale.ROOT));
            data.put("lines", payload);
            data.put("packets", "ServerboundUseItemOnPacket(open editor), ServerboundSignUpdatePacket");
            data.put("hint", "read it back with the server probe's GET /block/sign; a rejected edit "
                    + "leaves the old text and logs 'just tried to change non-editable sign'");
            ok(ex, data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(ex, 400, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "sign edit failed: " + e);
        }
    }

    /**
     * {@code GET /block/command-block?x&y&z} —— 读命令方块状态（客户端视角，只读）。
     *
     * <p>写命令只能由服务端做（`BaseCommandBlock.setCommand`），所以写入口在服务端探针的
     * 同名路由 {@code POST /block/command-block}。</p>
     */
    private static void handleBlockCommandBlock(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        try {
            BlockPos pos = requireBlockPosQuery(ex);
            Map<String, Object> data = onClient(() -> {
                if (Minecraft.getInstance().level == null) {
                    throw new IllegalStateException("client is not in a world");
                }
                BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
                if (!(be instanceof CommandBlockEntity commandBlock)) {
                    throw new IllegalStateException("no command block at " + coordLine(pos)
                            + " (block entity: " + (be == null ? "none"
                                    : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())) + ")");
                }
                return describeCommandBlock(commandBlock);
            });
            ok(ex, data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(ex, 404, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "command block read failed: " + e);
        }
    }

    /** 告示牌文字 → JSON（客户端与服务端两个探针共用同一份形状）。 */
    private static Map<String, Object> describeSign(SignBlockEntity sign, SignTextSlot slot) {
        SignText text = sign.getText(slot);
        List<String> lines = new ArrayList<>();
        for (var component : text.getMessages(false)) {
            lines.add(component.getString());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pos", coordLine(sign.getBlockPos()));
        data.put("side", slot.name().toLowerCase(java.util.Locale.ROOT));
        data.put("lines", lines);
        data.put("waxed", sign.isWaxed());
        data.put("glowing", text.hasGlowingText());
        data.put("color", text.getColor().getSerializedName());
        data.put("hasMessage", text.hasMessage(false));
        return data;
    }

    /** 命令方块状态 → JSON（只读字段；写入口在服务端探针）。 */
    private static Map<String, Object> describeCommandBlock(CommandBlockEntity commandBlock) {
        BaseCommandBlock base = commandBlock.getCommandBlock();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pos", coordLine(commandBlock.getBlockPos()));
        data.put("command", base.getCommand());
        data.put("mode", commandBlock.getMode().name().toLowerCase(java.util.Locale.ROOT));
        data.put("automatic", commandBlock.isAutomatic());
        data.put("conditional", commandBlock.isConditional());
        data.put("powered", commandBlock.isPowered());
        data.put("trackOutput", base.isTrackOutput());
        data.put("successCount", base.getSuccessCount());
        var lastOutput = base.getLastOutput();
        data.put("lastOutput", lastOutput == null ? null : lastOutput.getString());
        return data;
    }

    /** 读请求体为 JSON 对象；空体当 `{}`，非对象/坏 JSON 抛 IllegalArgumentException（→ 400）。 */
    private static JsonObject readBody(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return new JsonObject();
        }
        try {
            var parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid json body: " + e.getMessage());
        }
    }

    /** 从 query 取 x/y/z（必须齐全），缺一个就 400。 */
    private static BlockPos requireBlockPosQuery(HttpExchange ex) {
        int x = intQuery(ex, "x", Integer.MIN_VALUE);
        int y = intQuery(ex, "y", Integer.MIN_VALUE);
        int z = intQuery(ex, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("missing query param: x/y/z are all required");
        }
        return new BlockPos(x, y, z);
    }

    /** 从请求体取坐标：接受 `{"pos":[x,y,z]}`、`{"pos":{"x":..}}`、`{"x":..,"y":..,"z":..}`。 */
    private static BlockPos posFromBody(JsonObject body) {
        int[] xyz = new int[3];
        if (body.has("pos")) {
            var pos = body.get("pos");
            if (pos.isJsonArray() && pos.getAsJsonArray().size() == 3) {
                for (int i = 0; i < 3; i++) {
                    xyz[i] = pos.getAsJsonArray().get(i).getAsInt();
                }
            } else if (pos.isJsonObject()) {
                xyz[0] = pos.getAsJsonObject().get("x").getAsInt();
                xyz[1] = pos.getAsJsonObject().get("y").getAsInt();
                xyz[2] = pos.getAsJsonObject().get("z").getAsInt();
            } else {
                throw new IllegalArgumentException("field pos must be [x,y,z] or {x,y,z}");
            }
        } else if (body.has("x") && body.has("y") && body.has("z")) {
            xyz[0] = body.get("x").getAsInt();
            xyz[1] = body.get("y").getAsInt();
            xyz[2] = body.get("z").getAsInt();
        } else {
            throw new IllegalArgumentException("missing field: pos (or x/y/z)");
        }
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }

    /** `x, y, z` 一行文本；日志与错误信息里统一用它，方便和 Minecraft 聊天行对照。 */
    private static String coordLine(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * {@code POST /action/{name}} —— 语义动作层的唯一入口（架构 §8.1）。
     *
     * <p>把动作名从 URL 尾巴取出来写进请求体，交给 {@link ClientActionBackend} 分派：
     * 一条路由处理器 + 一份后端，比 12 个几乎相同的处理器短，也不会出现「某条路由忘了
     * 做 gamemode 校验」这类漂移。</p>
     *
     * <p>动作是阻塞的（走路要等 600 ms、挖方块最多等 5 s），但这里跑在 HttpServer 的
     * 工作线程上，不占客户端主线程 —— 见 {@link ClientActionBackend} 的线程模型说明。</p>
     */
    private static void handleAction(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String action = path.substring(path.lastIndexOf('/') + 1);
        try {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = new JsonObject();
            if (!raw.isBlank()) {
                com.google.gson.JsonElement parsed = JsonParser.parseString(raw);
                if (!parsed.isJsonObject()) {
                    error(ex, 400, "request body must be a JSON object");
                    return;
                }
                body = parsed.getAsJsonObject();
            }
            body.addProperty("action", action);
            ok(ex, ACTION_BACKEND.execute(new ActionRequest(body)));
        } catch (ActionException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", e.code().name());
            err.addProperty("message", e.getMessage());
            if (e.detail() != null) {
                err.add("detail", e.detail());
            }
            JsonObject body = new JsonObject();
            body.addProperty("ok", false);
            body.add("error", err);
            send(ex, e.code().httpStatus(), body);
        } catch (Exception e) {
            error(ex, 500, "action '" + action + "' failed: " + e);
        }
    }

    private static <T> T onClient(Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // 把游戏线程里抛的运行时异常原样抛回来：各 handler 的 catch (IllegalStateException) /
            // catch (IllegalArgumentException) 才能映射成 404/400。不拆包的话这些异常会被
            // 通用 catch 吞成 500，同一类错误在两端返回不同状态码（服务端 404、客户端 500）。
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static boolean authorized(HttpExchange ex) throws IOException {
        if (token.isEmpty()) {
            return true;
        }
        String got = ex.getRequestHeaders().getFirst("X-Mcdev-Token");
        if (token.equals(got)) {
            return true;
        }
        error(ex, 401, "invalid token");
        return false;
    }

    private static void ok(HttpExchange ex, Object data) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("data", GSON.toJsonTree(data));
        send(ex, 200, root);
    }

    private static void error(HttpExchange ex, int code, String message) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        JsonObject err = new JsonObject();
        err.addProperty("message", message);
        root.add("error", err);
        send(ex, code, root);
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int intQuery(HttpExchange ex, String key, int def) {
        String raw = strQuery(ex, key, null);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String requireQuery(HttpExchange ex, String key) {
        String raw = strQuery(ex, key, null);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("missing query param: " + key);
        }
        return raw;
    }

    private static String strQuery(HttpExchange ex, String key, String def) {
        String query = ex.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return def;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return def;
    }
}
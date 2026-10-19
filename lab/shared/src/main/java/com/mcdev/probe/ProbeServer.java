package com.mcdev.probe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcdev.probe.action.ActionException;
import com.mcdev.probe.action.ActionRequest;
import com.mcdev.probe.action.ServerActionBackend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ProbeServer {
    private static final Gson GSON = new Gson();
    private static HttpServer server;
    private static ExecutorService executor;
    private static MinecraftServer mc;
    private static String token = "mcdev";
    /**
     * 加载器标识（"fabric" / "forge" / "neoforge"）。
     *
     * <p>共享库不能自己判断这个 —— 判断它需要加载器 API（违反架构 §I1）。所以由
     * {@code platform/<loader>/} 的入口在 {@link #start} 之后调 {@link #setLoader} 告知。
     * 用途：DSH 插件层确认「连上的是不是我以为的那份 jar」，而不是靠端口猜。
     */
    private static String loader = "unknown";
    private static final java.util.LinkedHashSet<String> CAPS = new java.util.LinkedHashSet<>();
    /** 服务端侧的语义动作后端；{@link #start} 里创建，{@link #stop} 里置空。 */
    private static ServerActionBackend actionBackend;

    private ProbeServer() {
    }

    public static synchronized void start(MinecraftServer serverIn, int port, String tokenIn) {
        if (server != null) {
            return;
        }
        mc = serverIn;
        token = tokenIn == null ? "" : tokenIn;
        CAPS.clear();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            route("/status", ProbeServer::handleStatus, "core");
            route("/log/tail", ProbeServer::handleLogTail, "core");
            route("/command", ProbeServer::handleCommand, "command");
            route("/world/block", ProbeServer::handleWorldBlock, "world");
            route("/world/break", ProbeServer::handleWorldBreak, "world");
            route("/world/mine", ProbeServer::handleWorldMine, "world");
            route("/items", ProbeServer::handleItems, "inventory");
            route("/recipe", ProbeServer::handleRecipe, "craft");
            route("/craft", ProbeServer::handleCraft, "craft");
            route("/loot", ProbeServer::handleLoot, "craft");
            route("/item", ProbeServer::handleItem, "inventory");
            route("/player/state", ProbeServer::handlePlayerState, "player");
            route("/entity/list", ProbeServer::handleEntityList, "entity");
            // ---- P4 第二批：聊天与特殊方块 UI（服务端权威侧）----
            // 客户端同名路由只能看到「自己那份方块实体」，服务端这条才是回读用的权威口径。
            route("/block/sign", ProbeServer::handleBlockSign, "block_extra");
            route("/block/command-block", ProbeServer::handleBlockCommandBlock, "block_extra");
            route("/run/dump", ProbeServer::handleRunDump, "core");
            // 服务端动作后端（架构 §8.2 的第二后端）：只做服务端天然权威的四件事。
            // 能力 `action` 由注册派生，「支持哪些动作」由后端自己的 actions() 决定。
            actionBackend = new ServerActionBackend(serverIn);
            for (String action : actionBackend.actions()) {
                route("/action/" + action, ProbeServer::handleAction, "action");
            }
            server.createContext("/screenshot", ProbeServer::handleNotClient);
            server.createContext("/ui/snapshot", ProbeServer::handleNotClient);
            server.createContext("/ui/click", ProbeServer::handleNotClient);
            executor = Executors.newFixedThreadPool(2);
            server.setExecutor(executor);
            server.start();
            ProbeLog.LOGGER.info("Mcdev Probe listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            ProbeLog.LOGGER.error("Failed to start Mcdev Probe", e);
        }
    }

    /**
     * 由平台入口声明「我是哪个加载器」。**应在 {@link #start} 之前调用** ——
     * 否则监听一开、标志还没写，「/status 报 unknown」这个窗口虽然只有微秒级，但没有理由留它。
     *
     * @param name 小写加载器名，如 {@code "fabric"} / {@code "forge"} / {@code "neoforge"}
     */
    /** 注册一条路由并登记它提供的能力（架构 I3：能力由注册派生，禁止手写）。 */
    private static void route(String path, com.sun.net.httpserver.HttpHandler handler, String capability) {
        server.createContext(path, handler);
        CAPS.add(capability);
    }

    public static synchronized void setLoader(String name) {
        loader = name == null || name.isEmpty() ? "unknown" : name;
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
        actionBackend = null;
    }

    /** 26.x 的 Recipe 不再有 getResultItem；产物走 display() → SlotDisplay。 */
    private static ItemStack resultOf(Recipe<?> recipe) {
        List<RecipeDisplay> displays = recipe.display();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return displays.get(0).result().resolveForFirstStack(ContextMap.EMPTY);
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("side", "server");
        data.put("loader", loader);
        data.put("capabilities", new ArrayList<>(CAPS));
        data.put("actionBackend", actionBackend == null ? null : actionBackend.name());
        data.put("actions", actionBackend == null ? List.of() : new ArrayList<>(actionBackend.actions()));
        data.put("tick", mc.getTickCount());
        data.put("gameTime", mc.overworld().getGameTime());
        data.put("dayTime", mc.overworld().getOverworldClockTime());
        data.put("players", mc.getPlayerList().getPlayerCount());
        data.put("maxPlayers", mc.getPlayerList().getMaxPlayers());
        data.put("level", mc.getWorldData().getLevelName());
        data.put("version", SharedConstants.getCurrentVersion().name());
        data.put("frozen", TickControl.isFrozen());
        data.put("stepBudget", TickControl.getStepBudget());
        ok(ex, data);
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

    private static void handleCommand(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "POST required");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final String cmd;
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            cmd = obj.get("cmd").getAsString();
        } catch (Exception e) {
            error(ex, 400, "invalid json: " + e.getMessage());
            return;
        }
        CompletableFuture<Integer> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                mc.getCommands().performPrefixedCommand(mc.createCommandSourceStack(), cmd);
                future.complete(0);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            int result = future.get(10, TimeUnit.SECONDS);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cmd", cmd);
            data.put("result", result);
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "command failed: " + e.getMessage());
        }
    }

    private static void handleWorldBlock(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        String dim = strQuery(ex, "dim", "minecraft:overworld");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ResourceKey<Level> key = ResourceKey.create(
                        Registries.DIMENSION, Identifier.parse(dim));
                ServerLevel level = mc.getLevel(key);
                if (level == null) {
                    level = mc.overworld();
                }
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                BlockEntity be = level.getBlockEntity(pos);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("dim", level.dimension().identifier().toString());
                data.put("pos", x + "," + y + "," + z);
                data.put("block", String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
                data.put("state", state.toString());
                data.put("analog", state.getAnalogOutputSignal(level, pos, net.minecraft.core.Direction.UP));
                data.put("blockEntity", be == null ? null : be.saveWithoutMetadata(mc.registryAccess()).toString());
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "world query failed: " + e.getMessage());
        }
    }

    private static void handleWorldBreak(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ServerLevel level = mc.overworld();
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("pos", x + "," + y + "," + z);
                data.put("blockBefore", String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
                data.put("removed", level.destroyBlock(pos, true));
                AABB box = new AABB(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4);
                List<Map<String, Object>> drops = new ArrayList<>();
                int total = 0;
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
                    ItemStack stack = entity.getItem();
                    Map<String, Object> drop = new LinkedHashMap<>();
                    drop.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                    drop.put("count", stack.getCount());
                    drops.add(drop);
                    total += stack.getCount();
                }
                data.put("drops", drops);
                data.put("total", total);
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "world break failed: " + e.getMessage());
        }
    }

    private static void handleWorldMine(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        String playerName = strQuery(ex, "player", "Dev");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ServerPlayer player = mc.getPlayerList().getPlayerByName(playerName);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("pos", x + "," + y + "," + z);
                data.put("player", playerName);
                if (player == null) {
                    data.put("found", false);
                    data.put("error", "player not online");
                    future.complete(data);
                    return;
                }
                ServerLevel level = mc.overworld();
                BlockPos pos = new BlockPos(x, y, z);
                data.put("before", String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())));
                GameType oldMode = player.gameMode.getGameModeForPlayer();
                player.setGameMode(GameType.SURVIVAL);
                data.put("removed", player.gameMode.destroyBlock(pos));
                player.setGameMode(oldMode);
                AABB box = new AABB(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4);
                List<Map<String, Object>> drops = new ArrayList<>();
                int total = 0;
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
                    ItemStack stack = entity.getItem();
                    Map<String, Object> drop = new LinkedHashMap<>();
                    drop.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                    drop.put("count", stack.getCount());
                    drops.add(drop);
                    total += stack.getCount();
                }
                data.put("drops", drops);
                data.put("total", total);
                data.put("after", String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())));
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "world mine failed: " + e.getMessage());
        }
    }

    private static void handleItems(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        int r = Math.min(16, Math.max(1, intQuery(ex, "r", 4)));
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ServerLevel level = mc.overworld();
                AABB box = new AABB(x - r, y - r, z - r, x + r + 1, y + r + 1, z + r + 1);
                List<Map<String, Object>> drops = new ArrayList<>();
                int total = 0;
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
                    ItemStack stack = entity.getItem();
                    Map<String, Object> drop = new LinkedHashMap<>();
                    drop.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                    drop.put("count", stack.getCount());
                    drops.add(drop);
                    total += stack.getCount();
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("pos", x + "," + y + "," + z);
                data.put("radius", r);
                data.put("drops", drops);
                data.put("total", total);
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "items query failed: " + e.getMessage());
        }
    }

    private static void handleCraft(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String recipeId = strQuery(ex, "recipe", "");
        String playerName = strQuery(ex, "player", "Dev");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("recipe", recipeId);
                data.put("player", playerName);
                ServerPlayer player = mc.getPlayerList().getPlayerByName(playerName);
                if (player == null) {
                    data.put("found", false);
                    data.put("crafted", false);
                    data.put("error", "player not online");
                    future.complete(data);
                    return;
                }
                Identifier rl = Identifier.tryParse(recipeId);
                if (rl == null) {
                    data.put("found", false);
                    data.put("crafted", false);
                    data.put("error", "invalid recipe id");
                    future.complete(data);
                    return;
                }
                var optional = mc.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, rl));
                if (optional.isEmpty()) {
                    data.put("found", false);
                    data.put("crafted", false);
                    data.put("error", "recipe not found");
                    future.complete(data);
                    return;
                }
                Recipe<?> recipe = optional.get().value();
                data.put("found", true);
                List<Map<String, Object>> consumed = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                var inventory = player.getInventory().getNonEquipmentItems();
                for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    boolean matched = false;
                    for (ItemStack stack : inventory) {
                        if (!stack.isEmpty() && ingredient.test(stack)) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                            entry.put("count", 1);
                            consumed.add(entry);
                            stack.shrink(1);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        missing.add(ingredient.toString());
                    }
                }
                data.put("consumed", consumed);
                data.put("missing", missing);
                if (!missing.isEmpty()) {
                    data.put("crafted", false);
                    future.complete(data);
                    return;
                }
                ItemStack result = resultOf(recipe).copy();
                ItemStack output = result.copy();
                boolean added = player.getInventory().add(result);
                if (!added) {
                    player.drop(result, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(output.getItem())));
                out.put("count", output.getCount());
                data.put("crafted", true);
                data.put("result", out);
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "craft failed: " + e.getMessage());
        }
    }

    private static void handleRecipe(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String id = strQuery(ex, "id", "");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", id);
                Identifier rl = Identifier.tryParse(id);
                if (rl == null) {
                    data.put("found", false);
                    data.put("error", "invalid recipe id");
                    future.complete(data);
                    return;
                }
                var optional = mc.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, rl));
                if (optional.isEmpty()) {
                    data.put("found", false);
                    future.complete(data);
                    return;
                }
                Recipe<?> recipe = optional.get().value();
                ItemStack result = resultOf(recipe);
                data.put("found", true);
                data.put("item", result.isEmpty() ? null
                        : String.valueOf(BuiltInRegistries.ITEM.getKey(result.getItem())));
                data.put("count", result.getCount());
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "recipe query failed: " + e.getMessage());
        }
    }

    private static void handleLoot(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String blockId = strQuery(ex, "block", "");
        int x = intQuery(ex, "x", 0);
        int y = intQuery(ex, "y", 64);
        int z = intQuery(ex, "z", 0);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("block", blockId);
                Identifier rl = Identifier.tryParse(blockId);
                Block block = rl == null ? null : BuiltInRegistries.BLOCK.getValue(rl);
                if (block == null) {
                    data.put("found", false);
                    future.complete(data);
                    return;
                }
                ServerLevel level = mc.overworld();
                List<ItemStack> drops = Block.getDrops(
                        block.defaultBlockState(), level, new BlockPos(x, y, z),
                        null, null, ItemStack.EMPTY);
                List<Map<String, Object>> list = new ArrayList<>();
                int total = 0;
                for (ItemStack stack : drops) {
                    Map<String, Object> drop = new LinkedHashMap<>();
                    drop.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                    drop.put("count", stack.getCount());
                    list.add(drop);
                    total += stack.getCount();
                }
                data.put("found", true);
                data.put("drops", list);
                data.put("total", total);
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "loot query failed: " + e.getMessage());
        }
    }

    private static void handleItem(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String itemId = strQuery(ex, "item", "");
        String nbt = strQuery(ex, "nbt", "");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("item", itemId);
                Identifier rl = Identifier.tryParse(itemId);
                Item item = rl == null ? null : BuiltInRegistries.ITEM.getValue(rl);
                if (item == null) {
                    data.put("found", false);
                    future.complete(data);
                    return;
                }
                ItemStack stack = new ItemStack(item);
                if (!nbt.isEmpty()) {
                    String snbt = nbt.startsWith("{") ? nbt : "{" + nbt + "}";
                    CompoundTag tag = TagParser.parseCompoundFully(snbt);
                    stack = ItemStack.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(stack);
                }
                data.put("found", true);
                data.put("visible", item.isBarVisible(stack));
                data.put("width", item.getBarWidth(stack));
                data.put("color", item.getBarColor(stack));
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            ok(ex, future.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            error(ex, 500, "item query failed: " + e.getMessage());
        }
    }

    private static void handleRunDump(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        int n = intQuery(ex, "n", 500);
        ok(ex, RunRecorder.dump(n));
    }

    /**
     * 读一个在线玩家的**服务端权威状态**。
     *
     * <p>为什么需要这条：`/command` 返回的 `result` 不是「成功布尔」—— 26.3 里
     * `data get entity <name> playerGameType` 恒返回 0（该字段已不可这样读），
     * 而 `gamemode` / `execute if entity` 的返回值也都可能是 0。
     * 所以「动作层到底有没有改到服务端」必须有一条**直接读 `ServerPlayer` 字段**的旁证，
     * 否则测试只能相信客户端自己说的话（那正是原探针最大的缺陷）。
     *
     * <p>{@code player} 省略时取第一个在线玩家（单客户端测试场景）。
     */
    private static void handlePlayerState(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String name = strQuery(ex, "player", null);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ServerPlayer player = (name == null || name.isEmpty())
                        ? (mc.getPlayerList().getPlayers().isEmpty() ? null : mc.getPlayerList().getPlayers().get(0))
                        : mc.getPlayerList().getPlayerByName(name);
                if (player == null) {
                    future.complete(null);
                    return;
                }
                GameType mode = player.gameMode.getGameModeForPlayer();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", player.getName().getString());
                data.put("online", true);
                data.put("gamemode", String.valueOf(mode));
                data.put("gamemodeId", mode.getId());
                data.put("creative", mode.isCreative());
                data.put("survival", mode.isSurvival());
                data.put("adventure", mode == GameType.ADVENTURE);
                data.put("spectator", mode == GameType.SPECTATOR);
                data.put("health", player.getHealth());
                data.put("maxHealth", player.getMaxHealth());
                data.put("food", player.getFoodData().getFoodLevel());
                data.put("position", player.getX() + "," + player.getY() + "," + player.getZ());
                data.put("flying", player.getAbilities().flying);
                // 不报 mayfly：NeoForge 的补丁把 Abilities.mayfly 标了 @Deprecated（shared main 层
                // 三家共用，报它会让 NeoForge 构建出现唯一一条警告，破坏「零警告」验收项）。
                data.put("invulnerable", player.getAbilities().invulnerable);
                data.put("onGround", player.onGround());
                data.put("sleeping", player.isSleeping());
                data.put("dead", player.isDeadOrDying());
                // 不做 op 判断：26.3 的 PlayerList.isOp 要 NameAndId，ServerPlayer.hasPermissions 已移除。
                // 需要 op 状态的调用方看 /command 的结果即可，这里不为此引入一层转换。
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            Map<String, Object> data = future.get(10, TimeUnit.SECONDS);
            if (data == null) {
                error(ex, 404, "player not online: " + (name == null ? "<any>" : name));
                return;
            }
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "player state failed: " + e.getMessage());
        }
    }

    /**
     * 列出玩家附近的实体（服务端侧）。
     *
     * <p>为什么服务端也要这一条：`/action/attack` 与 `/action/use` 的 {@code target:"entity"}
     * 都只认实体**网络 id**，而服务端自己没有任何手段发现它 —— 没有这条路由，
     * 「纯服务端跑动作」就永远要借一次客户端探针。有了它就完全自洽：实体 id 由服务端列，
     * 动作由服务端执行，客户端只负责「有人在线」。
     *
     * <p>{@code player} 省略取第一个在线玩家，{@code r} 默认 32 格。
     */
    private static void handleEntityList(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String name = strQuery(ex, "player", null);
        int radius = intQuery(ex, "r", 32);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ServerPlayer player = (name == null || name.isEmpty())
                        ? (mc.getPlayerList().getPlayers().isEmpty() ? null : mc.getPlayerList().getPlayers().get(0))
                        : mc.getPlayerList().getPlayerByName(name);
                if (player == null) {
                    future.complete(null);
                    return;
                }
                double r2 = (double) radius * radius;
                List<Map<String, Object>> list = new ArrayList<>();
                int scanned = 0;
                for (Entity entity : player.level().getAllEntities()) {
                    scanned++;
                    if (entity == player || entity.distanceToSqr(player) > r2) {
                        continue;
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("id", entity.getId());
                    e.put("type", String.valueOf(entity.getType()));
                    e.put("uuid", entity.getUUID().toString());
                    e.put("distance", Math.round(Math.sqrt(entity.distanceToSqr(player)) * 100.0) / 100.0);
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", Math.round(entity.getX() * 100.0) / 100.0);
                    pos.put("y", Math.round(entity.getY() * 100.0) / 100.0);
                    pos.put("z", Math.round(entity.getZ() * 100.0) / 100.0);
                    e.put("pos", pos);
                    list.add(e);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("player", player.getName().getString());
                data.put("radius", radius);
                data.put("scanned", scanned);
                data.put("count", list.size());
                data.put("entities", list);
                future.complete(data);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            Map<String, Object> data = future.get(10, TimeUnit.SECONDS);
            if (data == null) {
                error(ex, 404, "player not online: " + (name == null ? "<any>" : name));
                return;
            }
            ok(ex, data);
        } catch (Exception e) {
            error(ex, 500, "entity list failed: " + e.getMessage());
        }
    }

    /**
     * {@code GET /block/sign?x&y&z&side=front|back} —— 服务端权威读告示牌文字。
     *
     * <p>客户端探针有同名路由，但客户端只在自己那块区块加载时能看到方块实体，
     * 而且看到的是**客户端副本**。要验证「包发出去之后服务端真的写进去了」，必须读这条。</p>
     */
    private static void handleBlockSign(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String method = ex.getRequestMethod();
        boolean post = "POST".equalsIgnoreCase(method);
        if (!post && !"GET".equalsIgnoreCase(method)) {
            error(ex, 405, "GET or POST required");
            return;
        }
        try {
            BlockPos pos;
            String op = "read";
            String side = "front";
            String playerName = null;
            List<String> newLines = null;
            if (post) {
                JsonObject body = readBody(ex);
                pos = posFromBody(body);
                if (body.has("op")) {
                    op = body.get("op").getAsString();
                }
                if (body.has("side")) {
                    side = body.get("side").getAsString();
                }
                if (body.has("player")) {
                    playerName = body.get("player").getAsString();
                }
                if (body.has("lines") && body.get("lines").isJsonArray()) {
                    newLines = new ArrayList<>();
                    for (var element : body.getAsJsonArray("lines")) {
                        newLines.add(element.getAsString());
                    }
                }
            } else {
                pos = requireBlockPosQuery(ex);
                side = strQuery(ex, "side", "front");
            }
            final String finalOp = op;
            final String finalPlayer = playerName;
            final List<String> finalLines = newLines;
            SignTextSlot slot = "back".equalsIgnoreCase(side) ? SignTextSlot.BACK : SignTextSlot.FRONT;
            var future = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
            mc.execute(() -> {
                try {
                    ServerLevel level = mc.overworld();
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof SignBlockEntity sign)) {
                        future.complete(null);
                        return;
                    }
                    String note = null;
                    if ("open".equals(finalOp)) {
                        ServerPlayer actor = finalPlayer == null
                                ? (mc.getPlayerList().getPlayers().isEmpty() ? null : mc.getPlayerList().getPlayers().get(0))
                                : mc.getPlayerList().getPlayerByName(finalPlayer);
                        if (actor == null) {
                            future.completeExceptionally(new IllegalArgumentException(
                                    "player not online: " + (finalPlayer == null ? "<any>" : finalPlayer)));
                            return;
                        }
                        // 与真人右键同一条路径：服务端跑 SignBlock.useWithoutItem → openTextEdit，
                        // 只有这一步才会把 getPlayerWhoMayEdit() 写成该玩家，之后的文本包才被接受。
                        var hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
                        actor.gameMode.useItemOn(actor, level, actor.getMainHandItem(),
                                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                        note = "editor authorized for " + actor.getScoreboardName()
                                + " (a real client must now send the text packet)";
                    } else if ("set".equals(finalOp)) {
                        // 直接写方块实体：不需要任何在线玩家，也就不需要先授权。
                        // 代价是这条路径不经过 ServerboundSignUpdatePacket —— 包路径由 op=open + 客户端编辑验证。
                        if (finalLines == null) {
                            future.completeExceptionally(new IllegalArgumentException("op set requires field: lines"));
                            return;
                        }
                        List<net.minecraft.network.chat.Component> components = new ArrayList<>();
                        for (int i = 0; i < 4; i++) {
                            components.add(net.minecraft.network.chat.Component.literal(
                                    i < finalLines.size() ? finalLines.get(i) : ""));
                        }
                        SignText oldText = sign.getText(slot);
                        sign.setText(new SignText(components, oldText.getMessages(true),
                                oldText.getColor(), oldText.hasGlowingText()), slot);
                        sign.setChanged();
                        level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), 3);
                        note = "text written server-side (does not exercise the text packet)";
                    } else if (!"read".equals(finalOp)) {
                        future.completeExceptionally(new IllegalArgumentException(
                                "unknown op '" + finalOp + "' (expected read|open|set)"));
                        return;
                    }
                    SignText text = sign.getText(slot);
                    List<String> lines = new ArrayList<>();
                    for (var component : text.getMessages(false)) {
                        lines.add(component.getString());
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("pos", pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    data.put("side", slot.name().toLowerCase(java.util.Locale.ROOT));
                    data.put("lines", lines);
                    data.put("waxed", sign.isWaxed());
                    data.put("glowing", text.hasGlowingText());
                    data.put("color", text.getColor().getSerializedName());
                    data.put("hasMessage", text.hasMessage(false));
                    if (note != null) {
                        data.put("note", note);
                    }
                    future.complete(data);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            try {
                Map<String, Object> data = future.get(10, TimeUnit.SECONDS);
                if (data == null) {
                    error(ex, 404, "no sign at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    return;
                }
                ok(ex, data);
            } catch (Exception e) {
                error(ex, 500, "sign read failed: " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        }
    }

    /**
     * {@code GET /block/command-block?x&y&z} 读 / {@code POST} 写或执行。
     *
     * <p>命令方块的命令只有服务端能改（{@code BaseCommandBlock.setCommand}），客户端探针那条
     * 同名路由是只读的。POST 请求体：</p>
     * <ul>
     *   <li>{@code {"op":"set","pos":[x,y,z],"command":"say hi","auto":false}} —— 写命令，回读校验；</li>
     *   <li>{@code {"op":"run","pos":[x,y,z]}} —— 立刻执行一次（{@code performCommand}），
     *       回 successCount；适合验证「命令真的能跑」。</li>
     * </ul>
     */
    private static void handleBlockCommandBlock(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        String method = ex.getRequestMethod();
        boolean post = "POST".equalsIgnoreCase(method);
        if (!post && !"GET".equalsIgnoreCase(method)) {
            error(ex, 405, "GET or POST required");
            return;
        }
        try {
            BlockPos pos;
            String op = "read";
            String command = null;
            Boolean auto = null;
            if (post) {
                JsonObject body = readBody(ex);
                pos = posFromBody(body);
                if (body.has("op")) {
                    op = body.get("op").getAsString();
                }
                if (body.has("command")) {
                    command = body.get("command").getAsString();
                }
                if (body.has("auto")) {
                    auto = body.get("auto").getAsBoolean();
                }
            } else {
                pos = requireBlockPosQuery(ex);
            }
            final String finalOp = op;
            final String finalCommand = command;
            final Boolean finalAuto = auto;
            var future = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
            mc.execute(() -> {
                try {
                    ServerLevel level = mc.overworld();
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof CommandBlockEntity commandBlock)) {
                        future.complete(null);
                        return;
                    }
                    BaseCommandBlock base = commandBlock.getCommandBlock();
                    String note = null;
                    if ("set".equals(finalOp)) {
                        if (finalCommand == null) {
                            future.completeExceptionally(new IllegalArgumentException("op set requires field: command"));
                            return;
                        }
                        base.setCommand(finalCommand);
                        if (finalAuto != null) {
                            commandBlock.setAutomatic(finalAuto);
                        }
                        commandBlock.setChanged();
                        level.sendBlockUpdated(pos, commandBlock.getBlockState(), commandBlock.getBlockState(), 3);
                        note = "command written; setChanged + block update sent";
                    } else if ("run".equals(finalOp)) {
                        boolean ran = base.performCommand(level);
                        note = ran ? "executed" : "performCommand returned false (conditional or disabled)";
                    } else if (!"read".equals(finalOp)) {
                        future.completeExceptionally(new IllegalArgumentException(
                                "unknown op '" + finalOp + "' (expected read|set|run)"));
                        return;
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("pos", pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    data.put("op", finalOp);
                    data.put("command", base.getCommand());
                    data.put("mode", commandBlock.getMode().name().toLowerCase(java.util.Locale.ROOT));
                    data.put("automatic", commandBlock.isAutomatic());
                    data.put("conditional", commandBlock.isConditional());
                    data.put("powered", commandBlock.isPowered());
                    data.put("trackOutput", base.isTrackOutput());
                    data.put("successCount", base.getSuccessCount());
                    var lastOutput = base.getLastOutput();
                    data.put("lastOutput", lastOutput == null ? null : lastOutput.getString());
                    if (note != null) {
                        data.put("note", note);
                    }
                    future.complete(data);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            try {
                Map<String, Object> data = future.get(10, TimeUnit.SECONDS);
                if (data == null) {
                    error(ex, 404, "no command block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                    return;
                }
                ok(ex, data);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalArgumentException) {
                    error(ex, 400, cause.getMessage());
                } else {
                    error(ex, 500, "command block failed: " + cause);
                }
            } catch (Exception e) {
                error(ex, 500, "command block failed: " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        }
    }

    /**
     * 服务端侧的语义动作入口（{@code POST /action/<name>}）。
     *
     * <p>与客户端探针同名路由、同一种信封：成功 {@code {ok:true,data}}，失败
     * {@code {ok:false,error:{code,message,detail?}}}，HTTP 状态由 {@link ActionException#code()} 决定。
     * 差别只在 {@code data.backend}：这里是 {@code "server"}（只做服务端天然权威的动作，
     * 见 {@link ServerActionBackend}），客户端那边是 {@code "client"}。
     *
     * <p>为什么两端要有同名路由：调用方（DSH 插件层）只关心「做这个动作」，不该关心
     * 「哪一端能做」；`/status` 的 {@code actionBackend} / {@code actions} 才是选端的依据。
     */
    private static void handleAction(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            error(ex, 405, "use POST");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String action = path.substring(path.lastIndexOf('/') + 1);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            error(ex, 400, "request body is not a JSON object: " + e.getMessage());
            return;
        }
        json.addProperty("action", action);
        try {
            ok(ex, actionBackend.execute(new ActionRequest(json)));
        } catch (ActionException e) {
            actionError(ex, e);
        }
    }

    private static void actionError(HttpExchange ex, ActionException e) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        JsonObject err = new JsonObject();
        err.addProperty("code", e.code().name());
        err.addProperty("message", e.getMessage());
        if (e.detail() != null) {
            err.add("detail", e.detail());
        }
        root.add("error", err);
        send(ex, e.code().httpStatus(), root);
    }

    private static void handleNotClient(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            return;
        }
        error(ex, 501, "client-only endpoint; this probe is running on the server");
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

    /**
     * 从 query 读必须给出的方块坐标（{@code x}/{@code y}/{@code z}）。
     *
     * <p>与客户端探针同名 helper 保持同一套错误文案，调用方换端时不用改脚本。</p>
     */
    private static BlockPos requireBlockPosQuery(HttpExchange ex) {
        return new BlockPos(
                requireIntQuery(ex, "x"),
                requireIntQuery(ex, "y"),
                requireIntQuery(ex, "z"));
    }

    private static int requireIntQuery(HttpExchange ex, String key) {
        String raw = strQuery(ex, key, null);
        if (raw == null) {
            throw new IllegalArgumentException("missing query param: " + key);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("query param '" + key + "' is not an integer: " + raw);
        }
    }

    /**
     * 读请求体为 JSON 对象；空体当 {@code {}}。
     *
     * <p>与客户端探针同一实现：非对象直接 400，而不是把 null 往下传。</p>
     */
    private static JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new JsonObject();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            var parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException("invalid json: " + e.getMessage());
        }
    }

    /**
     * 从请求体读坐标：{@code pos:{x,y,z}} 或 {@code pos:[x,y,z]}。
     *
     * <p>数组形式是给 curl 手敲用的，对象形式与 {@code /action/*} 的约定一致。</p>
     */
    private static BlockPos posFromBody(JsonObject body) {
        if (!body.has("pos")) {
            throw new IllegalArgumentException("missing field: pos");
        }
        var pos = body.get("pos");
        if (pos.isJsonArray()) {
            var arr = pos.getAsJsonArray();
            if (arr.size() < 3) {
                throw new IllegalArgumentException("field 'pos' must have 3 numbers: [x, y, z]");
            }
            return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
        }
        if (pos.isJsonObject()) {
            var obj = pos.getAsJsonObject();
            for (String key : new String[] {"x", "y", "z"}) {
                if (!obj.has(key)) {
                    throw new IllegalArgumentException("field 'pos' is missing: " + key);
                }
            }
            return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
        }
        throw new IllegalArgumentException("field 'pos' must be an object {x,y,z} or an array [x,y,z]");
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

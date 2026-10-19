package com.mcdev.probe.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 客户端动作后端 —— 架构 §8.2 的 {@code ClientActionBackend}，覆盖接近全量。
 *
 * <p><b>为什么放在客户端：</b>用户决定（m00625）测试形态是「服务端装 MOD + 客户端连进来探测」，
 * 而 58 个 serverbound 包里有 30 个只能由客户端发出（移动、模式与能力、旁观、特殊方块 UI、查询管理）。
 * 服务端那侧再怎么写假玩家也补不齐这些 —— 客户端本来就有完整的发包路径。</p>
 *
 * <p><b>实现手段不是自造协议，而是借原版自己的入口</b>：{@link MultiPlayerGameMode} 是原版客户端
 * 所有交互的唯一出口（{@code startDestroyBlock / useItemOn / attack / handleContainerInput / …}），
 * {@link net.minecraft.client.player.ClientInput} 是原版移动的唯一输入源。走这两个入口，
 * 服务端收到的包与真人操作**一模一样**，不需要逐包拼载荷，也不会漏掉「客户端本地状态也要更新」
 * 这类隐式副作用（只拼包会让本地端与服务端不一致，后续 check 全乱）。</p>
 *
 * <p><b>线程模型：</b>{@code /action/*} 的处理器跑在 JDK HttpServer 的线程池上，而所有 Minecraft
 * 对象只能在客户端主线程碰。因此这里只有 {@link #onClient(Callable)} 这一种操作方式
 * （单步 hop 到主线程 + 结果回传），「需要等时间」的部分（走路、挖方块）在 HTTP 线程上
 * {@code sleep} 轮询，**绝不能**在主线程上 sleep —— 那会把游戏的 tick 一起冻住。</p>
 *
 * <p><b>三段式校验</b>（架构 §8.1）：trait 可用 → gamemode 符合 → 目标存在。第二段用
 * {@link #requireMode} 显式拒绝（例如旁观者挖方块返回 {@code GAMEMODE_MISMATCH} 而非「成功但没效果」），
 * 第三段在每个动作里各自校验（方块是空气、实体 id 不存在、槽位越界……）。</p>
 */
public final class ClientActionBackend implements ActionBackend {

    /** 单次 hop 到主线程的最长等待；超过就是主线程卡死（或世界正在加载），必须响亮失败。 */
    private static final long CLIENT_HOP_TIMEOUT_MS = 10_000L;
    /** 挖方块默认预算；原版徒手挖 1 格石头约 1.5 s，给 5 s 足够暴露「其实没在挖」。 */
    private static final int DEFAULT_MINE_TIMEOUT_MS = 5_000;
    /** 走路默认时长（有 to 目标时为上限）。 */
    private static final int DEFAULT_WALK_MS = 600;
    private static final int MAX_WALK_MS = 5_000;

    /**
     * 服务端探针的地址 —— {@code mode:"server-teleport"} 要用它请服务端代劳。
     *
     * <p>为什么需要跨端：{@code player_movement_check} 是**世界设置**，不是探针能改的客户端开关；
     * 而客户端无论怎么改都只能<em>请求</em>移动，没法让服务端接受它自己的位移。真正无条件的瞬移
     * 只有服务端自己的 {@code /tp}（它直接 {@code setPos} + 发位置包给客户端）才有。
     * 所以「把玩家送到远处」的正确实现是：客户端探针 → 服务端探针 {@code /command} →
     * 服务端 {@code /player/state} 读回权威坐标。这条链路同时把「客户端侧能力」和「服务端侧观察」
     * 接线成可用的一步。</p>
     */
    private static final String SERVER_PROBE_URL_PROP = "mcdev.probe.server.url";
    private static final String DEFAULT_SERVER_PROBE_URL = "http://127.0.0.1:39001";
    private static final String SERVER_PROBE_TIMEOUT_PROP = "mcdev.probe.server.timeoutMs";
    private static final int DEFAULT_SERVER_PROBE_TIMEOUT_MS = 5_000;
    private static final String TOKEN_PROP = "mcdev.probe.token";

    private static HttpClient httpClient;

    /**
     * 每个动作名 → 它实际驱动到的 serverbound 包。写进响应的 {@code packets} 字段，
     * 也是「58 包覆盖面」的记账依据（不靠嘴说覆盖了哪些）。
     */
    private static final Map<String, List<String>> COVERAGE = new LinkedHashMap<>();

    static {
        COVERAGE.put("move", List.of(
                "ServerboundPlayerInputPacket",
                "ServerboundMovePlayerPacket$PosRot",
                "ServerboundMovePlayerPacket$Pos",
                "ServerboundAcceptTeleportationPacket"));
        COVERAGE.put("mine", List.of(
                "ServerboundPlayerActionPacket(START_DESTROY_BLOCK)",
                "ServerboundPlayerActionPacket(CHANGE_DESTROY_DIRECTION)",
                "ServerboundPlayerActionPacket(STOP_DESTROY_BLOCK)",
                "ServerboundPlayerActionPacket(ABORT_DESTROY_BLOCK)"));
        COVERAGE.put("place", List.of("ServerboundUseItemOnPacket"));
        COVERAGE.put("use", List.of(
                "ServerboundUseItemOnPacket",
                "ServerboundUseItemPacket",
                "ServerboundInteractPacket",
                "ServerboundPlayerActionPacket(RELEASE_USE_ITEM)"));
        COVERAGE.put("attack", List.of("ServerboundAttackPacket", "ServerboundInteractPacket"));
        COVERAGE.put("pick", List.of("ServerboundPickItemFromBlockPacket", "ServerboundPickItemFromEntityPacket"));
        COVERAGE.put("inventory", List.of(
                "ServerboundContainerClickPacket",
                "ServerboundContainerClosePacket",
                "ServerboundContainerButtonClickPacket",
                "ServerboundSetCreativeModeSlotPacket",
                "ServerboundSetCarriedItemPacket",
                "ServerboundPlayerActionPacket(DROP_ITEM)",
                "ServerboundPlayerActionPacket(DROP_ALL_ITEMS)"));
        COVERAGE.put("gamemode", List.of("ServerboundChangeGameModePacket"));
        COVERAGE.put("ability", List.of("ServerboundPlayerAbilitiesPacket"));
        COVERAGE.put("spectate", List.of("ServerboundSpectatorActionPacket", "ServerboundTeleportToEntityPacket"));
        COVERAGE.put("respawn", List.of("ServerboundClientCommandPacket(PERFORM_RESPAWN)"));
        COVERAGE.put("sleep", List.of("ServerboundUseItemOnPacket"));
    }

    @Override
    public String name() {
        return "client";
    }

    @Override
    public Set<String> actions() {
        return new LinkedHashSet<>(COVERAGE.keySet());
    }

    @Override
    public JsonObject execute(ActionRequest req) throws ActionException {
        String action = req.str("action", null);
        if (action == null) {
            throw ActionException.badRequest("internal: action name missing (route should inject it)");
        }
        return switch (action) {
            case "move" -> move(req);
            case "mine" -> mine(req);
            case "place" -> place(req);
            case "use" -> use(req);
            case "attack" -> attack(req);
            case "pick" -> pick(req);
            case "inventory" -> inventory(req);
            case "gamemode" -> gamemode(req);
            case "ability" -> ability(req);
            case "spectate" -> spectate(req);
            case "respawn" -> respawn(req);
            case "sleep" -> sleep(req);
            default -> throw ActionException.notSupported("unknown action '" + action + "'");
        };
    }

    // ------------------------------------------------------------------
    // 动作实现
    // ------------------------------------------------------------------

    /**
     * {@code POST /action/move {player,to?,look?,mode:"teleport"|"walk"|"server-teleport",durationMs?,reach?}}
     *
     * <p>三种模式的区别是「谁执行位移」：</p>
     * <ul>
     *   <li>{@code teleport} —— 客户端改本地坐标，服务端按 {@code player_movement_check} 裁决。
     *       世界默认开着该 gamerule，所以超过几个格的跳会被拉回（响应里 {@code serverAccepted:false}
     *       + {@code snappedBack:true} 会直说）。</li>
     *   <li>{@code walk} —— 替换 {@link net.minecraft.client.player.ClientInput} 让玩家真的走过去，
     *       全程都在服务端的移动检查之内，是「合规」的长距离移动。</li>
     *   <li>{@code server-teleport} —— 让服务端探针执行 {@code /tp}，再由服务端探针读回权威坐标。
     *       这是唯一不受 gamerule 限制的瞬移，代价是需要服务端探针可达（默认 39001，
     *       可用 {@code -Dmcdev.probe.server.url=} 指定）。</li>
     * </ul>
     */
    private JsonObject move(ActionRequest req) throws ActionException {
        LocalPlayer p = player();
        String mode = req.str("mode", req.has("to") ? "teleport" : "walk");
        float[] look = req.has("look") ? req.yawPitch("look") : null;
        Vec3 from = onClient(p::position);

        JsonObject data = new JsonObject();
        data.addProperty("mode", mode);
        data.add("from", vecJson(from));
        data.addProperty("packets", join(COVERAGE.get("move")));

        if ("server-teleport".equals(mode)) {
            if (!req.has("to")) {
                throw ActionException.badRequest("mode=server-teleport requires 'to' {x,y,z}");
            }
            double[] to = req.vec3("to");
            String name = onClient(p::getScoreboardName);
            String command = "tp " + name + " " + coord(to[0]) + " " + coord(to[1]) + " " + coord(to[2]);

            JsonObject probe = new JsonObject();
            probe.addProperty("url", serverProbeUrl());
            probe.addProperty("command", command);
            data.add("serverProbe", probe);

            JsonObject tp = new JsonObject();
            tp.addProperty("cmd", command);
            JsonObject response = callServerProbe("POST", "/command", tp);
            probe.addProperty("commandOk", isOk(response));
            if (!isOk(response)) {
                probe.add("commandResponse", response);
                JsonObject detail = new JsonObject();
                detail.addProperty("command", command);
                detail.add("serverResponse", response);
                throw new ActionException(ActionError.INTERNAL,
                        "the server probe rejected the teleport command", detail);
            }

            // 服务端的 /tp 在这一 tick 内生效；等一拍再读，免得读到旧的 lastPos。
            sleep(400);
            JsonObject state = callServerProbe("GET", "/player/state?player=" + name, null);
            if (!isOk(state) || !state.getAsJsonObject("data").has("position")) {
                JsonObject detail = new JsonObject();
                detail.addProperty("player", name);
                detail.add("serverResponse", state);
                throw ActionException.playerNotFound("the server probe does not know a player named '" + name + "'");
            }
            Vec3 serverPos = parsePosition(state.getAsJsonObject("data").get("position").getAsString());
            double horizontalError = Math.hypot(serverPos.x - to[0], serverPos.z - to[2]);
            boolean applied = horizontalError <= 0.75D;

            data.add("to", vecJson(new Vec3(to[0], to[1], to[2])));
            data.add("serverPosition", vecJson(serverPos));
            data.addProperty("applied", applied);
            data.addProperty("horizontalError", round3(horizontalError));
            if (!applied) {
                data.addProperty("hint",
                        "the server accepted the /tp but the player is not where it was asked to be; "
                        + "check that the target chunk is loaded and that the player has permission (op level 2)");
            }
            return data;
        }

        if ("teleport".equals(mode)) {
            if (!req.has("to")) {
                throw ActionException.badRequest("mode=teleport requires 'to' {x,y,z}");
            }
            double[] to = req.vec3("to");
            float yaw = look == null ? onClient(p::getYRot) : look[0];
            float pitch = look == null ? onClient(p::getXRot) : look[1];
            Vec3 target = new Vec3(to[0], to[1], to[2]);
            onClient(() -> {
                // 只改本地位置：位置包由 LocalPlayer 自己的 tick 发。
                // 手搓 ServerboundMovePlayerPacket 会和本 tick 的正常位置包撞车，
                // 服务端只允许每 tick 一个位置包，第二个直接以
                // multiplayer.disconnect.invalid_player_movement 断线（已实测踩过）。
                p.absSnapTo(target.x, target.y, target.z, yaw, pitch);
                p.setDeltaMovement(Vec3.ZERO);
                return null;
            });
            sleep(250);
            JsonObject after = onClient(() -> {
                JsonObject obj = new JsonObject();
                obj.add("position", vecJson(p.position()));
                obj.addProperty("onGround", p.onGround());
                return obj;
            });
            data.add("after", after);
            // 26.3 实测（两轮）：默认 gamerule player_movement_check=true 时，客户端把位置挪远会被
            // ServerGamePacketListenerImpl.handleMovePlayer 以 "moved too quickly" 拒绝，并发一个
            // 位置修正包把玩家拉回原处；关掉该 gamerule（它是世界设置，不是代码开关）之后，
            // 服务端接受任意距离的客户端位移 —— 实测 60 格一次到位。
            // 判据只用水平误差：传送落地后玩家会按重力下坠几毫秒，竖直漂移不是失败信号。
            Vec3 now = onClient(p::position);
            double horizontalError = Math.sqrt(
                    (now.x - target.x) * (now.x - target.x) + (now.z - target.z) * (now.z - target.z));
            double verticalDrift = Math.abs(now.y - target.y);
            boolean accepted = horizontalError <= 0.75D;
            boolean snappedBack = !accepted && from.distanceTo(now) <= 0.75D;
            data.addProperty("serverAccepted", accepted);
            data.addProperty("horizontalError", Math.round(horizontalError * 1000D) / 1000D);
            data.addProperty("verticalDrift", Math.round(verticalDrift * 1000D) / 1000D);
            if (!accepted) {
                data.addProperty("snappedBack", snappedBack);
                data.addProperty("hint", snappedBack
                        ? "the server rejected the jump ('moved too quickly') and snapped the player back. "
                        + "The check is the world gamerule player_movement_check (default true). Options: "
                        + "use mode=server-teleport (works with the check on), turn the gamerule off with "
                        + "POST /command {\"cmd\":\"gamerule player_movement_check false\"}, "
                        + "or walk there with mode=walk"
                        : "the player did not land on the requested position; check that the target chunk is "
                        + "loaded and that the player can stand there");
            }
            return data;
        }

        if (!"walk".equals(mode)) {
            throw ActionException.badRequest("unknown mode '" + mode + "' (expected teleport|walk|server-teleport)");
        }
        double[] to = req.has("to") ? req.vec3("to") : null;
        int durationMs = clamp(req.i32("durationMs", to == null ? DEFAULT_WALK_MS : MAX_WALK_MS),
                100, MAX_WALK_MS);
        double reach = req.f64("reach", 0.8D);
        ScriptedInput input = new ScriptedInput();
        onClient(() -> {
            input.attach(p);
            return null;
        });
        int steps = 0;
        try {
            long deadline = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < deadline) {
                Vec3 pos = onClient(p::position);
                if (to == null) {
                    onClient(() -> {
                        if (look != null) {
                            p.setYRot(look[0]);
                            p.setXRot(look[1]);
                        }
                        input.pressForward();
                        return null;
                    });
                } else {
                    double dx = to[0] - pos.x;
                    double dz = to[2] - pos.z;
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    if (horizontal <= reach) {
                        break;
                    }
                    float yaw = look != null ? look[0] : (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = look != null ? look[1] : onClient(p::getXRot);
                    onClient(() -> {
                        p.setYRot(yaw);
                        p.setXRot(pitch);
                        input.pressForward();
                        return null;
                    });
                }
                sleep(50);
                steps++;
            }
        } finally {
            onClient(() -> {
                input.detach(p);
                return null;
            });
        }

        Vec3 end = onClient(p::position);
        double horizontal = Math.hypot(end.x - from.x, end.z - from.z);
        float finalYaw = onClient(p::getYRot);
        float finalPitch = onClient(p::getXRot);
        data.add("to", vecJson(end));
        data.addProperty("steps", steps);
        data.addProperty("durationMs", durationMs);
        data.addProperty("horizontalDistance", horizontal);
        data.addProperty("moved", horizontal > 0.05D);
        data.addProperty("yaw", finalYaw);
        data.addProperty("pitch", finalPitch);
        return data;
    }

    /** {@code POST /action/mine {player,pos,face}} */
    private JsonObject mine(ActionRequest req) throws ActionException {
        int[] bp = req.blockPos("pos");
        Direction face = direction(req, "face", Direction.UP);
        GameType mode = requireMode(GameType.SURVIVAL, GameType.CREATIVE);
        LocalPlayer p = player();
        BlockPos pos = new BlockPos(bp[0], bp[1], bp[2]);
        int timeoutMs = clamp(req.i32("timeoutMs", DEFAULT_MINE_TIMEOUT_MS), 200, 30_000);

        BlockState before = onClient(() -> level().getBlockState(pos));
        if (before.isAir()) {
            JsonObject detail = new JsonObject();
            detail.add("pos", blockJson(pos));
            detail.addProperty("block", "minecraft:air");
            throw new ActionException(ActionError.TARGET_NOT_FOUND, "there is no block to mine at " + pos, detail);
        }

        // 先让玩家真的「看向」目标方块：原版客户端只有在准星对准时才会正常推进挖掘，
        // 否则 continueDestroyBlock 会被客户端自己的命中校验拒掉（实测表现为超时且服务端收不到 START）。
        aimAt(pos);

        onClient(() -> {
            mc().gameMode.startDestroyBlock(pos, face);
            if (mode == GameType.CREATIVE && !mc().gameMode.isDestroying()) {
                // 创造模式是瞬破，原版走 destroyBlock 分支；这条也覆盖了「客户端本地已删方块」的路径。
                mc().gameMode.destroyBlock(pos);
            }
            return null;
        });

        boolean broken = false;
        int ticks = 0;
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                broken = onClient(() -> level().getBlockState(pos).isAir());
                if (broken) {
                    break;
                }
                // 原版挖掘需要每 tick 「继续挖」才累积进度；HTTP 线程上按 50 ms 一拍模拟。
                onClient(() -> {
                    mc().gameMode.continueDestroyBlock(pos, face);
                    return null;
                });
                sleep(50);
                ticks++;
            }
        } finally {
            onClient(() -> {
                mc().gameMode.stopDestroyBlock();
                return null;
            });
        }

        JsonObject data = new JsonObject();
        data.add("pos", blockJson(pos));
        data.addProperty("face", face.getName());
        data.addProperty("block", blockId(before));
        data.addProperty("broken", broken);
        data.addProperty("mode", mode.getName());
        data.addProperty("ticks", ticks);
        data.addProperty("packets", join(COVERAGE.get("mine")));
        if (!broken) {
            JsonObject detail = new JsonObject();
            detail.add("pos", blockJson(pos));
            detail.addProperty("timeoutMs", timeoutMs);
            throw ActionException.timeout("block not broken within " + timeoutMs + " ms", detail);
        }
        return data;
    }

    /** {@code POST /action/place {player,pos,face,item?}} */
    private JsonObject place(ActionRequest req) throws ActionException {
        int[] bp = req.blockPos("pos");
        Direction face = direction(req, "face", Direction.UP);
        requireMode(GameType.SURVIVAL, GameType.CREATIVE);
        LocalPlayer p = player();
        BlockPos pos = new BlockPos(bp[0], bp[1], bp[2]);
        InteractionHand hand = hand(req, "hand", InteractionHand.MAIN_HAND);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        InteractionResult result = onClient(() -> mc().gameMode.useItemOn(p, hand, hit));

        JsonObject data = new JsonObject();
        String blockAfter = onClient(() -> blockId(level().getBlockState(pos)));
        data.add("pos", blockJson(pos));
        data.addProperty("face", face.getName());
        data.addProperty("hand", hand.name());
        data.addProperty("result", String.valueOf(result));
        data.add("held", itemJson(onClient(() -> p.getItemInHand(hand))));
        data.addProperty("block", blockAfter);
        data.addProperty("packets", join(COVERAGE.get("place")));
        return data;
    }

    /** {@code POST /action/use {player,target:"block"|"entity"|"item",pos?,entityId?,hand?}} */
    private JsonObject use(ActionRequest req) throws ActionException {
        String target = req.str("target", "item");
        LocalPlayer p = player();
        InteractionHand hand = hand(req, "hand", InteractionHand.MAIN_HAND);
        JsonObject data = new JsonObject();
        data.addProperty("target", target);
        data.addProperty("packets", join(COVERAGE.get("use")));

        switch (target) {
            case "block" -> {
                int[] bp = req.blockPos("pos");
                Direction face = direction(req, "face", Direction.UP);
                requireMode(GameType.SURVIVAL, GameType.CREATIVE);
                BlockPos pos = new BlockPos(bp[0], bp[1], bp[2]);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                InteractionResult r = onClient(() -> mc().gameMode.useItemOn(p, hand, hit));
                data.add("pos", blockJson(pos));
                data.addProperty("result", String.valueOf(r));
            }
            case "entity" -> {
                Entity entity = entity(req.i32("entityId"));
                requireMode(GameType.SURVIVAL, GameType.CREATIVE);
                EntityHitResult hit = new EntityHitResult(entity);
                InteractionResult r = onClient(() -> mc().gameMode.interact(p, entity, hit, hand));
                data.addProperty("entityId", req.i32("entityId"));
                data.addProperty("result", String.valueOf(r));
            }
            case "item" -> {
                boolean releasing = onClient(p::isUsingItem);
                if (releasing) {
                    onClient(() -> {
                        mc().gameMode.releaseUsingItem(p);
                        return null;
                    });
                    data.addProperty("released", true);
                } else {
                    InteractionResult r = onClient(() -> mc().gameMode.useItem(p, hand));
                    data.addProperty("result", String.valueOf(r));
                }
            }
            default -> throw ActionException.badRequest("target must be block|entity|item, got '" + target + "'");
        }
        data.add("held", itemJson(onClient(() -> p.getItemInHand(InteractionHand.MAIN_HAND))));
        return data;
    }

    /** {@code POST /action/attack {player,entityId}} */
    private JsonObject attack(ActionRequest req) throws ActionException {
        int entityId = req.i32("entityId");
        requireMode(GameType.SURVIVAL, GameType.CREATIVE);
        Entity entity = entity(entityId);
        if (!(entity instanceof LivingEntity living)) {
            JsonObject detail = new JsonObject();
            detail.addProperty("entityId", entityId);
            detail.addProperty("entity", entity.getType().toString());
            throw new ActionException(ActionError.TARGET_NOT_FOUND,
                    "entity " + entityId + " is not a living entity, attack would be a no-op", detail);
        }
        LocalPlayer p = player();
        float[] healthBefore = onClient(() -> new float[]{living.getHealth(), living.getMaxHealth()});
        onClient(() -> {
            // 挥手臂的 ServerboundPunchPacket 单独发：26.3 的 LivingEntity.swing(hand, SwingAnimation, boolean)
            // 需要动画组件参数，而服务端真正在意的是这个包。
            mc().getConnection().send(ServerboundPunchPacket.INSTANCE);
            mc().gameMode.attack(p, living);
            return null;
        });
        sleep(150);
        float[] healthAfter = onClient(() -> new float[]{living.getHealth(), living.getMaxHealth()});

        JsonObject data = new JsonObject();
        data.addProperty("entityId", entityId);
        data.addProperty("entity", entity.getType().toString());
        data.addProperty("healthBefore", healthBefore[0]);
        data.addProperty("healthAfter", healthAfter[0]);
        data.addProperty("maxHealth", healthAfter[1]);
        data.addProperty("damaged", healthAfter[0] < healthBefore[0]);
        data.addProperty("packets", join(COVERAGE.get("attack")));
        return data;
    }

    /** {@code POST /action/pick {player,pos} 或 {player,entityId}} */
    private JsonObject pick(ActionRequest req) throws ActionException {
        requireMode(GameType.CREATIVE);
        LocalPlayer p = player();
        JsonObject data = new JsonObject();
        if (req.has("entityId")) {
            Entity entity = entity(req.i32("entityId"));
            boolean includeData = req.bool("includeData", true);
            onClient(() -> {
                mc().gameMode.handlePickItemFromEntity(entity, includeData);
                return null;
            });
            data.addProperty("target", "entity");
            data.addProperty("entityId", req.i32("entityId"));
        } else {
            int[] bp = req.blockPos("pos");
            BlockPos pos = new BlockPos(bp[0], bp[1], bp[2]);
            boolean includeData = req.bool("includeData", true);
            // 空气处没有可拾取的方块：26.3 的 handlePickItemFromBlock 会静默无动作，
            // 这里显式拒绝（与服务端后端 pick 的前置一致），否则调用方拿到 200 却什么都没变。
            boolean air = onClient(() -> mc().level.getBlockState(pos).isAir());
            if (air) {
                throw ActionException.targetNotFound("no pickable block at "
                        + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " (block is air)");
            }
            onClient(() -> {
                mc().gameMode.handlePickItemFromBlock(pos, includeData);
                return null;
            });
            data.addProperty("target", "block");
            data.add("pos", blockJson(pos));
        }
        sleep(100);
        data.add("held", itemJson(onClient(() -> p.getItemInHand(InteractionHand.MAIN_HAND))));
        data.addProperty("packets", join(COVERAGE.get("pick")));
        return data;
    }

    /** {@code POST /action/inventory {player,op:"set-selected"|"creative-set"|"click"|"button"|"drop"|"close",...}} */
    private JsonObject inventory(ActionRequest req) throws ActionException {
        String op = req.str("op");
        LocalPlayer p = player();
        JsonObject data = new JsonObject();
        data.addProperty("op", op);
        data.addProperty("packets", join(COVERAGE.get("inventory")));
        switch (op) {
            case "set-selected" -> {
                // 与服务端后端同一口径：热栏 0..8 与 InventoryMenu 36..44 都接受（区间不相交），
                // 因为 /player/inventory 与 /action/inventory click 报的都是菜单序号，两套编号最容易写错脚本。
                int raw = req.i32("slot");
                int slot = (raw >= 36 && raw <= 44) ? raw - 36 : raw;
                if (slot < 0 || slot > 8) {
                    throw ActionException.targetNotFound(
                            "slot out of range: " + raw + " (expected hotbar 0..8 or menu 36..44)");
                }
                onClient(() -> {
                    p.getInventory().setSelectedSlot(slot);
                    p.connection.send(new ServerboundSetCarriedItemPacket(slot));
                    return null;
                });
                data.addProperty("selectedSlot", slot);
                data.addProperty("menuSlot", 36 + slot);
            }
            case "creative-set" -> {
                requireMode(GameType.CREATIVE);
                // slot = InventoryMenu 的槽位号（1..45；9..35=主背包 9..35，36..44=快捷栏 0..8），
                // 因为 ServerboundSetCreativeModeSlotPacket 的 slot 就是这个编号，服务端只收 1..45。
                int slot = requireRange(req.i32("slot"), 1, 45, "slot");
                int invIndex = slot >= 36 ? slot - 36 : slot;
                ItemStack stack = stack(req);
                boolean infinite = onClient(() -> p.hasInfiniteMaterials());
                // 先走官方入口；26.3 的守卫是 hasInfiniteMaterials() + isFeatureEnabled()，
                // 任一不满足它会静默不发包 —— 所以下面按「读回」判定，不满足再直发一次。
                onClient(() -> {
                    mc().gameMode.handleCreativeModeItemAdd(stack, slot);
                    return null;
                });
                sleep(200);
                boolean applied = onClient(() -> {
                    ItemStack now = invIndex < p.getInventory().getContainerSize()
                            ? p.getInventory().getItem(invIndex) : ItemStack.EMPTY;
                    return ItemStack.isSameItemSameComponents(now, stack) && now.getCount() == stack.getCount();
                });
                boolean direct = false;
                if (!applied) {
                    direct = true;
                    onClient(() -> {
                        p.connection.send(new ServerboundSetCreativeModeSlotPacket(slot, stack));
                        return null;
                    });
                    sleep(250);
                    applied = onClient(() -> {
                        ItemStack now = invIndex < p.getInventory().getContainerSize()
                                ? p.getInventory().getItem(invIndex) : ItemStack.EMPTY;
                        return ItemStack.isSameItemSameComponents(now, stack) && now.getCount() == stack.getCount();
                    });
                }
                if (slot >= 36 && slot <= 44) {
                    onClient(() -> {
                        p.getInventory().setSelectedSlot(slot - 36);
                        p.connection.send(new ServerboundSetCarriedItemPacket(slot - 36));
                        return null;
                    });
                }
                ItemStack left = onClient(() -> invIndex < p.getInventory().getContainerSize()
                        ? p.getInventory().getItem(invIndex) : ItemStack.EMPTY);
                String menuName = onClient(() -> p.containerMenu.getClass().getSimpleName());
                if (!applied) {
                    // 26.3 Fabric 实测：客户端 hasInfiniteMaterials()=true、服务端 abilities.instabuild=1b，
                    // 服务端仍不处理 ServerboundSetCreativeModeSlotPacket（官方入口与直发各试一次都不生效）。
                    // 按「静默失败 → 响亮失败」原则（架构 §8.1）不让调用方误以为已拿到物品。
                    JsonObject detail = new JsonObject();
                    detail.addProperty("slot", slot);
                    detail.addProperty("inventoryIndex", invIndex);
                    detail.add("stack", itemJson(stack));
                    detail.add("applied", itemJson(left));
                    detail.addProperty("directPacket", direct);
                    detail.addProperty("infiniteMaterials", infinite);
                    detail.addProperty("menu", menuName);
                    detail.addProperty("hint", "provision items with POST /command {\"cmd\":\"give <player> <item> <count>\"}; "
                            + "see docs/p4a-action-layer-record.md for the evidence");
                    throw new ActionException(ActionError.TRAIT_UNAVAILABLE,
                            "creative-set did not take effect (both the official entry and a direct packet were ignored)",
                            detail);
                }
                data.addProperty("slot", slot);
                data.addProperty("inventoryIndex", invIndex);
                data.add("stack", itemJson(stack));
                data.add("applied", itemJson(left));
                data.addProperty("appliedMatches", true);
                data.addProperty("directPacket", direct);
                data.addProperty("infiniteMaterials", infinite);
                data.addProperty("menu", menuName);
            }
            case "pickup", "click" -> {
                int slot = req.i32("slot");
                AbstractContainerMenu menu = onClient(() -> p.containerMenu);
                requireRange(slot, 0, menu.slots.size() - 1, "slot");
                int button = req.i32("button", 0);
                ContainerInput input = containerInput(req, "input", ContainerInput.PICKUP);
                onClient(() -> {
                    mc().gameMode.handleContainerInput(menu.containerId, slot, button, input, p);
                    return null;
                });
                data.addProperty("containerId", menu.containerId);
                data.addProperty("slot", slot);
                data.addProperty("button", button);
                data.addProperty("input", input.name());
            }
            case "button" -> {
                int button = req.i32("buttonId");
                AbstractContainerMenu menu = onClient(() -> p.containerMenu);
                onClient(() -> {
                    mc().gameMode.handleInventoryButtonClick(menu.containerId, button);
                    p.connection.send(new ServerboundContainerButtonClickPacket(menu.containerId, button));
                    return null;
                });
                data.addProperty("containerId", menu.containerId);
                data.addProperty("buttonId", button);
            }
            case "drop" -> {
                boolean all = req.bool("all", false);
                GameType mode = currentMode();
                if (mode == GameType.CREATIVE) {
                    ItemStack held = onClient(() -> p.getInventory().getSelectedItem());
                    onClient(() -> {
                        mc().gameMode.handleCreativeModeItemDrop(held);
                        return null;
                    });
                    data.add("dropped", itemJson(held));
                } else {
                    onClient(() -> {
                        p.connection.send(new ServerboundPlayerActionPacket(
                                all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                                    : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                                BlockPos.ZERO, Direction.DOWN, 0));
                        return null;
                    });
                }
                data.addProperty("all", all);
                data.addProperty("mode", mode.getName());
            }
            case "close" -> {
                AbstractContainerMenu menu = onClient(() -> p.containerMenu);
                onClient(() -> {
                    p.connection.send(new ServerboundContainerClosePacket(menu.containerId));
                    return null;
                });
                data.addProperty("containerId", menu.containerId);
                data.addProperty("menu", menu.getClass().getSimpleName());
            }
            default -> throw ActionException.badRequest(
                    "unknown op '" + op + "' (expected set-selected|creative-set|pickup|click|button|drop|close)");
        }
        return data;
    }

    /** {@code POST /action/gamemode {player,mode:"survival"|"creative"|"adventure"|"spectator"}} */
    private JsonObject gamemode(ActionRequest req) throws ActionException {
        String raw = req.str("mode").toLowerCase(Locale.ROOT);
        // 走严格解析：GameType.byName(raw) 的未知输入会静默回 SURVIVAL（26.3 实测）。
        GameType target = GameTypes.parse(raw);
        LocalPlayer p = player();
        GameType before = currentMode();
        onClient(() -> {
            p.connection.send(new ServerboundChangeGameModePacket(target));
            return null;
        });
        sleep(200);
        GameType after = currentMode();

        JsonObject data = new JsonObject();
        data.addProperty("requested", target.getName());
        data.addProperty("before", before.getName());
        data.addProperty("after", after.getName());
        data.addProperty("applied", after == target);
        data.addProperty("packets", join(COVERAGE.get("gamemode")));
        if (after != target) {
            data.addProperty("note", "server did not apply it (no permission / op level too low); "
                    + "the local mode follows the server, so 'after' is authoritative");
        }
        return data;
    }

    /** {@code POST /action/ability {player,flying?:bool,invulnerable?:bool}} */
    private JsonObject ability(ActionRequest req) throws ActionException {
        if (!req.has("flying") && !req.has("invulnerable")) {
            throw ActionException.badRequest("ability needs at least one of 'flying' / 'invulnerable'");
        }
        LocalPlayer p = player();
        Boolean flying = req.has("flying") ? req.bool("flying", false) : null;
        Boolean invulnerable = req.has("invulnerable") ? req.bool("invulnerable", false) : null;
        JsonObject data = onClient(() -> {
            Abilities abilities = p.getAbilities();
            JsonObject obj = new JsonObject();
            obj.addProperty("flyingBefore", abilities.flying);
            obj.addProperty("invulnerableBefore", abilities.invulnerable);
            if (flying != null) {
                // 不碰 mayfly：creative / spectator 下服务端已经通过 ClientboundPlayerAbilitiesPacket
                // 把 mayfly 置 true，自己再写一遍既是多余的，又会在 NeoForge 上触发 @Deprecated 警告
                // （Abilities.mayfly 被 NeoForge 补丁标了 @Deprecated，而 shared 源码三家共用）。
                // 生存模式下没有 mayfly，写 flying=true 会被客户端下一 tick 拉回地面 —— 所以本动作
                // 的前置是 creative / spectator，由 requireMode 拦住。
                abilities.flying = flying;
            }
            if (invulnerable != null) {
                abilities.invulnerable = invulnerable;
            }
            p.onUpdateAbilities();
            obj.addProperty("flyingAfter", abilities.flying);
            obj.addProperty("invulnerableAfter", abilities.invulnerable);
            return obj;
        });
        data.addProperty("packets", join(COVERAGE.get("ability")));
        return data;
    }

    /** {@code POST /action/spectate {player,entityId}} */
    private JsonObject spectate(ActionRequest req) throws ActionException {
        requireMode(GameType.SPECTATOR);
        int entityId = req.i32("entityId");
        Entity entity = entity(entityId);
        LocalPlayer p = player();
        onClient(() -> {
            mc().gameMode.spectate(entity);
            return null;
        });
        sleep(200);
        JsonObject data = new JsonObject();
        data.addProperty("entityId", entityId);
        data.addProperty("entity", entity.getType().toString());
        String camera = cameraEntity();
        data.addProperty("cameraEntity", camera);
        // 服务端只在玩家真是旁观者时才接受附身请求；没接上时相机仍是自己，
        // 这时候不给 note 就等于把「没生效」伪装成成功（实测踩过一次）。
        if (camera != null && !camera.contains("player")) {
            data.addProperty("attached", true);
        } else {
            data.addProperty("attached", false);
            data.addProperty("note", "camera did not attach to the target; the server only honours "
                    + "SpectatorAction when the player is actually in spectator mode (check /player/state.gamemode)");
        }
        data.addProperty("packets", join(COVERAGE.get("spectate")));
        return data;
    }

    /** {@code POST /action/respawn {player}} */
    private JsonObject respawn(ActionRequest req) throws ActionException {
        LocalPlayer p = player();
        if (!onClient(p::isDeadOrDying)) {
            throw ActionException.badRequest("player is not dead; PERFORM_RESPAWN would be a no-op");
        }
        onClient(() -> {
            p.connection.send(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            return null;
        });
        sleep(1200);
        // 重生会替换整个 LocalPlayer 实例（ClientPacketListener.handleRespawn → LocalPlayer.respawn），
        // 所以读回必须重新取 player()，否则读到的是那个已经作废的旧对象（永远 shown as dead）。
        LocalPlayer now = player();
        JsonObject data = new JsonObject();
        data.addProperty("requested", true);
        data.addProperty("alive", !onClient(now::isDeadOrDying));
        data.addProperty("health", onClient(now::getHealth));
        data.add("position", onClient(() -> vecJson(now.position())));
        data.addProperty("note", "alive/health are read 1.2 s after PERFORM_RESPAWN; the respawn replaces the LocalPlayer instance");
        data.addProperty("packets", join(COVERAGE.get("respawn")));
        return data;
    }

    /** {@code POST /action/sleep {player,bedPos}} */
    private JsonObject sleep(ActionRequest req) throws ActionException {
        int[] bp = req.blockPos("bedPos");
        // 创造模式也能睡觉（只有旁观者不行），所以这里放行 survival + creative。
        requireMode(GameType.SURVIVAL, GameType.CREATIVE);
        LocalPlayer p = player();
        BlockPos pos = new BlockPos(bp[0], bp[1], bp[2]);
        BlockState state = onClient(() -> level().getBlockState(pos));
        if (!(state.getBlock() instanceof BedBlock)) {
            JsonObject detail = new JsonObject();
            detail.add("bedPos", blockJson(pos));
            detail.addProperty("block", blockId(state));
            throw new ActionException(ActionError.TARGET_NOT_FOUND,
                    "block at " + pos + " is not a bed", detail);
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = onClient(() -> mc().gameMode.useItemOn(p, hand, hit));
        sleep(300);
        boolean sleeping = onClient(p::isSleeping);
        sleep(600);
        sleeping = sleeping || onClient(p::isSleeping);
        JsonObject data = new JsonObject();
        data.add("bedPos", blockJson(pos));
        data.addProperty("result", String.valueOf(result));
        data.addProperty("sleeping", sleeping);
        if (!sleeping) {
            // 服务端拒绝入睡时客户端只看到一次 useItemOn 成功预测 —— 这里补一条可判定的原因提示，
            // 免得调用方以为「200 就是睡着了」。
            data.addProperty("hint", "the bed was clicked but the server did not put the player to sleep: "
                    + "check night time (/time set midnight), monsters nearby, and that the bed is not occupied");
        }
        data.addProperty("packets", join(COVERAGE.get("sleep")));
        return data;
    }

    // ------------------------------------------------------------------
    // 前置校验与工具
    // ------------------------------------------------------------------

    /**
     * 三段式校验的第二段：当前 GameType 必须在允许集合内，否则抛 {@code GAMEMODE_MISMATCH}。
     *
     * @return 当前模式，供调用方复用（避免再 hop 一次）
     */
    private static GameType requireMode(GameType... allowed) throws ActionException {
        GameType current = currentMode();
        for (GameType candidate : allowed) {
            if (candidate == current) {
                return current;
            }
        }
        JsonObject detail = new JsonObject();
        detail.addProperty("current", current.getName());
        JsonArray list = new JsonArray();
        for (GameType candidate : allowed) {
            list.add(candidate.getName());
        }
        detail.add("allowed", list);
        throw new ActionException(ActionError.GAMEMODE_MISMATCH,
                "this action requires gamemode " + list + " but player is in " + current.getName(), detail);
    }

    private static GameType currentMode() throws ActionException {
        return onClient(() -> {
            MultiPlayerGameMode gameMode = mc().gameMode;
            if (gameMode == null) {
                throw ActionException.playerNotFound("client is not connected to a server");
            }
            return gameMode.getPlayerMode();
        });
    }

    private static LocalPlayer player() throws ActionException {
        return onClient(() -> {
            LocalPlayer p = mc().player;
            if (p == null || mc().level == null) {
                throw ActionException.playerNotFound("client is not in a world");
            }
            return p;
        });
    }

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static Entity entity(int entityId) throws ActionException {
        Entity entity = onClient(() -> level().getEntity(entityId));
        if (entity == null) {
            throw ActionException.targetNotFound("entity id " + entityId + " is not loaded on this client");
        }
        return entity;
    }

    /** 当前摄像机附着的实体（旁观时才是被附身的那个），用于验证 {@code /action/spectate} 生效。 */
    private static String cameraEntity() throws ActionException {
        return onClient(() -> {
            Entity camera = mc().getCameraEntity();
            return camera == null ? "null" : camera.getType().toString();
        });
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /**
     * 把一段操作搬到客户端主线程执行并等结果。
     *
     * <p>为什么必须这么绕：Minecraft 的对象没有线程安全，从 HTTP 线程直接读 {@code player.position()}
     * 大概率读到半更新的状态、甚至触发 CME。单步 hop 也保证「一次 sleep 的时间里游戏能继续 tick」，
     * 挖方块 / 走路这类需要多 tick 的动作因此在 HTTP 线程上循环、而不是阻塞主线程。</p>
     */
    private static <T> T onClient(Callable<T> task) throws ActionException {
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture<T> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(CLIENT_HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            JsonObject detail = new JsonObject();
            detail.addProperty("timeoutMs", CLIENT_HOP_TIMEOUT_MS);
            throw ActionException.timeout("client main thread did not answer within "
                    + CLIENT_HOP_TIMEOUT_MS + " ms (world loading or deadlock)", detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ActionException.internal("interrupted while waiting for the client main thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ActionException actionException) {
                throw actionException;
            }
            throw ActionException.internal("client action failed: " + cause, cause);
        }
    }

    /**
     * 把本地玩家的朝向对准某个方块的中心（并同步给服务端）。
     *
     * <p>这不是装饰：在 26.3 上，{@code MultiPlayerGameMode.continueDestroyBlock} 与
     * {@code startDestroyBlock} 都要求玩家的命中目标就是该方块，准星对着别处时挖掘不会开始
     * ——实测表现为 {@code /action/mine} 超时、服务端一个 START 包都没收到。
     */
    private void aimAt(BlockPos pos) throws ActionException {
        onClient(() -> {
            LocalPlayer lp = mc().player;
            if (lp == null) {
                throw new IllegalStateException("client is not in a world");
            }
            Vec3 eye = lp.getEyePosition();
            Vec3 center = Vec3.atCenterOf(pos);
            double dx = center.x - eye.x;
            double dy = center.y - eye.y;
            double dz = center.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            lp.setYRot(yaw);
            lp.setXRot(pitch);
            // 只发朝向包，不发位置包：26.3 服务端对同一 tick 内第二个带位置字段的包会判
            // "Invalid move player packet received" 直接断线（P4 实测踩过）。
            lp.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, lp.onGround(), false));
            return null;
        });
    }
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Direction direction(ActionRequest req, String key, Direction def) throws ActionException {
        String raw = req.str(key, null);
        if (raw == null) {
            return def;
        }
        Direction dir = Direction.byName(raw.toLowerCase(Locale.ROOT));
        if (dir == null) {
            throw ActionException.badRequest("unknown direction '" + raw
                    + "' (expected north|south|east|west|up|down)");
        }
        return dir;
    }

    private static InteractionHand hand(ActionRequest req, String key, InteractionHand def) throws ActionException {
        String raw = req.str(key, null);
        if (raw == null) {
            return def;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if ("main".equals(normalized) || "main_hand".equals(normalized)) {
            return InteractionHand.MAIN_HAND;
        }
        if ("off".equals(normalized) || "off_hand".equals(normalized)) {
            return InteractionHand.OFF_HAND;
        }
        throw ActionException.badRequest("unknown hand '" + raw + "' (expected main|off)");
    }

    private static ContainerInput containerInput(ActionRequest req, String key, ContainerInput def)
            throws ActionException {
        String raw = req.str(key, null);
        if (raw == null) {
            return def;
        }
        try {
            return ContainerInput.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ActionException.badRequest("unknown container input '" + raw
                    + "' (expected PICKUP|QUICK_MOVE|SWAP|CLONE|THROW|QUICK_CRAFT|PICKUP_ALL)");
        }
    }

    private static int requireRange(int value, int min, int max, String name) throws ActionException {
        if (value < min || value > max) {
            JsonObject detail = new JsonObject();
            detail.addProperty("value", value);
            detail.addProperty("min", min);
            detail.addProperty("max", max);
            throw new ActionException(ActionError.TARGET_NOT_FOUND,
                    name + " out of range: " + value + " not in [" + min + "," + max + "]", detail);
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 把 {@code "item"} 解析成物品栈：接受 {@code "minecraft:stone"}、{@code "stone"}，
     * 以及省略时的空气；{@code count} 默认 1，上限 99。
     */
    private static ItemStack stack(ActionRequest req) throws ActionException {
        String raw = req.str("item", "minecraft:air");
        int count = clamp(req.i32("count", 1), 0, 99);
        if (count == 0 || raw.isEmpty() || "air".equals(raw) || "minecraft:air".equals(raw)) {
            return ItemStack.EMPTY;
        }
        Identifier id = raw.contains(":")
                ? Identifier.parse(raw)
                : Identifier.fromNamespaceAndPath("minecraft", raw);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null) {
            throw ActionException.targetNotFound("unknown item '" + raw + "'");
        }
        return new ItemStack(item, count);
    }

    private static JsonObject itemJson(ItemStack stack) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", stack.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        obj.addProperty("count", stack.getCount());
        return obj;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static JsonObject vecJson(Vec3 vec) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", vec.x);
        obj.addProperty("y", vec.y);
        obj.addProperty("z", vec.z);
        return obj;
    }

    private static JsonObject blockJson(BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    // ---------------------------------------------------------------- 跨端：调用服务端探针

    /** 服务端探针基址；去掉结尾的 {@code /} 免得拼出 {@code //command}。 */
    public static String serverProbeUrl() {
        String url = System.getProperty(SERVER_PROBE_URL_PROP, DEFAULT_SERVER_PROBE_URL).trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String serverProbeToken() {
        return System.getProperty(TOKEN_PROP, "mcdev");
    }

    private static int serverProbeTimeoutMs() {
        return Integer.getInteger(SERVER_PROBE_TIMEOUT_PROP, DEFAULT_SERVER_PROBE_TIMEOUT_MS);
    }

    private static HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    // 探针之间永远是回环/LAN 请求，绝不能走 JVM 的 http.proxyHost ——
                    // 本机开发环境为了 gradle 拉依赖常设本地代理，
                    // 代理会把 POST 转发出去然后卡住，表现为 5s 超时（实测踩过）。
                    .proxy(HttpClient.Builder.NO_PROXY)
                    // 必须钉 HTTP/1.1：JDK HttpClient 默认先试 HTTP/2（明文就是 h2c Upgrade），
                    // 而探针那边是 com.sun.net.httpserver.HttpServer —— 它既不认 h2c、也不会
                    // 明确拒绝，客户端就一直等，表现为 5s 超时（实测：同样请求 PowerShell 49ms 成功，
                    // JDK HttpClient 默认版本必超时，加上这一行立刻 49ms）。
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(serverProbeTimeoutMs()))
                    .build();
        }
        return httpClient;
    }

    /**
     * 调服务端探针（GET 时 {@code body} 传 null）。
     *
     * <p>探针不可达映射成 {@link ActionError#TRAIT_UNAVAILABLE}（501）而不是 500：
     * 客户端本身没坏，坏的是「这次测试没有把服务端探针接上」——501 的语义正是
     * 「这条动作需要的能力在本环境不可用」，与「服务端不允许」区分得开。</p>
     */
    private static JsonObject callServerProbe(String method, String path, JsonObject body) throws ActionException {
        String url = serverProbeUrl() + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(serverProbeTimeoutMs()));
        String token = serverProbeToken();
        if (!token.isEmpty()) {
            builder.header("X-Mcdev-Token", token);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = http().send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ActionException.internal("interrupted while calling the server probe at " + url, e);
        } catch (java.io.IOException | IllegalArgumentException e) {
            JsonObject detail = new JsonObject();
            detail.addProperty("url", url);
            detail.addProperty("cause", e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new ActionException(ActionError.TRAIT_UNAVAILABLE,
                    "server probe unreachable at " + serverProbeUrl()
                            + " (start the server with the probe, or give the client -D"
                            + SERVER_PROBE_URL_PROP + "=<url>)", detail);
        }

        String text = response.body() == null ? "" : response.body().trim();
        JsonObject json;
        try {
            json = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            JsonObject detail = new JsonObject();
            detail.addProperty("url", url);
            detail.addProperty("httpStatus", response.statusCode());
            detail.addProperty("body", text.length() > 500 ? text.substring(0, 500) : text);
            throw new ActionException(ActionError.INTERNAL,
                    "the server probe answered with a non-JSON body (HTTP " + response.statusCode() + ")", detail);
        }
        return json;
    }

    private static boolean isOk(JsonObject response) {
        return response.has("ok") && response.get("ok").isJsonPrimitive() && response.get("ok").getAsBoolean();
    }

    /** 解析服务端探针 {@code /player/state} 的 {@code position} 字段（{@code "x,y,z"}）。 */
    private static Vec3 parsePosition(String text) throws ActionException {
        String[] parts = text == null ? new String[0] : text.split(",");
        if (parts.length != 3) {
            throw ActionException.internal("the server probe returned an unparsable position: " + text,
                    new IllegalArgumentException(String.valueOf(text)));
        }
        try {
            return new Vec3(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()));
        } catch (NumberFormatException e) {
            throw ActionException.internal("the server probe returned an unparsable position: " + text, e);
        }
    }

    /** 坐标格式化：{@code /tp} 要的是十进制文本，固定 3 位小数（避免 {@code 1.0E-4} 这种科学计数）。 */
    private static String coord(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double round3(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private static String join(List<String> values) {
        return String.join(",", values);
    }
}

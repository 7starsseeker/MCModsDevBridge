package com.mcdev.probe.action;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务端侧的动作后端（架构 §8.2 的第二个后端）。
 *
 * <p><b>定位是「观察者 + 环境控制」，不是「角色驱动」</b>（用户 2026-10-19 的决定，架构 §8.2）：
 * 服务端没有客户端输入栈，也没有 {@code MultiPlayerGameMode}，要让角色真走路/真挖方块得先造假玩家
 * （FakePlayer，已降级为可选增强）。所以这里只做四件服务端**天然权威**的事：
 *
 * <table border="1">
 *   <caption>动作与实现手段</caption>
 *   <tr><th>action</th><th>实现</th><th>为什么服务端能做</th></tr>
 *   <tr><td>{@code teleport}</td><td>{@code ServerPlayer.teleportTo}</td><td>服务端位置是唯一权威，天然立刻生效</td></tr>
 *   <tr><td>{@code gamemode}</td><td>{@code ServerPlayer.setGameMode}</td><td>命令层之下就是这个方法</td></tr>
 *   <tr><td>{@code ability}</td><td>{@code Abilities} + {@code onUpdateAbilities()}</td><td>同步用 {@code ClientboundPlayerAbilitiesPacket}</td></tr>
 *   <tr><td>{@code inventory}</td><td>{@code AbstractContainerMenu.clicked(...)}</td><td>**一次覆盖 29 个 {@code *Menu}**（架构 §8.3 的最高性价比项）</td></tr>
 * </table>
 *
 * <p>读写全部丢给 {@code mc.execute(...)}（服务端主线程），HTTP 线程只等结果，
 * 与 {@code ClientActionBackend} 的线程模型一致：**长动作在 HTTP 线程等，不在主线程 sleep**。
 */
public final class ServerActionBackend implements ActionBackend {

    private static final Gson GSON = new Gson();
    private static final int ON_SERVER_TIMEOUT_MS = 10_000;

    /**
     * 每个动作在协议层牵动的包，写进响应供调用方判断「这条路走的是哪一格」。
     *
     * <p>名字都已核实存在于 26.3 客户端 jar 的 {@code net/minecraft/network/protocol/game/} 下
     * （服务端用它们回同步客户端）；{@code gamemode} 的发送点连字节码都看过：
     * {@code ServerPlayer.setGameMode} → {@code ClientboundGameEventPacket.CHANGE_GAME_MODE}。
     */
    private static final Map<String, List<String>> COVERAGE = new LinkedHashMap<>();

    static {
        COVERAGE.put("teleport", List.of("ClientboundPlayerPositionPacket"));
        COVERAGE.put("gamemode", List.of("ClientboundGameEventPacket"));
        COVERAGE.put("ability", List.of("ClientboundPlayerAbilitiesPacket"));
        // 以下五个是「服务端代跑」：不需要客户端输入，也不需要客户端在 GUI 里做什么，
        // 但**需要玩家在线**（动作作用在一个真实的 ServerPlayer 上，不是假玩家）。
        COVERAGE.put("mine", List.of(
                "ServerboundPlayerActionPacket(destroy-block)",
                "ClientboundBlockUpdatePacket",
                "ClientboundBlockDestructionPacket",
                "ClientboundBlockChangedAckPacket"));
        COVERAGE.put("place", List.of(
                "ServerboundUseItemOnPacket",
                "ClientboundBlockUpdatePacket",
                "ClientboundContainerSetContentPacket"));
        COVERAGE.put("use", List.of(
                "ServerboundUseItemOnPacket",
                "ServerboundUseItemPacket",
                "ClientboundBlockUpdatePacket"));
        COVERAGE.put("attack", List.of(
                "ServerboundAttackPacket",
                "ClientboundDamageEventPacket",
                "ClientboundSetEntityDataPacket"));
        COVERAGE.put("pick", List.of(
                "ServerboundPickItemFromBlockPacket",
                "ClientboundContainerSetContentPacket",
                "ClientboundSetHeldSlotPacket"));
        COVERAGE.put("inventory", List.of(
                "ClientboundContainerSetSlotPacket",
                "ClientboundContainerSetContentPacket",
                "ClientboundSetHeldSlotPacket",
                "ClientboundContainerClosePacket",
                // 以下五条是「服务端直接执行 serverbound 包的语义」：不发包，但覆盖同一动作面（架构 §10.5 的 58 包口径）
                "ServerboundPlayerActionPacket(DROP_ITEM|DROP_ALL_ITEMS)",
                "ServerboundContainerClosePacket",
                "ServerboundContainerButtonClickPacket",
                "ServerboundSelectTradePacket",
                "ServerboundRenameItemPacket"));
    }

    private final MinecraftServer mc;

    public ServerActionBackend(MinecraftServer server) {
        this.mc = server;
    }

    @Override
    public String name() {
        return "server";
    }

    @Override
    public Set<String> actions() {
        return new LinkedHashSet<>(COVERAGE.keySet());
    }

    @Override
    public JsonObject execute(ActionRequest req) throws ActionException {
        String action = req.str("action");
        JsonObject data = switch (action) {
            case "teleport" -> teleport(req);
            case "gamemode" -> gamemode(req);
            case "ability" -> ability(req);
            case "inventory" -> inventory(req);
            case "mine" -> mine(req);
            case "place" -> place(req);
            case "use" -> use(req);
            case "attack" -> attack(req);
            case "pick" -> pick(req);
            default -> throw ActionException.badRequest("unknown action: " + action);
        };
        data.add("packets", GSON.toJsonTree(COVERAGE.get(action)));
        data.addProperty("backend", name());
        return data;
    }

    // ------------------------------------------------------------------ 动作

    private JsonObject teleport(ActionRequest req) throws ActionException {
        double[] to = req.vec3("to");
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            String before = pos(p);
            // 服务端传送是「写世界状态」而不是「改客户端位置」：不受 player_movement_check 影响，
            // 也不需要构造任何 serverbound 包（那些是客户端才发的东西）。
            p.teleportTo(to[0], to[1], to[2]);
            if (req.has("look")) {
                float[] look = req.yawPitch("look");
                p.setYRot(look[0]);
                p.setXRot(look[1]);
            }
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("before", before);
            data.addProperty("after", pos(p));
            double dx = p.getX() - to[0];
            double dz = p.getZ() - to[2];
            data.addProperty("horizontalError", round3(Math.hypot(dx, dz)));
            data.addProperty("applied", Math.hypot(dx, dz) <= 0.75);
            return data;
        });
    }

    private JsonObject gamemode(ActionRequest req) throws ActionException {
        String raw = req.str("mode");
        // 严格解析：GameType.byName(raw) 的未知输入静默回 SURVIVAL（26.3 字节码实测，记录 §12）。
        GameType target = GameTypes.parse(raw);
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            GameType before = p.gameMode();
            p.setGameMode(target);
            GameType after = p.gameMode();
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("before", String.valueOf(before));
            data.addProperty("after", String.valueOf(after));
            // applied 是「现在处于请求的模式」这个状态判据，不是「这次调用有没有改动」——
            // 幂等调用（本来就是目标模式）必须算成功，否则调用方会被一个假的失败困住。
            data.addProperty("applied", after == target);
            data.addProperty("changed", before != after);
            return data;
        });
    }

    private JsonObject ability(ActionRequest req) throws ActionException {
        Boolean flying = req.has("flying") ? Boolean.valueOf(req.bool("flying", false)) : null;
        Boolean invulnerable = req.has("invulnerable")
                ? Boolean.valueOf(req.bool("invulnerable", false))
                : null;
        if (flying == null && invulnerable == null) {
            throw ActionException.badRequest("ability needs at least one of flying / invulnerable");
        }
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            boolean flyingBefore = p.getAbilities().flying;
            boolean invulnerableBefore = p.getAbilities().invulnerable;
            if (flying != null) {
                p.getAbilities().flying = flying.booleanValue();
            }
            if (invulnerable != null) {
                p.getAbilities().invulnerable = invulnerable.booleanValue();
            }
            p.onUpdateAbilities();
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("flyingBefore", flyingBefore);
            data.addProperty("flyingAfter", p.getAbilities().flying);
            data.addProperty("invulnerableBefore", invulnerableBefore);
            data.addProperty("invulnerableAfter", p.getAbilities().invulnerable);
            if (Boolean.TRUE.equals(flying) && !p.getAbilities().flying) {
                data.addProperty("note", "server reset flying=true right away; "
                        + "grant creative first (POST /action/gamemode) so mayfly is set");
            }
            return data;
        });
    }

    private JsonObject inventory(ActionRequest req) throws ActionException {
        String op = req.str("op");
        String name = req.str("player", null);
        return switch (op) {
            case "give" -> give(req, name);
            case "clear" -> clear(name);
            case "set-selected" -> setSelected(req, name);
            case "click" -> click(req, name);
            case "slots" -> slots(name);
            case "drop" -> drop(req, name);
            case "close" -> close(name);
            case "button" -> button(req, name);
            case "trade" -> trade(req, name);
            case "rename" -> rename(req, name);
            default -> throw ActionException.badRequest("unknown inventory op: " + op
                    + " (server backend supports give|clear|set-selected|click|slots"
                    + "|drop|close|button|trade|rename)");
        };
    }

    private JsonObject give(ActionRequest req, String name) throws ActionException {
        ItemStack stack = stack(req);
        // slot 可选：不传就是「塞进背包任意空位」（人类语义），传了就是「放进这个快捷栏格」。
        // 后者是给自动化用的 —— 实测 Forge 那台服的 testworld 里玩家快捷栏 0 还留着旧测试的腐肉，
        // 于是 give+set-selected 之后主手是腐肉、place 静默失败（详情见记录 §16.7）。
        boolean hasSlot = req.has("slot");
        int rawSlot = hasSlot ? req.i32("slot") : -1;
        boolean select = req.bool("select", false);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            JsonObject data = new JsonObject();
            data.addProperty("op", "give");
            data.addProperty("player", p.getName().getString());
            if (hasSlot) {
                int hotbar = (rawSlot >= 36 && rawSlot <= 44) ? rawSlot - 36 : rawSlot;
                if (hotbar < 0 || hotbar > 8) {
                    throw ActionException.badRequest("slot out of range: " + rawSlot
                            + " (expected hotbar 0..8 or menu 36..44)");
                }
                p.getInventory().setItem(hotbar, stack);
                p.inventoryMenu.broadcastChanges();
                data.addProperty("added", true);
                data.addProperty("slot", hotbar);
                data.addProperty("menuSlot", 36 + hotbar);
                if (select) {
                    p.getInventory().setSelectedSlot(hotbar);
                    p.connection.send(new ClientboundSetHeldSlotPacket(hotbar));
                }
            } else {
                boolean added = p.getInventory().add(stack);
                data.addProperty("added", added);
                if (!added) {
                    data.addProperty("note", "inventory was full; the stack was dropped at the player");
                }
            }
            data.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
            data.addProperty("item", itemId(p.getMainHandItem()));
            data.addProperty("count", p.getMainHandItem().getCount());
            return data;
        });
    }

    private JsonObject clear(String name) throws ActionException {
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            p.getInventory().clearContent();
            JsonObject data = new JsonObject();
            data.addProperty("op", "clear");
            data.addProperty("player", p.getName().getString());
            data.addProperty("cleared", true);
            return data;
        });
    }

    /**
     * 切换主手槽位。
     *
     * <p>两种编号都接受：<b>热栏序号</b> {@code 0..8}，或 {@code /action/inventory {"op":"slots"}}
     * 报出的<b>菜单序号</b> {@code 36..44}（后者与 {@code click} 的 {@code slot} 同一种编号）。
     * 之所以两种都收：实测里「slots 报 36，set-selected 要 0」这种编号漂移最容易让人写错脚本，
     * 而这里两种编号**没有歧义**（区间不相交），所以由后端归一，而不是让调用方记住两套。
     */
    private JsonObject setSelected(ActionRequest req, String name) throws ActionException {
        int raw = req.i32("slot");
        int slot = (raw >= 36 && raw <= 44) ? raw - 36 : raw;
        if (slot < 0 || slot > 8) {
            throw ActionException.badRequest("slot out of range: " + raw
                    + " (expected hotbar 0..8 or menu 36..44)");
        }
        final int hotbar = slot;
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            p.getInventory().setSelectedSlot(hotbar);
            p.connection.send(new ClientboundSetHeldSlotPacket(hotbar));
            JsonObject data = new JsonObject();
            data.addProperty("op", "set-selected");
            data.addProperty("player", p.getName().getString());
            data.addProperty("slot", hotbar);
            data.addProperty("menuSlot", 36 + hotbar);
            data.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
            data.addProperty("mainhand", itemId(p.getMainHandItem()));
            data.addProperty("count", p.getMainHandItem().getCount());
            return data;
        });
    }

    private JsonObject click(ActionRequest req, String name) throws ActionException {
        int slot = req.i32("slot");
        int button = req.i32("button", 0);
        String raw = req.str("input", "pickup");
        ContainerInput input;
        try {
            input = ContainerInput.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ActionException.badRequest("unknown container input: " + raw
                    + " (expected pickup|quick_move|swap|clone|throw|quick_craft|pickup_all)");
        }
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu menu = p.containerMenu;
            int slotCount = menu.slots.size();
            if (slot < 0 || slot >= slotCount) {
                throw ActionException.targetNotFound("slot out of range: " + slot
                        + " (menu " + menuName(menu) + " has " + slotCount + " slots)");
            }
            menu.clicked(slot, button, input, p);
            menu.broadcastChanges();
            JsonObject data = new JsonObject();
            data.addProperty("op", "click");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menu", menuName(menu));
            data.addProperty("slot", slot);
            data.addProperty("button", button);
            data.addProperty("input", input.name());
            data.addProperty("carried", itemId(menu.getCarried()));
            data.addProperty("carriedCount", menu.getCarried().getCount());
            data.addProperty("slotItem", itemId(menu.getSlot(slot).getItem()));
            data.addProperty("slotCount", menu.getSlot(slot).getItem().getCount());
            return data;
        });
    }

    private JsonObject slots(String name) throws ActionException {
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu menu = p.containerMenu;
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.getSlot(i);
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", i);
                entry.put("containerSlot", slot.getContainerSlot());
                entry.put("item", itemId(stack));
                entry.put("count", stack.getCount());
                items.add(entry);
            }
            JsonObject data = new JsonObject();
            data.addProperty("op", "slots");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menu", menuName(menu));
            data.addProperty("containerId", menu.containerId);
            data.addProperty("slotCount", menu.slots.size());
            data.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
            data.addProperty("carried", itemId(menu.getCarried()));
            data.add("items", GSON.toJsonTree(items));
            return data;
        });
    }

    /**
     * 丢出手上的物品（{@code ServerboundPlayerActionPacket} 的 {@code DROP_ITEM} / {@code DROP_ALL_ITEMS} 对应的服务端语义）。
     *
     * <p>服务端不需要客户端也不需要在场：{@code ServerPlayer.drop(boolean)} 就是服务端处理这两个子动作时
     * 实际调用的方法（{@code ServerGamePacketListenerImpl.handlePlayerAction} 的两个分支都落到它）。
     */
    private JsonObject drop(ActionRequest req, String name) throws ActionException {
        boolean all = req.bool("all", false);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            ItemStack before = p.getMainHandItem().copy();
            p.drop(all);
            JsonObject data = new JsonObject();
            data.addProperty("op", "drop");
            data.addProperty("player", p.getName().getString());
            data.addProperty("all", all);
            data.addProperty("dropped", itemId(before));
            data.addProperty("count", before.getCount());
            data.addProperty("mainhand", itemId(p.getMainHandItem()));
            data.addProperty("mainhandCount", p.getMainHandItem().getCount());
            if (before.isEmpty()) {
                data.addProperty("note", "nothing was held; the call succeeded but no item entity was created");
            }
            return data;
        });
    }

    /**
     * 关闭当前容器界面（{@code ServerboundContainerClosePacket} 的服务端落点）。
     *
     * <p>用 {@code doCloseContainer()} 而不是只发 {@code ClientboundContainerClosePacket}：前者会走
     * {@code menu.removed(player)} + 重置 {@code containerMenu}，与客户端真实关界面的效果一致；
     * 只发包会让服务端仍认为某个菜单开着。
     */
    private JsonObject close(String name) throws ActionException {
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu before = p.containerMenu;
            String menuBefore = menuName(before);
            int idBefore = before.containerId;
            p.doCloseContainer();
            JsonObject data = new JsonObject();
            data.addProperty("op", "close");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menuBefore", menuBefore);
            data.addProperty("containerIdBefore", idBefore);
            data.addProperty("menuAfter", menuName(p.containerMenu));
            data.addProperty("closed", p.containerMenu.containerId != idBefore);
            if (!(p.containerMenu.containerId != idBefore)) {
                data.addProperty("note", "no container was open (inventory menu id 0 cannot be closed)");
            }
            return data;
        });
    }

    /**
     * 按下容器界面的按钮（{@code ServerboundContainerButtonClickPacket} 的服务端落点，如附魔台/信标/织布机）。
     */
    private JsonObject button(ActionRequest req, String name) throws ActionException {
        int buttonId = req.i32("buttonId");
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu menu = p.containerMenu;
            boolean handled = menu.clickMenuButton(p, buttonId);
            menu.broadcastChanges();
            JsonObject data = new JsonObject();
            data.addProperty("op", "button");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menu", menuName(menu));
            data.addProperty("buttonId", buttonId);
            data.addProperty("handled", handled);
            if (!handled) {
                data.addProperty("note", "this menu ignored button " + buttonId
                        + " (either the menu has no buttons or the id is out of range)");
            }
            return data;
        });
    }

    /**
     * 选择村民交易项（{@code ServerboundSelectTradePacket} 的服务端落点）。
     *
     * <p>与 vanilla 处理器逐行对应：{@code if (menu instanceof MerchantMenu m) m.setSelectionHint(index)}。
     * 因此这里**不**自己扣物品、不自己加成交易次数 —— 那些由之后的 {@code click} 完成。
     */
    private JsonObject trade(ActionRequest req, String name) throws ActionException {
        int index = req.i32("index", 0);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu menu = p.containerMenu;
            if (!(menu instanceof MerchantMenu merchant)) {
                throw ActionException.targetNotFound("no merchant menu open (current menu: "
                        + menuName(menu) + "); walk up to a villager first");
            }
            int offers = merchant.getOffers().size();
            if (index < 0 || index >= offers) {
                throw ActionException.targetNotFound("trade index out of range: " + index
                        + " (merchant offers " + offers + ")");
            }
            merchant.setSelectionHint(index);
            merchant.broadcastChanges();
            JsonObject data = new JsonObject();
            data.addProperty("op", "trade");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menu", menuName(menu));
            data.addProperty("index", index);
            data.addProperty("offers", offers);
            return data;
        });
    }

    /**
     * 重命名铁砧里的物品（{@code ServerboundRenameItemPacket} 的服务端落点）。
     *
     * <p>{@code AnvilMenu.setItemName} 同时负责「名字合法就重算结果槽」和「扣经验」，
     * 所以这里只调它 + 读回结果槽，不自己 set 名称。
     */
    private JsonObject rename(ActionRequest req, String name) throws ActionException {
        String text = req.str("name", "");
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            AbstractContainerMenu menu = p.containerMenu;
            if (!(menu instanceof AnvilMenu anvil)) {
                throw ActionException.targetNotFound("no anvil menu open (current menu: "
                        + menuName(menu) + "); open an anvil first");
            }
            boolean accepted = anvil.setItemName(text);
            anvil.broadcastChanges();
            ItemStack result = anvil.getSlot(anvil.getResultSlot()).getItem();
            JsonObject data = new JsonObject();
            data.addProperty("op", "rename");
            data.addProperty("player", p.getName().getString());
            data.addProperty("menu", menuName(menu));
            data.addProperty("requested", text);
            data.addProperty("accepted", accepted);
            data.addProperty("resultItem", itemId(result));
            data.addProperty("resultName", result.getHoverName().getString());
            if (!accepted) {
                data.addProperty("note", "the anvil rejected this name (too long / illegal chars / player level too low)");
            }
            return data;
        });
    }

    // ------------------------------------------------------------------ 服务端代跑的动作
    //
    // 「服务端代跑」= 不需要客户端发任何包、也不需要客户端界面，但**需要玩家在线**：
    // 动作作用在一个真实的 ServerPlayer 上，走的是服务端自己的 ServerPlayerGameMode / Player.attack，
    // 与客户端发来的包在服务端触发的下游完全同一条。真正的「无人在线也能跑」需要假玩家
    // （FakePlayer，架构 §8.2 里降级为可选增强），这里没有实现。

    /**
     * 服务端破方块（{@code ServerboundPlayerActionPacket} 的 {@code START/STOP_DESTROY_BLOCK} 的服务端落点）。
     *
     * <p>{@code ServerPlayerGameMode.destroyBlock} 就是服务端处理破坏包时最终调用的方法，
     * 因此它会正常掉落、给经验、走 {@code ClientboundBlockUpdatePacket} 同步。
     * 前置：目标不是空气（否则返回 404，而不是「成功但什么都没发生」）。
     */
    private JsonObject mine(ActionRequest req) throws ActionException {
        BlockPos pos = target(req);
        Direction face = direction(req.str("face", "up"));
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            ServerLevel level = p.level();
            BlockState before = level.getBlockState(pos);
            if (before.isAir()) {
                throw ActionException.targetNotFound("block at " + coordLine(pos) + " is air; nothing to mine");
            }
            boolean broken = p.gameMode.destroyBlock(pos);
            BlockState after = level.getBlockState(pos);
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("pos", coordLine(pos));
            data.addProperty("face", face.name().toLowerCase(Locale.ROOT));
            data.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
            data.addProperty("blockBefore", blockId(before));
            data.addProperty("blockAfter", blockId(after));
            data.addProperty("broken", broken && after.isAir());
            data.addProperty("serverSide", true);
            return data;
        });
    }

    /**
     * 服务端放方块（{@code ServerboundUseItemOnPacket} 的服务端落点）。
     *
     * <p>用 {@code ServerPlayerGameMode.useItemOn(player, level, stack, hand, hit)}，
     * 参数与 {@code handleUseItemOn} 转发时完全一致；拿的是什么就放什么，换手用 {@code hand} 指定。
     */
    private JsonObject place(ActionRequest req) throws ActionException {
        BlockPos pos = target(req);
        Direction face = direction(req.str("face", "up"));
        InteractionHand hand = hand(req.str("hand", "main_hand"));
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            ServerLevel level = p.level();
            ItemStack held = p.getItemInHand(hand);
            // 「放置」改变的是被点方块**沿 face 的相邻格**，不是被点的方块本身。
            // 第一版把 before/after 都读在被点方块上，于是成功放置也报 changed:false —— 这是
            // 一次「响应说了假话」的教训：报告字段必须读**动作真正作用的位置**。
            BlockPos placedAt = pos.relative(face);
            BlockState targetBefore = level.getBlockState(pos);
            BlockState placedBefore = level.getBlockState(placedAt);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
            InteractionResult result = p.gameMode.useItemOn(p, level, held, hand, hit);
            BlockState placedAfter = level.getBlockState(placedAt);
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("pos", coordLine(pos));
            data.addProperty("face", face.name().toLowerCase(Locale.ROOT));
            data.addProperty("placedAt", coordLine(placedAt));
            data.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
            data.addProperty("held", itemId(held));
            data.addProperty("heldCount", held.getCount());
            data.addProperty("result", String.valueOf(result));
            data.addProperty("targetBlock", blockId(targetBefore));
            data.addProperty("blockBefore", blockId(placedBefore));
            data.addProperty("blockAfter", blockId(placedAfter));
            data.addProperty("changed", placedBefore.getBlock() != placedAfter.getBlock());
            data.addProperty("heldAfter", itemId(p.getItemInHand(hand)));
            data.addProperty("serverSide", true);
            return data;
        });
    }

    /**
     * 服务端「使用」（{@code target} 三选一：{@code block} / {@code entity} / {@code item}）。
     *
     * <p>三个分支分别对应三个 serverbound 包的服务端处理：
     * {@code UseItemOnPacket} → {@code useItemOn}；{@code InteractPacket} → {@code Player.interactOn}；
     * {@code UseItemPacket} → {@code gameMode.useItem}。返回 {@code result} 原文（{@code Success[]} 等），
     * 不把 {@code Pass} 说成成功。
     */
    private JsonObject use(ActionRequest req) throws ActionException {
        String target = req.str("target", "block");
        InteractionHand hand = hand(req.str("hand", "main_hand"));
        String name = req.str("player", null);
        JsonObject data = switch (target) {
            case "block" -> {
                BlockPos pos = target(req);
                Direction face = direction(req.str("face", "up"));
                yield onServer(() -> {
                    ServerPlayer p = requirePlayer(name);
                    ServerLevel level = p.level();
                    ItemStack held = p.getItemInHand(hand);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                    InteractionResult result = p.gameMode.useItemOn(p, level, held, hand, hit);
                    JsonObject d = new JsonObject();
                    d.addProperty("player", p.getName().getString());
                    d.addProperty("target", "block");
                    d.addProperty("pos", coordLine(pos));
                    d.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
                    d.addProperty("result", String.valueOf(result));
                    d.addProperty("serverSide", true);
                    return d;
                });
            }
            case "entity" -> {
                int entityId = req.i32("entityId");
                yield onServer(() -> {
                    ServerPlayer p = requirePlayer(name);
                    Entity e = entity(p, entityId);
                    InteractionResult result = p.interactOn(e, hand, e.position());
                    JsonObject d = new JsonObject();
                    d.addProperty("target", "entity");
                    d.addProperty("entityId", entityId);
                    d.addProperty("entityType", e.getType().toString());
                    d.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
                    d.addProperty("result", String.valueOf(result));
                    d.addProperty("serverSide", true);
                    return d;
                });
            }
            case "item" -> onServer(() -> {
                ServerPlayer p = requirePlayer(name);
                ServerLevel level = p.level();
                ItemStack held = p.getItemInHand(hand);
                InteractionResult result = p.gameMode.useItem(p, level, held, hand);
                JsonObject d = new JsonObject();
                d.addProperty("target", "item");
                d.addProperty("held", itemId(held));
                d.addProperty("result", String.valueOf(result));
                d.addProperty("usingItem", p.isUsingItem());
                d.addProperty("serverSide", true);
                return d;
            });
            default -> throw ActionException.badRequest("unknown use target: " + target
                    + " (expected block|entity|item)");
        };
        data.addProperty("player", name == null ? onServer(() -> requirePlayer(null).getName().getString())
                : onServer(() -> requirePlayer(name).getName().getString()));
        return data;
    }

    /**
     * 服务端攻击（{@code ServerboundAttackPacket} 的服务端落点 {@code Player.attack(entity)}）。
     *
     * <p>与客户端那条路不同的是：服务端这一步真的扣血、真的触发击退与附魔，
     * 所以响应里的 {@code healthBefore/healthAfter} 是权威数字。
     */
    private JsonObject attack(ActionRequest req) throws ActionException {
        int entityId = req.i32("entityId");
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            Entity e = entity(p, entityId);
            if (!(e instanceof LivingEntity living)) {
                throw ActionException.targetNotFound("entity " + entityId
                        + " is not a living entity; attacking it would be a no-op");
            }
            float before = living.getHealth();
            p.attack(living);
            float after = living.getHealth();
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("entityId", entityId);
            data.addProperty("entityType", living.getType().toString());
            data.addProperty("healthBefore", round3(before));
            data.addProperty("healthAfter", round3(after));
            data.addProperty("maxHealth", round3(living.getMaxHealth()));
            data.addProperty("damaged", after < before);
            data.addProperty("serverSide", true);
            return data;
        });
    }

    /**
     * 服务端「选取方块」（{@code ServerboundPickItemFromBlockPacket} 在创造模式下的效果）。
     *
     * <p>用 {@code BlockState.getCloneItemStack(level, pos, includeData)} —— 这正是创造模式 pick 取物品的
     * 官方途径（会带上方块实体数据，例如箱子/玩家头颅）；而不是手搓 {@code new ItemStack(block.asItem())}。
     */
    private JsonObject pick(ActionRequest req) throws ActionException {
        BlockPos pos = target(req);
        boolean includeData = req.bool("includeData", true);
        String name = req.str("player", null);
        return onServer(() -> {
            ServerPlayer p = requirePlayer(name);
            ServerLevel level = p.level();
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                throw ActionException.targetNotFound("block at " + coordLine(pos) + " is air");
            }
            ItemStack clone = state.getCloneItemStack(level, pos, includeData);
            if (clone.isEmpty()) {
                throw ActionException.targetNotFound("block " + blockId(state) + " has no item form");
            }
            p.getInventory().setSelectedItem(clone.copy());
            p.connection.send(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
            p.containerMenu.broadcastChanges();
            JsonObject data = new JsonObject();
            data.addProperty("player", p.getName().getString());
            data.addProperty("pos", coordLine(pos));
            data.addProperty("block", blockId(state));
            data.addProperty("includeData", includeData);
            data.addProperty("mainhand", itemId(p.getMainHandItem()));
            data.addProperty("count", p.getMainHandItem().getCount());
            data.addProperty("serverSide", true);
            return data;
        });
    }

    // ------------------------------------------------------------------ 工具

    private <T> T onServer(ThrowingSupplier<T> task) throws ActionException {
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.get());
            } catch (ActionException e) {
                future.completeExceptionally(e);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(ON_SERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw ActionException.timeout("server tick did not run within " + ON_SERVER_TIMEOUT_MS + " ms",
                    null);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ActionException actionError) {
                throw actionError;
            }
            throw ActionException.internal("server action failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ActionException.internal("interrupted while waiting for the server tick", e);
        }
    }

    private ServerPlayer requirePlayer(String name) throws ActionException {
        ServerPlayer p = (name == null || name.isEmpty())
                ? (mc.getPlayerList().getPlayers().isEmpty() ? null : mc.getPlayerList().getPlayers().get(0))
                : mc.getPlayerList().getPlayerByName(name);
        if (p == null) {
            throw ActionException.playerNotFound("player not online: " + (name == null ? "<any>" : name));
        }
        return p;
    }

    private static String menuName(AbstractContainerMenu menu) {
        return menu.getClass().getSimpleName();
    }

    /** 按名字找在线玩家自己维度里的一只实体；找不到或找的是玩家自己都返回 404（而不是静默取到 null）。 */
    private static Entity entity(ServerPlayer p, int entityId) throws ActionException {
        Entity e = p.level().getEntity(entityId);
        if (e == null) {
            throw ActionException.targetNotFound("no entity with id " + entityId
                    + " in " + p.level().dimension().identifier());
        }
        if (e == p) {
            throw ActionException.targetNotFound("entity " + entityId + " is the player itself");
        }
        return e;
    }

    private static Direction direction(String raw) throws ActionException {
        Direction d = Direction.byName(raw);
        if (d == null) {
            throw ActionException.badRequest("unknown direction: " + raw
                    + " (expected north|south|east|west|up|down)");
        }
        return d;
    }

    private static InteractionHand hand(String raw) throws ActionException {
        String v = raw == null ? "main_hand" : raw.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "main", "main_hand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand" -> InteractionHand.OFF_HAND;
            default -> throw ActionException.badRequest("unknown hand: " + raw + " (expected main_hand|off_hand)");
        };
    }

    /**
     * 服务端代码统一用 {@link BlockPos}，而 {@link ActionRequest#blockPos} 返回 {@code int[3]}
     * （共享的请求视图不认识 MC 类型，见 {@link ActionRequest} 的类注释）。
     */
    private static BlockPos target(ActionRequest req) throws ActionException {
        int[] p = req.blockPos("pos");
        return new BlockPos(p[0], p[1], p[2]);
    }

    private static String coordLine(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String blockId(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? String.valueOf(state.getBlock()) : id.toString();
    }

    private static String pos(ServerPlayer p) {
        return round3(p.getX()) + "," + round3(p.getY()) + "," + round3(p.getZ());
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "air";
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString();
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static int requireRange(int value, int min, int max, String what) throws ActionException {
        if (value < min || value > max) {
            throw ActionException.badRequest(what + " out of range: " + value
                    + " (expected " + min + ".." + max + ")");
        }
        return value;
    }

    private static ItemStack stack(ActionRequest req) throws ActionException {
        String raw = req.str("item", "minecraft:stone");
        String id = raw.contains(":") ? raw : "minecraft:" + raw;
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            throw ActionException.badRequest("bad item id: " + raw);
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        if (item == null) {
            throw ActionException.targetNotFound("unknown item: " + id);
        }
        // 26.3 的 ItemStack 构造器是 (ItemLike, int)；上限按 99 clamp，避免一次塞满整包。
        int count = Math.max(1, Math.min(99, req.i32("count", 1)));
        return new ItemStack(item, count);
    }

    /** 与 {@code Callable} 同形，但允许抛本包自己的检查异常。 */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ActionException;
    }
}
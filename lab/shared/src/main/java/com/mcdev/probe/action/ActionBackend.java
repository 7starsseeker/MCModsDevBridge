package com.mcdev.probe.action;

/**
 * 一个后端能执行的动作集合（架构 §8.2）。
 *
 * <p>接口刻意不含任何 Minecraft 类型：{@code lab/shared/src/main} 这一层两侧共享，
 * 一旦引用 {@code net.minecraft.client.*} 服务端构建就会炸。动作的「执行」细节全在各端实现里。</p>
 *
 * <p>三个方法对应架构的 {@code supports() / coverage() / execute()}：</p>
 * <ul>
 *   <li>{@link #actions()} —— 这个后端认识哪些动作名，用于 {@code /status} 自描述与路由注册；</li>
 *   <li>{@link #execute(ActionRequest)} —— 执行，失败一律抛 {@link ActionException}（带码）；</li>
 *   <li>{@link #name()} —— 后端名，进 {@code /status.actionBackend}。</li>
 * </ul>
 *
 * <p>三段式校验的第三段（目标存在）必然在后端内部做，第二段（gamemode）在第一段
 * 路由层就能拒绝的动作也允许后端拒绝 —— 校验器与实现放在一起，防止两处漂移。</p>
 */
public interface ActionBackend {

    /** 后端名，例如 {@code "client"} / {@code "server-observer"}。 */
    String name();

    /** 该后端支持的动作名集合。只用于自描述，不做鉴权。 */
    java.util.Set<String> actions();

    /**
     * 执行一个动作。
     *
     * @param request 已解析的 JSON 请求体
     * @return 序列化进 {@code data} 的结果对象（永不为 null）
     * @throws ActionException 参数错 / 前置不满足 / 目标不存在 / 超时
     */
    com.google.gson.JsonObject execute(ActionRequest request) throws ActionException;
}

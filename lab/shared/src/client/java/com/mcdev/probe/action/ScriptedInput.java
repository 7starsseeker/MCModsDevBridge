package com.mcdev.probe.action;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * 一段「脚本化的按键输入」：把 {@link LocalPlayer#input} 临时换成它，让玩家真的按这个方向走。
 *
 * <p>为什么不直接改 {@code KeyboardInput}、也不只发 {@code ServerboundPlayerInputPacket}：</p>
 * <ul>
 *   <li>26.3 的 {@link ClientInput#tick()} 是空实现（javap 实测 {@code Code: 0: return}），
 *       键盘版 {@code KeyboardInput.tick()} 每 tick 从 {@code Options} 重算 {@code keyPresses}
 *       与 {@code moveVector} —— 只在外面写一次字段，下一 tick 就被覆盖回零，玩家原地不动。</li>
 *   <li>只发输入包也不行：那个包只把输入状态告知服务端；真正让本地玩家移动的是客户端物理
 *       （{@code LocalPlayer.tick → applyInput → move}），它读的是 {@code input.getMoveVector()}。</li>
 *   <li>换掉整个 {@code input} 对象，同时拿到「每 tick 持续」与「走客户端真实物理」两件事 ——
 *       走路时服务端收到的 {@code ServerboundMovePlayerPacket} 与真人按键时一模一样。</li>
 * </ul>
 *
 * <p>副作用说明：attach 期间原键盘输入被顶替、真人按键暂时无效；{@link #detach(LocalPlayer)}
 * 会原样还回去，动作层保证在 {@code finally} 里调用。</p>
 *
 * <p>朝向不在这里处理 —— 动作层每轮直接在游戏线程上 {@code setYRot/setXRot}，
 * 本类只管「按住哪个方向」。</p>
 */
public final class ScriptedInput extends ClientInput {

    private ClientInput previous;
    private boolean forward;
    private Vec2 vector = Vec2.ZERO;

    /** 顶替玩家的输入源，保持到 {@link #detach(LocalPlayer)}。 */
    public void attach(LocalPlayer player) {
        if (previous == null) {
            previous = player.input;
            player.input = this;
        }
    }

    /** 还原输入源。重复调用安全（幂等）。 */
    public void detach(LocalPlayer player) {
        release();
        if (previous != null) {
            if (player.input == this) {
                player.input = previous;
            }
            previous = null;
        }
    }

    /** 按住「前进」。关键状态每 tick 由 {@link #tick()} 重写。 */
    public void pressForward() {
        forward = true;
        vector = new Vec2(0.0F, 1.0F);
    }

    /** 松开全部按键。 */
    public void release() {
        forward = false;
        vector = Vec2.ZERO;
    }

    @Override
    public void tick() {
        // 关键：每 tick 重写，而不是只写一次。ClientInput.tick() 是空实现，
        // 键盘版会在这里覆盖掉外部写入；我们自己就是最终值。
        keyPresses = forward
                ? new Input(true, false, false, false, false, false, false)
                : Input.EMPTY;
        moveVector = vector;
    }
}

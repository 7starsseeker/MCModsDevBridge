package com.mcdev.probe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /mcdev} 命令树 —— HTTP 之外的第二条控制通道，方便在游戏内直接操作。
 *
 * <p>P1 只保留 {@code status} / {@code freeze} / {@code step} 三个子命令：
 * {@code fakeplayer} 子命令依赖各加载器各写一份的假玩家实现（见架构文档 §8 平台适配面），
 * 属 P2/P4 范围，这里刻意不占位，避免共享层出现「名字在、实现空」的半成品。
 *
 * <p>{@code freeze} 不再改 {@code randomTickSpeed} / {@code doDaylightCycle} 这两个游戏规则：
 * 26.3 的 {@code TickRateManager} 会让整个 level 停走，改规则既多余，又会把改动永久留在存档里。
 */
public final class ProbeCommands {
    private ProbeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mcdev");
        root.then(Commands.literal("status").executes(ProbeCommands::status));
        root.then(Commands.literal("freeze").then(
                Commands.argument("value", BoolArgumentType.bool()).executes(ProbeCommands::freeze)));
        root.then(Commands.literal("step").then(
                Commands.argument("ticks", IntegerArgumentType.integer(1, 10000))
                        .executes(ProbeCommands::step)));
        dispatcher.register(root);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal(
                "tick=" + src.getServer().getTickCount()
                        + " players=" + src.getServer().getPlayerList().getPlayerCount()
                        + " frozen=" + TickControl.isFrozen()
                        + " step=" + TickControl.getStepBudget()), false);
        return 1;
    }

    private static int freeze(CommandContext<CommandSourceStack> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        TickControl.setFrozen(value);
        ctx.getSource().sendSuccess(() -> Component.literal("frozen=" + value), false);
        return 1;
    }

    private static int step(CommandContext<CommandSourceStack> ctx) {
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        TickControl.step(ticks);
        ctx.getSource().sendSuccess(() -> Component.literal("step=" + ticks), false);
        return 1;
    }
}

package com.mcdev.probe.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.BuiltinTestFunctions;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * V6/P0 的 NeoForge GameTest 入口（P4 步骤 0 从 examplemod 脚手架迁入探针）。
 *
 * 26.3 的 NeoForge 已经没有 {@code @GameTest} / {@code @GameTestHolder} / {@code @PrefixGameTestTemplate}
 * —— net/neoforged/neoforge/gametest/ 里只有 BlockPosValueConverter / GameTestHooks / package-info。
 * 官方路线是往 BuiltInRegistries.TEST_FUNCTION 注册测试函数 + 写 test_instance/*.json；
 * 但 RegisterGameTestsEvent 直接提供了运行期注册 GameTestInstance 的口子，不需要 json、不需要 datagen。
 *
 * 本类只用 net.minecraft.* + net.neoforged.neoforge.event.*，不放任何加载器公共 API 进 shared/。
 */
public final class NeoForgeGameTests {

    private static final String MODID = "mcdev_probe";

    private NeoForgeGameTests() {
    }

    public static void register(RegisterGameTestsEvent event) {
        // 环境：AllOf() 空集合 = 不改时间/天气/难度/规则，最省事且不会因 gamerule 名变更而失配。
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(
                Identifier.parse(MODID + ":p0_env"),
                new TestEnvironmentDefinition.AllOf());

        // 结构：原版 jar 里确实存在 data/minecraft/structure/empty.nbt
        TestData<Holder<TestEnvironmentDefinition<?>>> data =
                new TestData<>(env, Identifier.parse("minecraft:empty"), 20, 1, true);

        event.registerTest(Identifier.parse(MODID + ":v6_p0_smoke"),
                new MarkerTest(BuiltinTestFunctions.ALWAYS_PASS, data));
    }

    /** 复用原版现成的 ALWAYS_PASS 函数名，只把 run() 换成先落证据再放行。 */
    private static final class MarkerTest extends FunctionGameTestInstance {

        MarkerTest(ResourceKey<Consumer<GameTestHelper>> fn,
                   TestData<Holder<TestEnvironmentDefinition<?>>> data) {
            super(fn, data);
        }

        @Override
        public void run(GameTestHelper helper) {
            String msg = "[MCDEV-V6-OK] neoforge gametest body executed";
            System.out.println(msg);
            try {
                Files.writeString(Path.of("mcdev-v6-marker.txt"), msg + System.lineSeparator());
            } catch (Throwable ignored) {
                // 证据通道之一失败不影响测试结论
            }
            super.run(helper);
        }
    }
}

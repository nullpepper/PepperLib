package io.pepper.lib.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pepper.lib.i18n.FakePapiPlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * {@link PapiExpansionSupport} 注册样板测试：未安装 → null 且工厂不被调用
 * （扩展类 extends PAPI 类型，无 PAPI 时构造即 NoClassDefFoundError）；
 * 已启用 → 返回注册实例；register 异常 → null 兜底。
 */
class PapiExpansionSupportTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void returnsNullAndSkipsFactoryWhenPapiNotInstalled() {
        final AtomicBoolean factoryCalled = new AtomicBoolean();
        final PlaceholderExpansion expansion = Mockito.mock(PlaceholderExpansion.class);
        assertNull(PapiExpansionSupport.register(() -> {
            factoryCalled.set(true);
            return expansion;
        }));
        assertFalse(factoryCalled.get(), "factory must not run without PAPI");
        Mockito.verify(expansion, Mockito.never()).register();
    }

    @Test
    void registersAndReturnsInstanceWhenPapiPresent() {
        MockBukkit.load(FakePapiPlugin.class);
        final PlaceholderExpansion expansion = Mockito.mock(PlaceholderExpansion.class);
        Mockito.when(expansion.register()).thenReturn(true);
        assertEquals(expansion, PapiExpansionSupport.register(() -> expansion));
        Mockito.verify(expansion).register();
    }

    @Test
    void returnsNullWhenRegisterThrows() {
        MockBukkit.load(FakePapiPlugin.class);
        final PlaceholderExpansion expansion = Mockito.mock(PlaceholderExpansion.class);
        Mockito.when(expansion.register()).thenThrow(new IllegalStateException("boom"));
        assertNull(PapiExpansionSupport.register(() -> expansion));
    }

    @Test
    void returnsNullWhenFactoryThrowsLinkageError() {
        MockBukkit.load(FakePapiPlugin.class);
        // PAPI 存在但类损坏/版本错配时，扩展构造抛 NoClassDefFoundError（Error 子类）：
        // 软依赖兜底必须吞掉，不能中断插件启动。
        assertNull(PapiExpansionSupport.register(() -> {
            throw new NoClassDefFoundError("simulated broken PAPI classes");
        }));
    }
}

package io.pepper.lib.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.runtime.PepperLibRuntime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link DefaultPepperLibRuntime} 契约：版本透传 + 能力集查询。 */
class DefaultPepperLibRuntimeTest {

    @Test
    void apiVersionIsPassedThrough() {
        final PepperLibRuntime runtime = new DefaultPepperLibRuntime("0.3.0", Set.of());
        assertEquals("0.3.0", runtime.apiVersion());
    }

    @Test
    void emptyCapabilitiesReportFalseForEverything() {
        final PepperLibRuntime runtime = new DefaultPepperLibRuntime("0.3.0", Set.of());
        assertFalse(runtime.supports(PepperLibRuntime.CAP_GUI_HOST));
        assertFalse(runtime.supports("unknown"));
        assertFalse(runtime.supports(null));
    }

    @Test
    void declaredCapabilityIsReported() {
        final PepperLibRuntime runtime = new DefaultPepperLibRuntime("0.3.0", Set.of(PepperLibRuntime.CAP_GUI_HOST));
        assertTrue(runtime.supports(PepperLibRuntime.CAP_GUI_HOST));
        assertFalse(runtime.supports("unknown"));
    }
}

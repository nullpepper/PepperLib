package ltd.pepper.lib.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ltd.pepper.lib.expression.PlaceholderVariableMapper.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 占位符 → 变量名改写（源自 PepperBotCustomMessage 提取）。 */
class PlaceholderVariableMapperTest {

    @Test
    @DisplayName("改写内置变量并重写表达式")
    void rewritesBuiltinVariablesAndRewritesExpression() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("${bot_args} + ${bot_group_id} + 5");
        assertEquals("args + group_id + 5", mapping.expression());
        assertEquals("args", mapping.builtinVariables().get("args"));
        assertEquals("group_id", mapping.builtinVariables().get("group_id"));
        assertTrue(mapping.papiVariables().isEmpty());
    }

    @Test
    @DisplayName("%player_name% 与 %server_time_HH:mm:ss% 映射为 PAPI 变量")
    void mapsPapiPlaceholdersToSanitizedVariables() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("%player_name% and %server_time_HH:mm:ss%");
        assertEquals("player_name and server_time_HH_mm_ss", mapping.expression());
        assertEquals("player_name", mapping.papiVariables().get("player_name"));
        assertEquals("server_time_HH:mm:ss", mapping.papiVariables().get("server_time_HH_mm_ss"));
        assertTrue(mapping.builtinVariables().isEmpty());
    }

    @Test
    @DisplayName("a < b 中的 < 是小于号，${} 语法下不再有歧义")
    void lessThanOperatorIsNeverAPlaceholder() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("a < b and ${bot_args} > 1");
        assertEquals("a < b and args > 1", mapping.expression());
        assertEquals("args", mapping.builtinVariables().get("args"));
        assertTrue(mapping.papiVariables().isEmpty());
    }

    @Test
    @DisplayName("尖括号不再是内置占位符（语法已迁到 ${bot_*}）")
    void angleBracketsAreNoLongerPlaceholders() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("<args> + 5");
        assertEquals("<args> + 5", mapping.expression());
        assertTrue(mapping.builtinVariables().isEmpty());
    }

    @Test
    @DisplayName("非 bot 命名空间的 ${} 不被识别为内置占位符")
    void foreignNamespaceIsNotRecognized() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("${player_name} + ${bot_args}");
        assertEquals("${player_name} + args", mapping.expression());
        assertEquals(1, mapping.builtinVariables().size());
        assertEquals("args", mapping.builtinVariables().get("args"));
    }

    @Test
    @DisplayName("%foo bar%（含空白）不被解析为占位符")
    void doesNotParsePapiWithWhitespaceInside() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("%foo bar%");
        assertEquals("%foo bar%", mapping.expression());
        assertTrue(mapping.papiVariables().isEmpty());
    }

    @Test
    @DisplayName("清洗后同名（如 %a-b% 与 <a_b>）触发冲突异常")
    void conflictingSanitizedNamesThrow() {
        assertThrows(IllegalArgumentException.class, () -> PlaceholderVariableMapper.rewrite("%a-b% + ${bot_a_b}"));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderVariableMapper.rewrite("%a-b% + %a_b%"));
    }

    @Test
    @DisplayName("allVariables() 同时包含内置与 PAPI 变量")
    void allVariablesIncludesBuiltinAndPapi() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("${bot_args} + %player_name%");
        assertEquals(2, mapping.allVariables().size());
        assertTrue(mapping.allVariables().contains("args"));
        assertTrue(mapping.allVariables().contains("player_name"));
    }

    @Test
    @DisplayName("空表达式返回空映射")
    void emptyExpression() {
        Mapping mapping = PlaceholderVariableMapper.rewrite("");
        assertTrue(mapping.expression().isEmpty());
        assertTrue(mapping.allVariables().isEmpty());
    }
}

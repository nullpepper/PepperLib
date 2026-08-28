package ltd.pepper.lib.expression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import ltd.pepper.lib.expression.SafeExpression.BoolNode;
import ltd.pepper.lib.expression.SafeExpression.EvaluationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 安全表达式引擎：白名单运算与字面量，拒绝反射/类加载/静态调用（源自 PepperBotCustomMessage 提取）。 */
class SafeExpressionTest {

    @Test
    @DisplayName("白名单运算可用：== != && || ! contains 与数值比较")
    void whitelistedOperatorsWork() {
        assertTrue(eval("args == \"x\"", Map.of("args", "x")));
        assertTrue(eval("args != \"x\"", Map.of("args", "y")));
        assertTrue(eval("count >= 5 && !(name contains \"bot\")", Map.of("count", 7, "name", "steve")));
        assertTrue(eval("name startswith \"st\" && name endswith \"ve\"", Map.of("name", "steve")));
        assertTrue(eval("a || b", Map.of("a", false, "b", true)));
        assertTrue(eval("!a", Map.of("a", false)));
    }

    @Test
    @DisplayName("数值比较与数值字符串自动转换")
    void numericComparisons() {
        assertTrue(eval("n > 3.5", Map.of("n", 4)));
        assertFalse(eval("n <= 3.5", Map.of("n", 4)));
        assertTrue(eval("s == 42", Map.of("s", "42")));
        assertFalse(eval("s == 43", Map.of("s", "42")));
        assertTrue(eval("s < 10", Map.of("s", "7")));
    }

    @Test
    @DisplayName("非数值做大小比较返回 false 而非异常")
    void nonNumericComparisonIsFalse() {
        assertFalse(eval("s > 5", Map.of("s", "abc")));
        assertFalse(eval("n < \"x\"", Map.of("n", 1)));
    }

    @Test
    @DisplayName("字面量：字符串单双引号、整数、小数、true/false")
    void literals() {
        assertTrue(eval("'single' == \"single\"", Map.of()));
        assertTrue(eval("10 > 5", Map.of()));
        assertFalse(eval("1.5 >= 2", Map.of()));
        assertTrue(eval("true && !false", Map.of()));
        assertFalse(eval("true == false", Map.of()));
    }

    @Test
    @DisplayName("裸布尔变量直接作为条件（AsBool）")
    void bareBooleanVariableAsCondition() {
        assertTrue(eval("is_online", Map.of("is_online", true)));
        assertFalse(eval("is_online", Map.of("is_online", false)));
    }

    @Test
    @DisplayName("空串与空白表达式恒为 false")
    void emptyExpressionIsFalse() {
        assertFalse(SafeExpression.compile("").evaluate(Map.of()));
        assertFalse(SafeExpression.compile("   ").evaluate(Map.of()));
        assertFalse(SafeExpression.compile(null).evaluate(Map.of()));
    }

    @Test
    @DisplayName("变量缺失抛 EvaluationException")
    void missingVariableThrows() {
        BoolNode node = SafeExpression.compile("missing == 1");
        assertThrows(EvaluationException.class, () -> node.evaluate(Map.of()));
    }

    @Test
    @DisplayName("拒绝反射链：.getClass().forName(...) 编译失败")
    void rejectsReflectionChain() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeExpression.compile("message.getClass().forName(\"java.lang.Runtime\")"));
    }

    @Test
    @DisplayName("拒绝静态方法调用：Runtime.getRuntime() 编译失败")
    void rejectsStaticMethodCall() {
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("Runtime.getRuntime()"));
    }

    @Test
    @DisplayName("拒绝方法调用与字段访问")
    void rejectsMethodCallAndFieldAccess() {
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("foo.bar()"));
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("foo.bar"));
    }

    @Test
    @DisplayName("拒绝非法字符与未闭合字符串")
    void rejectsIllegalCharactersAndUnclosedString() {
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("a $ b"));
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("\"unclosed"));
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("a;b"));
    }

    @Test
    @DisplayName("拒绝残留内容（表达式后跟多余 token）")
    void rejectsTrailingTokens() {
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("true true"));
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("(true) extra"));
    }

    @Test
    @DisplayName("超长表达式拒绝（> 4096）")
    void rejectsOverlongSource() {
        String source = "true && ".repeat(600) + "true";
        assertTrue(source.length() > SafeExpression.MAX_SOURCE_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile(source));
    }

    @Test
    @DisplayName("嵌套过深拒绝（> 64）")
    void rejectsDeepNesting() {
        String source = "(".repeat(70) + "true" + ")".repeat(70);
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile(source));
    }

    @Test
    @DisplayName("括号不配对拒绝")
    void rejectsUnbalancedParentheses() {
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("(true"));
        assertThrows(IllegalArgumentException.class, () -> SafeExpression.compile("true)"));
    }

    private static boolean eval(String source, Map<String, Object> variables) {
        return SafeExpression.compile(source).evaluate(variables);
    }
}

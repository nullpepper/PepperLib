package io.pepper.lib.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小安全布尔表达式引擎（源自 PepperBotCustomMessage 提取）。
 *
 * <p>只允许白名单运算与字面量，彻底移除反射、类加载、静态方法调用等 RCE 面：
 * <ul>
 *   <li>逻辑：{@code &&}、{@code ||}、{@code !}、括号</li>
 *   <li>比较：{@code ==}、{@code !=}、{@code >}、{@code >=}、{@code <}、{@code <=}</li>
 *   <li>字符串白名单运算：{@code contains}、{@code startswith}、{@code endswith}</li>
 *   <li>字面量：字符串（单/双引号）、整数、小数、{@code true}/{@code false}</li>
 *   <li>标识符：仅变量名（{@code [A-Za-z_][A-Za-z0-9_]*}），不允许方法调用/字段访问</li>
 * </ul>
 *
 * <p>求值是线性递归下降，无循环、无反射，天然不可超时挂死；仅以源码长度与嵌套深度做有界防护。
 */
public final class SafeExpression {

    /** 单个条件表达式最大源码长度（防病态超长输入）。 */
    public static final int MAX_SOURCE_LENGTH = 4096;
    /** 括号/逻辑嵌套最大深度（防递归爆栈）。 */
    public static final int MAX_NESTING_DEPTH = 64;

    private SafeExpression() {}

    /** 编译表达式；空串视为恒 false。失败抛 {@link IllegalArgumentException}。 */
    public static BoolNode compile(String source) {
        if (source == null) {
            return BoolNode.FALSE;
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return BoolNode.FALSE;
        }
        if (trimmed.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("表达式过长: " + trimmed.length() + " > " + MAX_SOURCE_LENGTH);
        }
        Parser parser = new Parser(trimmed);
        BoolNode node = parser.parse();
        parser.expectEnd();
        return node;
    }

    // ---- AST ----

    public sealed interface BoolNode {
        BoolNode FALSE = new Constant(false);
        BoolNode TRUE = new Constant(true);

        boolean evaluate(Map<String, Object> variables);

        record Constant(boolean value) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                return value;
            }
        }

        record And(BoolNode left, BoolNode right) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                return left.evaluate(variables) && right.evaluate(variables);
            }
        }

        record Or(BoolNode left, BoolNode right) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                return left.evaluate(variables) || right.evaluate(variables);
            }
        }

        record Not(BoolNode inner) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                return !inner.evaluate(variables);
            }
        }

        /** 裸布尔变量/字面量直接作为条件（如 {@code <is_online>}）。 */
        record AsBool(Value value) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                Object result = value.evaluate(variables);
                return result instanceof Boolean b && b;
            }
        }

        record Compare(Op op, Value left, Value right) implements BoolNode {
            @Override
            public boolean evaluate(Map<String, Object> variables) {
                return OpEvaluator.evaluate(op, left.evaluate(variables), right.evaluate(variables));
            }
        }
    }

    public sealed interface Value {
        Object evaluate(Map<String, Object> variables);

        record Literal(Object value) implements Value {
            @Override
            public Object evaluate(Map<String, Object> variables) {
                return value;
            }
        }

        record Variable(String name) implements Value {
            @Override
            public Object evaluate(Map<String, Object> variables) {
                if (!variables.containsKey(name)) {
                    throw new EvaluationException("未提供变量: " + name);
                }
                return variables.get(name);
            }
        }
    }

    public enum Op {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH
    }

    public static final class EvaluationException extends RuntimeException {
        public EvaluationException(String message) {
            super(message);
        }
    }

    private static final class OpEvaluator {

        private OpEvaluator() {}

        static boolean evaluate(Op op, Object left, Object right) {
            return switch (op) {
                case EQ -> equal(left, right);
                case NE -> !equal(left, right);
                case CONTAINS -> string(left).contains(string(right));
                case STARTS_WITH -> string(left).startsWith(string(right));
                case ENDS_WITH -> string(left).endsWith(string(right));
                case GT, GE, LT, LE -> {
                    DoublePair pair = numeric(left, right);
                    if (pair == null) {
                        // 非数值做大小比较无定义，返回 false（等价于条件不成立）
                        yield false;
                    }
                    int cmp = Double.compare(pair.left(), pair.right());
                    yield switch (op) {
                        case GT -> cmp > 0;
                        case GE -> cmp >= 0;
                        case LT -> cmp < 0;
                        case LE -> cmp <= 0;
                        default -> false;
                    };
                }
            };
        }

        private static boolean equal(Object left, Object right) {
            if (left == null || right == null) {
                return left == right;
            }
            DoublePair pair = numeric(left, right);
            if (pair != null) {
                return Double.compare(pair.left(), pair.right()) == 0;
            }
            return string(left).equals(string(right));
        }

        private static DoublePair numeric(Object left, Object right) {
            Double a = toNumber(left);
            Double b = toNumber(right);
            return a != null && b != null ? new DoublePair(a, b) : null;
        }

        private static Double toNumber(Object value) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            if (value instanceof CharSequence s) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private static String string(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private record DoublePair(double left, double right) {}
    }

    // ---- 词法 / 语法 ----

    private enum TokenType {
        IDENT,
        STRING,
        NUMBER,
        OP,
        EOF
    }

    private record Token(TokenType type, String text, Object value) {

        static Token eof() {
            return new Token(TokenType.EOF, "", null);
        }
    }

    private static final class Parser {

        private final List<Token> tokens;
        private int pos;
        private int depth;

        Parser(String source) {
            this.tokens = tokenize(source);
        }

        BoolNode parse() {
            return parseExpression();
        }

        void expectEnd() {
            if (peek().type() != TokenType.EOF) {
                throw error("存在无法解析的内容: '" + peek().text() + "'");
            }
        }

        private BoolNode parseExpression() {
            return parseOr();
        }

        private BoolNode parseOr() {
            BoolNode left = parseAnd();
            while (matchOp("||")) {
                left = new BoolNode.Or(left, parseAnd());
            }
            return left;
        }

        private BoolNode parseAnd() {
            BoolNode left = parseNot();
            while (matchOp("&&")) {
                left = new BoolNode.And(left, parseNot());
            }
            return left;
        }

        private BoolNode parseNot() {
            depth++;
            if (depth > MAX_NESTING_DEPTH) {
                throw error("表达式嵌套过深（>" + MAX_NESTING_DEPTH + "）");
            }
            try {
                if (matchOp("!")) {
                    return new BoolNode.Not(parseNot());
                }
                if (matchOp("(")) {
                    BoolNode inner = parseExpression();
                    expectOp(")");
                    return inner;
                }
                return parseComparison();
            } finally {
                depth--;
            }
        }

        private BoolNode parseComparison() {
            Value left = parseValue();
            Op op = matchCompareOp();
            if (op == null) {
                return new BoolNode.AsBool(left);
            }
            Value right = parseValue();
            return new BoolNode.Compare(op, left, right);
        }

        private Value parseValue() {
            Token token = peek();
            switch (token.type()) {
                case STRING:
                case NUMBER:
                    advance();
                    return new Value.Literal(token.value());
                case IDENT:
                    advance();
                    if ("true".equals(token.text())) {
                        return new Value.Literal(Boolean.TRUE);
                    }
                    if ("false".equals(token.text())) {
                        return new Value.Literal(Boolean.FALSE);
                    }
                    return new Value.Variable(token.text());
                default:
                    throw error("期望值，但遇到 " + describe(token));
            }
        }

        private Op matchCompareOp() {
            Token token = peek();
            if (token.type() == TokenType.OP) {
                return switch (token.text()) {
                    case "==" -> takeOp(Op.EQ);
                    case "!=" -> takeOp(Op.NE);
                    case ">" -> takeOp(Op.GT);
                    case ">=" -> takeOp(Op.GE);
                    case "<" -> takeOp(Op.LT);
                    case "<=" -> takeOp(Op.LE);
                    default -> null;
                };
            }
            if (token.type() == TokenType.IDENT) {
                return switch (token.text()) {
                    case "contains" -> takeOp(Op.CONTAINS);
                    case "startswith" -> takeOp(Op.STARTS_WITH);
                    case "endswith" -> takeOp(Op.ENDS_WITH);
                    default -> null;
                };
            }
            return null;
        }

        private Op takeOp(Op op) {
            advance();
            return op;
        }

        private boolean matchOp(String text) {
            Token token = peek();
            if (token.type() == TokenType.OP && token.text().equals(text)) {
                advance();
                return true;
            }
            return false;
        }

        private void expectOp(String text) {
            if (!matchOp(text)) {
                throw error("期望 '" + text + "'，但遇到 " + describe(peek()));
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private void advance() {
            if (pos < tokens.size() - 1) {
                pos++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + pos + "）");
        }

        private static String describe(Token token) {
            return token.type() == TokenType.EOF ? "结尾" : "'" + token.text() + "'";
        }
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = readString(source, i, c, tokens);
                continue;
            }
            if (Character.isDigit(c)) {
                i = readNumber(source, i, tokens);
                continue;
            }
            if (isIdentStart(c)) {
                i = readIdent(source, i, tokens);
                continue;
            }
            String two = i + 1 < n ? source.substring(i, i + 2) : "";
            if ("&&".equals(two)
                    || "||".equals(two)
                    || "==".equals(two)
                    || "!=".equals(two)
                    || ">=".equals(two)
                    || "<=".equals(two)) {
                tokens.add(new Token(TokenType.OP, two, null));
                i += 2;
                continue;
            }
            if (c == '!' || c == '(' || c == ')' || c == '>' || c == '<') {
                tokens.add(new Token(TokenType.OP, String.valueOf(c), null));
                i++;
                continue;
            }
            throw new IllegalArgumentException("位置 " + i + " 的字符不允许: '" + c + "'");
        }
        tokens.add(Token.eof());
        return tokens;
    }

    private static int readString(String source, int start, char quote, List<Token> tokens) {
        int i = start + 1;
        StringBuilder sb = new StringBuilder();
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = source.charAt(i + 1);
                if (next == quote || next == '\\') {
                    sb.append(next);
                    i += 2;
                    continue;
                }
            }
            if (c == quote) {
                tokens.add(new Token(TokenType.STRING, sb.toString(), sb.toString()));
                return i + 1;
            }
            sb.append(c);
            i++;
        }
        throw new IllegalArgumentException("未闭合的字符串字面量（起始位置 " + start + "）");
    }

    private static int readNumber(String source, int start, List<Token> tokens) {
        int i = start;
        int n = source.length();
        while (i < n && Character.isDigit(source.charAt(i))) {
            i++;
        }
        boolean decimal = false;
        if (i + 1 < n && source.charAt(i) == '.' && Character.isDigit(source.charAt(i + 1))) {
            decimal = true;
            i++;
            while (i < n && Character.isDigit(source.charAt(i))) {
                i++;
            }
        }
        if (i < n && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
            decimal = true;
            i++;
            if (i < n && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                i++;
            }
            while (i < n && Character.isDigit(source.charAt(i))) {
                i++;
            }
        }
        String text = source.substring(start, i);
        Object value;
        if (decimal) {
            value = Double.parseDouble(text);
        } else {
            value = Long.parseLong(text);
        }
        tokens.add(new Token(TokenType.NUMBER, text, value));
        return i;
    }

    private static int readIdent(String source, int start, List<Token> tokens) {
        int i = start;
        int n = source.length();
        while (i < n && isIdentPart(source.charAt(i))) {
            i++;
        }
        String text = source.substring(start, i);
        tokens.add(new Token(TokenType.IDENT, text, text));
        return i;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}

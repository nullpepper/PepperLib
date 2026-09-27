package ltd.pepper.lib.redis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 最小 RESP2 客户端：只实现本插件真正用到的命令。
 *
 * <p><b>为什么不引 Jedis/Lettuce：</b>本插件的运行时依赖是零（纯 JDK），
 * 而需要的只是 {@code PING / SET / GET / PUBLISH / SUBSCRIBE} 五条命令。
 * 为此拉进一个 Redis 客户端库（及其连接池、编解码、Netty 传递依赖）不划算，
 * 也不符合本仓库「核心纯 JDK」的约定。协议本身是行式的，实现量可控且可测。</p>
 *
 * <p><b>两条连接</b>：Redis 的订阅连接进入订阅模式后只能收推送、不能再发普通命令，
 * 因此普通命令与订阅各用一条 socket。命令侧加锁串行化；订阅侧单独线程常驻读推送。</p>
 *
 * <p><b>失败可见</b>：连不上、命令报错、响应畸形都抛 {@link RedisException}，
 * 绝不静默返回空值 —— 调用方据此报错或重试。</p>
 */
public final class RedisClient implements AutoCloseable {

    /** Redis 交互失败（连接、协议、服务端 -ERR）。 */
    public static class RedisException extends RuntimeException {
        public RedisException(String message) {
            super(message);
        }

        public RedisException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 连接与读超时。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;

    private static final int READ_TIMEOUT_MILLIS = 3_000;
    /** 订阅重连的退避间隔。 */
    private static final long RECONNECT_DELAY_MILLIS = 200L;

    /** 当前生效的连接设置；{@link #reconfigure(RedisSettings)} 可替换。 */
    private volatile RedisSettings settings;

    private final Object commandLock = new Object();

    private Socket commandSocket;
    private InputStream commandIn;
    private OutputStream commandOut;
    private volatile boolean connected;
    private volatile boolean closed;

    public RedisClient(RedisSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** 连接设置。 */
    public RedisSettings settings() {
        return settings;
    }

    /**
     * 换用新的连接设置（不自动重连）。
     *
     * <p>供「配置里改对了 Redis 地址后 reload」这条路径使用：
     * 只换设置而不重连，避免在调用方还没准备好时就开始连接。</p>
     */
    public void reconfigure(RedisSettings next) {
        if (next != null) {
            this.settings = next;
        }
    }

    /** 是否已连接（命令侧）。 */
    public boolean isConnected() {
        return connected && !closed;
    }

    // ==================== 连接 ====================

    /**
     * 建立命令连接并握手（PING）。
     *
     * @throws RedisException 连不上或握手失败
     */
    public void connect() {
        if (closed) {
            throw new RedisException("客户端已关闭");
        }
        synchronized (commandLock) {
            closeCommandQuietly();
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(settings.host(), settings.port()), CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                socket.setTcpNoDelay(true);
                commandSocket = socket;
                commandIn = socket.getInputStream();
                commandOut = socket.getOutputStream();
                // 握手：必须先把 PING 发出去再读响应 —— 只读不写会一直等到读超时。
                writeCommand(commandOut, "PING");
                String pong = readReplyBytes(commandIn);
                if (!"PONG".equals(pong)) {
                    throw new RedisException("PING 返回了意外响应：" + pong);
                }
                connected = true;
            } catch (IOException io) {
                closeCommandQuietly();
                throw new RedisException("连接 Redis " + settings.host() + ":" + settings.port() + " 失败：" + io, io);
            } catch (RedisException already) {
                closeCommandQuietly();
                throw already;
            }
        }
    }

    /** 连通性探测；失败返回 {@code false}（不抛异常，供健康检查用）。 */
    public boolean ping() {
        try {
            synchronized (commandLock) {
                requireConnected();
                writeCommand(commandOut, "PING");
                return "PONG".equals(readReplyBytes(commandIn));
            }
        } catch (IOException | RedisException probeFailed) {
            return false;
        }
    }

    // ==================== 命令 ====================

    /**
     * {@code SET key value}。
     *
     * @return 服务端是否确认
     */
    public boolean set(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (commandLock) {
            requireConnected();
            try {
                writeCommand(commandOut, "SET", key, value);
                return "OK".equals(readReplyBytes(commandIn));
            } catch (IOException io) {
                onCommandIoFailure(io);
                throw new RedisException("SET " + key + " 失败：" + io, io);
            }
        }
    }

    /**
     * {@code GET key}。
     *
     * @return 值；键不存在时为 {@code null}
     */
    public String get(String key) {
        Objects.requireNonNull(key, "key");
        synchronized (commandLock) {
            requireConnected();
            try {
                writeCommand(commandOut, "GET", key);
                return readReplyBytes(commandIn);
            } catch (IOException io) {
                onCommandIoFailure(io);
                throw new RedisException("GET " + key + " 失败：" + io, io);
            }
        }
    }

    /**
     * {@code DEL key}。
     *
     * @return 被删除的键数量
     */
    public int del(String key) {
        Objects.requireNonNull(key, "key");
        synchronized (commandLock) {
            requireConnected();
            try {
                writeCommand(commandOut, "DEL", key);
                String reply = readReplyBytes(commandIn);
                try {
                    return Integer.parseInt(reply);
                } catch (NumberFormatException notAnInteger) {
                    throw new RedisException("DEL 返回了非整数响应：" + reply);
                }
            } catch (IOException io) {
                onCommandIoFailure(io);
                throw new RedisException("DEL " + key + " 失败：" + io, io);
            }
        }
    }

    /**
     * {@code PUBLISH channel message}。
     *
     * @return 收到消息的订阅者数量
     */
    public int publish(String channel, String message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        synchronized (commandLock) {
            requireConnected();
            try {
                writeCommand(commandOut, "PUBLISH", channel, message);
                String reply = readReplyBytes(commandIn);
                try {
                    return Integer.parseInt(reply);
                } catch (NumberFormatException notAnInteger) {
                    throw new RedisException("PUBLISH 返回了非整数响应：" + reply);
                }
            } catch (IOException io) {
                onCommandIoFailure(io);
                throw new RedisException("PUBLISH " + channel + " 失败：" + io, io);
            }
        }
    }

    // ==================== 订阅 ====================

    /**
     * 订阅通道。
     *
     * <p>订阅线程常驻：收到消息回调 {@code onMessage}；连接断开则退避重连，
     * 重连成功后回调 {@code onReconnected}（调用方应借此重新 {@code GET} 全量，
     * 避免断线窗口内漏掉的推送造成状态不一致）。</p>
     *
     * @param channel       通道名
     * @param onMessage     收到消息时回调
     * @param onReconnected 重连成功回调；可为 {@code null}
     */
    public RedisSubscription subscribe(String channel, Consumer<String> onMessage, Runnable onReconnected) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(onMessage, "onMessage");
        RedisSubscription subscription = new RedisSubscription(settings, channel, onMessage, onReconnected);
        subscription.start();
        return subscription;
    }

    /** 订阅通道（不关心重连事件）。 */
    public RedisSubscription subscribe(String channel, Consumer<String> onMessage) {
        return subscribe(channel, onMessage, null);
    }

    /**
     * 断开当前连接但保持可用（之后可再次 {@link #connect()}）。
     *
     * <p>与 {@link #close()} 的区别：{@code close()} 是终结（不可再用），
     * 本方法是「换地址/重连」前的清理。</p>
     */
    public void disconnect() {
        synchronized (commandLock) {
            closeCommandQuietly();
        }
    }

    /** 终结客户端：断开连接且之后不可再用。 */
    @Override
    public void close() {
        closed = true;
        synchronized (commandLock) {
            closeCommandQuietly();
        }
    }

    // ==================== 内部 ====================

    private void requireConnected() {
        if (!connected || commandSocket == null || commandSocket.isClosed()) {
            throw new RedisException("尚未连接 Redis（请先调用 connect()）");
        }
    }

    private void onCommandIoFailure(IOException io) {
        connected = false;
        closeCommandQuietly();
    }

    private void closeCommandQuietly() {
        connected = false;
        try {
            if (commandSocket != null) {
                commandSocket.close();
            }
        } catch (IOException ignored) {
            // 关闭即可
        }
        commandSocket = null;
        commandIn = null;
        commandOut = null;
    }

    /**
     * 按 RESP2 数组形式写一条命令。
     *
     * <p><b>必须写字节而不是字符</b>：协议头里的长度是<b>字节数</b>，
     * 而中文等多字节字符的字符数小于字节数 —— 用 Writer 写字符会让服务端
     * 按字节数读时一直等不到足够的数据（表现为读超时）。</p>
     */
    private static void writeCommand(OutputStream out, String... args) throws IOException {
        out.write(buildCommand(args));
        out.flush();
    }

    /** 构造 RESP2 命令字节（包内可见，便于单测直接校验线上格式）。 */
    static byte[] buildCommand(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        writeAscii(buf, "*" + args.length + "\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            writeAscii(buf, "$" + bytes.length + "\r\n");
            buf.write(bytes, 0, bytes.length);
            writeAscii(buf, "\r\n");
        }
        return buf.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream buf, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            buf.write(ascii.charAt(i)); // 协议骨架全是 ASCII
        }
    }

    /**
     * 读一条 RESP2 响应。
     *
     * <p><b>必须按字节读</b>：协议里的长度是字节数，且批量字符串的内容
     * <b>可以包含换行</b>（本插件的载荷本身就是多行文本）——
     * 用行式 Reader 读会在载荷内部的第一个换行处截断，导致整条流错位。</p>
     *
     * <p>支持 {@code +}简单字符串、{@code -}错误、{@code :}整数、{@code $}批量字符串。
     * 其它前缀一律视为协议错误 —— 本客户端不期待数组响应。</p>
     *
     * @return 值；批量字符串为空（{@code $-1}）时返回 {@code null}
     */
    private static String readReplyBytes(InputStream in) throws IOException {
        String line = readLineBytes(in);
        if (line == null) {
            throw new IOException("连接被对端关闭");
        }
        if (line.isEmpty()) {
            throw new IOException("收到空响应行");
        }
        char type = line.charAt(0);
        String body = line.substring(1);
        switch (type) {
            case '+':
                return body;
            case '-':
                throw new RedisException("Redis 返回错误：" + body);
            case ':':
                return body;
            case '$': {
                int length = parseLength(body);
                if (length < 0) {
                    return null; // $-1：键不存在
                }
                String value = readBulkBytes(in, length);
                expectCrlfBytes(in);
                return value;
            }
            default:
                throw new IOException("无法识别的响应前缀 '" + type + "'：" + abbreviate(line));
        }
    }

    /**
     * 按字节读一行（以 CRLF 结束）。
     *
     * @return 行内容（不含 CRLF）；流结束返回 {@code null}
     */
    private static String readLineBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\r') {
                int lf = in.read();
                if (lf == '\n') {
                    return buf.toString(StandardCharsets.UTF_8);
                }
                buf.write(b);
                if (lf >= 0) {
                    buf.write(lf);
                }
                continue;
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    /** 读指定字节数的批量字符串内容（允许内含换行）。 */
    private static String readBulkBytes(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(buf, read, length - read);
            if (r < 0) {
                throw new IOException("读取批量字符串时连接中断");
            }
            read += r;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void expectCrlfBytes(InputStream in) throws IOException {
        in.read();
        in.read();
    }

    private static int parseLength(String body) throws IOException {
        try {
            return Integer.parseInt(body.trim());
        } catch (NumberFormatException malformed) {
            throw new IOException("长度字段非法：" + body);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /**
     * 一条订阅连接的生命周期：常驻线程 + 断线退避重连。
     *
     * <p>{@link #close()} 后线程退出；线程为 daemon，不会拖住服务端关停。</p>
     */
    public static final class RedisSubscription implements AutoCloseable {

        private final RedisSettings settings;
        private final String channel;
        private final Consumer<String> onMessage;
        private final Runnable onReconnected;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        RedisSubscription(RedisSettings settings, String channel, Consumer<String> onMessage, Runnable onReconnected) {
            this.settings = settings;
            this.channel = channel;
            this.onMessage = onMessage;
            this.onReconnected = onReconnected;
            this.thread = new Thread(this::loop, "pepperlib-redis-subscription");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        /** 订阅线程是否仍在运行。 */
        public boolean isActive() {
            return running.get() && thread.isAlive();
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }

        private void loop() {
            boolean everConnected = false;
            while (running.get()) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(settings.host(), settings.port()), CONNECT_TIMEOUT_MILLIS);
                    socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                    socket.setTcpNoDelay(true);
                    try (InputStream in = socket.getInputStream();
                            OutputStream out = socket.getOutputStream()) {
                        writeCommand(out, "SUBSCRIBE", channel);

                        // 等订阅确认（*3 数组，首元素 subscribe）
                        SubscribeFrame confirmation = readSubscribeFrame(in);
                        if (!confirmation.isSubscribe()) {
                            throw new IOException("订阅未确认，收到：" + confirmation.kind());
                        }
                        if (everConnected && onReconnected != null) {
                            // 重连成功：让调用方重新拉全量，补上断线窗口内漏掉的更新
                            onReconnected.run();
                        }
                        everConnected = true;

                        // 常驻读推送。
                        //
                        // 读超时必须在这里捕获：通道安静时 SO_TIMEOUT 会如期触发，
                        // 那只是「这段时间没有消息」，连接仍然有效。
                        // 若把超时留给外层 catch，`continue` 会跳到外层循环 ——
                        // 等于每次安静 3 秒就拆掉连接重连一次（生产上表现为
                        // 每 3 秒一条「订阅已重连」+ Redis 侧每 3 秒一个新连接）。
                        while (running.get()) {
                            SubscribeFrame frame;
                            try {
                                frame = readSubscribeFrame(in);
                            } catch (SocketTimeoutException idle) {
                                continue; // 空窗期：继续在同一连接上读
                            }
                            if (frame.isMessage() && frame.message() != null) {
                                onMessage.accept(frame.message());
                            }
                            // 其它帧（如 subscribe/unsubscribe 确认）忽略
                        }
                    }
                } catch (IOException | RedisException disconnected) {
                    // 真正的断线（EOF、连接重置、协议错乱）才重连。
                    if (!running.get()) {
                        return;
                    }
                    sleepQuietly();
                }
            }
        }

        /**
         * 一个订阅帧的内容：类型 + 附带的消息体（仅 {@code message} 帧有）。
         *
         * @param kind    帧类型（{@code subscribe} / {@code message} / …）
         * @param message 消息体；非消息帧为 {@code null}
         */
        private record SubscribeFrame(String kind, String message) {

            boolean isSubscribe() {
                return "subscribe".equals(kind);
            }

            boolean isMessage() {
                return "message".equals(kind);
            }
        }

        /**
         * 读一个完整的订阅帧。
         *
         * <p><b>必须按字节读完整数组</b>：订阅模式下所有响应都是数组，
         * 只读首元素会让剩余元素留在缓冲区里、下一帧读错位；
         * 而按行读会在消息体内部的换行处截断（载荷本身就是多行文本）。</p>
         */
        private static SubscribeFrame readSubscribeFrame(InputStream in) throws IOException {
            String header = readLineBytes(in);
            if (header == null) {
                throw new IOException("订阅连接被对端关闭");
            }
            if (!header.startsWith("*")) {
                throw new IOException("订阅响应不是数组：" + abbreviate(header));
            }
            int count = parseLength(header.substring(1));
            String kind = null;
            String message = null;
            for (int i = 0; i < count; i++) {
                String element = readSubscribeElement(in);
                if (i == 0) {
                    kind = element;
                } else if (i == 2 && "message".equals(kind)) {
                    message = element; // message 帧的第三元素才是载荷
                }
            }
            if (kind == null) {
                throw new IOException("订阅数组为空");
            }
            return new SubscribeFrame(kind, message);
        }

        /**
         * 读一个订阅数组元素。
         *
         * <p>{@code subscribe} 确认帧的第三个元素是整数（订阅计数），
         * 不是批量字符串 —— 只认 {@code $} 会在那里读错位。</p>
         */
        private static String readSubscribeElement(InputStream in) throws IOException {
            String line = readLineBytes(in);
            if (line == null) {
                throw new IOException("读取订阅元素时连接中断");
            }
            if (line.startsWith(":")) {
                return line.substring(1); // 整数元素
            }
            if (!line.startsWith("$")) {
                throw new IOException("订阅元素类型不支持：" + abbreviate(line));
            }
            int length = parseLength(line.substring(1));
            if (length < 0) {
                return null;
            }
            String value = readBulkBytes(in, length);
            expectCrlfBytes(in);
            return value;
        }

        private void sleepQuietly() {
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                running.set(false);
            }
        }
    }

    /** 供诊断：把命令列表渲染成一行（日志用）。 */
    static String describe(String... args) {
        List<String> parts = new ArrayList<>(args.length);
        for (String a : args) {
            parts.add(a.length() <= 20 ? a : a.substring(0, 17) + "...");
        }
        return String.join(" ", parts);
    }
}

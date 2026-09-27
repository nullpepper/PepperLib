package ltd.pepper.lib.redis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的「假 Redis」：在本地端口上按 RESP2 真实收发字节。
 *
 * <p>刻意<b>按字节</b>处理而不是按字符：RESP 协议里的长度是字节数，
 * 而中文等多字节字符的字符数小于字节数 —— 早期版本按字符数读，
 * 遇到中文会一直等永远不来的数据（表现为客户端读超时）。
 * 这个 bug 只在「值里含非 ASCII」时才暴露，正是要靠测试兜住的那类问题。</p>
 *
 * <p>只实现本插件用到的命令：{@code PING / SET / GET / DEL / PUBLISH / SUBSCRIBE / QUIT}。</p>
 */
final class FakeRedisServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    /** 订阅连接对应的输出流（用于推送）。 */
    private final List<OutputStream> subscribers = new CopyOnWriteArrayList<>();
    /** 订阅连接对应的 socket（断开时用）。 */
    private final List<Socket> subscriberSockets = new CopyOnWriteArrayList<>();

    private final AtomicInteger connectCount = new AtomicInteger();
    private volatile boolean closing;
    /** 非 null 时所有命令都回错误（模拟 Redis 报错）。 */
    volatile String forceError;
    /** 非 null 时所有命令都回这段垃圾（模拟协议被破坏）。 */
    volatile String forceGarbage;

    FakeRedisServer() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        acceptor = new Thread(this::acceptLoop, "fake-redis-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    int connectCount() {
        return connectCount.get();
    }

    /** 当前登记的订阅者数量（测试用来等待订阅就绪，避免竞态）。 */
    int subscriberCount() {
        return subscribers.size();
    }

    String stored(String key) {
        return values.get(key);
    }

    /** 服务端主动推送（订阅者应收到）。 */
    void push(String channel, String message) throws IOException {
        for (OutputStream out : subscribers) {
            try {
                writeArray(out, "message", channel, message);
            } catch (IOException gone) {
                subscribers.remove(out); // 连接已断，移除即可
            }
        }
    }

    /**
     * 真正断开所有订阅连接（关闭 socket），触发客户端重连。
     *
     * <p>只清空列表不会让客户端察觉 —— 它要读到 EOF/IO 错误才会重连。</p>
     */
    void dropSubscribers() {
        for (Socket s : subscriberSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭即可
            }
        }
        subscriberSockets.clear();
        subscribers.clear();
    }

    private void acceptLoop() {
        while (!closing) {
            try {
                Socket socket = server.accept();
                connectCount.incrementAndGet();
                Thread t = new Thread(() -> handle(socket), "fake-redis-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException stopped) {
                return;
            }
        }
    }

    private final ThreadLocal<Socket> currentSocket = new ThreadLocal<>();

    private void handle(Socket socket) {
        currentSocket.set(socket);
        try (socket;
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream()) {
            List<String> args;
            while ((args = readCommand(in)) != null) {
                if (closing) {
                    return;
                }
                String garbage = forceGarbage;
                if (garbage != null) {
                    out.write(garbage.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                String error = forceError;
                if (error != null) {
                    out.write(("-ERR " + error + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                dispatch(args, out);
            }
        } catch (IOException ignored) {
            // 连接结束
        }
    }

    private void dispatch(List<String> args, OutputStream out) throws IOException {
        String cmd = args.get(0).toUpperCase(Locale.ROOT);
        switch (cmd) {
            case "PING" -> writeSimple(out, "PONG");
            case "QUIT" -> {
                writeSimple(out, "OK");
                throw new IOException("client quit");
            }
            case "SET" -> {
                values.put(args.get(1), args.get(2));
                writeSimple(out, "OK");
            }
            case "GET" -> {
                String v = values.get(args.get(1));
                if (v == null) {
                    writeRaw(out, "$-1\r\n");
                } else {
                    writeBulk(out, v);
                }
            }
            case "PUBLISH" -> {
                String channel = args.get(1);
                String message = args.get(2);
                // 先回命令响应，再推给订阅者
                writeRaw(out, ":" + subscribers.size() + "\r\n");
                for (OutputStream sub : subscribers) {
                    try {
                        writeArray(sub, "message", channel, message);
                    } catch (IOException gone) {
                        subscribers.remove(sub);
                    }
                }
            }
            case "DEL" -> {
                boolean removed = values.remove(args.get(1)) != null;
                writeRaw(out, ":" + (removed ? 1 : 0) + "\r\n");
            }
            case "SUBSCRIBE" -> {
                subscribers.add(out);
                subscriberSockets.add(currentSocket.get());
                writeArray(out, "subscribe", args.get(1), "1");
            }
            default -> writeRaw(out, "-ERR unknown command '" + cmd + "'\r\n");
        }
    }

    // ==================== RESP2 读写（按字节） ====================

    /** 读一条数组形式的命令；连接结束返回 {@code null}。 */
    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        if (!header.startsWith("*")) {
            return List.of("__malformed__");
        }
        int n = Integer.parseInt(header.substring(1));
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String lenLine = readLine(in);
            if (lenLine == null || !lenLine.startsWith("$")) {
                return List.of("__malformed__");
            }
            int size = Integer.parseInt(lenLine.substring(1));
            byte[] payload = readExactly(in, size);
            if (payload == null) {
                return null;
            }
            expectCrlf(in);
            args.add(new String(payload, StandardCharsets.UTF_8));
        }
        return args;
    }

    private static String readLine(InputStream in) throws IOException {
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

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        int read = 0;
        while (read < size) {
            int r = in.read(buf, read, size - read);
            if (r < 0) {
                return null;
            }
            read += r;
        }
        return buf;
    }

    private static void expectCrlf(InputStream in) throws IOException {
        in.read();
        in.read();
    }

    private static void writeRaw(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeSimple(OutputStream out, String text) throws IOException {
        writeRaw(out, "+" + text + "\r\n");
    }

    private static void writeBulk(OutputStream out, String value) throws IOException {
        writeRaw(out, "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n");
    }

    private static void writeArray(OutputStream out, String... parts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append('$')
                    .append(p.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(p)
                    .append("\r\n");
        }
        writeRaw(out, sb.toString());
    }

    @Override
    public void close() {
        closing = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭即可
        }
        acceptor.interrupt();
    }
}

package com.example.mariogame;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 超级像素冒险 - 本地静态资源服务器入口。
 *
 * 使用 JDK 自带的 com.sun.net.httpserver.HttpServer 托管 src/main/resources/static
 * 下的游戏静态资源（index.html 等）。启动后自动用系统默认浏览器打开游戏页面。
 *
 * 无第三方依赖，仅使用 JDK 标准库，可在 IDEA 中直接运行 main 方法。
 */
public class MarioGameServer {

    /** 起始端口，被占用时自动 +1 顺延。 */
    private static final int START_PORT = 8080;
    /** 最大尝试端口数。 */
    private static final int MAX_PORT_TRIES = 50;
    /** classpath 中静态资源根路径。 */
    private static final String STATIC_PREFIX = "/static";

    public static void main(String[] args) {
        int port = findAvailablePort(START_PORT, MAX_PORT_TRIES);
        if (port < 0) {
            System.err.println("未找到可用端口（尝试了 " + START_PORT + " ~ " + (START_PORT + MAX_PORT_TRIES - 1) + "），请释放端口后重试。");
            System.exit(1);
            return;
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            System.err.println("创建 HttpServer 失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        server.createContext("/", new StaticHandler());
        server.setExecutor(null); // 使用默认执行器

        // 优雅关停
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在停止服务器...");
            server.stop(0);
            System.out.println("服务器已停止。");
        }));

        server.start();

        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("============================================");
        System.out.println("  超级像素冒险 已启动");
        System.out.println("  访问地址: " + url);
        System.out.println("  停止方式: 按 Ctrl+C 或关闭此控制台窗口");
        System.out.println("============================================");

        openBrowser(url);
    }

    /**
     * 从 start 开始寻找可用端口，最多尝试 maxTries 个。
     * 找到返回端口号，否则返回 -1。
     */
    private static int findAvailablePort(int start, int maxTries) {
        for (int i = 0; i < maxTries; i++) {
            int candidate = start + i;
            try {
                // 尝试绑定一个临时 ServerSocket 检测端口是否可用
                java.net.ServerSocket ss = new java.net.ServerSocket();
                try {
                    ss.setReuseAddress(false);
                    ss.bind(new java.net.InetSocketAddress("127.0.0.1", candidate), 1);
                } finally {
                    ss.close();
                }
                return candidate;
            } catch (IOException ignored) {
                // 端口被占用，继续下一个
            }
        }
        return -1;
    }

    /** 用系统默认浏览器打开 URL。 */
    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
            // 回退到命令行方式
        }
        // 命令行回退
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (IOException e) {
            System.out.println("自动打开浏览器失败，请手动访问: " + url + "  (" + e.getMessage() + ")");
        }
    }

    /** 静态资源处理器：从 classpath /static 下读取并返回。 */
    static class StaticHandler implements HttpHandler {
        private static final Map<String, String> CONTENT_TYPES = new HashMap<String, String>();
        static {
            CONTENT_TYPES.put("html", "text/html; charset=utf-8");
            CONTENT_TYPES.put("htm", "text/html; charset=utf-8");
            CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
            CONTENT_TYPES.put("css", "text/css; charset=utf-8");
            CONTENT_TYPES.put("json", "application/json; charset=utf-8");
            CONTENT_TYPES.put("png", "image/png");
            CONTENT_TYPES.put("jpg", "image/jpeg");
            CONTENT_TYPES.put("jpeg", "image/jpeg");
            CONTENT_TYPES.put("gif", "image/gif");
            CONTENT_TYPES.put("svg", "image/svg+xml");
            CONTENT_TYPES.put("ico", "image/x-icon");
            CONTENT_TYPES.put("woff", "font/woff");
            CONTENT_TYPES.put("woff2", "font/woff2");
            CONTENT_TYPES.put("ttf", "font/ttf");
            CONTENT_TYPES.put("txt", "text/plain; charset=utf-8");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                // 根路径映射到 index.html
                if (path.equals("/") || path.isEmpty()) {
                    path = "/index.html";
                }
                // 安全：防止路径穿越
                if (path.contains("..")) {
                    sendError(exchange, 403, "Forbidden");
                    return;
                }

                String resourcePath = STATIC_PREFIX + path;
                byte[] data = readResource(resourcePath);
                if (data == null) {
                    sendError(exchange, 404, "Not Found: " + path);
                    return;
                }

                String contentType = guessContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, data.length);
                OutputStream os = exchange.getResponseBody();
                os.write(data);
                os.close();
            } catch (Exception e) {
                try {
                    sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        }

        /** 从 classpath 读取资源为字节数组。 */
        private byte[] readResource(String resourcePath) throws IOException {
            // 1. 优先用 classpath（jar 内）
            InputStream in = getClass().getResourceAsStream(resourcePath);
            if (in == null) {
                // 2. 回退：开发期从 classes 目录 / resources 目录按文件系统读取
                URL url = getClass().getResource(resourcePath);
                if (url != null) {
                    in = url.openStream();
                }
            }
            if (in == null) {
                return null;
            }
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            } finally {
                in.close();
            }
        }

        private String guessContentType(String path) {
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) {
                return "application/octet-stream";
            }
            String ext = path.substring(dot + 1).toLowerCase();
            return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
        }

        private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(code, body.length);
            OutputStream os = exchange.getResponseBody();
            os.write(body);
            os.close();
        }
    }
}

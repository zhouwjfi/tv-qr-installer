package com.zhouwj.tvinstaller;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP 服务器,只服务两件事:
 *   GET  /        -> 返回手机端上传页
 *   POST /upload  -> 接收文件原始字节流(前端用 XHR 直接 send(File),无需 multipart)
 * 每个连接处理完即关闭(Connection: close)。
 */
final class HttpFileServer {

    interface Callback {
        void onUploadStart(String name, long totalBytes);

        void onProgress(long done, long totalBytes);

        void onReceived(File file, String displayName);

        void onUploadError(String message);

        void onServerReady(String url);
    }

    private final int port;
    private final File targetFile;
    private final String indexHtml;
    private final Callback callback;
    private ServerSocket serverSocket;
    private volatile boolean running;

    HttpFileServer(int port, File targetFile, String indexHtml, Callback callback) {
        this.port = port;
        this.targetFile = targetFile;
        this.indexHtml = indexHtml;
        this.callback = callback;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"));
        running = true;
        Thread t = new Thread(this::acceptLoop, "http-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    boolean isAlive() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running || serverSocket.isClosed()) {
                    return;
                }
                // 意外异常不退出循环,稍等后继续 accept
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(out, 400, "text/plain; charset=utf-8", "bad request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }

            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 200; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                respond(out, 200, "text/html; charset=utf-8",
                        indexHtml.getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(method) && "/upload".equals(path)) {
                handleUpload(in, out, headers);
            } else {
                respond(out, 404, "text/plain; charset=utf-8",
                        "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleUpload(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        long len;
        try {
            len = Long.parseLong(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            len = 0;
        }
        if (len <= 0) {
            respond(out, 411, "text/plain; charset=utf-8",
                    "缺少 Content-Length".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = "upload.apk";
        String raw = headers.get("x-filename");
        if (raw != null && !raw.isEmpty()) {
            name = URLDecoder.decode(raw, "UTF-8");
        }
        name = sanitize(name);

        try (FileOutputStream fo = new FileOutputStream(targetFile)) {
            callback.onUploadStart(name, len);
            byte[] buf = new byte[65536];
            long done = 0;
            int lastPct = -1;
            int n;
            while (done < len && (n = in.read(buf, 0, (int) Math.min((long) buf.length, len - done))) != -1) {
                fo.write(buf, 0, n);
                done += n;
                int pct = (int) (done * 100 / len);
                if (pct != lastPct) {
                    lastPct = pct;
                    callback.onProgress(done, len);
                }
            }
            if (done < len) {
                throw new IOException("连接中断,仅收到 " + done + "/" + len + " 字节");
            }
            fo.getFD().sync();
        } catch (IOException e) {
            targetFile.delete();
            callback.onUploadError(String.valueOf(e.getMessage()));
            respond(out, 400, "text/plain; charset=utf-8",
                    ("上传失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        callback.onReceived(targetFile, name);
        respond(out, 200, "application/json; charset=utf-8",
                ("{\"ok\":true,\"size\":" + targetFile.length() + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(String name) {
        name = name.replaceAll("[/\\\\:;\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) {
            name = "upload.apk";
        }
        if (name.length() > 80) {
            name = name.substring(name.length() - 80);
        }
        return name;
    }

    private static void respond(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 404 ? "Not Found" : status == 411 ? "Length Required" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** 按字节逐个读取一行(ISO-8859-1),避免缓冲吞掉请求体 */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\r') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }
}

package com.parental.tvguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 极简局域网题库上传服务(端口 8765)。
 * 手机浏览器访问 http://<电视IP>:8765 上传 JSON 题库。
 * 纯 ServerSocket 实现,无第三方依赖。
 */
public final class LanServer {

    private static final String TAG = "LanServer";
    public static final int PORT = 8765;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static LanServer sInstance;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public LanServer(Context context) {
        this.context = context.getApplicationContext();
        sInstance = this;
    }

    /** 全局单例,保证运行状态在界面重建后仍一致。 */
    public static LanServer get(Context context) {
        if (sInstance == null) new LanServer(context);
        return sInstance;
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Log.i(TAG, "LAN 服务已启动,端口 " + PORT);
                    while (running) {
                        final Socket s = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handle(s);
                            }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) Log.e(TAG, "LAN 服务异常", e);
                }
            }
        }, "LanServer-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
        Log.i(TAG, "LAN 服务已停止");
    }

    public boolean isRunning() {
        return running;
    }

    // ---- 请求处理 ----

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) {
                s.close();
                return;
            }
            // 解析请求头
            long contentLength = 0;
            String boundary = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (low.startsWith("content-type:") && line.contains("boundary=")) {
                    boundary = line.substring(line.indexOf("boundary=") + 9).trim();
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                }
            }

            String resp;
            if (requestLine.startsWith("POST /upload")) {
                byte[] body = readBytes(in, contentLength);
                resp = handleUpload(boundary, body);
            } else {
                resp = buildPage();
            }

            byte[] body = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            s.close();
        } catch (Exception e) {
            Log.e(TAG, "处理请求失败", e);
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String handleUpload(String boundary, byte[] body) {
        if (boundary == null) return "<h2>上传失败:不是 multipart 表单</h2>" + backLink();
        String bmark = "--" + boundary;
        // 找到文件部分:第一个 boundary 后的 header 结束点
        byte[] bmarkBytes = bmark.getBytes(StandardCharsets.UTF_8);
        int start = indexOf(body, bmarkBytes, 0);
        if (start < 0) return "<h2>上传失败:未找到文件数据</h2>" + backLink();
        int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), start);
        if (headerEnd < 0) return "<h2>上传失败:数据格式错误</h2>" + backLink();
        int dataStart = headerEnd + 4;
        int dataEnd = indexOf(body, bmarkBytes, dataStart);
        if (dataEnd < 0) dataEnd = body.length;
        if (dataEnd > dataStart + 2) dataEnd -= 2; // 去掉结尾 \r\n

        // 提取文件名
        String headers = new String(body, start, headerEnd + 4 - start, StandardCharsets.UTF_8);
        String fileName = "upload.json";
        int fnIdx = headers.indexOf("filename=\"");
        if (fnIdx >= 0) {
            int end = headers.indexOf('"', fnIdx + 10);
            if (end > fnIdx + 10) {
                fileName = headers.substring(fnIdx + 10, end);
            }
        }

        if (dataEnd <= dataStart) return "<h2>上传失败:文件为空</h2>" + backLink();
        String json = new String(body, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8);
        // 去除 UTF-8 BOM
        if (json.startsWith("\uFEFF")) json = json.substring(1);

        try {
            final int count = QuestionBank.importBank(context, fileName, json);
            MonitorService.reloadBank(context);
            toast("题库导入成功:" + fileName + " 共 " + count + " 题");
            return "<h2>导入成功!共 " + count + " 道题,电视已生效。</h2>" + backLink();
        } catch (final Exception e) {
            toast("题库导入失败:" + e.getMessage());
            return "<h2>导入失败:" + escape(e.getMessage()) + "</h2>" + backLink();
        }
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String buildPage() {
        String ip = getLocalIp();
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>题库上传</title></head><body style='font-family:sans-serif;max-width:560px;margin:40px auto'>"
                + "<h2>视界守卫者 - 题库上传</h2>"
                + "<p>选择转换好的 questions.json 文件上传:</p>"
                + "<form method='POST' action='/upload' enctype='multipart/form-data'>"
                + "<input type='file' name='file' accept='.json'><br><br>"
                + "<button style='padding:10px 24px;font-size:16px'>上传</button>"
                + "</form>"
                + "<p style='color:#888'>题库格式见 App 说明文档。</p>"
                + "</body></html>";
    }

    private String backLink() {
        return "<p><a href='/'>返回继续上传</a></p>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String getLocalIp() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return "0.0.0.0";
    }

    // ---- IO ----

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        if (c == -1 && bos.size() == 0) return null;
        return bos.toString("UTF-8");
    }

    private static byte[] readBytes(InputStream in, long len) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long remain = len;
        while (remain > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
            if (n < 0) break;
            bos.write(buf, 0, n);
            remain -= n;
        }
        return bos.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

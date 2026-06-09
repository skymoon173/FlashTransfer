package com.localdrop.flashtransfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TransferService extends Service {
    public static final int HTTP_PORT = 8765;
    public static final int DISCOVERY_PORT = 8766;
    public static final String DISCOVERY_MESSAGE = "FLASH_TRANSFER_DISCOVER";
    public static final String ACTION_STARTED = "com.localdrop.flashtransfer.STARTED";
    public static final String ACTION_STOP = "com.localdrop.flashtransfer.STOP";
    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024 * 1024;
    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    public static volatile String token;
    public static volatile boolean running;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket discoverySocket;
    private File sharedDir;

    @Override
    public void onCreate() {
        super.onCreate();
        sharedDir = new File(getFilesDir(), "shared_files");
        sharedDir.mkdirs();
        token = randomToken();
        startForeground(1001, notification());
        running = true;
        workers.execute(this::httpLoop);
        workers.execute(this::discoveryLoop);
        sendBroadcast(new Intent(ACTION_STARTED).setPackage(getPackageName()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        close(serverSocket);
        if (discoverySocket != null) discoverySocket.close();
        workers.shutdownNow();
        sendBroadcast(new Intent(ACTION_STARTED).setPackage(getPackageName()));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private android.app.Notification notification() {
        String channelId = "transfer_host";
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(channelId, "闪传主机服务", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TransferService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, channelId)
                : new android.app.Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("闪传主机正在运行")
                    .setContentText("其他设备现在可以连接并交换文件")
                    .setContentIntent(openIntent)
                    .addAction(new android.app.Notification.Action.Builder(null, "停止", stopIntent).build())
                    .setOngoing(true)
                    .build();
    }

    private String randomToken() {
        byte[] bytes = new byte[5];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format("%02x", item));
        return value.toString();
    }

    private void discoveryLoop() {
        try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
            discoverySocket = socket;
            socket.setBroadcast(true);
            byte[] buffer = new byte[1024];
            while (running) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String message = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8).trim();
                if (!DISCOVERY_MESSAGE.equals(message)) continue;
                JSONObject payload = new JSONObject();
                payload.put("service", "flash-transfer");
                payload.put("port", HTTP_PORT);
                payload.put("token", token);
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, request.getAddress(), request.getPort()));
            }
        } catch (Exception ignored) {
        }
    }

    private void httpLoop() {
        try (ServerSocket socket = new ServerSocket(HTTP_PORT)) {
            serverSocket = socket;
            while (running) {
                Socket client = socket.accept();
                workers.execute(() -> handle(client));
            }
        } catch (Exception ignored) {
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            client.setReceiveBufferSize(2 * IO_BUFFER_SIZE);
            client.setSendBufferSize(2 * IO_BUFFER_SIZE);
            try (BufferedInputStream input = new BufferedInputStream(client.getInputStream(), IO_BUFFER_SIZE);
                 BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream(), IO_BUFFER_SIZE)) {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] first = requestLine.split(" ", 3);
            if (first.length < 2) return;
            String method = first[0];
            String target = first[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            route(method, target, headers, input, output);
            output.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void route(String method, String target, Map<String, String> headers, InputStream input, OutputStream output) throws Exception {
        int question = target.indexOf('?');
        String path = decode(question >= 0 ? target.substring(0, question) : target);
        Map<String, String> query = parseQuery(question >= 0 ? target.substring(question + 1) : "");
        if ("/".equals(path)) {
            redirect(output, "/" + token + "/");
            return;
        }
        String prefix = "/" + token + "/";
        if (!path.startsWith(prefix)) {
            text(output, 404, "Not found");
            return;
        }
        String relative = path.substring(prefix.length());
        if (relative.isEmpty()) {
            asset(output, "web/index.html", "text/html; charset=utf-8");
        } else if ("static/app.css".equals(relative)) {
            asset(output, "web/app.css", "text/css; charset=utf-8");
        } else if ("static/app.js".equals(relative)) {
            asset(output, "web/app.js", "text/javascript; charset=utf-8");
        } else if ("api/files".equals(relative) && "GET".equals(method)) {
            listFiles(output);
        } else if ("api/upload".equals(relative) && "POST".equals(method)) {
            upload(output, input, headers, query.get("name"));
        } else if ("api/download".equals(relative) && "GET".equals(method)) {
            download(output, query.get("name"));
        } else if ("api/file".equals(relative) && "DELETE".equals(method)) {
            delete(output, query.get("name"));
        } else {
            text(output, 404, "Not found");
        }
    }

    private void listFiles(OutputStream output) throws Exception {
        ArrayList<File> files = new ArrayList<>();
        collect(sharedDir, files);
        files.sort(Comparator.comparingLong(File::lastModified).reversed());
        JSONArray list = new JSONArray();
        for (File file : files) {
            JSONObject item = new JSONObject();
            item.put("name", relative(file));
            item.put("size", file.length());
            item.put("modified", file.lastModified());
            list.put(item);
        }
        JSONObject payload = new JSONObject();
        payload.put("files", list);
        json(output, 200, payload.toString());
    }

    private void upload(OutputStream output, InputStream input, Map<String, String> headers, String name) throws Exception {
        long length;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException error) {
            length = -1;
        }
        if (length < 0 || name == null) {
            json(output, 400, "{\"error\":\"无效上传\"}");
            return;
        }
        if (length > MAX_UPLOAD_BYTES) {
            json(output, 413, "{\"error\":\"单个文件超过 50 GB 限制\"}");
            return;
        }
        if (sharedDir.getUsableSpace() < length + 16L * 1024 * 1024) {
            json(output, 507, "{\"error\":\"手机存储空间不足\"}");
            return;
        }
        File destination = uniqueFile(safeRelative(name));
        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(destination.getParentFile(), "." + destination.getName() + ".uploading");
        try (FileOutputStream file = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new Exception("连接提前断开");
                file.write(buffer, 0, count);
                remaining -= count;
            }
        }
        if (!temporary.renameTo(destination)) {
            copy(temporary, destination);
            temporary.delete();
        }
        JSONObject payload = new JSONObject();
        JSONObject file = new JSONObject();
        file.put("name", relative(destination));
        file.put("size", destination.length());
        file.put("modified", destination.lastModified());
        payload.put("file", file);
        json(output, 201, payload.toString());
    }

    private void download(OutputStream output, String name) throws Exception {
        File file = resolve(name);
        if (file == null || !file.isFile()) {
            text(output, 404, "Not found");
            return;
        }
        String encoded = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
        String fallback = file.getName().replaceAll("[^A-Za-z0-9._-]", "_").replace("\"", "_");
        String mime = mimeType(file.getName());
        headers(output, 200, mime, file.length(), "Content-Disposition: attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded + "\r\n");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    private void delete(OutputStream output, String name) throws Exception {
        File file = resolve(name);
        if (file == null || !file.isFile() || !file.delete()) {
            json(output, 404, "{\"error\":\"文件不存在\"}");
            return;
        }
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(sharedDir) && parent.list() != null && parent.list().length == 0) {
            parent.delete();
            parent = parent.getParentFile();
        }
        json(output, 200, "{\"deleted\":\"" + escape(relative(file)) + "\"}");
    }

    private void asset(OutputStream output, String name, String type) throws Exception {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            byte[] data = bytes.toByteArray();
            headers(output, 200, type, data.length, "Cache-Control: no-cache\r\n");
            output.write(data);
        }
    }

    private void json(OutputStream output, int status, String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        headers(output, status, "application/json; charset=utf-8", data.length, "Cache-Control: no-store\r\n");
        output.write(data);
    }

    private void text(OutputStream output, int status, String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        headers(output, status, "text/plain; charset=utf-8", data.length, "");
        output.write(data);
    }

    private void redirect(OutputStream output, String location) throws Exception {
        output.write(("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void headers(OutputStream output, int status, String type, long length, String extra) throws Exception {
        String reason = status == 200 ? "OK" : status == 201 ? "Created" : "Error";
        String value = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length + "\r\n" + extra + "Connection: close\r\n\r\n";
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) >= 0) {
            if (previous == '\r' && current == '\n') break;
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
        if (current < 0 && previous < 0 && bytes.size() == 0) return null;
        if (previous >= 0 && previous != '\r') bytes.write(previous);
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private Map<String, String> parseQuery(String value) throws Exception {
        Map<String, String> query = new HashMap<>();
        for (String item : value.split("&")) {
            if (item.isEmpty()) continue;
            String[] pair = item.split("=", 2);
            query.put(decode(pair[0]), decode(pair.length > 1 ? pair[1] : ""));
        }
        return query;
    }

    private String decode(String value) throws Exception {
        return URLDecoder.decode(value, "UTF-8");
    }

    private String safeRelative(String value) {
        ArrayList<String> parts = new ArrayList<>();
        for (String part : value.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            String cleaned = part.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_");
            parts.add(cleaned.isEmpty() ? "unnamed-file" : cleaned);
            if (parts.size() >= 32) break;
        }
        return String.join(File.separator, parts);
    }

    private File resolve(String name) throws Exception {
        if (name == null) return null;
        File file = new File(sharedDir, safeRelative(name)).getCanonicalFile();
        return file.getPath().startsWith(sharedDir.getCanonicalPath() + File.separator) ? file : null;
    }

    private File uniqueFile(String name) {
        File file = new File(sharedDir, name);
        if (!file.exists()) return file;
        String original = file.getName();
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        String suffix = dot > 0 ? original.substring(dot) : "";
        int index = 1;
        while (true) {
            File candidate = new File(file.getParentFile(), stem + " (" + index + ")" + suffix);
            if (!candidate.exists()) return candidate;
            index++;
        }
    }

    private String relative(File file) {
        return sharedDir.toURI().relativize(file.toURI()).getPath();
    }

    private void collect(File directory, ArrayList<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, output);
            else output.add(child);
        }
    }

    private void copy(File source, File destination) throws Exception {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type != null) return type;
        }
        return "application/octet-stream";
    }

    private void close(ServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
    }
}

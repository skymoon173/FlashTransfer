package com.localdrop.flashtransfer;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 42;

    private LinearLayout homePanel;
    private EditText addressInput;
    private TextView statusText;
    private TextView hostAddress;
    private ImageView hostQr;
    private ProgressBar progress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private WifiManager.MulticastLock multicastLock;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateHostStatus();
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        configureWebView();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceReceiver, new IntentFilter(TransferService.ACTION_STARTED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, new IntentFilter(TransferService.ACTION_STARTED));
        }
        updateHostStatus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(serviceReceiver);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hostAddress != null) updateHostStatus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        return button;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 16, 28));

        ScrollView scroll = new ScrollView(this);
        homePanel = new LinearLayout(this);
        homePanel.setOrientation(LinearLayout.VERTICAL);
        homePanel.setPadding(dp(22), dp(42), dp(22), dp(32));
        scroll.addView(homePanel, new ScrollView.LayoutParams(-1, -2));

        TextView logo = text("闪传", 36, Color.WHITE);
        logo.setTypeface(null, Typeface.BOLD);
        homePanel.addView(logo);
        TextView subtitle = text("一台手机做主机，所有设备都能传", 14, Color.rgb(145, 160, 181));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.bottomMargin = dp(28);
        homePanel.addView(subtitle, subtitleParams);

        LinearLayout hostCard = card();
        TextView hostTitle = text("让别人连接这台手机", 21, Color.WHITE);
        hostTitle.setTypeface(null, Typeface.BOLD);
        hostCard.addView(hostTitle);
        hostCard.addView(text("适用于安卓 ↔ 安卓、安卓 ↔ iPhone、手机 ↔ 电脑", 12, Color.rgb(145, 160, 181)));
        hostAddress = text("主机服务未开启", 14, Color.rgb(141, 245, 194));
        hostAddress.setTextIsSelectable(true);
        hostAddress.setPadding(0, dp(16), 0, dp(10));
        hostCard.addView(hostAddress);
        hostQr = new ImageView(this);
        hostQr.setVisibility(View.GONE);
        hostQr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(220));
        qrParams.bottomMargin = dp(10);
        hostCard.addView(hostQr, qrParams);
        LinearLayout hostButtons = row();
        hostButtons.addView(button("开启主机", v -> startHost()), new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        openParams.leftMargin = dp(8);
        hostButtons.addView(button("打开共享页", v -> openLocalHost()), openParams);
        hostCard.addView(hostButtons);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, dp(48));
        stopParams.topMargin = dp(8);
        hostCard.addView(button("停止主机", v -> stopHost()), stopParams);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(-1, dp(48));
        shareParams.topMargin = dp(8);
        hostCard.addView(button("分享主机地址", v -> shareHostAddress()), shareParams);
        homePanel.addView(hostCard);

        LinearLayout connectCard = card();
        TextView connectTitle = text("连接附近设备", 21, Color.WHITE);
        connectTitle.setTypeface(null, Typeface.BOLD);
        connectCard.addView(connectTitle);
        statusText = text("点击自动查找，连接附近运行闪传的设备", 12, Color.rgb(145, 160, 181));
        statusText.setPadding(0, dp(4), 0, dp(10));
        connectCard.addView(statusText);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        connectCard.addView(progress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        addressInput = new EditText(this);
        addressInput.setHint("粘贴对方显示的完整地址");
        addressInput.setTextColor(Color.WHITE);
        addressInput.setHintTextColor(Color.rgb(120, 137, 158));
        addressInput.setSingleLine(true);
        addressInput.setBackgroundColor(Color.rgb(20, 33, 53));
        addressInput.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(52));
        inputParams.topMargin = dp(12);
        connectCard.addView(addressInput, inputParams);
        LinearLayout connectButtons = row();
        connectButtons.addView(button("自动查找", v -> discoverServer()), new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        connectParams.leftMargin = dp(8);
        connectButtons.addView(button("连接地址", v -> openAddress(addressInput.getText().toString())), connectParams);
        LinearLayout.LayoutParams connectButtonsParams = new LinearLayout.LayoutParams(-1, -2);
        connectButtonsParams.topMargin = dp(8);
        connectCard.addView(connectButtons, connectButtonsParams);
        LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(-1, dp(48));
        wifiParams.topMargin = dp(8);
        connectCard.addView(button("打开 Wi-Fi / 热点设置", v -> startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS))), wifiParams);
        LinearLayout.LayoutParams connectCardParams = new LinearLayout.LayoutParams(-1, -2);
        connectCardParams.topMargin = dp(16);
        homePanel.addView(connectCard, connectCardParams);

        TextView note = text("使用方式：任意安卓手机开启主机，其他安卓点击自动查找；iPhone 或电脑在浏览器输入主机显示的地址。", 12, Color.rgb(145, 160, 181));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(18);
        homePanel.addView(note, noteParams);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        root.addView(webView, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.rgb(16, 27, 43));
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = downloadFileName(url, contentDisposition, mimeType);
                String resolvedMime = downloadMimeType(fileName, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName);
                request.setMimeType(resolvedMime);
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(this, "已开始下载", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "无法开始下载", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String downloadFileName(String url, String contentDisposition, String mimeType) {
        try {
            String queryName = Uri.parse(url).getQueryParameter("name");
            if (queryName != null && !queryName.trim().isEmpty()) {
                String clean = queryName.replace('\\', '/');
                clean = clean.substring(clean.lastIndexOf('/') + 1);
                if (!clean.isEmpty()) return clean;
            }
        } catch (Exception ignored) {
        }
        return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
    }

    private String downloadMimeType(String fileName, String supplied) {
        if (supplied != null && !supplied.isEmpty() && !"application/octet-stream".equals(supplied)) return supplied;
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String detected = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (detected != null) return detected;
        }
        return "application/octet-stream";
    }

    private void startHost() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        Intent service = new Intent(this, TransferService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "正在开启主机服务", Toast.LENGTH_SHORT).show();
        hostAddress.postDelayed(this::updateHostStatus, 500);
    }

    private void stopHost() {
        stopService(new Intent(this, TransferService.class));
        hostAddress.postDelayed(this::updateHostStatus, 300);
    }

    private void updateHostStatus() {
        if (!TransferService.running || TransferService.token == null) {
            hostAddress.setText("主机服务未开启");
            hostQr.setVisibility(View.GONE);
            return;
        }
        ArrayList<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            hostAddress.setText("主机已开启，请先打开 Wi-Fi 或热点");
            hostQr.setVisibility(View.GONE);
            return;
        }
        StringBuilder value = new StringBuilder("主机已开启，其他设备打开：\n");
        for (String address : addresses) {
            value.append("http://").append(address).append(":").append(TransferService.HTTP_PORT).append("/").append(TransferService.token).append("/\n");
        }
        hostAddress.setText(value.toString().trim());
        showQr("http://" + addresses.get(0) + ":" + TransferService.HTTP_PORT + "/" + TransferService.token + "/");
    }

    private void showQr(String value) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600);
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.RGB_565);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            hostQr.setImageBitmap(bitmap);
            hostQr.setVisibility(View.VISIBLE);
        } catch (Exception error) {
            hostQr.setVisibility(View.GONE);
        }
    }

    private void openLocalHost() {
        if (!TransferService.running || TransferService.token == null) {
            startHost();
            webView.postDelayed(this::openLocalHost, 600);
            return;
        }
        openAddress("http://127.0.0.1:" + TransferService.HTTP_PORT + "/" + TransferService.token + "/");
    }

    private void shareHostAddress() {
        if (!TransferService.running || TransferService.token == null) {
            Toast.makeText(this, "请先开启主机", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            Toast.makeText(this, "请先打开 Wi-Fi 或热点", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "http://" + addresses.get(0) + ":" + TransferService.HTTP_PORT + "/" + TransferService.token + "/";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "打开闪传共享地址：\n" + url);
        startActivity(Intent.createChooser(share, "分享主机地址"));
    }

    private ArrayList<String> localAddresses() {
        ArrayList<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface item : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!item.isUp() || item.isLoopback()) continue;
                for (InetAddress address : Collections.list(item.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        String value = address.getHostAddress();
                        if (!addresses.contains(value)) addresses.add(value);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        addresses.sort((a, b) -> Boolean.compare(!a.endsWith(".1"), !b.endsWith(".1")));
        return addresses;
    }

    private void discoverServer() {
        progress.setVisibility(View.VISIBLE);
        statusText.setText("正在查找附近设备...");
        new Thread(() -> {
            String found = null;
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicastLock = wifi.createMulticastLock("flash-transfer-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.setSoTimeout(1200);
                byte[] message = TransferService.DISCOVERY_MESSAGE.getBytes(StandardCharsets.UTF_8);
                DatagramPacket request = new DatagramPacket(message, message.length, InetAddress.getByName("255.255.255.255"), TransferService.DISCOVERY_PORT);
                for (int attempt = 0; attempt < 3 && found == null; attempt++) {
                    socket.send(request);
                    byte[] buffer = new byte[2048];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(response);
                        JSONObject data = new JSONObject(new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8));
                        if ("flash-transfer".equals(data.optString("service"))) {
                            found = "http://" + response.getAddress().getHostAddress() + ":" + data.getInt("port") + "/" + data.getString("token") + "/";
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            }
            String result = found;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (result != null) {
                    statusText.setText("已找到设备，正在连接...");
                    openAddress(result);
                } else {
                    statusText.setText("未找到设备，请确认双方连接同一 Wi-Fi / 热点，并且对方已开启主机。");
                }
            });
        }).start();
    }

    private void openAddress(String raw) {
        String url = raw.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        webView.setVisibility(View.VISIBLE);
        ((View) homePanel.getParent()).setVisibility(View.GONE);
        webView.loadUrl(url);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webView.getVisibility() == View.VISIBLE) {
            webView.setVisibility(View.GONE);
            ((View) homePanel.getParent()).setVisibility(View.VISIBLE);
            updateHostStatus();
        } else {
            super.onBackPressed();
        }
    }
}

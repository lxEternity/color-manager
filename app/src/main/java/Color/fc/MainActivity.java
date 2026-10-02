package Color.fc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Vue 应用宿主：WebView 承载 assets/webapp（Vite 构建产物），
 * 原生能力（root/电池/CPU/悬浮窗/主题）经 NativeBridge 暴露给 JS。
 * 窗口层主题（日/夜间、背景沉浸、系统栏配色）仍由 ThemedActivity/ThemeStore 处理。
 */
public class MainActivity extends ThemedActivity {

    private WebView webView;
    private NativeBridge bridge;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setTextZoom(100);
        // 沉浸模式透出窗口背景（自定义背景图/全透明），普通模式也保持透明让 CSS 控制
        webView.setBackgroundColor(0x00000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        bridge = new NativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "NativeFC");

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 页内资源放行；外链跳系统浏览器
                if (url.startsWith("file:///android_asset/")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/webapp/index.html");

        // 已配置单应用负载限制则确保执行服务在跑（与旧版一致）
        AppLimitService.ensure(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge != null) bridge.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        // Vue 单页应用无多级页面历史，返回键直接退后台（保留悬浮窗/服务）
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.shutdown();
        if (webView != null) {
            webView.loadUrl("about:blank");
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vue ↔ 原生桥。
 * JS 侧（bridge.js）通过 addJavascriptInterface 注入的 NativeFC.call(method,args,id) 调用；
 * 本类在单线程队列执行（root/文件操作串行，避免并发 su 抢占），
 * 完成后经主线程 evaluateJavascript 回调 window.__cfResult(id,ok,json)。
 * 原生主动通知（背景图更换/主题变化/Root 状态）走 window.__cfEvent(json)。
 */
public class NativeBridge {

    private static final String PAGE_URL = "file:///android_asset/webapp/index.html";

    private final Activity host;
    private final android.webkit.WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 单线程：所有 shell/文件操作排队执行，JS 端永不阻塞 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    // ===== Root 状态缓存 =====
    private static volatile boolean rooted = false;
    private static volatile boolean rootChecked = false;
    private static volatile boolean rootRequesting = false;
    /** 核心数缓存（cpuSnapshot 首次探测） */
    private static volatile int coreCountCache = 0;
    private static SocInfo socCache = null;
    private static String bgDataCache = null;
    private static long bgDataStamp = -1;

    private static final int REQ_PICK = 0x6CB3;

    /** 功耗记录采样间隔 */
    private static final long SAMPLE_INTERVAL_MS = 2_000;
    /** powerCsv 传输行数上限（超出按等距抽稀，防大 CSV 撑爆旧 WebView） */
    private static final int CSV_MAX_ROWS = 12_000;

    private volatile boolean running = true;
    private Thread sampler;

    public NativeBridge(Activity host, android.webkit.WebView webView) {
        this.host = host;
        this.webView = webView;
        pool.submit(() -> {
            // 后台预检测 Root（已授权时秒回；未授权 su 超时 6s 返回无 ROOT）
            rooted = RootShell.hasRoot();
            rootChecked = true;
            emitEvent(rootEvent());
        });
        startSampler();
    }

    /**
     * 功耗历史采样循环（独立守护线程，2s 一拍）：
     * 读电池 → 按当前电芯模式修正 → 写入 PowerHistoryManager（内部 2s 节流）。
     * 不占用桥的单线程队列，页面 JS 调用永不被采样阻塞。
     */
    private void startSampler() {
        sampler = new Thread(() -> {
            while (running) {
                try {
                    PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                    if (st != null) {
                        int cellMode = host.getSharedPreferences("colorfc", Context.MODE_PRIVATE)
                                .getInt("cellMode", 0);
                        st.watts = PowerMonitor.applyCellMode(st, cellMode);
                        PowerHistoryManager.record(host, st);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "cf-power-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** 宿主销毁时停止采样 */
    public void shutdown() {
        running = false;
        if (sampler != null) sampler.interrupt();
    }

    // ==================== JS 入口 ====================

    @android.webkit.JavascriptInterface
    public void call(final String method, final String argsJson, final int id) {
        pool.submit(() -> {
            Object result;
            boolean ok = true;
            try {
                result = dispatch(method, new JSONArray(argsJson));
            } catch (Throwable t) {
                ui.post(() -> respond(id, false, String.valueOf(t.getMessage() == null ? t : t.getMessage())));
                return;
            }
            final Object r = result;
            ui.post(() -> respond(id, ok, r));
        });
    }

    private Object dispatch(String method, JSONArray args) throws Exception {
        switch (method) {
            case "exec": {
                String cmd = args.optString(0, "");
                int timeout = args.optInt(1, 6);
                RootShell.Result r = RootShell.exec(cmd, timeout);
                JSONObject o = new JSONObject();
                o.put("code", r.code);
                o.put("out", r.out == null ? "" : r.out);
                o.put("err", r.err == null ? "" : r.err);
                o.put("ok", r.ok());
                return o;
            }
            case "readFile":
                return RootShell.readFile(args.optString(0, ""));
            case "writeFile": {
                RootShell.Result r = RootShell.writeFile(host.getCacheDir(),
                        args.optString(0, ""), args.optString(1, ""));
                JSONObject o = new JSONObject();
                o.put("ok", r.ok());
                o.put("err", r.err);
                return o;
            }
            case "writeFiles": {
                List<String> contents = new ArrayList<>();
                List<String> targets = new ArrayList<>();
                JSONArray arr = args.optJSONArray(0);
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONArray p = arr.optJSONArray(i);
                        if (p != null && p.length() >= 2) {
                            contents.add(p.optString(0));
                            targets.add(p.optString(1));
                        }
                    }
                }
                boolean okAll = RootShell.writeFiles(host.getCacheDir(),
                        contents.toArray(new String[0]), targets.toArray(new String[0]));
                JSONObject o = new JSONObject();
                o.put("ok", okAll);
                return o;
            }
            case "getprop":
                return RootShell.getprop(args.optString(0, ""));
            case "hasSuBinary":
                return RootShell.hasSuBinary();
            case "rootState": {
                JSONObject o = new JSONObject();
                o.put("rooted", rooted);
                o.put("checked", rootChecked);
                o.put("requesting", rootRequesting);
                return o;
            }
            case "requestRoot": {
                // 弹窗 + su 授权流程在主线程发起，结果经事件通知
                ui.post(this::startRootRequest);
                JSONObject o = new JSONObject();
                o.put("requesting", true);
                return o;
            }
            case "readBattery": {
                PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                if (st == null) return null;
                JSONObject o = new JSONObject();
                o.put("watts", st.watts);
                o.put("volts", st.volts);
                o.put("amps", st.amps);
                o.put("cells", st.cells);
                o.put("level", st.level);
                o.put("tempC", st.tempC);
                o.put("status", st.status == null ? "" : st.status);
                return o;
            }
            case "applyCellMode": {
                // 从 JSON 重建 BatteryStat，复用原生校准逻辑
                JSONObject s = args.optJSONObject(0);
                if (s == null) return 0.0;
                PowerMonitor.BatteryStat st = new PowerMonitor.BatteryStat();
                st.watts = s.optDouble("watts", 0);
                st.volts = s.optDouble("volts", 0);
                st.amps = s.optDouble("amps", 0);
                st.cells = s.optInt("cells", 1);
                st.level = s.optInt("level", -1);
                st.tempC = s.optDouble("tempC", 0);
                st.status = s.optString("status", "");
                return PowerMonitor.applyCellMode(st, args.optInt(1, 0));
            }
            case "getCellMode":
                return host.getSharedPreferences("colorfc", Context.MODE_PRIVATE).getInt("cellMode", 0);
            case "setCellMode":
                host.getSharedPreferences("colorfc", Context.MODE_PRIVATE)
                        .edit().putInt("cellMode", args.optInt(0, 0)).commit();
                return true;
            case "recentWatts": {
                float[] w = PowerHistoryManager.recentWatts(host, args.optInt(0, 180));
                JSONArray a = new JSONArray();
                if (w != null) for (float v : w) a.put((double) v);
                return a;
            }
            case "powerCsv": {
                File f = PowerHistoryManager.file(host);
                if (f == null || !f.exists()) return "";
                return readLocalFileThinned(f, CSV_MAX_ROWS);
            }
            case "clearPowerCsv":
                PowerHistoryManager.clear(host);
                return true;
            case "cpuSnapshot": {
                if (coreCountCache <= 0) coreCountCache = CpuCoreManager.coreCount();
                int n = coreCountCache;
                if (n <= 0) return null;
                CpuCoreManager.Snapshot sp = CpuCoreManager.snapshot(n);
                JSONObject o = new JSONObject();
                o.put("cores", sp.cores);
                JSONArray on = new JSONArray(), fq = new JSONArray(), bs = new JSONArray();
                for (int i = 0; i < sp.cores; i++) {
                    on.put(sp.online[i]);
                    fq.put(sp.freqMhz[i]);
                    bs.put(sp.busy != null ? (double) sp.busy[i] : 0.0);
                }
                o.put("online", on);
                o.put("freqMhz", fq);
                o.put("busy", bs);
                o.put("onlineCount", sp.onlineCount());
                return o;
            }
            case "cpuTopology": {
                List<int[]> t = CpuCoreManager.policyTopology();
                JSONArray a = new JSONArray();
                if (t != null) for (int[] p : t) a.put(new JSONArray(new int[]{p[0], p[1], p[2]}));
                return a;
            }
            case "setCoreOnline":
                return CpuCoreManager.setCoreOnline(args.optInt(0, 0), args.optBoolean(1, true));
            case "overlayStates": {
                SharedPreferences p = host.getSharedPreferences("colorfc", Context.MODE_PRIVATE);
                JSONArray a = new JSONArray();
                for (String k : MonitorService.PREF_KEYS) a.put(p.getBoolean(k, false));
                return a;
            }
            case "overlaySet": {
                int type = args.optInt(0, 0);
                boolean on = args.optBoolean(1, false);
                JSONObject o = new JSONObject();
                if (on && !android.provider.Settings.canDrawOverlays(host)) {
                    o.put("needPermission", true);
                    return o;
                }
                host.getSharedPreferences("colorfc", Context.MODE_PRIVATE)
                        .edit().putBoolean(MonitorService.PREF_KEYS[type], on).apply();
                if (on) MonitorService.show(host, type);
                else MonitorService.hide(host, type);
                o.put("ok", true);
                return o;
            }
            case "canDrawOverlays":
                return android.provider.Settings.canDrawOverlays(host);
            case "requestOverlayPermission":
                ui.post(() -> {
                    try {
                        host.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + host.getPackageName())));
                    } catch (Exception ignored) {
                    }
                });
                return true;
            case "themeGet": {
                JSONObject o = new JSONObject();
                o.put("dark", ThemeStore.dark(host));
                o.put("transparent", ThemeStore.transparentBg(host));
                o.put("imageBg", ThemeStore.imageBg(host));
                o.put("bgAlpha", ThemeStore.bgAlpha(host));
                o.put("bgScale", ThemeStore.bgScale(host));
                o.put("bgOffX", ThemeStore.bgOffX(host));
                o.put("bgOffY", ThemeStore.bgOffY(host));
                o.put("glass", ThemeStore.glassAlpha(host));
                o.put("liquid", ThemeStore.liquidGlass(host));
                o.put("hasImage", ThemeStore.bgFile(host).exists());
                return o;
            }
            case "themeSet":
                ui.post(() -> applyThemeSet(args.optString(0, ""), args.opt(1)));
                return true;
            case "bgImage":
                return bgDataUrl();
            case "pickImage":
                ui.post(() -> {
                    try {
                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        it.setType("image/*");
                        host.startActivityForResult(Intent.createChooser(it, "选择背景图片"), REQ_PICK);
                    } catch (Exception ignored) {
                    }
                });
                return true;
            case "appList": {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> all = new ArrayList<>(host.getPackageManager().queryIntentActivities(i, 0));
                Collections.sort(all, (a, b) -> String.valueOf(a.loadLabel(host.getPackageManager()))
                        .compareTo(String.valueOf(b.loadLabel(host.getPackageManager()))));
                JSONArray a = new JSONArray();
                for (ResolveInfo ri : all) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", ri.activityInfo.packageName);
                    o.put("label", String.valueOf(ri.loadLabel(host.getPackageManager())));
                    a.put(o);
                }
                return a;
            }
            case "appLimitEnsure":
                AppLimitService.ensure(host);
                return true;
            case "soc": {
                if (socCache == null) socCache = SocInfo.autoDetect();
                JSONObject o = new JSONObject();
                o.put("code", socCache.code);
                o.put("marketing", socCache.marketing);
                o.put("shortName", socCache.shortName);
                o.put("vendor", socCache.vendor);
                o.put("config", socCache.config);
                o.put("known", socCache.known);
                return o;
            }
            case "toast":
                ui.post(() -> Toast.makeText(host, args.optString(0, ""), Toast.LENGTH_SHORT).show());
                return true;
            default:
                throw new Exception("unknown method: " + method);
        }
    }

    // ==================== 回调与事件 ====================

    private void respond(int id, boolean ok, Object result) {
        String json;
        if (result == null) json = "null";
        else if (result instanceof Boolean || result instanceof Integer || result instanceof Double)
            json = String.valueOf(result);   // 原生 JSON 字面量
        else if (result instanceof JSONArray || result instanceof JSONObject) json = result.toString();
        else json = JSONObject.quote(String.valueOf(result));   // 字符串
        eval("window.__cfResult && window.__cfResult(" + id + "," + (ok ? "true" : "false") + "," + json + ")");
    }

    private void emitEvent(JSONObject ev) {
        eval("window.__cfEvent && window.__cfEvent(" + ev.toString() + ")");
    }

    private void eval(String js) {
        ui.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    private static JSONObject rootEvent() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "rootChanged");
            o.put("rooted", rooted);
            o.put("checked", rootChecked);
            o.put("requesting", rootRequesting);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Root 授权流程（照搬原生首启逻辑） ====================

    private void startRootRequest() {
        final SharedPreferences sp = host.getSharedPreferences("colorfc", Context.MODE_PRIVATE);
        if (!sp.getBoolean("rootReqDone", false)) {
            sp.edit().putBoolean("rootReqDone", true).apply();
            if (!RootShell.hasSuBinary()) {
                // 无 su 的设备：直接后台检测（必然无 ROOT）
                pool.submit(() -> {
                    rooted = false;
                    rootChecked = true;
                    emitEvent(rootEvent());
                });
                return;
            }
            rootRequesting = true;
            AlertDialog dlg = new AlertDialog.Builder(ThemeStore.dialogCtx(host))
                    .setTitle("申请 Root 授权")
                    .setMessage("实时功耗、模式切换、调度参数、应用策略等核心功能均需要 Root 权限。\n\n"
                            + "点击「立即授权」后，请在系统弹出的授权窗口中选择允许。")
                    .setPositiveButton("立即授权", (d, w) -> doRootRequest())
                    .setNegativeButton("暂不", (d, w) -> {
                        rootRequesting = false;
                        emitEvent(rootEvent());
                    })
                    .setCancelable(false)
                    .show();
            ThemeStore.styleDialog(host, dlg);
            return;
        }
        // 已走过首启流程：后台重新检测当前授权状态
        rootRequesting = true;
        emitEvent(rootEvent());
        doRootRequest();
    }

    private void doRootRequest() {
        pool.submit(() -> {
            RootShell.Result r = RootShell.exec("id", 60);
            final boolean ok = r.ok() && r.out != null && r.out.contains("uid=0");
            rooted = ok;
            rootChecked = true;
            rootRequesting = false;
            emitEvent(rootEvent());
            ui.post(() -> {
                if (ok) Toast.makeText(host, "Root 授权成功", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ==================== 主题写入（主线程） ====================

    private void applyThemeSet(String key, Object value) {
        boolean dark = value instanceof Boolean ? (Boolean) value
                : "true".equals(String.valueOf(value));
        int intV = value instanceof Number ? ((Number) value).intValue()
                : parseIntSafe(String.valueOf(value));
        switch (key) {
            case "dark":
                ThemeStore.setDark(host, dark);
                ThemeStore.invalidate();
                ThemeStore.applyTheme(host);
                ThemeStore.applyBackground(host);
                // 通知 Vue 层重读（图表配色等随 CSS 变量切换）
                eval("window.__cfEvent && window.__cfEvent({\"type\":\"themeChanged\"})");
                break;
            case "transparent": ThemeStore.setTransparent(host, dark); ThemeStore.applyBackground(host); break;
            case "imageBg": ThemeStore.setImageBg(host, dark); ThemeStore.applyBackground(host); break;
            case "bgAlpha": ThemeStore.setBgAlpha(host, intV); ThemeStore.applyBackground(host); break;
            case "bgScale": ThemeStore.setBgScale(host, intV); ThemeStore.applyBackground(host); break;
            case "bgOffX": ThemeStore.setBgOffX(host, intV); ThemeStore.applyBackground(host); break;
            case "bgOffY": ThemeStore.setBgOffY(host, intV); ThemeStore.applyBackground(host); break;
            case "glass": ThemeStore.setGlass(host, intV); ThemeStore.applyBackground(host); break;
            case "liquid": ThemeStore.setLiquid(host, dark); ThemeStore.applyBackground(host); break;
        }
    }

    // ==================== 图片选择回调（宿主 onActivityResult 转发） ====================

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) return;
        final Uri uri = data.getData();
        pool.submit(() -> {
            try {
                // 两遍解码：边界 → 采样缩放（≤1080 宽，省内存）→ JPEG 90 存入主题背景文件
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream is = host.getContentResolver().openInputStream(uri);
                if (is != null) {
                    BitmapFactory.decodeStream(is, null, o);
                    is.close();
                }
                int sample = 1;
                while (o.outWidth / sample > 1080) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                InputStream is2 = host.getContentResolver().openInputStream(uri);
                Bitmap bmp = is2 != null ? BitmapFactory.decodeStream(is2, null, o2) : null;
                if (is2 != null) is2.close();
                if (bmp == null) return;
                File f = ThemeStore.bgFile(host);
                java.io.OutputStream os = new java.io.FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.close();
                bmp.recycle();
                bgDataCache = null;   // 使 dataURL 缓存失效
                // 通知 Vue：背景图已更换（theme.js 监听后重拉 bgImage）
                eval("window.__cfEvent && window.__cfEvent({\"type\":\"bgChanged\"})");
            } catch (Exception ignored) {
            }
        });
    }

    /** 背景图 dataURL（预缩放 JPEG，带 mtime 缓存） */
    private String bgDataUrl() {
        try {
            File f = ThemeStore.bgFile(host);
            if (!f.exists()) return null;
            if (bgDataCache != null && bgDataStamp == f.lastModified()) return bgDataCache;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = f.length() > 1_500_000 ? 2 : 1;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            bmp.recycle();
            String url = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            bgDataCache = url;
            bgDataStamp = f.lastModified();
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLocalFile(File f) {
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读 CSV 并按行数等距抽稀（保留首尾行）：
     * 2s 采样下全量可达 26 万行（约 7MB），旧 WebView 传输大字符串易卡顿；
     * 抽到 ≤maxRows 后最粗粒度约 26万/1.2万≈22 秒/点，远小于会话断流阈值（15 分钟），
     * 会话分段/统计/曲线语义不受影响（曲线端本来就有 600 桶降采样）。
     */
    private static String readLocalFileThinned(File f, int maxRows) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            // 第一遍数行数
            int total = 0;
            while (r.readLine() != null) total++;
            r.close();
            int step = total > maxRows ? (int) Math.ceil(total / (double) maxRows) : 1;
            if (step == 1) return readLocalFile(f);
            r = new BufferedReader(new FileReader(f));
            String line;
            String lastLine = null;
            int i = 0;
            while ((line = r.readLine()) != null) {
                lastLine = line;
                if (i % step == 0) sb.append(line).append('\n');
                i++;
            }
            r.close();
            // 尾行兜底：保证最新采样一定带过去（末行未落在步进点上时补写）
            if (total > 0 && (total - 1) % step != 0 && lastLine != null) {
                sb.append(lastLine).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

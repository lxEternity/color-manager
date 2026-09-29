package Color.fc.stub;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

import dalvik.system.InMemoryDexClassLoader;

/**
 * 加固壳入口（爱加密式 DEX 加壳）。
 *
 * APK 内不携带任何业务代码：真实 classes.dex 以 AES-256-GCM 密文存放于
 * assets/cfc.dat，本壳在进程启动最早期（attachBaseContext）完成：
 *   1. 反调试检测（被 ptrace/IDE 附加即退出）
 *   2. APK 签名证书校验（防二次打包，篡改后无法解出真实代码）
 *   3. 解密真实 dex 到内存（不落盘，规避文件级 W^X 限制与 dump 残留）
 *   4. InMemoryDexClassLoader 加载 + 补丁 LoadedApp.mClassLoader——
 *      此后系统按名字实例化的一切组件（Activity/Service/Receiver）
 *      均解析到内存中的真实类，全部功能照常运转。
 *
 * 静态反编译（jadx/apktool）只能看到本壳，业务逻辑与字符串零暴露。
 */
public class StubApp extends Application {

    /** 加密真实 dex 的资产名 */
    private static final String DATA_NAME = "cfc.dat";

    /** 官方签名证书 SHA-256（与 v1.70 起历版一致），不匹配即判定被二次打包 */
    private static final String CERT_SHA256 =
            "f6e69b7cc4ffb32491f8bf6c770a859d8ca6c4f66f781c652eda8b160b8e0329";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            unsealHiddenApi();
        } catch (Throwable ignored) {
        }
        if (beingTraced()) { quit(); return; }
        if (!signatureOk()) { quit(); return; }
        byte[] dex = decryptPayload();
        if (dex == null || dex.length == 0) { quit(); return; }
        patchClassLoader(base, dex);
    }

    /** 反调试：IDE 调试连接 / ptrace 附加（Frida 等）时拒绝运行 */
    private static boolean beingTraced() {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    r.close();
                    return !line.substring(9).trim().equals("0");
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 签名校验：证书摘要与官方一致才放行。二次打包重签后此处必失败，
     *  即使攻击者绕过本方法，密文也未随新签名变化（密钥独立），解密照常失败 */
    private boolean signatureOk() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] ss = pi.signatures;
            if (ss == null || ss.length == 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(ss[0].toByteArray())) hex.append(String.format("%02x", b));
            return CERT_SHA256.equals(hex.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取 assets 密文并解密真实 dex；格式损坏/密钥不符/被篡改返回 null */
    private byte[] decryptPayload() {
        try (InputStream in = getAssets().open(DATA_NAME)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] all = bo.toByteArray();
            if (all.length < 4 + 12 + 16) return null;
            if (all[0] != 'C' || all[1] != 'F' || all[2] != 'C' || all[3] != '1') return null;
            byte[] iv = Arrays.copyOfRange(all, 4, 16);
            byte[] ct = Arrays.copyOfRange(all, 16, all.length);
            return KeyBox.unseal(iv, ct);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 加载真实 dex 并让组件解析指向它。双路径互为备份，任一成功即生效 */
    private static void patchClassLoader(Context base, byte[] dex) {
        try {
            ClassLoader parent = base.getClassLoader();
            InMemoryDexClassLoader loader =
                    new InMemoryDexClassLoader(ByteBuffer.wrap(dex), parent);
            if (!patchViaActivityThread(loader)) patchViaContext(base, loader);
        } catch (Throwable ignored) {
        }
    }

    /** 路径 1：ActivityThread.mBoundApplication.info(LoadedApk).mClassLoader */
    private static boolean patchViaActivityThread(InMemoryDexClassLoader loader) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            Field fBind = at.getDeclaredField("mBoundApplication");
            fBind.setAccessible(true);
            Object bind = fBind.get(thread);
            Field fInfo = bind.getClass().getDeclaredField("info");
            fInfo.setAccessible(true);
            Object apk = fInfo.get(bind);
            return setLoader(apk, loader);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 路径 2：ContextImpl.mPackageInfo(LoadedApk).mClassLoader（attachBaseContext
     *  的 base 即 ContextImpl，其 mPackageInfo 与进程 LoadedApk 为同一实例） */
    private static boolean patchViaContext(Context base, InMemoryDexClassLoader loader) {
        try {
            Field fPkg = base.getClass().getDeclaredField("mPackageInfo");
            fPkg.setAccessible(true);
            Object apk = fPkg.get(base);
            return setLoader(apk, loader);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setLoader(Object loadedApk, InMemoryDexClassLoader loader) {
        try {
            Field fCl = loadedApk.getClass().getDeclaredField("mClassLoader");
            fCl.setAccessible(true);
            fCl.set(loadedApk, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解除隐藏 API 反射限制（元反射技巧，失败不影响主流程：
     *  壳用到的字段均为 greylist，不解封也可访问） */
    private static void unsealHiddenApi() {
        try {
            Method getDeclaredMethod = Class.class
                    .getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method setExempt = (Method) getDeclaredMethod
                    .invoke(vm, "setHiddenApiExemptions", new Class[]{String[].class});
            Method getRuntime = (Method) getDeclaredMethod.invoke(vm, "getRuntime", new Class[0]);
            Object runtime = getRuntime.invoke(null);
            setExempt.invoke(runtime, (Object) new String[]{""});
        } catch (Throwable ignored) {
        }
    }

    private static void quit() {
        System.exit(0);
    }
}

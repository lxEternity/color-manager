package Color.fc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/**
 * Root 命令封装：读取/写入 /data/adb 下的模块脚本
 */
public class RootShell {

    public static class Result {
        public int code = -1;
        public String out = "";
        public String err = "";
        public boolean ok() { return code == 0; }
    }

    public static final String CONFIG_DIR = "/data/adb/modules/colorFC/config";
    public static final String GOV_DIR = "/data/adb/modules/colorFC/A";

    /** 执行 su -c 命令（带超时保护，防止授权弹窗/卡死阻塞） */
    public static Result exec(String cmd) {
        return exec(cmd, 6);
    }

    /** 执行 su -c 命令（自定义超时秒数） */
    public static Result exec(String cmd, int timeoutSec) {
        Result r = new Result();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                r.err = "timeout";
                return r;
            }
            r.out = readAll(p.getInputStream());
            r.err = readAll(p.getErrorStream());
            r.code = p.exitValue();
        } catch (Exception e) {
            r.err = String.valueOf(e);
            if (p != null) p.destroyForcibly();
        }
        return r;
    }

    private static String readAll(java.io.InputStream is) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 是否拥有 Root */
    public static boolean hasRoot() {
        Result r = exec("id");
        return r.ok() && r.out.contains("uid=0");
    }

    /** 读取 root 文件内容，失败返回 null */
    public static String readFile(String path) {
        Result r = exec("cat '" + path + "'");
        return r.ok() ? r.out : null;
    }

    /** 通过缓存临时文件把内容写入 root 目标文件 */
    public static Result writeFile(File cacheDir, String content, String target) {
        File tmp = null;
        try {
            tmp = new File(cacheDir, "colorfc_" + System.currentTimeMillis() + ".sh");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(content);
            w.flush();
            w.close();
            Result r = exec("cp '" + tmp.getAbsolutePath() + "' '" + target + "'"
                    + " && chmod 755 '" + target + "'");
            return r;
        } catch (Exception e) {
            Result r = new Result();
            r.err = String.valueOf(e);
            return r;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * 读取系统属性（多级回退，任一可用即返回）：
     * 1. 反射 SystemProperties（无需进程，最可靠）
     * 2. 直接执行 getprop（普通权限）
     * 3. 解析 build.prop
     * 4. su getprop
     */
    public static String getprop(String name) {
        // 1. 反射 SystemProperties
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, name, "");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        } catch (Throwable ignored) {
        }
        // 2. 直接执行 getprop
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", name});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Exception ignored) {
        }
        // 3. 解析 build.prop
        try {
            File[] props = {new File("/system/build.prop"), new File("/vendor/build.prop")};
            for (File f : props) {
                if (!f.canRead()) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(name + "=")) {
                        r.close();
                        return line.substring(name.length() + 1).trim();
                    }
                }
                r.close();
            }
        } catch (Exception ignored) {
        }
        // 4. su getprop
        Result r = exec("getprop " + name);
        return r.out == null ? "" : r.out.trim();
    }
}

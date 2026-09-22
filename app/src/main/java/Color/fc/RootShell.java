package Color.fc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

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

    /** 执行 su -c 命令 */
    public static Result exec(String cmd) {
        Result r = new Result();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader so = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader se = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder ob = new StringBuilder();
            StringBuilder eb = new StringBuilder();
            String line;
            while ((line = so.readLine()) != null) ob.append(line).append('\n');
            while ((line = se.readLine()) != null) eb.append(line).append('\n');
            r.out = ob.toString();
            r.err = eb.toString();
            r.code = p.waitFor();
        } catch (Exception e) {
            r.err = String.valueOf(e);
        }
        return r;
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

    /** 读取系统属性（优先无 root 读 build.prop，失败用 su getprop） */
    public static String getprop(String name) {
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
        Result r = exec("getprop " + name);
        return r.out == null ? "" : r.out.trim();
    }
}

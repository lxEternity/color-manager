package Color.fc;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * color.lax 配置导入导出（仅参数数值，非完整脚本代码）
 * 文件格式：每行 key=value，# 开头为注释
 * gov.* = 调速器参数段，sch.* = 调度参数段；两段共存，导出时互不覆盖
 */
public class LaxStore {

    /** 导出位置：Download 目录（用户可直接在文件管理器中看到） */
    public static final String PATH = "/sdcard/Download/color.lax";

    /** 解析 lax 内容（key=value 行），忽略注释与空行 */
    public static LinkedHashMap<String, String> parse(String content) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (content == null) return map;
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return map;
    }

    /** 读取 lax 文件（不存在/失败返回空 map） */
    public static LinkedHashMap<String, String> read() {
        return parse(RootShell.readFile(PATH));
    }

    /** 写入 lax：先剔除与 block 同前缀（首段）的旧键再合并，gov 段保持在最前 */
    public static RootShell.Result write(File cacheDir, LinkedHashMap<String, String> block) {
        LinkedHashMap<String, String> all = read();
        HashSet<String> prefixes = new HashSet<>();
        for (String k : block.keySet()) {
            int dot = k.indexOf('.');
            prefixes.add(dot > 0 ? k.substring(0, dot) : k);
        }
        all.keySet().removeIf(k -> {
            int dot = k.indexOf('.');
            return prefixes.contains(dot > 0 ? k.substring(0, dot) : k);
        });
        all.putAll(block);

        StringBuilder sb = new StringBuilder();
        sb.append("# ColorFC 配置 (color.lax)\n");
        sb.append("# 仅包含参数数值：gov.*=调速器参数，sch.*=调度参数\n");
        for (String k : all.keySet()) {
            if (k.startsWith("gov.")) sb.append(k).append('=').append(all.get(k)).append('\n');
        }
        for (String k : all.keySet()) {
            if (!k.startsWith("gov.")) sb.append(k).append('=').append(all.get(k)).append('\n');
        }
        return RootShell.writeFile(cacheDir, sb.toString(), PATH);
    }
}

package Color.fc;

import android.os.Build;

/**
 * SOC 型号检测与配置映射
 * 天玑9300/9400/9500全系列、骁龙8gen1/2/3、8Elite(第一代) → 配置A
 * 第五代8Elite (SM8850)、8s至尊版 (SM8845) → 配置B
 */
public class SocInfo {

    public final String platform;   // 原始 platform
    public final String code;       // 规范名，如 SM8750
    public final String marketing;  // 营销名
    public final String shortName;  // 短名（用于芯片图）
    public final String vendor;     // 厂商
    public final String config;     // "a" / "b"
    public final boolean known;     // 是否命中预设 SOC 列表

    private SocInfo(String platform, String code, String marketing, String shortName, String vendor, String config, boolean known) {
        this.platform = platform;
        this.code = code;
        this.marketing = marketing;
        this.shortName = shortName;
        this.vendor = vendor;
        this.config = config;
        this.known = known;
    }

    private static final String[][] MAP = {
            // platform, code, marketing, shortName, vendor, config
            {"sm8850", "SM8850", "骁龙 8 至尊版（第五代 8 Elite）", "8 Elite G5", "Qualcomm", "b"},
            {"sm8845", "SM8845", "骁龙 8s 至尊版（8s Elite）", "8s Elite", "Qualcomm", "b"},
            {"sm8750", "SM8750", "骁龙 8 至尊版（8 Elite）", "8 Elite", "Qualcomm", "a"},
            {"sm8650", "SM8650", "骁龙 8 Gen 3", "8 Gen 3", "Qualcomm", "a"},
            {"sm8550", "SM8550", "骁龙 8 Gen 2", "8 Gen 2", "Qualcomm", "a"},
            {"sm8450", "SM8450", "骁龙 8 Gen 1", "8 Gen 1", "Qualcomm", "a"},
            {"mt6995", "MT6995", "天玑 9500 系列", "天玑 9500", "MediaTek", "a"},
            {"mt6993", "MT6993", "天玑 9500 系列", "天玑 9500", "MediaTek", "a"},
            {"mt6991", "MT6991", "天玑 9400 系列", "天玑 9400", "MediaTek", "a"},
            {"mt6989", "MT6989", "天玑 9300 / 9400E 系列", "天玑 9300", "MediaTek", "a"},
    };

    /**
     * 自动检测：多属性 + Build 字段多级回退，命中即返回
     * 顺序：ro.board.platform → ro.soc.model → ro.mediatek.platform
     *       → Build.HARDWARE → Build.BOARD → Build.DEVICE
     */
    public static SocInfo autoDetect() {
        String[] keys = {"ro.board.platform", "ro.soc.model", "ro.mediatek.platform"};
        String first = "";
        for (String k : keys) {
            String v = RootShell.getprop(k);
            if (v == null || v.trim().isEmpty()) continue;
            v = v.trim();
            if (first.isEmpty()) first = v;
            SocInfo s = detect(v);
            if (s.known) return s;
        }
        // Build 字段兜底（无需任何系统调用；MTK 的 HARDWARE 通常就是 platform）
        String[] fields = {Build.HARDWARE, Build.BOARD, Build.DEVICE};
        for (String f : fields) {
            if (f == null || f.trim().isEmpty()) continue;
            SocInfo s = detect(f.trim());
            if (s.known) return s;
        }
        // 全部未命中：显示检测到的第一个值，未知平台默认配置A
        return detect(first);
    }

    /** 根据 platform 字符串匹配 SOC 信息，未知平台默认加载配置A */
    public static SocInfo detect(String platform) {
        if (platform == null) platform = "";
        platform = platform.trim().toLowerCase();
        for (String[] row : MAP) {
            if (row[0].equals(platform)) {
                return new SocInfo(row[0], row[1], row[2], row[3], row[4], row[5], true);
            }
        }
        String up = platform.isEmpty() ? "未知" : platform.toUpperCase();
        String marketing = platform.isEmpty() ? "检测错误" : "检测错误（" + up + "）";
        return new SocInfo(platform, up, marketing, up.length() <= 8 ? up : up.substring(0, 8), "--", "a", false);
    }
}

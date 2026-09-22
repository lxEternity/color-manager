package Color.fc;

/**
 * SOC 型号检测与配置映射
 * 天玑9300/9400/9500全系列、骁龙8gen1/2/3、8Elite(第一代) → 配置A
 * 第五代8Elite (SM8850) → 配置B
 */
public class SocInfo {

    public final String platform;   // 原始 platform
    public final String code;       // 规范名，如 SM8750
    public final String marketing;  // 营销名
    public final String shortName;  // 短名（用于芯片图）
    public final String vendor;     // 厂商
    public final String config;     // "a" / "b"

    private SocInfo(String platform, String code, String marketing, String shortName, String vendor, String config) {
        this.platform = platform;
        this.code = code;
        this.marketing = marketing;
        this.shortName = shortName;
        this.vendor = vendor;
        this.config = config;
    }

    private static final String[][] MAP = {
            // platform, code, marketing, shortName, vendor, config
            {"sm8850", "SM8850", "骁龙 8 至尊版（第五代 8 Elite）", "8 Elite G5", "Qualcomm", "b"},
            {"sm8750", "SM8750", "骁龙 8 至尊版（8 Elite）", "8 Elite", "Qualcomm", "a"},
            {"sm8650", "SM8650", "骁龙 8 Gen 3", "8 Gen 3", "Qualcomm", "a"},
            {"sm8550", "SM8550", "骁龙 8 Gen 2", "8 Gen 2", "Qualcomm", "a"},
            {"sm8450", "SM8450", "骁龙 8 Gen 1", "8 Gen 1", "Qualcomm", "a"},
            {"mt6995", "MT6995", "天玑 9500 系列", "天玑 9500", "MediaTek", "a"},
            {"mt6993", "MT6993", "天玑 9500 系列", "天玑 9500", "MediaTek", "a"},
            {"mt6991", "MT6991", "天玑 9400 系列", "天玑 9400", "MediaTek", "a"},
            {"mt6989", "MT6989", "天玑 9300 系列", "天玑 9300", "MediaTek", "a"},
    };

    /** 根据 ro.board.platform 检测 SOC 信息，未知平台默认加载配置A */
    public static SocInfo detect(String platform) {
        if (platform == null) platform = "";
        platform = platform.trim().toLowerCase();
        for (String[] row : MAP) {
            if (row[0].equals(platform)) {
                return new SocInfo(row[0], row[1], row[2], row[3], row[4], row[5]);
            }
        }
        String up = platform.toUpperCase();
        String marketing = platform.isEmpty() ? "未知平台" : "未知平台（" + up + "）";
        return new SocInfo(platform, up, marketing, up.length() <= 8 ? up : up.substring(0, 8), "--", "a");
    }
}

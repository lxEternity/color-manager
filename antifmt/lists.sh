#!/system/bin/sh
# ============================================================
# 受保护分区清单（基础名；_a/_b 插槽后缀自动匹配）
# 设备上不存在的名字自动忽略，一份清单覆盖高通/联发科/动态分区
# ============================================================

# ---- 引导链（写入即变砖）----
PROTECT_DEFAULT="boot init_boot vendor_boot recovery dtbo
vbmeta vbmeta_system vbmeta_vendor vbmeta_odm vbmeta_product
preloader preloader_backup lk bootloader abl xbl xbl_config
tz aop hyp shrm cpucp rpm imagefv multiimgoem multiimgq6 qupfw devcfg"

# ---- 系统分区（含动态分区 mapper 条目）----
PROTECT_DEFAULT="$PROTECT_DEFAULT super system vendor product system_ext odm oem"

# ---- 基带/固件镜像（运行期只读）----
PROTECT_DEFAULT="$PROTECT_DEFAULT modem md1img md1dsp md3img
spmfw scp sspm mcupm cam_vpu1 cam_vpu2 cam_vpu3 gpud audio_dsp dpm
mddb mdcfg splash logo"

# ---- 密钥/校准/身份分区（丢失=硬砖：IMEI/WiFi/BT/DRM 密钥）----
PROTECT_DEFAULT="$PROTECT_DEFAULT persist nvram keystore teecfg tee
keymaster modemst1 modemst2 fsg fsc otp seccfg proinfo ocdt"

# ---- 永不保护的分区（运行时需要写入，锁定会立刻破坏系统/开机）----
# data/userdata: 用户数据(系统持续写入)  metadata: 文件加密元数据(锁=无法开机)
# misc: OTA/Recovery 引导命令(BCB)      nvdata: MTK NVRAM 实时存储
# expdb/oops: 异常日志运行时写入        frp/para/boot_para: 系统流程写入
# cache: OTA 下载缓存                   rescue/ramdump/log*: 日志转储
SKIP_DEFAULT="data userdata metadata misc nvdata expdb oops log logfs frp cache
para boot_para rescue ramdump pimlog mobile_log"

# 归一化空白：多行字符串中行首词前是换行而非空格，case 空格匹配会漏词
PROTECT_DEFAULT=$(echo $PROTECT_DEFAULT)
SKIP_DEFAULT=$(echo $SKIP_DEFAULT)

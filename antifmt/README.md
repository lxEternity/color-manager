# 防格机·全分区守护 (antifmt)

内核级全分区防写保护，防止误格机 / 恶意格机导致的**变砖与密钥丢失**。

## 原理

对受保护分区的块设备下发内核 `BLKROSET` 只读锁（`blockdev --setro`）。
加锁后任何进程（含 root）对设备**打开写入、格式化、擦除一律返回 EROFS**：
`dd` 写分区、`mkfs` 格式化、`blkid` 擦除、病毒格机脚本全部失败。

- 在 `late_start` 阶段（系统完全启动后）加锁，只限制**运行期写入**，不影响任何读取——**不触碰引导链，加锁失败也只写日志，绝不可能导致无法开机**
- 只读锁不跨重启：重启后由本模块重新加锁；卸载模块并重启即彻底解除
- fastboot / 高通 9008 线刷发生在系统启动前，**不受任何影响**（天然救砖通道）

## 保护范围（按类别）

| 类别 | 分区（_a/_b 插槽自动匹配） |
|---|---|
| 引导链 | boot、init_boot、vendor_boot、recovery、dtbo、vbmeta*、preloader、lk、abl、xbl*、tz、aop、hyp 等 |
| 系统分区 | super、system、vendor、product、system_ext、odm、oem（动态分区走 /dev/block/mapper） |
| 基带/固件 | modem、md1img、md3img、spmfw、scp、sspm、mcupm、cam_vpu*、gpud、audio_dsp、mddb、logo、splash 等 |
| 密钥/校准 | **persist、nvram、keystore、ocdt**、teecfg、keymaster、modemst1/2、fsg、fsc、otp、seccfg、proinfo |

设备上不存在的名字自动忽略；一份清单覆盖高通 / 联发科 / 动态分区。
模块介绍（Magisk 模块页 description）实时显示**本次已保护分区数量**。

## 有意不保护的分区（保护它们 = 立刻无法开机/系统崩溃）

`data`、`userdata`（系统持续写入）、`metadata`（文件加密元数据）、
`misc`（OTA/Recovery 引导命令）、`nvdata`（MTK NVRAM 实时存储）、
`expdb`/`oops`/`log`（运行时异常日志）、`frp`/`para`/`cache`（系统流程写入）。
`oplusreserve` 等不确定运行时行为的分区默认未收录，可自行追加（见下）。

## 配置

编辑 `/data/adb/antifmt/config`（首次启动自动生成），保存后在 Magisk
模块页点「执行」立即生效，或重启生效：

```sh
ENABLED=0        # 关闭保护（OTA/刷机/Magisk 升级前必做，重启后清除全部锁）
WATCHDOG=1       # 看门狗：周期自动重锁，封堵 setrw 后抢写
INTERVAL=5       # 重锁间隔（秒）
EXTRA_PROTECT="oplusreserve oplusstanvbk"  # 追加保护
EXTRA_SKIP="logo"                          # 追加放行（优先级最高）
```

## 查看状态

- 已保护分区明细：`/data/adb/antifmt/protected.list`
- 完整日志（含跳过原因）：`/data/adb/antifmt/antifmt.log`
- Magisk 模块页 description 显示保护数量；OTA 合并期间自动暂缓并提示

## 重要提醒

1. **OTA / 刷入 Magisk / 任何写分区操作前**：config 设 `ENABLED=0` 并重启，
   操作完成后再改回 `1` 重启（模块检测到 OTA 快照合并时也会自动暂缓一次）
2. **fastboot 刷机不受影响**，无需关闭即可线刷救砖
3. 诚实说明局限：纯用户态方案无法对抗「root 攻击者先 setrw 再抢写」的定向
   竞争（看门狗把窗口压缩到秒级）；对误操作、格机脚本、病毒式格机是可靠防护

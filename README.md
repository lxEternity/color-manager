# ColorFC 模块（加固版发布仓库）

本仓库仅发布**加固版** Magisk 模块，不包含源码。源码保存在私有仓库。

## 下载

到 [Releases](https://github.com/lxEternity/color-manager/releases) 页获取最新加固版 zip：

- **v1.4.1.2**：https://github.com/lxEternity/color-manager/releases/download/v1.4.1.2/ColorFC_Module_v1.4.1.2.zip

校验（SHA-256）：`83fb7393fe7d2d0dd5750d7d2aa1eb129ab8d58f25297a797d1159b3cde2d73f`

> 本版重新打包修复：改包后完整性校验不符（qqverify 清单与文件哈希已重新绑定，解包实测 PKG_OK）；脚本补齐 shebang 并以 `sh` 前缀启动守护（防执行位丢失）；包内中文文件名编码损坏已修复。此前各版修复（进游戏切换、待机功耗、启动健壮性）均已包含，请重新下载。

## 说明

- 模块内置完整性校验（qqverify），被篡改或二次打包会自动禁用
- 版本更新只通过本仓库 Releases 发布

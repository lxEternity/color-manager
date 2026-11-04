# ColorFC 模块（加固版发布仓库）

本仓库仅发布**加固版** Magisk 模块，不包含源码。源码保存在私有仓库。

## 下载

到 [Releases](https://github.com/lxEternity/color-manager/releases) 页获取最新加固版 zip：

- **v1.4.2.1**：https://github.com/lxEternity/color-manager/releases/download/v1.4.2.1/ColorFC_Module_v1.4.2.1.zip

校验（SHA-256）：`7c977ce85adbd9b590a8a573e6b3428252e6eaa9e2f1ac4d3d97182df15bdebd`

> 本版修复：外部接口 /data/powercfg.sh 丢失参数导致 Scene/轻调度 APP 无法切换模式；外部裸调用不再误触 stop；WebUI 改走模块内接口并在失败时显示原因；切换锁自愈；powercfg.json 更名并随版本同步。已在本地全链路测试通过（46 项）。

## 说明

- 模块内置完整性校验（qqverify），被篡改或二次打包会自动禁用
- 版本更新只通过本仓库 Releases 发布

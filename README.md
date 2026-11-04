# ColorFC 模块（加固版发布仓库）

本仓库仅发布**加固版** Magisk 模块，不包含源码。源码保存在私有仓库。

## 下载

到 [Releases](https://github.com/lxEternity/color-manager/releases) 页获取最新加固版 zip：

- **v1.4.2**：https://github.com/lxEternity/color-manager/releases/download/v1.4.2/ColorFC_Module_v1.4.2.zip

校验（SHA-256）：`2466648e2065d4a326dacf6f6a8340f2286b18035ba213ed677859d352cd19e0`

> 本版更新：模式切换统一走模块内接口并恢复旧版切换语义（修复动态切换/模式快切失败与锁定均衡）；主页当前模式即时同步；快切失败提示具体原因；Color调度页新增「切到自适应」「切到OPGCE」并排入口（移除风驰增强独立卡片）；修复 qqverify 在 ARM64 下 TLS 对齐导致的完整性校验崩溃；风驰增强内核为 OPGCE v3.1.7。包内文件见 Updatelog.md。

## 说明

- 模块内置完整性校验（qqverify），被篡改或二次打包会自动禁用
- 版本更新只通过本仓库 Releases 发布

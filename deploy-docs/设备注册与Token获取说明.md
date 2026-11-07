# 设备注册与 Token 获取 · 端侧说明

> 面向：端侧固件开发 / 测试（ODM）
> 日期：2026-11-07
> 入口：运营后台「设备管理」页签
> 协议依据：《[端云通信协议-唯一基线](../docs/baseline/端云通信协议-唯一基线.md)》§2.1（连接与鉴权）

---

## 1. 为什么需要注册

云端鉴权机制：设备连 WSS 时，握手携带 `device_id + token`，云端按 `device_id` 查设备表、比对 `token`。**设备未注册（表里没有该 `device_id`）或 `token` 不匹配，握手直接 403 拒绝**。

所以每台真机在切换到**真实 `device_id`** 联调前，必须先注册一次、拿到它自己的 token。之前联调用的固定 `demo-device-001` / `demo-token-001` 换掉 device_id 后连不上，就是这个原因。

---

## 2. 入口与环境

| 项 | 值 |
|---|---|
| 注册后台 | `https://aicard.seassoon.net:58081/admin.html` → 顶部「**设备管理**」页签 |
| 设备联调（WSS） | `wss://aicard.seassoon.net:58083/ws` |

---

## 3. 操作流程

1. 打开后台「设备管理」页签。
2. 在「注册设备」区填写：

   | 字段 | 必填 | 说明 |
   |---|---|---|
   | 设备ID | ✅ | 设备真实 `device_id`（1–64 字符） |
   | 员工语言 | — | 如 `ja-JP` / `zh-CN` / `en-US`；影响问答/翻译方向 |
   | 员工姓名 | — | 展示用 |
   | 员工工号 | — | 展示用 |

3. 点「**注册并生成 Token**」。
4. 页面显示该设备的 **Token（仅本次显示，请立即保存）** 与完整握手地址，可一键复制。
5. 复制 Token，端侧用它连 WSS。

---

## 4. 握手地址

```
wss://aicard.seassoon.net:58083/ws?device_id=<device_id>&token=<token>&fw_version=<固件版本>&proto_ver=1
```

| 参数 | 说明 |
|---|---|
| `device_id` | 刚注册的设备 ID |
| `token` | 刚注册返回的 token |
| `fw_version` | 端侧主固件版本（P4）；**握手不校验**，可先填占位如 `0.0.1` |
| `proto_ver` | 固定 `1` |

---

## 5. 关键规则

| 规则 | 说明 |
|---|---|
| token 仅显示一次 | 注册返回后不再可查；设备列表接口**不回显 token**（安全考虑） |
| device_id 唯一 | 同一 `device_id` 不能重复注册，重复会报错 |
| token 丢失 | 需重新注册（换一个 `device_id`），或联系云端重置旧设备的 token |
| 鉴权只看 device_id + token | `fw_version` 握手不校验 |

---

## 6. 注册后验证

注册成功后，端侧用返回的 `device_id + token` 连 WSS，握手应成功（HTTP 101）。若握手 403，检查 device_id 是否已注册、token 是否抄对。

---

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 握手 403 | `device_id` 未注册，或 `token` 不匹配（换真实 device_id 后仍用旧 demo token） |
| 重复注册同 `device_id` 报错 | `device_id` 唯一；如需换 token，换一个 `device_id` 或联系云端重置 |
| token 丢了 | 重新注册一个新 `device_id`，或联系云端重置旧设备的 token |
| 注册后连 WSS 方向/语言不对 | 检查注册时填的「员工语言」是否与设备实际 `staff_language` 一致 |

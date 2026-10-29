# 端侧回复：`product_model` / `hardware_version` 填什么

> 面向：ODM / 端侧固件开发
> 日期：2026-10-30
> 适用接口：`POST /api/v1/ota/check`（OTA 检查更新）
> 协议依据：《[端云通信协议-唯一基线](../docs/baseline/端云通信协议-唯一基线.md)》§3.2、§4

---

## 1. 一句话结论

这两个字段**只用于 OTA 检查更新**，不是会议录音接口的字段。

当前联调建议值：

| 字段 | 建议值 | 说明 |
|---|---|---|
| `product_model` | `AIB-01` | AI Badge 第一代；**待硬件负责人确认后冻结** |
| `hardware_version` | `EVT1.0` | 当前 EVT 样机 |
| `hardware_version`（开发板） | `DEV-1.0` | 开发板必须与正式板区分 |

> 一旦冻结，值必须**逐字节稳定**：大小写、空格、连字符都不能变。云端比对用的是字符串严格相等。

---

## 2. 协议怎么定义

| 字段 | 类型 / 约束 | 含义 |
|---|---|---|
| `product_model` | string，1–32 | 产品型号，须与设备档案一致 |
| `hardware_version` | string，1–32 | 板卡硬件版本；**开发板与正式板必须区分** |

两个字段在 `POST /api/v1/ota/check` 请求中**均为必填**。

> 注意区分：它们不是固件版本。P4 / C5 的固件版本在 `current_versions.p4` / `current_versions.c5` 里，不要混用。

---

## 3. 推荐命名规则

产品型号只标识「产品形态 + 代次」，换代才改；硬件版本按硬件阶段递增：

| 阶段 | `product_model` | `hardware_version` | 说明 |
|---|---|---|---|
| 开发板 | `AIB-01` | `DEV-1.0` | 与正式板区分 |
| EVT 样机 | `AIB-01` | `EVT1.0` | 当前阶段 |
| DVT | `AIB-01` | `DVT1.0` | 设计验证 |
| PVT | `AIB-01` | `PVT1.0` | 小批量试产 |
| 量产 | `AIB-01` | `MP1.0` | 正式出货 |

约束：

- 全大写、无空格、≤32 字符；
- 同一块板子的值在固件里写成常量，不要运行时拼随机值/时间戳；
- 如果 ODM 已有自己的型号编码，也可以沿用，但必须提前告知云端并在设备档案里保持一致。

---

## 4. 请求示例

```http
POST /api/v1/ota/check
Authorization: Bearer <device_token>
Content-Type: application/json
```

```json
{
  "device_id": "demo-device-001",
  "product_model": "AIB-01",
  "hardware_version": "EVT1.0",
  "current_versions": {
    "p4": "1.0.0",
    "c5": "1.0.0"
  },
  "trigger": "boot"
}
```

---

## 5. 云端当前行为（联调期必读）

1. **当前设备档案里这两个字段尚未录入**（后台创建设备时没有写入入口），因此云端目前只在「档案已有值」时才做比对；档案为空时，端侧传任意非空值都会通过。
2. 但协议要求这两个字段必填，云端后续会补：
   - 后台登记设备时录入型号/硬件版本；
   - `ota/check` 请求非空校验；
   - 与设备档案严格相等比对。
3. 所以**不要依赖当前「不校验」的宽松状态**去传临时值。一旦云端补齐档案，值不一致会直接返回：

```json
{"error_code":"INVALID_ARGUMENT","message":"product_model mismatch"}
```

或

```json
{"error_code":"INVALID_ARGUMENT","message":"hardware_version mismatch"}
```

现在就把值定死，后面不用再改。

---

## 6. 需要端侧 / 硬件确认的事项

- 正式产品型号是否采用 `AIB-01`（还是 ODM 自有型号编码）；
- 板卡阶段命名是否采用 `EVT / DVT / PVT / MP` + 版本号；
- 开发板与正式板分别用什么值；
- 是否存在多块板卡 / 多个产品型号共存；
- 首个支持 OTA 的固件版本（与这两个字段无关，但建议一并给出）。

确认后把最终值写进《端云通信协议-唯一基线》§3.2 的示例，并同步到设备档案。

---

## 7. 可直接转给端侧开发的一句话

> `product_model` 和 `hardware_version` 是 OTA 检查更新（`POST /api/v1/ota/check`）的必填字段，不是会议录音接口的字段。联调期先按 `product_model=AIB-01`、`hardware_version=EVT1.0`（开发板用 `DEV-1.0`）填，写死在固件常量里，不要运行时变化。最终型号名以硬件负责人确认为准；云端后续会把这些值与设备档案做严格比对，届时不一致会返回 400 `INVALID_ARGUMENT`。

---

## 8. 云端待办（内部）

- [ ] 后台设备创建/编辑接口支持录入 `product_model` / `hardware_version`；
- [ ] `POST /api/v1/ota/check` 增加两字段非空校验（与协议「均必填」对齐）；
- [ ] 型号命名冻结后更新协议 §3.2 示例与设备档案；
- [ ] 联调设备档案回填 `AIB-01` / `EVT1.0`。

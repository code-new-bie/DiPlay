# DiLink 4.0 / Android 10 适配与实车验证

本分支：`feat/dilink4-carplay-continuity-and-vehicle-data`。
基于维护版 `ef8610b`，选择移植上游 `45fefe43` 中的窗口、定位、会话保活和车辆数据协议功能。
目标车辆是宋 Plus DM-i、DiLink 4.0、Android 10（API 29）。车辆 SDK 依据用户提供的固件 `UpdateFull2605271.1_0.zip` 中的 `system/framework/framework.jar` 核对。

## 功能与边界

| 功能 | 实现 | 验证关注点与支持边界 |
| --- | --- | --- |
| 倒车／360 窗口连续性 | 保留启动时协商的画布，临时缩小窗口时按比例显示，触摸坐标同步映射 | 系统是否只是改变窗口；若系统结束进程或断开链路，本功能无法保持连接 |
| 无线定位连续性 | 保存蓝牙 iAP2 的定位请求，Wi-Fi iAP2 接续请求，限制每秒一次发送 | 手机是否请求定位信息，车机 GPS 是否提供有效位置 |
| 已出画面的无线会话保活 | 当前会话已渲染视频时，缺少 iAP2 隧道不再触发交接超时清理 | 真实的 AirPlay 会话结束仍走原重连流程；旧会话的首帧不能保活新会话 |
| 车速／挡位辅助隧道导航 | 手机请求 selector 4 时发送 `$PASCD`，无 GPS 坐标时也可发送车速 | 车速和挡位读取是否正确，iPhone 是否接受并使用数据；不保证所有导航 App 支持 |
| 电量／纯电续航 | 有有效电池读数才声明车辆状态组件，按手机订阅发送 `0xA101` | Apple Maps 是否接受并展示；本次沿用上游电动车配置，DM-i 的混合动力路线规划未验证 |

真正的屏幕旋转、系统栏设置变化会重新协商。若连接建立时就处于较小的倒车窗口，恢复全屏超过启动窗口尺寸时，也需要重新协商，以恢复完整画布。

## 深浅模式同步

启动时读取应用进程的实时 Android 配置，连接期间继续读取比亚迪日夜设置；车辆设置有效时优先采用车辆值，否则回退到 Android 配置。
会话建立或恢复后台会话时立即发送当前主题，之后在第 2 秒、第 5 秒分别补发一次。补发使用当时的最新主题，不固定为连接开始时的值。
断开、重建连接和页面销毁会取消旧任务；指令通过控制器当前会话发送。事件通道尚未就绪时，AirPlay 会话保留最新主题并在通道建立后补发。

此行为参考 `feat/upstream-0.2.8-maintained` 在 `cbbdc96` 撤回前的延迟补发机制，以及该分支的实时配置读取和后台会话转发实现。
诊断日志记录主题来源、请求值、事件通道是否就绪，以及手机返回的事件响应。指令已发送和手机返回成功响应均不等同于已确认画面的实际主题。

本次主题同步修改通过 25 项回归测试，并在共享 Android 10 模拟器上通过 ADB 验证 `uiNight=16 → 32 → 16`、`resolved=light → dark → light`。
测试包为 `DiPlay-0.2.0-release-20261002-173251-appearance-startup-sync.apk`；真实 CarPlay 画面的启动补发效果仍需使用此包确认。

## MFi 认证方式选择

`build-diplay.ps1 release` 不包含离线认证资产；`build-diplay.ps1 debug` 从本地 `auth-assets` 目录带入资产。
Gradle 只将 `DIPLAY_AUTH_ASSETS_DIR` 挂载到 debug 源集，即使 release 构建时保留该环境变量也不会导入。
两个设置入口仅在 APK 内的 `identity.pk8`、`certificate.p7b` 均存在且非空时显示本地离线。
新安装有资产时默认本地离线，否则默认 USB/CH341。覆盖安装保存了本地离线、而新 APK 无资产时，
选择迁移为 USB/CH341；USB、I2C、远程的已有选择保留。应用目录里曾经提取的旧证书不会让选项重新出现。

认证方式按用户保存的选择执行，退出应用和进程重新启动不会重置为本地离线。
只有选择本地离线时才准备并读取离线认证资料；已有离线证书不会覆盖 USB/CH341、I2C 或远程选择。
USB/CH341 模式在未插认证设备时等待设备，不会自动使用离线资料继续连接。
修改认证方式在下一次连接时生效，已有会话需要断开重连。

认证设备的 USB 连接与手机的 CarPlay 传输方式分别处理：插入 CH341 不会把无线 CarPlay 切换成有线。

## 自定义车辆身份

入口：**设置 → 车载名称**。可分别设置车载名称、厂商、型号、附件序列号和网络主机名。
厂商与型号同时用于 iAP2 身份、AirPlay 信息；型号也用于 Bonjour 广播。
附件序列号用于 iAP2 身份。MFi 证书序列号由所选认证设备或认证资料提供。
网络主机名用于无线连接的 Bonjour 广播，只接受 1–63 个英文字母、数字或连字符，首尾为字母或数字。

序列号留空时使用原有身份生成的稳定序列号，主机名留空时沿用 Bonjour 自动生成方式。
厂商和型号留空时恢复默认的 `DiPlay`。保存后需断开并重新连接 CarPlay；本地与 USB 认证均读取同一套设置。

## 诊断报告

报告分别显示已保存的 MFi 认证设置和当前会话的认证目标；连接中修改设置后，两者可能不同。
无会话时标记为 `none`。是否完成认证以带时间戳的 backend、ready 和 authentication-succeeded 记录为准。

车辆名称诊断保留 `CarPlay identity`、`iap2 identification sent`、`airplay /info identity`，
可比较会话创建、实际 iAP2 发送以及 AirPlay 信息中的名称、厂商、型号和返回名称。
手机名称、凭据、证书、协议内容仍按原规则过滤。修复前已经被过滤的记录无法从旧报告中恢复，
安装修复版本后需重新连接并导出报告。

## 设置和 ADB

入口：**设置 → 比亚迪设置**，检测条件仍为车机安装了 `com.byd.bydlogtool`。

1. 启用“使用车机定位”，按提示授予定位权限。
2. 点击“检查并授权 ADB”，在车机调试授权弹窗中允许 DiPlay。
3. 按需启用“车速和挡位辅助隧道导航”“向 Apple Maps 发送电量和纯电续航（实验）”。
4. 若电量／续航检测无效，并且输出中剩余能量为 `-`、`0` 或无效值，请填写实际车型的动力电池容量（kWh），再检查。
   充电接口默认只声明 GB/T 交流；实际车型支持直流充电时，再开启“车辆支持 GB/T 直流充电”。
5. 断开并重新连接 CarPlay，使识别配置生效。连接建立时电池读数尚未准备好，会记录 `no battery reading` 并跳过电动车声明；读数有效后可重新连接。

车辆数据开关默认关闭，动力电池容量默认为 0（自动）。不预设年款或电池容量。
DM-i 只读取纯电续航，燃油续航不进入上报。手动容量用于 `容量 × 电量百分比` 估算剩余能量；自动模式根据 SDK 剩余能量及 SOC 估计满电容量。两者均为估算值。

本地 ADB 客户端连接 `127.0.0.1:5555`。**只有 USB ADB 可用，并不代表网络 ADB 已开启**。
如果车机通过 USB 连接电脑，可在电脑确认目标设备后执行：

```powershell
adb devices -l
adb -s <车机序列号> tcpip 5555
```

使用本机 Android SDK 的 `adb.exe`。若固件禁止 TCP 调试，需要通过车机支持的方式开启；DiPlay 的后台读取不会弹出授权提示。

## Android 10 SDK 读取

| 数据 | 固件 SDK 接口 | 处理 |
| --- | --- | --- |
| 车速 | `speed.BYDAutoSpeedDevice.getCurrentSpeed()` | km/h 转 m/s，过滤非有限值和超出 0–300 的值 |
| 挡位 | `gearbox.BYDAutoGearboxDevice.getGearboxAutoModeType()` | 固件常量 1=P、2=R、3=N、4=D、5=M、6=S；M/S 按前进方向传输 |
| SOC | `statistic.BYDAutoStatisticDevice.getElecPercentageValue()` | 百分比，接受 0–100 |
| 纯电续航 | `statistic.BYDAutoStatisticDevice.getElecDrivingRangeValue()` | km，过滤固件无效／默认值 1000、1023 |
| 剩余电量 | `power.BYDAutoPowerDevice.getBatteryRemainPowerEV()` | kWh；DM-i 可能不支持，使用手动容量兜底 |
| 充电状态 | `charging.BYDAutoChargingDevice.getBatteryManagementDeviceState()` | 固件状态 1 表示充电；不可用时省略状态字段 |

Android 10 固件的部分车辆数据编号与上游 DiLink 5.0 不同。因此使用固件自身 SDK Getter，未复制另一套 ROM 的原始 Binder 数据编号。
SDK 桥接通过 ADB 的 `app_process` 运行，只调用读取接口，不调用车辆控制 Setter。

车速以 4 Hz 采样、挡位以 1 Hz 采样，电池每 30 秒采样。每类数据使用一个持续运行的读取进程，最多运行 5 分钟后重建。
所有读取在独立线程中进行，iAP2 和音频线程只取缓存；数据失败时跳过上报，读数过期后不再发送。会话结束关闭读取连接，停止定位和车辆数据订阅。

为保持倒车弹窗覆盖期间的 GPS 访问，定位开启且已获得权限时，会话前台服务启用 `location` 类型。
依据 [Android 前台定位要求](https://developer.android.com/develop/sensors-and-location/location/permissions) 和 [前台服务类型要求](https://developer.android.com/develop/background-work/services/fgs/service-types#location)。

## 实车验证

2026-10-02，用户完成 `DiPlay-0.2.0-release-20261002-124438-dilink4-vehicle-data.apk` 的实车测试，反馈功能未发现明显问题，并确认合并到 `main`。
测试反馈针对本包整体功能，未提供各项协议消息和车辆读数的诊断日志。其他车型或后续回归可按以下场景验证并导出日志：

- 连续进入／退出倒车和 360，音乐、画面和手机连接状态是否连续；日志应出现 `keeping CarPlay session canvas=`。
- 启用定位后，无线连接完成是否出现 `location request continues from the Bluetooth link`、`tx=0xfffb`。
- 检查 ADB 结果中的车速、挡位、SOC、纯电续航是否与仪表一致。隧道测试需要手机请求 `components` 包含 4，日志应出现 `wheel speed first sample=`。
- 开启电量上报后重新连接，查看 `rx=0xa100`、`tx=0xa101`，再检查 Apple Maps。没有 `0xa100` 表示手机没有订阅车辆状态。
- ADB 关闭／授权取消后，确认 CarPlay 的画面和音乐仍可用。断开 CarPlay 后，确认读取进程和定位订阅停止。

本机测试覆盖窗口决策与触摸映射、首帧与会话代次校验、蓝牙到 Wi-Fi 定位请求接续、发送节流、ADB 认证和流取消、SDK 数据校验，以及 iAP2 单位／消息编码。
各车型的 SDK 支持和导航 App 的数据使用情况仍以对应实车和手机的结果为准。

## 共享 Android 10 模拟器验证（2026-10-02）

使用正式签名的 `DiPlay-0.2.0-release-20261002-124438-dilink4-vehicle-data.apk`，安装、页面操作和截图均通过 `emulator-5556` 的 ADB 完成。
为显示比亚迪设置，临时安装了只有 `com.byd.bydlogtool` 包名、无代码的测试标记，验证结束后移除。未修改生产代码的检测条件。

已验证：

- 车辆数据开关初始关闭，容量默认为自动读取。
- 开启车速／挡位上报会同时开启车机定位；关闭定位会同时关闭车速／挡位上报。
- 电量、直流充电开关和手动容量在强制停止、重新打开应用后保留。
- ADB 端口不可用时显示明确提示，设置页仍可操作。
- 验证后恢复车辆数据开关关闭、容量自动读取；应用日志未出现崩溃堆栈。

该模拟器没有比亚迪车辆 SDK、真实车辆读数或 iPhone CarPlay 会话。因此这次验证只覆盖安装与设置流程，不能替代上述实车验证。

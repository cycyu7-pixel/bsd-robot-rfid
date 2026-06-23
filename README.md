# InLay-RFID — InLayLink RFID 读写器客户端

基于 Spring Boot 2.4 + InLayLink RFID SDK 的读写器后台服务。插上读写器自动连接、持续盘点；掉线自动重连；服务器重启自动恢复。无人值守。

---

## 一、它做什么

读取附近 RFID 标签的编号（EPC），自动去重，写入日志文件。

部署到工业电脑或服务器之后：
- 启动 → 找到读写器 → 应用配置 → 开始持续盘点
- 标签靠近天线 → 日志写一条 `【新标签】 EPC=...`
- 读写器拔掉或断电 → 不崩溃，后台等设备回来
- 服务器重启 → Docker 自动拉起容器，程序自动跑

---

## 二、软件架构

```
┌──────────────────────────────────────────────────────────────┐
│                       外部环境                                │
│   ┌────────────┐         ┌────────────────────┐              │
│   │  RFID 标签 │ ─ 射频 ─▶│  InLayLink 读写器  │ ─ USB 串口 ─▶│
│   └────────────┘         └────────────────────┘              │
└──────────────────────────────────────────────────────────────┘
                                  │  /dev/ttyUSB0
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│              InLay-RFID 应用程序  (Spring Boot)               │
│                                                                │
│   ┌────────────────────────────────────────────────────────┐ │
│   │  ① RfidRunner    启动后自动执行                        │ │
│   │     ├─ 注册"连上后自动开始盘点"的回调                  │ │
│   │     └─ 启动后台重连服务                                │ │
│   └────────────────────────────────────────────────────────┘ │
│                              │                                 │
│                              ▼                                 │
│   ┌────────────────────────────────────────────────────────┐ │
│   │  ② RfidService   核心服务（所有 RFID 操作的入口）      │ │
│   │     ├─ 连接管理：连接 / 断开 / 自动重连 / 心跳检测     │ │
│   │     ├─ 参数配置：天线功率、Q 值、Session              │ │
│   │     ├─ 标签操作：持续盘点 / 单次盘点 / 读 / 写         │ │
│   │     └─ 状态监听：onConnected / onDisconnected         │ │
│   └────────────────────────────────────────────────────────┘ │
│           │                              │                     │
│           ▼                              ▼                     │
│   ┌──────────────────┐         ┌────────────────────────────┐ │
│   │ ③ RfidProperties │         │ ④ InLayLink RFID SDK       │ │
│   │   读取 yml 配置  │         │   厂商提供的底层 SDK       │ │
│   └──────────────────┘         └────────────────────────────┘ │
│                                                                │
│   ┌────────────────────────────────────────────────────────┐ │
│   │  ⑤ RfidController  HTTP 接口（给前端调用）             │ │
│   │     └─ 改天线功率、健康检查                            │ │
│   └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
            │                              │
            ▼                              ▼
   ┌─────────────────┐           ┌──────────────────┐
   │ application.yml │           │  logs/*.log      │
   │   (配置文件)    │           │   (按天滚动)     │
   └─────────────────┘           └──────────────────┘
```

应用内部 5 个模块：

| 模块 | 文件 | 职责 |
|---|---|---|
| 启动类 | `InLayRfidApplication.java` | Spring Boot 入口 |
| 配置 | `config/RfidProperties.java` | 把 `application.yml` 映射成 Java 对象 |
| 核心服务 | `service/RfidService.java` | 连接、重连、读卡、写卡、参数配置 |
| 启动流程 | `runner/RfidRunner.java` | 启动后自动连接 + 开启持续盘点 |
| REST 接口 | `controller/RfidController.java` | 给前端调用的 HTTP 接口 |

---

## 三、目录结构

```
InLay-RFID/
├── src/main/java/com/cyu/inlayrfid/
│   ├── InLayRfidApplication.java       启动类
│   ├── config/RfidProperties.java      配置映射
│   ├── service/RfidService.java        核心服务
│   ├── runner/RfidRunner.java          启动流程
│   └── controller/RfidController.java  REST 接口
├── src/main/resources/
│   ├── application.yml                 配置文件（串口、功率、重连）
│   └── logback-spring.xml              日志配置
├── lib/                                InLayLink SDK
├── Dockerfile                          镜像构建
├── deploy.sh                           一键部署脚本
└── pom.xml
```

---

## 四、核心功能

| 功能 | 说明 |
|---|---|
| 串口自动扫描 | `serial-port: auto` 自动扫所有端口 + 4 种波特率 |
| 持续后台盘点 | 连上即开始，标签实时去重打印 |
| 自动重连 | 1 秒一次心跳检测，掉线后台持续重试 |
| 运行时改功率 | HTTP 接口动态修改，不用重启 |
| 日志按天滚动 | 单文件 50MB，保留 3 天，自动压缩 |
| Docker 部署 | 一键脚本，开机自启 |

---

## 五、快速开始

### 本地运行

```bash
mvn clean package -DskipTests
java -jar target/InLay-RFID-1.0.0.jar
```

日志输出到控制台 + `./logs/rfid-yyyy-MM-dd.log`。

### Docker 部署（生产环境推荐）

```bash
./deploy.sh              # 构建 + 启动
./deploy.sh restart      # 重启
./deploy.sh stop         # 停止
./deploy.sh logs         # 看日志
./deploy.sh status       # 查状态
```

脚本会自动：检查 Docker → 设置 Docker 开机自启 → 创建日志目录 `/usr/log/rfid-logs` → 构建镜像 → 启动容器（`--restart=always`）→ 串口透传。

**修改 `deploy.sh` 顶部的 `SERIAL_DEVICE`** 可换串口路径。

---

## 六、配置说明

`application.yml`：

```yaml
rfid:
  serial-port: auto              # auto 或 /dev/ttyUSB0 / COM3
  baud-rate: 115200

  reconnect:
    enabled: true                # 开启自动重连
    interval-seconds: 5          # 重试间隔
    max-attempts: 0              # 0 = 无限

  antennas:                      # 每根天线独立功率
    - { id: 0, power: 1500 }     # 单位 0.1 dBm，1500 = 15 dBm
    - { id: 1, power: 1500 }
    - { id: 2, power: 1500 }
    - { id: 3, power: 1500 }

  query:
    session: S0
    target: AB

  q:
    init: 5
    max: 9
    min: 0
```

### 环境变量覆盖

Docker 启动时用 `-e` 覆盖：

```bash
-e RFID_SERIAL_PORT=/dev/ttyUSB1
-e RFID_ANTENNAS_0_POWER=2000
-e RFID_RECONNECT_INTERVAL_SECONDS=3
```

### Profile

- `dev`：本地开发，日志在 `./logs`
- `prod`：Docker 部署，日志在 `/usr/log/rfid-logs`

切换：`SPRING_PROFILES_ACTIVE=prod`

---

## 七、日志

| 项 | 值 |
|---|---|
| 路径（dev） | `./logs/rfid-yyyy-MM-dd.log` |
| 路径（prod） | `/usr/log/rfid-logs/rfid-yyyy-MM-dd.log` |
| 单文件上限 | 50 MB |
| 保留天数 | 3 天 |
| 总大小上限 | 500 MB |
| 历史压缩 | `.log.gz` |

关键日志示例：

```
INFO  读写器连接成功: /dev/ttyUSB0 @ 115200
INFO  天线 ANT0 配置成功: 15.0 dBm
INFO  持续盘点已启动，等待标签...
INFO  【新标签】 EPC=E20000172211...  RSSI=-45 dBm  天线=0
WARN  读写器已断开 (/dev/ttyUSB0), 后台持续重连中...
```

---

## 八、REST 接口

默认端口 `8080`。

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/rfid/health` | GET | 健康检查 |
| `/api/rfid/antennas/{antId}/power` | POST | 改单根天线功率 |
| `/api/rfid/antennas/power` | POST | 改所有天线功率 |

功率单位统一用整数 dBm（0~33），后端自动换算。

```bash
curl -X POST http://localhost:8080/api/rfid/antennas/0/power \
  -H "Content-Type: application/json" \
  -d '{"power": 20}'
```

---

## 九、二次开发

**业务逻辑加在哪？** 打开 `RfidRunner.startContinuousInventory()`，回调里写：

```java
rfidService.setInventoryCallback(tag -> {
    String epc = tag.getEpc();
    if (seenEpcs.add(epc)) {
        log.info("【新标签】 EPC={}", epc);
        // ⬇️ 在这里加业务（写库、推接口、报警）
    }
});
```

**核心 API**（`RfidService`）：

```java
// ── 持续盘点（生产环境用，项目默认就是这个） ─────────────
rfidService.setInventoryCallback(tag -> { ... });          // 注册标签回调
rfidService.startInventory(onSuccess, onFailure);          // 启动后一直跑
rfidService.stopInventory(onSuccess, onFailure);           // 停止

// ── 限时盘点（临时 / 工具场景，比如"扫一下"按钮） ────────
rfidService.inventoryFor(10);                              // 阻塞 10 秒，返回 List<InventoryTag>

// ── 状态与配置 ─────────────────────────────────────
rfidService.isConnected();                                 // 连接状态
rfidService.setAntennaPower(0, 2000);                      // 改天线功率（运行时）

// ── 单标签读写 ─────────────────────────────────────
rfidService.readTag(MemBank.EPC, 2, 6);                    // 读标签
rfidService.writeTag(MemBank.EPC, newEpc, 2, 6);           // 写标签
```

> 项目启动后会通过 `startInventory()` 进入**持续盘点**状态，SDK 内部循环读卡，直到 `stopInventory()` 或断开连接。`inventoryFor(N)` 是"读 N 秒后自动停"的便利方法，**只在临时/工具场景下用**，生产环境不要拿它替代持续盘点。

---

## 十、常见问题

**Docker 容器看不到串口**
必须用 `--device` 透传，`deploy.sh` 已处理。

**日志没写到文件**
检查 `-v /usr/log/rfid-logs:/usr/log/rfid-logs` 挂载，目录权限 777。

**自动扫描扫不到读写器**
直接指定路径：`-e RFID_SERIAL_PORT=/dev/ttyUSB0`，并确认 `--device` 已传。

**读不到标签**
检查：天线接好、标签在 5~50cm 范围内、标签是 UHF Gen2、功率够（可调到 2000+）。

**Docker 日志时间慢 8 小时**
Dockerfile 已设 `TZ=Asia/Shanghai`，自定义镜像注意保留。

---

## 十一、技术栈

Java 8 · Spring Boot 2.4.2 · InLayLink RFID SDK 2.25.05.191 · Logback · Maven · Docker

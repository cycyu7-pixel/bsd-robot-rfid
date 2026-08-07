# BSD Robot RFID

> 基于 InLayLink 读写器的 RFID 标签识别后端服务。
> 服务启动后自动扫描并连接读写器，连接成功后不会自动盘点；需要通过静态控制台或 REST API 手动开始读取。

| 字段 | 内容 |
| --- | --- |
| 项目名 | BSD Robot RFID / InLay-RFID |
| 仓库地址 | https://github.com/cycyu7-pixel/bsd-robot-rfid |
| 业务方/所属团队 | BSD |
| 一句话定位 | 让服务器持续连接 RFID 读写器，读取标签 EPC，并把运行状态开放给页面和接口调用方 |
| 技术栈 | Java 8 + Spring Boot 2.4.2 + InLayLink RFID SDK 2.25.05.191 + Docker |
| 部署环境 | Linux x86_64 / Docker；本地可用 Windows、macOS、Linux 调试 |
| 主要功能 | RFID 自动连接与重连、手动盘点与 EPC 去重、读取控制与天线功率调节 |

---

## 目录

- [1. 这个项目做什么](#1-这个项目做什么)
- [2. 工作原理](#2-工作原理)
- [3. 快速开始](#3-快速开始)
- [4. 工程结构](#4-工程结构)
- [5. 配置说明](#5-配置说明)
- [6. REST 接口 / 对外 API](#6-rest-接口--对外-api)
- [7. RFID 手动读取与重连](#7-rfid-手动读取与重连)
- [8. 静态控制台](#8-静态控制台)
- [9. 天线功率调节](#9-天线功率调节)
- [10. 日志查看](#10-日志查看)
- [11. 部署与运维](#11-部署与运维)
- [12. 改代码后怎么上线](#12-改代码后怎么上线)
- [13. 二次开发](#13-二次开发)
- [14. 常见问题排查](#14-常见问题排查)
- [15. RFID 领域基础知识](#15-rfid-领域基础知识)
- [16. 第三方 SDK / 库速查表](#16-第三方-sdk--库速查表)

---

## 1. 这个项目做什么

这个项目是一个 RFID 读写器后端服务，核心目标是：

1. 服务器启动后自动寻找 InLayLink RFID 读写器。
2. 读写器连接成功后应用天线、Session、Q 值等参数。
3. 连接成功后等待页面或 REST API 手动开始读取标签 EPC。
4. 通过页面和 REST API 查看状态、控制读取、调节功率、拉取新 EPC。

可以把它理解成一个“常开扫码器”：读写器像扫码枪一样一直工作，贴有 RFID 标签的物品经过天线范围时，本服务把标签 EPC 读出来并记录。

### 适用场景

| 场景 | 说明 |
| --- | --- |
| 机器人 / 产线 / 门禁旁的 RFID 识别 | 读写器接到服务器 USB 串口，本服务负责按人工或机器人信号手动读取 EPC |
| 需要无人值守运行 | 读写器没插、断电、拔插后，服务不会退出，会持续重连 |
| 需要现场调试功率 | 可通过浏览器页面或接口实时修改天线功率，不需要重启服务 |
| 需要简单对接业务系统 | 业务系统可轮询 `/api/rfid/tags?since=...` 获取新增标签 |

### 不适用场景或边界

| 边界 | 说明 |
| --- | --- |
| 不负责业务入库 | 当前项目只提供 EPC 读取、状态、控制接口，没有内置数据库持久化 |
| 不负责复杂规则引擎 | 标签去重只在进程内存中完成，重启后内存状态会丢失 |
| 不提供鉴权 | 当前 REST 接口没有登录鉴权，生产环境如暴露到公网需要额外加网关或防火墙 |
| 不保证所有读写器通用 | 代码按 InLayLink RFID SDK 编写，其他厂商读写器需要适配 SDK |

### 主要功能

| 功能 | 做什么 | 关键入口 |
| --- | --- | --- |
| RFID 自动连接与重连 | 自动扫描串口，按 USB VID 识别 InLayLink 设备；掉线后后台重连 | `RfidService.startReconnectService()` |
| 手动盘点与 EPC 去重 | 手动触发读取标签，记录总读取次数、不同 EPC 数、最近标签事件 | `RfidService.startReading()` |
| 一次性扫描（回调） | 机器人流程控制调用 `/api/rfid/scan`，扫到 EPC 后异步回调结果 | `ScanService`、`ScanTask` |
| 控制台与 REST API | 页面查看状态、开始/停止/重启读取、清空 EPC、调节功率 | `RfidController`、`static/index.html` |

---

## 2. 工作原理

```text
[RFID 标签]
    |
    | 860-960MHz 射频通信
    v
[InLayLink 读写器] -- USB 串口 --> [本项目 Spring Boot 服务]
                                      |
                                      |-- 写控制台日志 / 文件日志
                                      |
                                      |-- 静态控制台: http://服务器IP:8080/
                                      |
                                      |-- REST API: http://服务器IP:8080/api/rfid/*
                                      |
                                      `-- 内存标签事件队列: 最近 1000 条新 EPC
```

启动到运行的全过程：

1. Spring Boot 启动，读取 `application.yml`。
2. `RfidRunner` 打印启动信息、注册连接监听器。
3. `RfidService` 启动后台重连任务，每 1 秒检查一次连接状态。
4. 串口配置为 `auto` 时，程序扫描当前系统串口：
   - Linux 下优先读取 `/sys/class/tty/.../idVendor`，识别 `2fe3` 设备。
   - 识别不到时，回退遍历所有非系统串口。
5. 读写器连接成功后：
   - 打印版本信息。
   - 按配置设置天线功率、`Session`、`Target`、`Q`。
   - 初始化完成后等待页面或 REST API 手动开始读取。
6. 持续盘点时：
   - SDK 回调每次读到的标签。
   - `totalReads` 记录 SDK 回调总次数。
   - `seenEpcs` 对 EPC 去重。
   - 读取开始后，新 EPC 进入 `tagEvents` 队列，供页面和 `/api/rfid/tags` 拉取。
7. 读写器拔掉、串口消失、心跳失败时：
   - 程序标记未连接，停止读取状态。
   - 后台任务继续按间隔重连。
   - 读写器重新插上后自动恢复连接和参数配置，但仍等待手动开始读取。

---

## 3. 快速开始

### 3.1 环境要求

| 环境 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 1.8 | 本地运行需要 |
| Maven | 3.6+ | 本地打包需要；Docker 构建阶段使用 Maven 镜像 |
| Docker | 20+ | 生产部署推荐 |
| RFID 读写器 | InLayLink 读写器 | 通过 USB 串口连接服务器 |
| 串口权限 | Linux 需要访问 `/dev/ttyUSB*` 或 `/dev/ttyACM*` | 部署脚本默认使用 `--privileged` 并挂载 `/dev` |

### 3.2 健康检查

服务启动后，先检查 HTTP 服务是否存活：

```bash
curl -i http://localhost:8080/api/rfid/health
```

预期返回：

```text
HTTP/1.1 200
...
healthy
```

浏览器也可以打开控制台页面：

```text
http://localhost:8080/
```

服务启动后不会自动盘点。读写器连接成功后，需要在页面点击“开始读取”，或调用 `POST /api/rfid/reading/start`。

### 3.3 本地运行

```bash
# 1. 编译打包
mvn clean package -DskipTests

# 2. 启动，默认使用 dev profile
java -jar target/InLay-RFID-1.0.0.jar

# 3. 健康检查
curl http://localhost:8080/api/rfid/health
```

如果需要指定串口：

```bash
java -jar target/InLay-RFID-1.0.0.jar \
  --rfid.serial-port=COM3 \
  --rfid.baud-rate=115200
```

Linux 示例：

```bash
java -jar target/InLay-RFID-1.0.0.jar \
  --rfid.serial-port=/dev/ttyACM0
```

### 3.4 生产部署一条命令

```bash
cd ~/robot-rfid/bsd-robot-rfid
sudo bash deploy.sh
```

部署脚本会自动完成：

1. 检查 Docker。
2. 设置 Docker 开机自启。
3. 创建宿主机日志目录 `/usr/log/rfid-logs`。
4. 构建镜像。
5. 删除旧容器并启动新容器 `inlay-rfid`。
6. 使用 `--restart=always` 让容器随 Docker 自动恢复。

### 3.5 停止服务

```bash
sudo bash deploy.sh stop
```

### 3.6 启动失败时第一条检查命令

```bash
sudo docker logs --tail=200 inlay-rfid
```

如果容器没有启动，再看容器状态：

```bash
sudo docker ps -a | grep inlay-rfid
```

---

## 4. 工程结构

```text
bsd-robot-rfid/
├── lib/                                      # 项目本地 Maven 仓库，存放 InLayLink RFID SDK
│   └── com/inlaylink/rfid/2.25.05.191/
├── logs/                                    # 本地 dev 日志目录，运行后生成
├── src/
│   ├── main/
│   │   ├── java/com/cyu/inlayrfid/
│   │   │   ├── InLayRfidApplication.java    # Spring Boot 启动入口
│   │   │   ├── config/RfidProperties.java   # rfid.* 配置映射
│   │   │   ├── config/ThreadPoolConfig.java # 一次性扫描线程池 + RestTemplate Bean
│   │   │   ├── controller/RfidController.java # REST 接口
│   │   │   ├── entity/dto/                  # 请求 DTO
│   │   │   ├── entity/vo/                   # 响应 VO
│   │   │   ├── runner/RfidRunner.java       # 启动后注册监听、开启重连
│   │   │   ├── service/RfidService.java     # RFID 连接、读取、重连、读写标签核心逻辑
│   │   │   ├── service/ScanService.java     # 一次性扫描编排，提交任务到线程池
│   │   │   └── task/ScanTask.java           # 一次性扫描任务（事件驱动，扫到/超时后回调）
│   │   └── resources/
│   │       ├── application.yml              # 应用配置，含 dev/prod profile
│   │       ├── logback-spring.xml           # 日志配置
│   │       └── static/index.html            # 静态控制台页面
│   └── test/                                # 测试代码
├── Dockerfile                               # 多阶段镜像构建
├── deploy.sh                                # Docker 一键部署脚本
├── log-cleanup.sh                           # 日志兜底清理脚本
├── pom.xml                                  # Maven 构建文件
└── README.md                                # 项目说明文档
```

| 路径 | 职责 | 修改建议 |
| --- | --- | --- |
| `src/main/java/com/cyu/inlayrfid/service/RfidService.java` | RFID SDK 封装，连接、重连、盘点、读写标签都在这里 | 新增 SDK 操作优先放这里 |
| `src/main/java/com/cyu/inlayrfid/runner/RfidRunner.java` | Spring Boot 启动后执行初始化流程 | 改启动流程、自动读取策略时修改 |
| `src/main/java/com/cyu/inlayrfid/controller/RfidController.java` | 对外 REST API | 新增 HTTP 接口时修改 |
| `src/main/java/com/cyu/inlayrfid/config/RfidProperties.java` | `application.yml` 配置映射 | 新增 `rfid.*` 配置时同步添加字段 |
| `src/main/java/com/cyu/inlayrfid/config/ThreadPoolConfig.java` | 一次性扫描线程池（core=1, max=3, queue=5）与 RestTemplate Bean | 调整扫描并发、回调 HTTP 客户端时修改 |
| `src/main/java/com/cyu/inlayrfid/service/ScanService.java` | 一次性扫描编排，生成 requestId 并提交任务到线程池 | 改扫描任务提交逻辑时修改 |
| `src/main/java/com/cyu/inlayrfid/task/ScanTask.java` | 一次性扫描任务，打开/关闭读写器、扫到或超时后回调调用方 | 改扫描流程、回调逻辑、超时时长时修改 |
| `src/main/java/com/cyu/inlayrfid/entity/dto` | 请求入参对象 | 不建议用 `Map` 接口入参，优先新增 DTO |
| `src/main/java/com/cyu/inlayrfid/entity/vo` | 接口响应对象 | 新增接口响应字段时优先新增 VO |
| `src/main/resources/static/index.html` | 浏览器控制台 | 改页面展示和接口调用逻辑时修改 |
| `src/main/resources/application.yml` | 应用配置 | 改默认端口、串口、统一功率、手动读取策略等配置 |
| `src/main/resources/logback-spring.xml` | 日志格式和归档策略 | 改日志级别、保留天数、日志目录时修改 |
| `deploy.sh` | 生产部署脚本 | 改容器名、端口、日志挂载、镜像源时修改 |
| `Dockerfile` | 镜像构建 | 改基础镜像、JVM 参数、运行目录时修改 |

---

## 5. 配置说明

配置文件位置：

```text
src/main/resources/application.yml
```

### 5.1 完整配置示例

```yaml
# ============================================================
# InLayLink RFID Reader 配置
# ============================================================
server:
  port: 8080 # HTTP 服务端口，访问控制台和 REST API 使用

spring:
  application:
    name: InLay-RFID # 应用名
  profiles:
    active: dev # 默认 profile，本地为 dev；Dockerfile 和 deploy.sh 使用 prod

logging:
  file:
    path: ./logs # 日志目录；prod profile 会覆盖为 /usr/log/rfid-logs

# 一次性扫描回调地址（机器人上的流程控制服务接口）
# 扫描到 EPC / 超时 / 出错后，本服务会 POST 结果到该地址
scan:
  callback-url: http://localhost:18800/api/v1/epc/callback

rfid:
  serial-port: auto # 串口路径；auto 表示自动扫描，也可以写 /dev/ttyACM0、/dev/ttyUSB0、COM3
  baud-rate: 115200 # 固定串口连接时使用；serial-port=auto 时会自动尝试常见波特率

  reconnect:
    enabled: true # 是否开启后台自动重连
    interval-seconds: 5 # 重连间隔，单位秒
    max-attempts: 0 # 最大重试次数；0 或负数表示无限重试

  inventory:
    auto-start: false # 当前机器人抓取场景固定手动读取；服务启动和重连后都不会自动盘点

  antennas:
    - id: 0 # 天线端口编号 ANT0
      power: 1500 # 服务内存展示值 / 手动统一设置后的记录值，连接成功时不会自动下发
    - id: 1
      power: 1500
    - id: 2
      power: 1500
    - id: 3
      power: 1500

  query:
    session: S0 # 盘点会话，可选 S0/S1/S2/S3
    target: AB # 盘点目标，可选 A/B/AB

  q:
    init: 5 # 初始 Q 值
    max: 9 # 最大 Q 值
    min: 0 # 最小 Q 值

---
# ============================================================
# Profile: dev，本地开发
# ============================================================
spring:
  config:
    activate:
      on-profile: dev

logging:
  file:
    path: ./logs # 本地日志目录

rfid:
  serial-port: auto # 本地也默认自动扫描

---
# ============================================================
# Profile: prod，Docker / Linux 部署
# ============================================================
spring:
  config:
    activate:
      on-profile: prod

logging:
  file:
    path: /usr/log/rfid-logs # 容器内日志目录，deploy.sh 会挂载到宿主机同路径

rfid:
  serial-port: auto # Linux 下自动扫描 /dev/ttyUSB*、/dev/ttyACM* 等串口
```

> 注意：天线 `power` 在配置文件和 SDK 内部使用 `0.01 dBm` 单位，`1500 = 15 dBm`。当前服务连接读写器时不会自动下发该功率；只有点击页面“应用全部”或调用统一功率接口时才会写入读写器。REST 接口和页面使用整数 dBm，传 `15` 即可。

### 5.2 必填字段

| 配置 | 是否必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `server.port` | 否 | `8080` | HTTP 端口 |
| `rfid.serial-port` | 否 | `auto` | 推荐保持 `auto`，程序自动扫描串口 |
| `rfid.baud-rate` | 否 | `115200` | 固定串口模式下使用 |
| `rfid.antennas` | 否 | ANT0-ANT3 | 配置服务需要管理的天线端口；`power` 仅作展示/记录，连接时不自动下发 |
| `rfid.reconnect.enabled` | 否 | `true` | 生产环境建议开启 |
| `rfid.inventory.auto-start` | 否 | `false` | 当前保留为兼容旧配置；实际启动流程固定等待手动读取 |
| `scan.callback-url` | 否 | `http://localhost:18800/api/v1/epc/callback` | 一次性扫描回调地址，机器人流程控制服务所在位置 |

### 5.3 默认值速查

| 配置 | 默认值 | 来源 |
| --- | --- | --- |
| `spring.profiles.active` | `dev` | `application.yml` |
| `logging.file.path` | `./logs` | `application.yml` |
| `rfid.serial-port` | `auto` | `application.yml` / `RfidProperties` |
| `rfid.baud-rate` | `115200` | `application.yml` / `RfidProperties` |
| `rfid.reconnect.interval-seconds` | `5` | `application.yml` / `RfidProperties` |
| `rfid.reconnect.max-attempts` | `0` | `application.yml` / `RfidProperties` |
| `rfid.inventory.auto-start` | `false` | `application.yml` / `RfidProperties` |
| `rfid.query.session` | `S0` | `application.yml` / `RfidProperties` |
| `rfid.query.target` | `AB` | `application.yml` / `RfidProperties` |
| `rfid.q.init/max/min` | `5/9/0` | `application.yml` / `RfidProperties` |
| `scan.callback-url` | `http://localhost:18800/api/v1/epc/callback` | `application.yml` / `ScanService` 读取 |

### 5.4 环境变量覆盖

Spring Boot 支持用环境变量覆盖配置。常用示例：

```bash
SERVER_PORT=18080 \
SPRING_PROFILES_ACTIVE=prod \
RFID_SERIAL_PORT=/dev/ttyACM0 \
RFID_BAUD_RATE=115200 \
RFID_RECONNECT_INTERVAL_SECONDS=3 \
java -jar target/InLay-RFID-1.0.0.jar
```

Docker 示例：

```bash
docker run -d \
  --name inlay-rfid \
  --restart=always \
  --privileged \
  -p 8080:8080 \
  -v /dev:/dev \
  -v /usr/log/rfid-logs:/usr/log/rfid-logs \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e RFID_SERIAL_PORT=auto \
  -e TZ=Asia/Shanghai \
  inlay-rfid:latest
```

天线配置也可以通过环境变量覆盖，示例：

```bash
-e RFID_ANTENNAS_0_ID=0 \
-e RFID_ANTENNAS_0_POWER=1800 \
-e RFID_ANTENNAS_1_ID=1 \
-e RFID_ANTENNAS_1_POWER=1800
```

### 5.5 Profile 切换

本地使用 `dev`：

```bash
java -jar target/InLay-RFID-1.0.0.jar --spring.profiles.active=dev
```

生产使用 `prod`：

```bash
java -jar target/InLay-RFID-1.0.0.jar --spring.profiles.active=prod
```

环境变量方式：

```bash
SPRING_PROFILES_ACTIVE=prod java -jar target/InLay-RFID-1.0.0.jar
```

| Profile | 日志路径 | 适用场景 |
| --- | --- | --- |
| `dev` | `./logs` | 本地开发、Windows/macOS/Linux 调试 |
| `prod` | `/usr/log/rfid-logs` | Docker / Linux 部署 |

### 5.6 修改配置后是否需要重启

| 修改方式 | 是否需要重启 | 说明 |
| --- | --- | --- |
| 修改 `application.yml` | ✅ 需要 | Spring Boot 启动时读取配置 |
| 修改环境变量 | ✅ 需要 | 需要重建或重启容器让环境变量生效 |
| 通过 REST 接口改天线功率 | ❌ 不需要 | 运行时立即调用 SDK 设置，但重启后恢复为配置文件值 |
| 修改 `logback-spring.xml` | ✅ 需要 | 推荐重启服务 |

---

## 6. REST 接口 / 对外 API

所有 REST 接口前缀：

```text
http://服务器IP:8080/api/rfid
```

统一 JSON 响应结构：

```json
{
  "success": true,
  "message": "操作成功",
  "data": {}
}
```

健康检查接口例外，直接返回纯文本 `healthy`。

### 6.1 健康检查

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/rfid/health` |
| 用途 | 判断 HTTP 服务是否存活 |
| 请求体 | 无 |

响应示例：

```text
healthy
```

curl：

```bash
curl -i http://localhost:8080/api/rfid/health
```

### 6.2 获取当前状态

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/rfid/status` |
| 用途 | 查询连接状态、读取状态、串口、读取次数、天线功率等 |
| 请求体 | 无 |

响应示例：

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "connected": true,
    "reading": true,
    "actualSerialPort": "ttyACM0",
    "totalReads": 128,
    "uniqueCount": 6,
    "latestSeq": 6,
    "lastTagCallbackTime": 1793260800000,
    "lastTagCallbackAgoSeconds": 1,
    "lastReadingRestartTime": 1793260790000,
    "antennaPower": 1500,
    "antennaPowerDbm": 15.0,
    "antennaCount": 4,
    "antennaPowerUniform": true
  }
}
```

curl：

```bash
curl http://localhost:8080/api/rfid/status
```

> `antennaPowerDbm` 当前功率取所有配置天线端口中的最大功率，用于现场直接判断当前读取范围；页面在读写器未连接时默认展示 `13 dBm`，该展示值不会自动下发到读写器。

### 6.3 开始持续读取

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/rfid/reading/start` |
| 用途 | 手动开始持续读取标签 |
| 请求体 | 无 |

响应示例：

```json
{
  "success": true,
  "message": "已开始读取",
  "data": {
    "reading": true
  }
}
```

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/reading/start
```

### 6.4 停止持续读取

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/rfid/reading/stop` |
| 用途 | 手动停止持续读取标签 |
| 请求体 | 无 |

响应示例：

```json
{
  "success": true,
  "message": "已停止读取",
  "data": {
    "reading": false
  }
}
```

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/reading/stop
```

### 6.5 重启持续读取

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/rfid/reading/restart` |
| 用途 | 读写器仍连接但长时间没有标签回调时，手动重启盘点 |
| 请求体 | 无 |

响应示例：

```json
{
  "success": true,
  "message": "已重启读取",
  "data": {
    "reading": true
  }
}
```

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/reading/restart
```

### 6.6 增量获取新 EPC

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/rfid/tags` |
| 用途 | 按序号增量获取新读取到的 EPC |

请求参数：

| 参数 | 类型 | 是否必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `since` | `long` | 否 | `0` | 只返回 `seq > since` 的标签事件 |

响应示例：

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "latestSeq": 8,
    "items": [
      {
        "seq": 7,
        "epc": "B00110020002409230407738",
        "rssi": -58.0,
        "antenna": 2,
        "timestamp": 1793260800000
      },
      {
        "seq": 8,
        "epc": "B00110020002409230407739",
        "rssi": -61.0,
        "antenna": 1,
        "timestamp": 1793260801000
      }
    ]
  }
}
```

curl：

```bash
# 首次拉取
curl "http://localhost:8080/api/rfid/tags?since=0"

# 后续用上次返回的 latestSeq 继续拉取
curl "http://localhost:8080/api/rfid/tags?since=8"
```

### 6.7 清空 EPC 记录

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/rfid/tags/clear` |
| 用途 | 清空内存中的已读 EPC 和页面事件队列 |
| 请求体 | 无 |

响应示例：

```json
{
  "success": true,
  "message": "已清空 EPC 记录",
  "data": {
    "latestSeq": 0,
    "items": []
  }
}
```

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/tags/clear
```

### 6.8 统一修改所有天线功率

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/rfid/antennas/power` |
| 用途 | 将所有已配置天线端口统一修改为同一个功率 |

请求体：

```json
{
  "power": 18
}
```

响应示例：

```json
{
  "success": true,
  "message": "修改成功",
  "data": {
    "powerDbm": 18,
    "results": [
      {"antId": 0, "success": true},
      {"antId": 1, "success": true},
      {"antId": 2, "success": true},
      {"antId": 3, "success": true}
    ]
  }
}
```

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/antennas/power \
  -H "Content-Type: application/json" \
  -d '{"power":18}'
```

### 6.9 一次性扫描（回调模式）

这是机器人抓取场景的核心接口：**流程控制服务调用本接口触发一次扫描，立即拿到 `requestId`，扫描结果异步回调到配置好的地址**（默认 `http://localhost:18800/api/v1/epc/callback`）。

#### 请求

```http
POST /api/rfid/scan
```

无请求体。超时时间固定 35 秒（`ScanTask` 中常量 `SCAN_TIMEOUT_SEC`），调用方无需传任何参数。

#### 立即响应

```json
{
  "success": true,
  "message": "scan accepted",
  "data": {
    "requestId": "a1b2c3d4e5f6..."
  }
}
```

流程控制服务拿到 `requestId` 存起来，等回调。

#### 回调（本服务 → 流程控制服务）

扫描完成/超时/出错后，本服务向 `scan.callback-url` 发起 POST：

```json
// 扫到了
{
  "requestId": "a1b2c3d4e5f6...",
  "epc": "E200001234567",
  "error": null
}

// 超时/出错
{
  "requestId": "a1b2c3d4e5f6...",
  "epc": null,
  "error": "扫描超时（35s），未读取到 EPC，请重新发起扫描"
}
```

#### 流程控制服务处理逻辑

```java
if (result.error != null) {
    // 出错/超时 → 清掉该 requestId 缓存，重新调 POST /api/rfid/scan
    retry();
} else {
    // 成功 → 拿 result.epc 干活
    process(result.epc);
}
```

#### 触发场景

| 场景 | 回调 payload | 流程控制处理 |
| --- | --- | --- |
| 扫到 EPC | `error: null, epc: "E2..."` | 处理 EPC |
| 35s 超时 | `error: "扫描超时..."` | 清缓存重试 |
| 读写器未连接 | `error: "读写器未连接"` | 清缓存重试 |
| 启动盘点失败 | `error: "启动盘点失败"` | 清缓存重试 |
| 线程池满载（排队>5） | 立即返回 `{"success":false,...}` | 稍后重试 |

curl：

```bash
curl -X POST http://localhost:8080/api/rfid/scan
```

### 6.10 常见错误响应

| 场景 | 响应示例 | 处理方式 |
| --- | --- | --- |
| 读写器未连接 | `{"success":false,"message":"读写器未连接","data":null}` | 检查 USB、串口、容器权限和日志 |
| `power` 缺失 | `{"success":false,"message":"power 必须是 0~33 的整数","data":null}` | 请求体加上 `{"power":18}` |
| `power` 超范围 | `{"success":false,"message":"power 必须是 0~33 的整数","data":null}` | 调整为 `0~33` 整数 |
| 启动读取失败 | `{"success":false,"message":"开始读取失败","data":null}` | 查看读写器连接、天线连接和 SDK 日志 |
| 重启读取失败 | `{"success":false,"message":"重启读取失败","data":null}` | 先确认 `/status` 中 `connected=true` |

---

## 7. RFID 手动读取与重连

### 7.1 自动连接

默认配置：

```yaml
rfid:
  serial-port: auto
```

`auto` 模式下，服务会：

1. 扫描系统串口。
2. Linux 下通过 USB VID 识别 InLayLink 设备，代码中识别 `2fe3`。
3. 对候选串口依次尝试常见波特率：`115200`、`57600`、`38400`、`9600`。
4. 连接成功后保存实际串口到 `actualSerialPort`，可通过 `/api/rfid/status` 查看。

固定串口示例：

```yaml
rfid:
  serial-port: /dev/ttyACM0
  baud-rate: 115200
```

### 7.2 后台重连

配置：

```yaml
rfid:
  reconnect:
    enabled: true
    interval-seconds: 5
    max-attempts: 0
```

运行行为：

| 状态 | 行为 |
| --- | --- |
| 启动时读写器未插 | 程序不退出，后台按间隔持续扫描 |
| 运行中 USB 被拔掉 | 检测到串口消失或心跳失败，断开并等待重连 |
| 读写器重新插上 | 自动连接并重新应用参数；是否读取由页面或 REST API 手动决定 |
| `max-attempts=0` | 无限重试，适合无人值守部署 |

### 7.3 手动开始读取

当前机器人抓取场景固定为手动读取：服务启动、读写器首次连接、读写器断线重连后，都只初始化参数，不会自动开始盘点。

开始读取可以通过控制台页面点击“开始读取”，也可以调用接口：

```bash
curl -X POST http://localhost:8080/api/rfid/reading/start
```

停止读取：

```bash
curl -X POST http://localhost:8080/api/rfid/reading/stop
```

### 7.4 手动重启读取

如果读写器仍然连接，但现场怀疑 SDK 读取状态异常，可以手动重启盘点。该操作只会在你点击页面按钮或调用接口时发生，系统不会再根据无标签回调时间自动重启。

```bash
curl -X POST http://localhost:8080/api/rfid/reading/restart
```

### 7.5 EPC 去重与事件队列

| 数据 | 说明 |
| --- | --- |
| `totalReads` | SDK 标签回调总次数，包含重复读到同一个标签 |
| `uniqueCount` | 当前进程内不同 EPC 数量 |
| `latestSeq` | 新 EPC 事件递增序号 |
| `tagEvents` | 最近新 EPC 事件队列，最多保留 1000 条 |
| `seenEpcs` | 内存去重集合，清空或重启后重置 |

清空去重记录：

```bash
curl -X POST http://localhost:8080/api/rfid/tags/clear
```

---

## 8. 静态控制台

控制台文件：

```text
src/main/resources/static/index.html
```

访问地址：

```text
http://服务器IP:8080/
```

### 8.1 页面功能

| 区域 | 功能 |
| --- | --- |
| 服务状态 | 显示 `/api/rfid/health` 是否返回 `healthy` |
| 读写器连接 | 显示 `connected`、`actualSerialPort` |
| 读取状态 | 显示是否正在盘点 |
| 统计卡片 | 显示总读取次数、不同 EPC 数、最新序号、最后回调时间 |
| 读取控制 | 开始读取、停止读取、重启读取、清空 EPC 记录、手动刷新 |
| 统一功率调节 | 所有天线端口统一设置 `0~33 dBm`，并展示当前统一功率 |
| 新读取 EPC | 每 500ms 增量拉取新 EPC，页面最多显示最近 300 条 |

### 8.2 页面轮询频率

| 轮询内容 | 接口 | 频率 |
| --- | --- | --- |
| 服务健康 | `/api/rfid/health` | 5 秒 |
| 当前状态 | `/api/rfid/status` | 2 秒 |
| 新 EPC | `/api/rfid/tags?since=...` | 500 毫秒 |

### 8.3 控制台常用操作

| 想做什么 | 操作 |
| --- | --- |
| 确认服务是否活着 | 看“服务状态”是否为 `healthy` |
| 确认读写器是否连接 | 看“读写器连接”和“当前串口” |
| 读不到标签 | 点击“重启读取”，再看日志和天线功率 |
| 调小读取范围 | 把功率调低，例如 `10~15 dBm` |
| 调大读取范围 | 把功率调高，例如 `18~25 dBm` |
| 让旧标签重新显示为新标签 | 点击“清空 EPC 记录” |

---

## 9. 天线功率调节

### 9.1 单位说明

| 位置 | 单位 | 示例 |
| --- | --- | --- |
| `application.yml` | `0.01 dBm` | `1500 = 15 dBm` |
| REST API | `dBm` 整数 | `{"power":18}` |
| 控制台页面 | `dBm` 整数 | 输入 `18` |
| SDK 调用 | `0.01 dBm` | 代码中接口会把 `18` 转为 `1800` |

### 9.2 功率参考

| 功率 | 适用场景 | 说明 |
| --- | --- | --- |
| `0 dBm` | 关闭或极近距离调试 | 代码中 `power=0` 会设置天线 disabled |
| `10 dBm` | 标签贴近天线 | 读取范围小，误读少 |
| `13 dBm` | 胸口近距离 / 机器人抓取读卡 | 页面未连接读写器时的默认展示值 |
| `18 dBm` | 常规读取 | 现场常用起始值 |
| `20 dBm` | 中距离读取 | 需要注意误读附近标签 |
| `25 dBm` | 远距离读取 | 适合较远标签，但可能读到无关标签 |
| `30~33 dBm` | 最大功率附近 | 不建议长期默认使用，先现场验证 |

### 9.3 操作示例

服务连接成功后不会自动修改读写器功率。只有调用下面的统一功率接口，才会把功率写入所有已配置天线端口。

统一改成 `18 dBm`：

```bash
curl -X POST http://localhost:8080/api/rfid/antennas/power \
  -H "Content-Type: application/json" \
  -d '{"power":18}'
```

只改 ANT2：


查看当前功率：

```bash
curl http://localhost:8080/api/rfid/status
```

### 9.4 调整原则

| 现象 | 建议 |
| --- | --- |
| 读不到标签 | 确认天线接线，再逐步调高功率 |
| 读到很多不该读的标签 | 降低功率，或只启用实际需要的天线 |
| 改完功率重启后又恢复 | REST 修改只在运行时生效；要持久化需改 `application.yml` |

---

## 10. 日志查看

日志同时输出到：

| 类型 | 位置 |
| --- | --- |
| 容器控制台 | `docker logs inlay-rfid` |
| 文件日志 dev | `./logs/rfid-yyyy-MM-dd.log` |
| 文件日志 prod | `/usr/log/rfid-logs/rfid-yyyy-MM-dd.log` |

日志归档策略：

| 配置 | 值 |
| --- | --- |
| 文件名 | `rfid-%d{yyyy-MM-dd}.log` |
| 保留天数 | 3 天 |
| 总大小上限 | 500MB |
| 项目日志级别 | `com.cyu.inlayrfid=DEBUG` |
| SDK 日志级别 | `com.inlaylink=INFO` |

### 10.1 容器 / 进程日志查看

```bash
# 1. 实时查看容器日志
sudo docker logs -f inlay-rfid

# 2. 查看最后 200 行
sudo docker logs --tail=200 inlay-rfid

# 3. 按相对时间查看，例如最近 10 分钟
sudo docker logs --since 10m inlay-rfid

# 4. 按具体时间查看
sudo docker logs --since "2026-06-29T00:00:00" inlay-rfid

# 5. 带时间戳查看
sudo docker logs -t --tail=200 inlay-rfid

# 6. less 翻页查看，按 q 退出，按 / 搜索
sudo docker logs inlay-rfid 2>&1 | less

# 7. 本地 java -jar 运行时，如果用 nohup 启动
 tail -f nohup.out
```

通过部署脚本查看：

```bash
sudo bash deploy.sh logs
sudo bash deploy.sh status
```

### 10.2 日志文件查看

生产环境：

```bash
# 列出日志文件
ls -lh /usr/log/rfid-logs/

# 实时查看今天日志
tail -f /usr/log/rfid-logs/rfid-$(date +%F).log

# 查看最后 100 行
tail -n 100 /usr/log/rfid-logs/rfid-$(date +%F).log

# 翻页查看今天日志
less /usr/log/rfid-logs/rfid-$(date +%F).log

# 查看某一天日志
less /usr/log/rfid-logs/rfid-2026-06-29.log
```

本地 dev：

```bash
ls -lh logs/
tail -f logs/rfid-$(date +%F).log
less logs/rfid-$(date +%F).log
```

### 10.3 关键词检索

```bash
# 查错误
 grep -n "ERROR" /usr/log/rfid-logs/rfid-$(date +%F).log

# 统计错误次数
 grep -c "ERROR" /usr/log/rfid-logs/rfid-$(date +%F).log

# 查看出现过的异常类型
 grep "Exception" /usr/log/rfid-logs/rfid-$(date +%F).log | sort | uniq -c | sort -nr

# 查看读到的新标签
 grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log

# 统计今天读到多少个新 EPC
 grep -c "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log

# 提取 EPC 并去重
 grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log | grep -oE 'EPC=[A-Fa-f0-9]+' | sort -u

# 查看连接成功和断开记录
 grep -E "读写器连接成功|读写器已断开|心跳无响应|串口.*消失" /usr/log/rfid-logs/rfid-$(date +%F).log

# 查看功率修改记录
 grep "功率已动态修改" /usr/log/rfid-logs/rfid-$(date +%F).log
```

### 10.4 进容器内看日志

```bash
sudo docker exec -it inlay-rfid sh
ls -lh /usr/log/rfid-logs
tail -f /usr/log/rfid-logs/rfid-$(date +%F).log
exit
```

查看串口设备：

```bash
sudo docker exec -it inlay-rfid sh
ls -lh /dev/ttyUSB* /dev/ttyACM* 2>/dev/null
lsusb
exit
```

### 10.5 清空日志

谨慎操作。建议先备份再清空。

```bash
# 备份今天的文件日志
sudo cp /usr/log/rfid-logs/rfid-$(date +%F).log \
  /usr/log/rfid-logs/rfid-$(date +%F).log.bak.$(date +%Y%m%d%H%M%S)

# 清空今天的文件日志
sudo sh -c ': > /usr/log/rfid-logs/rfid-$(date +%F).log'

# 清空 Docker 容器 json 日志，不影响文件日志
sudo truncate -s 0 $(sudo docker inspect --format='{{.LogPath}}' inlay-rfid)
```

删除旧文件日志：

```bash
# 删除 4 天前的日志
sudo bash log-cleanup.sh

# 或安装到 cron.daily
sudo cp log-cleanup.sh /etc/cron.daily/inlay-rfid-log-cleanup
sudo chmod +x /etc/cron.daily/inlay-rfid-log-cleanup
```

### 10.6 关键日志含义

| 日志关键词 | 含义 | 正常处理 | 异常处理 |
| --- | --- | --- | --- |
| `InLayLink RFID Reader - 启动中` | Spring Boot 已进入 RFID 初始化流程 | 等待后续连接日志 | 如果后续无日志，检查应用是否卡住 |
| `当前可用串口` | 程序打印扫描到的系统串口 | 查看是否包含读写器端口 | 没有端口时检查 USB、驱动、容器权限 |
| `识别到 InlayLink 读写器` | 按 USB VID/描述识别到设备 | 等待连接成功 | 识别不到时会回退遍历串口 |
| `读写器连接成功` | SDK 已连接串口 | 可以读取标签或调接口 | 如果反复断开，检查线材、电源、权限 |
| `设置 Query 成功` | Query 参数已下发 | 等待手动开始读取 | 如果失败，检查 SDK 返回和配置枚举 |
| `设置 Q 成功` | Q 值参数已下发 | 等待手动开始读取 | 如果失败，检查 SDK 返回和配置值 |
| `持续盘点已启动，等待标签` | 已开始读取 | 把标签放到天线前 | 若无标签日志，检查标签、功率和天线 |
| `【新标签】 EPC=` | 读到一个新的 EPC | 业务系统可拉取 | 如果数量异常，调整功率或位置 |
| `读写器已断开` | 串口丢失或 SDK 断开 | 等待后台重连 | 检查 USB 是否松动、电源是否稳定 |
| `读写器心跳无响应` | 心跳命令超时 | 程序会断开并重连 | 如果频繁出现，检查设备稳定性 |

---

## 11. 部署与运维

### 11.1 一键部署

```bash
cd ~/robot-rfid/bsd-robot-rfid
sudo bash deploy.sh
```

### 11.2 deploy.sh 参数

```bash
sudo bash deploy.sh start     # 构建镜像 + 启动容器，默认命令
sudo bash deploy.sh restart   # 删除旧容器并启动新容器，不重新构建镜像
sudo bash deploy.sh stop      # 停止并删除容器
sudo bash deploy.sh logs      # 查看实时容器日志
sudo bash deploy.sh status    # 查看容器状态和日志文件
sudo bash deploy.sh build     # 只构建镜像，不启动容器
```

### 11.3 部署脚本关键变量

`deploy.sh` 顶部配置区：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CONTAINER_NAME` | `inlay-rfid` | 容器名 |
| `IMAGE_NAME` | `inlay-rfid` | 镜像名 |
| `IMAGE_TAG` | `latest` | 镜像 tag |
| `SERIAL_DEVICE` | `/dev/ttyUSB0` | 脚本检查用串口；程序实际默认仍用 `auto` 扫描 |
| `LOG_DIR` | `/usr/log/rfid-logs` | 宿主机日志目录 |
| `APP_PORT` | `8080` | 宿主机端口，映射到容器 `8080` |
| `SPRING_PROFILE` | `prod` | 容器内 Spring Profile |
| `MAVEN_IMAGE` | `docker.1ms.run/library/maven:3.8-eclipse-temurin-8` | 构建阶段基础镜像 |
| `RUNTIME_IMAGE` | `docker.1ms.run/library/eclipse-temurin:8-jre-jammy` | 运行阶段基础镜像 |

切回 Docker Hub 官方镜像源：

```bash
MAVEN_IMAGE=maven:3.8-eclipse-temurin-8 \
RUNTIME_IMAGE=eclipse-temurin:8-jre-jammy \
sudo bash deploy.sh
```

### 11.4 容器启动方式

部署脚本最终执行的核心逻辑等价于：

```bash
docker run -d \
  --name inlay-rfid \
  --restart=always \
  --privileged \
  -p 8080:8080 \
  -v /dev:/dev \
  -v /usr/log/rfid-logs:/usr/log/rfid-logs \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e TZ=Asia/Shanghai \
  inlay-rfid:latest
```

说明：

| 参数 | 作用 |
| --- | --- |
| `--restart=always` | Docker 启动后自动拉起容器 |
| `--privileged` | 允许容器访问宿主机串口设备 |
| `-v /dev:/dev` | 挂载整个 `/dev`，支持读写器热插拔后容器内可见 |
| `-v /usr/log/rfid-logs:/usr/log/rfid-logs` | 文件日志持久化到宿主机 |
| `-p 8080:8080` | 宿主机 8080 映射到容器 8080 |

### 11.5 常用 Docker 命令

```bash
# 查看运行中容器
sudo docker ps | grep inlay-rfid

# 查看所有状态容器
sudo docker ps -a | grep inlay-rfid

# 查看实时日志
sudo docker logs -f inlay-rfid

# 重启容器
sudo docker restart inlay-rfid

# 进入容器
sudo docker exec -it inlay-rfid sh

# 查看容器配置
sudo docker inspect inlay-rfid | less

# 删除容器
sudo docker rm -f inlay-rfid

# 查看镜像
sudo docker images | grep inlay-rfid
```

### 11.6 开机自启原理

```text
宿主机开机
   |
   v
systemd 启动 Docker 服务
   |
   v
Docker 根据 --restart=always 拉起 inlay-rfid 容器
   |
   v
Spring Boot 启动
   |
   v
后台重连任务扫描串口
   |
   v
读写器连接成功后应用 Query/Q 配置，等待手动开始读取，不自动修改天线功率
```

确认 Docker 开机自启：

```bash
sudo systemctl is-enabled docker
sudo systemctl enable docker
```

确认容器有自动重启策略：

```bash
sudo docker inspect inlay-rfid --format='{{.HostConfig.RestartPolicy.Name}}'
```

预期输出：

```text
always
```

### 11.7 回滚方式

如果刚上线后不可用，可以先回到上一个 Git 版本再部署：

```bash
# 查看提交记录
git log --oneline -5

# 回到指定提交，例如 abc1234
git reset --hard abc1234

# 重新部署
sudo bash deploy.sh
```

如果只是想临时恢复旧容器，但旧容器已被 `deploy.sh` 删除，则需要重新用旧代码构建镜像。建议生产上线前记录当前 commit：

```bash
git rev-parse --short HEAD
```

---

## 12. 改代码后怎么上线

### 12.1 标准 Git 流程

```bash
# 本地查看改动
git status

# 添加改动
git add .

# 提交
git commit -m "docs: update README"

# 拉取远端最新代码，避免覆盖别人提交
git pull --rebase

# 推送
git push
```

服务器上线：

```bash
ssh 用户名@服务器IP
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh
```

### 12.2 哪些改动需要重建 / 重启

| 改动类型 | 是否需要重建镜像 | 是否需要重启容器 | 命令 |
| --- | --- | --- | --- |
| Java 代码 `src/main/java` | ✅ | ✅ | `sudo bash deploy.sh` |
| 静态页面 `src/main/resources/static/index.html` | ✅ | ✅ | `sudo bash deploy.sh` |
| `application.yml` | ✅ | ✅ | `sudo bash deploy.sh` |
| `logback-spring.xml` | ✅ | ✅ | `sudo bash deploy.sh` |
| `pom.xml` / SDK 依赖 | ✅ | ✅ | `sudo bash deploy.sh` |
| `Dockerfile` | ✅ | ✅ | `sudo bash deploy.sh` |
| `deploy.sh` 中容器端口、挂载路径 | ❌ | ✅ | `sudo bash deploy.sh restart`，必要时先 `build` |
| REST 接口调整的天线功率 | ❌ | ❌ | 立即生效，重启后恢复配置文件值 |
| README 文档 | ❌ | ❌ | 不影响运行 |

### 12.3 上线后验证

```bash
# 容器是否运行
sudo docker ps | grep inlay-rfid

# 健康检查
curl http://localhost:8080/api/rfid/health

# 状态检查
curl http://localhost:8080/api/rfid/status

# 看最近日志
sudo docker logs --tail=100 inlay-rfid
```

---

## 13. 二次开发

### 13.1 想改什么，加在哪里

| 想改什么 | 推荐位置 | 注意事项 |
| --- | --- | --- |
| 新增 REST 接口 | `RfidController` | 入参用 DTO，返回 `Result<T>` 和 VO |
| 新增请求参数 | `entity/dto` | 不建议用 `Map` 接收业务入参 |
| 新增响应字段 | `entity/vo` | 保持字段含义清楚，避免接口直接返回 SDK 对象 |
| 新增 RFID SDK 操作 | `RfidService` | SDK 多为异步回调，必要时用 `CountDownLatch` 包装 |
| 修改启动行为 | `RfidRunner` | 连接成功后的初始化逻辑在 `onConnected()` 中 |
| 新增配置 | `application.yml` + `RfidProperties` | 同步补默认值和注释 |
| 修改页面 | `static/index.html` | 页面直接调用当前域名下 `/api/rfid/*` |
| 写业务日志 | 对应类的 `Logger` | 标签读取日志关键词建议保留，方便现场 grep |

### 13.2 核心类速查

| 类 | 作用 |
| --- | --- |
| `InLayRfidApplication` | Spring Boot 启动入口，启用 `RfidProperties` |
| `RfidProperties` | 绑定 `rfid.*` 配置 |
| `RfidRunner` | 启动后注册连接监听器、启动重连任务 |
| `RfidService` | 连接、重连、心跳、手动盘点、读写标签、功率设置 |
| `ScanService` | 一次性扫描编排：生成 `requestId`，提交扫描任务到线程池 |
| `ScanTask` | 一次性扫描任务：打开/关闭读写器，扫到 EPC 或超时/出错后回调调用方 |
| `ThreadPoolConfig` | 一次性扫描线程池（core=1, max=3, queue=5）+ RestTemplate Bean |
| `RfidController` | REST API |
| `Result<T>` | 统一接口响应 |
| `RfidStatusVO` | 状态接口响应 |
| `TagEventsVO` / `TagEventVO` | 增量标签事件响应 |
| `AntennaPowerDTO` | 统一修改功率请求体 |
| `AntennaPowerBatchVO` | 统一修改所有天线功率响应 |

### 13.3 `RfidService` 常用 API

```java
// 连接与状态
rfidService.connect();
rfidService.isConnected();
rfidService.getActualSerialPort();
rfidService.startReconnectService();

// 读取控制
rfidService.startReading();
rfidService.stopReading();
rfidService.restartReading();
rfidService.isReading();

// 标签事件
rfidService.getStatus();
rfidService.getTagEventsSince(since);
rfidService.clearTags();

// 功率配置
rfidService.setAllAntennaPower(1800); // 所有配置天线统一设置为 18 dBm
rfidService.getAntennaConfigs();

// 单标签读写
rfidService.selectTag(epc);
rfidService.readTag(MemBank.EPC, 2, 6);
rfidService.writeTag(MemBank.EPC, newEpc, 2, 6);
```

### 13.4 在读到新标签时加业务

当前 `startReading()` 中已有新 EPC 处理逻辑：

```java
reader.setInventoryCallback(tag -> {
    lastTagCallbackTime.set(System.currentTimeMillis());
    totalReads.incrementAndGet();
    String epc = tag.getEpc();
    if (epc == null || epc.trim().isEmpty()) {
        return;
    }
    if (seenEpcs.add(epc)) {
        long seq = sequence.incrementAndGet();
        TagEventVO event = new TagEventVO(seq, epc, tag.getRssi(), tag.getAnt(), System.currentTimeMillis());
        tagEvents.addLast(event);
        trimTagEvents();
        log.info("【新标签】 EPC={}  RSSI={} dBm  天线={}", epc, tag.getRssi(), tag.getAnt());
    }
});
```

如要对接业务系统，可以在 `seenEpcs.add(epc)` 成功后新增：

- 写数据库。
- 调第三方 HTTP 接口。
- 推送消息队列。
- 根据天线编号判断入口 / 出口。

注意不要在 SDK 回调中做耗时阻塞操作。耗时业务建议丢到独立线程池或消息队列。

---

## 14. 常见问题排查

### 14.1 服务起不来

**症状**

```text
容器不存在、容器反复重启，或 curl /api/rfid/health 不通。
```

**原因**

- Docker 没启动。
- 端口 `8080` 被占用。
- 镜像构建失败。
- Java 启动异常。

**解决**

```bash
# 看 Docker 状态
sudo systemctl status docker

# 看容器状态
sudo docker ps -a | grep inlay-rfid

# 看启动日志
sudo docker logs --tail=200 inlay-rfid

# 检查端口占用
sudo lsof -i :8080

# 重新部署
sudo bash deploy.sh
```

### 14.2 健康检查正常，但读写器未连接

**症状**

```text
/api/rfid/health 返回 healthy，但 /api/rfid/status 中 connected=false。
```

**原因**

- USB 没插好。
- 容器内看不到 `/dev/ttyUSB*` 或 `/dev/ttyACM*`。
- 串口权限不足。
- 读写器不是当前 SDK 支持的设备。

**解决**

```bash
# 宿主机看串口
ls -lh /dev/ttyUSB* /dev/ttyACM* 2>/dev/null

# 容器内看串口
sudo docker exec -it inlay-rfid sh -c 'ls -lh /dev/ttyUSB* /dev/ttyACM* 2>/dev/null || true'

# 查看 USB 设备
sudo docker exec -it inlay-rfid sh -c 'lsusb || true'

# 查看连接相关日志
sudo docker logs --tail=200 inlay-rfid | grep -E "串口|读写器|连接|Inlay"
```

如果自动扫描不符合现场环境，可以临时指定串口运行，或修改 `application.yml`：

```yaml
rfid:
  serial-port: /dev/ttyACM0
  baud-rate: 115200
```

### 14.3 读写器已连接，但读不到标签

**症状**

```text
/status 中 connected=true、reading=true，但没有“新标签”日志，页面也不显示 EPC。
```

**原因**

- 标签不在天线有效范围内。
- 标签不是 UHF Gen2 860-960MHz。
- 天线没接好，或接错端口。
- 功率太低。
- 现场金属、液体或摆放角度影响读取。
- 读取线程无回调，需要重启盘点。

**解决**

```bash
# 看当前状态
curl http://localhost:8080/api/rfid/status

# 手动重启读取
curl -X POST http://localhost:8080/api/rfid/reading/restart

# 临时提高功率到 20 dBm
curl -X POST http://localhost:8080/api/rfid/antennas/power \
  -H "Content-Type: application/json" \
  -d '{"power":20}'

# 看新标签日志
grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log
```

现场建议：先把标签放到天线正前方 `5~50 cm`，确认能读后再调整距离和功率。

### 14.4 日志没写出来

**症状**

```text
容器能启动，但 /usr/log/rfid-logs 没有日志文件。
```

**原因**

- 宿主机日志目录不存在或无权限。
- 容器没有正确挂载日志目录。
- 当前 profile 不是 `prod`，日志写到了 `./logs`。

**解决**

```bash
# 创建并授权日志目录
sudo mkdir -p /usr/log/rfid-logs
sudo chmod 777 /usr/log/rfid-logs

# 查看挂载
sudo docker inspect inlay-rfid | grep -A 20 Mounts

# 查看容器内日志目录
sudo docker exec -it inlay-rfid sh -c 'ls -lh /usr/log/rfid-logs'

# 查看 profile
sudo docker exec -it inlay-rfid sh -c 'env | grep SPRING_PROFILES_ACTIVE'
```

### 14.5 配置不生效

**症状**

```text
改了 application.yml、功率、串口或 profile，但启动后还是旧值。
```

**原因**

- 改的是本地文件，但服务器没 `git pull`。
- 改了 `application.yml` 后只重启了旧镜像，没有重新构建。
- 环境变量覆盖了 yml。
- REST 接口动态改功率只保存在内存，重启后恢复 yml。

**解决**

```bash
# 服务器拉代码
cd ~/robot-rfid/bsd-robot-rfid
git pull

# 重新构建并启动
sudo bash deploy.sh

# 查看环境变量
sudo docker exec -it inlay-rfid sh -c 'env | sort | grep -E "SPRING|RFID|LOGGING"'

# 查看当前状态和天线功率
curl http://localhost:8080/api/rfid/status
```

### 14.6 重启服务器后服务没自动拉起来

**症状**

```text
服务器重启后，接口不通，docker ps 看不到 inlay-rfid。
```

**原因**

- Docker 服务没有开机自启。
- 容器没有 `--restart=always`。
- 容器启动后立即异常退出。

**解决**

```bash
# 开启 Docker 开机自启
sudo systemctl enable docker
sudo systemctl start docker

# 查看容器，包括已退出的
sudo docker ps -a | grep inlay-rfid

# 查看容器重启策略
sudo docker inspect inlay-rfid --format='{{.HostConfig.RestartPolicy.Name}}'

# 如果策略不对，重新部署
cd ~/robot-rfid/bsd-robot-rfid
sudo bash deploy.sh
```

### 14.7 页面能打开，但按钮操作失败

**症状**

```text
控制台页面打开正常，点击开始读取或调功率提示失败。
```

**原因**

- 读写器未连接。
- 请求被浏览器缓存或网络代理影响。
- 接口返回 `success=false`。
- 后端抛异常。

**解决**

```bash
# 先看状态
curl http://localhost:8080/api/rfid/status

# 直接用 curl 调接口，排除页面问题
curl -X POST http://localhost:8080/api/rfid/reading/start

# 看后端日志
sudo docker logs --tail=200 inlay-rfid
```

### 14.8 Docker 构建拉镜像失败

**症状**

```text
docker build 阶段拉取 maven 或 eclipse-temurin 镜像失败。
```

**原因**

- 服务器访问 Docker Hub 超时。
- 镜像代理不可用。

**解决**

默认 `deploy.sh` 使用镜像代理：

```bash
sudo bash deploy.sh
```

如代理不可用，切回官方源或换成现场可用镜像源：

```bash
MAVEN_IMAGE=maven:3.8-eclipse-temurin-8 \
RUNTIME_IMAGE=eclipse-temurin:8-jre-jammy \
sudo bash deploy.sh
```

---

## 15. RFID 领域基础知识

| 概念 | 解释 | 在本项目中的作用 |
| --- | --- | --- |
| RFID | Radio Frequency Identification，射频识别 | 通过无线电读取标签信息 |
| Tag / 标签 | 贴在物品上的电子标签 | 被读写器读取，通常返回 EPC |
| Reader / 读写器 | 连接天线并执行读取的设备 | 本项目通过 InLayLink SDK 控制它 |
| Antenna / 天线 | 负责发射和接收射频信号 | 本项目按已配置的 ANT0-ANT3 天线端口统一设置功率 |
| EPC | Electronic Product Code，电子产品编码 | 本项目主要读取和展示的标签唯一标识 |
| RSSI | 信号强度 | 标签事件中返回，用于判断读取距离和稳定性 |
| Inventory / 盘点 | 扫描范围内所有标签 EPC | 本项目持续执行的主要动作 |
| Session | 防碰撞会话参数，常见 S0-S3 | 影响重复读取和稳定性 |
| Target | 盘点目标，常见 A/B/AB | 当前默认 `AB` |
| Q 值 | 防碰撞槽数参数 | 标签密集时影响读取效率 |
| MemBank | 标签存储区 | 单标签读写时使用，包含 EPC/TID/USER 等 |

### 15.1 EPC 和标签读取

EPC 通常是一串十六进制字符，例如：

```text
B00110020002409230407738
```

项目中“读到新标签”指的是：当前进程内第一次看到这个 EPC。重复读到同一个 EPC 时，`totalReads` 会增加，但 `uniqueCount` 不会增加，也不会再次进入 `tagEvents`，除非调用 `/api/rfid/tags/clear` 清空记录。

### 15.2 盘点和读写的区别

| 动作 | 类比 | 特点 | 本项目使用情况 |
| --- | --- | --- | --- |
| 盘点 Inventory | 门口数人头 | 快速获取范围内所有标签 EPC | 手动触发持续盘点 |
| 读 Read | 查某个人档案 | 通常需要先选中标签，再读指定存储区 | `RfidService.readTag()` 已封装，当前未开放 REST |
| 写 Write | 修改档案 | 有写入风险，需要明确业务规则 | `RfidService.writeTag()` 已封装，当前未开放 REST |

### 15.3 标签存储区

| 存储区 | 内容 | 是否常用 | 风险 |
| --- | --- | --- | --- |
| `RESERVED` | 访问密码、销毁密码 | 少 | 写错可能影响标签使用 |
| `EPC` | 标签编码 | 是 | 写错会改变业务识别码 |
| `TID` | 厂商烧录的唯一 ID | 是 | 通常只读 |
| `USER` | 用户自定义数据 | 视标签而定 | 需要确认标签是否支持 |

---

## 16. 第三方 SDK / 库速查表

### 16.1 依赖速查

| SDK / 库 | 版本 | 用途 | 配置位置 | 常见问题 |
| --- | --- | --- | --- | --- |
| Spring Boot Web | 2.4.2 | 提供 REST API 和静态页面服务 | `pom.xml` | 端口冲突、profile 不生效 |
| InLayLink RFID SDK | 2.25.05.191 | 控制读写器连接、盘点、读写标签、设置功率 | `lib/` + `pom.xml` | 串口权限、设备不匹配、SDK 回调超时 |
| jSerialComm | SDK 间接使用 | 扫描和访问串口 | SDK 依赖 | 容器内看不到 `/dev/tty*` |
| Logback | Spring Boot 默认 | 控制台和文件日志 | `logback-spring.xml` | 日志目录权限、归档保留天数 |
| Lombok | 项目依赖 | 简化 DTO、VO 和配置类的 getter/setter | `pom.xml` | IDE 未安装插件时可能提示异常 |

### 16.2 InLayLink SDK 类速查

| 类 / 枚举 | 用途 | 本项目使用位置 |
| --- | --- | --- |
| `Reader` | 读写器操作入口 | `RfidService.reader` |
| `ReaderImpl` | 创建 Reader 实例 | `ReaderImpl.create()` |
| `SerialPortHandle` | 串口连接参数 | `connect(serialPort, baudRate)` |
| `AntConfig` | 天线启用和功率配置 | `applyQueryAndQConfig()`、`setAllAntennaPower()` |
| `QueryConfig` | Session / Target 配置 | `applyQueryAndQConfig()` |
| `QConfig` | Q 值配置 | `applyQueryAndQConfig()` |
| `InventoryTag` | 盘点回调标签 | `startReading()`、`inventoryFor()` |
| `MemBank` | 标签存储区枚举 | `readTag()`、`writeTag()` |
| `ReadConfig` | 读标签配置 | `readTag()` |
| `WriteConfig` | 写标签配置 | `writeTag()`、`writeTagBlock()` |
| `SelectConfig` | 选择指定标签 | `selectTag()` |
| `Session` | 盘点 Session 枚举 | `RfidProperties.Query` |
| `Target` | 盘点 Target 枚举 | `RfidProperties.Query` |
| `Success` / `Failure` | SDK 操作回调结果 | 多数 SDK 方法回调 |

### 16.3 SDK 回调模型

InLayLink SDK 多数方法不是直接返回结果，而是通过成功 / 失败回调返回：

```java
reader.startInventory(
    success -> log.info("startInventory 成功"),
    failure -> log.warn("startInventory 失败: {}", failure)
);
```

本项目在需要同步等待结果的地方使用 `CountDownLatch` 包装，例如设置功率：

```java
CountDownLatch latch = new CountDownLatch(1);
AtomicBoolean ok = new AtomicBoolean(false);

reader.setAntConfig(antConfig,
    success -> {
        ok.set(true);
        latch.countDown();
    },
    failure -> {
        latch.countDown();
    }
);

latch.await(5000, TimeUnit.MILLISECONDS);
return ok.get();
```

---

| 项 | 内容 |
| --- | --- |
| 项目名 | BSD Robot RFID / InLay-RFID |
| 仓库地址 | https://github.com/cycyu7-pixel/bsd-robot-rfid |
| 业务方/所属团队 | BSD |
| 技术栈 | Java 8 + Spring Boot 2.4.2 + InLayLink RFID SDK 2.25.05.191 + Docker |
| 部署环境 | Linux x86_64 / Docker；本地可用 Windows、macOS、Linux 调试 |
| README 维护建议 | 代码、配置、接口、部署脚本、日志路径或现场运维方式变化时同步更新 |

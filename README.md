# BSD Robot RFID

> RFID 标签识别后端服务
>
> 基于 InLayLink 读写器，持续盘点 RFID 标签，提供日志输出与 REST 接口。
> 无人值守：插上读写器自动连接、断电掉线自动重连、服务器重启自动恢复。

---

## 目录

- [1. 这个项目做什么](#1-这个项目做什么)
- [2. 工作原理](#2-工作原理)
- [3. 快速开始](#3-快速开始)
- [4. 工程结构](#4-工程结构)
- [5. 配置说明](#5-配置说明)
- [6. REST 接口](#6-rest-接口)
- [7. 天线功率调节](#7-天线功率调节)
- [8. 日志查看](#8-日志查看)
- [9. Docker 部署与运维](#9-docker-部署与运维)
- [10. 改代码后怎么上线](#10-改代码后怎么上线)
- [11. 二次开发](#11-二次开发)
- [12. 常见问题排查](#12-常见问题排查)
- [13. RFID 基础知识](#13-rfid-基础知识)
- [14. SDK 速查表](#14-sdk-速查表)

---

## 1. 这个项目做什么

 **InLayLink RFID 读写器**对话，做三件事：

| 它在做的事 | 结果 |
|--------|--------|
| 持续监听天线范围内的 RFID 标签 | 把读到的标签 EPC 写进日志 |
| 自动管理读写器连接生命周期 | 拔插读写器、重启服务器都能自动恢复 |
| 对外提供 REST 接口 | 可以实时调节天线功率、查健康状态 |

简单说，它是一个**永远开着的扫描器**。把贴了 RFID 标签的物品（衣服、零件、纸箱等）放到天线前面，它就把标签里存的编号读出来，记下来给业务系统。

---

## 2. 工作原理

```
   ┌───────────┐         ┌─────────────┐
   │  RFID 标签 │  ──→    │   读写器     │  ──── USB ────┐
   │ (贴在物品上)│  电磁场 │ (InLayLink) │               │
   └───────────┘         └─────────────┘               ▼
                                                ┌─────────────┐
                                                │   本服务     │
                                                │ (Docker 容器)│
                                                └──────┬──────┘
                                                       │
                                                       ├──→ 日志文件
                                                       │    /usr/log/rfid-logs/
                                                       │
                                                       └──→ REST 接口
                                                            :8080/api/rfid/*
```

启动到运行的全过程：

1. 服务器开机 → Docker 自动启动容器
2. 自动扫描 USB 串口，按 `VID:PID = 2fe3:0100` 找到读写器
3. 应用配置（天线功率、Session、Q 值）
4. 读写器持续发射电磁波，唤醒范围内的标签
5. 读到标签 → 实时写入日志（去重）→ 业务系统可读
6. 读写器拔掉或断电 → 后台等待 → 重新插上 → 自动恢复盘点

---

## 3. 快速开始

### 看系统是否正常运行

浏览器打开 `http://服务器IP:8080/api/rfid/health`，返回 `healthy` 就是正常。

或者命令行：
```bash
curl http://服务器IP:8080/api/rfid/health
```

### 本地跑起来

```bash
mvn clean package -DskipTests
java -jar target/InLay-RFID-1.0.0.jar
```

日志会同时输出到控制台和 `./logs/rfid-yyyy-MM-dd.log`。

### Docker 部署（生产环境推荐）

```bash
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh
```

第一次会自动设置 Docker 开机自启、创建日志目录、构建镜像、启动容器。之后服务器重启会自动拉起容器，不需要手工操作。

---

## 4. 工程结构

```
bsd-robot-rfid/
├── lib/                                    InLayLink SDK 本地 jar 仓库
├── src/main/java/com/cyu/inlayrfid/
│   ├── InLayRfidApplication.java           启动入口
│   ├── config/RfidProperties.java          读取 yml 配置到 Java 对象
│   ├── service/RfidService.java            核心：连接 / 重连 / 盘点 / 读写
│   ├── runner/RfidRunner.java              启动时：注册回调 + 启动后台重连
│   └── controller/RfidController.java      REST 接口（健康检查 / 改功率）
├── src/main/resources/
│   ├── application.yml                     主配置（dev / prod 双 profile）
│   └── logback-spring.xml                  日志配置（按日期归档，保留 3 天）
├── Dockerfile                              多阶段构建
├── deploy.sh                               一键部署脚本
├── log-cleanup.sh                          日志兜底清理（可装到 crontab）
└── pom.xml
```

各模块职责：

| 模块 | 职责 | 改这里的场景 |
|------|-----------|------------|
| `RfidService` | 跟读写器对话 | 加新的 SDK 操作（读 TID、写标签等） |
| `RfidRunner` | 启动后做什么 | 改持续盘点的行为、加业务回调 |
| `RfidController` | HTTP 接口 | 加新接口给前端 |
| `RfidProperties` + `application.yml` | 配置 | 加新配置项 |
| `Dockerfile` + `deploy.sh` | 部署 | 改镜像、改启动参数 |
| `logback-spring.xml` | 日志 | 改日志级别、保留天数 |

---

## 5. 配置说明

### `application.yml` 全字段

```yaml
server:
  port: 8080

spring:
  profiles:
    active: dev               # dev = 本地, prod = Docker

logging:
  file:
    path: ./logs              # 日志目录（prod 覆盖成 /usr/log/rfid-logs）

rfid:
  serial-port: auto           # auto 自动识别，或写死 /dev/ttyACM2 / COM3
  baud-rate: 115200           # auto 时自动尝试 115200/57600/38400/9600

  reconnect:
    enabled: true
    interval-seconds: 5
    max-attempts: 0           # 0 = 无限重试

  antennas:
    - id: 0
      power: 2000             # 0.1 dBm，2000 = 20 dBm
    - id: 1
      power: 2000
    - id: 2
      power: 2000
    - id: 3
      power: 2000

  query:
    session: S0
    target: AB

  q:
    init: 5
    max: 9
    min: 0
```

### 环境变量覆盖（Docker 用）

```bash
docker run -e RFID_SERIAL_PORT=/dev/ttyACM0 \
           -e RFID_RECONNECT_INTERVAL_SECONDS=3 \
           ...
```

### Profile 切换

```bash
java -jar app.jar --spring.profiles.active=prod
# 或
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```

| Profile | 日志路径 | 串口 |
|---------|---------|------|
| `dev`（默认） | `./logs` | `auto` |
| `prod` | `/usr/log/rfid-logs` | `auto` |

---

## 6. REST 接口

所有接口前缀：`http://服务器IP:8080/api/rfid`

### 6.1 健康检查

```http
GET /api/rfid/health
```

返回纯文本 `healthy`。

```bash
curl http://localhost:8080/api/rfid/health
```

### 6.2 修改单根天线功率

```http
POST /api/rfid/antennas/{antId}/power
Content-Type: application/json

{ "power": 18 }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `antId`（路径） | int | 天线 ID，0 / 1 / 2 / 3 |
| `power`（body） | int | 0-33 整数，单位 dBm |

返回：
```json
{
  "antId": 1,
  "success": true,
  "powerDbm": 18,
  "message": "修改成功"
}
```

示例：
```bash
curl -X POST http://localhost:8080/api/rfid/antennas/1/power \
     -H "Content-Type: application/json" \
     -d '{"power": 18}'
```

### 6.3 修改所有天线功率（统一）

```http
POST /api/rfid/antennas/power
Content-Type: application/json

{ "power": 18 }
```

返回：
```json
{
  "results": {"0": true, "1": true, "2": true, "3": true},
  "powerDbm": 18,
  "success": true
}
```

### 6.4 错误响应

| 场景 | 响应 |
|------|------|
| 读写器未连接 | `{"success": false, "message": "读写器未连接"}` |
| power 字段缺失 | `{"success": false, "message": "缺少 power 字段"}` |
| power 超范围 | `{"success": false, "message": "power 范围 0~33，当前: 50"}` |
| power 不是数字 | `{"success": false, "message": "power 必须是 0~33 的整数"}` |

---

## 7. 天线功率调节

运行时通过 REST 接口实时修改，**不需要重启服务**。

### 功率参考表

| 功率 | 适用场景 | 读取距离参考 |
|------|---------|------------|
| 10 dBm | 标签贴在天线上 | < 10 cm |
| 15 dBm | 桌面近距离 | 10-30 cm |
| **18 dBm** | **常规扫描** | **30-80 cm** |
| 20 dBm | 中距离 | 80 cm-1.5 m |
| 25 dBm | 远距离 | 1.5-3 m |
| 30 dBm | 最远 | 3 m+ |
| 33 dBm | 最大值（不推荐） | 可能干扰隔壁标签 |

### 调整原则

- 读不到 / 读得少 → 调高
- 读到太多无关标签 → 调低

### 命令示例

```bash
# 所有天线统一改成 18 dBm
curl -X POST http://localhost:8080/api/rfid/antennas/power \
     -H "Content-Type: application/json" \
     -d '{"power": 18}'

# 只改 1 号天线为 25 dBm
curl -X POST http://localhost:8080/api/rfid/antennas/1/power \
     -H "Content-Type: application/json" \
     -d '{"power": 25}'
```

---

## 8. 日志查看

日志同时输出到控制台（`docker logs`）和文件（`/usr/log/rfid-logs/rfid-yyyy-MM-dd.log`，保留 3 天）。

### 8.1 容器控制台日志

```bash
# 看最近 30 行
sudo docker logs --tail 30 inlay-rfid

# 实时跟随（Ctrl+C 退出查看）
sudo docker logs -f inlay-rfid

# 实时跟随 + 只看最近 50 行起
sudo docker logs --tail 50 -f inlay-rfid

# 看启动后 10 分钟内的日志
sudo docker logs --since 10m inlay-rfid

# 看某个时间点之后的日志
sudo docker logs --since "2026-06-23T00:00:00" inlay-rfid

# 用 less 翻页看（按 q 退出，/ 搜索）
sudo docker logs inlay-rfid 2>&1 | less
```

### 8.2 日志文件（按日期归档）

```bash
# 列出所有日志文件
ls -lh /usr/log/rfid-logs/

# 实时跟随今天的日志
tail -f /usr/log/rfid-logs/rfid-$(date +%F).log

# 看昨天的日志
cat /usr/log/rfid-logs/rfid-$(date -d "yesterday" +%F).log

# 用 less 翻页看
less /usr/log/rfid-logs/rfid-$(date +%F).log

# 看最后 100 行
tail -n 100 /usr/log/rfid-logs/rfid-$(date +%F).log

# 看头 100 行（启动日志）
head -n 100 /usr/log/rfid-logs/rfid-$(date +%F).log
```

### 8.3 关键词检索

```bash
# 统计今天读到了多少个不同标签
grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log | wc -l

# 列出今天读到的所有 EPC（去重）
grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log | \
    grep -oE 'EPC=[A-F0-9]+' | sort -u

# 查找某个特定 EPC 出现过几次（所有天的日志）
grep "B00110020002409230407738" /usr/log/rfid-logs/rfid-*.log

# 看所有错误 / 警告
grep -iE "error|warn|失败|断开|异常" /usr/log/rfid-logs/rfid-$(date +%F).log

# 看读写器连接 / 断开记录
grep -E "连接成功|已断开" /usr/log/rfid-logs/rfid-$(date +%F).log

# 统计今天功率被改了几次
grep "功率已动态修改" /usr/log/rfid-logs/rfid-$(date +%F).log | wc -l

# 看启动时串口扫描结果
grep -A 5 "当前可用串口" /usr/log/rfid-logs/rfid-$(date +%F).log
```

### 8.4 进容器内看

```bash
sudo docker exec -it inlay-rfid sh
ls /usr/log/rfid-logs/
cat /usr/log/rfid-logs/rfid-*.log
exit
```

### 8.5 清空日志

```bash
# 清 Docker 容器日志（不影响日志文件）
sudo truncate -s 0 $(sudo docker inspect --format='{{.LogPath}}' inlay-rfid)

# 清所有归档日志
sudo rm /usr/log/rfid-logs/rfid-*.log
```

### 8.6 通过 deploy.sh 看日志

```bash
sudo bash deploy.sh logs        # 等价于 docker logs -f
sudo bash deploy.sh status      # 容器状态 + 日志文件列表
```

### 8.7 关键日志含义

```
========================================
  InLayLink RFID Reader - 启动中...                    程序启动
========================================
识别到 InlayLink 读写器: ttyACM2                        找到读写器
读写器连接成功: ttyACM2 @ 115200                        建立连接
天线 ANT0 配置成功: 20.0 dBm                            功率设置完成
持续盘点已启动，等待标签...                               开始读标签
【新标签】 EPC=... RSSI=-58.0 dBm  天线=2                新标签被扫到
读写器已断开 (ttyACM2), 后台持续重连中...                 设备被拔或断电
```

---

## 9. Docker 部署与运维

### 一键部署

```bash
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh
```

`deploy.sh` 会自动：检查 Docker → 设置开机自启 → 创建日志目录 → 构建镜像 → 启动容器（`--restart=always`）。

### 常用命令

```bash
sudo bash deploy.sh             # 重新构建 + 启动
sudo bash deploy.sh restart     # 只重启容器，不重新构建
sudo bash deploy.sh stop        # 停止并删除容器
sudo bash deploy.sh logs        # 实时日志
sudo bash deploy.sh status      # 容器状态 + 日志文件列表
sudo bash deploy.sh build       # 只构建镜像，不启动
```

### 直接用 docker 命令

```bash
sudo docker ps | grep inlay-rfid               # 容器状态
sudo docker restart inlay-rfid                 # 重启容器
sudo docker rm -f inlay-rfid                   # 删除容器
sudo docker exec -it inlay-rfid sh             # 进入容器
sudo docker inspect inlay-rfid | less          # 容器配置
```

### 开机自启原理

宿主机开机 → systemd 启动 Docker 服务 → Docker 拉起 `--restart=always` 的容器 → inlay-rfid 启动 → Java 进程自动扫描 USB → 找到读写器 → 开始盘点。

三件套（缺一不可）：
- `--restart=always`（deploy.sh 已自动设置）
- `sudo systemctl enable docker`（deploy.sh 第一次会自动执行）
- 程序内部的后台重连（代码已实现）

---

## 10. 改代码后怎么上线

标准流程：本地改 → Git → 服务器同步

```bash
# 本地（Mac）
git add .
git commit -m "feat: ..."
git push

# 服务器（Linux）
ssh bosideng@服务器IP
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh          # 重新构建镜像 + 启动
```

### 必须重新构建镜像的改动

- 改了 Java 代码（`src/`）、`application.yml`、`pom.xml`、`Dockerfile`
→ 用 `sudo bash deploy.sh`

### 只重启就行

- 改了 `deploy.sh` 环境变量、挂载路径
→ 用 `sudo bash deploy.sh restart`

### 完全不需要重启

- 通过 REST 接口改的天线功率 → 立即生效

---

## 11. 二次开发

### 业务逻辑加在哪

```java
rfidService.setInventoryCallback(tag -> {
    String epc = tag.getEpc();
    if (seenEpcs.add(epc)) {
        log.info("【新标签】 EPC={}", epc);
        // 在这里加业务（写库、推接口、报警）
    }
});
```

### `RfidService` 核心 API

```java
// 持续盘点
rfidService.setInventoryCallback(tag -> { ... });
rfidService.startInventory(onSuccess, onFailure);
rfidService.stopInventory(onSuccess, onFailure);

// 限时盘点
rfidService.inventoryFor(10);     // 阻塞 10 秒

// 状态与配置
rfidService.isConnected();
rfidService.setAntennaPower(0, 2000);
rfidService.getActualSerialPort();

// 单标签读写
rfidService.selectTag(epc);
rfidService.readTag(MemBank.EPC, 2, 6);
rfidService.writeTag(MemBank.EPC, newEpc, 2, 6);

// 连接事件
rfidService.addConnectionListener(new ConnectionListener() {
    public void onConnected() { ... }
    public void onDisconnected() { ... }
});
```

---

## 12. 常见问题排查

### 读不到标签

① 系统在跑吗？`sudo docker ps | grep inlay-rfid`
② 读写器连上了吗？`sudo docker logs --tail 5 inlay-rfid` 最后几行应包含「读写器连接成功」
③ 标签 OK 吗？UHF Gen2 协议、放在天线正前方 5-50 cm、不被金属覆盖
④ 功率够吗？默认 20 dBm，见第 7 节

### Docker 容器看不到串口

症状：`docker exec inlay-rfid ls /dev/ttyACM*` 报 No such file
解决：`deploy.sh` 已用 `--privileged + -v /dev:/dev` 处理。

### 日志没写到文件

```bash
sudo docker inspect inlay-rfid | grep -A 3 Mounts   # 确认挂载
sudo chmod 777 /usr/log/rfid-logs                   # 确认权限
```

### 自动扫描扫不到读写器

日志一直 `connect failed`。直接指定：
```bash
ls /dev/ttyACM*
-e RFID_SERIAL_PORT=/dev/ttyACM2
```

### 改了 yml 不生效

Spring Boot 启动时才读 yml，运行中不重读。需要 `sudo bash deploy.sh` 重建。

### 服务器重启后服务没起来

```bash
sudo systemctl enable docker
```

### 天线功率改完没反应

先看日志确认实际工作的天线编号：
```bash
grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log | head -3
# 输出 ... 天线=2 → 改 2 号天线
curl -X POST http://localhost:8080/api/rfid/antennas/2/power \
     -d '{"power": 18}' -H 'Content-Type: application/json'
```

---

## 13. RFID 基础知识

### 什么是 RFID

射频识别，一张电子标签 + 一台读写器，通过电磁波通信。

- 标签（Tag）：贴在物品上的小芯片，存唯一编号 EPC
- 读写器（Reader）：发射电磁波唤醒标签，读取 EPC
- 天线：电磁波从这里发出

### 什么是 EPC

电子产品编码，标签的唯一身份证。96 位（12 字节），24 个十六进制字符。

```
B00110020002409230407738
└──┘└──┘└──┘└──────────┘
 厂商 类型 类别   序列号
```

### 盘点 vs 读

| | 盘点（Inventory） | 读（Read） |
|---|------------------|-----------|
| 像什么 | 门口数人头 | 查某个人的档案 |
| 速度 | 快，一秒几十个 | 慢，一次一个 |
| 目标 | 读所有标签 | 必须先选中一个 |
| 读什么 | 只读 EPC | 读 EPC/TID/USER |
| 场景 | 入库盘点、过门检测 | 读批次号、写防伪码 |

### 标签的 4 个存储区

| 存储区 | 内容 | 能写吗 |
|--------|------|--------|
| RESERVED | 密码 | 可写（危险） |
| EPC | 唯一识别码 | 可写 |
| TID | 厂商烧死的 ID | 只读 |
| USER | 用户数据（批次号等） | 可写 |

### 常用参数

| 概念 | 说明 | 默认值 |
|------|------|--------|
| 天线 ANT0-3 | 最多接 4 根天线，独立功率 | 全开，20 dBm |
| Session S0-S3 | 防碰撞会话 | S0 |
| Target A/B/AB | 盘存目标 | AB（自动循环） |
| Q 值 | 盘点槽数，影响吞吐 | 5 |

---

## 14. SDK 速查表

### 操作类

| 类 | 作用 | 代码位置 |
|---|------|---------|
| `Reader` | 读写器对象总入口 | `RfidService.reader` |
| `ReaderImpl` | `Reader.create()` 创建实例 | `connect()` |
| `SerialPortHandle` | 串口连接器 | `connect()` |
| `AntConfig` | 天线配置 | `applyDefaultConfig` / `setAntennaPower` |
| `QueryConfig` | Session/Target 配置 | `applyDefaultConfig` |
| `QConfig` | Q 值配置 | `applyDefaultConfig` |
| `SelectConfig` | 标签过滤 | `selectTag()` |
| `ReadConfig` | 读标签参数 | `readTag()` |
| `WriteConfig` | 写标签参数 | `writeTag()` |
| `Consumer<T>` | 异步回调 | 所有 SDK 方法的最后参数 |

### 返回数据类

| 类 | 含义 | 主要字段 |
|---|------|---------|
| `InventoryTag` | 盘点到的标签 | `epc`、`rssi`、`ant` |
| `ReadTag` | 读到的标签数据 | 数据内容 |
| `WrittenTag` | 写标签结果 | 是否成功 |
| `Success` / `Failure` | 操作结果 | 状态 / 错误信息 |

### 枚举类

| 类 | 取值 | 含义 |
|---|------|------|
| `MemBank` | `RESERVED` / `EPC` / `TID` / `USER` | 存储区 |
| `Select` | `SELECT_ALL` / `SELECT_ASSERTED` 等 | 过滤模式 |
| `Session` | `S0` / `S1` / `S2` / `S3` | 防碰撞会话 |
| `Target` | `A` / `B` / `AB` | 盘存目标 |

### Consumer 异步回调

SDK 所有硬件操作都用回调而非返回值：

```java
reader.doSomething(
    successConsumer,   // 成功时 SDk 调它
    failureConsumer    // 失败时 SDk 调它
);
```

代码中用 `CountDownLatch` 将异步包装成同步：

```java
public ReadTag readTag(...) {
    CountDownLatch latch = new CountDownLatch(1);
    reader.readTag(config,
        success -> { result.set(success); latch.countDown(); },
        failure -> { latch.countDown(); });
    latch.await(5, TimeUnit.SECONDS);
    return result.get();
}
```

---

## 项目信息

| 项 | 值                                                           |
|---|-------------------------------------------------------------|
| 仓库 | https://github.com/cycyu7-pixel/bsd-robot-rfid              |
| 业务方 | BSD                                                         |
| 技术栈 | Java 8 + Spring Boot 2.4 + InLayLink RFID SDK V2.2 + Docker |
| 部署 | Linux x86_64 / Docker                                       |

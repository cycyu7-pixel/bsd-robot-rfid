# BSD Robot RFID

> 博斯登（BSD）机器人 RFID 标签识别后端服务
>
> 基于 InLayLink 读写器，持续盘点 RFID 标签，提供日志输出与 REST 接口。
> 无人值守：插上读写器自动连接、断电掉线自动重连、服务器重启自动恢复。

---

## 📑 目录

- 👥 **给运营 / 业务人员**
  - [一、这是什么？能干什么？](#一这是什么能干什么)
  - [二、它是怎么工作的](#二它是怎么工作的)
  - [三、怎么判断系统是否正常](#三怎么判断系统是否正常)
  - [四、读不到标签怎么办（自助排查）](#四读不到标签怎么办自助排查)
  - [五、怎么调节天线功率](#五怎么调节天线功率)
- 🛠️ **给开发 / 运维**
  - [六、工程模块概览](#六工程模块概览)
  - [七、Docker 部署 / 日常运维](#七docker-部署--日常运维)
  - [八、日志查看大全](#八日志查看大全)
  - [九、REST 接口文档](#九rest-接口文档)
  - [十、配置项说明](#十配置项说明)
  - [十一、改代码后怎么上线](#十一改代码后怎么上线)
  - [十二、二次开发指引](#十二二次开发指引)
- 📚 **附录**
  - [十三、RFID 基础知识](#十三rfid-基础知识)
  - [十四、SDK 速查表](#十四sdk-速查表)
  - [十五、常见问题 FAQ](#十五常见问题-faq)

---

# 👥 给运营 / 业务人员

## 一、这是什么？能干什么？

这是一个**后台服务**，专门跟博斯登机器人/产线上的 **RFID 读写器**对话，干三件事：

| 它在做 | 给谁用 |
|--------|--------|
| 一直监听天线范围内的 RFID 标签 | 业务方（仓库系统、产线系统） |
| 把读到的标签 EPC 号写进日志 / 推给业务接口 | 数据分析、追溯系统 |
| 提供 Web 接口给前端调节天线功率 | 现场调试、运维人员 |

**简单理解**：它就像一个**永远开着的扫描器**。你把贴了 RFID 标签的物品（衣服、零件、纸箱…）放到天线前面，它就把标签里存的编号读出来，并记下来给业务系统。

---

## 二、它是怎么工作的

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
                                                       │    (/usr/log/rfid-logs/)
                                                       │
                                                       └──→ REST 接口
                                                            (供前端 / 业务系统)
```

**工作流程**：

1. 服务器开机 → Docker 自动启动这个服务
2. 服务自动找到 USB 上的读写器，连上
3. 读写器持续发射电磁波，唤醒范围内的标签
4. 读到标签 → 写入日志 → 推送给业务（通过日志/接口）
5. 拔掉读写器 → 服务等待 → 重新插上 → 自动恢复（**不需要手工重启**）

---

## 三、怎么判断系统是否正常

### 最简单的方法：调健康检查接口

在能访问服务器的电脑上打开浏览器或终端：

```
http://服务器IP:8080/api/rfid/health
```

**正常**返回：
```
healthy
```

**不正常**：返回 404 / 500 / 超时 / 连接拒绝 → 服务挂了。

### 进一步：看日志

```bash
ssh bosideng@服务器IP
sudo docker logs --tail 30 inlay-rfid
```

**正常**应该看到：

```
========================================
  InLayLink RFID Reader - 启动中...
========================================
识别到 InlayLink 读写器: ttyACM2 [Nordic InlayLink_RFID]
读写器连接成功: ttyACM2 @ 115200
--- 读写器已连接，初始化参数 ---
天线 ANT0 配置成功: 20.0 dBm
持续盘点已启动，等待标签...
【新标签】 EPC=B00110020002409230407738  RSSI=-58.0 dBm  天线=2
```

**有问题**会看到：

```
读写器已断开 (ttyACM2), 后台持续重连中...
```

→ 检查 USB 线、读写器电源、再插紧一点。

---

## 四、读不到标签怎么办（自助排查）

按顺序排查：

### ① 系统在跑吗？

```bash
sudo docker ps | grep inlay-rfid
```

看到 `Up X minutes` = 在跑。**没看到？** → 执行 `sudo bash deploy.sh` 启动。

### ② 读写器连上了吗？

```bash
sudo docker logs --tail 5 inlay-rfid
```

最后几行应该有「读写器连接成功」+「持续盘点已启动」。

**没连上？**
- 检查 USB 数据线（用充电线不行，必须是数据线）
- 检查读写器指示灯是否点亮
- 拔掉重插

### ③ 标签 OK 吗？

- 标签是不是 **UHF Gen2 协议**？（860–960 MHz，最常见的 RFID 服装/物流标签）
- 标签放在天线**正前方** 5~50 cm 内
- 标签有没有被金属覆盖、是不是贴在金属表面（金属屏蔽电磁波）

### ④ 功率够吗？

默认天线功率 = **20 dBm**（适合桌面近距离）。需要远距离请调到 25~30 dBm（见 [第五节](#五怎么调节天线功率)）。

### ⑤ 都不行就找开发

把 `sudo docker logs --tail 50 inlay-rfid` 输出截图发给开发。

---

## 五、怎么调节天线功率

### 实时调节（推荐，不需要重启）

#### 把所有天线统一改成 18 dBm
```bash
curl -X POST http://服务器IP:8080/api/rfid/antennas/power \
     -H "Content-Type: application/json" \
     -d '{"power": 18}'
```

#### 只改 1 号天线为 25 dBm
```bash
curl -X POST http://服务器IP:8080/api/rfid/antennas/1/power \
     -H "Content-Type: application/json" \
     -d '{"power": 25}'
```

### 功率参考表

| 功率 | 适用场景 | 读取距离参考 |
|------|---------|------------|
| 10 dBm | 标签贴在天线上 | < 10 cm |
| 15 dBm | 桌面近距离 | 10~30 cm |
| **18 dBm** | **常规扫描** | **30~80 cm** |
| 20 dBm | 中距离 | 80 cm~1.5 m |
| 25 dBm | 远距离 | 1.5~3 m |
| 30 dBm | 最远 | 3 m+ |
| 33 dBm | 最大值（不推荐） | 信号会过强干扰隔壁标签 |

**调整原则**：
- **读不到 / 读得少** → 调高
- **读到太多无关标签 / 读到隔壁柜子的** → 调低

---

# 🛠️ 给开发 / 运维

## 六、工程模块概览

```
bsd-robot-rfid/
├── lib/                                    InLayLink SDK 本地 jar 仓库（必需）
├── src/main/java/com/cyu/inlayrfid/
│   ├── InLayRfidApplication.java           启动入口
│   ├── config/RfidProperties.java          读取 yml 配置到 Java 对象
│   ├── service/RfidService.java            ⭐ 核心：连接 / 重连 / 盘点 / 读写
│   ├── runner/RfidRunner.java              启动时跑：注册回调 + 启动重连
│   └── controller/RfidController.java      REST 接口（健康检查 / 改功率）
├── src/main/resources/
│   ├── application.yml                     主配置（dev / prod 双 profile）
│   └── logback-spring.xml                  日志配置（按日期归档 / 保留 3 天）
├── Dockerfile                              多阶段构建
├── deploy.sh                               ⭐ 一键部署脚本
├── log-cleanup.sh                          日志兜底清理（可装到 crontab）
└── pom.xml
```

### 各模块职责一句话总结

| 模块 | 一句话职责 | 你最常改这里因为… |
|------|-----------|------------|
| `RfidService` | 跟读写器对话 | 加新的 SDK 操作（读 TID、写标签…） |
| `RfidRunner` | 启动后第一件事干啥 | 改持续盘点的行为 |
| `RfidController` | HTTP 接口 | 加新接口给前端 |
| `RfidProperties` + `application.yml` | 配置 | 加新配置项 |
| `Dockerfile` + `deploy.sh` | 部署 | 改镜像、改启动参数 |
| `logback-spring.xml` | 日志 | 改日志级别、保留天数 |

### 系统数据流

```
[启动]
  └─ RfidRunner.run()
       ├─ 注册 ConnectionListener (连上后自动开始持续盘点)
       └─ 启动后台重连服务

[运行中 - 已连接]
  读写器电磁波 → 标签响应 → SDK 收到回调
                              ↓
                          RfidService.setInventoryCallback
                              ↓
                          【新标签】日志 / 业务回调

[运行中 - 拔掉读写器]
  心跳检测失败 → disconnect() → 后台线程每 5 秒重试 connect()
                                          ↓
                                  插回去 → 连上 → 重新启动盘点
```

---

## 七、Docker 部署 / 日常运维

### 一键部署（首次 / 修改代码后）

```bash
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh
```

`deploy.sh` 会自动：
1. 检查 Docker 已安装
2. 设置 Docker 服务开机自启
3. 创建日志目录 `/usr/log/rfid-logs`
4. 构建镜像（约 2~5 分钟）
5. 停掉旧容器，启动新容器（`--restart=always` 开机自启）

### 常用命令

```bash
sudo bash deploy.sh             # 重新构建 + 启动（代码改了用这个）
sudo bash deploy.sh restart     # 只重启容器，不重新构建（仅改 yml/Dockerfile 才需要重建）
sudo bash deploy.sh stop        # 停止并删除容器
sudo bash deploy.sh logs        # 实时日志
sudo bash deploy.sh status      # 看容器状态 + 日志文件列表
sudo bash deploy.sh build       # 只构建镜像，不启动
```

### 直接用 docker 命令

```bash
sudo docker ps | grep inlay-rfid               # 看容器在不在
sudo docker restart inlay-rfid                 # 重启容器（不重建镜像）
sudo docker rm -f inlay-rfid                   # 删除容器
sudo docker exec -it inlay-rfid sh             # 进入容器内部
sudo docker inspect inlay-rfid | less          # 看容器详细配置
```

### 开机自启原理

```
宿主机开机
  └─ systemd 启动 Docker 服务（已 enable）
     └─ Docker 自动拉起所有 --restart=always 的容器
        └─ inlay-rfid 容器启动
           └─ Java 进程后台扫描 USB → 找到 InlayLink → 开始盘点
```

**三件套**（缺一不可）：
- `--restart=always` ← `deploy.sh` 已自动设置
- `sudo systemctl enable docker` ← `deploy.sh` 第一次会自动执行
- 程序内部的后台重连 ← 代码已实现，读写器拔插能自动恢复

---

## 八、日志查看大全

### 8.1 容器控制台日志

```bash
# 看最近 30 行
sudo docker logs --tail 30 inlay-rfid

# 实时跟随（Ctrl+C 退出查看，不会停掉容器）
sudo docker logs -f inlay-rfid

# 实时跟随 + 只看最近 50 行起
sudo docker logs --tail 50 -f inlay-rfid

# 看启动后 10 分钟内的日志
sudo docker logs --since 10m inlay-rfid

# 看某个时间点之后的日志
sudo docker logs --since "2026-06-23T00:00:00" inlay-rfid

# 看日志的最后 100 行
sudo docker logs --tail 100 inlay-rfid

# 用 less 翻页看（按 q 退出，/ 搜索）
sudo docker logs inlay-rfid 2>&1 | less
```

### 8.2 日志文件（按日期归档）

日志按日期保存在 `/usr/log/rfid-logs/`，保留 3 天。

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

### 8.3 关键词检索（最实用）

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

# 看启动时识别到的串口列表
grep -A 5 "当前可用串口" /usr/log/rfid-logs/rfid-$(date +%F).log
```

### 8.4 进容器内看（紧急情况）

```bash
# 进入容器
sudo docker exec -it inlay-rfid sh

# 容器内查日志
ls /usr/log/rfid-logs/
cat /usr/log/rfid-logs/rfid-*.log

# 退出容器
exit
```

### 8.5 清空日志（很少用）

```bash
# 清 Docker 容器日志（不影响日志文件）
sudo truncate -s 0 $(sudo docker inspect --format='{{.LogPath}}' inlay-rfid)

# 清所有归档日志
sudo rm /usr/log/rfid-logs/rfid-*.log
```

### 8.6 通过 deploy.sh 看日志

```bash
sudo bash deploy.sh logs        # 等价于 docker logs -f inlay-rfid
sudo bash deploy.sh status      # 容器状态 + 日志文件列表
```

---

## 九、REST 接口文档

> 所有接口都以 `http://服务器IP:8080/api/rfid` 为前缀

### 9.1 健康检查

```http
GET /api/rfid/health
```

**返回**：纯文本 `healthy`

```bash
curl http://localhost:8080/api/rfid/health
# → healthy
```

---

### 9.2 修改单根天线功率

```http
POST /api/rfid/antennas/{antId}/power
Content-Type: application/json

{ "power": 18 }
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `antId` (路径) | int | 天线 ID，0 / 1 / 2 / 3 |
| `power` (body) | int | **0–33 整数**，单位 dBm |

**成功返回**：
```json
{
  "antId": 1,
  "success": true,
  "powerDbm": 18,
  "message": "修改成功"
}
```

**示例**：
```bash
curl -X POST http://localhost:8080/api/rfid/antennas/1/power \
     -H "Content-Type: application/json" \
     -d '{"power": 18}'
```

---

### 9.3 修改所有天线功率（统一）

```http
POST /api/rfid/antennas/power
Content-Type: application/json

{ "power": 18 }
```

**成功返回**：
```json
{
  "results": {"0": true, "1": true, "2": true, "3": true},
  "powerDbm": 18,
  "success": true
}
```

**示例**：
```bash
curl -X POST http://localhost:8080/api/rfid/antennas/power \
     -H "Content-Type: application/json" \
     -d '{"power": 18}'
```

---

### 9.4 错误响应

| 场景 | 响应 |
|------|------|
| 读写器未连接 | `{"success": false, "message": "读写器未连接"}` |
| `power` 字段缺失 | `{"success": false, "message": "缺少 power 字段"}` |
| `power` 超范围 | `{"success": false, "message": "power 范围 0~33，当前: 50"}` |
| `power` 不是数字 | `{"success": false, "message": "power 必须是 0~33 的整数"}` |

---

## 十、配置项说明

### `application.yml` 全字段

```yaml
server:
  port: 8080                  # HTTP 端口

spring:
  profiles:
    active: dev               # dev=本地, prod=Docker

logging:
  file:
    path: ./logs              # 日志目录（prod profile 会覆盖成 /usr/log/rfid-logs）

rfid:
  serial-port: auto           # auto = 按 USB VID:PID 自动识别 InlayLink 设备
                              # 或写死: /dev/ttyACM2、/dev/ttyUSB0、COM3
  baud-rate: 115200           # serial-port=auto 时此项被忽略（自动尝试 115200/57600/38400/9600）

  reconnect:
    enabled: true             # 自动重连开关
    interval-seconds: 5       # 重试间隔
    max-attempts: 0           # 0 = 无限重试

  antennas:                   # 天线列表（单位 0.1 dBm，2000 = 20 dBm）
    - id: 0
      power: 2000
    - id: 1
      power: 2000
    - id: 2
      power: 2000
    - id: 3
      power: 2000

  query:
    session: S0               # 防碰撞 Session: S0/S1/S2/S3
    target: AB                # 盘存目标: A/B/AB

  q:
    init: 5                   # 初始 Q 值
    max: 9
    min: 0
```

### 环境变量覆盖（Docker 用）

把 `.` 换成 `_`，全大写即可：

```bash
docker run -e RFID_SERIAL_PORT=/dev/ttyACM0 \
           -e RFID_RECONNECT_INTERVAL_SECONDS=3 \
           ...
```

### Profile 切换

```bash
# 默认 dev
java -jar app.jar

# 切到 prod
java -jar app.jar --spring.profiles.active=prod
# 或
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```

---

## 十一、改代码后怎么上线

**标准流程（本地改 → Git → 服务器同步）**：

```bash
# 1. 本地（Mac）
# ... 改代码 ...
git add .
git commit -m "feat: 加个新接口"
git push

# 2. 服务器（Linux）
ssh bosideng@服务器IP
cd ~/robot-rfid/bsd-robot-rfid
git pull
sudo bash deploy.sh          # 必须重新构建镜像！
```

### 哪些改动 **必须**重新构建镜像？

- 改了 Java 代码（`src/`）
- 改了 `application.yml`
- 改了 `pom.xml` / 加了依赖
- 改了 `Dockerfile`

→ 用 `sudo bash deploy.sh`（不加参数 = 完整重建）

### 哪些改动只重启就行？

- 改了 `deploy.sh` 里的环境变量（如 `SERIAL_DEVICE`）
- 改了挂载路径

→ 用 `sudo bash deploy.sh restart`（不重新构建）

### 哪些改动**完全不需要**重启？

- 通过 REST 接口改的天线功率 → **立即生效**
- 改 `application.yml` **但不重启** → **不生效**（Spring Boot 启动时读 yml，运行中不重读）

---

## 十二、二次开发指引

### 业务逻辑加在哪？

打开 `RfidRunner.startContinuousInventory()`，回调里写：

```java
rfidService.setInventoryCallback(tag -> {
    String epc = tag.getEpc();
    if (seenEpcs.add(epc)) {
        log.info("【新标签】 EPC={}", epc);
        // ⬇️ 在这里加业务（写库、推接口、报警）
        // myBusinessService.handleNewTag(epc);
    }
});
```

### `RfidService` 核心 API

```java
// ── 持续盘点（生产环境默认就是这个） ─────────────────
rfidService.setInventoryCallback(tag -> { ... });          // 注册标签回调
rfidService.startInventory(onSuccess, onFailure);          // 启动后一直跑
rfidService.stopInventory(onSuccess, onFailure);           // 停止

// ── 限时盘点（临时 / 工具场景，比如"扫一下"按钮） ────
rfidService.inventoryFor(10);                              // 阻塞 10 秒，返回 List<InventoryTag>

// ── 状态与配置 ─────────────────────────────────────
rfidService.isConnected();                                 // 连接状态
rfidService.setAntennaPower(0, 2000);                      // 改天线功率（运行时）
rfidService.getActualSerialPort();                         // 实际连上的串口

// ── 单标签读写 ─────────────────────────────────────
rfidService.selectTag(epc);                                // 先选中
rfidService.readTag(MemBank.EPC, 2, 6);                    // 读
rfidService.writeTag(MemBank.EPC, newEpc, 2, 6);           // 写

// ── 连接事件监听 ───────────────────────────────────
rfidService.addConnectionListener(new ConnectionListener() {
    public void onConnected() { ... }
    public void onDisconnected() { ... }
});
```

> 项目启动后会通过 `startInventory()` 进入**持续盘点**状态，SDK 内部循环读卡。
> `inventoryFor(N)` 是"读 N 秒后自动停"的便利方法，**仅在临时/工具场景下用**，生产环境不要拿它替代持续盘点。

---

# 📚 附录

## 十三、RFID 基础知识

### 什么是 RFID

**Radio Frequency Identification**，射频识别。一张电子标签 + 一台读写器，通过电磁波通信。

- **标签**（Tag）：贴在物品上的小芯片，里面存一个唯一编号 EPC
- **读写器**（Reader）：发射电磁波唤醒标签，读取 EPC
- **天线**：读写器的"耳朵"，电磁波从这里发出

### 什么是 EPC

**Electronic Product Code**，电子产品编码，标签的唯一身份证。

格式：96 位（12 字节），通常用 24 个十六进制字符表示。

```
B00110020002409230407738
└──┘└──┘└──┘└──────────┘
 厂商 类型 类别   序列号
```

### 盘点 vs 读 — 啥区别

| | 盘点（Inventory） | 读（Read） |
|---|------------------|-----------|
| 像什么 | 门口数人头 | 查某个人的档案 |
| 速度 | 快，一秒几十个 | 慢，一次一个 |
| 目标 | 不指定，读所有标签 | 必须先 select 选中一个 |
| 读什么 | 只读 EPC（标签身份） | 读 EPC/TID/USER 的具体数据 |
| 典型场景 | 入库盘点、过门检测 | 读批次号、写防伪码 |

### 标签的 4 个存储区（MemBank）

| 存储区 | 内容 | 能写吗 |
|--------|------|--------|
| RESERVED | 密码（杀密码 + 访问密码） | 可写（危险） |
| EPC | 唯一识别码（最常读的） | 可写 |
| TID | 厂商烧死的 ID | **只读** |
| USER | 用户自定义数据（批次号、日期等） | 可写 |

### 天线 / Session / Target / Q 值

| 概念 | 解释 | 我们的默认 |
|------|------|-----------|
| 天线 ANT0~3 | 一台读写器最多接 4 根天线，每根独立功率 | 4 根全开，各 20 dBm |
| Session S0/S1/S2/S3 | 防碰撞会话，影响标签被重复读到的概率 | S0（同标签 1 秒内能反复读到） |
| Target A/B/AB | 标签被读后会"翻转" flag，Target 决定读哪种 | AB（自动循环） |
| Q 值 | 控制盘点算法的"槽数"，影响吞吐 | 5（适合 < 100 标签） |

---

## 十四、SDK 速查表

**你只需要认识这 10 个核心类**：

### 14.1 操作类

| 类 | 作用 | 在我们代码哪里 |
|---|------|-------------|
| `Reader` | 读写器对象，所有操作的入口 | `RfidService.reader` |
| `ReaderImpl` | `Reader.create()` 创建实例 | `connect()` 方法 |
| `SerialPortHandle` | 串口连接器 | `connect()` 方法 |
| `AntConfig` | 天线配置（ID/功率/启用） | `applyDefaultConfig` / `setAntennaPower` |
| `QueryConfig` | 查询配置（Session/Target） | `applyDefaultConfig` |
| `QConfig` | Q 值配置 | `applyDefaultConfig` |
| `SelectConfig` | 标签过滤配置 | `selectTag()` |
| `ReadConfig` | 读标签参数（区/地址/长度） | `readTag()` |
| `WriteConfig` | 写标签参数 | `writeTag()` |
| `Consumer<T>` | 异步回调接口（成功/失败时被 SDK 反过来调用） | 所有 SDK 方法的最后两个参数 |

### 14.2 返回数据类

| 类 | 含义 | 主要字段 |
|---|------|---------|
| `InventoryTag` | 盘点到的标签 | `epc`、`rssi`、`ant`、`tid`、`frequency` |
| `ReadTag` | 读到的标签数据 | 数据内容 |
| `WrittenTag` | 写标签结果 | 是否成功 |
| `Success` | 操作成功响应 | 状态信息 |
| `Failure` | 操作失败响应 | 错误码、错误描述 |

### 14.3 枚举类

| 类 | 取值 | 含义 |
|---|------|------|
| `MemBank` | `RESERVED` / `EPC` / `TID` / `USER` | 标签的 4 个存储区 |
| `Select` | `SELECT_ALL` / `SELECT_ASSERTED` / `SELECT_DEASSERTED` / `SELECT_NOT_ASSERTED` | 盘点时是否过滤标签 |
| `Session` | `S0` / `S1` / `S2` / `S3` | 防碰撞会话 |
| `Target` | `A` / `B` / `AB` | 盘存目标 |

### 14.4 Consumer 异步回调

SDK 是异步设计，所有"会等待硬件响应"的方法都用回调而不是返回值：

```java
// 同步写法（SDK 不支持）：
String result = reader.doSomething();   // 会卡住主线程几百毫秒

// SDK 实际用的异步写法：
reader.doSomething(
    successConsumer,   // ← 成功时 SDK 调它，把结果传进来
    failureConsumer    // ← 失败时 SDK 调它，把错误传进来
);
// doSomething 立即返回，不阻塞
```

**例子**：
```java
reader.readTag(config,
    success -> log.info("读到: {}", success),   // Consumer<ReadTag>
    failure -> log.warn("失败: {}", failure)    // Consumer<Failure>
);
```

我们代码里用 `CountDownLatch` 把异步包装成同步：

```java
public ReadTag readTag(...) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ReadTag> result = new AtomicReference<>();

    reader.readTag(config,
        success -> { result.set(success); latch.countDown(); },
        failure -> { latch.countDown(); });

    latch.await(5, TimeUnit.SECONDS);  // 阻塞最多 5 秒
    return result.get();
}
```

---

## 十五、常见问题 FAQ

### Q1：Docker 容器看不到串口

**症状**：`docker exec inlay-rfid ls /dev/ttyACM*` 报 "No such file"

**原因**：容器没用 `--device` 或 `--privileged` 传入设备

**解决**：`deploy.sh` 已用 `--privileged + -v /dev:/dev` 处理。如果手工 `docker run` 要记得加上这两个参数。

### Q2：日志没写到文件

**症状**：`/usr/log/rfid-logs/` 是空的，但 `docker logs` 有内容

**原因**：volume 挂载或权限问题

**解决**：
```bash
# 1. 确认挂载
sudo docker inspect inlay-rfid | grep -A 3 Mounts

# 2. 确认目录权限
sudo chmod 777 /usr/log/rfid-logs
```

### Q3：自动扫描扫不到读写器

**症状**：日志一直 `connect failed` 或 `重连尝试 N`

**原因**：USB VID:PID 没匹配上（程序按 InlayLink VID=2fe3 识别）

**解决**：直接指定路径：
```yaml
rfid:
  serial-port: /dev/ttyACM2
```

或运行时覆盖：
```bash
-e RFID_SERIAL_PORT=/dev/ttyACM2
```

### Q4：读不到标签

按 [第四节](#四读不到标签怎么办自助排查) 排查清单：天线、距离、协议、功率。

### Q5：Docker 日志时间慢 8 小时

**原因**：容器内时区没设置

**解决**：Dockerfile 已设 `TZ=Asia/Shanghai`。如果自定义镜像注意保留。

### Q6：改了 yml 不生效

**原因**：Spring Boot 启动时才读 yml，运行中不重读

**解决**：
```bash
sudo bash deploy.sh    # 完整重建镜像 + 重启
```

### Q7：服务器重启后服务没起来

**原因**：Docker 服务没开机自启

**解决**：
```bash
sudo systemctl enable docker
sudo systemctl status docker
```

### Q8：天线功率改完没反应

**原因**：天线 ID 不对（你的读写器可能只接了 1 号天线）

**解决**：先看日志确认实际工作的天线编号：
```bash
grep "新标签" /usr/log/rfid-logs/rfid-$(date +%F).log | head -3
# 输出 ... 天线=2  → 实际是 2 号天线
# 改 2 号:
curl -X POST http://localhost:8080/api/rfid/antennas/2/power -d '{"power": 18}' -H 'Content-Type: application/json'
```

---

## 项目信息

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/cycyu7-pixel/bsd-robot-rfid |
| 业务方 | 博斯登（BSD） |
| 技术栈 | Java 8 + Spring Boot 2.4 + InLayLink RFID SDK V2.2 + Docker |
| 部署 | Linux x86_64 / Docker |
# InLay-RFID — InLayLink RFID 读写器客户端

基于 Spring Boot 2.4 + InLayLink RFID SDK V2.2 的读写器控制程序。
- 串口自动扫描 + 多波特率自适应
- 后台持续盘点，新标签实时打印
- 读写器热插拔自动重连
- 日志按天滚动，保留 3 天
- 支持 Docker 一键部署

## 项目结构

```
InLay-RFID/
├── lib/                                  # SDK 本地依赖
├── src/main/java/com/cyu/inlayrfid/
│   ├── InLayRfidApplication.java         # Spring Boot 启动类
│   ├── config/RfidProperties.java        # application.yml 配置映射
│   ├── service/RfidService.java          # 读写器核心服务
│   └── runner/RfidRunner.java           # 持续盘点入口
├── src/main/resources/
│   ├── application.yml                   # 配置（dev / prod profile）
│   └── logback-spring.xml                # 日志配置（控制台 + 滚动文件）
├── Dockerfile                            # Docker 多阶段构建
├── .dockerignore
└── pom.xml
```

## 快速开始

### 本地运行

```bash
mvn clean package -DskipTests
java -jar target/InLay-RFID-1.0.0.jar
```

日志会输出到控制台，同时写入 `./logs/rfid-yyyy-MM-dd.log`。

### Docker 部署

#### 一键部署（推荐）

```bash
# 构建 + 启动（首次部署）
./deploy.sh

# 常用命令
./deploy.sh restart    # 代码改了，重启容器（不重新构建镜像）
./deploy.sh stop       # 停止并删除容器
./deploy.sh logs       # 实时查看容器日志
./deploy.sh status     # 查看容器状态 + 日志文件
./deploy.sh build      # 仅构建镜像，不启动
```

脚本会自动完成：
- 检查 Docker 已安装并运行
- 设置 Docker 服务开机自启（`sudo systemctl enable docker`）
- 创建宿主机日志目录 `/usr/log/rfid-logs`
- 构建 Docker 镜像
- 启动容器（`--restart=always` 开机自启）
- 串口不存在时也不报错（容器内程序会后台重连）

**修改脚本顶部的 `SERIAL_DEVICE` / `LOG_DIR` 等变量可自定义配置。**

#### 开机自启原理

```
宿主机开机
  └─ systemd 启动 docker 服务（已 enable）
     └─ docker 自动拉起所有 --restart=always 的容器
        └─ InLay-RFID 容器启动
           └─ Java 程序后台重连读写器
              └─ 读写器插上后自动连接 + 持续盘点
```

#### 手动部署（可选）

```bash
# 1. 构建镜像
docker build -t inlay-rfid .

# 2. 运行（关键：--device 把宿主机串口传进容器）
docker run -d \
  --name inlay-rfid \
  --restart=always \
  --device /dev/ttyUSB0 \
  -v /usr/log/rfid-logs:/usr/log/rfid-logs \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e RFID_SERIAL_PORT=/dev/ttyUSB0 \
  inlay-rfid

# 3. 查看日志
docker logs -f inlay-rfid                       # 容器控制台日志
ls /usr/log/rfid-logs/                          # 宿主机日志文件（按日期命名）
tail -f /usr/log/rfid-logs/rfid-$(date +%F).log # 实时查看今天的日志
```

#### 日志兜底清理（可选）

程序在跑时 logback 会自动清理 3 天前的日志。但如果程序长期不跑，日志会堆积。
可以装一个 crontab 兜底：

```bash
sudo cp log-cleanup.sh /etc/cron.daily/inlay-rfid-log-cleanup
sudo chmod +x /etc/cron.daily/inlay-rfid-log-cleanup
```

## 日志说明

### 日志策略（`logback-spring.xml`）

| 输出目标 | 级别 | 说明 |
|---------|------|------|
| 控制台 | DEBUG+ | 彩色，方便开发调试 |
| 文件 | INFO+ | 异步写入，不阻塞主线程 |
| 滚动策略 | 按天 + 按大小（50MB） | 当天超过 50MB 会切割 |
| 保留时长 | **3 天** | 第 4 天自动清理 |
| 总大小上限 | 500MB | 超过会从最老的开始删 |
| 压缩 | `.log.gz` | 历史日志自动压缩 |

### 日志路径

| 环境 | 路径 | 配置方式 |
|------|------|---------|
| dev (本地) | `./logs/rfid-yyyy-MM-dd.log` | `logging.file.path: ./logs` |
| prod (Docker) | `/usr/log/rfid-logs/rfid-yyyy-MM-dd.log` | `logging.file.path: /usr/log/rfid-logs`，挂载 `-v 宿主机:/usr/log/rfid-logs` |

### 日志文件示例

按日期命名，文件结构：

```
/usr/log/rfid-logs/
├── rfid-2026-06-21.log     # 3 天前（明天被自动清理）
├── rfid-2026-06-22.log     # 2 天前
└── rfid-2026-06-23.log     # 今天（当前活动文件）
```

跨天时自动新建文件，不需要重启程序。

## 配置说明

`application.yml` 用 Spring profile 区分环境：

```yaml
spring:
  profiles:
    active: dev    # 本地开发用 dev，Docker 用 prod

rfid:
  serial-port: auto          # auto = 自动扫描所有串口
  baud-rate: 115200
  reconnect:
    enabled: true
    interval-seconds: 5
    max-attempts: 0           # 0 = 无限重试
  antennas:
    - id: 0
      power: 2000             # 20 dBm
    - id: 1
      power: 2000
    - id: 2
      power: 2000
    - id: 3
      power: 2000
```

### 环境变量覆盖

Docker 部署时可以用 `-e` 覆盖任意配置：

```bash
docker run -d \
  --device /dev/ttyUSB0 \
  -v /usr/log/rfid-logs:/usr/log/rfid-logs \
  -e RFID_SERIAL_PORT=/dev/ttyUSB0 \
  -e RFID_ANTENNAS_0_POWER=2500 \
  -e RFID_RECONNECT_INTERVAL_SECONDS=3 \
  inlay-rfid
```

## Docker 部署注意

### 1. 串口权限

容器需要访问宿主机的 `/dev/ttyUSB0`，**必须用 `--device` 传进去**：

```bash
# 正确写法
docker run --device /dev/ttyUSB0 inlay-rfid

# 错误写法（容器里看不到串口）
docker run inlay-rfid
```

如果读写器是 USB CDC 设备（如 `/dev/ttyACM0`），同样传：

```bash
docker run --device /dev/ttyACM0 inlay-rfid
```

### 2. 日志持久化

容器删除后日志会丢，**必须挂载 volume**：

```bash
-v /usr/log/rfid-logs:/usr/log/rfid-logs
```

### 3. 容器自动重启

```bash
--restart=unless-stopped
```

读写器掉线/重启会自动重连，但容器本身崩溃时也能自动拉起。

### 4. 时区

Dockerfile 里已经设置 `TZ=Asia/Shanghai`，日志时间戳是东八区。

## RfidService 核心 API

```java
// 持续盘点（自动启动）
rfidService.addConnectionListener(new RfidService.ConnectionListener() {
    @Override public void onConnected() {
        // 连接成功后回调
    }
    @Override public void onDisconnected() {
        // 断线时回调
    }
});

// 手动盘点
List<InventoryTag> tags = rfidService.inventoryFor(10);

// 读标签
rfidService.selectTag(epc);
ReadTag result = rfidService.readTag(MemBank.EPC, 2, 6);

// 写标签
rfidService.writeTag(MemBank.EPC, newEpc, 2, 6);
```

## 常见问题

### Q: Docker 里访问不到串口
```bash
# 宿主机检查
ls /dev/ttyUSB*
# 用 --device 传进容器
docker run --device /dev/ttyUSB0 inlay-rfid
```

### Q: 日志没写入文件
```bash
# 检查挂载点权限
docker exec inlay-rfid ls -la /usr/log/rfid-logs
# 宿主机日志目录权限
sudo chmod 777 /usr/log/rfid-logs
```

### Q: 自动扫描不到串口
```bash
# 宿主机检查
ls /dev/ttyUSB* /dev/ttyACM*
# 如果是固定路径，直接指定
docker run -e RFID_SERIAL_PORT=/dev/ttyUSB0 --device /dev/ttyUSB0 inlay-rfid
```
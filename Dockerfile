# ============================================================
# 多阶段构建：第一阶段构建 fat jar，第二阶段只运行
# 镜像大小约 250MB（基于 eclipse-temurin:8-jre-jammy）
# 注：不用 openjdk:8 因其已停止维护，部分镜像源 403 拒绝拉取
# ============================================================

# ---------- Stage 1: Build ----------
FROM maven:3.8-eclipse-temurin-8 AS builder
WORKDIR /build

# 先单独拷 pom，利用 Docker 缓存加速依赖下载
COPY pom.xml .
COPY lib ./lib
RUN mvn -B -q dependency:go-offline || true

# 拷源码并构建
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:8-jre-jammy
LABEL maintainer="InLay-RFID"

# 装一些调试工具（lsusb / ls /dev/tty* 用），不需要可删
RUN apt-get update \
    && apt-get install -y --no-install-recommends usbutils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 拷贝构建产物
COPY --from=builder /build/target/InLay-RFID-*.jar /app/app.jar

# 日志目录（Docker volume 挂载点）
RUN mkdir -p /usr/log/rfid-logs
VOLUME ["/usr/log/rfid-logs"]

# 时区改成东八区（避免日志时间偏 8 小时）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# JVM 参数：容器内堆内存限制 + 退出时刷日志
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# 默认用 prod 配置（串口 /dev/ttyUSB0，日志 /usr/log/rfid-logs）
ENV SPRING_PROFILES_ACTIVE=prod

# 容器需要 host 的 dialout 组权限才能访问串口设备
# 启动示例（见 README.md）：
#   docker run --device /dev/ttyUSB0 -v /usr/log/rfid-logs:/usr/log/rfid-logs inlay-rfid

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
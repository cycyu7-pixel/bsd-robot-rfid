#!/bin/bash
# ============================================================
# InLay-RFID Docker 一键部署脚本
# ============================================================
# 用法：
#   ./deploy.sh              # 构建并启动
#   ./deploy.sh restart      # 仅重启容器（不重新构建镜像）
#   ./deploy.sh stop         # 停止并删除容器
#   ./deploy.sh logs         # 查看实时日志
#   ./deploy.sh status       # 查看容器状态
# ============================================================

set -e

# ----------------------- 配置区（按需修改） -----------------------
CONTAINER_NAME="inlay-rfid"
IMAGE_NAME="inlay-rfid"
IMAGE_TAG="latest"

# 串口设备（读写器实际接的端口，常见 /dev/ttyUSB0 或 /dev/ttyACM0）
SERIAL_DEVICE="/dev/ttyUSB0"

# 日志挂载路径（宿主机侧）
LOG_DIR="/usr/log/rfid-logs"

# Spring profile（prod = Docker 部署）
SPRING_PROFILE="prod"

# -----------------------------------------------------------------

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
step()  { echo -e "${CYAN}[STEP]${NC} $1"; }

# 获取脚本所在目录（项目根目录）
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# ============================================================
# 函数定义
# ============================================================

check_docker() {
    if ! command -v docker &> /dev/null; then
        error "未检测到 docker，请先安装 Docker"
        echo "  Ubuntu/Debian:  curl -fsSL https://get.docker.com | sh"
        echo "  CentOS:         curl -fsSL https://get.docker.com | sh"
        echo "  安装后请重启系统，并确保 docker 服务已开机自启："
        echo "    sudo systemctl enable docker"
        exit 1
    fi

    if ! docker info &> /dev/null; then
        error "docker daemon 未运行或当前用户无权限"
        echo "  1. 启动 docker: sudo systemctl start docker"
        echo "  2. 加入 docker 组（免 sudo）: sudo usermod -aG docker \$USER && newgrp docker"
        echo "  3. 设置开机自启: sudo systemctl enable docker"
        exit 1
    fi
}

check_serial_device() {
    if [ ! -e "$SERIAL_DEVICE" ]; then
        warn "串口设备 $SERIAL_DEVICE 当前不存在"
        echo "  - 如果读写器还没插，可忽略，容器会持续重连"
        echo "  - 如果读写器已插但没出现，执行: ls /dev/ttyUSB* /dev/ttyACM*"
        echo "  - 修改本脚本顶部的 SERIAL_DEVICE 变量可换其他端口"
        echo ""
    fi
}

create_log_dir() {
    if [ ! -d "$LOG_DIR" ]; then
        step "创建日志目录: $LOG_DIR"
        sudo mkdir -p "$LOG_DIR"
        sudo chmod 777 "$LOG_DIR"
        info "日志目录已创建"
    else
        info "日志目录已存在: $LOG_DIR"
    fi
}

build_image() {
    step "构建 Docker 镜像: $IMAGE_NAME:$IMAGE_TAG"
    docker build -t "$IMAGE_NAME:$IMAGE_TAG" .
    info "镜像构建完成"
}

stop_container() {
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        step "停止并删除旧容器: $CONTAINER_NAME"
        docker rm -f "$CONTAINER_NAME" &> /dev/null
        info "旧容器已删除"
    fi
}

run_container() {
    step "启动新容器: $CONTAINER_NAME"

    # ============================================================
    # 关键：容器访问宿主机串口的两种方式
    # ------------------------------------------------------------
    # 方式 A（--device）：只能挂载启动时已存在的设备，拔插后断
    # 方式 B（--privileged + -v /dev）：挂载整个 /dev 目录
    #   优点：容器启动后插拔读写器也能感知到
    #   缺点：特权模式，容器有宿主机 root 权限
    # 这里默认用方式 B，因为读写器经常拔插
    # ============================================================
    docker run -d \
        --name "$CONTAINER_NAME" \
        --restart=always \
        --privileged \
        -v /dev:/dev \
        -v "$LOG_DIR":"$LOG_DIR" \
        -e SPRING_PROFILES_ACTIVE="$SPRING_PROFILE" \
        -e RFID_SERIAL_PORT="$SERIAL_DEVICE" \
        -e TZ=Asia/Shanghai \
        "$IMAGE_NAME:$IMAGE_TAG"

    info "容器已启动（特权模式 + 挂载 /dev，支持热插拔）"
}

show_status() {
    echo ""
    step "容器状态"
    docker ps --filter "name=$CONTAINER_NAME" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
    echo ""
    step "日志文件"
    if [ -d "$LOG_DIR" ]; then
        ls -lh "$LOG_DIR" 2>/dev/null || echo "  （目录为空，等待程序写入）"
    else
        echo "  日志目录不存在: $LOG_DIR"
    fi
    echo ""
    info "查看实时日志:    $0 logs"
    info "查看容器控制台:  docker logs -f $CONTAINER_NAME"
    info "今天的日志文件:  tail -f $LOG_DIR/rfid-\$(date +%F).log"
}

ensure_docker_autostart() {
    # 确保 docker 服务本身开机自启（容器才能跟着自启）
    if systemctl list-unit-files 2>/dev/null | grep -q docker.service; then
        if ! systemctl is-enabled docker &> /dev/null; then
            step "设置 docker 服务开机自启"
            sudo systemctl enable docker
            info "docker 服务已设为开机自启"
        else
            info "docker 服务已是开机自启"
        fi
    fi
}

# ============================================================
# 主流程
# ============================================================

case "${1:-start}" in
    start|"")
        info "===== 开始部署 $CONTAINER_NAME ====="
        check_docker
        ensure_docker_autostart
        check_serial_device
        create_log_dir
        build_image
        stop_container
        run_container
        show_status
        info "===== 部署完成 ====="
        ;;

    restart)
        info "===== 重启 $CONTAINER_NAME（不重新构建镜像） ====="
        check_docker
        stop_container
        run_container
        show_status
        ;;

    stop)
        step "停止并删除容器: $CONTAINER_NAME"
        docker rm -f "$CONTAINER_NAME" &> /dev/null && info "已停止" || info "容器不存在"
        ;;

    logs)
        step "实时日志（按 Ctrl+C 退出）"
        docker logs -f "$CONTAINER_NAME"
        ;;

    status)
        show_status
        ;;

    build)
        build_image
        ;;

    *)
        echo "用法: $0 {start|restart|stop|logs|status|build}"
        echo ""
        echo "命令说明:"
        echo "  start     构建镜像 + 启动容器（默认）"
        echo "  restart   不重新构建，只重启容器"
        echo "  stop      停止并删除容器"
        echo "  logs      实时查看容器日志"
        echo "  status    查看容器状态和日志文件"
        echo "  build     仅构建镜像，不启动"
        exit 1
        ;;
esac
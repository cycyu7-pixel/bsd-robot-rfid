#!/bin/bash
# ============================================================
# InLay-RFID 日志兜底清理脚本
# ============================================================
# 由 crontab 每天调用一次，删除 4 天前的日志
# （logback 自己清理 3 天内的，这里多留 1 天避免误删当天）
#
# 安装方式：
#   sudo cp log-cleanup.sh /etc/cron.daily/inlay-rfid-log-cleanup
#   sudo chmod +x /etc/cron.daily/inlay-rfid-log-cleanup
# ============================================================

LOG_DIR="/usr/log/rfid-logs"

if [ ! -d "$LOG_DIR" ]; then
    exit 0
fi

# 删除 4 天前的 rfid-*.log 文件
find "$LOG_DIR" -name "rfid-*.log" -mtime +4 -delete 2>/dev/null

# 同时清理空的子目录
find "$LOG_DIR" -type d -empty -delete 2>/dev/null
package com.cyu.inlayrfid.runner;

import com.cyu.inlayrfid.config.RfidProperties;
import com.cyu.inlayrfid.service.RfidService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 程序入口：Spring Boot 启动后触发。
 * <p>
 * 流程：
 *  1. 启动后台线程持续扫描串口、连接读写器
 *  2. 连接成功 → 应用默认参数
 *  3. 如果配置 auto-start=true，则自动开启持续盘点
 *  4. 页面也可以随时通过 REST 接口开始/停止盘点
 */
@Component
public class RfidRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RfidRunner.class);

    private final RfidService rfidService;
    private final RfidProperties properties;

    public RfidRunner(RfidService rfidService, RfidProperties properties) {
        this.rfidService = rfidService;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        boolean autoMode = properties.getSerialPort() == null
                || properties.getSerialPort().trim().isEmpty()
                || "auto".equalsIgnoreCase(properties.getSerialPort().trim());

        log.info("========================================");
        log.info("  InLayLink RFID Reader - 启动中...");
        if (autoMode) {
            log.info("  串口: auto (按 USB VID:PID 识别 InlayLink 设备)");
            log.info("  当前可用串口:");
            for (String info : RfidService.scanSerialPortsDetailed()) {
                log.info("    - {}", info);
            }
        } else {
            log.info("  串口: {}", properties.getSerialPort());
            log.info("  波特率: {}", properties.getBaudRate());
        }
        log.info("  天线: {}", properties.getAntennas());
        log.info("  自动重连: {}", properties.getReconnect().isEnabled()
                ? "已开启（间隔 " + properties.getReconnect().getIntervalSeconds() + " 秒）"
                : "已关闭");
        log.info("  自动开始读取: {}", properties.getInventory().isAutoStart() ? "是" : "否");
        log.info("========================================");

        rfidService.addConnectionListener(new RfidService.ConnectionListener() {
            @Override
            public void onConnected() {
                log.info("--- 读写器已连接，初始化参数 ---");
                rfidService.printVersion();
                rfidService.applyDefaultConfig();
                if (properties.getInventory().isAutoStart()) {
                    rfidService.startReading();
                } else {
                    log.info("auto-start=false，等待页面手动开始读取");
                }
            }

            @Override
            public void onDisconnected() {
                // RfidService 已经处理读取状态和断开日志，这里无需重复打印
            }
        });

        if (!properties.getReconnect().isEnabled()) {
            log.info("自动重连已关闭，尝试单次连接...");
            if (!rfidService.connect()) {
                log.warn("单次连接失败。可调用 rfidService.connect() 手动重试。");
            }
        }
        rfidService.startReconnectService();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到退出信号，关闭读写器连接...");
            rfidService.shutdown();
        }));

        log.info("程序已启动，按 Ctrl+C 退出程序");
    }
}
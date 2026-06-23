package com.cyu.inlayrfid.runner;

import com.cyu.inlayrfid.config.RfidProperties;
import com.cyu.inlayrfid.service.RfidService;
import com.inlaylink.rfid.bean.config.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 程序入口：Spring Boot 启动后触发。
 * <p>
 * 流程：
 *  1. 启动后台线程持续扫描串口、连接读写器
 *  2. 连接成功 → 应用默认参数 → 开启「持续后台盘点」
 *  3. 每读到一个新 EPC 立即打印，重复的静默
 *  4. 读写器掉线 → 后台线程自动重连 → 恢复后再次开启持续盘点
 */
@Component
public class RfidRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RfidRunner.class);

    private final RfidService rfidService;
    private final RfidProperties properties;

    /** 已经见过的 EPC（连接生命周期内去重），断线重连时会清空重新统计 */
    private final Set<String> seenEpcs = ConcurrentHashMap.newKeySet();
    /** 累计读取次数 */
    private final AtomicLong totalReads = new AtomicLong();

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
        log.info("========================================");

        // ===================================================================
        // 连接生命周期回调：连上后开启持续盘点，断线时停止并清状态
        // ===================================================================
        rfidService.addConnectionListener(new RfidService.ConnectionListener() {
            @Override
            public void onConnected() {
                log.info("--- 读写器已连接，初始化参数 ---");
                rfidService.printVersion();
                rfidService.applyDefaultConfig();
                startContinuousInventory();
            }

            @Override
            public void onDisconnected() {
                // RfidService 已经打印过「读写器已断开，后台持续重连中...」
                // 这里只清理业务状态，不再重复打印
                log.debug("清理盘点统计：{} 次读取，{} 个不同 EPC",
                        totalReads.get(), seenEpcs.size());
                seenEpcs.clear();
                totalReads.set(0);
            }
        });

        // ===================================================================
        // 启动后台连接 / 重连线程（非 daemon，保活 JVM）
        // ===================================================================
        if (!properties.getReconnect().isEnabled()) {
            log.info("自动重连已关闭，尝试单次连接...");
            if (!rfidService.connect()) {
                log.warn("单次连接失败。可调用 rfidService.connect() 手动重试。");
            }
        }
        rfidService.startReconnectService();

        // ===================================================================
        // JVM 关闭钩子：保证断开连接
        // ===================================================================
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到退出信号，关闭读写器连接...");
            rfidService.shutdown();
        }));

        log.info("程序已启动，按 Ctrl+C 退出程序");
    }

    /**
     * 开启「持续后台盘点」：
     * 读写器一直处于盘存状态，每读到一个新 EPC 立刻打印，重复的静默。
     */
    private void startContinuousInventory() {
        try {
            // 先清除任何 select 过滤，保证能读到所有标签
            rfidService.rawReader().setSelectMode(Select.SELECT_ALL, null, null);

            // 注册回调：每收到一个标签就执行
            rfidService.setInventoryCallback(tag -> {
                totalReads.incrementAndGet();
                String epc = tag.getEpc();
                //todo 可在此新增 epc 读取之后的逻辑
                if (seenEpcs.add(epc)) {
                    log.info("【新标签】 EPC={}  RSSI={} dBm  天线={}",
                            epc, tag.getRssi(), tag.getAnt());
                }
            });

            // 启动持续盘点（SDK 内部会循环读，不会自己停）
            rfidService.startInventory(
                    s -> log.info("持续盘点已启动，等待标签..."),
                    f -> log.error("启动盘点失败: {}", f));

        } catch (Exception e) {
            log.error("开启持续盘点异常: {}", e.getMessage(), e);
        }
    }
}

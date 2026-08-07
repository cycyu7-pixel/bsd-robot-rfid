package com.cyu.inlayrfid.task;

import com.cyu.inlayrfid.entity.vo.ScanCallbackVO;
import com.cyu.inlayrfid.service.RfidService;
import com.inlaylink.rfid.bean.config.Select;
import com.inlaylink.rfid.bean.receive.InventoryTag;
import com.inlaylink.rfid.communication.InventoryHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次性扫描任务。
 * <p>
 * 纯事件驱动：设置好 callback 和超时后立即返回，不阻塞线程池线程。
 * 扫到首个 EPC 或超时/出错后，自动清理并回调调用方。
 * 回调使用 {@link Result}<{@link ScanCallbackVO}> 包装，调用方通过 error 字段判断。
 */
public class ScanTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScanTask.class);

    /** 扫描超时秒数，35 秒内没读到 EPC 就算失败 */
    private static final int SCAN_TIMEOUT_SEC = 35;

    private final RfidService rfidService;
    private final RestTemplate restTemplate;
    private final String requestId;
    private final String callbackUrl;

    /** 防止 finish 被多次调用（标签到达 vs 超时 的竞态） */
    private final AtomicBoolean completed = new AtomicBoolean(false);
    /** 扫描专用去重集合 */
    private final Set<String> seenThisScan = ConcurrentHashMap.newKeySet();
    /** 扫描到的标签列表，取第一个 EPC 返回 */
    private final ConcurrentLinkedQueue<InventoryTag> foundTags = new ConcurrentLinkedQueue<>();
    /** 是否由我们启动的盘点（需要由我们停止） */
    private volatile boolean startedByUs = false;
    /** 保存的原始 callback，finish 时恢复 */
    private volatile InventoryHandle savedCallback;
    /** 超时任务句柄 */
    private volatile ScheduledFuture<?> timeoutFuture;

    public ScanTask(RfidService rfidService, RestTemplate restTemplate,
                    String requestId, String callbackUrl) {
        this.rfidService = rfidService;
        this.restTemplate = restTemplate;
        this.requestId = requestId;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public void run() {
        log.info("[scan-{}] 开始扫描，等待新标签...", requestId);

        // 保存当前连续模式的 callback，扫描完成后恢复
        savedCallback = rfidService.getContinuousCallback();

        // 检查读写器是否可用
        if (rfidService.getReader() == null) {
            log.warn("[scan-{}] 读写器未连接，无法扫描", requestId);
            if (completed.compareAndSet(false, true)) {
                finishWithError("读写器未连接");
            }
            return;
        }

        // 设置扫描专用 callback（SDK 线程触发，非阻塞）
        rfidService.getReader().setInventoryCallback(tag -> {
            if (completed.get()) return;
            String epc = tag.getEpc();
            if (epc == null || epc.trim().isEmpty()) return;
            if (seenThisScan.add(epc)) {
                foundTags.add(tag);
                log.info("[scan-{}] 扫到新标签 EPC={}", requestId, epc);
                // 首次扫到新标签 → 完成扫描
                if (completed.compareAndSet(false, true)) {
                    finishScan();
                }
            }
        });

        // 如果 reader 当前不在读取状态，由我们启动盘点
        if (!rfidService.getReader().isReading()) {
            if (!startInventory()) {
                // 启动盘点失败，已由 startInventory 内部回调 finishWithError
                return;
            }
        }

        // 调度超时任务（scheduler 线程触发）
        timeoutFuture = rfidService.getScheduler().schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                log.info("[scan-{}] 扫描超时（{}s），未读取到 EPC", requestId, SCAN_TIMEOUT_SEC);
                finishWithError("扫描超时（" + SCAN_TIMEOUT_SEC + "s），未读取到 EPC，请重新发起扫描");
            }
        }, SCAN_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 线程返回，不阻塞等待
    }

    /**
     * 启动盘点，等待 SDK 确认结果。
     *
     * @return true 启动成功，false 启动失败（已回调 error）
     */
    private boolean startInventory() {
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean startOk = new AtomicBoolean(false);

        rfidService.getReader().setSelectMode(Select.SELECT_ALL, null, null);
        rfidService.getReader().startInventory(
                s -> { startOk.set(true); startedByUs = true; startLatch.countDown(); },
                f -> {
                    log.warn("[scan-{}] 启动盘点失败: {}", requestId, f);
                    startLatch.countDown();
                });

        try {
            startLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!startOk.get()) {
            log.warn("[scan-{}] 启动盘点失败，结束扫描", requestId);
            if (completed.compareAndSet(false, true)) {
                finishWithError("启动盘点失败");
            }
            return false;
        }
        return true;
    }

    /**
     * 正常完成扫描（扫到标签）。
     */
    private void finishScan() {
        doCleanup();
        // 取第一个扫到的 EPC
        String epc = foundTags.isEmpty() ? null : foundTags.peek().getEpc();
        doCallback(null, epc);
    }

    /**
     * 异常完成扫描（读写器未连接、启动盘点失败、超时等）。
     */
    private void finishWithError(String error) {
        doCleanup();
        doCallback(error, null);
    }

    /**
     * 清理扫描资源：取消超时、停止盘点、恢复原始 callback。
     */
    private void doCleanup() {
        try {
            // 取消超时任务
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }

            // 停止盘点（仅当我们启动时才停）
            if (startedByUs && rfidService.getReader() != null
                    && rfidService.getReader().isReading()) {
                CountDownLatch stopLatch = new CountDownLatch(1);
                rfidService.getReader().stopInventory(
                        s -> stopLatch.countDown(),
                        f -> stopLatch.countDown());
                stopLatch.await(5, TimeUnit.SECONDS);
            }

            // 恢复原始 callback
            if (rfidService.getReader() != null && savedCallback != null) {
                rfidService.getReader().setInventoryCallback(savedCallback);
            }
        } catch (Exception e) {
            log.error("[scan-{}] 清理资源异常: {}", requestId, e.getMessage(), e);
        }
    }

    /**
     * POST 扫描结果到调用方提供的回调地址。
     * <p>
     * 直接发送 {@link ScanCallbackVO}，不做额外包装。
     *
     * @param error 错误信息，null 表示正常完成
     * @param epc   读取到的标签 EPC，error 不为 null 时为 null
     */
    private void doCallback(String error, String epc) {
        ScanCallbackVO data = new ScanCallbackVO(requestId, epc, error);

        if (error != null) {
            log.info("[scan-{}] 扫描出错: {}，回调通知调用方", requestId, error);
        } else {
            log.info("[scan-{}] 扫描完成, EPC={}", requestId, epc);
        }

        try {
            restTemplate.postForObject(callbackUrl, data, String.class);
            log.info("[scan-{}] 回调成功", requestId);
        } catch (Exception e) {
            log.warn("[scan-{}] 回调失败: {}  (调用方可能已断开，不影响扫描)",
                    requestId, e.getMessage());
        }
    }
}
package com.cyu.inlayrfid.service;

import com.cyu.inlayrfid.config.RfidProperties;
import com.cyu.inlayrfid.entity.vo.AntennaSetResultVO;
import com.cyu.inlayrfid.entity.vo.RfidStatusVO;
import com.cyu.inlayrfid.entity.vo.TagEventVO;
import com.fazecast.jSerialComm.SerialPort;
import com.inlaylink.connect.port.SerialPortHandle;
import com.inlaylink.rfid.Reader;
import com.inlaylink.rfid.base.Consumer;
import com.inlaylink.rfid.bean.config.MemBank;
import com.inlaylink.rfid.bean.config.Select;
import com.inlaylink.rfid.bean.receive.Failure;
import com.inlaylink.rfid.bean.receive.InventoryTag;
import com.inlaylink.rfid.bean.receive.ReadTag;
import com.inlaylink.rfid.bean.receive.Success;
import com.inlaylink.rfid.bean.receive.WrittenTag;
import com.inlaylink.rfid.bean.send.AntConfig;
import com.inlaylink.rfid.bean.send.QConfig;
import com.inlaylink.rfid.bean.send.QueryConfig;
import com.inlaylink.rfid.bean.send.ReadConfig;
import com.inlaylink.rfid.bean.send.SelectConfig;
import com.inlaylink.rfid.bean.send.WriteConfig;
import com.inlaylink.rfid.communication.InventoryHandle;
import com.inlaylink.rfid.process.ReaderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * InLayLink RFID 读写器服务封装。
 * <p>
 * 主要职责：
 *  - 管理读写器生命周期，支持「后台持续重连，读写器插上后自动连上」
 *  - 提供盘点（同步 / 异步）、读标签、写标签、参数配置等高层接口
 *  - 把 SDK 的回调模型转换成阻塞式 / 集合式结果，便于业务侧调用
 */
@Service
public class RfidService {

    private static final Logger log = LoggerFactory.getLogger(RfidService.class);

    /** 同步操作默认超时时间 */
    private static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /** 常用波特率，自动扫描时逐个尝试 */
    private static final int[] COMMON_BAUD_RATES = {115200, 57600, 38400, 9600};

    private final RfidProperties properties;

    /** SDK 提供的 Reader 实例，整个进程共用一个 */
    private volatile Reader reader;

    /** 当前连接状态 */
    private volatile boolean connected = false;

    /** 实际连接成功的串口路径（自动发现时有用） */
    private volatile String actualSerialPort = null;

    /** 后台重连任务是否已启动 */
    private final AtomicBoolean reconnectRunning = new AtomicBoolean(false);

    /** 连接状态变化监听器（重连成功后触发客户业务） */
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /** 控制后台重连退出 */
    private volatile boolean shutdown = false;

    /**
     * 统一调度线程池：5 个核心线程，承担所有周期任务与异步工作。
     * daemon 线程，不会阻塞 JVM 退出（由专门的 keepalive 线程负责保活）。
     */
    private static final int THREAD_POOL_SIZE = 5;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(THREAD_POOL_SIZE,
            new NamedDaemonThreadFactory("rfid-scheduler", true));

    /** 重连周期任务句柄，shutdown 时取消 */
    private volatile ScheduledFuture<?> reconnectTask;

    /** 专用保活线程（非 daemon），让 JVM 持续运行；shutdown 时立刻退出 */
    private volatile Thread keepaliveThread;

    /** 当前是否正在持续盘点 */
    private final AtomicBoolean reading = new AtomicBoolean(false);

    /** 已读到的不同 EPC，用于去重 */
    private final Set<String> seenEpcs = ConcurrentHashMap.newKeySet();

    /** SDK 回调总次数（含重复标签） */
    private final AtomicLong totalReads = new AtomicLong();

    /** 标签事件递增序号，前端用 since 参数增量拉取 */
    private final AtomicLong sequence = new AtomicLong();

    /** 最近一次收到 SDK 标签回调的时间 */
    private final AtomicLong lastTagCallbackTime = new AtomicLong(0);

    /** 最近一次手动重启读取的时间 */
    private final AtomicLong lastReadingRestartTime = new AtomicLong(0);

    /** 最近的新 EPC 事件，供页面展示 */
    private final ConcurrentLinkedDeque<TagEventVO> tagEvents = new ConcurrentLinkedDeque<>();

    /** 最多保留多少条事件，避免长时间运行占内存 */
    private static final int MAX_TAG_EVENTS = 1000;

    /** 持续模式的回调引用，scan-once 任务需要保存/恢复它 */
    volatile InventoryHandle continuousCallback;

    @Autowired
    public RfidService(RfidProperties properties) {
        this.properties = properties;
    }

    /** 命名 + daemon 可选的线程工厂 */
    private static class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final boolean daemon;
        private final AtomicInteger counter = new AtomicInteger();

        NamedDaemonThreadFactory(String prefix, boolean daemon) {
            this.prefix = prefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(daemon);
            return t;
        }
    }

    // =========================================================================
    // 串口扫描
    // =========================================================================

    /**
     * 列出当前系统所有可用串口。
     */
    public static List<String> scanSerialPorts() {
        List<String> ports = new java.util.ArrayList<>();
        for (SerialPort sp : SerialPort.getCommPorts()) {
            ports.add(sp.getSystemPortName());
        }
        return ports;
    }

    /**
     * 列出系统所有串口及其描述信息（用于调试）。
     */
    public static List<String> scanSerialPortsDetailed() {
        List<String> info = new java.util.ArrayList<>();
        for (SerialPort sp : SerialPort.getCommPorts()) {
            info.add(String.format("%s [%s] (%s)",
                    sp.getSystemPortName(),
                    sp.getPortDescription(),
                    sp.getDescriptivePortName()));
        }
        return info;
    }

    /**
     * 判断这个串口对应的 USB 设备是否是 InlayLink 读写器。
     * <p>
     * 检测策略（按可靠性从高到低）：
     *  1. Linux: 读 /sys/class/tty/{name}/device/.../{idVendor,idProduct}
     *     如果是 2fe3:0100 → 100% 确认是 InlayLink RFID
     *  2. 端口描述名包含 "inlay" / "rfid" / "nordic" 关键字
     */
    private boolean isInlayLinkPort(SerialPort sp) {
        String name = sp.getSystemPortName();
        String desc = (sp.getPortDescription() + " " + sp.getDescriptivePortName()).toLowerCase();

        // 策略 1：Linux 通过 sysfs 读 USB VID/PID（最可靠）
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            try {
                String[] vidPid = readUsbVidPid(name);
                if (vidPid != null) {
                    String vid = vidPid[0].toLowerCase();
                    String pid = vidPid[1].toLowerCase();
                    log.debug("串口 {} USB VID:PID = {}:{}", name, vid, pid);
                    // InlayLink 读写器: VID=2fe3 (NordicSemiconductor)
                    // 只要 VID 是 2fe3 就当作 InlayLink 设备
                    if ("2fe3".equals(vid)) {
                        return true;
                    }
                    // 已经成功读到 VID/PID 但不匹配 → 明确不是 InlayLink
                    return false;
                }
            } catch (Exception e) {
                log.debug("读取 USB VID/PID 失败 ({}): {}", name, e.getMessage());
                // 读不到就 fallback 到描述名匹配
            }
        }

        // 策略 2：描述名包含 inlay/rfid/nordic
        return desc.contains("inlay") || desc.contains("rfid") || desc.contains("nordic");
    }

    /**
     * Linux 下通过 sysfs 读串口对应 USB 设备的 VID/PID。
     * 路径示例：/sys/class/tty/ttyACM2/device/../idVendor
     *
     * @return [vid, pid] 或 null（不是 USB 设备/读不到）
     */
    private String[] readUsbVidPid(String portName) {
        try {
            // 不带 /dev/ 前缀
            String shortName = portName.startsWith("/dev/")
                    ? portName.substring(5) : portName;
            java.io.File devLink = new java.io.File("/sys/class/tty/" + shortName + "/device");
            if (!devLink.exists()) return null;

            // /sys/class/tty/ttyACM2/device 是 tty 子设备，要再往上找一级才有 idVendor
            // 用 canonicalPath 解析符号链接到真实路径，然后逐级往上找
            java.io.File current = devLink.getCanonicalFile();
            for (int i = 0; i < 6 && current != null; i++) {
                java.io.File vidFile = new java.io.File(current, "idVendor");
                java.io.File pidFile = new java.io.File(current, "idProduct");
                if (vidFile.exists() && pidFile.exists()) {
                    String vid = new String(java.nio.file.Files.readAllBytes(vidFile.toPath())).trim();
                    String pid = new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim();
                    return new String[]{vid, pid};
                }
                current = current.getParentFile();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 自动发现：
     * 1. 先在所有串口里挑出 InlayLink 设备（按 USB VID:PID 识别，不依赖端口名）
     * 2. 对匹配的端口尝试常用波特率连接
     * 3. 如果一个 InlayLink 设备都没找到，fallback 到遍历所有串口
     *
     * @return 连接成功的串口路径，失败返回 null
     */
    public String autoConnect() {
        SerialPort[] allPorts = SerialPort.getCommPorts();
        if (allPorts.length == 0) {
            return null;
        }

        // 第一轮：只挑 InlayLink 设备
        List<SerialPort> candidates = new java.util.ArrayList<>();
        for (SerialPort sp : allPorts) {
            if (isInlayLinkPort(sp)) {
                candidates.add(sp);
                log.info("识别到 InlayLink 读写器: {} [{}]",
                        sp.getSystemPortName(), sp.getPortDescription());
            }
        }

        // 第二轮：如果没匹配到，fallback 遍历所有非系统端口
        if (candidates.isEmpty()) {
            log.warn("未通过 USB VID/PID 识别到 InlayLink 设备，尝试遍历所有串口...");
            for (SerialPort sp : allPorts) {
                String name = sp.getSystemPortName();
                if (name.contains("Bluetooth") || name.contains("debug-console")) continue;
                candidates.add(sp);
            }
        }

        for (SerialPort sp : candidates) {
            String port = sp.getSystemPortName();
            for (int baud : COMMON_BAUD_RATES) {
                if (connect(port, baud)) {
                    actualSerialPort = port;
                    return port;
                }
                try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // =========================================================================
    // 连接状态监听器
    // =========================================================================

    public interface ConnectionListener {
        /** 连接建立成功时回调 */
        void onConnected();
        /** 连接断开时回调 */
        void onDisconnected();
    }

    public void addConnectionListener(ConnectionListener listener) {
        connectionListeners.add(listener);
    }

    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    // =========================================================================
    // 连接管理
    // =========================================================================

    /**
     * 启动后台重连 + 保活。该方法非阻塞，立即返回。
     * <p>
     * 启动一个周期任务（由线程池调度），每 1 秒检查一次：
     *  - 已连接 → 心跳检测 + 文件存在性校验
     *  - 未连接 → 按间隔重试连接
     * <p>
     * 同时启动一个保活线程（非 daemon），防止 JVM 退出。
     */
    public void startReconnectService() {
        if (!reconnectRunning.compareAndSet(false, true)) {
            log.info("后台重连服务已在运行");
            return;
        }

        shutdown = false;

        // 启动保活线程（非 daemon，唯一作用：阻止 JVM 退出）
        keepaliveThread = new Thread(() -> {
            while (!shutdown) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "rfid-keepalive");
        keepaliveThread.setDaemon(false);
        keepaliveThread.start();

        // 启动周期重连任务（每 1 秒执行一次）
        reconnectTask = scheduler.scheduleWithFixedDelay(
                this::reconnectTick,
                0, 1, TimeUnit.SECONDS);

        if (properties.getReconnect().isEnabled()) {
            log.info("后台重连已启动，串口: {}", properties.getSerialPort());
        } else {
            log.info("后台保活已启动，自动重连关闭");
        }
    }

    /**
     * 关闭后台重连 + 断开当前连接。
     */
    @PreDestroy
    public synchronized void shutdown() {
        shutdown = true;
        disconnect();

        // 取消周期任务
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }

        // 关闭线程池
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 通知保活线程退出
        if (keepaliveThread != null) {
            keepaliveThread.interrupt();
            keepaliveThread = null;
        }

        reconnectRunning.set(false);
    }

    /**
     * 阻塞等待连接建立，直到成功或超时。
     *
     * @param timeoutMs 超时毫秒数，<=0 表示无限等待
     * @return true 表示连接成功，false 表示超时
     */
    public boolean waitForConnection(long timeoutMs) {
        if (connected) return true;
        log.info("等待读写器连接建立...");
        long start = System.currentTimeMillis();
        while (!connected) {
            if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) {
                log.warn("等待连接超时（{}ms）", timeoutMs);
                return false;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.info("读写器连接已就绪");
        return true;
    }

    /**
     * 按配置文件连接：
     *  - serial-port = "auto"：自动扫描所有串口 + 多波特率
     *  - 否则按配置的固定端口和波特率连接
     */
    public synchronized boolean connect() {
        String port = properties.getSerialPort();
        if (port == null || port.trim().isEmpty() || "auto".equalsIgnoreCase(port.trim())) {
            return autoConnect() != null;
        }
        return connect(port, properties.getBaudRate());
    }

    public synchronized boolean connect(String serialPort, int baudRate) {
        if (connected) {
            return true;
        }
        try {
            reader = ReaderImpl.create();
            connected = reader.connect(new SerialPortHandle(serialPort, baudRate));
            if (connected) {
                actualSerialPort = serialPort;
                // 只在连接成功时打印一次
                log.info("读写器连接成功: {} @ {}", serialPort, baudRate);
                // 重连成功，重置 flag，下次断开时再打一次
                disconnectLogged = false;
                fireOnConnected();
            } else {
                // 连接失败，静默（重连场景下避免刷屏）
                reader = null;
            }
        } catch (Exception e) {
            // 异常也静默，由调用方决定是否打印
            connected = false;
            reader = null;
        }
        return connected;
    }

    public synchronized void disconnect() {
        if (!connected || reader == null) return;
        try {
            if (reader.isReading()) {
                reader.stopInventory(null, null);
            }
            reader.disconnect();
        } catch (Exception e) {
            log.debug("断开读写器时发生异常: {}", e.getMessage());
        } finally {
            connected = false;
            reader = null;
            reading.set(false);
            // 只在第一次断开时打印一次「等待重连」
            if (!disconnectLogged) {
                log.warn("读写器已断开 ({}), 后台持续重连中...", actualSerialPort);
                disconnectLogged = true;
            }
            fireOnDisconnected();
        }
    }

    public boolean isConnected() { return connected; }
    public boolean isReading() { return reading.get(); }
    public Reader rawReader() { return reader; }
    public String getActualSerialPort() { return actualSerialPort; }

    // =========================================================================
    // 周期重连任务（线程池调度，每 1 秒触发一次）
    // =========================================================================

    /** 上一次重连尝试的时间戳，用于控制重连间隔 */
    private long lastReconnectAttempt = 0;
    /** 重连尝试计数 */
    private int reconnectAttempt = 0;
    /** 上一次心跳时间 */
    private long lastHeartbeat = 0;
    /**
     * 是否已经打印过「断开等待重连」日志。
     *  - 断开瞬间置 true，只打印一次「读写器已断开，等待重连...」
     *  - 重连成功瞬间置 false，只打印一次「读写器连接成功」
     * 避免每次重试都刷屏。
     */
    private volatile boolean disconnectLogged = false;

    private void reconnectTick() {
        if (shutdown) return;

        if (connected) {
            long now = System.currentTimeMillis();

            // 已连接：定期心跳检测
            if (now - lastHeartbeat > 2000) {
                lastHeartbeat = now;
                if (!isPortStillPresent()) {
                    log.warn("串口 {} 已从系统消失，断开连接", actualSerialPort);
                    disconnect();
                    return;
                }
                if (!tryHeartbeat()) {
                    log.warn("读写器心跳无响应，断开连接");
                    disconnect();
                    return;
                }
            }
            return;
        }

        // 未连接：按配置间隔重试
        if (!properties.getReconnect().isEnabled()) return;

        int intervalSec = properties.getReconnect().getIntervalSeconds();
        int maxAttempts = properties.getReconnect().getMaxAttempts();

        if (maxAttempts > 0 && reconnectAttempt >= maxAttempts) {
            // 达到上限，不再重试
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastReconnectAttempt < intervalSec * 1000L) {
            return; // 还没到重试间隔，静默等待
        }
        lastReconnectAttempt = now;

        reconnectAttempt++;
        // 不打印每次重试，避免刷屏；只在断开瞬间和重连成功瞬间打印

        if (connect()) {
            reconnectAttempt = 0; // 成功，重置计数
            // connect() 内部会打印 "读写器连接成功"，并触发 onConnected
            // 重连成功，重置 flag，下次断开时再打一次
            disconnectLogged = false;
        }
        // 失败时不打印任何信息，静默等待下次重试
    }

    /**
     * 重启持续读取：停止盘点，短暂等待后重新开始。
     */
    public boolean restartReading() {
        ensureConnected();
        return restartReadingInternal("manual");
    }

    /**
     * 内部重启逻辑。reason 仅用于日志标识。
     */
    private synchronized boolean restartReadingInternal(String reason) {
        if (!connected || reader == null) {
            return false;
        }
        lastReadingRestartTime.set(System.currentTimeMillis());
        log.info("重启持续盘点，原因: {}", reason);
        try {
            if (reading.get()) {
                stopReading();
            }
            TimeUnit.MILLISECONDS.sleep(500);
            boolean ok = startReading();
            if (ok) {
                lastTagCallbackTime.set(System.currentTimeMillis());
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 检测当前已连接的串口是否还存在于系统中。
     * USB 拔掉后：
     *  - Linux/Mac: /dev/* 文件直接消失
     *  - Windows: 设备从串口列表里消失
     * 优先用文件系统检查（最实时），失败时回退到串口扫描。
     */
    private boolean isPortStillPresent() {
        if (actualSerialPort == null) return true; // 还没记录端口名，跳过检测
        try {
            // 1. 类 Unix：直接检查设备文件
            if (actualSerialPort.startsWith("/dev/")) {
                return new java.io.File(actualSerialPort).exists();
            }
            // 2. Mac 上 jSerialComm 返回的是 cu.xxx 不带 /dev/ 前缀
            java.io.File devFile = new java.io.File("/dev/" + actualSerialPort);
            if (devFile.exists()) return true;

            // 3. Windows 或异常情况，回退到串口扫描
            for (SerialPort sp : SerialPort.getCommPorts()) {
                if (actualSerialPort.equals(sp.getSystemPortName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("检查串口存在性时异常: {}", e.getMessage());
            return true; // 异常时不判定为丢失，避免误判
        }
        return false;
    }

    /**
     * 心跳检测：向读写器发一条查询命令，超时 1.5 秒无响应视为断开。
     * 即使正在盘点中也不影响，SDK 内部会排队。
     */
    private boolean tryHeartbeat() {
        if (reader == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean alive = new AtomicBoolean(false);
        try {
            reader.getVersion(
                    v -> { alive.set(true); latch.countDown(); },
                    f -> { alive.set(false); latch.countDown(); });
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
        return alive.get();
    }

    // =========================================================================
    // 设备信息
    // =========================================================================

    public void printVersion() {
        ensureConnected();
        reader.getVersion(
                v -> log.info("版本信息: firmware={}, hardware={}, software={}",
                        v.getFirmwareVersion(), v.getHardwareVersion(), v.getSoftwareVersion()),
                f -> log.warn("获取版本失败: {}", f));
    }

    public void printHardwareInfo() {
        ensureConnected();
        reader.getHardwareInfo(
                info -> log.info("硬件信息: {}", info),
                f -> log.warn("获取硬件信息失败: {}", f));
    }

    // =========================================================================
    // 基础参数配置
    // =========================================================================

    public void applyQueryAndQConfig() {
        ensureConnected();

        QueryConfig queryConfig = new QueryConfig.Builder()
                .setSession(properties.getQuery().getSession())
                .setTarget(properties.getQuery().getTarget())
                .build();
        reader.setQuery(queryConfig,
                s -> log.info("设置 Query 成功: {}", s),
                f -> log.warn("设置 Query 失败: {}", f));

        QConfig qConfig = new QConfig.Builder()
                .setInit(properties.getQ().getInit())
                .setMax(properties.getQ().getMax())
                .setMin(properties.getQ().getMin())
                .build();
        reader.setQ(qConfig,
                s -> log.info("设置 Q 成功: {}", s),
                f -> log.warn("设置 Q 失败: {}", f));
    }

    /**
     * 动态统一修改所有配置天线端口的功率（运行时，不需要重启程序）。
     *
     * @param power 功率，单位 0.01 dBm（如 1800 = 18 dBm）
     * @return 每个天线端口的配置应用结果
     */
    public java.util.List<AntennaSetResultVO> setAllAntennaPower(int power) {
        ensureConnected();
        if (power < 0 || power > 3300) {
            throw new IllegalArgumentException("功率必须在 0~3300 之间 (0~33 dBm)，当前: " + power);
        }

        java.util.List<AntennaSetResultVO> results = new java.util.ArrayList<>();
        for (RfidProperties.Antenna ant : properties.getAntennas()) {
            results.add(new AntennaSetResultVO(ant.getId(), applyAntennaPower(ant.getId(), power)));
        }
        return results;
    }

    /**
     * 向 SDK 应用单个天线端口的功率。
     * 这是统一功率下发的内部实现，不对接口层暴露单根天线调节场景。
     */
    private boolean applyAntennaPower(int antId, int power) {
        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        AntConfig antConfig = new AntConfig.Builder()
                .setAntId(antId)
                .setPower(power)
                .setEnable(power > 0)
                .build();

        reader.setAntConfig(antConfig,
                s -> {
                    ok.set(true);
                    log.info("天线 ANT{} 功率已动态修改为 {} dBm", antId, power / 100.0);
                    // 同步更新 properties 内存中的值（下次重启会用 yml，运行时保持一致）
                    for (RfidProperties.Antenna ant : properties.getAntennas()) {
                        if (ant.getId() == antId) {
                            ant.setPower(power);
                            break;
                        }
                    }
                    latch.countDown();
                },
                f -> {
                    log.warn("天线 ANT{} 功率修改失败: {}", antId, f);
                    latch.countDown();
                });

        try {
            latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    /**
     * 查询当前内存中所有天线端口的配置（来自 yml + 运行时统一修改后的值）。
     */
    public java.util.List<RfidProperties.Antenna> getAntennaConfigs() {
        return new java.util.ArrayList<>(properties.getAntennas());
    }

    // =========================================================================
    // 盘点（Inventory）
    // =========================================================================

    public void setInventoryCallback(java.util.function.Consumer<InventoryTag> callback) {
        ensureConnected();
        reader.setInventoryCallback(callback::accept);
    }

    /**
     * 开始持续读取。读取到的新 EPC 会写入 tagEvents，并通过 /api/rfid/tags 增量返回。
     */
    public boolean startReading() {
        ensureConnected();
        if (!reading.compareAndSet(false, true)) {
            return true;
        }
        try {
            reader.setSelectMode(Select.SELECT_ALL, null, null);
            lastTagCallbackTime.set(System.currentTimeMillis());
            continuousCallback = tag -> {
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
            };
            reader.setInventoryCallback(continuousCallback);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean(false);
            reader.startInventory(
                    s -> { ok.set(true); latch.countDown(); },
                    f -> { log.error("启动盘点失败: {}", f); latch.countDown(); });
            latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (ok.get()) {
                log.info("持续盘点已启动，等待标签...");
                return true;
            }
            reading.set(false);
            return false;
        } catch (Exception e) {
            reading.set(false);
            log.error("开启持续盘点异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 停止持续读取。 */
    public boolean stopReading() {
        ensureConnected();
        if (!reading.compareAndSet(true, false)) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        try {
            if (reader.isReading()) {
                reader.stopInventory(
                        s -> { log.info("持续盘点已停止"); latch.countDown(); },
                        f -> { ok.set(false); log.warn("停止盘点失败: {}", f); latch.countDown(); });
                latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            ok.set(false);
            log.warn("停止盘点异常: {}", e.getMessage());
        }
        return ok.get();
    }

    /** 清空已读 EPC 与页面事件，下次读到同一 EPC 会重新作为新标签。 */
    public void clearTags() {
        seenEpcs.clear();
        tagEvents.clear();
        totalReads.set(0);
        sequence.set(0);
        log.info("已清空 EPC 读取记录");
    }

    /** 获取当前运行状态。 */
    public RfidStatusVO getStatus() {
        RfidStatusVO status = new RfidStatusVO();
        status.setConnected(connected);
        status.setReading(reading.get());
        status.setActualSerialPort(actualSerialPort);
        status.setTotalReads(totalReads.get());
        status.setUniqueCount(seenEpcs.size());
        status.setLatestSeq(sequence.get());
        long lastCallback = lastTagCallbackTime.get();
        status.setLastTagCallbackTime(lastCallback);
        status.setLastTagCallbackAgoSeconds(lastCallback > 0 ? (System.currentTimeMillis() - lastCallback) / 1000 : -1);
        status.setLastReadingRestartTime(lastReadingRestartTime.get());

        int antennaPower = 0;
        boolean powerUniform = true;
        java.util.List<RfidProperties.Antenna> antennas = properties.getAntennas();
        if (antennas != null && !antennas.isEmpty()) {
            antennaPower = antennas.stream().mapToInt(RfidProperties.Antenna::getPower).max().orElse(0);
            for (RfidProperties.Antenna ant : antennas) {
                if (ant.getPower() != antennaPower) {
                    powerUniform = false;
                    break;
                }
            }
        }
        status.setAntennaPower(antennaPower);
        status.setAntennaPowerDbm(antennaPower / 100.0);
        status.setAntennaCount(antennas == null ? 0 : antennas.size());
        status.setAntennaPowerUniform(powerUniform);
        return status;
    }

    /** 获取 sequence 大于 since 的新标签事件。 */
    public java.util.List<TagEventVO> getTagEventsSince(long since) {
        java.util.List<TagEventVO> result = new java.util.ArrayList<>();
        for (TagEventVO event : tagEvents) {
            if (event.getSeq() > since) {
                result.add(event);
            }
        }
        return result;
    }

    private void trimTagEvents() {
        while (tagEvents.size() > MAX_TAG_EVENTS) {
            tagEvents.pollFirst();
        }
    }

    public void startInventory(Consumer<Success> onSuccess, Consumer<Failure> onFailure) {
        ensureConnected();
        reader.startInventory(onSuccess, onFailure);
    }

    public void stopInventory(Consumer<Success> onSuccess, Consumer<Failure> onFailure) {
        ensureConnected();
        if (reader.isReading()) {
            reader.stopInventory(onSuccess, onFailure);
        }
    }

    public List<InventoryTag> inventoryFor(int durationSeconds) {
        ensureConnected();
        ConcurrentLinkedQueue<InventoryTag> tags = new ConcurrentLinkedQueue<>();

        // 重要：先清除任何 select 过滤，确保盘点能读到所有标签
        try {
            reader.setSelectMode(Select.SELECT_ALL, null, null);
        } catch (Exception e) {
            log.debug("清除 select 模式异常（忽略）: {}", e.getMessage());
        }

        // 每读到一个标签实时打印（带去重统计）
        java.util.Set<String> seen = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        reader.setInventoryCallback(tag -> {
            tags.add(tag);
            String epc = tag.getEpc();
            if (seen.add(epc)) {
                log.info("【新标签】 EPC={}, RSSI={}, 天线={}", epc, tag.getRssi(), tag.getAnt());
            } else {
                log.debug("（重复） EPC={}, RSSI={}", epc, tag.getRssi());
            }
        });

        log.info("开始盘点，持续 {} 秒... 请把标签放在天线附近", durationSeconds);
        CountDownLatch startLatch = new CountDownLatch(1);
        reader.startInventory(
                s -> { log.info("startInventory 成功，开始读卡"); startLatch.countDown(); },
                f -> { log.error("startInventory 失败: {}（检查天线是否连接、功率配置）", f); startLatch.countDown(); });
        try {
            startLatch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            TimeUnit.SECONDS.sleep(durationSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        CountDownLatch latch = new CountDownLatch(1);
        reader.stopInventory(
                s -> { log.info("停止盘点"); latch.countDown(); },
                f -> { log.warn("stopInventory fail: {}", f); latch.countDown(); });
        try {
            latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("盘点结束：共 {} 次读取，{} 个不同 EPC", tags.size(), seen.size());
        if (seen.isEmpty()) {
            log.warn("===========================================");
            log.warn(" 没有读到任何标签，请排查：");
            log.warn(" 1. 天线是否接好（必须接到 ANT0 端口，且接头拧紧）");
            log.warn(" 2. 标签是否在天线正前方 5-50 cm 范围内");
            log.warn(" 3. 标签是否是 UHF Gen2 协议（860-960MHz）");
            log.warn(" 4. 天线功率是否够（当前: {}，可改 rfid.antenna.power=3000 试试）", properties.getAntenna().getPower());
            log.warn(" 5. 是否设置了错误的 Session/Target（当前: {}/{}）",
                    properties.getQuery().getSession(), properties.getQuery().getTarget());
            log.warn("===========================================");
        }
        return new java.util.ArrayList<>(tags);
    }

    // =========================================================================
    // 单标签读 / 写
    // =========================================================================

    public void selectTag(String epc) {
        ensureConnected();
        reader.setSelectMode(Select.SELECT_ASSERTED, null, null);
        reader.selectTag(
                new SelectConfig.Builder().setMemBank(MemBank.EPC).setData(epc).build(),
                s -> log.debug("selectTag ok: {}", s),
                f -> log.warn("selectTag fail: {}", f));
    }

    public ReadTag readTag(MemBank bank, int wordAddress, int wordLength) {
        ensureConnected();
        AtomicReference<ReadTag> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ReadConfig config = new ReadConfig.Builder()
                .setMemBank(bank)
                .setWordAddress(wordAddress)
                .setWordLength(wordLength)
                .build();

        reader.readTag(config,
                s -> { result.set(s); latch.countDown(); },
                f -> {
                    log.warn("readTag fail: bank={}, addr={}, len={}, err={}", bank, wordAddress, wordLength, f);
                    latch.countDown();
                });

        try {
            if (!latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("readTag 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    public boolean writeTag(MemBank bank, String data, int wordAddress, int wordLength) {
        ensureConnected();
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);

        WriteConfig config = new WriteConfig.Builder()
                .setMemBank(bank)
                .setData(data)
                .setWordAddress(wordAddress)
                .setWordLength(wordLength)
                .build();

        reader.writeTag(config,
                s -> { log.info("writeTag ok: {}", s); ok.set(true); latch.countDown(); },
                f -> { log.warn("writeTag fail: {}", f); latch.countDown(); });

        try {
            latch.await(DEFAULT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    public WrittenTag writeTagBlock(MemBank bank, String data, int wordAddress, int wordLength) {
        ensureConnected();
        AtomicReference<WrittenTag> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        WriteConfig config = new WriteConfig.Builder()
                .setMemBank(bank)
                .setData(data)
                .setWordAddress(wordAddress)
                .setWordLength(wordLength)
                .build();

        reader.writeTagBlock(config,
                s -> { result.set(s); latch.countDown(); },
                f -> { log.warn("writeTagBlock fail: {}", f); latch.countDown(); });

        try {
            latch.await(DEFAULT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    // =========================================================================
    // 公开访问器（task 包使用）
    // =========================================================================

    /**
     * 获取当前 Reader 实例。
     */
    public Reader getReader() {
        return reader;
    }

    /**
     * 获取调度器线程池。
     */
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    /**
     * 获取当前持续模式的 callback 引用。
     */
    public InventoryHandle getContinuousCallback() {
        return continuousCallback;
    }

    /**
     * 设置当前持续模式的 callback 引用。
     */
    public void setContinuousCallback(InventoryHandle callback) {
        this.continuousCallback = callback;
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    private void ensureConnected() {
        if (!connected || reader == null) {
            throw new IllegalStateException("读写器未连接，请先连接读写器");
        }
    }

    private void fireOnConnected() {
        for (ConnectionListener l : connectionListeners) {
            try { l.onConnected(); } catch (Exception ignored) {}
        }
    }

    private void fireOnDisconnected() {
        for (ConnectionListener l : connectionListeners) {
            try { l.onDisconnected(); } catch (Exception ignored) {}
        }
    }
}
package com.cyu.inlayrfid.service;

import com.cyu.inlayrfid.config.ThreadPoolConfig.RejectedExecutionException;
import com.cyu.inlayrfid.task.ScanTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 一次性扫描编排服务。
 * <p>
 * 外部服务调用 {@link #scanAsync()} 提交扫描任务，立即返回 requestId；
 * 扫描到新标签或超时（35s）后，自动 POST 结果到配置的回调地址。
 * <p>
 * 扫描任务运行在独立的 scanExecutor 线程池中，不阻塞 Tomcat 线程。
 * 出错时回调 payload 中会携带 error 字段，调用方应清空缓存重新发起扫描。
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final RfidService rfidService;
    private final ThreadPoolExecutor scanExecutor;
    private final RestTemplate restTemplate;
    private final String callbackUrl;

    @Autowired
    public ScanService(RfidService rfidService,
                       @Qualifier("scanExecutor") ThreadPoolExecutor scanExecutor,
                       @Qualifier("restTemplate") RestTemplate restTemplate,
                       @Value("${scan.callback-url}") String callbackUrl) {
        this.rfidService = rfidService;
        this.scanExecutor = scanExecutor;
        this.restTemplate = restTemplate;
        this.callbackUrl = callbackUrl;
    }

    /**
     * 提交一次性扫描任务。
     * <p>
     * 立即返回 requestId，扫描结果通过配置的回调地址通知调用方。
     * 超时 35 秒未读到 EPC 算失败，回调中携带 error 信息。
     * 扫描线程池满载时抛出 {@link RejectedExecutionException}，由 Controller 层处理。
     *
     * @return requestId 唯一标识，调用方用它匹配回调
     */
    public String scanAsync() {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        scanExecutor.submit(new ScanTask(rfidService, restTemplate, requestId, callbackUrl));
        log.info("提交扫描任务 requestId={}, callbackUrl={}", requestId, callbackUrl);
        return requestId;
    }
}
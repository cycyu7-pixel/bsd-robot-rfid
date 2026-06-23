package com.cyu.inlayrfid.controller;

import com.cyu.inlayrfid.config.RfidProperties;
import com.cyu.inlayrfid.service.RfidService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFID 读写器 REST 接口（给前端用）
 * <p>
 * 前端约定：
 *  - 功率字段统一用整数 dBm，范围 0~33
 *  - 后端自动 ×100 转成 SDK 需要的 0.1 dBm 单位
 */
@RestController
@RequestMapping("/api/rfid")
public class RfidController {

    private static final Logger log = LoggerFactory.getLogger(RfidController.class);

    private final RfidService rfidService;
    private final RfidProperties properties;

    @Autowired
    public RfidController(RfidService rfidService, RfidProperties properties) {
        this.rfidService = rfidService;
        this.properties = properties;
    }

    // =========================================================================
    // 健康检查
    // =========================================================================

    /**
     * GET /api/rfid/health
     * 返回固定字符串 "healthy"
     */
    @GetMapping("/health")
    public String health() {
        return "healthy";
    }

    // =========================================================================
    // 单根天线功率修改
    // =========================================================================

    /**
     * POST /api/rfid/antennas/{antId}/power
     * Body: { "power": 18 }      ← 0~33 的整数，单位 dBm
     *
     * @param antId 天线 ID (0/1/2/3)
     * @param body  JSON，必填 power 字段（0~33 整数）
     */
    @PostMapping("/antennas/{antId}/power")
    public Map<String, Object> setAntennaPower(
            @PathVariable int antId,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("antId", antId);

        if (!rfidService.isConnected()) {
            result.put("success", false);
            result.put("message", "读写器未连接");
            return result;
        }

        Object powerObj = body.get("power");
        if (powerObj == null) {
            result.put("success", false);
            result.put("message", "缺少 power 字段");
            return result;
        }

        int powerDbm;
        try {
            powerDbm = Integer.parseInt(String.valueOf(powerObj));
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "power 必须是 0~33 的整数");
            return result;
        }

        if (powerDbm < 0 || powerDbm > 33) {
            result.put("success", false);
            result.put("message", "power 范围 0~33，当前: " + powerDbm);
            return result;
        }

        // 前端传 dBm，SDK 需要 0.1 dBm，×100
        int powerRaw = powerDbm * 100;
        try {
            boolean ok = rfidService.setAntennaPower(antId, powerRaw);
            result.put("success", ok);
            result.put("powerDbm", powerDbm);
            result.put("message", ok ? "修改成功" : "修改失败");
        } catch (Exception e) {
            log.error("修改天线功率异常: antId={}, power={}dBm, err={}", antId, powerDbm, e.getMessage());
            result.put("success", false);
            result.put("message", "修改异常: " + e.getMessage());
        }
        return result;
    }

    // =========================================================================
    // 所有天线统一功率修改
    // =========================================================================

    /**
     * POST /api/rfid/antennas/power
     * Body: { "power": 18 }      ← 0~33 的整数，单位 dBm
     *
     * 把所有天线统一改成指定功率
     */
    @PostMapping("/antennas/power")
    public Map<String, Object> setAllAntennasPower(@RequestBody Map<String, Object> body) {

        Map<String, Object> result = new LinkedHashMap<>();

        if (!rfidService.isConnected()) {
            result.put("success", false);
            result.put("message", "读写器未连接");
            return result;
        }

        Object powerObj = body.get("power");
        if (powerObj == null) {
            result.put("success", false);
            result.put("message", "缺少 power 字段");
            return result;
        }

        int powerDbm;
        try {
            powerDbm = Integer.parseInt(String.valueOf(powerObj));
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "power 必须是 0~33 的整数");
            return result;
        }

        if (powerDbm < 0 || powerDbm > 33) {
            result.put("success", false);
            result.put("message", "power 范围 0~33，当前: " + powerDbm);
            return result;
        }

        // 前端传 dBm，SDK 需要 0.1 dBm，×100
        int powerRaw = powerDbm * 100;
        Map<Integer, Integer> antPowerMap = new LinkedHashMap<>();
        for (RfidProperties.Antenna ant : properties.getAntennas()) {
            antPowerMap.put(ant.getId(), powerRaw);
        }

        Map<Integer, Boolean> results = rfidService.setAntennaPowers(antPowerMap);

        // 整理返回结果（用 dBm 显示，方便前端）
        Map<Integer, Boolean> resultsReadable = new LinkedHashMap<>();
        for (Map.Entry<Integer, Boolean> e : results.entrySet()) {
            resultsReadable.put(e.getKey(), e.getValue());
        }

        result.put("results", resultsReadable);
        result.put("powerDbm", powerDbm);
        result.put("success", results.values().stream().allMatch(Boolean::booleanValue));
        return result;
    }
}
package com.cyu.inlayrfid.controller;

import com.cyu.inlayrfid.config.RfidProperties;
import com.cyu.inlayrfid.entity.dto.AntennaPowerDTO;
import com.cyu.inlayrfid.entity.vo.AntennaPowerBatchVO;
import com.cyu.inlayrfid.entity.vo.AntennaPowerVO;
import com.cyu.inlayrfid.entity.vo.AntennaSetResultVO;
import com.cyu.inlayrfid.entity.vo.OperationVO;
import com.cyu.inlayrfid.entity.vo.Result;
import com.cyu.inlayrfid.entity.vo.RfidStatusVO;
import com.cyu.inlayrfid.entity.vo.TagEventsVO;
import com.cyu.inlayrfid.service.RfidService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFID 读写器 REST 接口。
 * power 统一使用 dBm 整数，范围 0~33。
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

    /**
     * 健康检查，用于判断服务是否存活。
     */
    @GetMapping("/health")
    public String health() {
        return "healthy";
    }

    /**
     * 查询 RFID 当前状态：连接状态、读取状态、串口、读取次数、天线功率等。
     */
    @GetMapping("/status")
    public Result<RfidStatusVO> status() {
        return Result.success(rfidService.getStatus());
    }

    /**
     * 开始持续读取标签。
     * 读写器未连接时返回失败。
     */
    @PostMapping("/reading/start")
    public Result<OperationVO> startReading() {
        if (!rfidService.isConnected()) {
            return Result.fail("读写器未连接");
        }
        boolean ok = rfidService.startReading();
        OperationVO data = new OperationVO(rfidService.isReading());
        return ok ? Result.success("已开始读取", data) : Result.fail("开始读取失败");
    }

    /**
     * 停止持续读取标签。
     * 读写器未连接时返回失败。
     */
    @PostMapping("/reading/stop")
    public Result<OperationVO> stopReading() {
        if (!rfidService.isConnected()) {
            return Result.fail("读写器未连接");
        }
        boolean ok = rfidService.stopReading();
        OperationVO data = new OperationVO(rfidService.isReading());
        return ok ? Result.success("已停止读取", data) : Result.fail("停止读取失败");
    }

    /**
     * 重启持续读取标签。
     * 适用于读写器还连接着但长时间没有标签回调的场景。
     */
    @PostMapping("/reading/restart")
    public Result<OperationVO> restartReading() {
        if (!rfidService.isConnected()) {
            return Result.fail("读写器未连接");
        }
        boolean ok = rfidService.restartReading();
        OperationVO data = new OperationVO(rfidService.isReading());
        return ok ? Result.success("已重启读取", data) : Result.fail("重启读取失败");
    }

    /**
     * 增量获取读取到的新 EPC。
     * since 表示上一次拿到的最新序号，前端用它避免重复拉取。
     */
    @GetMapping("/tags")
    public Result<TagEventsVO> tags(@RequestParam(value = "since", defaultValue = "0") long since) {
        TagEventsVO data = new TagEventsVO(rfidService.getStatus().getLatestSeq(), rfidService.getTagEventsSince(since));
        return Result.success(data);
    }

    /**
     * 清空已读 EPC 记录。
     * 清空后，同一个标签再次出现会重新作为新标签返回。
     */
    @PostMapping("/tags/clear")
    public Result<TagEventsVO> clearTags() {
        rfidService.clearTags();
        return Result.success("已清空 EPC 记录", new TagEventsVO(0, java.util.Collections.emptyList()));
    }

    /**
     * 修改单根天线功率。
     * antId 是天线编号，power 单位是 dBm，范围 0~33。
     */
    @PostMapping("/antennas/{antId}/power")
    public Result<AntennaPowerVO> setAntennaPower(
            @PathVariable int antId,
            @RequestBody AntennaPowerDTO dto) {

        if (!rfidService.isConnected()) {
            return Result.fail("读写器未连接");
        }

        Integer powerDbm = parsePowerDbm(dto);
        if (powerDbm == null) {
            return Result.fail("power 必须是 0~33 的整数");
        }

        int powerRaw = powerDbm * 100;
        try {
            boolean ok = rfidService.setAntennaPower(antId, powerRaw);
            AntennaPowerVO data = new AntennaPowerVO(antId, powerDbm);
            return ok ? Result.success("修改成功", data) : Result.fail("修改失败");
        } catch (Exception e) {
            log.error("修改天线功率异常: antId={}, power={}dBm, err={}", antId, powerDbm, e.getMessage());
            return Result.fail("修改异常: " + e.getMessage());
        }
    }

    /**
     * 统一修改所有天线功率。
     * power 单位是 dBm，范围 0~33。
     */
    @PostMapping("/antennas/power")
    public Result<AntennaPowerBatchVO> setAllAntennasPower(@RequestBody AntennaPowerDTO dto) {
        if (!rfidService.isConnected()) {
            return Result.fail("读写器未连接");
        }

        Integer powerDbm = parsePowerDbm(dto);
        if (powerDbm == null) {
            return Result.fail("power 必须是 0~33 的整数");
        }

        int powerRaw = powerDbm * 100;
        Map<Integer, Integer> antPowerMap = new LinkedHashMap<>();
        for (RfidProperties.Antenna ant : properties.getAntennas()) {
            antPowerMap.put(ant.getId(), powerRaw);
        }

        List<AntennaSetResultVO> results = rfidService.setAntennaPowers(antPowerMap);
        AntennaPowerBatchVO data = new AntennaPowerBatchVO(powerDbm, results);
        boolean success = results.stream().allMatch(AntennaSetResultVO::isSuccess);
        return success ? Result.success("修改成功", data) : Result.fail("部分天线修改失败");
    }

    /**
     * 解析并校验 power 参数。
     * 前端传 dBm 整数，校验通过后调用方会乘以 100 转成 SDK 单位。
     */
    private Integer parsePowerDbm(AntennaPowerDTO dto) {
        if (dto == null || dto.getPower() == null) {
            return null;
        }
        Integer powerDbm = dto.getPower();
        if (powerDbm < 0 || powerDbm > 33) {
            return null;
        }
        return powerDbm;
    }
}
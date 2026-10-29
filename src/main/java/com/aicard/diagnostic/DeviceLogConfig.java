package com.aicard.diagnostic;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 设备诊断日志配置。 */
@Component
@Getter
public class DeviceLogConfig {

    /** 总开关；关闭时设备上传返回 E_DEVICE_LOG_DISABLED。 */
    @Value("${device-log.enabled:true}")
    private boolean enabled;

    /** 日志共享卷：多容器部署必须挂同一卷。 */
    @Value("${device-log.storage-dir:/data/device-logs}")
    private String storageDir;

    /** 保留天数，到期删除文件与索引。 */
    @Value("${device-log.retention-days:7}")
    private int retentionDays;

    /** 单次上传请求体上限（压缩后）。 */
    @Value("${device-log.max-bytes:262144}")
    private int maxBytes;

    /** 解压后上限（防 gzip 炸弹）。 */
    @Value("${device-log.max-raw-bytes:1048576}")
    private int maxRawBytes;

    /** 同一设备两次上传的最小间隔（秒）。 */
    @Value("${device-log.rate-limit-seconds:60}")
    private int rateLimitSeconds;

    /** 到期清理 cron。 */
    @Value("${device-log.cleanup-cron:0 40 3 * * *}")
    private String cleanupCron;
}

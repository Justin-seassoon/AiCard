package com.aicard.meeting;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 会议录音配置（共享卷目录、保留期、分片大小、转写语言）。 */
@Component
@Getter
public class MeetingConfig {

    /** 音频共享卷目录：多容器部署必须共用同一卷。 */
    @Value("${meeting.storage-dir:/data/meetings}")
    private String storageDir;

    /** 录音保留天数（到期删除音频，保留转写与纪要）。 */
    @Value("${meeting.retention-days:30}")
    private int retentionDays;

    /** 分片时长（秒）。 */
    @Value("${meeting.chunk-sec:30}")
    private int chunkSec;

    /** 超过该分钟数没有新分片 → 自动结束会议（设备丢失/断电兜底）。 */
    @Value("${meeting.finalize-after-minutes:10}")
    private int finalizeAfterMinutes;

    /** 转写语言。 */
    @Value("${meeting.language:ja-JP}")
    private String language;

    /** 单片最大字节数（30 秒 16k PCM ≈ 960KB）。 */
    @Value("${meeting.max-chunk-bytes:2097152}")
    private int maxChunkBytes;
}

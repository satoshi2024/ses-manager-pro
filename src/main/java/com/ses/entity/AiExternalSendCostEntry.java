package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NF08 外部送信1回分の費用予約と精算（V194 {@code t_ai_external_send_cost_entry}。attemptと1対1）。
 * <p>{@code reservationId} はsummary dispatchのclaim IDと同じUUIDで、小文字36文字表記で保持する。
 * DBのBINARY(16)との変換は {@code AiExternalSendCostEntryMapper} の {@code UUID_TO_BIN} / {@code BIN_TO_UUID(?, 0)}
 * だけが行う（計画 D-2）。予約日のbucket（{@code dayKey} / {@code monthKey}）は作成後に変えない。</p>
 */
@Data
@TableName("t_ai_external_send_cost_entry")
public class AiExternalSendCostEntry {

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_SETTLED = "SETTLED";
    public static final String STATE_KEPT_UPPER_BOUND = "KEPT_UPPER_BOUND";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reservationId;
    private String tenantId;
    private Long runId;
    private String traceId;
    private String costScope;
    private String dayKey;
    private String monthKey;
    private String decisionId;
    private byte[] approvalDigest;
    private Integer reservedYen;
    private Integer settledYen;
    private String entryState;
    private Integer statusVersion;
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;
}

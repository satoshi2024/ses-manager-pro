package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * サービスリクエスト詳細DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestDto {

    private Long id;

    private String requestNo;

    private Long customerId;

    private String customerName;

    private Long contactId;

    private String contactName;

    private Long contractId;

    private String contractNo;

    private Long projectId;

    private String projectName;

    private Long engineerId;

    private String engineerName;

    private String category;

    private String priority;

    private String channel;

    private String subject;

    private String description;

    private Long ownerUserId;

    private String ownerUserName;

    private String status;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    private LocalDateTime reopenedAt;

    private Integer reopenCount;

    private Long portalUserId;

    /**
     * 顧客ポータルへ出す範囲の問い合わせか（{@code PortalServiceRequestVisibilityPolicy} と同じ判定。portal起票、
     * または公開開始時刻以降の作成）。内部画面の「顧客公開対象／社内限定」表示に使う。
     * 顧客ポータルの子スイッチがOFFの間は、trueでも顧客には表示されない。
     */
    private Boolean portalVisible;

    private Long createdBy;

    private String createdByName;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** SLA計時状況 */
    private ServiceSlaClockDto slaClock;

    public ServiceSlaClockDto getCurrentSlaClock() {
        return slaClock;
    }

    public void setCurrentSlaClock(ServiceSlaClockDto slaClock) {
        this.slaClock = slaClock;
    }

    /** コメント一覧 */
    private List<ServiceCommentDto> comments;

    /** CSATスコア */
    private Integer csatScore;

    /** CSAT回答詳細 */
    private String csatComment;
}

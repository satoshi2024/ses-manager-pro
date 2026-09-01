package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定例会・QBR記録エンティティ（t_customer_qbr）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_qbr")
public class CustomerQbr {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 顧客ID */
    private Long customerId;

    /** 会議タイトル */
    private String title;

    /** 開催日 */
    private LocalDate meetingDate;

    /** 参加者 */
    private String attendees;

    /** 議題 */
    private String agenda;

    /** 討議内容 */
    private String discussion;

    /** 決定事項 */
    private String decisions;

    /** 次回予定日 */
    private LocalDate nextMeetingDate;

    /** 作成者ID (sys_user.id) */
    private Long createdBy;

    /** 更新者ID */
    private Long updatedBy;

    /** 作成日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;

    public String getMinutes() {
        return discussion;
    }

    public void setMinutes(String minutes) {
        this.discussion = minutes;
    }

    public String getActionItems() {
        return decisions;
    }

    public void setActionItems(String actionItems) {
        this.decisions = actionItems;
    }

    public static class CustomerQbrBuilder {
        public CustomerQbrBuilder minutes(String minutes) {
            this.discussion = minutes;
            return this;
        }

        public CustomerQbrBuilder actionItems(String actionItems) {
            this.decisions = actionItems;
            return this;
        }
    }
}

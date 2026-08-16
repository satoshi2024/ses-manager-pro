package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 1on1申請と実施記録（t_one_on_one_request）。
 * 状態機械: 申請→日程確定→実施済 / 取消（状態CAS。design §6.3）。
 * private_note_ref はconfidential相談の参照で、通常のRetentionRisk DTOへ出さない（design §5/§6.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_one_on_one_request")
public class OneOnOneRequest extends BaseEntity {

    /** 申請要員ID */
    private Long engineerId;

    /** 相手（担当営業/上長等の内部ユーザー） */
    private Long counterpartUserId;

    /** 候補日JSON（申請時点の希望日一覧） */
    private String candidateDatesJson;

    /** 確定日程（NULL=未確定） */
    private LocalDate scheduledAt;

    /** 申請/日程確定/実施済/取消 */
    private String status;

    /** 要員本人に公開する実施記録 */
    private String employeeVisibleNote;

    /** confidential相談の参照（HR/管理者のみ可視） */
    private String privateNoteRef;
}

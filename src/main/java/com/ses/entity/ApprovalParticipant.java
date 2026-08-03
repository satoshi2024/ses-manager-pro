package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 承認一覧のSQL可視性境界を構成する申請参加者。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_approval_participant")
public class ApprovalParticipant implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long requestId;
    private Long userId;
    /** applicant / approver */
    private String participantRole;
    private Integer roundNo;
}

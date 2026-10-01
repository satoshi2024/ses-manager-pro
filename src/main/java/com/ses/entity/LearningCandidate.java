package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** AIの学習候補。正式skill・配置・承認projectionとは分離したappendableな判断対象。 */
@Data
@TableName("t_learning_candidate")
public class LearningCandidate implements java.io.Serializable {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String tenantId;
    private Long engineerId;
    private Long projectId;
    private Long customerId;
    private LocalDate asOfDate;
    private Long ruleGapSnapshotId;
    private String ruleCourseIdsJson;
    private String aiCourseIdsJson;
    private String snapshotHash;
    private String status;
    private LocalDateTime expiresAt;
    private Long decisionActorUserId;
    private String decisionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deletedFlag;
}

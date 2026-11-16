package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DSAR と本人の明示 link（{@code t_privacy_request_subject_link}、V190）。
 *
 * <p>候補（CANDIDATE）は privacy-admin が subject 参照で登録し、SELECTED / REJECTED は人の裁定だけが書く
 * （氏名の一致で自動結合しない）。論理削除列を持たない表のため、論理削除の対象にしない。
 */
@Data
@TableName("t_privacy_request_subject_link")
public class PrivacyRequestSubjectLink implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long requestId;
    /** 種別付き opaque 参照（例: {@code ENGINEER:123}）。 */
    private String subjectRef;
    /** CANDIDATE / SELECTED / REJECTED。 */
    private String linkState;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionCode;
}

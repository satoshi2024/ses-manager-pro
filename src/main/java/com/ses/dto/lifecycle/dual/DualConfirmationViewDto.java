package com.ses.dto.lifecycle.dual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二者確認の現在状態（NF01細粒度epoch計画 §5.2 A1・A5）。
 *
 * <p>{@code taskGeneration} は提出body（A3/A7）へそのまま渡す現在のタスク世代。projectionの世代が現在と異なる場合
 * （保留・取消・担当変更・免除の後）は {@code stale=true} で、その提出は確認できない。操作可否はlockを取らない読取による
 * 画面表示用の目安であり、実際の可否は提出・確認のattemptが判定する。</p>
 *
 * <p>業務commentの本文は {@code DualConfirmationCommentDisclosure} の表示範囲（確認者のrole・その提出の提出者）にだけ返し、
 * それ以外の照会者には本文をnullにして有無（{@code *CommentPresent}）だけを返す（W3-21b、review P3-3）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DualConfirmationViewDto {
    private Long taskId;
    private Long caseId;
    private String taskStatus;
    private String caseStatus;
    private Long taskGeneration;
    /** projectionの状態（PENDING / CONFIRMED / REJECTED）。提出が無ければnull。 */
    private String state;
    /** projectionの世代が現在のタスク世代と異なる（旧世代の提出）。 */
    private boolean stale;
    private Long submissionId;
    private Integer submissionRevision;
    private String evidenceSha256;
    private Long submitterUserId;
    private LocalDateTime submittedAt;
    private Long documentId;
    private Long documentVersionId;
    private Integer documentVersionNo;
    /** 照会者がcommentの本文を読めるか（読めない場合、本文の2項目はnullで有無だけを返す）。 */
    private boolean commentsVisible;
    /** 提出commentの本文（表示範囲外ならnull）。 */
    private String submissionComment;
    /** 提出commentが記入されているか（表示範囲外でも返す）。 */
    private boolean submissionCommentPresent;
    /** projectionの提出に対する確認・差戻しの記録（無ければnull）。 */
    private String decision;
    private Long confirmerUserId;
    private LocalDateTime decidedAt;
    /** 確認・差戻しcommentの本文（表示範囲外ならnull）。 */
    private String decisionComment;
    /** 確認・差戻しcommentが記入されているか（表示範囲外でも返す）。 */
    private boolean decisionCommentPresent;
    private boolean canSubmit;
    private boolean canDecide;
}

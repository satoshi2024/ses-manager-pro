package com.ses.dto.lifecycle.dual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二者確認の提出・確認・差戻しの結果（NF01細粒度epoch計画 §5.2 A3・A4・A7）。
 *
 * <p>{@code evidenceSha256} は保存済み提出の証跡canonical JSONのhash（小文字hex）で、確認者がA4のbodyへ渡す値である。
 * 文書内容のhash・storage key・title・業務commentは含めない。{@code replayed} は同じ {@code Idempotency-Key}・同じ内容の再送に
 * 保存済みの結果を返した場合にtrue。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DualConfirmationResultDto {
    private Long taskId;
    private Long submissionId;
    private Integer submissionRevision;
    private String evidenceSha256;
    /** projectionの状態（PENDING / CONFIRMED / REJECTED）。再送時に別の提出がprojectionを占めていればnull。 */
    private String state;
    /** 確認・差戻しの記録ID（提出ではnull）。 */
    private Long confirmationId;
    /** 判断（CONFIRMED / REJECTED。提出ではnull）。 */
    private String decision;
    /** 処理後のタスク状態。 */
    private String taskStatus;
    private boolean replayed;
}

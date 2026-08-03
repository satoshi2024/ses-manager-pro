package com.ses.dto.approval;

/** 承認画面・export共通の差分表示行。masked=trueの場合、before/afterは常にnull。 */
public record ApprovalDiffItem(String field, String label, Object before, Object after,
                               boolean changed, boolean masked) {
}

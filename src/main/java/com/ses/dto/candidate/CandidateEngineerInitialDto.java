package com.ses.dto.candidate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 候補者「入社」→エンジニア新規作成画面への初期値引き渡し用DTO。
 * 自動保存はせず、エンジニア新規作成フォームの初期値としてのみ利用する。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateEngineerInitialDto {

    /** 候補者ID(変換完了後にconvertedEngineerIdを紐付けるため) */
    private Long candidateId;

    /** 氏名(t_engineer.fullNameの初期値) */
    private String fullName;

    /** スキル概要(t_engineer.resumeSummaryの初期値) */
    private String resumeSummary;
}

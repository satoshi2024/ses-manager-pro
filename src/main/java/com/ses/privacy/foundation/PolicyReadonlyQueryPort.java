package com.ses.privacy.foundation;

/**
 * 読み取り専用ポリシー照会・評価ポート。
 * 呼び出し元が実行時に Provider やアクターロール、asOf 日時を差し替えることは禁止される。
 */
public interface PolicyReadonlyQueryPort {
    /**
     * 内部に注入された Caller Provider と Clock に基づき、指定された照会要求の政策を評価・説明する。
     *
     * @param query 照会要求パラメータ（テナント、アクターID、対象要素・操作・プロバイダー）
     * @return 政策説明メタデータ（実行権限は含まない）
     */
    PolicyExplanationResult evaluate(PolicyEvaluationQuery query);
}

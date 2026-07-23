package com.ses.service.ai;

/**
 * AIテキスト生成サービスインターフェース。
 * プロバイダ（Gemini / Mock 等）に依存しない共通API。
 */
public interface AiTextService {

    /**
     * プロンプトを送信してテキスト応答を得る。
     *
     * @param prompt 送信するプロンプト
     * @return 生成されたテキスト
     * @throws com.ses.common.exception.BusinessException AI呼び出し失敗時（4xx=設定/入力起因、5xx=外部障害）
     */
    String generate(String prompt);
}

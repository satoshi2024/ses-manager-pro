package com.ses.service.ai;

/**
 * Provider 向け直近プロンプトの検査口。本番では NoOp 実装のみがロードされる（REV-B2.1-P2-002）。
 */
public interface AiOutboundProbe {

    void record(String prompt);

    String lastOutbound();

    void clear();
}

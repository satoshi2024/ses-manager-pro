package com.ses.privacy.foundation;

import java.util.Optional;

/**
 * サービス初期化時に注入される合成呼び出し元プロバイダー。
 * 本スライスでは隔離された合成環境の固定マッピング・テストフィクスチャを用い、実 IdP には接続しない。
 */
@FunctionalInterface
interface SyntheticCallerContextProvider {
    Optional<TrustedCallerContext> getCallerContext();
}

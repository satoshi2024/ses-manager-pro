package com.ses.service.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可視範囲（組織scope / DataScope）の世代番号。
 *
 * <p>Dashboardの集計キャッシュはユーザー単位でキーを分けているが、それだけでは
 * <b>同一ユーザーの権限が縮小した直後</b>に古いキャッシュが返る。組織を異動した、
 * 所属を解除した、担当を外したといった変更のあと、TTL(既定60秒)が切れるまで
 * 旧scopeで計算した他組織のデータを見せ続けてしまう（短時間の越権閲覧）。
 *
 * <p>そこで可視範囲に影響する更新のたびに世代番号を進め、キャッシュキーへ含める。
 * 世代が変われば旧キーには二度と当たらないため、次のアクセスから即座に再計算される。
 *
 * <p>粒度は意図的に全体（ユーザー単位ではない）。組織改編・所属変更は頻度が低く、
 * 取りこぼしのない広めの無効化のほうが、ユーザー単位で漏れを作るより安全なため。
 */
@Component
public class ScopeVersionRegistry {

    private final AtomicLong version = new AtomicLong();

    /** 現在の世代番号。キャッシュキーの構成要素として使う。 */
    public long current() {
        return version.get();
    }

    /** 可視範囲に影響する更新が確定したときに呼ぶ。 */
    public void bump() {
        version.incrementAndGet();
    }
}

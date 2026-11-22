# G2r サービスデスクの補完受入チェックリスト

2026-11-14。実装方針の記録であり、CS Ownerの署名や本番公開の承認を代行しない。
自動browserは統括Runnerだけが実行する。`ServiceDeskBrowserMTest`はdesktopと390pxで起票・詳細・公開返信・内部メモを実送信し、console/API失敗0件を検証する。

## 前提の変更（決定済み（2026-11-22、利用者））

- NF-02 の子の顧客ポータルの問い合わせ（`FEATURE_NF02_CUSTOMER_PORTAL_ENABLED`）も**初回リリースで ON** にする（B-NF02-2 の「第二段階」を変更した）。
- そのため下の「第二段階」の節の確認は、初回配備（go-live）の前の確認になる（第一段階と同じく go-live の前提。**未取得**）。節の名前と項目は変えずに残す。
- 第二段階の1つ目の「第一段階の内部desk公開条件には含めない」は、初回から CSAT の回答も公開の対象になるため、go-live の前に確かめる項目として読む。
- 索引: `production-launch/deploy-runbook.md` §1.7 の NF02 の行、`production-launch/feature-switches.md` 4.3.1。

| 記録 | 値 |
|---|---|
| 対象SHA / 実行run-id | |
| 実施者 / 日時 / 環境 | |
| CS Ownerの正式署名 | |

## 第一段階（内部desk）

- [ ] 全社共通カレンダーが当年度を含む。法人既定がある顧客と無い顧客を用意し、他法人の休日が混入しない。
- [ ] 390px / desktopで実起票・検索・詳細・返信と内部メモの切替を完了する。`target/browser-m-evidence/service-desk/`のrun-id、summary、一覧・起票・詳細PNG、console JSONを保存する。
- [ ] Windows実Chromeの終了直後にprofile削除が遅延しても、元の判定がcleanup例外に置換されない。
- [ ] Edge、Safari、Android、iOSの実機で同じ操作を確認し、横溢れ、入力時のキーボード、縦スクロールと送信ボタンへの到達を記録する。
- [ ] missing_calendar WARNがある時、tenant・法人ID・問い合わせIDで特定し、件名・本文・氏名等のPIIがログに含まれない。カレンダー登録後に再計算して解消する。

## 第二段階（portalは別途承認・子スイッチON後）

- [ ] CSATは解決済み公開対象だけに回答でき、二重回答を拒否する。第一段階の内部desk公開条件には含めない。
- [ ] visible-since直前の内部起票は添付upload/downloadとも404。同時刻・直後およびportal起票は公開添付だけを取得できる。
- [ ] INTERNAL添付は公開しない。他顧客・他tenant・未scan添付は拒否する。
- [ ] portal詳細リンクの既存page route問題は別タスクで解決し、実機から返信とCSATを完了できることを確認する。

結果・未解決事項:

| ケース | 結果 / 証跡 / 残件 |
|---|---|
| 第一段階 | |
| 第二段階 | |

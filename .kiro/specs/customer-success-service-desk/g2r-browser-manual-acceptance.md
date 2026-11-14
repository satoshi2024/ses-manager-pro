# G2r サービスデスクの補完受入チェックリスト

2026-11-14。実装方針の記録であり、CS Ownerの署名や本番公開の承認を代行しない。
自動browserは主控Runnerだけが実行する。`ServiceDeskBrowserMTest`はdesktopと390pxで起票・詳細・公開返信・内部メモを実送信し、console/API失敗0件を検証する。

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

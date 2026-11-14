# NF04 baseline 独立静的Review

- Reviewer: `astra_review_baseline` / gpt-6-astra
- Fixed Head: `8ad0c34eacbb04a031667934f313a2b6965583f1`（main `1443fb68`とtree同一）
- read-only、試験未実施。総括による日本語転記。
- PLAN PASS / IMPLEMENTATION FAIL / PRODUCTION BLOCKED。

## PRD-NF04-001 / P1 / OPEN

`static/js/pwa-queue.js:493,529-536`はchange-request conflict refreshで`/api/my/profile`全体をIndexedDBの
`record.conflict`に保存する。`EngineerChangeRequestService.MyProfileView:94-103`には担当営業氏名/ID、
contracts/careers等が含まれ、最小payload allow-listを迂回する。

修正: requestTypeと実際の対象fieldだけを投影し、全conflict書込を共通sanitizer経由にする。
既存過大conflictをupgradeで除去。profile/skill/careerの実IndexedDB検査で第三者PIIと無関係fieldが0。

## PRD-NF04-002 / P1 / OPEN

`pwa-queue.js:684,689`のflushは一度読んだqueue配列を送信する。
`updateFollowingVersions:416-430`は別readAllとIndexedDBだけ更新するため、同月2件目は旧baseVersionを送る。
正常な連続offline操作が自己生成409になり、更新済みrecordを旧snapshotで上書きする。

修正: 送信直前の最新record再取得またはaggregate単位の直列dispatcher。
同aggregateのrequest直接送信とflushの競合も防ぐ。
回帰: 同月2/3件、一回flush、応答喪失、restart、実際の外部version変更。

## PRD-NF04-003 / P1 / OPEN

`common.js:1047-1064`のlogoutは現tabだけreset/DB清掃する。
`pwa-queue.js:758`のresetはclosureのみ、`:711-713`にtab間撤回listenerがない。
`:633,666-667`は非同期処理後にepoch/scopeを再確認せずputでき、logout後に旧payloadを再作成し得る。
clearUserScopeのblocked/error/timeoutを成功相当として扱う点も境界未完了。

修正: 永続revocation epoch、tab間通知、UI clear、in-flight停止、各await/DB書込/送信/応答時のscope再検証。
清掃失敗は隔離し、旧scopeを復活しない。
回帰: 2 tab logout、遅延hash/通信失敗callback、IndexedDB blocked/error、A→B、清掃後の再書込0。

## PRD-NF04-004 / P2 / OPEN

`service-worker.js:88-91`は`/my/**`のoffline navigationにfallbackを返さず、offline.htmlにもdraft復帰入口がない。
offline browser restartではdraftを復元する画面へ到達できない。

修正: 認証HTMLをcacheしない静的offline shellと、承認されたlocal scope/撤回契約を実装する。
回帰: timesheet/attendance/expenseのdraft→終了→offline restart→安全復帰→一回同期。
CacheStorageに動的HTML/APIが存在しないこと。

## 検証上の境界

SW allow-list、network-only、NoStoreFilter、server TTL、ledger/domain transaction、row lock/CAS、
user/engineer scopeは今回の静的確認だけで新PASSを付与しないが、未実測だけを理由に欠陥ともしない。
固定修正Headのreal SW/IndexedDB/390x844/restart/user switch/session expiry/upgrade/multi-browser証拠が必要。

## 状態更新（2026-11-14 追記。上記の記録自体は変更しない）

W3-R1 最終SHAの静的再レビュー（`1be03bea7d0780481f4b91be432c766c9340fd69` に限定。報告: control repo `reviews/w3-r1-nf02-03-04-1be03bea-review.md` §2）で、PRD-NF04-001〜004 を**CLOSED（静的）**と判定した。いずれも静的判定であり、以下の行番号は 1be03bea 上のものである。上の「検証上の境界」が求める固定修正Headでのruntime証拠（real SW/IndexedDB/restart等）は、W3-09 の最終SHAで browser gate（`-Pbrowser-tests` の `PwaQueueChromeIsolationBrowserTest`・`PwaQueueRestoreAvailabilityBrowserTest`・`PwaRestoreChromeAuthenticationBrowserTest`、0 fail / 0 skip）が通ることを条件とし、それまではruntime CLOSEDと扱わない。W3-09（NF04-1）で残りのテスト欠落（P2-N4-3）を補った。

| ID | 状態 | 1be03bea上の証拠（ファイル:行） | W3-09の追加 |
|---|---|---|---|
| PRD-NF04-001 | **CLOSED（静的）** | `pwa-queue.js:611-675`（`sanitizeConflict`: change-requestは `record.payload.payload` の鍵をallow-listにし、入れ子鍵もその中だけで投影。`FORBIDDEN_KEY` 除外、32KiB上限）。conflict書込は `:925`（409の `markConflict`）と `:1022`（`refreshConflict`）の2か所だけで、どちらもsanitizerを通る。`openDb` の `oldVersion < 2` で過大・conflict付きrecordをERRORにしpayload・hash・conflictを消す（`:240-258`、`DB_VERSION = 2` は `:6`）。回帰: `PwaQueueChromeIsolationBrowserTest.java:221-231` | skill/careerの投影と、v1→v2の実IndexedDB upgradeを実Chromeで確認（`PwaQueueRestoreAvailabilityBrowserTest`）。upgradeで付与したepochを保存し忘れていた点を修正。change-requestの差分のclient値を申請内容と比べるよう修正（R1 P3-N4-5） |
| PRD-NF04-002 | **CLOSED（静的）** | tab内は `resourceKey` ごとのpromise直列化（`pwa-queue.js:1073-1082`）、tab間はIndexedDB `meta` lease と `claimAggregate`（`:881-904`、lease 30秒 `:12`）。lease取得後に `readById` で最新recordを読んで送る（`:1096-1100`）。成功後はlease保持中に `updateFollowingVersions` と `putClaimed` で後続のbaseVersionとhashを進める（`:1161`、`:861-879`、`:305-326`）。`request()` も `flush()` も `sendRecord` を通る（`:1207`、`:1227`）。回帰: `PwaQueueChromeIsolationBrowserTest.java:208-219`（同月3件・baseVersion [0,1,2]）、`:341-343`（lease引継ぎ後の旧ownerの遅延応答をcommitしない） | 応答喪失（server commit後のTypeError）→ 同じaggregateの後続を同じflushで送らない → 再起動・明示復元 → 同一IDの再送がledger replay → 後続のbaseVersionが進む、の組合せを実Chromeで確認。後続を旧baseVersionで送って自己生成の409になる経路を `flush` で塞いだ |
| PRD-NF04-003 | **CLOSED（静的）**（既知の残余リスクあり） | 撤回epochのlocalStorage・cookie二重記録と不一致時の `unverified`（`pwa-queue.js:122-147`）。`revokeScope`（`:199-224`）がgeneration加算・DOM消去・epoch永続化・broadcast・IDB消去を行い、失敗時は `cleared:false`。tab間はBroadcastChannelとstorage event（`:189-197`、`:1290-1304`）から `acceptRevocation`（`:159-173`）。各await・DB書込・送信・応答で `ticketIsCurrent` を再判定（`:153-157`、`:288-348`、`:1106-1166`、`:1201`、bootstrap `:687-744`）。清掃失敗でも旧epochのrecordは `scopeRecords`（`:775-781`）と `confirmRestore`（`:1402`）から外れる。回帰: `PwaQueueChromeIsolationBrowserTest.java:44-126`、`:337-512` | 残余リスク: (1) 全永続化と全tab通知が同時に失敗した場合の即時消去は保証しない（`revocation-recovery-decision.md:79`、B-NF04-4待ち）。(2) R1 P3-N4-1（Service Worker非対応環境でlogoutを結び付けない）は `common.js` で修正。撤回epoch cookieの期限延長（R1 P2-N4-2）を追加し、延長はcookieだけに限ってlocalStorageは書き直さない |
| PRD-NF04-004 | **CLOSED（静的）**（改訂後の契約に基づく） | `service-worker.js:85-91` は全same-origin navigationの失敗で `offline.html` を返し、HTMLはcacheしない。`offline.html:25-38` が復元入口（復元はonline・再認証必須）。回帰: `PwaQueueChromeIsolationBrowserTest.java:243-255`、`:517-520`、`PwaRestoreChromeAuthenticationBrowserTest.java:98`。NF04 OFF（本番既定）ではSWを登録しないため、入口はonline時の同期パネル「下書きを復元」（`base.html:50`） | R1 P1-N4-1（本番はOIDC必須で、パスワード再認証の復元が事実上使えない）は 2026-11-14 の決定（方案A）で、OIDC sessionでは復元不可と明示し入口を隠す扱いにした。復元APIはOIDC sessionをパスワード照合・失敗加算より前に専用code（`PWA_RESTORE_REAUTH_UNSUPPORTED`）で拒否する。ローカルパスワードsessionの復元は従来どおり。OIDC sessionのsession期限切れ（無操作30分）でも下書きは復元できなくなる（W3-09 P1-W309-1。利用者決定 (a) で受け入れ、画面と説明で告知） |

- 本追記は静的再レビューとW3-09の実装・テスト追加の記録であり、W3-09のテスト実行結果、実機ブラウザ受入（`specs/mobile-pwa-self-service/real-device-browser-acceptance-template.md`）、B-NF04-4 の判断、production PASS を意味しない。

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
`pwa-queue.js:758`のresetはclosureのみ、`:711-713`に跨tab撤回listenerがない。
`:633,666-667`は非同期処理後にepoch/scopeを再確認せずputでき、logout後に旧payloadを再作成し得る。
clearUserScopeのblocked/error/timeoutを成功相当として扱う点も境界未完了。

修正: 永続revocation epoch、跨tab通知、UI clear、in-flight停止、各await/DB書込/送信/応答時のscope再検証。
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

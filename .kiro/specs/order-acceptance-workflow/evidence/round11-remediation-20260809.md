# S09 Round 11 FAIL remediation evidence

## 固定点

- Base: `23793ec4f9fdd37305d3ffacda84024c5ab1fe46` (`23793ec`)
- 前回review implementation Head: `e0bd72b1021cd31dee7017b5e9f4dd475731259b` (`e0bd72b`)
- 現行code/evidence Head: `67de0d431bcd74e82ab17b9d1d7d67c6bd1a1287` (`67de0d4`)
- fix commit: `fix(S09): Round 11 review blockersを修正`
- Packet文書の同期commitは本流履歴で `git log -1 -- <path>` により解決する。

## 指摘対応

| 指摘 | 対応 | 主な変更file / test |
|---|---|---|
| R9-P1-09 storage orphan | `DocumentServiceImpl`でstorage put前にrollback compensationを登録し、transaction中のput失敗でもcatch cleanupを実行。`LocalDocumentStorage`はpartial fileを失敗時に削除 | `DocumentServiceImpl.java`, `LocalDocumentStorage.java`, `DocumentServiceImplTest`, `DocumentStorageTest` |
| R9-P1-10 invalid 390px PNG | 注文画面のfilter cardとtable cardを分離し、共通mobile collapseがtable全体を隠さないDOMへ変更。390x844の安定待機後PNGを再取得 | `templates/sales-order/list.html`, `round11-20260809-sales-order-mobile-390.png`, `...-filter-390.png`, `...-sidebar-390.png` |
| R11-P1-01 dirty gitlink | root indexから`.tmp-ui-scale-r3`を除去し、`.gitignore`へ追加。nested repositoryは削除せず保持 | `.gitignore`, `git ls-tree HEAD -- .tmp-ui-scale-r3` empty |
| R11-P1-02 archive all-load | `AcceptanceMapper`にworkMonthと許可contract IDでのSQL母集団queryを追加し、`DocumentServiceImpl`の全検収行`selectList()`を廃止 | `AcceptanceMapper.java`, `DocumentServiceImpl.java`, `AcceptanceAsOfScopeTest` |
| R11-P2-01 loose concurrency assertion | concurrent testは許容する409 `BusinessException`のcodeとmessage keyを限定し、その他の`Throwable`を`unexpected`として失敗扱い | `ConcurrentSubmitReopenTest.java` |

## 自動検証

| 範囲 | command / 結果 |
|---|---|
| 定向 storage/order回帰 | `mvn -B -Dtest=DocumentStorageTest,DocumentServiceImplTest,SalesOrderServiceImplTest -DfailIfNoTests=false test` — 30 tests / 0 failures / 0 errors / 0 skipped |
| 定向 scope回帰 | `mvn -B -Dtest=SalesOrderDocumentScopeTest,AcceptanceAsOfScopeTest -DfailIfNoTests=false test` — 9 / 0 / 0 / 0 |
| 実MySQL concurrency | `mvn -B -Dtest=ConcurrentSubmitReopenTest -DfailIfNoTests=false test` — 3 / 0 / 0 / 0 |
| L4 | `scripts/verify-like-ci.ps1`のMaven/Surefire child完了後に全reportを集計 — 282 classes / 1582 tests / 0 failures / 0 errors / 0 skipped。Docker MySQL smokeを含む |
| diff hygiene | `git diff --check` — exit 0 |

L4は外側監視の120秒timeout後もMaven/Surefire childが継続し、最終report群は完了している。wrapperの最終trailer/exit行そのものは外側tool timeoutのため取得していない。これはテスト失敗ではなく、report群の完了・全282 XML・zero-skippedを別途確認した運用上の注記である。

## Browser Demo（実アプリ / 390px）

実MySQL `ses-order-r10-demo`（localhost:33306）へ接続したSpring Bootアプリを実Chromeで操作した。viewportは390x844へ固定し、各状態で700ms安定待機後に取得した。

- filter closed: filter cardのみcollapse、tableは可視。`bodyWidth=390`, `bodyScrollWidth=390`。
- filter open: form rect `left=17,right=373`、tableはresponsive領域内。body overflowなし。
- sidebar open: sidebar `width=260`、backdrop表示、mainは390pxのまま。文字が一文字幅へ潰れない。
- detail modal: `O-202608-0001`, `PO-R10-0001`, `C-202609-0001`を表示。dialog widthは390px内。
- Enter検索、reload、back、double-clickを実施。console logは空、拒否/rollback経路は未実施。

PNG SHA-256:

- `round11-20260809-sales-order-mobile-390.png`: `9116E2C934B28705557095C0D5C38C462944C30FC26E3BED6A8A46D625FABA8D`
- `round11-20260809-sales-order-mobile-filter-390.png`: `13A7741E65EBA389E2A3F02F05752D0341AFDAC4A62D3646CC94C801AC60CEA8`
- `round11-20260809-sales-order-mobile-sidebar-390.png`: `13A86C1D389B2AB9DA48E33E09ADE4C9629CA81B9F01298E5DD9A1DE8E328A94`

L4の`RealBrowserScreenshotTest`は既存`evidence/browser-r8/`もrunId `browser-r8-20260809145958`で再生成した。そこには既知のfavicon等404が1件記録されるため、Round 11の注文画面証跡は上記の直接CDP操作結果を正本とする。

## Scope / rollback / residual

- `.tmp-ui-scale-r3` nested repo内部はS09のmain-tree変更対象外。root trackingだけを除去した。
- V80は変更せず、V81 forward repair/runbookをrollback境界として維持する。down migrationは主張しない。
- 実S3等の外部storage、production backup/restore、全roleの403 UI、rejection→resubmitおよびrollbackの全Browser経路、独立reviewerのchanged-files inventoryは未検証である。
- S10/S11/Wave 2はS09独立再Review PASSまで停止する。

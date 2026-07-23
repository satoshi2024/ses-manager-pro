# 第7回対応 再レビュー（第2回）— R7-01〜R7-11 の対応結果

- 再レビュー日: 2026-07-23
- 基準: `HEAD=41924ea` + 未コミットworktree変更（R7対応の全量。テスト追随・application-test.yml 含む）
- 比較元: `2026-07-23-round7-fix-recheck.md`
- 方針: 読み取り専用で確認し、全量テストを実行した。本書以外の業務ファイルは変更していない。

## 1. 結論

**主要な指摘（P1/P2）はすべて解消し、全量テストは緑になった。**

- `mvn test`: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**（仮修正なし・納品状態のまま）。本改修サイクルで初めて「テスト緑の状態で納品」が達成された。
- R7-01〜R7-08 は全件解消。実装品質も良い（特に R7-08 の期間フィルタは mapper の重なり判定・動的年度・フィルタUIまで正しく実装）。
- 残るのは P3 の未対応3件（メッセージキー・文書・締め保護の残り）と軽微な仕上げのみ。**コミットはまだ一切行われていない**（45ファイルがworktreeに滞留）ため、下記残件を片付けた上で論理単位に分けてコミットすることを次の完了条件とする。

### 判定サマリ

| 判定 | ID |
|---|---|
| 解消 | R7-01, R7-02（テスト緑化・MonthlyClosingは根因特定の上で正しく修正）, R7-03, R7-04, R7-05, R7-06, R7-07, R7-08 |
| 部分解消 | R7-11（CAS追加・旧メソッド削除・根因修正は✔ / @Transactional・層CRUD締め・フォントキャッシュ・整形が残） |
| 未対応 | R7-09（メッセージキー）, R7-10（CLAUDE.md/AGENTS.md）, A7-07残（agingDetail） |

## 2. 個別確認結果

### 解消を確認したもの

1. **R7-01**: `EngineerApiController:38` は `Page<Engineer>` に修正され、コンパイル成功。
2. **R7-02**: 前回赤だった44件はすべて緑。特筆すべき点として、`MonthlyClosingServiceImplTest` の2件は「stubで誤魔化す」のではなく根因（`summary` の期限超過判定が status を見ていない）を特定し、**本体を `InvoiceService.isOverdue` へ統一（A7-12の完成形）した上でテストに status を追加**しており、正しい直し方だった。
3. **R7-03**: approve/reject とも「普通読み→締めロック→Contractロック→`selectByIdForUpdate` 再取得」の順に修正。Contract→WorkRecord のロック順が全経路で統一され、鮮度も current read で確保。
4. **R7-04**: `SES.pagination.render` を common.js に実装（前後移動+近傍5ページ、disabled制御、コールバック）。請求一覧のページャが機能する。
5. **R7-05**: dashboard最優先の明示分岐+「bare prefix→実在ルート」の変換表を追加。変換先の実在をページコントローラー全数で確認した（`/ai/matching`・`/candidate/list`・`/email/template/list` 等すべて実在）。
6. **R7-06**: `menu.cache.ttl-ms` プロパティ化（既定60000）+ `application-test.yml` で 0。権限系テスト8件の反転が解消。
7. **R7-07**: freee `refresh()` は `applicationContext.getBean(FreeeIntegrationService.class).refresh()` でプロキシ経由になり、`REQUIRES_NEW` + `selectLatestForUpdate` が実際に効く。
8. **R7-08**: `/api/contracts` に `periodFrom`/`periodTo` を追加（既存の `endDateFrom/To` と別名で混同なし）。mapper は「`end_date IS NULL OR end_date >= periodFrom` AND `start_date <= periodTo`」の正しい重なり判定。ガントJSは年度を動的算出（4月起点）し、フィルタ入力(`#filter-period-from/to`)も追加。ハードコード日付は排除された。
9. **R7-11の一部**: `syncRootBpAmount`・`updateLayer` に status CAS 追加、`QuotationPdfServiceImpl` の旧 `resolveCjkFont` 削除。

### 残存（次回対応）

#### N2-1 【P3】新設メッセージキーが依然として未定義（R7-09 未対応）

- `error.common.optimisticLock` / `error.payroll.invalidType` / `js.gantt.maxLimitReached` は全ロケール（ja/en/zh_CN/ko/デフォルト）の `messages*.properties` に存在しない。競合時・type不正時・ガント上限時に**生のキー文字列が画面に表示される**。JS側は `t()` の第2引数がフォールバックではなく引数扱いのため同様。
- 改修方法: 3キー×全ロケールを追加する。キー欠落を検出する既存のI18nテストがあれば対象へ含める。

#### N2-2 【P3】文書更新が依然として未対応（R7-10 未対応）

- `CLAUDE.md`: 「CSRFは/api/**で無効」「ロールは4種固定ENUM」「V1〜V14」が3ラウンド連続で残存。
- `AGENTS.md`: `SES.csrf.setup()`（実在は `SES.csrf.token()/header()`）、`config/DataScopeConfig`（実在しない）の誤記が残存。さらに表内の「請求書API（A7-07）は未適用」「BP支払（A7-05）は未対応」「フォント未埋め込み（A7-03）」「署名済みPDF保存は未実装（A7-04）」「refresh並行ガード未実装（A7-18）」は**今回の実装で解消済みのため、現状と矛盾する記述になっている**。コミット前に必ず現状へ書き換えること。

#### N2-3 【P3】BP支払の締め保護が半分のまま（R7-11-4 残）

- `changeBpPaymentStatus` に `@Transactional` が無く、`assertOpenForUpdate` の締めロックがメソッド内で完結・解放される（チェックは瞬間判定に留まる）。
- `BpPaymentServiceImpl.addLayer/updateLayer/deleteLayer` は締めチェック自体が未追加（CASのみ追加された）。締め済み月のBP階層追加・金額変更・削除は依然として可能。
- 改修方法: `changeBpPaymentStatus` に `@Transactional` を付与。層CRUD 3メソッドに「workRecordId→`t_work_record.work_month`→`assertOpenForUpdate`」を追加。

#### N2-4 【P3】agingDetail のコメントアウト行（A7-07残・3ラウンド連続で放置）

- `InvoiceApiController:160` の `// dataScopeService.assertAllowedCustomer(customerId);` が原様。`listPayments`/`listReminders`/`aging`/`aging-export` もスコープ外のまま。有効化するか、スコープ外とする設計判断をコメントで明記して行を削除するか、どちらかに決着させること。

#### N2-5 【P4】軽微な仕上げ

1. 請求一覧のページャ: `list.html` の容器が `<ul id="pagination">` のまま、`render` がその中へさらに `<ul>` を挿入するため **ul の入れ子**（不正なHTML。表示はされる）。容器を `<div>` に変える。
2. R7-05 変換表の `/skill-tag`: `SkillTagPageController` が存在せず着地不能（skill-tagのみ許可のロールという極端な構成でのみ404）。変換表から除外するか一覧ページを用意する。
3. `PdfFontUtils` の BaseFont キャッシュ未実装（毎PDF生成でTTF再読込）。
4. `sendReminders` の「入金済のため対象外」行のインデント崩れが残存。
5. LF/CRLF 警告が多数のファイルで残存。コミット時に正規化されるが、`.gitattributes` の明示を検討。
6. 回帰固定テスト（要員ログイン着地・dashboard無しロール着地・approveロック順検証）は未追加。R7-05/R7-03/A7-01 は現状コードで正しいが、テストによる固定が無いため次の改修で再退行しうる。

## 3. 次のアクション（推奨順）

1. **コミット整理（最優先）**: 現在45ファイルが未コミット。N2-1〜N2-4 を片付けた上で、(a) R7退行修正+テスト追随、(b) BP締め保護、(c) 文書更新、程度の論理単位でコミットする。テスト緑の今の状態を早く確定させること。
2. N2-1（メッセージキー）と N2-2（文書）は機械的な作業。特に AGENTS.md の「未対応」注記は実装と矛盾しているため、コミットに文書更新を同梱しないと次のAI改修が誤誘導される。
3. N2-3（BP締め保護の完成）。
4. N2-4（agingDetail の決着）。
5. N2-5 と回帰固定テストは次ラウンドで可。

## 4. 完了条件（次回）

1. worktree がクリーン（全変更が論理単位でコミット済み）で、`mvn test` が緑。
2. 3メッセージキーが全ロケールに存在し、画面に生キーが出ない。
3. CLAUDE.md / AGENTS.md が実装と一致（「未対応」注記の残骸なし）。
4. BP支払の全更新経路（status変更+層CRUD）が締め済み月で4xx。
5. `agingDetail` のコメントアウト行が決着（有効化 or 設計判断明記の上で削除）。

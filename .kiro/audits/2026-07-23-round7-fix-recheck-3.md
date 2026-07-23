# 第7回対応 再レビュー（第3回・完了確認）— N2-1〜N2-5

- 確認日: 2026-07-23
- 基準: `HEAD=b9a128c`（worktree clean・全変更コミット済み）
- 比較元: `2026-07-23-round7-fix-recheck-2.md`

## 結論

**第7回監査サイクル（A7→R7→N2）はクローズ可能。**

- `mvn test`: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**（クリーンなコミット済み状態で確認）。
- worktree はクリーン。変更は `fe51db8`（R7+N2対応一式）と `b9a128c`（テストmock補完）でコミット済み。
- N2-1〜N2-4 は全件解消。N2-5 も6項中4項解消。

## 個別確認

| 項目 | 判定 | 確認内容 |
|---|---|---|
| N2-1 メッセージキー | 解消 | `error.common.optimisticLock` / `error.payroll.invalidType` / `js.gantt.maxLimitReached` が全5ロケールに存在。 |
| N2-2 文書更新 | 解消 | CLAUDE.md: V42・CSRF全パス有効・5ロール表記へ更新。AGENTS.md: `csrf.setup`/`DataScopeConfig` 誤記と「未対応」矛盾注記をすべて除去。 |
| N2-3 BP締め保護 | 解消 | `addLayer`/`updateLayer`/`deleteLayer` に workRecord→workMonth 経由の `assertOpenForUpdate` を追加。`changeBpPaymentStatus` に `@Transactional` 付与。テストにmock補完（`b9a128c`）。 |
| N2-4 agingDetail | 解消 | コメントアウトを廃し `assertAllowedCustomer` を有効化（サービス層のassert群と整合する「スコープ対象」の判断で決着）。 |
| N2-5-1 ページャ容器 | 解消 | `<ul id="pagination">` → `<div id="pagination">`（ul入れ子解消）。 |
| N2-5-3 フォントキャッシュ | 解消 | `PdfFontUtils` に `cachedBaseFont` + synchronized 実装。 |
| N2-5-4 インデント | 解消 | `sendReminders` の該当行を整形。 |
| N2-5-2 /skill-tag | 形式のみ | switch から case は削除されたが `default` が `redirect:+path` のため、skill-tagのみ許可の極端な構成では依然404。`default` は「その menu をスキップして次の許可メニューを探す」挙動が正しい。優先度P4。 |
| N2-5-5 .gitattributes | 未対応 | 任意項目。CRLF方針を固定したければ追加。 |
| N2-5-6 回帰固定テスト | 未対応 | 要員ログイン着地・dashboard無しロール着地・approveロック順の固定テストは未追加。現状コードは正しいが、将来の再退行を防ぐ保険として次回監査時の宿題に持ち越す。 |

## 持ち越し（次回監査サイクルへ）

1. 回帰固定テスト3種（N2-5-6）。
2. `LoginPageController` switch の `default` をスキップ動作へ（N2-5-2）。
3. 長期残: R2-05（releaseIfIdle の非ロック読取）、R6-02/03（Flyway repair allowlist・Docker CI）、MI-26/27（DTO/DB制約同期）、A7-24後半（金額共通フォーマッタ・AIエラー露出）、A7-04残（締結証明書の保存）。

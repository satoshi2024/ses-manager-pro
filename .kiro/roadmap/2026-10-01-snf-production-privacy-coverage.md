# NF07 現行source coverage 再確認

## 最新の統合source scan（2026-10-04）

- 固定Head: `ad9bb1c6a58c37643e8753a2fd42c87b180f5ce5`（NF04 G1限定統合後）。
- 下記と同じread-only scannerを実行し、起止Head一致・cleanを確認した。DB/provider接続なし。
- statusは **BLOCKED_COVERAGE_INCOMPLETE / scanner exit 2**。providerCallCount/writeCountとも0。
- migration 183 scripts、254 table、244 entity table、724 provider候補。
- privacy catalog未分類74 table、既存catalog policy unknown78、構造欠落column1082、
  provider301、entity68。provider候補は機械抽出であり、全件が実外部送信経路とは限らない。
- source manifest SHA-256: `d3bb9b8d1ea1a610f0854c835e697cb4f5a213a0a2230c4a46adf943e725380e`。
- raw JSON、起止metadata、hashは `verification-evidence/20261004-privacy-coverage-03/`。
  scanner wrapperのexit 0は収集処理終了であり、内側scanner exit 2をPASSへ変更しない。
- 未統合NF06 V184、NF08後続sliceはこのscanに含まれない。最終RCで再実行する。

## 2026-10-01の統合source scan履歴

- 固定Head: `cde61306`（V183、NF07 SF-W1統合後）。
- commandは下記と同じread-only scanner。DB/provider接続なし、providerCallCount/writeCountとも0。
- statusは **BLOCKED_COVERAGE_INCOMPLETE / exit 2**。
- migration 183 scripts、254 table、244 entity table、708 provider候補。
- privacy catalog未分類74 table、既存catalog policy unknown78、構造欠落column1082、
  provider285、entity68。policy unknown78は全sourceの未承認件数ではない。
- source manifest SHA-256: `590890d027164e388c84968b77697d0480aa5b5373e72b57940bc7e5ceda03ff`。
- raw JSON、固定Head/終了metadata、hash manifestは
  `verification-evidence/20261001-privacy-coverage-02/`へ保存した。

旧catalogを現行sourceへ同期する作業は未完了。追加table/provider候補を機械的に列挙しても、
保持期間・処分権限・Privacy Owner・Legal承認を自動付与しない。

## B0時点の履歴

- 実行日: 2026-10-01、Asia/Tokyo
- code/schema base: `1443fb68943849a3260d7ebe005259f3eb609d60`
- worktree: `C:\work\ses-snf-status-audit`
- command: `pwsh -NoProfile -File tools/privacy-retention-dsar/inventory-coverage.ps1`
- sourceのread-only scan。DB/外部provider接続・業務writeなし。

| 項目 | 結果 |
|---|---:|
| status / exit | BLOCKED_COVERAGE_INCOMPLETE / 2 |
| migration SQL files | 182（181 versioned + 1 repeatable） |
| migration table / entity table | 253 / 244 |
| provider candidate file | 699 |
| privacy catalog unclassified table | 73 |
| 既存catalog内policy unknown table | 78（現行全対象のunknown総数ではない） |
| source coverage missing column | 1077 |
| provider coverage missing | 276 |
| entity coverage missing | 68 |
| providerCallCount / writeCount | 0 / 0 |
| source manifest SHA-256 | `185c158ac1cb490f2fac6dfeded6f9fa5fa24fcda87d27e6af75bfe1a57002f8` |

既存2026-08-28のcoverage evidenceは履歴として残すが、現行headのcoverage PASS証拠ではない。
W0で全差分を再分類・同期する。機械的source一致が達成されてもpolicy/Owner/Legalの承認を意味しない。
scannerが扱うtableはmigration中の宣言を基にするため、実production live schema inventoryとは別の証拠である。

# NF07 現行source coverage 再確認

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

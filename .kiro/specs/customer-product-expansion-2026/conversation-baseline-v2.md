# 既存対話へのv2.0基線切替メッセージ

## 実装・修正対話へ送る文面

```text
実行基線を次の文書へ更新してください。
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md v2.0
- .kiro/specs/customer-product-expansion-2026/shared-standards.md v2.0
- .kiro/specs/customer-product-expansion-2026/s02-review-retrospective.md

既に独立Reviewで受入済みの成果を再実装せず、中央ledgerのOPEN issueとそのdirect regressionだけを扱ってください。
作業前にREADINESS、対象taskのTASK CONTRACT、現行Issue Register、Base/Head、dirty fileの所有者を提示してください。
旧基線と新版に矛盾がある場合は差分表を提示して停止し、推測で大規模改修しないでください。
修正完了時はcommit固定済みREVIEW PACKETを提出し、自己PASSは宣言しないでください。
```

## Review対話へ送る文面

```text
Review基線を次の文書へ更新してください。
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md v2.0
- .kiro/specs/customer-product-expansion-2026/shared-standards.md v2.0
- .kiro/specs/customer-product-expansion-2026/s02-review-retrospective.md

REVIEW PACKETのBase/Head、対象task、test/Demo証拠が固定されていなければNOT REVIEWABLEとしてください。
再ReviewはOPEN issue、fix diff、direct regression、変更public contractのconsumerだけを対象にしてください。
VERIFIED_CLOSED issueは新しい再現証拠なしに再開しないでください。新規P0/P1はoriginal headの見落としかfix delta導入かを明記してください。
全指摘へissue ID、AC、file:line、再現、影響、最小fix、regression scopeを付けてください。
最後はPASS / CONDITIONAL PASS / FAIL / NOT REVIEWABLEのいずれかで終了してください。
```

## 未開始のS03〜S17について

既存のcopyable conversationをそのまま送信できる。全34promptが`shared-standards.md`を読むため、v2.0を自動継承する。
送信時に本ファイルの該当文面を先頭へ追加すれば、基線versionが対話上でも明示される。

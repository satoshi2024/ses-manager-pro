# 既存対話へのv2.0基線切替メッセージ

## 実装・修正対話へ送る文面

```text
実行基線を次の文書へ更新してください。
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md v2.0
- .kiro/specs/customer-product-expansion-2026/shared-standards.md v2.0
- .kiro/specs/customer-product-expansion-2026/s02-review-retrospective.md
- .kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md（S03〜S17のみ）

既に独立Reviewで受入済みの成果を再実装せず、中央ledgerのOPEN issueとそのdirect regressionだけを扱ってください。
作業前にREADINESS、対象taskのTASK CONTRACT、現行Issue Register、Base/Head、dirty fileの所有者を提示してください。
旧基線と新版に矛盾がある場合は差分表を提示して停止し、推測で大規模改修しないでください。
修正完了時はcommit固定済みREVIEW PACKETを提出し、自己PASSは宣言しないでください。
S03〜S17の通常TaskはL0〜L3の定向test・直接回帰で完了し、無条件の全量testを実行しないでください。
各specのM taskでL4全量を1回実行し、それ以前は昇格条件に該当する安定checkpointだけ中間L4を実行してください。
```

## Review対話へ送る文面

```text
Review基線を次の文書へ更新してください。
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md v2.0
- .kiro/specs/customer-product-expansion-2026/shared-standards.md v2.0
- .kiro/specs/customer-product-expansion-2026/s02-review-retrospective.md
- .kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md（R03〜R17のみ）

REVIEW PACKETのBase/Head、対象task、test/Demo証拠が固定されていなければNOT REVIEWABLEとしてください。
再ReviewはOPEN issue、fix diff、direct regression、変更public contractのconsumerだけを対象にしてください。
VERIFIED_CLOSED issueは新しい再現証拠なしに再開しないでください。新規P0/P1はoriginal headの見落としかfix delta導入かを明記してください。
全指摘へissue ID、AC、file:line、再現、影響、最小fix、regression scopeを付けてください。
最後はPASS / CONDITIONAL PASS / FAIL / NOT REVIEWABLEのいずれかで終了してください。
同一Headに有効なL4証拠がある場合は理由なく全量testを再実行せず、重要なL1/L2再現testと証拠照合で独立性を確保してください。
L4を再実行する場合は、merge差分、共有境界変更、skip、未知回帰のどの昇格条件に該当したか記録してください。
```

## 未開始のS03〜S17について

既存のcopyable conversationをそのまま送信できる。全34promptが`shared-standards.md`を読むため、v2.0を自動継承する。
送信時に本ファイルの該当文面を先頭へ追加すれば、基線versionが対話上でも明示される。

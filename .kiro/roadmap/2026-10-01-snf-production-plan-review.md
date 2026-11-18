# NF06 / NF07 技術increment 独立Plan Review

- Reviewer: `astra_review_baseline` / gpt-6-astra
- Fixed Head: `09ab55a876e381de7e82c4f5af0c7fef5c484275`
- 対象: production-technical-decisions TD-02/TD-03、既存NF06/NF07 specs
- 方式: read-only。試験実行・production approvalなし。総括による日本語転記。

| Scope | Verdict | 開始可能な範囲 |
|---|---|---|
| NF06 TD-02 | CONDITIONAL | W0: 5 adapter契約、schema/golden fixture、純parser/read-only validator |
| NF07 TD-03 | CONDITIONAL | W0: inventory/no-write classifier、synthetic incrementの設計 |
| Full production | BLOCKED | 実source移行、DSAR交付、実処分を許可しない |

ユーザーによる技術決定・開発の委任は有効。次の技術不足は総括が補正でき、同じ技術選択の再承認を求めない。
実在のdata owner、Privacy/Legal、production承認を代行する権限ではない。

## NF06 OPEN P1

1. **PLAN-NF06-001 adapter完全契約**: 各5 adapterの必須field、初期/更新state、domain facade、
   master dependency、子collection、副作用、補償を固定する。順序はCUSTOMER/ENGINEER→PROJECT→
   PROPOSAL/CONTRACT。skills/user/org/position/history/assignmentはscopeを明示し、未定義を自動作成しない。
   Proposal成約のContract派生を許可するか決め、許可時はrow/id-map/補償に含める。
2. **PLAN-NF06-002 identity/transaction**: 旧job単位id-mapと新cross-job external keyを同期する。
   source-system登録、key normalization、binding revision、target tenant/version、DB uniqueを定義。
   target/binding/row result/auditはrow transaction、checkpointは再構成可能なprojection。
   stale workerのcommitを止めるlease fencingを要求する。
3. **PLAN-NF06-003 counter/hash**: restart時に既存INSERT/UPDATEのrow結果をSKIPPEDへ変更しない。
   attemptのDEDUPED/REUSEDを別記録し、business counter/amount/hashを不変にする。
   validateのacceptedはpendingでありapply終端式を先取りしない。metadata no-write例外をtable単位で列挙する。

W1開始前に旧requirements/design/tasksを実行契約へ同期し、上記をAstraで再Reviewする。
回帰はcross-job同key、別mapping、row commit前後crash、checkpoint失敗、lease期限切れ旧worker、
無中断と再開後の同一counter/amount/hash、全5 adapterの正常/拒否/tenant/補償を必須とする。

## NF07 OPEN P1

1. **PLAN-NF07-001 isolated trust domain**: 新`SYNTHETIC_FOUNDATION`を旧DEV-0/D0と区別する。
   generated fixtureのみ、独立temporary DB/object store、実account/credential/providerなし。
   prodおよびprod,testでsynthetic verifier/policy/providerを拒否し、環境変数やdatasource注入でも迂回できない。
2. **PLAN-NF07-002 approval/hold/fencing**: policy approvalとcase approvalを分離する。
   tenant/subject/provider/action/version/hash、requester/approver分離、署名/authority/revocationを検証する。
   hold追加と実行を原子的に直列化し、claim/CAS/lease fence、対象変更の再承認、未知外部結果のUNKNOWNを定義する。
3. **PLAN-NF07-003 independent journal**: disposition/hold journalをrestoreする旧snapshotと独立して保護する。
   信頼可能なwatermark、整合性検証、冪等再適用、再公開gateを定義し、欠落/破損/遅延時は公開しない。
   journalに削除PIIを再保存しない。

W1は隔離/信頼/競合/復旧契約の再Review PASS後のみ。synthetic基盤の実装完了はDG07・旧F1-M・
Full Feature・法的承認の完了ではない。実PII/provider/交付/処分は必要な人工gate完了まで禁止する。

## HUMAN_REQUIRED

正式Privacy Owner、適用法/HR/税務policyと期限、hold権限、実本人確認/交付、実provider契約、
正式source/mapping/binding/data owner、production topology/enablement署名。

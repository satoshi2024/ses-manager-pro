# S03〜S17 分層テスト実行方針 v1.0

## 1. 目的と適用範囲

本方針はS03 `enterprise-identity-security` からS17 `ai-feedback-learning` まで、T014〜T115へ適用する。
S01/S02の完了履歴と過去証拠は変更しない。

目的は、各Taskで必要な品質証拠を維持しながら、変更と無関係な全量testの反復実行を止めることである。
「各Taskでtestする」と「各Taskで全量testする」を明確に分離する。

## 2. 基本原則

1. 各Taskは必ずtestするが、通常Taskでは定向testと直接回帰だけを実行する。
2. 全量testはM task、共有境界変更の安定checkpoint、merge競合解消後、CI/release候補に集約する。
3. Review AIは同一commitの成功済み全量testを理由なく再実行しない。証拠のcommit一致と信頼性を確認する。
4. test範囲は変更file数ではなく、変更したcontractとconsumerで決める。
5. testを省略する場合は「不要な理由」を記録し、未実行を実行済みと扱わない。
6. 失敗・未知影響・共有境界変更があれば上位levelへ段階的に拡張する。

## 3. Test Level

| Level | 名称 | 実行時点 | 必須範囲 | 全量 |
|---|---|---|---|---|
| L0 | 文書/静的 | inventory、decision、文書だけ | link、ID、番号、`git diff --check` | 不要 |
| L1 | 定向 | 実装中、1つの修正 | 変更class/test、再現test、syntax/compile | 不要 |
| L2 | 直接回帰 | 通常Task完了、再Review | public contractの直接consumer、権限/境界/失敗 | 不要 |
| L3 | subsystem | 共有service、schema、security、合流 | 影響module、関連MVC/Mapper/DB、必要smoke | 条件付き |
| L4 | spec全量 | M task | `mvn test`全量、Node、必要Docker/browser/provider | 必須 |
| L5 | release/CI | merge後release候補 | 全repo、実MySQL、browser、sandbox、security scan | 必須 |

下位level成功後に上位levelを実行する。L1失敗中にL4を反復してはならない。

## 4. Task種別ごとの既定level

| Task/変更 | 開発中 | Task完了 | M/Release |
|---|---|---|---|
| 0/inventory/decision文書のみ | L0 | L0 | Mで統合確認 |
| 単一service/DTO/controller | L1 | L2 | MでL4 |
| UI/JS/template/i18n | syntax+対象layout | 対象browser/MVC+bundle | Mで全UI回帰 |
| Migration/entity/H2 | integrity+対象Mapper | fresh/legacy対象smoke(L3) | Mで全migration回帰 |
| SecurityConfig/auth/scope | 対象security test | role/IDOR/consumer回帰(L3) | Mで全security回帰 |
| 共通cache/transaction | 再現+ordering | 全直接consumer(L3) | MでL4 |
| 外部adapter | fixture/WireMock | error matrix(L2/L3) | M/releaseでsandbox |
| 子Agent lane | lane定向 | lane L2 | merge後主担当L3、MでL4 |

## 5. 全量testへの昇格条件

通常Taskでも次のいずれかに該当すれば、安定checkpointでL4へ昇格する。ただし修正のたびではなく、関連修正をまとめて
定向testがgreenになった後に1回実行する。

- `pom.xml`、Spring context構成、profile、dependency/BOM変更。
- `SecurityConfig`、全局filter/advice、認証principal、CSRF、session。
- `DataScopeService`、`OrganizationScopeService`、tenant/file scope等の共通認可母集団。
- 共通cache key、transaction callback、scheduler/async context。
- V1、増分Flyway、共通H2 schema、entityの横断変更。
- 共通金額計算、状態機械、FileStorage、Notification等、多数specのconsumerを持つ契約。
- 複数lane merge、競合解消、rebaseで実装行を変更。
- 定向testから説明できない失敗、Spring context failure、test順依存が発生。

文書、comment、message文言だけの変更は、それ自体を理由にL4へ昇格しない。

## 6. 通常Taskの完了証拠

通常Taskは次で完了できる。全量test未実行だけを理由に未完了としない。

```text
TEST SCOPE DECISION
- task / commit:
- changed contracts:
- selected level: L0 / L1 / L2 / L3
- selected tests and consumers:
- excluded suites and reason:
- escalation trigger present: yes/no
- exact result: tests/failures/errors/skipped/exit
- next L4 checkpoint: <M task or named merge checkpoint>
```

必須条件:

- requirements/ACの正常、拒否、境界、直接回帰を満たす。
- 変更contractのconsumer inventoryと選択testが対応する。
- skipを列挙する。
- 次にL4を行うTask/commitが明記される。

## 7. M taskのL4

各specのM taskで初めて、そのspecの全成果を固定したcommitに対してL4を行う。

- Maven全量test。
- Node/JS syntax（JS変更がある場合）。
- fresh/legacy MySQL smoke（DDLがある場合）。
- security/scope matrix（該当する場合）。
- desktop/390px browser Demo（UIがある場合）。
- provider sandbox/official fixture（外部連携がある場合）。
- `git diff --check`。

M task後にproduction codeを変更した場合、変更に応じてL1/L2/L3を実行する。L4再実行は§5の昇格条件または
M証拠のcommitと最終Headが一致しなくなり、差分が単純文書変更でない場合に限る。

## 8. merge後Reviewの再実行判断

Review AIは次の順で判断する。

### 全量再実行不要

次を全て満たす場合、L4を再実行せず証拠確認+独立L1/L2でよい。

- MのL4証拠commitとmerge後Headのtreeが同一、または差分が文書だけ。
- CI結果が同一Headに紐付く。
- merge conflict解消でproduction/test/schemaを変更していない。
- OPEN issue修正が共有境界を変更していない。

### 全量再実行必要

- L4後にproduction code/schema/dependency/security/shared contractが変わった。
- merge conflict解消で実装を手編集した。
- L4結果が別commit、別branch、別profileで対応不能。
- skipが必須suiteを隠していた。
- 定向Reviewで未知回帰を検出した。

判断と根拠をReview Ledgerに残す。単に「念のため」でL4を反復しない。

## 9. 再Review

再ReviewはOPEN issueの再現test、修正test、direct regressionをL1/L2で行う。共有境界ならL3へ上げる。
全量は§5/§8に該当する場合だけ行う。同じHeadの同じL4を別AIが再実行することを独立性の必須条件にしない。

独立性は次で確保する。

- test command/result/commitの照合。
- test本文とassertの読解。
- reviewerが重要な再現testを選択実行。
- CIまたはM証拠の改ざん・取り違えがないことの確認。

## 10. S03〜S17の固定checkpoint

| Spec | 通常Task | L4 checkpoint |
|---|---|---|
| S03 identity | T014〜T019はL0〜L3 | T020 |
| S04 archive | T021〜T026はL0〜L3 | T027 |
| S05 productivity | T028〜T032はL1〜L3 | T033 |
| S06 BP | T034〜T039はL0〜L3 | T040 |
| S07 approval | T041〜T046はL0〜L3 | T047 |
| S08 CRM | T048〜T052はL1〜L3 | T053 |
| S09 order | T054〜T058はL1〜L3 | T059 |
| S10 dispatch | T060〜T065はL0〜L3 | T066 |
| S11 attendance | T067〜T073はL0〜L3 | T074 |
| S12 staffing | T075〜T079はL1〜L3 | T080 |
| S13 external portal | T081〜T086はL0〜L3 | T087 |
| S14 engineer portal | T088〜T092はL1〜L3 | T093 |
| S15 accounting | T094〜T100はL0〜L3 | T101 |
| S16 JP PINT | T102〜T107はL0〜L3 | T108 |
| S17 AI feedback | T109〜T114はL0〜L3 | T115 |

## 11. 禁止事項

- 通常Taskのcheckbox条件に無条件の`mvn test`全量を追加する。
- 1行修正ごとにL4を走らせる。
- L1/L2がredのままL4で偶然greenになることを期待する。
- 全量未実行を隠す、または定向testを全量と表現する。
- 同一commitの有効なL4証拠を、理由なく実装AIとReview AIの双方で重複実行する。
- 時間短縮を理由にscope、migration、securityの必須L3を省略する。

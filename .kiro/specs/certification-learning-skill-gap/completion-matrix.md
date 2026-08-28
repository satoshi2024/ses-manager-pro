# 完了対応表（NF-03 approved）

## Status

| 項目 | 値 |
|---|---|
| traceability | `APPROVED` |
| Start Head | `c29001e3` |
| F1 Head | `3784a6a0`（P1-F1-02 inclusive interval 修正後） |
| 承認 Base | `76e45340` |
| migration | V115〜V119（latest+1 から採番済み） |
| F2 | 未着手（F1 完了報告後） |

## F1 Task対応

| Task | commit | migration | 主要変更 | test | Demo | status |
|---|---|---|---|---|---|---|
| F1-1 | `ccaa77cc` | V115 | master/record、AES-256-GCM CNF1、crypto service | CryptoServiceTest、EngineerCertificationServiceTest、FlywayCertificationF11SchemaSmokeTest | DRAFT 申請＋暗号化保存 | [x] |
| F1-2 | `391f0907` | V116 | t_certification_event、CERTIFICATION_EVIDENCE FileScope | FileScopeValidationServiceTest（13件） | typed link/CLEAN/version/hash 否定系 | [x] |
| F1-3 | `5dd9d8c7` | V117 | course/plan/enrollment DDL+entity | MigrationScriptIntegrityTest | DDL shape（MySQL smoke は F1-5 統合） | [x] |
| F1-4 | `24577fd4` | V118 | skill/position events、service フック | EngineerSkillServiceImplTest | replaceSkills で event 追記 | [x] |
| F1-5 | `e7d3b36d` | V119 | assessment/decision event DDL | FlywayCertificationLearningSkillGapSchemaSmokeTest | V115-V119 MySQL smoke | [x] |

## 未検証（F2 以降）

- 資格 API/UI、90/60/30 scheduler E2E
- 証憑 upload E2E（DocumentService 連携）
- 複数 JVM 通知 dedupe
- production `certification.pii.view` 権限 seed
- NF-07 `CERTIFICATION_PII` 保持年数

## Rollback

各 migration V115〜V119 を逆順 DROP（未本番適用前提）。FileScope 変更は V116 commit を revert。

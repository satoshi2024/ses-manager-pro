# Round 10 L4 evidence

## Result

- Date: 2026-08-09 JST
- Code/evidence implementation Head: `e0bd72b1021cd31dee7017b5e9f4dd475731259b`
- Command: `.\scripts\verify-like-ci.ps1`
- Exit code: `0`
- Surefire reports: **282 test classes / 1578 tests / failures 0 / errors 0 / skipped 0**
- Docker: available (`docker info` succeeded); MySQL Testcontainers smoke paths executed with zero skipped tests
- `git diff --check`: exit code `0`

The result was collected from the single clean `verify-like-ci.ps1` run at the implementation Head. The
script output was cross-checked against every `target/surefire-reports/TEST-*.xml`; no report contained a
failure, error, or skipped test. The target directory was subsequently used only to package the same
implementation Head for the local browser run.

## Directly relevant reports

| Test class | Tests / failures / errors / skipped |
|---|---:|
| `FlywayMigrationSmokeTest` | 2 / 0 / 0 / 0 |
| `FlywayLegacyV60MigrationSmokeTest` | 1 / 0 / 0 / 0 |
| `FlywayLegacyV71MigrationSmokeTest` | 1 / 0 / 0 / 0 |
| `FlywayRepairRunbookTest` | 1 / 0 / 0 / 0 |
| `FlywayV73PartialRepairSmokeTest` | 1 / 0 / 0 / 0 |
| `FlywayV79_1RepairSmokeTest` | 2 / 0 / 0 / 0 |
| `FlywayV80RepairSmokeTest` | 4 / 0 / 0 / 0 |
| `FlywayV81RepairSmokeTest` | 2 / 0 / 0 / 0 |
| `AcceptanceAsOfScopeTest` | 6 / 0 / 0 / 0 |
| `AcceptanceDocumentTest` | 3 / 0 / 0 / 0 |
| `AcceptanceIdMySqlIntegrationTest` | 1 / 0 / 0 / 0 |
| `AcceptanceServiceImplTest` | 11 / 0 / 0 / 0 |
| `ConcurrentContractizationTest` | 1 / 0 / 0 / 0 |
| `ConcurrentSubmitReopenTest` | 1 / 0 / 0 / 0 |
| `DocumentHashClaimTest` | 1 / 0 / 0 / 0 |
| `OrderAcceptanceSchemaTest` | 5 / 0 / 0 / 0 |
| `SalesOrderApiControllerTest` | 7 / 0 / 0 / 0 |
| `SalesOrderPdfServiceImplTest` | 2 / 0 / 0 / 0 |
| `SalesOrderQuotationContractIntegrationTest` | 7 / 0 / 0 / 0 |
| `SalesOrderServiceImplTest` | 9 / 0 / 0 / 0 |
| `NotificationGenerateServiceTest` | 21 / 0 / 0 / 0 |
| `AcceptanceJsRuntimeTest` | 1 / 0 / 0 / 0 |
| `JsSyntaxCheckTest` | 1 / 0 / 0 / 0 |
| `MobileResponsiveLayoutTest` | 25 / 0 / 0 / 0 |
| `RealBrowserScreenshotTest` | 1 / 0 / 0 / 0 |
| `MessageBundleConsistencyTest` | 4 / 0 / 0 / 0 |
| `SpecDispatchConsistencyTest` | 8 / 0 / 0 / 0 |

Independent Review must repeat or accept this evidence against the submitted code/evidence Head; this file
does not itself close any P0/P1/P2 issue.

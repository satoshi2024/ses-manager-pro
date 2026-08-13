# freee連携 fixture（匿名・人工データ）

本ディレクトリのJSONは**すべて人工の匿名fixture**であり、実在する人物・事業所・token・給与金額を含まない。

## 正本

- freee人事労務OpenAPI固定commit: `52c69a6819ef14979a31b342123df816cb72c742`
  （https://github.com/freee/freee-api-schema/tree/52c69a6819ef14979a31b342123df816cb72c742/hr/open-api-3）
- 確認日: 2026-08-14（`hr/open-api-3/api-schema.json`最終更新は2026-07-09、固定commit時点と同一内容）
- 公式実装参考commit（endpoint/契約の読み方のみ）: `freee/freee-mcp@826e22555a9befe5a672e9bdfc23070676f41969`

## 禁止情報

実token、認可code、state、実氏名、実従業員番号、実事業所名/ID、実給与金額、実email、
raw sandbox response をこのディレクトリへ保存してはならない。
fixtureの値はすべてplaceholder（例: `fixture-access-token`、`従業員甲`、`E-501`、事業所ID `123`）である。

## 一覧

| file | 対応endpoint / 用途 | 構造の根拠 |
|---|---|---|
| token-success.json | OAuth token endpoint 成功応答 | 公式OAuth文書（`access_token`/`refresh_token`/`expires_in`/`company_id`） |
| token-invalid-grant.json | OAuth token endpoint 失効応答 | 公式OAuth文書のerror応答（`invalid_grant`） |
| users-me-company-admin.json | `GET /api/v1/users/me`（company_admin） | 固定OpenAPI `ApiV1UsersMeSerializer` |
| users-me-self-only.json | `GET /api/v1/users/me`（self_only） | 同上（role enumに`BP`は存在しない） |
| employees-page1.json | `GET /api/v1/companies/{company_id}/employees` **raw配列** | 固定OpenAPI `ApiV1CompaniesEmployeeSerializer` |
| employees-legacy-wrapped.json | 旧実装root（`{"employees": [...]}`）再現用 | 現行実装の解析root。BP判定違反のbaseline test専用 |
| salary-calculated.json | `GET /api/v1/salaries/employee_payroll_statements`（確定） | 固定OpenAPI `ApiV1SalariesEmployeePayrollStatementsIndexSerializer` |
| salary-calculating-null.json | 同上（計算中: 金額null・配列空） | 固定OpenAPI（金額はstring、nullable） |
| bonus-calculated.json | `GET /api/v1/bonuses/employee_payroll_statements`（確定） | 固定OpenAPI `ApiV1BonusesEmployeePayrollStatementsIndexSerializer` |
| statements-legacy-null.json | 旧root/旧field（`statements`/`gross_amount`）のnull再現用 | 現行実装の解析root/field。null→0変換違反のbaseline test専用 |
| statements-legacy-items.json | 旧rootのitems再現用 | 現行実装のDTO（`Map`）と比較するbaseline test専用 |

## 注意

- `employees-page1.json`の3件目は公式schemaに存在しない未知property `employment_type: "BP"`を持つ。
  公式契約では未知propertyは無視され、この従業員も一覧へ含まれる（HFP-01-R01-3）。BP判定は本システム側で行うため、この値はfixture内の"未知property"であり実契約のenumではない。
- 金額はすべて文字列（JSON string）で表現される。`null`は「計算中」を意味し、`0`とは区別する。

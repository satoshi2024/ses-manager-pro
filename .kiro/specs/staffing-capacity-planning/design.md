# Design — 要員配置・需給計画

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。
>
> S12正式migration V103（実装済み。V1統合baselineとH2/MySQL smokeに同期済み）。

## 1. DDL（予約V103）

- `t_project_position(id, project_id, position_no, role_name, required_count, skills_json,
  unit_price_min/max, start/end_date, location, allocation_percent, priority, status, version)`。
- `t_allocation_plan(id, engineer_id, position_id NULL, allocation_type, start/end_date,
  allocation_percent, status, source_contract_id, exception_reason, approval_request_id, version)`。
- `t_staffing_scenario(id, owner_user_id, name, base_date, shared_flag, assumptions_json)`。
- `t_staffing_scenario_allocation(scenario_id, engineer_id, position_id, dates, percent)`。
- proposal/contractへposition_id。

## 2. Capacity service

- 日単位区間を月別FTEへ変換。境界はinclusive start/end、open endは計画window末。
- actual contractを優先表示、planは重ねる。二重計上をsource_contract_idで排除。
- leaveは稼働可能日数を減らすが、短期休暇で契約FTE自体を自動変更しない。

## 3. UI

- project detail position board、engineer timeline、需給heatmap、scenario compare。
- 既存analytics availability calendarとcontract ganttを再利用/リンクし、別計算口径を作らない。

## 4. Query/performance

- planning horizon最大24か月、position/engineer filter必須、大量gridはserver aggregate。
- 全engineer×全dayの直積をJava memoryへ作らない。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| position | `t_project_position.status` | 遷移は監査 | — | 現在値 | `end_date IS NULL`＝**open end**。計画window末まで |
| plan allocation | `t_allocation_plan` | 版を持たない（上書き） | — | 対象日を含む区間 | `position_id IS NULL`＝**社内/待機**（未割当ではない） |
| actual allocation | 契約から導出 | 契約側の履歴 | — | 対象日時点の有効契約 | — |
| capacity | 導出値（都度計算） | **保存しない** | scenarioのみ固定 | 計算基準日 | — |
| scenario | `t_staffing_scenario` | — | `base_date`時点の実データをcopy | `base_date` | — |
| 稼働率 | `UtilizationCalcService`（既存） | — | — | 対象月 | — |

- `position_id IS NULL`は「社内業務・待機」という**業務値**である。未割当と混同すると
  待機要員が需給計算から消える。§1.1に該当。
- **稼働率の口径は`UtilizationCalcService`を使う**（CLAUDE.md記載の既存不変条件）。
  本specで独自に「稼働中/待機」を再定義しない。dashboard KPI・FR-07 forecast と同一値になること。
- capacityは都度計算し永続化しない。permanent tableを作ると契約変更時の再計算漏れが起きる。

### 5.2 期間代数とFTE換算（確定済み。実装中に変更しない）

| 論点 | 決定 |
|---|---|
| 区間境界 | `start_date` **inclusive** / `end_date` **inclusive** |
| open end (`end_date IS NULL`) | 計画window末（最大24か月先）まで |
| 月別FTE換算 | 月内の**稼働可能日数**に対する在籍日数比 × `allocation_percent` |
| 稼働可能日数の源 | `m_work_calendar`（attendance spec）。無い場合は法人既定 |
| 月中開始/終了 | 日割り。月単位に丸めない |
| 休暇 | **稼働可能日数を減らす**が、契約FTE自体は自動変更しない（design §2） |
| 合計上限 | 同一期間の`allocation_percent`合計 > 100 を原則拒否 |
| 例外承認 | `exception_reason` + `approval_request_id` 必須（R2.2） |
| 判定単位 | **日単位**で判定する。月平均で100%以内でも、日単位で超えたら警告 |

受入条件（R5）の`50%+50%=許可` / `60%+50%=警告または拒否`は、
**期間が重なる日が1日でもあれば**判定対象になる。月単位判定にすると
「前半60%・後半50%で重複なし」を誤って拒否する。

| case | 期待 |
|---|---|
| 50%+50% 完全重複 | 許可（=100%） |
| 60%+50% 完全重複 | 拒否または例外承認（=110%） |
| 60%+50% 重複なし | 許可 |
| 60%+50% 1日だけ重複 | **その1日で拒否** |
| 隣接（前の`end_date`翌日が次の`start_date`） | 重複なし。許可 |
| 同日（前の`end_date`＝次の`start_date`） | **重複あり**（両端inclusiveのため）。合算して判定 |

### 5.3 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 全て | 需給再計算 |
| マネージャー | 組織scope ∩ DataScope | 同左 | 自組織の不足/過配賦 | 同上 |
| 営業 | 既存DataScope（担当案件/要員）。**組織で追加制限しない** | 同左 | 自担当の充足/不足 | 同上 |
| HR | 要員の配置状況（単価・粗利は**mask**） | 同左 | 採用需要 | — |
| 要員 | 自分の配置約定のみ（S14経由） | — | — | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は担当営業/マネージャー | 需給集計 |

- **bench cost・単価帯はHRからmask**する。配置は見せるが金額は見せない。
- scenarioの可視性: `owner_user_id`本人 ＋ `shared_flag=1`なら同一組織scope内。
  **共有scenarioでも実データのscopeを超えて見せない**（scenario経由のscope迂回を防ぐ）。
  scenario内の要員一覧も閲覧者のscopeでfilterする。

### 5.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| position 募集中 | →候補選定 / →取消 | 状態CAS | — | 募集中へ |
| 候補選定 | →充足 / →保留 / →取消 | 状態CAS＋`version` | 複数提案の同時確定 | 候補選定へ |
| 充足 | →募集中（欠員発生） | 状態CAS | — | — |
| 保留 / 取消 | →募集中 | 状態CAS | — | — |
| plan 下書き | →確定 / →破棄 | 状態CAS | drag&dropの連続操作 | **UI側でrollback表示**（design §3） |
| 確定 | →変更（新区間）/ →破棄 | `version` CAS | 同一要員の同時配置 | 変更前の区間へ |

- **plan と actual の二重計上排除**（design §2）: `source_contract_id IS NOT NULL`の
  allocationはactual。同一契約からplanとactualの両方を数えない。
  需給集計SQLで`source_contract_id`による排他を**WHERE句**で行う（memory filterにしない）。
- **scenario isolation**（R3.3）: scenario操作は`t_staffing_scenario_allocation`のみを更新し、
  `t_allocation_plan`・契約・提案を**一切変更しない**。
  scenarioから実データへの書き込み経路が存在しないことをtestで固定する。
- 過配賦の判定は**配置確定transaction内**で対象要員の期間行をロックして行う。
  読んでから書くまでの間に別の配置が入る競合を防ぐ。
- planning horizonは最大24か月。超える要求は**拒否**（R相当、design §4）。
  全engineer×全dayの直積をJava memoryへ作らない。server aggregateで返す。

## 6. テスト

区間境界、FTE、100%競合、actual/plan排他、更新契約、leave、scenario isolation、scope、24か月上限。


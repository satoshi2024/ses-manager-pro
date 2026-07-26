# 子Agent分担サマリー

## 1. 基本方針

子Agentは「考える人数を増やす」ためではなく、**独立した成果物と検証条件を持つ小さなレーン**へ使う。
主担当は設計判断、共有interface、merge、最終Demo、checkbox更新を保持する。子Agentへ渡す前に、対象task、
許可ファイル、禁止ファイル、入力commit、完了条件を明文化する。

同一Codex task配下のAgentは同じfilesystemを共有する。したがって、同時編集する場合はファイル単位の所有権が
必須である。所有権を切れない場合は、子Agentをread-only調査、テスト実行、diffレビューに限定する。

## 2. 子Agentへ向く仕事

| 種別 | 子Agent適性 | 任せられる成果物 | 主担当が保持するもの |
|---|---|---|---|
| inventory/調査 | 高 | SQL/API/file/permission一覧、公式仕様とのgap、テストmatrix | decision-logの最終判断、spec変更 |
| UIレーン | 高 | 専用template/JS/CSS、画面単体テスト、Demo手順 | API/DTO契約、共通layout/sidebar/message統合 |
| 独立API/サービス | 中〜高 | interface確定後の専用controller/service/test | 共通entity、transaction/state machine、認可境界 |
| provider adapter | 高 | mock/WireMock、timeout/retry/error mapping、contract test | credential方針、canonical model、job/idempotency設計 |
| 自動テスト | 高 | 境界値、権限matrix、fixture/golden file、性能計測 | production修正、最終合否判定 |
| migration/共通schema | 低 | read-onlyレビュー、reconciliation query、smoke観点 | V1/Flyway/H2/entityを1人で同期 |
| SecurityConfig/認証 | 低 | threat review、テストケース、provider mock | security chain、session/authority modelの編集 |
| `M`統合task | 低 | 分割したテスト実行と結果収集、diffレビュー | merge、障害修正、Demo、checkbox、完了宣言 |

## 3. 115タスクに対する推奨分担

### C — 主担当中心、子Agentは調査/テストのみ

- tenant T001〜T007: T001のSQL/file/job inventoryとT007のisolation matrix/容量計測は分担可。
  T002〜T006のschema、TenantContext、認証、file/exportは横断境界のため主担当が連続して編集する。
- 各specのDDL基盤task: T002、T008、T015、T022、T028、T035、T042、T048、T054、T061、T068、
  T075、T082、T088、T095、T103、T110。子AgentはDDLレビューとテスト観点だけを返し、schema一式を編集しない。
- identityのA1/A2（T016/T017）、external portal F2（T083）、accounting F2（T096）、JP PINT F2（T104）、
  AI gateway F2（T111）は共有security/canonical/interfaceを決めるため、主担当の単独所有とする。
- 全M task: T007、T013、T020、T027、T033、T040、T047、T053、T059、T066、T074、T080、T087、
  T093、T101、T108、T115。子Agentはテスト分担とレビューのみ。完了判定は統合担当1人。

### A — interface固定後、子Agentを積極利用できるtask

- archive: T024（台帳UI）、T025（既存帳票/CloudSign統合）、T026（export/retention）。
- productivity: T029（横断検索）、T030（ToDo）、T031（保存ビュー）、T032（一括操作）。
- BP master: T037（管理UI）、T038（compliance rule）、T039（risk/通知）。
- approval: T044（inbox/diff）、T045（route/代理UI）、T046（SLA通知）。
- CRM: T050（contact/timeline）、T051（lead/opportunity UI）、T052（KPI）。
- dispatch: T063（profile UI）、T064（法定帳票/archive）、T065（deadline/risk）。
- attendance: T071（休暇）、T072（provider sync）。T070/T073はcalculatorと集計契約が固定後なら別レーン可。
- staffing: T077（board/timeline）、T078（heatmap/KPI）、T079（scenario）。
- external portal: T084（顧客）、T085（BP）、T086（管理/通知）。F2の公開DTOとsecurity boundary merge後に開始。
- engineer portal: T089（dashboard/profile）、T090（給与/勤怠）、T091（経費）、T092（1on1/privacy）。

### B — 条件付きで子Agentを使うtask

- organization: T009（scope service）とT010（組織UI）は分担可。T011→T012はsnapshot/APIを固定して順送り。
- identity: T018（action permission）とT019（file scan）は別パッケージなら分担可。T016/T017と
  `SecurityConfig.java`を共有しない。
- order: T056（注文/PDF）とT057（月次検収）は分担可。T058はT057 merge後。
- attendance: T069のcalculatorは主担当。T071/T072を子Agentへ渡し、T070/T073はcoreレーンへ残すのが安全。
- accounting: T097（UI）、T098（売上）、T099（BP/経費）はprovider/job interface固定後に分担可。
  T100はT098/T099 merge後に主担当または照合専任Agentへ渡す。
- JP PINT: T105（provider）とT106（UI）は分担可。T107はprovider event/canonical model固定後。
- AI: T112（feedback/outcome）とT113（offline evaluation）は分担可。T114はevaluation API固定後。

## 4. 推奨Agent構成

### 4.1 3レーン型（A推奨spec）

```text
主担当: schema/interface/認可/merge/M task
子Agent 1: UI + browser Demo
子Agent 2: 独立service/provider + contract test
子Agent 3: 境界/権限/性能テスト + diff review
```

archive、BP、approval、CRM、dispatch、staffing、external portalで使いやすい。productivityとengineer portalは
4機能あるため、最大3子Agentで開始し、先に完了したAgentへ4本目を順送りする。

### 4.2 2レーン型（B推奨spec）

```text
主担当: core/state/security/canonical model
子Agent 1: UIまたは独立feature
子Agent 2: provider mock/自動テスト/fixtures
```

organization、identity、order、accounting、JP PINT、AIで使う。core interfaceがmergeされる前に子Agentへ
実装させない。

### 4.3 検証型（C推奨spec）

```text
主担当: 全production変更
子Agent 1: read-only inventory/脅威レビュー
子Agent 2: テスト実行/失敗分類
子Agent 3: requirements対diffレビュー
```

tenant、各DDL基盤、SecurityConfig、M taskに使う。子Agentは同じproduction fileを編集しない。

## 5. 子Agent用の短い派工テンプレート

### 5.1 read-only棚卸し/レビュー

```text
親taskは <Txxx / spec / task-id> です。あなたはread-onlyの調査担当です。
許可: repository/spec/diff/test logの読取、問題一覧と根拠の報告。
禁止: すべてのfile編集、task checkbox更新、仕様決定。
調査範囲: <SQL/file/permission/provider/requirements IDs>。
成果物: 対象一覧、漏れ、重大度、根拠file:line、推奨最小修正、追加テスト。推測は明示してください。
```

### 5.2 UIレーン

```text
親taskは <Txxx / spec / task-id> です。基盤commit <hash> のAPI/DTOを変更せず、UIレーンだけを担当してください。
所有file: <templates/...、static/js/modules/...、専用UI test>。
禁止file: SecurityConfig、entity、migration、共通service、layout/sidebar、共通message bundle、tasks.md。
完了条件: 4言語key不足を一覧化し、CSRF/session expiry/error表示、権限制御、空/大量/競合状態をテストし、
taskのDemo手順と結果を返してください。API変更が必要なら実装せず親へinterface変更案を報告してください。
```

### 5.3 provider/adapterレーン

```text
親taskは <Txxx / spec / task-id> です。固定済みinterface <path> に対するadapterとcontract testだけを担当してください。
所有file: <専用adapter package、fixture、WireMock/contract test>。
禁止: credential実値の保存、canonical model変更、DB transaction内の外部呼出し、共有job/state machine変更。
timeout、retry/backoff、rate limit、idempotency、correlation ID、監査、4xx/5xx/timeout/重複webhookを検証し、
未確認のprovider仕様は公式URLと仮定を分けて報告してください。
```

### 5.4 テスト専任レーン

```text
親taskは <Txxx / spec / task-id> です。production codeを変更せず、担当requirementsの失敗テストと検証matrixを作成してください。
所有file: <専用test/fixtureのみ>。禁止: production、migration、tasks.md。
正常系だけでなく、権限、tenant/data/file scope、境界値、競合、二重送信、rollback、既存回帰を含めてください。
テストがproduction bugを検出した場合は、再現手順、期待値、実値、最小修正候補を親へ返し、自分で範囲外修正しないでください。
```

## 6. ファイル所有権テンプレート

並行開始前に親taskのコメントまたは作業メモへ次を記入する。

| 項目 | 記入内容 |
|---|---|
| 基盤commit | 全Agentが開始する同一commit hash |
| 親task | Txxx / spec / task-id |
| Agent | 主担当または子Agent名 |
| 許可file/glob | 実際に編集してよい狭いpath |
| 禁止共有file | migration、SecurityConfig、entity、共通service、layout、messages、tasks等 |
| interface version | DTO/API/schema/provider contractの版 |
| 完了条件 | requirements ID、自動テスト、Demo |
| merge順 | 何番目に取り込むか |
| 停止条件 | interface変更、競合、未決decision、環境不足 |

## 7. 子Agentを開始してはいけない状態

- 親task自身のObjective、requirements ID、先行依存が特定できていない。
- blocking decision、法務項目、provider plan、公開fieldが未確定である。
- 基盤commit/API/DTO/schema/interfaceが動いている最中である。
- 許可fileが「関連ファイル一式」のように広く、別Agentと重複する。
- 同一migration、SecurityConfig、共通entity/service、message bundle、checkboxを複数Agentへ渡している。
- 子Agentの成果を誰がmergeし、誰がDemoし、誰がrollbackするか決まっていない。


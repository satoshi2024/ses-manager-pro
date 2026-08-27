# Tasks（NF-03 candidate）

> `[x]` はObjective、implementation evidence、required test、manual Demoをすべて満たしたTaskだけに付ける。NF-03が `CANDIDATE` の間はF1以降を開始しない。

## 0. 準備・inventory

- [x] **Task 0: 既存資産inventoryと承認前specを作成する**
  - Objective: 既存の資格/skill/career/training/demand/document/approval/self-service資産を確認し、重複masterを防ぐcandidate requirements/design/planを作る。
  - Implementation: `inventory.md`、`requirements.md`、`design.md`、`plan.md`、`tasks.md`、`completion-matrix.md`を作成する。PII、DocumentLink、taxonomy、as-of、費用approval、AI/humanのdecision tableを含める。
  - Test: production source、migration、test、seed dataに変更がなく、`git diff --check`が成功する。NF-03 traceabilityが`CANDIDATE`であることを再確認する。
  - Demo: 専用worktreeのbranch/status/remote/baseを表示し、文書のsource inventory、重複回避、未解決DG-03、Task完了対応表を確認する。

## F1. 資格/course/plan/enrollment DDL（承認後のみ）

- [ ] **Task F1-1: 資格masterと取得recordのDDL/entityを追加する**
  - Objective: 資格名、issuer、code、期限規則、engineer取得状態、番号参照、versionを正規化する。
  - Implementation: 最新migration+1、V1/H2専用schema/entity/mapperを同期し、PII fieldとduplicate uniqueを実装する。適用済みmigrationは編集しない。
  - Test: empty DB/MySQL migration、H2 context、duplicate取得、nullable/期限、PII DTO非漏えい。
  - Demo: HRがmasterを登録し、本人申請がpendingで保存され、承認前activeにならないことを確認する。

- [ ] **Task F1-2: certification eventと証憑参照を追加する**
  - Objective: submit/verify/correct/cancelのappend-only履歴とDocumentLink参照を定義する。
  - Implementation: DocumentService/FileScopeValidationService/FileReferenceProviderの既存境界に接続する。raw file pathを持たない。
  - Test: duplicate event、correct/cancel理由、scope外、未scan、unknown file、legal hold、DocumentLink union。
  - Demo: CLEAN証憑だけが対象取得recordのdetail/downloadに出て、scope外roleでは404/403相当で漏えいしないことを確認する。

- [ ] **Task F1-3: course、plan、enrollment DDL/entityを追加する**
  - Objective: course catalog、canonical target skill、learning goal、enrollment/result/certificateを正規化する。
  - Implementation: `m_skill_tag` FKを使い、必要ならcourse-skill/plan-skill joinを作る。`t_training_history`は再利用しない。
  - Test: JPY/期間/capacity、state version、cancel/correct、certificate document reference、training historyとの分離。
  - Demo: HRがcourseを登録し、本人/上長がplan/enrollmentを作り、完了前後のdetailが状態機械どおりに変わることを確認する。

## F2. service（承認後のみ）

- [ ] **Task F2-1: 資格履歴・期限・通知判定serviceを実装する**
  - Objective: 90/60/30境界、取消、訂正、重複取得、idempotent通知を実現する。
  - Test: 90/60/30当日・前後、timezone、expired/cancelled/corrected、再実行。
  - Demo: expiry dateを固定し、各境界で本人/上長recipientへ一度だけ通知されることを確認する。

- [ ] **Task F2-2: training plan/enrollment/approval serviceを実装する**
  - Objective: state transitionと費用threshold、既存approval engine、自己承認拒否を接続する。
  - Test: threshold未満/等値/超過、route不在、申請者自己承認、CAS競合、approval後completion。
  - Demo: threshold等値の申請がapprovalへ進み、申請者がapproveできず、承認後のみcompletionできることを確認する。

- [ ] **Task F2-3: as-of skill gap serviceを実装する**
  - Objective: project/position期間、skill level、evidence count、unknown/synonym、0件を説明可能に比較する。
  - Test: as-of、同義tag、未知skill、案件期間両端、0件、AI停止時rule fallback。
  - Demo: AI providerを停止してもgapが表示され、AIはcourse候補のみで評価/配置を確定しないことを確認する。

## A1. HR/manager UI（承認後のみ）

- [ ] **Task A1: HR/manager資格・training・gap list/detailを実装する**
  - Objective: role別に同じpopulationをlist/detail/count/exportで表示する。
  - Test: manager org∩DataScope、HR、admin、scope外、番号mask、empty state、390px、safePage。
  - Demo: 同一filterでlist/detail/exportの対象IDが一致し、document/PII fieldだけpolicyどおり差異があることを確認する。

## A2. 本人申請・学習計画（承認後のみ）

- [ ] **Task A2: 本人の取得申請とlearning plan/enrollment UIを実装する**
  - Objective: account linkから本人を解決し、証憑upload、plan、status、withdraw/resubmitを提供する。
  - Test: 他人engineerId改変、attachment scope、approval待ち、cancel/correct、本人export。
  - Demo: URL/APIに他人IDを渡しても本人以外を参照できず、CLEAN証憑とplan状態がdetailに反映されることを確認する。

## B1. 通知・承認・document（承認後のみ）

- [ ] **Task B1: 期限通知、費用approval、証憑download/exportを実装する**
  - Objective: recipient user ID、approval route、DocumentLink/FileScopeValidationServiceをつなぐ。
  - Test: 90/60/30、重複通知、threshold境界、自己承認、scan/legal hold/unknown、同一scope。
  - Demo: scheduler再実行後も通知/eventが重複せず、download/exportがUIと同じscopeで拒否されることを確認する。

## B2. 需要連携・AI候補（承認後のみ）

- [ ] **Task B2: staffing as-ofとAI候補を接続する**
  - Objective: project position/project skillとcanonical taxonomyを期間指定で比較し、AIをcandidate-onlyで表示する。
  - Test: as-of period、inclusive境界、同義tag、未知skill、0件、AI停止/error/timeout、AIによる最終配置なし。
  - Demo: 需要期間を変えると結果が変わり、未知skillが隠れず、AI結果を無効にしてもrule gapが残ることを確認する。

## M. 完了・Review handoff（承認後のみ）

- [ ] **Task M: mandatory test/DemoとReview packetを完成する**
  - Objective: 全受入条件とpopulation一致を証拠化し、完了対応表とremote HEADをReviewへ渡す。
  - Test: fast suite、MySQL suite、performance/CI相当、scope/PII/document/approval/AI回帰、migration/H2 smoke。
  - Demo: 資格期限90/60/30、取消/訂正、重複、証憑scope、as-of/synonym/unknown/period/0件、threshold/自己承認、AI停止、list/detail/export/本人/上長/HRの全シナリオを実演する。
  - Deliverable: M完了後にだけ `review-packet.md` を作成し、ReviewのPLAN/IMPLEMENTATION双方PASS後にPR作成工程へ引き渡す。

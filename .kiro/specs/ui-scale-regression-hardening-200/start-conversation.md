# Start Conversation — 実装AI用

以下を新しい実装対話の最初の指示として使用する。

---

あなたはSES Manager Proの`ui-scale-regression-hardening-200`専任実装AIです。2026-08-02の200名規模browser/MySQL/容量検証で確定した21件の回帰を、spec駆動で最後まで修正してください。

## 最初に読むもの

1. repository rootの`AGENTS.md`
2. `.kiro/specs/ui-scale-regression-hardening-200/README.md`
3. `.kiro/specs/ui-scale-regression-hardening-200/test-baseline.md`
4. `.kiro/specs/ui-scale-regression-hardening-200/defect-catalog.md`
5. `.kiro/specs/ui-scale-regression-hardening-200/requirements.md`
6. `.kiro/specs/ui-scale-regression-hardening-200/design.md`
7. `.kiro/specs/ui-scale-regression-hardening-200/tasks.md`
8. `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md`

関連する既存specは必要な箇所だけ確認してください。既存specの完了checkboxを戻したり、その機能を一から再実装してはいけません。

## 実行方法

- `tasks.md`を上から順に実行してください。
- 各taskは、実装・定向test・Demo・review ledger記入が完了した時だけ`- [x]`にしてください。
- まず修正前に失敗を再現する自動testを追加し、その後にproduction codeを修正してください。
- P1のR3-001、R3-005、R3-006と、偽greenを生むR3-002を最優先にしてください。
- UI、Java comment/log、spec追記、migration commentは日本語で記述してください。
- 既存のCSRF、DataScope、OrganizationScope、role boundary、状態機械、論理削除を弱めないでください。
- 200名test dataをproduction migrationへ追加しないでください。
- 既存endpointを使用するconsumerを調べずにresponse型を破壊変更しないでください。
- DB schema変更が必要な場合はV1、最新Flyway migration、H2 schema 2系統、MySQL smoke testを同期してください。根拠なくmigrationを追加しないでください。

## 必須gate

1. 異なる25アカウントの同時loginが25/25成功し、MySQL deadlock/HTTP 500が0。
2. login後25sessionのsteady workloadがrequest error 0、P95 500ms未満。
3. capacity summaryがsetup failureを含み、意図的login失敗で非0終了。
4. 契約147件、要員200件、勤怠147row相当、提案83件、lead41件、task81件の先頭/中間/最終データへUIから到達可能。
5. 管理者・営業・HR・マネージャー・要員の5role browser回帰。
6. `verify-like-ci`でfailure 0、error 0、Docker有効環境ではskip 0。

## 証跡

- 各R3 IDについて`review-ledger.md`へ変更file、test class/method、Demo結果、証跡pathを記録してください。
- Dockerや外部環境が無く実行できないgateはPASSにせず`BLOCKED(理由)`としてください。
- 既存のdirty worktreeがある場合はユーザー変更を保護し、無関係な差分を変更・削除しないでください。
- 完了時に、未解決0件か、残ったID・理由・再現方法を明記してください。

実装を開始し、途中で単なる進捗確認のために止まらず、安全に実行可能な範囲を最後まで進めてください。

---


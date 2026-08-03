# 200名規模 UI・同時実行回帰ハードニング

## 目的

2026-08-02 に、約200名のSES企業を想定した実MySQLデータを投入し、管理者・営業・HR・マネージャー・要員の5ロールでブラウザ操作、全量自動テスト、同時実行試験を行った。

本specは、その検証で再現した未修正不具合を、別AIがそのまま実装できる粒度で修正するための唯一の実行入口である。既存機能specを「実装済み」として再実行せず、本specの回帰項目だけを追加修正する。

## 対象規模と品質ゲート

- 要員: 200名
- 管理対象: 顧客25社、案件64件、契約147件、提案83件
- 月次勤怠: 月次146件、日次2,901件
- 周辺データ: 請求21件、候補者31件、リード41件、商機31件、ToDo 81件、見積41件、BP会社21社
- ロール: 管理者、営業、HR、マネージャー、要員
- 通常同時利用: 25セッション
- ログイン集中: 異なる10/25アカウントが同時にログインしても500・deadlockを発生させない
- 一覧: 100件を超えても全件へ到達でき、初期描画で全件DOM生成しない
- 権限: 許可されない操作をUIに表示しない。直接URL/APIは従来どおりサーバー側でも拒否する
- テスト: `mvn test` 0 failure / 0 error。Docker有効環境ではskip 0

## 読む順序

1. `test-baseline.md` — 再現環境、データ件数、実測結果
2. `defect-catalog.md` — 全21件の事象、再現、期待結果、根拠
3. `requirements.md` — 必須要件と受入条件
4. `design.md` — ファイル単位の実装方針、API、テスト設計
5. `tasks.md` — 実装順と完了条件
6. `start-conversation.md` — 実装AI用の開始指示
7. `review-conversation.md` — 独立Review AI用の指示
8. `review-ledger.md` — 実装・Reviewの証跡台帳

## 既存specとの関係

本specは次の既存specを置き換えず、実装後に見つかった回帰を補足する。

| 領域 | 既存spec | 本specで追加する回帰 |
|---|---|---|
| session | `enterprise-identity-security` | 異なるユーザーの同時login deadlock、容量試験の偽陰性 |
| BP取込 | `bp-availability-ingestion` | review画面のThymeleaf 500 |
| 契約・dashboard | `dashboard-and-contract-list` | 契約100件打切り、一覧Top-N、scopeラベル |
| 横断検索・ToDo | `productivity-search-saved-view` | 要員ロールへの禁止UI露出、ToDo大量表示 |
| CRM | `crm-contact-opportunity` | 存在しないcustomerIdでDB外部キー500、リード大量表示 |
| 見積 | `quotation-management` | ページ文言のi18n引数破損 |
| 候補者 | `recruiting-pipeline` | 一覧から編集できないCRUD欠落 |
| 共通UI | `frontend-common-hardening` | 403時のdummy表示、ロール別UI可視性、HTML landmark |

既存specの完了チェックを戻さない。本specの`tasks.md`と`review-ledger.md`だけで進捗を管理する。

## 優先度

- P1: R3-001、R3-002、R3-005、R3-006
- P2: R3-003、R3-004、R3-007〜R3-016
- P3: R3-017〜R3-021

## 完了の定義

1. `tasks.md`の全taskが実装・自動テスト・Demo済みで`- [x]`になっている。
2. `defect-catalog.md`の21件すべてについて、`review-ledger.md`に変更file、テスト、Demo、判定が記録されている。
3. 200名fixtureまたは同等の生成fixtureで、全5ロールのブラウザ回帰が通る。
4. 25アカウント同時loginと、login後25セッションの通常負荷がともにエラー0になる。
5. 容量試験はsetup/login failureを含めて集計し、1件でも失敗したら非0終了する。
6. Review AIがrequirementsと実diffを独立照合し、P0/P1/P2の未解決指摘が0件になる。


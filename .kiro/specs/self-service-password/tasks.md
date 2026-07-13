# Implementation Plan — 自身のパスワード変更(self-service-password)

上から順に実施する。`SecurityConfig` は編集禁止(WS-C の担当。本機能は設定変更なしで動く設計)。

- [x] 1. パスワードポリシーの共通化
  - **Objective**: R1-3。ポリシー検証の二重実装を防ぐ土台を作る。
  - **実装ガイダンス**: design.md 1章。`PasswordPolicyValidator`(static)新設 → `UserApiController` を差し替え(挙動不変)。
  - **テスト要件**: 既存 `UserApiControllerTest` が無修正でグリーン(挙動不変の証明)。
  - **Demo**: ユーザー管理画面で7文字パスワード登録 → 従来と同じエラーメッセージ。

- [x] 2. パスワード変更API
  - **Objective**: R1。全ロールが自身のパスワードを安全に変更できるエンドポイント。
  - **実装ガイダンス**: design.md 2章のコード例。対象は常に `Authentication` 由来の本人。更新は id+password のみの部分更新(update-strategy: not_null を利用)。
  - **テスト要件**: design.md 5章の6ケース(正常/現行PW不一致/ポリシー違反/新旧同一/未認証/営業ロール可)。
  - **Demo**: `curl` で営業ユーザーのパスワード変更 → 新パスワードでログインできる。

- [x] 3. ヘッダーUI(モーダル + profile.js)
  - **Objective**: R2。全ページから使えるパスワード変更UI。
  - **実装ガイダンス**: design.md 3章。header.html にメニュー項目、base.html にモーダルと script 読込、profile.js 新規。確認不一致はクライアント側で弾く。
  - **テスト要件**: `MobileResponsiveLayoutTest` 等レイアウト系既存テストがグリーンのまま。
  - **Demo**: 営業ロールでログイン → ヘッダーから変更 → 成功Toast → ログアウト → 新パスワードでログイン成功。誤った現行PWでは日本語エラーToast。

- [x] 4. 監査の確認
  - **Objective**: R3。変更操作が監査に残り、平文が漏れないこと。
  - **実装ガイダンス**: design.md 4章。`ApiAuditFilter` の記録内容を確認。ボディ非記録ならテストのみ追加。ボディ記録型ならマスク処理を追加(その場合のみ ApiAuditFilter を編集可)。
  - **テスト要件**: パスワード変更後、監査ログに PUT /api/profile/password の行があり、レコード内に平文パスワード文字列が含まれない。
  - **Demo**: 管理者の監査ログ画面に変更操作が表示される。`mvn test` 全件グリーン。

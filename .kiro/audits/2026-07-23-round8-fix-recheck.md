# 第8回対応 再レビュー — R8-01〜R8-06（新規混入・残課題）

- 確認日: 2026-07-23
- 基準: worktree（未コミット差分）。`full-audit-round8.md`（A8-01〜09）＋ spec `.kiro/specs/skillsheet-ingestion/` への対応を検証。
- 検証: `mvn test` = **BUILD SUCCESS（失敗0）**。Docker依存の `FlywayMigrationSmokeTest` のみ skip（既定どおり）。
- 方針: 改修方法と期待効果のみ記述、コードは書かない（実装は別担当）。

## 0. 結論

**A8-01〜09 の監査指摘・スキルシート取込機能は、方針どおり実装されている。**
基盤対応（AI基盤の設定駆動化・偽AI死コード削除・孤児清理のプロバイダ集約・スキル名解決・
DTO値域・PII保持バッチ・候補者連携）はいずれも設計意図に沿っており、確定トランザクションの
CAS二重確定ガードやロールバックも論理的に正しい。

ただし、**グリーンビルドが隠している新規混入の不具合が P1×1・P2×3、残課題が P3×2** ある。
新機能コアの単体テストが無いため、下記 R8-01 のような実行時エラーが自動検出できていない。
これらの解消をもって第8回サイクルをクローズ可能とする。

### A8 指摘の対応状況（逐条）

| A8-ID | 指摘 | 判定 |
|---|---|---|
| A8-01 | AI基盤の設定値未使用・クライアント供給キー・タイムアウト無し | 解消。`GeminiTextServiceImpl` が apiKey/apiUrl/model/maxTokens を使用、専用 `aiRestTemplate`(5s/60s)、エラー分類＋サニタイズ。`AiRestController` はクライアント apiKey 撤去。※ただし R8-03 の副作用を混入 |
| A8-02 | `/api/ai` 二重controller・偽skill-sheet死コード | 解消。`AiSkillSheetService`/`generateSkillSheet` 削除、`checkAiEnabled` を `error.ai.disabled` キー化、責務コメント付与 |
| A8-03 | 孤児清理の参照列allowlistハードコード | 解消。`FileReferenceProvider` 集約方式へ（推奨案）。足し忘れ構造的防止 |
| A8-04 | ファイルDLのアクセス制御無し | 対応済だが **R8-01 の不具合を混入**（存在しない列参照） |
| A8-05 | 要員PIIの保持方針未定義 | 解消。`ResumeRetentionCleanupServiceImpl`（`app.resume.retention-days`、確定済/却下の extracted_text を期限後クリア） |
| A8-06 | 候補者→要員が手入力 | 解消。取込機能＋候補者詳細「スキルシートから要員化」（candidateId 連携） |
| A8-07 | スキル名→skill_id 解決不能 | 解消。`SkillTagResolver`（正規化＋'未分類'自動作成） |
| A8-08 | `EngineerSaveDto` 値域不完全 | 解消。gender/employmentType/japaneseLevel に `@Pattern`、国籍/日本語レベルに `@Size` |
| A8-09 | Gemini例外の握り潰し | 解消。`GeminiTextServiceImpl` でスタック保持＋機密サニタイズ、4xx/5xx分類 |

---

## 1. 新規混入の不具合（要修正）

### R8-01 【P1】ファイルダウンロードが要員写真等で必ず500になる（A8-04対応で混入した回帰）

- 対象: `service/security/impl/FileScopeValidationService.assertDownloadAllowed`
- 現象: 要員照合で `QueryWrapper<Engineer>().eq("skill_sheet_file_name", storedName)` と**存在しない列**を参照している。`t_engineer` に `skill_sheet_file_name` 列はスキーマ（V1〜V43）・エンティティのどこにも無く、あるのは `photo_url` のみ。取込ファイルは手順1で早期returnするため無事だが、**要員顔写真・提案スキルシートのダウンロードは手順2に到達し「Unknown column 'skill_sheet_file_name'」でSQL例外 → 500**。顔写真は `<img src="/api/files/…">` で要員一覧・詳細に表示されるため、A8-04対応が既存のファイル表示機能を全面的に壊している。
- 改修方法: 要員照合の条件から `skill_sheet_file_name` を除き `photo_url` のみで突合する（要員はスキルシート原本を保持しない。原本は提案の `skill_sheet_path`）。あわせて「認証済みユーザーが任意ファイル名でDL → 該当なしは一律許可」経路が残るので、既存の写真/スキルシートDLが確実に通ることを回帰テストで固定する。
- 期待効果: 既存のファイル表示が復旧し、A8-04（スコープ検証）が「壊さずに効く」状態になる。

### R8-02 【P2】`@Async` の自己呼び出しで解析が同期実行になる（アップロードがブロック）

- 対象: `service/impl/ResumeIngestionServiceImpl.createJob`（`parseAsync` 呼び出し）、同 `reparse`
- 現象: `createJob` が同一Bean内の `parseAsync` を `this.` 経由で呼ぶため、`@Async("taskExecutor")` のプロキシを迂回し**同期実行**になる。アップロードAPIがテキスト抽出＋AI呼び出し（実Geminiで最大60秒）完了までブロックする。要件1-3（即応答）・6-3（「抽出中」遷移・ポーリング）が実質未達で、A7-19（MailServiceの非同期宣言と同期実装の不一致）の教訓が再発している。
- 改修方法: `parseAsync` の起動をコントローラー側（`createJob` 成功後に別途 `parseAsync` を呼ぶ）へ移すか、自己プロキシ注入／`ApplicationEventPublisher` によるイベント駆動にして、プロキシ経由で真に非同期化する。非同期化にあたり、`createJob` のDBコミット後に解析スレッドが走る順序（未コミット読取の回避）も担保する。
- 期待効果: アップロードが即応答し、一覧の「抽出中→要確認/失敗」遷移とポーリングが意味を持つ。実プロバイダ接続時にリクエストスレッドが長時間占有されない。

### R8-03 【P2】`ai.provider=gemini` にするとアプリが起動不能（マッチングBean消失）

- 対象: `service/ai/impl/AiMatchingServiceImpl`（`@ConditionalOnExpression("!'gemini'.equals(...) && !'rule'.equals(...)")`）
- 現象: gemini用のマッチング実装は存在せず、mockマッチングの条件が gemini を明示除外している。このため **`ai.provider=gemini` にすると `AiMatchingService` のBeanが1つも無くなり、`AiApiController` の依存解決に失敗してコンテキストが起動しない**。`AiTextService`/`ResumeParseService` の mock は `!gemini` で正しく退避し gemini実装へ委譲できるのに、マッチングだけこの委譲先が無い。A8-01の狙い「設定だけで実プロバイダに切替」がマッチング系で崩れている。
- 改修方法: `AiMatchingServiceImpl` の条件を `!'rule'.equals('${ai.provider:mock}')` に変更し、gemini時も mockマッチングをフォールバックとして残す（実マッチングAI実装は本フェーズ対象外）。あわせて `ai.provider=gemini` でコンテキストが起動することを検証するスライステストを追加する。
- 期待効果: 実プロバイダに切り替えてもアプリが起動する。プロバイダ切替の設計不変条件（どの provider でも全AI系Beanが一意に解決）が担保される。

### R8-04 【P2】新機能コアの単体テストが未整備

- 対象: `ResumeIngestionServiceImpl` / `SkillTagResolver` / `ResumeTextExtractorImpl` / `MockResumeParseServiceImpl`（対応する `*Test` が存在しない）
- 現象: tasks.md の P2・A1・A2・B2 で必須指定したテストが無い。最もリスクの高い確定トランザクション（要員＋スキル＋経歴の一括生成、二重確定409、途中例外の全ロールバック、未登録スキルの自動作成）が完全に無検証で、R8-01 のような実行時エラーが自動検出できていない。`mvn test` が緑でも「新機能の正しさ」は保証されていない。
- 改修方法: 最低限、(1) `confirm`（正常生成／二重確定409／途中例外でロールバックし要員が残らない）、(2) `SkillTagResolver`（既存一致・全半角/大文字小文字ゆれ一致・未登録の自動作成）、(3) 抽出（各形式・空抽出→失敗）、(4) `ai.provider=gemini` 起動（R8-03）とファイルDL回帰（R8-01）を固定するテストを追加する。
- 期待効果: 再退行を自動検出できるようになり、以後の改修で確定フローの破壊を早期に捕捉できる。

---

## 2. 残課題（P3）

### R8-05 【P3】`reject()` に状態ガードが無く、確定済ジョブを却下に倒せる

- 対象: `service/impl/ResumeIngestionServiceImpl.reject`
- 現象: 存在確認のみで、状態を無条件に「却下」へ更新する。既に「確定済」（要員生成済み）のジョブも却下に倒せてしまい、生成済み要員が残ったままジョブだけ却下という状態不整合が起きる。二重却下も可能。
- 改修方法: `要確認 / 失敗 / 取込待ち` からのみ却下可能とし、それ以外（確定済等）は409を返す（他の状態遷移と同じくCASで担保）。
- 期待効果: ジョブ状態と要員生成実績の整合が保たれる。

### R8-06 【P3】CLAUDE.md の追随が中途半端・旧 `GeminiService` が死コード化

- 対象: `CLAUDE.md`、`service/GeminiService`
- 現象: (1) CLAUDE.md は注記を追記しただけで、上段の旧記述（`GeminiService` と削除済み `/api/ai/skill-sheet/generate` を「placeholder」と説明）が残存。(2) 追記した注記は Bean競合回避に `@ConditionalOnProperty … matchIfMissing=true` を推奨するが、実コードは `@ConditionalOnExpression` を採用しており文書と実装が不一致。(3) 主要モジュール一覧に `resume-ingestion` の記載が無い。(4) 旧 `GeminiService` は `AiRestController` が `AiTextService` へ移行したため本番未使用（テストのみ参照）で、死コード化している。
- 改修方法: CLAUDE.md の AI 記述を現状（`AiTextService` 抽象・`GeminiTextServiceImpl`・`@ConditionalOnExpression` 方式・provider切替の不変条件）へ更新し、`resume-ingestion` モジュールを一覧に追加する。旧 `GeminiService` は削除（テスト参照も `AiTextService`/`GeminiTextServiceImpl` へ移行）するか、当面残すなら「非推奨・未使用」を明記する。
- 期待効果: 以後のAI改修が誤った前提（旧 `GeminiService`・`@ConditionalOnProperty` 前提）で書かれる事故を防ぐ。

---

## 3. 些細（任意）

| 区分 | 対象 | 内容 |
|---|---|---|
| 品質 | `ResumeIngestionServiceImpl.saveReview` | catch が全例外を `error.resume.invalidStatus` に丸める。JSON化失敗でも「状態不正」と誤表示。原因別に分ける。 |
| 品質 | `ResumeTextExtractorImpl.extractXlsx` | NUMERICセルを `(long)` キャスト。小数の切捨て・Excel日付シリアル値の破壊が起きる。日付/小数は書式判定して文字列化する。 |
| 設定 | `ResumeRetentionCleanupServiceImpl` / `application.yml` | `@Value` 既定90日に対し yml は30日。実効値は30で問題ないが、既定値の意図を揃えるとよい。 |
| 文書 | `MockResumeParseServiceImpl` | クラスコメントが「matchIfMissing=true」と記すが実装は `@ConditionalOnExpression`。コメントを実態へ。 |

## 4. 推奨改修順・完了条件

1. **R8-01（P1）**を最優先で修正（既存の写真表示が壊れているため）。
2. **R8-02 / R8-03**（アップロード非同期化・provider切替の起動不変条件）。
3. **R8-04**（R8-01/03 の固定テスト＋確定フロー・スキル解決のテスト）。
4. R8-05 / R8-06 / 些細。

完了条件（次回確認での検証項目）:
- 要員顔写真・提案スキルシートのダウンロードが200で返る回帰テストが緑。
- アップロードが即応答し、解析がプール（`taskExecutor`）上で非同期実行される。
- `ai.provider=gemini` でコンテキストが起動する。
- `confirm` の正常/二重確定409/ロールバックのテストが緑。
- `reject` が確定済ジョブで409。
- CLAUDE.md の AI 記述が実装（`AiTextService`/`@ConditionalOnExpression`）と一致し、旧 `GeminiService` の扱いが決着。
</content>

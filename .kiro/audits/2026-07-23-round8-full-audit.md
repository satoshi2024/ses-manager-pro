# 第8回 全体監査 — AI基盤・ファイル処理を中心とした不合理点（A8-01〜A8-09）

- 監査日: 2026-07-23
- 基準: `HEAD=b9a128c`（worktree clean・第7回サイクル A7→R7→N2 はクローズ済み）
- 方針: 全モジュールを読み取り専用で再点検。第7回で全域を精査済みのため、**未報告の新規問題**と、今回追加する「履歴書（スキルシート）取込機能」（別紙 `.kiro/specs/skillsheet-ingestion/`）の前提となる**AI基盤・ファイル処理の構造的欠陥**を中心に整理した。改修方法と期待効果のみ記述し、コードは書かない（実装は別担当）。

## 0. 総括

| 区分 | 件数 | ID |
|---|---:|---|
| P2: 業務影響・新機能の前提となる欠陥 | 4 | A8-01, A8-03, A8-06, A8-07 |
| P3: 最適化・衛生・設計整合 | 5 | A8-02, A8-04, A8-05, A8-08, A8-09 |

今回の重点は次の3系統。

1. **AI基盤が「実接続できない設計」のまま放置されている**（A8-01, A8-02）。`GeminiService` は設定値（`ai.api-url`/`ai.model`/`ai.max-tokens`）を一切使わずURL・モデルをハードコードし、APIキーをクライアントから受け取り、タイムアウト無しの素の `RestTemplate` を使う。`/api/ai` に責務の重なる2コントローラーが同居し、スキルシート生成は偽データを返す死コード。**新機能「履歴書取込」はこの基盤の上に載るため、ここを整えないと機能自体が成立しない。**
2. **アップロードファイルの取り扱いに構造的な穴がある**（A8-03, A8-04）。孤児清理の参照列がハードコードの2列allowlistで、ファイルを持つ新機能を足すたびに「アップロードした原本が24時間後に消える」事故が起きる。ダウンロードAPIにはアクセス制御が無く、PIIを含むスキルシートを名前さえ分かれば誰でも取得できる。
3. **要員データにPII（生年月日・国籍等）が増える一方、保持・マスキング方針が無い**（A8-05）。履歴書取込でPIIの流入経路が増えるため、この機会に方針を明文化する。

---

## 1. AI基盤（履歴書取込機能の前提）

### A8-01 【P2】AI連携が実接続不能な設計のまま — 設定値が全て死んでおり、キーをクライアントから受け取る

- 対象: `service/GeminiService`、`config/AiConfig`、`controller/api/AiRestController.chat`
- 現象:
  1. `AiConfig` は `apiUrl` / `model` / `maxTokens` を持つが、`GeminiService` は**どれも使っていない**。エンドポイントは定数 `GEMINI_API_URL`（`gemini-1.5-flash` 固定）にハードコードされ、モデル変更・プロバイダ切替が設定でできない。設定クラスが実装と乖離した「飾り」になっている。
  2. APIキーは `AiConfig.apiKey`（サーバー側設定）ではなく、**リクエストボディ `AiChatRequest.apiKey`（＝ブラウザから毎回送る）** で受け取る（MI-05/06 の再確認）。ブラウザにキーを持たせる前提のため、バックグラウンドのバッチ処理（履歴書取込のような非対話処理）からは呼べない。
  3. `restTemplate` は `new RestTemplate()` を直接生成しており、接続・読取タイムアウトが無い（A7-18 で指摘した「外部SaaS用のタイムアウト付き RestTemplate」を使っていない）。AI応答が遅い/無応答のとき、リクエストスレッドが無限に張り付く。
- 改修方法:
  1. `GeminiService` を「`AiConfig.apiUrl`（未設定時のみ既定URL）・`model`・`maxTokens` を使う」実装へ直す。プロバイダ差し替えを見据え、`AiTextService`（`generate(prompt): String`）のような**インターフェース＋プロバイダ実装**（`ai.provider` で `@ConditionalOnProperty` 切替、mock 実装も用意）に整理する。`AiMatchingServiceImpl` が既に採っている `@ConditionalOnProperty(name="ai.provider", havingValue="mock", matchIfMissing=true)` の型に揃える。
  2. APIキーはサーバー側 `AiConfig.apiKey`（環境変数注入）を既定にする。クライアント入力キーはオプト経路として残すにしても、サーバー側キーが設定されていればそちらを優先する。
  3. 外部SaaS用のタイムアウト付き `RestTemplate`（接続5s/読取30〜60s程度、AIは長め）を `AppConfig` に用意して注入する。Webhook用の3秒タイムアウトを流用しない。
- 期待効果: 設定だけでモデル/プロバイダ/キーを切り替えられ、対話・バッチ双方からAIを安全に呼べる基盤になる。**履歴書取込（A案の中核）がこの上に実装可能になる。**

### A8-02 【P3】`/api/ai` に責務の重なる2コントローラー + 偽データを返すスキルシート死コード

- 対象: `controller/api/AiApiController`（`/api/ai/match/**`, `/api/ai/skill-sheet/generate`）、`controller/api/AiRestController`（`/api/ai/chat`）、`service/ai/impl/AiSkillSheetServiceImpl`
- 現象:
  1. 同一ベースパス `/api/ai` に2つの `@RestController` が同居し、片方は mock サービス（`AiMatchingServiceImpl`/`AiSkillSheetServiceImpl`）、もう片方は実 `GeminiService` を使う。責務境界が不明瞭で、AI基盤を触る改修のたびにどちらを直すべきか迷う。
  2. `AiSkillSheetServiceImpl.generateSkillSheet` は**ハードコードの偽データ**（`氏名: 山田太郎…`）と、実在しないパス `"/files/skillsheets/generated_" + id + ".pdf"` を返す。一方でスキルシートPDFは `SkillSheetGenerator`（`/api/engineers/{id}/skill-sheet.pdf`、`ai-skillsheet.js`）で**実際に生成できている**。`/api/ai/skill-sheet/generate` は実機能を重複させた偽エンドポイント（フロントからは未使用を確認）。A7-20 と同型の「偽成功」残骸。
  3. `AiApiController.checkAiEnabled` は `throw new BusinessException("AI機能は現在無効化されています。")` と、**メッセージキーではなく日本語文字列を直接** `BusinessException` に渡している。他所は `BusinessException.of("error.xxx")`（i18nキー）方式なので多言語化の穴。
- 改修方法: (1) AIエンドポイントを1コントローラーに集約するか、少なくとも「対話系」「マッチング系」「生成系」の責務コメントを付けて分離を明示する。(2) `/api/ai/skill-sheet/generate` と `AiSkillSheetServiceImpl` を削除し、スキルシート生成は `SkillSheetGenerator` 経路に一本化する（AIによる文章生成が必要なら A8-01 の基盤で再実装）。(3) `checkAiEnabled` を `BusinessException.of("error.ai.disabled")` に直し、キーを5ロケールへ追加。
- 期待効果: AI機能の入口が1系統になり、偽データが本番に出る経路が消える。多言語対応の一貫性が回復する。

---

## 2. ファイル処理

### A8-03 【P2】孤児ファイル清理が「参照列2つのハードコードallowlist」で、ファイルを持つ新機能を足すたびに原本が消える

- 対象: `service/impl/FileCleanupServiceImpl.collectReferencedFileNames`（`t_engineer.photo_url` と `t_proposal.skill_sheet_path` のみ収集）、`FileCleanupScheduler`（毎週日曜3時）
- 現象: 孤児清理は「DBに紐付いていない・最終更新から24h経過したファイルを削除」する。しかし参照元の収集は**この2列を手書きで列挙しているだけ**で、新たにファイルを保存する機能（今回の履歴書取込、将来の契約書PDF保管 等）を追加しても、その参照列を `collectReferencedFileNames` に足し忘れると、**アップロードした原本が翌週の清理で削除される**。コンパイル時の担保が無く、レビューで気づけない限り必ず踏む地雷。
- 改修方法:
  1. 「ファイル参照を持つ機能」が自分の参照集合を提供する仕組みにする（例: `FileReferenceProvider` インターフェースを各機能が実装し、`FileCleanupServiceImpl` が全 provider を集約）。新機能追加時に provider を1つ実装すれば清理対象から自動的に除外される、という構造にして足し忘れを防ぐ。
  2. 当面の最小対応でも、履歴書取込テーブルの `stored_file_name` 列を必ず `collectReferencedFileNames` に追加する（別紙 design.md の「孤児清理との統合」参照）。
- 期待効果: ファイルを持つ機能の追加が「参照列の登録漏れで原本消失」という事故を構造的に起こさなくなる。

### A8-04 【P3】ファイルダウンロードにアクセス制御が無い（PIIを含むスキルシートが名前だけで取得可能）

- 対象: `controller/api/FileApiController.download`（`GET /api/files/{storedName}`）
- 現象: ダウンロードは「認証済みなら誰でも、`storedName`（UUID）を知っていれば取得可能」。所有者・データスコープの検証が無い。保存名がUUIDで推測困難とはいえ、(1) スキルシート/履歴書はPII（氏名・生年月日・国籍・顔写真）を含み、(2) `scope.sales-own-data-only` 下の営業は担当外要員の情報を見られない建前なのに、ファイル名さえ漏れれば担当外要員のスキルシートを取得できる。またレスポンスに `X-Content-Type-Options: nosniff` が無く、`inline` 配信のため将来アップロード種別が増えたときのMIMEスニッフィングリスクが残る。
- 改修方法: 保存ファイルに「所有エンティティ種別＋ID」を持たせ（履歴書取込テーブル・`t_engineer` 経由でスコープ判定できるようにする）、ダウンロード時に `DataScopeService` で参照可否を検証する。少なくとも `nosniff` を付与し、`Content-Disposition` を用途に応じて `attachment` にする。
- 期待効果: PIIファイルの越権取得経路を塞ぎ、データスコープの一貫性をファイル層まで広げる。

### A8-05 【P3】要員PIIの保持・マスキング方針が未定義（履歴書取込でPII流入が増える）

- 対象: `entity/Engineer`（`birthDate`/`nationality`/`photoUrl`）、履歴書取込で追加される抽出テキスト・原本ファイル
- 現象: 現状でも要員は生年月日・国籍・顔写真というPIIを保持するが、保持期間・マスキング・退場後の扱いの方針が無い。履歴書取込は「原本ファイル＋AI抽出テキスト（氏名・経歴が平文）」というPIIの塊を新テーブルに溜めるため、無方針のまま作ると個人情報の滞留面が一気に増える。
- 改修方法: 取込機能の設計に合わせて最小限の方針を決める。(1) 確定（要員化）後、抽出テキスト・原本を残すか消すか（監査目的で残すなら保持期間を定義）。(2) 却下された取込データの自動パージ（例: 却下後30日）。(3) ダウンロード/閲覧を HR/管理者ロールに限定。方針は spec とCLAUDE.md（主要モジュール一覧）に明記する。
- 期待効果: 個人情報の滞留を制御下に置き、後日の削除要請・監査に耐える。

---

## 3. その他（設計整合・衛生）

### A8-06 【P2】候補者→要員の変換が「氏名とスキル概要だけの手入力」で、経歴・スキルが引き継がれない

- 対象: `service/impl/CandidateServiceImpl.getEngineerInitialDto`（`name` と `skillSummary` のみ返す）、`linkConvertedEngineer`
- 現象: 採用候補者（`t_candidate`）を要員化する際、初期値として渡るのは氏名とスキル概要テキストだけ。候補者が応募時に提出した職務経歴書の情報（経歴・スキル・単価）は**人が全部手で打ち直す**。SES業務で最も頻度の高い「新規要員登録」が丸ごと手作業のため、入力工数と転記ミスが恒常的に発生する。これは今回の「履歴書取込」機能が解決すべき本丸。
- 改修方法: 履歴書取込機能（別紙）を候補者変換フローと接続する。候補者詳細から「履歴書を取込んで要員化」を起動し、取込確定時に `linkConvertedEngineer` まで一気通貫にする（design.md の「候補者連携」参照）。取込機能を単体でも動くようにしつつ、候補者経路を第2フェーズで繋ぐ。
- 期待効果: 新規要員登録の主要工数（1人あたり15〜30分の手入力）を「レビュー2〜3分」に短縮する。転記ミスとスキル表記ゆれを削減する。

### A8-07 【P2】要員スキルが「マスタ未登録の名称」を扱えず、AI/外部由来のスキル取込で破綻する

- 対象: `entity/EngineerSkill`（`skillId` 必須・FK前提）、`m_skill_tag`（`skillName`+`category`）、`controller/api/EngineerSkillApiController.replaceSkills`
- 現象: 要員スキルは `skill_id`（`m_skill_tag` のFK）でしか登録できない。履歴書やAI抽出が返すのは「React」「AWS」等の**スキル名文字列**なので、マスタに無い名称は登録できず落ちる。マスタ整備を人手で先行させない限り、外部由来のスキル取込が成立しない。
- 改修方法: 「スキル名 → `skill_id` 解決」の共通処理を1つ用意する（既存タグに大文字小文字・全半角を正規化して突合、無ければ `category='未分類'` で新規作成）。履歴書取込の確定時と、将来のCSV取込の双方から使う。表記ゆれ抑制のため別名（エイリアス）を持たせるかは別途判断。
- 期待効果: 外部データからのスキル登録が自動化でき、`m_skill_tag` が実データに追随して育つ。

### A8-08 【P3】`EngineerSaveDto` のバリデーションが Entity/DBと不完全同期（MI-27 の未了分）

- 対象: `dto/engineer/EngineerSaveDto`
- 現象: `status` には `@Pattern`（`稼動中|退場予定|Bench|提案中|空`）があるが、`gender`/`employmentType`/`japaneseLevel`/`nationality` には allowlist 制約が無い。API直叩きで任意文字列が入り、集計・フィルタの前提（固定選択肢）が崩れる。MI-27 の「DTOとDB ENUM/桁の完全同期」が要員でも未了。履歴書取込は AI が推定した値をこのDTO経由で入れるため、ここが緩いと不正値がそのまま要員マスタに入る。
- 改修方法: `gender`/`employmentType`/`japaneseLevel` に `@Pattern` の allowlist を付け、桁上限（`@Size`）を DB 定義に合わせる。取込確定APIも同じDTO/バリデーションを通す。
- 期待効果: 手入力・AI取込のどちらの経路でも要員マスタの値域が保証される。

### A8-09 【P3】`GeminiService` の例外処理が全握り潰しで、失敗原因がログから追えない

- 対象: `service/GeminiService.generateContent`（`catch (Exception e)` → `log.error("Gemini API Error (Provider: Gemini)")` のみでスタックトレース無し → 汎用 `RuntimeException`）
- 現象: APIエラー時、ログにプロバイダ名の固定文字列だけ出してスタックトレースも原因（HTTPステータス・レスポンス本文）も捨てる。障害切り分けが不能。プロバイダ名も引数の文字列連結でハードコード。履歴書取込のバッチ失敗時、原因不明で運用が詰まる。
- 改修方法: `log.error("Gemini API 呼び出し失敗", e)` のように例外を渡してスタックを残す（ただしAPIキー・PIIをログに出さないマスキングは維持）。呼び出し側（取込ジョブ）へは分類済みの `BusinessException`（4xx=入力/設定起因、5xx=外部障害）で返し、`error_message` にサニタイズ済みの要約を保存する。
- 期待効果: AI失敗時に原因追跡ができ、取込ジョブの「失敗」状態から復旧できる。

---

## 4. 第7回からの持ち越し（未実装・再確認済み、二重報告回避のためIDのみ）

第8回で新規に着手すべきものではないが、**まだ残っている**ことを確認した。優先度は下記の新規P2と同等以上。

| 旧ID | 現状 |
|---|---|
| N2-5-6 | 回帰固定テスト3種（要員ログイン着地・dashboard無しロール着地・approveロック順）未追加。 |
| N2-5-2 | `LoginPageController` の switch `default` をスキップ動作へ（skill-tagのみ許可ロールで404）。 |
| R2-05 | `releaseIfIdle` の提案/契約カウントが非ロック読取。 |
| R6-02/03 | Flyway repair allowlist 検証・Docker有効CI 未整備。 |
| MI-26/27 | User POST status allowlist / DTO⇔DB制約の完全同期（A8-08はこの要員分）。 |
| A7-24後半 | 金額共通フォーマッタ・AIエラーの内部情報露出（A8-01/09と同時整理推奨）。 |
| A7-04残 | 締結済み署名PDF/証明書の保存（未実装宣言 or 実装の決着）。 |

## 5. 推奨改修順

1. **A8-01 / A8-03**（履歴書取込の土台。AI基盤の実接続化とファイル清理の構造化を先に）。
2. **A8-06 / A8-07 / A8-08**（要員化フローと取込先の受け皿。スキル名解決とDTO値域）。
3. 履歴書取込機能の本体実装（別紙 `.kiro/specs/skillsheet-ingestion/tasks.md`）。
4. **A8-02 / A8-04 / A8-05 / A8-09**（衛生・PII・アクセス制御。取込機能と同時に片付けると手戻りが少ない）。
5. 第7回持ち越し（4章）を並行で消化。

## 6. 完了条件（次回監査での検証項目）

1. `ai.model`/`ai.api-url` を設定で変えると `GeminiService` の呼び先が変わるテストが緑。AIキーがサーバー側設定から解決される。
2. `/api/ai/skill-sheet/generate` と `AiSkillSheetServiceImpl` の偽データがリポジトリに存在しない。
3. ファイルを持つ新機能を足したとき、参照登録をしないと清理される／登録すれば守られる、を検証するテストが緑。
4. 担当外要員のスキルシートファイルをダウンロードできないテストが緑。
5. スキル名文字列からの要員スキル登録（マスタ未登録名の自動作成含む）が動作する。
6. `EngineerSaveDto` の gender/employmentType/japaneseLevel に allowlist 制約が入っている。
</content>
</invoke>

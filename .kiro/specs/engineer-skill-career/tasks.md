# Implementation Plan — 要員スキル・経歴・案件要求スキル(P1)

- [ ] 1. エンティティ・Mapper・DTO の作成
  - **Objective**: `t_engineer_skill` / `t_engineer_career` / `t_project_skill` をコードから扱えるようにする。
  - **実装ガイダンス**: design.md 1.1〜1.3 の通り `EngineerSkill` / `EngineerCareer` / `ProjectSkill` エンティティ、`BaseMapper` 継承 Mapper、JOIN 用 `@Select`(`selectDetailByEngineerId` / `selectDetailByProjectId`)、`EngineerSkillDetailDto` / `ProjectSkillDetailDto` を作成。`deletedFlag` フィールドは持たせない。
  - **テスト要件**: H2 テストスキーマに 3 テーブル + `m_skill_tag` を追加し、Mapper の insert/selectDetail をH2で検証する統合テストを1件。
  - **Demo**: `mvn test` グリーン。

- [ ] 2. スキル置換サービスの実装(テスト駆動)
  - **Objective**: `EngineerSkillService.replaceSkills` / `ProjectSkillService.replaceSkills` の全置換ロジックを固める。
  - **実装ガイダンス**: 先に `EngineerSkillServiceImplTest` を書く(全置換で旧データが消える/重複 skillId が除外される/engineerId が強制上書きされる)。その後 `@Transactional` 実装。
  - **テスト要件**: 上記3ケース + 空リストで全削除になるケース。
  - **Demo**: `mvn test -Dtest=EngineerSkillServiceImplTest` パス。

- [ ] 3. API コントローラー3本の追加
  - **Objective**: skills(GET/PUT)、careers(CRUD)、project skills(GET/PUT)を HTTP 公開する。
  - **実装ガイダンス**: design.md 3.1〜3.3。経歴の日付逆転は `BusinessException`。既存 `GlobalExceptionHandler` が JSON 化することを確認。
  - **テスト要件**: `@WebMvcTest` で正常系 + 日付逆転エラー系。
  - **Demo**: `curl http://localhost:8080/api/engineers/1/skills` で JOIN 済み JSON が返る。

- [ ] 4. 要員詳細画面の実データ化(スキル)
  - **Objective**: ハードコードバッジを廃止し実データ表示 + 編集モーダルを付ける。
  - **実装ガイダンス**: `engineer-detail.js` の `skillsHtml` ハードコード部を `loadSkills()` に置換。`#skillEditModal` は行追加型、スキル select は `/api/skill-tags` からカテゴリ optgroup で構築。
  - **テスト要件**: 手動確認中心(JSのため)。
  - **Demo**: 要員詳細でスキルを2件登録→リロードで表示され、1件に置換保存できる。

- [ ] 5. 要員詳細画面への経歴タブ追加
  - **Objective**: 経歴の一覧・追加・編集・削除を要員詳細で行えるようにする。
  - **実装ガイダンス**: `detail.html` にタブ + テーブル + `#careerModal`。削除は SweetAlert2 確認。period_from 降順表示。
  - **Demo**: 経歴を登録→編集→削除の一連操作が Toast 表示付きで完了する。

- [ ] 6. 案件編集モーダルへの要求スキル追加
  - **Objective**: 案件に必須/尚可スキルを登録し一覧にバッジ表示する。
  - **実装ガイダンス**: `project.js` の保存処理を「本体保存成功 → PUT skills」の直列化に変更。一覧行に必須スキルバッジ(強調色)を描画。
  - **Demo**: 案件に必須スキル2件+尚可1件を登録し、一覧にバッジが出る。

- [ ] 7. スキル条件検索
  - **Objective**: 要員一覧をスキル AND 条件で絞り込めるようにする。
  - **実装ガイダンス**: `EngineerApiController.page` に `skillIds` 追加(design.md 3.4 の `inSql` 方式)。一覧画面にスキル複数選択 UI。
  - **テスト要件**: H2 統合テスト — スキルA+Bを両方持つ要員のみヒットすること。
  - **Demo**: 画面で「Java + AWS」を選び検索すると両方持つ要員だけが表示される。

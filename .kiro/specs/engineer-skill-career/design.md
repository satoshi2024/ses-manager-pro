# Design Document — 要員スキル・経歴・案件要求スキル(P1)

新規テーブルなし。既存 DDL の `t_engineer_skill` / `t_engineer_career` / `t_project_skill` を使う。
これらのテーブルに `deleted_flag` 列は無いため、エンティティに `deletedFlag` フィールドを**持たせない**
(MyBatis-Plus の論理削除はフィールドが存在する場合のみ適用される。`removeById` は物理削除になるが、
中間テーブル/明細テーブルなので問題ない)。

## 1. エンティティ / Mapper

### 1.1 新規エンティティ(`entity/`)

```java
@Data @TableName("t_engineer_skill")
public class EngineerSkill {
    @TableId(type = IdType.AUTO) private Long id;
    private Long engineerId;
    private Long skillId;
    private String proficiency;      // 初級/中級/上級
    private Integer experienceYears;
}

@Data @TableName("t_engineer_career")
public class EngineerCareer {
    @TableId(type = IdType.AUTO) private Long id;
    private Long engineerId;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String projectName;
    private String clientIndustry;
    private String role;
    private String description;
    private String techStack;
    private Integer teamSize;
}

@Data @TableName("t_project_skill")
public class ProjectSkill {
    @TableId(type = IdType.AUTO) private Long id;
    private Long projectId;
    private Long skillId;
    private String requiredLevel;    // 初級/中級/上級
    private Integer isMust;          // 1:必須 0:尚可
}
```

### 1.2 Mapper(`mapper/`)

`EngineerSkillMapper` / `EngineerCareerMapper` / `ProjectSkillMapper` — `BaseMapper<T>` を継承。
スキル名を JOIN で取る 2 メソッドのみ注釈 SQL(既存の `RoleMenuMapper.selectMenuKeysByRole` と同じ流儀):

```java
@Mapper
public interface EngineerSkillMapper extends BaseMapper<EngineerSkill> {
    @Select("SELECT es.id, es.engineer_id, es.skill_id, es.proficiency, es.experience_years, " +
            "st.skill_name, st.category " +
            "FROM t_engineer_skill es JOIN m_skill_tag st ON es.skill_id = st.id " +
            "WHERE es.engineer_id = #{engineerId} ORDER BY st.category, st.skill_name")
    List<EngineerSkillDetailDto> selectDetailByEngineerId(Long engineerId);
}
// ProjectSkillMapper.selectDetailByProjectId も同型(is_must DESC, skill_name 順)
```

### 1.3 DTO(`dto/engineer/EngineerSkillDetailDto.java`, `dto/project/ProjectSkillDetailDto.java`)

エンティティのフィールド + `skillName` + `category`(+ project 側は `requiredLevel` / `isMust`)。

## 2. サービス

```java
public interface EngineerSkillService extends IService<EngineerSkill> {
    List<EngineerSkillDetailDto> listDetail(Long engineerId);
    void replaceSkills(Long engineerId, List<EngineerSkill> skills);  // @Transactional: delete→distinct→insert
}
public interface EngineerCareerService extends IService<EngineerCareer> {}  // 純CRUD
public interface ProjectSkillService extends IService<ProjectSkill> {
    List<ProjectSkillDetailDto> listDetail(Long projectId);
    void replaceSkills(Long projectId, List<ProjectSkill> skills);
}
```

`replaceSkills` 実装(`service/impl/EngineerSkillServiceImpl`):
1. `remove(new LambdaQueryWrapper<EngineerSkill>().eq(EngineerSkill::getEngineerId, engineerId))`
2. `skillId` で distinct(Requirement 1-AC3)、`engineerId` を強制セットして `saveBatch`
3. `@Transactional(rollbackFor = Exception.class)`

経歴の日付妥当性(Requirement 2-AC3)は `EngineerCareerApiController` 側で
`periodTo != null && periodFrom != null && periodTo.isBefore(periodFrom)` の時
`throw new BusinessException("終了時期は開始時期以降を指定してください")`。

## 3. API

### 3.1 `controller/api/EngineerSkillApiController`(`/api/engineers/{engineerId}/skills`)
| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/engineers/{engineerId}/skills` | `listDetail` 結果を `ApiResult<List<EngineerSkillDetailDto>>` で返す |
| PUT | `/api/engineers/{engineerId}/skills` | body: `List<EngineerSkill>` → `replaceSkills` |

### 3.2 `controller/api/EngineerCareerApiController`(`/api/engineers/{engineerId}/careers`)
GET(一覧, periodFrom 降順)/ POST / PUT / DELETE `/{id}` — 既存 CRUD パターン踏襲。

### 3.3 `controller/api/ProjectSkillApiController`(`/api/projects/{projectId}/skills`)
GET / PUT(全置換)— 3.1 と同型。

※ いずれも URL が `/api/engineers` / `/api/projects` 配下なので、`m_menu` の既存 `api_prefix`
(engineer / project メニュー)による権限制御がそのまま効く。メニュー追加は不要。

### 3.4 要員検索の拡張(`EngineerApiController.page`)

`@RequestParam(required = false) List<Long> skillIds` を追加し、AND 条件で絞る:

```java
if (skillIds != null && !skillIds.isEmpty()) {
    for (Long skillId : skillIds) {
        queryWrapper.inSql(Engineer::getId,
            "SELECT engineer_id FROM t_engineer_skill WHERE skill_id = " + skillId);
    }
}
```
`skillId` は `Long` 型のため SQL インジェクションの余地はないが、念のため `Objects.requireNonNull` + 数値であることを前提とする。

## 4. フロントエンド

### 4.1 要員詳細(`templates/engineer/detail.html` + `static/js/modules/engineer-detail.js`)
- `loadSkills()`: GET skills → カテゴリごとにバッジ描画(習熟度で色分け: 上級=accent、経験年数を「(3年)」表記)。ハードコード分(67〜77行目付近)を削除。
- スキル編集モーダル `#skillEditModal`: 行追加型 UI(スキル select は `/api/skill-tags` から取得しカテゴリで optgroup)。保存 = PUT 全置換。
- 経歴タブ: テーブル + `#careerModal`(既存 CRUD モーダルパターン)。

### 4.2 案件(`templates/project/list.html` + `static/js/modules/project.js`)
- 案件編集モーダルに「要求スキル」セクション(行追加型、必須チェックボックス付き)。
- 保存時は案件本体 POST/PUT 成功後に PUT `/api/projects/{id}/skills` を直列実行。

### 4.3 要員一覧(`templates/engineer/list.html` + `engineer.js`)
- 検索フォームにスキル複数選択(Bootstrap の multiple select または チップ式)。検索時 `skillIds=1&skillIds=5` 形式で送信。

## 5. テスト

- `EngineerSkillServiceImplTest`: replaceSkills の全置換・重複除外・トランザクション(H2)。
- `EngineerCareerApiControllerTest`(`@WebMvcTest`): 日付逆転で code≠200。
- H2 スキーマ: `src/test/resources/sql/` に `t_engineer_skill` / `t_engineer_career` / `t_project_skill` / `m_skill_tag` を追加。

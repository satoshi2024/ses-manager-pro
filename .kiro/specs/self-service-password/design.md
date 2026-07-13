# Design — 自身のパスワード変更(self-service-password)

マイグレーション不要。編集対象(これ以外に触れないこと):

- `src/main/java/com/ses/controller/api/ProfileApiController.java`(新規)
- `src/main/java/com/ses/dto/profile/PasswordChangeRequest.java`(新規)
- `src/main/java/com/ses/common/util/PasswordPolicyValidator.java`(新規 — 共通化先)
- `src/main/java/com/ses/controller/api/UserApiController.java`(`validatePasswordPolicy` を
  共通クラス呼び出しに置換する**最小差分のみ**)
- `src/main/resources/templates/layout/header.html` / `layout/base.html`
- `src/main/resources/static/js/profile.js`(新規)
- テスト: `controller/api/ProfileApiControllerTest.java`(新規)

## 1. パスワードポリシーの共通化(R1-3)

新規 `common/util/PasswordPolicyValidator.java`:

```java
public final class PasswordPolicyValidator {
    private PasswordPolicyValidator() {}
    /** 8文字以上・英字と数字を含む。違反は BusinessException。 */
    public static void validate(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw new BusinessException("パスワードは8文字以上で英字と数字を含めてください");
        }
    }
}
```

`UserApiController.validatePasswordPolicy` の中身をこの呼び出しに差し替える
(private メソッドは残しても除去してもよいが、ロジックの二重管理はしない)。

## 2. API(R1)

新規 `dto/profile/PasswordChangeRequest.java`(`@NotBlank` 付き2フィールド)。

新規 `ProfileApiController`:

```java
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileApiController {
    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

    @PutMapping("/password")
    public ApiResult<Boolean> changePassword(@Valid @RequestBody PasswordChangeRequest req,
                                             Authentication authentication) {
        SysUser user = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, authentication.getName()));
        if (user == null) throw new BusinessException("ユーザーが見つかりません");
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
            throw new BusinessException("現在のパスワードが正しくありません");
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword()))
            throw new BusinessException("現在と同じパスワードは設定できません");
        PasswordPolicyValidator.validate(req.getNewPassword());
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(req.getNewPassword()));
        return ApiResult.success(sysUserService.updateById(update));
    }
}
```

セキュリティ設定の変更は不要: `/api/profile/**` は `anyRequest().authenticated()` に該当し、
`m_menu` に登録しないため `MenuPermissionFilter` は素通しする(FileApiController と同じ設計)。
`SecurityConfig` は**編集しない**(WS-C が編集するため)。

## 3. UI(R2)

- `layout/header.html`: ユーザードロップダウンの `<ul>` に
  `<li><a class="dropdown-item" href="#" id="open-password-modal">…パスワード変更</a></li>` を追加
  (既存項目のアイコン/クラスの流儀に合わせる。ドロップダウンが存在しない場合は
  ログアウトリンク付近に同様のメニューを新設する — 実装時に現物を確認)。
- モーダルは `layout/base.html` の `toast-container` 付近(全ページ共通領域)に配置。
  id: `passwordChangeModal`。フィールド: `pw-current` / `pw-new` / `pw-confirm`(type=password,
  autocomplete=current-password / new-password)。
- `layout/base.html` のスクリプト読込に `<script th:src="@{/js/profile.js}"></script>` を
  common.js の後に追加。
- `static/js/profile.js`:
  - `#open-password-modal` クリック → フォームリセット → `bootstrap.Modal` show。
  - 保存ボタン: new !== confirm なら `Toast.error('新しいパスワードが一致しません')` で送信しない。
  - `SES.api.put('/api/profile/password', {currentPassword, newPassword})`(CSRF は
    `SES.api._fetch` が自動付与)→ 成功でモーダル hide + `Toast.success('パスワードを変更しました')`。
  - 失敗は `SES.api._fetch` が `result.message` を Toast 表示して throw する既存挙動に任せる
    (catch で握りつぶすのみ)。

## 4. 監査(R3)

`ApiAuditFilter` の記録内容(メソッド・パス・ユーザー等)を実装前に確認し、
リクエストボディを記録していないこと(=平文パスワードが残らないこと)を確かめる。
ボディを記録する実装だった場合は、`/api/profile/password` を記録除外ではなく
**ボディ記録のみマスク**する対応を `ApiAuditFilter` に追加する(その場合のみ同ファイルを編集可)。

## 5. テスト設計

`ProfileApiControllerTest`(MockMvc + H2、`UserApiControllerTest` のパターン踏襲。
test プロファイルは NoOp エンコーダーなので平文比較で書ける):
- 正常系: 正しい現行PW + ポリシー適合の新PW → 200、DB のパスワードが更新される。
- 現行PW不一致 → BusinessException メッセージ。
- ポリシー違反(7文字/数字なし) → 拒否。
- 新旧同一 → 拒否。
- 未認証 → 401/リダイレクト(既存のセキュリティ挙動)。
- 営業ロールでも 200(管理者専用でないこと)。

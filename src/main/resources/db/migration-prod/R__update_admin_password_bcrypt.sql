-- R__update_admin_password_bcrypt.sql（prod専用 Repeatable）
--
-- 初期管理者(admin)のパスワードを、配備時にsecretから注入したBCrypt hashへ置き換え、
-- 初回ログイン時のパスワード変更を要求する（配備決定-3。公開済みの既知パスワードを本番へ残さない）。
--
-- 注入方法: Flyway placeholder「admin_initial_password_hash」。
--   専用Flyway job（mvn -Pprod-migrate、設定は ops/flyway/flyway-prod.conf）では環境変数
--   FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH から渡す。値そのものはrepositoryに置かない。
--   未設定はFlywayがmigration解決時に失敗させ、空・BCrypt以外・公開済みhashは
--   ProductionAdminInitialPasswordFlywayCallback（BEFORE_MIGRATE）が1件もmigrationを実行する前に失敗させる。
--   下のprocedureの検査は、callbackを登録しない経路で実行された場合の多重防御である。
--
-- 一度だけ: 置換するのはadminが初期状態（V2の平文 admin123、NULL、旧scriptが書いた公開hash）の
--   ときだけである。初回ログインで変更した後や、注入済みhashのまま再実行（secret変更でchecksumが
--   変わった場合を含む）しても何もしない。
--
-- 秘匿: hashは次のSET文1行だけに現れる。検査とUPDATEはsession変数を参照するため、
--   失敗時にFlywayが出力する文（CALL）にもprocedure定義にもhashは含まれない。
--   session変数は最後に消す。

SET @ses_admin_initial_password_hash = '${admin_initial_password_hash}';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_admin_initial_password $$
CREATE PROCEDURE __ses_admin_initial_password()
BEGIN
  -- BCrypt（2a/2b/2y、cost 10〜31、salt+hash 53文字）だけを受け付ける。照合option c は大小文字を区別する。
  -- NO_BACKSLASH_ESCAPES の有無に依存しないよう、ドル記号は [$] で表す。
  IF @ses_admin_initial_password_hash IS NULL
     OR COALESCE(REGEXP_LIKE(@ses_admin_initial_password_hash,
          '^[$]2[aby][$](1[0-9]|2[0-9]|3[01])[$][./A-Za-z0-9]{53}$', 'c'), 0) = 0
     OR @ses_admin_initial_password_hash = '$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '初期管理者パスワードhashが未設定・空・BCrypt以外、または公開済みの値です';
  END IF;

  UPDATE sys_user
     SET password = @ses_admin_initial_password_hash,
         password_change_required = 1,
         failed_count = 0,
         locked_until = NULL
   WHERE username = 'admin'
     AND (password IS NULL
          OR password = 'admin123'
          -- 旧版の本scriptが書いていた admin123 の公開BCrypt hash
          OR password = '$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y'
          -- さらに旧いscriptが書いた不正なhash
          OR password = '$2a$10$wT1B/7j/c1oY76N.zT1sC.H9a6n8R7E4m5h6i7j8k9l0m1n2o3p4q');
END $$
CALL __ses_admin_initial_password() $$
DROP PROCEDURE __ses_admin_initial_password $$
DELIMITER ;

SET @ses_admin_initial_password_hash = NULL;

-- ============================================================
-- V186: 初回ログイン時のパスワード変更要求フラグ（配備バッチD1 / 配備決定-3）
--
-- 1 = 本人がパスワードを変更するまで、パスワード変更画面とAPI以外の画面・APIを遮断する
--     （PasswordChangeEnforcementFilter。local password loginのsessionだけが対象）。
-- 0 = 通常。既存・新規ユーザーの既定値。
--
-- このmigrationはフラグ列を追加するだけで、どのユーザーにも1を立てない。
-- 本番の初期adminへ1を立てるのは db/migration-prod/R__update_admin_password_bcrypt.sql
-- （secretから注入した初期パスワードhashと同じUPDATE）であり、dev/testのadmin(admin123)は
-- 従来どおりログイン直後から業務画面へ入れる。
-- 規約（2026-11-13 決定XB-6）: 新しい列はV1へ書き戻さず、新しいmigrationだけで追加する。
-- ============================================================
ALTER TABLE sys_user
    ADD COLUMN password_change_required TINYINT NOT NULL DEFAULT 0
        COMMENT '初回ログイン時のパスワード変更要求（1=変更まで業務画面・APIを遮断）';

-- V102_1 が作成し、後続のどのmigrationもDROPしていなかったhelper procedureを片付ける。
-- 適用済みmigrationは編集しない原則のため、V102_1本文ではなくここで消す。
-- 以後のmigrationは本procedureを呼ばない（呼んでいたのはV102_1の2箇所だけ）。
DROP PROCEDURE IF EXISTS __ses_g2_1_repair_check;

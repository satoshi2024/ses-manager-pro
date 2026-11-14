-- V188相当のH2 schema（ポータルユーザーと要員の紐付け）。
-- H2は生成列のSTORED指定を解釈しないため、db/migration/V188 と同じ式をSTORED無しで定義する。
-- FKは置かない: 共有H2はcontextごとにV1（t_portal_user・t_engineer のDROP TABLE）を再実行するため、
-- 参照先のFKがあるとDROPが失敗する。参照の整合はserviceの検査と実MySQL（V188のFK）で担保する。
-- schema-portal-h2.sql がポータル表を作り直した後に置き、毎回作り直して冪等にする。
DROP TABLE IF EXISTS t_portal_user_engineer_link;
CREATE TABLE t_portal_user_engineer_link (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      VARCHAR(100) NOT NULL,
    portal_user_id BIGINT NOT NULL,
    engineer_id    BIGINT NOT NULL,
    linked_by      BIGINT NOT NULL,
    linked_at      DATETIME NOT NULL,
    released_by    BIGINT,
    released_at    DATETIME,
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag   TINYINT NOT NULL DEFAULT 0,
    active_portal_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN released_at IS NULL AND deleted_flag = 0 THEN portal_user_id ELSE NULL END
    )
);
CREATE UNIQUE INDEX uk_portal_user_engineer_link_active ON t_portal_user_engineer_link (active_portal_user_id);
CREATE INDEX idx_portal_user_engineer_link_engineer ON t_portal_user_engineer_link (tenant_id, engineer_id, released_at);
CREATE INDEX idx_portal_user_engineer_link_user ON t_portal_user_engineer_link (portal_user_id);

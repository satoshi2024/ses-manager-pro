-- V113: 同一clientRequestIdを別HTTP operationへ誤適用しないための冪等境界。
ALTER TABLE t_pwa_client_mutation
    ADD COLUMN operation VARCHAR(160) NULL AFTER user_scope_hash;

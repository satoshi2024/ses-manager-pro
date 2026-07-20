package com.ses.common.util;

import com.ses.common.base.BaseEntity;

import java.lang.reflect.Method;

/**
 * API層でエンティティを直接受け取る際の保護ユーティリティ
 */
public class EntityProtectUtil {
    
    public static void protectForCreate(Object entity) {
        if (entity == null) {
            return;
        }
        
        if (entity instanceof BaseEntity) {
            BaseEntity base = (BaseEntity) entity;
            base.setId(null);
            base.setDeletedFlag(null);
            base.setCreatedAt(null);
            base.setUpdatedAt(null);
        }
        
        clearField(entity, "setCreatedBy", Long.class);
        clearField(entity, "setUpdatedBy", Long.class);
    }
    
    private static void clearField(Object entity, String methodName, Class<?> paramType) {
        try {
            Method method = entity.getClass().getMethod(methodName, paramType);
            method.invoke(entity, (Object) null);
        } catch (Exception e) {
            // NoSuchMethodExceptionなどは無視（フィールドが存在しない場合）
        }
    }
}

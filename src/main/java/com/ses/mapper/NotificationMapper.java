package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
    @Select("SELECT COUNT(*) FROM t_notification n LEFT JOIN sys_user u ON u.id=#{userId} WHERE " +
            "(u.role IS NULL OR u.role='管理者' OR n.menu_key IS NULL OR EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id WHERE rm.role=u.role AND m.menu_key=n.menu_key)) " +
            "AND NOT EXISTS (SELECT 1 FROM t_notification_read r WHERE r.notification_id=n.id AND r.user_id=#{userId})")
    long countUnread(@Param("userId") Long userId);

    /**
     * 対象ユーザーの未読通知を一括で既読化する（1回のINSERT..SELECTで完結）。
     * ループでの1件ずつのINSERTより高速で、旧実装が持っていた1000件上限も無い。
     */
    @Insert("INSERT INTO t_notification_read (notification_id, user_id, read_at) " +
            "SELECT n.id, #{userId}, CURRENT_TIMESTAMP FROM t_notification n LEFT JOIN sys_user u ON u.id=#{userId} WHERE " +
            "(u.role IS NULL OR u.role='管理者' OR n.menu_key IS NULL OR EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id WHERE rm.role=u.role AND m.menu_key=n.menu_key)) AND " +
            "NOT EXISTS (SELECT 1 FROM t_notification_read r " +
            "WHERE r.notification_id = n.id AND r.user_id = #{userId})")
    int markAllReadForUser(@Param("userId") Long userId);

    @Select("<script>" +
            "SELECT COUNT(*) FROM t_notification n LEFT JOIN sys_user u ON u.id=#{userId} " +
            "LEFT JOIN t_notification_read r ON r.notification_id = n.id AND r.user_id = #{userId} " +
            "<where> (u.role IS NULL OR u.role='管理者' OR n.menu_key IS NULL OR EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id WHERE rm.role=u.role AND m.menu_key=n.menu_key)) " +
            "<if test='type != null and type != \"\"'> AND n.type = #{type} </if> " +
            "<if test='unreadOnly != null and unreadOnly == true'> AND r.id IS NULL </if> " +
            "</where> " +
            "</script>")
    long countPageForUser(@Param("userId") Long userId, @Param("type") String type, @Param("unreadOnly") Boolean unreadOnly);

    @Select("<script>" +
            "SELECT n.*, (r.id IS NOT NULL) AS is_read FROM t_notification n LEFT JOIN sys_user u ON u.id=#{userId} " +
            "LEFT JOIN t_notification_read r ON r.notification_id = n.id AND r.user_id = #{userId} " +
            "<where> (u.role IS NULL OR u.role='管理者' OR n.menu_key IS NULL OR EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id WHERE rm.role=u.role AND m.menu_key=n.menu_key)) " +
            "<if test='type != null and type != \"\"'> AND n.type = #{type} </if> " +
            "<if test='unreadOnly != null and unreadOnly == true'> AND r.id IS NULL </if> " +
            "</where> " +
            "ORDER BY is_read ASC, n.created_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<NotificationDto> selectPageForUser(@Param("userId") Long userId, @Param("type") String type, @Param("unreadOnly") Boolean unreadOnly, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_notification n LEFT JOIN sys_user u ON u.id=#{userId} WHERE n.id=#{notificationId} AND (u.role IS NULL OR u.role='管理者' OR n.menu_key IS NULL OR EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id WHERE rm.role=u.role AND m.menu_key=n.menu_key))")
    long countVisible(@Param("notificationId") Long notificationId, @Param("userId") Long userId);
}

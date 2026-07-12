package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
    @Select("SELECT COUNT(*) FROM t_notification n WHERE NOT EXISTS " +
            "(SELECT 1 FROM t_notification_read r WHERE r.notification_id = n.id AND r.user_id = #{userId})")
    long countUnread(@Param("userId") Long userId);

    @Select("<script>" +
            "SELECT COUNT(*) FROM t_notification n " +
            "LEFT JOIN t_notification_read r ON r.notification_id = n.id AND r.user_id = #{userId} " +
            "<where> " +
            "<if test='type != null and type != \"\"'> AND n.type = #{type} </if> " +
            "<if test='unreadOnly != null and unreadOnly == true'> AND r.id IS NULL </if> " +
            "</where> " +
            "</script>")
    long countPageForUser(@Param("userId") Long userId, @Param("type") String type, @Param("unreadOnly") Boolean unreadOnly);

    @Select("<script>" +
            "SELECT n.*, (r.id IS NOT NULL) AS is_read FROM t_notification n " +
            "LEFT JOIN t_notification_read r ON r.notification_id = n.id AND r.user_id = #{userId} " +
            "<where> " +
            "<if test='type != null and type != \"\"'> AND n.type = #{type} </if> " +
            "<if test='unreadOnly != null and unreadOnly == true'> AND r.id IS NULL </if> " +
            "</where> " +
            "ORDER BY is_read ASC, n.created_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<NotificationDto> selectPageForUser(@Param("userId") Long userId, @Param("type") String type, @Param("unreadOnly") Boolean unreadOnly, @Param("limit") int limit, @Param("offset") int offset);
}

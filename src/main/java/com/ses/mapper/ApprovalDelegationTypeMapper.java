package com.ses.mapper;

import com.ses.entity.ApprovalDelegationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 複合主キーのためBaseMapperのxxByIdを使わず、必要なSQLだけを定義する。 */
@Mapper
public interface ApprovalDelegationTypeMapper {

    @Insert("INSERT INTO t_approval_delegation_type (delegation_id, request_type) "
            + "VALUES (#{delegationId}, #{requestType})")
    int insert(ApprovalDelegationType row);

    @Delete("DELETE FROM t_approval_delegation_type WHERE delegation_id = #{delegationId}")
    int deleteByDelegationId(@Param("delegationId") Long delegationId);

    @Select("SELECT request_type FROM t_approval_delegation_type WHERE delegation_id = #{delegationId} ORDER BY request_type")
    List<String> selectRequestTypes(@Param("delegationId") Long delegationId);
}

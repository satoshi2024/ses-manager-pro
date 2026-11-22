package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureSignOff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 機能の開放の署名（V203）。状態の遷移は version の CAS で、列を名指しした {@code UpdateWrapper} で行う（氏名・元の file 名・
 * 保存名を NULL にする消去は {@code set(col, null)}）。lock の順は世代・開放状態・申請の後、id の昇順（design §5.1 の (4)）。
 */
@Mapper
public interface FeatureSignOffMapper extends BaseMapper<FeatureSignOff> {

    /** tenant の確認済みの署名（snapshot の読込み。種別ごとに高々1件）。 */
    @Select("SELECT * FROM t_feature_sign_off WHERE tenant_id = #{tenantId} AND status = 'CONFIRMED' ORDER BY kind_code")
    List<FeatureSignOff> selectConfirmedByTenant(@Param("tenantId") String tenantId);

    /** 署名を X lock して読む。 */
    @Select("SELECT * FROM t_feature_sign_off WHERE id = #{id} FOR UPDATE")
    FeatureSignOff selectByIdForUpdate(@Param("id") Long id);

    /** 種別の確認済みの署名を X lock して読む（無ければ null）。 */
    @Select("SELECT * FROM t_feature_sign_off WHERE tenant_id = #{tenantId} AND kind_code = #{kindCode} "
            + "AND status = 'CONFIRMED' FOR UPDATE")
    FeatureSignOff selectConfirmedByKindForUpdate(@Param("tenantId") String tenantId, @Param("kindCode") String kindCode);

    /** 失効日を迎えた確認済みの署名（{@code expires_on <= today}）。 */
    @Select("SELECT * FROM t_feature_sign_off WHERE tenant_id = #{tenantId} AND status = 'CONFIRMED' "
            + "AND expires_on <= #{today} ORDER BY id")
    List<FeatureSignOff> selectExpiredConfirmed(@Param("tenantId") String tenantId, @Param("today") LocalDate today);

    /**
     * 汎用の file 経路の遮断に使う、保存名の照合（全 tenant。大文字小文字・末尾の {@code .}／空白の違いも正規化した名前で照合する。
     * {@code FileScopeValidationService}）。
     */
    @Select("SELECT COUNT(*) FROM t_feature_sign_off WHERE stored_name IN (#{storedName}, #{normalizedStoredName})")
    long countByStoredNames(@Param("storedName") String storedName,
                            @Param("normalizedStoredName") String normalizedStoredName);

    /** 消去していない署名の保存名（全 tenant。孤児清掃から守る。{@code FeatureSignOffFileReferenceProvider}）。 */
    @Select("SELECT stored_name FROM t_feature_sign_off WHERE status <> 'PURGED' AND stored_name IS NOT NULL")
    List<String> selectLiveStoredNames();

    /** 消去の期日（{@code purge_after}）を迎えた終わった署名（tenant の中で古い順に {@code limit} 件。review rb-fe-a1 P3-10）。 */
    @Select("SELECT * FROM t_feature_sign_off WHERE tenant_id = #{tenantId} "
            + "AND status IN ('SUPERSEDED', 'EXPIRED', 'REVOKED', 'REJECTED') "
            + "AND purge_after IS NOT NULL AND purge_after <= #{today} ORDER BY purge_after, id LIMIT #{limit}")
    List<FeatureSignOff> selectPurgeDue(@Param("tenantId") String tenantId, @Param("today") LocalDate today,
                                        @Param("limit") int limit);
}

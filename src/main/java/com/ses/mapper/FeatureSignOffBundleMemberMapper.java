package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.FeatureSignOffBundleMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 署名の組の member（V203）。{@code t_feature_sign_off_bundle_member} は追記専用。更新・削除・insertOrUpdate は
 * {@link AppendOnlyMapper} が SQL の発行前に拒否し、{@code AppendOnlySqlInjector} は更新・削除の文を注入しない。DB でも
 * BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。取り消した member を替えるには新しい組を登録する。
 */
@Mapper
public interface FeatureSignOffBundleMemberMapper extends AppendOnlyMapper<FeatureSignOffBundleMember> {

    @Override
    default String appendOnlyTable() {
        return "t_feature_sign_off_bundle_member";
    }

    /** 組の member（種別の昇順）。 */
    @Select("SELECT * FROM t_feature_sign_off_bundle_member WHERE bundle_id = #{bundleId} ORDER BY kind_code")
    List<FeatureSignOffBundleMember> selectByBundle(@Param("bundleId") Long bundleId);

    /**
     * tenant の確認済みの組の全ての member と、その署名の行の参照・日付・値・状態（snapshot の読込み。member の行の状態に依らず全てを
     * 返す。design §2.6.1 の P2-N1）。
     */
    @Select("SELECT m.bundle_id, m.kind_code, m.sign_off_id, s.reference, s.signed_on, s.expires_on, s.structured_values, "
            + "s.status FROM t_feature_sign_off_bundle_member m "
            + "JOIN t_feature_sign_off_bundle b ON b.id = m.bundle_id AND b.tenant_id = m.tenant_id "
            + "JOIN t_feature_sign_off s ON s.id = m.sign_off_id AND s.tenant_id = m.tenant_id "
            + "WHERE m.tenant_id = #{tenantId} AND b.status = 'CONFIRMED' ORDER BY m.bundle_id, m.kind_code")
    List<com.ses.featureenablement.state.BundleMemberRow> selectMembersOfConfirmedBundles(
            @Param("tenantId") String tenantId);

    /** 署名が属する組の member（引き継いだ署名は複数の組に属する）。 */
    @Select("SELECT * FROM t_feature_sign_off_bundle_member WHERE sign_off_id = #{signOffId} ORDER BY bundle_id")
    List<FeatureSignOffBundleMember> selectBySignOff(@Param("signOffId") Long signOffId);
}

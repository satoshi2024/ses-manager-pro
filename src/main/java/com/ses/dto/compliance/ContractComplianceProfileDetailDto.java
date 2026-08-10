package com.ses.dto.compliance;
import com.ses.dto.common.OptionDto;
import com.ses.entity.ContractComplianceProfile;
import lombok.Data;

import java.util.List;

/**
 * 契約compliance profile詳細レスポンス（T063 A1）。
 * profileはroleに応じてmask済み（管理者/HR=full、マネージャー=P1_MASK、営業=P2_LIMITED）。
 * findingsはcompliance menu権限（管理者/マネージャー）がある場合のみ含む（design §5.3）。
 */
@Data
public class ContractComplianceProfileDetailDto {

    private Long contractId;
    private String contractType;
    private String contractNo;
    private String engineerName;
    private String customerName;
    private String projectName;

    private ContractComplianceProfile profile;
    private boolean profileExists;
    private List<com.ses.entity.ComplianceFinding> findings;

    /** 就業事業所選択肢（契約の顧客に属する有効なもの） */
    private List<OptionDto> workplaces;

    /** FULL / MASK / LIMITED。masked roleがsensitive fieldを保存できないことを画面へ渡す。 */
    private String maskLevel;
    private boolean canEdit;
}

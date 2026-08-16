package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 需給計画シナリオ（仮配置・本データを変更しない）。
 *
 * <p>{@code base_date} 時点の実データをcopyし、scenario操作は
 * {@code t_staffing_scenario} / {@code t_staffing_scenario_allocation} のみを更新する（R3.3）。
 * 可視性: owner本人 ＋ {@code shared_flag=1} なら同一組織scope内（design §5.3）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_staffing_scenario")
public class StaffingScenario extends BaseEntity {

    /** 作成者ID（閲覧scopeの起点） */
    @NotNull(message = "作成者は必須です")
    private Long ownerUserId;

    /** scenario名 */
    @NotBlank(message = "シナリオ名は必須です")
    private String name;

    /** 実データcopy基準日（snapshot） */
    @NotNull(message = "基準日は必須です")
    private LocalDate baseDate;

    /** 共有フラグ（1=同一組織scope内で共有） */
    private Integer sharedFlag;

    /** 仮定メモのJSON */
    private String assumptionsJson;
}

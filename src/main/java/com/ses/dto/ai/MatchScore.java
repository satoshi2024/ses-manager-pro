package com.ses.dto.ai;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * マッチングスコア結果
 */
@Data
public class MatchScore {
    private int totalScore;
    private double mustCoverage;
    private List<Long> matchedMustIds = new ArrayList<>();
    private List<Long> missingMustIds = new ArrayList<>();
    private List<Long> matchedNiceIds = new ArrayList<>();
    private List<Long> missingNiceIds = new ArrayList<>();
    private boolean isExcluded;
    private int priceScore;
    private int dateScore;
}

package com.ses.service.ai.impl;

import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiMatchingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AIマッチングサービス実装
 */
@Service
@ConditionalOnExpression("!'rule'.equals('${ai.provider:mock}')")
public class AiMatchingServiceImpl implements AiMatchingService {

    @Override
    public List<MatchResultDto> findMatchingProjects(Long engineerId) {
        // モックデータを作成
        List<MatchResultDto> results = new ArrayList<>();
        
        MatchResultDto result1 = new MatchResultDto();
        result1.setProjectId(101L);
        result1.setProjectName("大手金融機関向けシステム刷新");
        result1.setScore(95);
        result1.setReason("JavaとSpring Bootの経験が豊富であり、要件に完全に合致しています。");
        result1.setSellingPoints("金融系の業務知識があり、即戦力として期待できます。");
        results.add(result1);
        
        MatchResultDto result2 = new MatchResultDto();
        result2.setProjectId(102L);
        result2.setProjectName("ECサイトバックエンド開発");
        result2.setScore(85);
        result2.setReason("バックエンド開発の経験が活かせます。");
        result2.setSellingPoints("高負荷環境でのパフォーマンスチューニング経験がアピールできます。");
        results.add(result2);
        
        MatchResultDto result3 = new MatchResultDto();
        result3.setProjectId(103L);
        result3.setProjectName("社内システム保守・運用");
        result3.setScore(70);
        result3.setReason("保守運用の経験がありますが、スキルセットの一部がオーバーキルです。");
        result3.setSellingPoints("安定志向かつ丁寧なドキュメント作成能力があります。");
        results.add(result3);
        
        return results;
    }

    @Override
    public List<MatchResultDto> findMatchingEngineers(Long projectId) {
        // モックデータを作成
        List<MatchResultDto> results = new ArrayList<>();
        
        MatchResultDto result1 = new MatchResultDto();
        result1.setEngineerId(201L);
        result1.setEngineerName("山田 太郎 (Mock)");
        result1.setScore(92);
        result1.setReason("JavaとSpring Bootの経験が豊富で案件の必須要件を満たしています。");
        result1.setSellingPoints("金融系の業務知識が豊富です。");
        result1.setProposedPrice(75);
        results.add(result1);
        
        return results;
    }
}

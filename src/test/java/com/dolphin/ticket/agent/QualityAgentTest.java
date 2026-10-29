package com.dolphin.ticket.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QualityAgent} 契约行解析单测:VERDICT:PASS / VERDICT:REJECT /
 * 契约缺失 / 多行 / 未知结论 / 空输出全分支覆盖。
 */
class QualityAgentTest {

    @Test
    void passVerdictParsedAndOpinionStripped() {
        QualityAgent.Verdict v = QualityAgent.parse("理由:合规且完整。\nVERDICT:PASS");
        assertTrue(v.passed());
        assertTrue(v.contractValid());
        assertEquals("理由:合规且完整。", v.opinion());
        assertFalse(v.opinion().contains("VERDICT"));
    }

    @Test
    void rejectVerdictParsed() {
        QualityAgent.Verdict v = QualityAgent.parse("草稿承诺了未经审批的重启。\nVERDICT:REJECT");
        assertFalse(v.passed());
        assertTrue(v.contractValid());
        assertTrue(v.opinion().contains("未经审批"));
    }

    @Test
    void missingVerdictLineMeansInvalidContract() {
        QualityAgent.Verdict v = QualityAgent.parse("第一行通过,理由若干,但没有结论行。");
        assertFalse(v.passed());
        assertFalse(v.contractValid());
    }

    @Test
    void multipleVerdictLinesMeansInvalidContract() {
        QualityAgent.Verdict v = QualityAgent.parse("意见。\nVERDICT:PASS\nVERDICT:REJECT");
        assertFalse(v.passed());
        assertFalse(v.contractValid());
    }

    @Test
    void unknownVerdictValueMeansInvalidContract() {
        QualityAgent.Verdict v = QualityAgent.parse("意见。\nVERDICT:OK");
        assertFalse(v.passed());
        assertFalse(v.contractValid());
    }

    @Test
    void blankOutputMeansInvalidContract() {
        QualityAgent.Verdict v = QualityAgent.parse("  ");
        assertFalse(v.passed());
        assertFalse(v.contractValid());
        QualityAgent.Verdict nullV = QualityAgent.parse(null);
        assertFalse(nullV.contractValid());
    }

    @Test
    void lowercaseVerdictTolerated() {
        QualityAgent.Verdict v = QualityAgent.parse("意见。\nverdict:pass");
        assertTrue(v.passed());
        assertTrue(v.contractValid());
    }
}

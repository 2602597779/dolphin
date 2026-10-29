package com.dolphin.ticket.agent;

import com.dolphin.ticket.action.ActionProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiagnosisAgent 契约行解析单测:覆盖 PROPOSAL 行的合法/非法全部分支。
 * 解析失败(缺失/多行/非法 JSON/字段为空/低置信度)必须判定 proposalInvalid=true,
 * 由图路由收敛到转人工,绝不猜测执行。
 */
class DiagnosisAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validProposalParsedAndReportStripped() {
        String content = """
                【故障摘要】实例夯死。
                【处置建议】执行重启。
                PROPOSAL:{"action":"restartInstance","target":"i-abc123","params":{},"confidence":0.9}""";
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome(content, mapper);
        assertFalse(out.proposalInvalid());
        assertNotNull(out.proposal());
        assertEquals("restartInstance", out.proposal().action());
        assertEquals("i-abc123", out.proposal().target());
        assertEquals(0.9, out.proposal().confidence(), 1e-9);
        // 报告正文不应残留契约行
        assertFalse(out.report().contains("PROPOSAL:"));
        assertTrue(out.report().contains("【故障摘要】"));
    }

    @Test
    void proposalWithParamsRoundTripsToStateString() {
        String content = """
                【处置建议】放行 443。
                PROPOSAL:{"action":"authorizeSecurityGroup","target":"sg-demo","params":{"port":"443"},"confidence":0.95}""";
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome(content, mapper);
        assertFalse(out.proposalInvalid());
        assertEquals("443", out.proposal().params().get("port"));
        // 与状态通道字符串格式互逆,供下游 ActionProposal.parse 消费
        assertEquals("authorizeSecurityGroup:sg-demo:port=443", out.proposal().toStateString());
        ActionProposal reparsed = ActionProposal.parse(out.proposal().toStateString());
        assertEquals("authorizeSecurityGroup", reparsed.action());
        assertEquals("sg-demo", reparsed.target());
    }

    @Test
    void proposalNoneMeansNoAction() {
        String content = "【故障摘要】信息不足。\nPROPOSAL:NONE";
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome(content, mapper);
        assertFalse(out.proposalInvalid());
        assertNull(out.proposal());
    }

    @Test
    void missingProposalLineIsInvalid() {
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome("【故障摘要】没有契约行。", mapper);
        assertTrue(out.proposalInvalid());
        assertNull(out.proposal());
    }

    @Test
    void blankContentIsInvalid() {
        assertTrue(DiagnosisAgent.toOutcome("", mapper).proposalInvalid());
        assertTrue(DiagnosisAgent.toOutcome(null, mapper).proposalInvalid());
    }

    @Test
    void multipleProposalLinesIsInvalid() {
        String content = """
                报告
                PROPOSAL:NONE
                PROPOSAL:NONE""";
        assertTrue(DiagnosisAgent.toOutcome(content, mapper).proposalInvalid());
    }

    @Test
    void malformedJsonIsInvalid() {
        String content = "报告\nPROPOSAL:{\"action\":\"restartInstance\",broken}";
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome(content, mapper);
        assertTrue(out.proposalInvalid());
    }

    @Test
    void missingOrEmptyRequiredFieldsIsInvalid() {
        // 缺 confidence
        assertTrue(DiagnosisAgent.toOutcome(
                "报告\nPROPOSAL:{\"action\":\"restartInstance\",\"target\":\"i-1\"}", mapper).proposalInvalid());
        // action 为空
        assertTrue(DiagnosisAgent.toOutcome(
                "报告\nPROPOSAL:{\"action\":\"\",\"target\":\"i-1\",\"confidence\":0.9}", mapper).proposalInvalid());
        // target 为空
        assertTrue(DiagnosisAgent.toOutcome(
                "报告\nPROPOSAL:{\"action\":\"restartInstance\",\"target\":\"\",\"confidence\":0.9}", mapper).proposalInvalid());
    }

    @Test
    void lowConfidenceIsInvalid() {
        String content = """
                报告
                PROPOSAL:{"action":"refundOrder","target":"o-9","params":{},"confidence":0.3}""";
        DiagnosisAgent.DiagnosisOutcome out = DiagnosisAgent.toOutcome(content, mapper);
        assertTrue(out.proposalInvalid());
    }

    @Test
    void confidenceAtThresholdIsValid() {
        String content = """
                报告
                PROPOSAL:{"action":"restartInstance","target":"i-1","params":{},"confidence":0.6}""";
        assertFalse(DiagnosisAgent.toOutcome(content, mapper).proposalInvalid());
    }
}

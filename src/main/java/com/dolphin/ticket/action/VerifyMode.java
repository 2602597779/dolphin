package com.dolphin.ticket.action;

/**
 * 写操作执行后的结果验证范式。
 *
 * <p>云上所有写操作都是"控制面受理 ≠ 实际生效",必须按动作的异步特性选择验证方式:
 * <ul>
 *   <li>{@link #POLL_STATUS} 轮询:查资源状态直到目标值(重启→Running、变配→新规格),分钟级;</li>
 *   <li>{@link #PROBE} 探测:从外部验证效果(开端口→curl 通、绑 EIP→ping 通),秒级;</li>
 *   <li>{@link #RECONCILE} 对账:查另一套系统的记录(退款→订单状态+流水号),只等受理不等到账;</li>
 *   <li>{@link #NONE} 无需验证(仅限无副作用动作)。</li>
 * </ul>
 */
public enum VerifyMode {
    POLL_STATUS,
    PROBE,
    RECONCILE,
    NONE
}

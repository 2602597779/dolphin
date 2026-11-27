import com.dolphin.im.DingTalkCrypto;

/**
 * 生成一条"合法签名"的钉钉回调报文(演示/联调工具,不属于生产代码)。
 *
 * <p>为什么要有这东西:{@code POST /api/im/callback/dingtalk} 要求
 * 验签三要素(timestamp/nonce/msg_signature)+ AES 加密请求体,手工 curl 造不出来。
 * 这里<b>直接复用生产实现</b>{@link DingTalkCrypto} 出报文,而不是在 shell 里另写一套
 * 加解密——两套实现必然漂移,演示脚本也就失去了验证意义。
 *
 * <p>纯 JDK 类(生产实现只依赖 javax.crypto/java.util.Base64),因此可以走
 * java 单文件源码模式,只需 {@code target/classes} 一个 classpath:
 *
 * <pre>
 *   java -cp target/classes demo/GenDingTalkCallback.java \
 *        &lt;token&gt; &lt;aesKey&gt; &lt;receiveId&gt; &lt;ticketId&gt; &lt;eventId&gt; &lt;approve|reject&gt; [approver]
 * </pre>
 *
 * <p>输出两行:第一行是 query string(钉钉把验签三要素放 query 上),
 * 第二行是请求体 {@code {"encrypt":"..."}}(⚠️ 钉钉只接受 JSON 包体,不接受裸密文)。
 */
public class GenDingTalkCallback {

    public static void main(String[] args) {
        if (args.length < 6) {
            System.err.println("用法: java -cp target/classes demo/GenDingTalkCallback.java "
                    + "<token> <aesKey> <receiveId> <ticketId> <eventId> <approve|reject> [approver]");
            System.exit(2);
        }
        String token = args[0];
        String aesKey = args[1];
        String receiveId = args[2];
        String ticketId = args[3];
        String eventId = args[4];
        String action = args[5];
        String approver = args.length > 6 ? args[6] : "approver-demo";

        // 事件明文:与 DingTalkCallbackAdapter.mapToCommand 的取值约定对齐——
        // EventId=幂等键, outTrackId 前缀带工单号, content.actionId=卡片动作, userId=审批人
        String cleartext = "{\"EventType\":\"card_action\",\"EventId\":\"" + eventId + "\","
                + "\"outTrackId\":\"dolphin-approval:" + ticketId + "\","
                + "\"userId\":\"" + approver + "\","
                + "\"content\":{\"actionId\":\"" + action + "\",\"remark\":\"演示:IM 卡片审批\"}}";

        DingTalkCrypto crypto = new DingTalkCrypto(token, aesKey, receiveId);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-" + Long.toHexString(System.nanoTime());
        String encrypt = crypto.encrypt(cleartext);

        System.out.println("timestamp=" + timestamp + "&nonce=" + nonce
                + "&msg_signature=" + crypto.sign(timestamp, nonce, encrypt));
        System.out.println("{\"encrypt\":\"" + encrypt + "\"}");
    }
}

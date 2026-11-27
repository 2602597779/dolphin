package com.dolphin.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IM 回调接入配置。
 *
 * <p>约定:凭证留空 = 该平台回调入口自动关闭(验签必然失败),不额外引入 enabled 开关。
 * 这样"没配"和"配错"走同一条失败路径,不会出现"以为关了其实开着"的静默状态。
 *
 * <p>凭证只从环境变量注入(见 application.yml 的占位),不进代码库。
 */
@ConfigurationProperties(prefix = "dolphin.im.callback")
public class ImCallbackProperties {

    private final DingTalk dingtalk = new DingTalk();

    public DingTalk getDingtalk() {
        return dingtalk;
    }

    /** 钉钉参考实现的凭证(token / EncodingAESKey 由钉钉后台生成) */
    public static class DingTalk {

        /** 回调验签 token */
        private String token = "";

        /** 43 字符 EncodingAESKey */
        private String aesKey = "";

        /** 明文尾部回带的应用标识(钉钉为 appKey/suiteKey),留空则不校验 */
        private String receiveId = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token;
        }

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey == null ? "" : aesKey;
        }

        public String getReceiveId() {
            return receiveId;
        }

        public void setReceiveId(String receiveId) {
            this.receiveId = receiveId == null ? "" : receiveId;
        }

        /** token 与 aesKey 齐备才算配置完成 */
        public boolean configured() {
            return !token.isBlank() && !aesKey.isBlank();
        }
    }
}

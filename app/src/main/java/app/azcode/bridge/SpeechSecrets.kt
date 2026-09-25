package app.azcode.bridge

/**
 * 讯飞开放平台凭据。
 *
 * 这里内置的是项目所有者提供的「语音听写」应用凭据，随 APK 分发给所有用户，开箱即用。
 * CI 若配置了同名 Secrets（XUNFEI_APP_ID / XUNFEI_API_KEY / XUNFEI_API_SECRET），会在构建时覆盖此处，
 * 便于日后轮换密钥而无需改动仓库。
 */
internal object SpeechSecrets {
    const val XUNFEI_APP_ID = "643e35e8"
    const val XUNFEI_API_KEY = "5737b63a6b9e80a789f93d3ecdde36d7"
    const val XUNFEI_API_SECRET = "YWEwYTc5ODYxN2FiYTZhYmQwYzllMjhm"
}
package app.azcode.bridge

/**
 * 讯飞开放平台凭据占位文件。
 *
 * 仓库内保持空值，避免密钥明文进入公开仓库；CI 构建时由 GitHub Actions Secrets
 * （XUNFEI_APP_ID / XUNFEI_API_KEY / XUNFEI_API_SECRET）覆盖写入真实值后打包。
 * 本地直接构建时讯飞引擎不可用，系统内置引擎不受影响。
 */
internal object SpeechSecrets {
    const val XUNFEI_APP_ID = ""
    const val XUNFEI_API_KEY = ""
    const val XUNFEI_API_SECRET = ""
}
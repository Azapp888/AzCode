package app.azcode.bridge.llm

import app.azcode.bridge.ProviderAccount
import app.azcode.bridge.ProviderProtocol
import app.azcode.bridge.llm.adapters.AnthropicAdapter
import app.azcode.bridge.llm.adapters.DeepSeekAdapter
import app.azcode.bridge.llm.adapters.DoubaoAdapter
import app.azcode.bridge.llm.adapters.GeminiAdapter
import app.azcode.bridge.llm.adapters.OpenAiAdapter
import app.azcode.bridge.llm.core.BaseAdapter

/** 厂商标识。 */
enum class LLMVendor(val key: String, val label: String) {
    DEEPSEEK("deepseek", "DeepSeek"),
    DOUBAO("doubao", "火山方舟（豆包）"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic Claude"),
    GEMINI("gemini", "Google Gemini"),
    GENERIC("generic", "OpenAI 兼容"),
}

/**
 * 适配器注册表。新增厂商支持只需：
 * 1. 在 llm/adapters 下新建适配器（继承 [BaseAdapter] 或 OpenAiCompatAdapter）；
 * 2. 在 [ADAPTERS] 中注册（必要时在 [detectVendor] 中补充识别规则）。
 * 基类与业务代码均无需改动。
 */
object LLMRegistry {

    private const val OPENAI_COMPAT_ID = "openai"

    private val ADAPTERS: List<BaseAdapter> = listOf(
        DeepSeekAdapter(),
        DoubaoAdapter(),
        OpenAiAdapter(),
        AnthropicAdapter(),
        GeminiAdapter(),
    )

    private val byId: Map<String, BaseAdapter> = ADAPTERS.associateBy { it.id }

    val all: List<BaseAdapter> get() = ADAPTERS

    /** 依据账号地址与模型名识别厂商。 */
    fun detectVendor(account: ProviderAccount, model: String): LLMVendor {
        val base = account.baseUrl.lowercase()
        val m = model.lowercase()
        return when (account.protocol) {
            ProviderProtocol.ANTHROPIC -> LLMVendor.ANTHROPIC
            ProviderProtocol.GEMINI -> LLMVendor.GEMINI
            ProviderProtocol.OPENAI -> when {
                base.contains("deepseek") || m.startsWith("deepseek") -> LLMVendor.DEEPSEEK
                base.contains("volces") || base.contains("ark.") ||
                    m.startsWith("doubao") || m.startsWith("seed-") -> LLMVendor.DOUBAO
                base.contains("openai.com") || m.startsWith("gpt-") || m.startsWith("chatgpt") ||
                    m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") -> LLMVendor.OPENAI
                else -> LLMVendor.GENERIC
            }
        }
    }

    /** 按厂商取适配器；未识别的厂商回退到通用 OpenAI 兼容适配器。 */
    fun adapterFor(account: ProviderAccount, model: String): BaseAdapter =
        byId[detectVendor(account, model).key] ?: byId.getValue(OPENAI_COMPAT_ID)

    /** 按协议取适配器（用于与具体模型无关的接口，如模型列表）。 */
    fun adapterForProtocol(protocol: ProviderProtocol): BaseAdapter = when (protocol) {
        ProviderProtocol.OPENAI -> byId.getValue(OPENAI_COMPAT_ID)
        ProviderProtocol.ANTHROPIC -> byId.getValue("anthropic")
        ProviderProtocol.GEMINI -> byId.getValue("gemini")
    }
}

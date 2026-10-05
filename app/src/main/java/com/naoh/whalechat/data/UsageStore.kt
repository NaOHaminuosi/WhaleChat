package com.naoh.whalechat.data

import android.content.Context
import com.naoh.whalechat.net.DEEPSEEK_BASE_URL
import com.naoh.whalechat.net.DeepSeekException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 账户余额这一档的本地缓存。**独立 prefs 文件 `whalechat_usage`，不塞进 `Settings`。**
 *
 * 为什么必须独立：余额是「每点一次刷新就写一次」的频繁更新数据，
 * 而 [SettingsStore.write] 每次会把全部字段重写一遍；混在一起会让「用户配置」和
 * 「本地统计缓存」这两类数据纠缠不清，每次刷余额都重写整个 Settings。
 *
 * ## 安全边界（两条硬的）
 *
 *  * **API Key 绝不进这里。** Key 只活在 [SettingsStore]，这里只缓存余额的**结果**
 *    （一个数字、一个币种、一个时间）。查余额时 Key 由调用方临时传进来，用完即丢，
 *    不落盘、不存字段。
 *  * **绝不把 token 折成钱。** 定价会变，本机硬编码一个价格迟早骗人。这一层只显示
 *    服务端给的真实余额；「这个对话烧了多少钱」这种折算不做（那是用户自己在心里算的）。
 */
data class UsageSettings(
    /** 缓存的余额，空串 = 没查过。 */
    val lastBalance: String = "",
    /** CNY / USD。 */
    val balanceCurrency: String = "",
    /** 用来显示「N 分钟前更新」。 */
    val balanceFetchedAt: Long = 0L,
)

class UsageStore(context: Context) {

    private val prefs = context.getSharedPreferences("whalechat_usage", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<UsageSettings> = _state.asStateFlow()

    val value: UsageSettings get() = _state.value

    /** 有没有可显示的缓存余额。 */
    val hasBalance: Boolean get() = _state.value.lastBalance.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 查一次余额并把结果缓存。返回可显示的一行字；失败抛 [DeepSeekException]。
     *
     * @param apiKey 由调用方从 [SettingsStore] 临时拿进来，**用完即丢、绝不落盘**。
     */
    suspend fun refresh(apiKey: String): String = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw DeepSeekException("先填 DeepSeek API Key")

        val request = Request.Builder()
            .url("$DEEPSEEK_BASE_URL/user/balance")
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()

        val raw = try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DeepSeekException(httpHint(response.code), readErrorDetail(body))
                }
                body
            }
        } catch (e: DeepSeekException) {
            throw e
        } catch (e: Exception) {
            throw DeepSeekException(networkHint(e), e.message)
        }

        // 账户本身不可用（Key 无效 / 欠费停用）时，服务端会回 200 但把 is_available 置 false。
        // 这跟「余额是 0」是两回事 —— 显示成 0 会让用户以为钱花完了，
        // 实际是他该去处理 Key 或额度。
        val unavailable = runCatching {
            JSONObject(raw).optBoolean("is_available", true).not()
        }.getOrDefault(false)
        if (unavailable) throw DeepSeekException("账户不可用，检查 Key 或额度")

        // 解析余额：`balance_infos` 是一组币种的数组，**要挑对那一档**。
        //
        // 曾经的 bug：只取「total_balance 非空」的第一条。但 `"0.00"` 也算非空 ——
        // 服务端把 USD 排在前面时，就挑中了「USD 0.00」，用户真正有余额的 CNY
        // 那一档被跳过。实测症状就是设置页永远显示 `USD 0.00`。
        //
        // 改成三级挑，优先级是「真的有余额 → CNY → 第一条非空」：
        // 第一级保证有余额时一定能显示出来，第二级保证没余额时至少给人民币那一档
        // （国内用户看 USD 0.00 没有信息量），第三级只是兜底。
        val balance = runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("balance_infos") ?: return@runCatching null
            val infos = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val picked = infos.firstOrNull {
                (it.optString("total_balance").toDoubleOrNull() ?: 0.0) > 0.0
            }
                ?: infos.firstOrNull { it.optString("currency").equals("CNY", ignoreCase = true) }
                ?: infos.firstOrNull { it.optString("total_balance").isNotBlank() }
            picked?.let {
                it.optString("total_balance") to it.optString("currency")
            }
        }.getOrNull()

        if (balance == null) {
            throw DeepSeekException("没有读到余额")
        }

        val (amount, currency) = balance
        _state.value = UsageSettings(
            lastBalance = amount,
            balanceCurrency = currency,
            balanceFetchedAt = System.currentTimeMillis(),
        )
        write()

        // 可显示的一行：币种符号 + 数值。DeepSeek 给的是 CNY 这种三字母码，
        // 显示时换成 ¥，其余币种照原样带码。
        val symbol = if (currency.equals("CNY", ignoreCase = true)) "¥" else "$currency "
        "$symbol$amount"
    }

    private fun httpHint(code: Int): String = when (code) {
        401 -> "API Key 无效，请到设置里重新填写"
        402 -> "账户余额不足"
        429 -> "请求过于频繁，请稍后再试"
        else -> "查询失败（HTTP $code）"
    }

    private fun networkHint(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "网络不可用，检查手表连接"
        is java.net.SocketTimeoutException -> "连接超时，请重试"
        else -> "网络错误"
    }

    private fun readErrorDetail(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()

    private fun write() {
        prefs.edit()
            .putString(KEY_BALANCE, _state.value.lastBalance)
            .putString(KEY_CURRENCY, _state.value.balanceCurrency)
            .putLong(KEY_FETCHED_AT, _state.value.balanceFetchedAt)
            .apply()
    }

    private fun read() = UsageSettings(
        lastBalance = prefs.getString(KEY_BALANCE, "").orEmpty(),
        balanceCurrency = prefs.getString(KEY_CURRENCY, "").orEmpty(),
        balanceFetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L),
    )

    private companion object {
        const val KEY_BALANCE = "balance"
        const val KEY_CURRENCY = "currency"
        const val KEY_FETCHED_AT = "fetched_at"
    }
}

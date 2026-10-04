package com.naoh.whalechat.net

/**
 * 识别链路上给用户看的一个失败。
 *
 * [hint] 是**直接显示在手表上的那句话**，所以必须是人话（「连不上讯飞（域名解析失败）」），
 * 不能是异常类名或 HTTP 状态码。之所以单独拎一个类型出来，而不是让各处直接
 * `IllegalStateException(...)`：界面要靠它把「已知的、已经翻译好的失败」和
 * 「没预料到的崩溃」分开 —— 前者原样呈现，后者才需要兜一句「未知错误」。
 *
 * [detail] 放原始信息（响应体、底层异常 message），只在 debug 包里记日志用。
 */
class SpeechException(val hint: String, val detail: String? = null) :
    Exception(detail ?: hint)

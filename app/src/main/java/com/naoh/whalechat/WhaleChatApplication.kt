package com.naoh.whalechat

import android.app.Application
import com.naoh.whalechat.data.ChatEngine

class WhaleChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 会话引擎挂在 Application 上：切页面、退到表盘，正在生成的回答都不会断
        ChatEngine.init(this)
    }
}

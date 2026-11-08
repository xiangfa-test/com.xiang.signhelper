package com.xiang.signhelper

import android.app.Application
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.util.LogX

/**
 * 模块自身的 Application（只在模块进程里跑）。
 *
 * 职责只有一个：尽早把 [Config] 初始化 —— 界面第一次读配置就应该是准的，
 * 而且远程 preferences 的服务绑定是异步的，越早注册越好。
 *
 * 注意：被注入进程（微信 / QQ 等）**不会**创建这个类，那边的一切从
 * [com.xiang.signhelper.hook.MainHook] 走。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            Config.init(this)
        } catch (t: Throwable) {
            // Application 里绝不能让异常冒出去 —— 那会直接崩在启动页。
            LogX.e("App.onCreate failed", t)
        }
    }
}

package com.yudhis7.gelombang

import android.app.Application
import com.yudhis7.gelombang.data.AppGraph

class GelombangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

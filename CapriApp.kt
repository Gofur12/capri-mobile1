package app.capri.mobile

import android.app.Application
import android.os.Build

class CapriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.add("APP", "build ${AppLog.BUILD} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

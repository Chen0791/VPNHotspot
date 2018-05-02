package be.mygod.vpnhotspot

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.preference.PreferenceManager
import android.support.annotation.StringRes
import android.widget.Toast

class App : Application() {
    companion object {
        const val ACTION_CLEAN_ROUTINGS = "be.mygod.vpnhotspot.CLEAN_ROUTINGS"
        const val KEY_OPERATING_CHANNEL = "service.repeater.oc"
        private const val KEY_DNS = "service.dns"

        @SuppressLint("StaticFieldLeak")
        lateinit var app: App
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        if (Build.VERSION.SDK_INT >= 24) {
            deviceContext = createDeviceProtectedStorageContext()
            deviceContext.moveSharedPreferencesFrom(this, PreferenceManager.getDefaultSharedPreferencesName(this))
        } else deviceContext = this
        // workaround for support lib PreferenceDataStore bug
        dns = dns
        ServiceNotification.updateNotificationChannels()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        ServiceNotification.updateNotificationChannels()
    }

    lateinit var deviceContext: Context
    val handler = Handler()
    val pref: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(deviceContext) }
    val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val wifi by lazy { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }

    val operatingChannel: Int get() {
        val result = pref.getString(KEY_OPERATING_CHANNEL, null)?.toIntOrNull() ?: 0
        return if (result in 1..165) result else 0
    }
    var dns: String
        get() = pref.getString(KEY_DNS, "8.8.8.8")
        set(value) = pref.edit().putString(KEY_DNS, value).apply()

    fun toast(@StringRes resId: Int) = handler.post { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
}

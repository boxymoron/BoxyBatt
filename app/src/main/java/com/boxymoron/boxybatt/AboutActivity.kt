package com.boxymoron.boxybatt

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app about page.
 * Shows disclaimer, how the app works, permissions, battery tips, and copyright.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Back button
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // Version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tv_version).text = "v${pInfo.versionName}"
        } catch (_: Exception) {}
    }
}

package com.supervideo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.supervideo.ui.App
import com.supervideo.ui.AppGraph

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as SuperVideoApplication
        val graph = AppGraph(
            platform = app.platform,
            platformUi = AndroidPlatformUi(this),
            settingsStore = app.settingsStore,
            jobRepository = app.repository,
            jobRunner = app.jobRunner,
        )
        setContent { App(graph) }
    }
}

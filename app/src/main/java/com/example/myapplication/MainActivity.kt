package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.mine.LauncherIconController
import com.example.myapplication.ui.mine.applyLauncherTaskAppearance
import com.example.myapplication.ui.mine.prepareLauncherTaskBitmap
import com.example.myapplication.ui.navigation.AppNavHost
import com.example.myapplication.ui.navigation.Routes
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        syncLauncherTaskAppearance()

        val sessionManager = (application as LearningApplication).container.sessionManager

        setContent {
            MyApplicationTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    startDestination = if (sessionManager.currentUserId.first() != null) {
                        Routes.HOME
                    } else {
                        Routes.LOGIN
                    }
                }

                val destination = startDestination
                if (destination == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    AppNavHost(startDestination = destination)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncLauncherTaskAppearance()
    }

    private fun syncLauncherTaskAppearance() {
        val appContext = applicationContext
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val selection = LauncherIconController(appContext).current()
                prepareLauncherTaskBitmap(appContext, selection)
            }
            applyLauncherTaskAppearance(this@MainActivity, bitmap)
        }
    }
}

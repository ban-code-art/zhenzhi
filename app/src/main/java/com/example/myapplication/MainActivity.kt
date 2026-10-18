package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.ui.navigation.AppNavHost
import com.example.myapplication.ui.navigation.Routes
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionManager = SessionManager(applicationContext)

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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    AppNavHost(startDestination = destination)
                }
            }
        }
    }
}

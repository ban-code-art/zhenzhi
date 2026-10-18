package com.example.myapplication.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.ui.auth.LoginScreen
import com.example.myapplication.ui.auth.RegisterScreen
import com.example.myapplication.ui.main.MainScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavHost(startDestination: String) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context.applicationContext) }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onGoRegister = { navController.navigate(Routes.REGISTER) }
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.HOME) {
            MainScreen(
                onLogout = {
                    scope.launch {
                        sessionManager.logout()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.local.VpnDatabase
import com.example.data.repository.VpnRepositoryImpl
import com.example.presentation.navigation.Routes
import com.example.presentation.screens.*
import com.example.presentation.viewmodel.VpnViewModel
import com.example.presentation.viewmodel.VpnViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val db by lazy { VpnDatabase.getDatabase(applicationContext) }
    private val repository by lazy { VpnRepositoryImpl(db.vpnDao()) }
    
    // Instantiate ViewModel with Repository Factory
    private val viewModel: VpnViewModel by viewModels {
        VpnViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(
                        navController = navController,
                        startDestination = Routes.SPLASH
                    ) {
                        composable(Routes.SPLASH) {
                            SplashScreen(navController = navController)
                        }
                        composable(Routes.HOME) {
                            HomeScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.SERVER_LIST) {
                            ServerListScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.ADD_EDIT_SERVER) {
                            AddEditServerScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.SUBSCRIPTION) {
                            SubscriptionScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.STATS) {
                            TrafficStatsScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.LOGS) {
                            LogsScreen(navController = navController, viewModel = viewModel)
                        }
                        composable(Routes.ABOUT) {
                            AboutScreen(navController = navController)
                        }
                    }
                }
            }
        }
    }
}

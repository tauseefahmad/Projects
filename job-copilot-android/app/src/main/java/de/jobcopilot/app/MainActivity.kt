package de.jobcopilot.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.jobcopilot.app.ui.MainViewModel
import de.jobcopilot.app.ui.screens.JobDetailScreen
import de.jobcopilot.app.ui.screens.LetterScreen
import de.jobcopilot.app.ui.screens.ProfileScreen
import de.jobcopilot.app.ui.screens.SearchScreen
import de.jobcopilot.app.ui.screens.SettingsScreen
import de.jobcopilot.app.ui.screens.TrackerScreen
import de.jobcopilot.app.ui.theme.JobCopilotTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { JobCopilotTheme { App() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("search", "Jobs", Icons.Default.Search),
    Tab("tracker", "Tracker", Icons.Default.ViewKanban),
    Tab("profile", "Profile", Icons.Default.Person),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toastShown() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "search", modifier = Modifier.padding(padding)) {
            composable("search") {
                SearchScreen(vm, onOpen = { nav.navigate("job/${Uri.encode(it)}") }, onEditProfile = { nav.navigate("profile") })
            }
            composable("job/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                JobDetailScreen(vm, id, onBack = { nav.popBackStack() }, onWriteLetter = { nav.navigate("letter/${Uri.encode(id)}") })
            }
            composable("letter/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                LetterScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("tracker") {
                TrackerScreen(vm, onOpenJob = { nav.navigate("job/${Uri.encode(it)}") })
            }
            composable("profile") { ProfileScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

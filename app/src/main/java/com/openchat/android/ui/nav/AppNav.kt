package com.openchat.android.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openchat.android.ui.chat.ChatScreen
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.files.EditorScreen
import com.openchat.android.ui.files.FilesScreen
import com.openchat.android.ui.settings.AboutScreen
import com.openchat.android.ui.settings.ModelEditScreen
import com.openchat.android.ui.settings.LocalModelsScreen
import com.openchat.android.ui.settings.ModelsScreen
import com.openchat.android.ui.settings.OllamaScreen
import com.openchat.android.ui.settings.OpenCodeScreen
import com.openchat.android.ui.settings.ProcessManagerScreen
import com.openchat.android.ui.settings.ProviderEditScreen
import com.openchat.android.ui.settings.ProvidersScreen
import com.openchat.android.ui.settings.SecurityScreen
import com.openchat.android.ui.settings.SettingsHomeScreen
import com.openchat.android.ui.settings.StorageScreen
import com.openchat.android.ui.settings.TerminalSettingsScreen
import com.openchat.android.ui.settings.UbuntuScreen
import com.openchat.android.ui.settings.WorkspaceScreen
import com.openchat.android.ui.terminalui.TerminalScreen

object Routes {
    const val CHAT = "chat"
    const val TERMINAL = "terminal"
    const val FILES = "files"
    const val SETTINGS = "settings"
    const val PROVIDERS = "settings/providers"
    const val PROVIDER_EDIT = "settings/provider/{providerId}"
    const val MODELS = "settings/models"
    const val MODEL_EDIT = "settings/model/{modelId}"
    const val OLLAMA = "settings/ollama"
    const val LOCAL = "settings/local"
    const val OPENCODE = "settings/opencode"
    const val UBUNTU = "settings/ubuntu"
    const val TERMINAL_SETTINGS = "settings/terminal"
    const val WORKSPACE = "settings/workspace"
    const val PROCESSES = "settings/processes"
    const val SECURITY = "settings/security"
    const val STORAGE = "settings/storage"
    const val ABOUT = "settings/about"
    const val EDITOR = "files/editor?path={path}&domain={domain}&isNew={isNew}"
}

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomItems = listOf(
    NavItem(Routes.CHAT, "Chat", AppIcons.Chat),
    NavItem(Routes.TERMINAL, "Terminal", AppIcons.Terminal),
    NavItem(Routes.FILES, "Files", AppIcons.Folder),
    NavItem(Routes.SETTINGS, "Settings", AppIcons.Gear),
)

@Composable
fun AppNav() {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomItems.map { it.route }) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    onOpenTerminal = {
                        nav.navigate(Routes.TERMINAL) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.TERMINAL) { TerminalScreen() }
            composable(Routes.FILES) { FilesScreen() }
            composable(Routes.SETTINGS) { SettingsHomeScreen(nav) }
            composable(Routes.PROVIDERS) { ProvidersScreen(nav) }
            composable(
                Routes.PROVIDER_EDIT,
                arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
            ) { entry ->
                ProviderEditScreen(nav, entry.arguments?.getString("providerId") ?: "new")
            }
            composable(Routes.MODELS) { ModelsScreen(nav) }
            composable(
                Routes.MODEL_EDIT,
                arguments = listOf(navArgument("modelId") { type = NavType.StringType }),
            ) { entry ->
                ModelEditScreen(nav, entry.arguments?.getString("modelId") ?: "new")
            }
            composable(Routes.OLLAMA) { OllamaScreen(nav) }
            composable(Routes.LOCAL) { LocalModelsScreen(nav) }
            composable(Routes.OPENCODE) { OpenCodeScreen(nav) }
            composable(Routes.UBUNTU) { UbuntuScreen(nav) }
            composable(Routes.TERMINAL_SETTINGS) { TerminalSettingsScreen(nav) }
            composable(Routes.WORKSPACE) { WorkspaceScreen(nav) }
            composable(Routes.PROCESSES) { ProcessManagerScreen(nav) }
            composable(Routes.SECURITY) { SecurityScreen(nav) }
            composable(Routes.STORAGE) { StorageScreen(nav) }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("domain") { type = NavType.StringType; defaultValue = "APP_DATA" },
                    navArgument("isNew") { type = NavType.StringType; defaultValue = "false" },
                ),
            ) { entry ->
                EditorScreen(
                    nav,
                    entry.arguments?.getString("path"),
                    entry.arguments?.getString("domain") ?: "APP_DATA",
                    entry.arguments?.getString("isNew") == "true",
                )
            }
        }
    }
}

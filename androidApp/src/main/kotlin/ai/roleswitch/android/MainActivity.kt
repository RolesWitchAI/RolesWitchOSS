//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ai.roleswitch.android.ui.assistant.AssistantScreen
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.safePopBackStack
import ai.roleswitch.android.ui.messages.MessagesScreen

import ai.roleswitch.android.ui.messages.ThreadsScreen
import ai.roleswitch.android.ui.messages.ThreadProfileScreen
import ai.roleswitch.android.ui.onboarding.OnboardingScreen
import ai.roleswitch.android.ui.settings.AcknowledgementsScreen
import ai.roleswitch.android.ui.settings.AdvancedScreen
import ai.roleswitch.android.ui.settings.LitertAdvancedScreen
import ai.roleswitch.android.ui.settings.LitertModelScreen
import ai.roleswitch.android.ui.settings.ModelSettingsScreen
import ai.roleswitch.android.ui.settings.SettingsScreen
import ai.roleswitch.android.ui.settings.ThreadAdvancedBinding
import ai.roleswitch.android.ui.settings.ThreadLitertAdvancedBinding
import ai.roleswitch.android.ui.settings.ToolsScreen
import ai.roleswitch.android.ui.theme.RolesWitchAndroidTheme
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.withLitertModelSelected
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Thread id to navigate to when the NavController is ready. Seeded from the
     * launch intent (cold start) and updated from [onNewIntent] (warm start).
     * Consumed by [MainApp].
     */
    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPhoneOrientationLock()
        enableEdgeToEdge()
        readDeepLinkFromIntent(intent)
        setContent {
            RolesWitchAndroidTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val app = application as RolesWitchApp
                    val hasCompletedOnboarding by app.settingsManager.hasCompletedOnboarding.collectAsState()

                    if (hasCompletedOnboarding) {
                        MainApp(app, pendingDeepLink)
                    } else {
                        OnboardingScreen(app)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Required so any subsequent singleTop launch (e.g. a notification tap
        // while the app is already running) updates the same intent instance.
        setIntent(intent)
        readDeepLinkFromIntent(intent)
    }

    /**
     * Portrait lock for phone-sized windows only.
     *
     * A manifest screenOrientation attribute became a no-op from Android 16 on large
     * screens (smallest width >= 600dp), so locking at runtime instead lets phones
     * (and small outer displays) keep the portrait experience while large screens
     * free-rotate. [smallestScreenWidthDp] reflects the app's window, so a foldable in
     * its folded state is locked and unfolded state is not.
     */
    @SuppressLint("DiscouragedApi")
    private fun applyPhoneOrientationLock() {
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun readDeepLinkFromIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_THREAD_ID)?.let { threadId ->
            if (threadId.isNotBlank()) pendingDeepLink.value = threadId
        }
    }

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
    }
}

@Composable
fun MainApp(
    app: RolesWitchApp,
    pendingDeepLink: MutableStateFlow<String?>
) {
    val navController = rememberNavController()
    val isThinking by app.llmService.isThinking.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val pendingThread by pendingDeepLink.collectAsState()

    var showThinkingExitAlert by remember { mutableStateOf(false) }
    var pendingNavAction by remember { mutableStateOf<() -> Unit>({}) }

    // Consume the deep link once the NavController is ready.
    LaunchedEffect(pendingThread) {
        val threadId = pendingThread ?: return@LaunchedEffect
        pendingDeepLink.value = null
        navController.navigate("messages/$threadId") {
            launchSingleTop = true
        }
    }

    val isLeavingThinkingScreen = currentRoute in listOf("messages/{threadId}", "assistant/{threadId}")

    fun navigateOrWarn(action: () -> Unit) {
        if (isThinking && isLeavingThinkingScreen) {
            pendingNavAction = action
            showThinkingExitAlert = true
        } else {
            action()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        NavHost(
            navController = navController,
            startDestination = "threads"
        ) {
            composable("threads") {
                ThreadsScreen(
                    app = app,
                    onNavigateToMessages = { threadId ->
                        navController.navigate("messages/$threadId") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSettings = {
                        navigateOrWarn {
                            navController.navigate("settings") {
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }
            composable(
                route = "messages/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                MessagesScreen(
                    app = app,
                    navController = navController,
                    threadId = threadId,
                    onBack = {
                        navigateOrWarn {
                            navController.safePopBackStack()
                        }
                    },
                    onNavigateToCall = {
                        navigateOrWarn {
                            navController.navigate("assistant/$threadId") {
                                launchSingleTop = true
                            }
                        }
                    },
                    onNavigateToProfile = {
                        navigateOrWarn {
                            navController.navigate("profile/$threadId") {
                                launchSingleTop = true
                            }
                        }
                    },
                    onNavigateToModel = {
                        navigateOrWarn {
                            navController.navigate("model/$threadId") {
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }
            composable(
                route = "assistant/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                AssistantScreen(
                    app = app,
                    navController = navController,
                    threadId = threadId
                )
            }
            composable(
                route = "profile/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                ThreadProfileScreen(
                    app = app,
                    threadId = threadId,
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    app = app,
                    onBack = { navController.safePopBackStack() },
                    onNavigateToAdvanced = { navController.navigate("advanced") },
                    onNavigateToAcknowledgements = { navController.navigate("acknowledgements") },
                    onNavigateToTools = { navController.navigate("tools") },
                    onNavigateToLitertModel = { navController.navigate("litert_model") }
                )
            }
            composable("litert_model") {
                val selected by app.settingsManager.litertModel.collectAsState()
                LitertModelScreen(
                    app = app,
                    selectedModelId = selected,
                    onSelect = { app.settingsManager.selectLitertModel(it) },
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable("tools") {
                ToolsScreen(
                    app = app,
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable("advanced") {
                // Which set of parameters "Advanced" means depends on the engine the
                // scope is running: the two have nothing in common but the screen.
                val useOnDevice by app.settingsManager.useOnDevice.collectAsState()
                if (useOnDevice) {
                    LitertAdvancedScreen(
                        app = app,
                        onBack = { navController.safePopBackStack() }
                    )
                } else {
                    AdvancedScreen(
                        app = app,
                        onBack = { navController.safePopBackStack() }
                    )
                }
            }
            composable("acknowledgements") {
                AcknowledgementsScreen(onBack = { navController.safePopBackStack() })
            }
            composable(
                route = "model/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                ModelSettingsScreen(
                    app = app,
                    threadId = threadId,
                    onBack = { navController.safePopBackStack() },
                    onNavigateToAdvanced = {
                        navController.navigate("model_advanced/$threadId") {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToLitertModel = {
                        navController.navigate("model_litert/$threadId") {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(
                route = "model_litert/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                val scope = rememberCoroutineScope()
                val thread by app.database.messageThreadDao()
                    .observeThreadById(threadId)
                    .collectAsState(initial = null)
                // Opaque for the whole transition, including the frame or two before
                // Room emits the thread. Without it the destination enters the
                // navigation animation with nothing in it and the content lands
                // afterwards — read as the screen jerking into place. The global route
                // has no such gap: it reads a StateFlow that already holds a value.
                ThreadScreenFrame {
                    val current = thread
                    if (current != null) {
                        LitertModelScreen(
                        app = app,
                        selectedModelId = EffectiveSettings.resolve(current, app.settingsManager).litertModel,
                        onSelect = { id ->
                            scope.launch {
                                app.database.updateThreadSettings(current) {
                                    it.withLitertModelSelected(id)
                                }
                            }
                        },
                            onBack = { navController.safePopBackStack() }
                        )
                    }
                }
            }
            composable(
                route = "model_advanced/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getString("threadId") ?: return@composable
                val scope = rememberCoroutineScope()
                val thread by app.database.messageThreadDao()
                    .observeThreadById(threadId)
                    .collectAsState(initial = null)
                // Same stable frame as the model route above.
                ThreadScreenFrame {
                    val current = thread
                    if (current != null) {
                        val effective = EffectiveSettings.resolve(current, app.settingsManager)
                        if (effective.useOnDevice) {
                            LitertAdvancedScreen(
                                app = app,
                                binding = remember(current) {
                                    ThreadLitertAdvancedBinding(app, current, scope)
                                },
                                onBack = { navController.safePopBackStack() }
                            )
                        } else {
                            AdvancedScreen(
                                binding = remember(current) {
                                    ThreadAdvancedBinding(app, current, scope)
                                },
                                onBack = { navController.safePopBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showThinkingExitAlert) {
        ConfirmDialog(
            text = stringResource(R.string.thinking_exit_message),
            confirmLabel = stringResource(R.string.ok),
            onConfirm = {
                showThinkingExitAlert = false
                pendingNavAction()
            },
            onDismiss = { showThinkingExitAlert = false }
        )
    }
}

/**
 * An opaque, full-screen backdrop for a destination whose content depends on a thread
 * loaded from the database.
 *
 * Those routes collect `observeThreadById` with `initial = null`, so for the first frame
 * or two they render nothing at all — the navigation animation runs on an empty
 * destination and the real screen appears once Room emits. This keeps the surface behind
 * it painted for the whole transition, so what animates in is a stable screen that then
 * fills, rather than a hole.
 */
@Composable
private fun ThreadScreenFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

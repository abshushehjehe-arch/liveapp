package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.ui.components.YouTubeSignInDialog
import com.example.ui.screens.ClipPlayerScreen
import com.example.ui.screens.ClipsScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.MonitorScreen
import com.example.ui.screens.PipelineScreen
import com.example.ui.theme.LocalStudioColors

private const val ROUTE_MONITOR = "monitor"
private const val ROUTE_PIPELINE = "pipeline"
private const val ROUTE_CLIPS = "clips"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_PLAYER = "player/{clipId}"

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val topDestinations = listOf(
    TopDestination(ROUTE_MONITOR, R.string.nav_monitor, Icons.Filled.Sensors, Icons.Outlined.Sensors),
    TopDestination(ROUTE_PIPELINE, R.string.nav_pipeline, Icons.Filled.AutoAwesomeMotion, Icons.Outlined.AutoAwesomeMotion),
    TopDestination(ROUTE_CLIPS, R.string.nav_clips, Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    TopDestination(ROUTE_DIAGNOSTICS, R.string.nav_diagnostics, Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart)
)

@Composable
fun PulseClipApp(
    viewModel: PulseClipViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val studio = LocalStudioColors.current

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.attachScreenCapture(result.resultCode, data)
        }
    }

    val launchProjectionIntent: () -> Unit = {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with MediaProjection whether audio capture was granted or skipped
        launchProjectionIntent()
    }

    val requestScreenAndAudioBridge: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionIntent()
        }
    }

    var pendingManualClipChannel by remember { mutableStateOf<com.example.data.local.MonitoredChannelEntity?>(null) }
    var pendingManualCutIsFreeform by remember { mutableStateOf(false) }
    var askedSpeechCaptionAudio by remember { mutableStateOf(false) }

    val speechAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingManualClipChannel?.let { ch ->
            val freeform = pendingManualCutIsFreeform
            pendingManualClipChannel = null
            if (freeform) viewModel.startManualCut(ch) else viewModel.triggerManualLiveClip(ch)
        }
    }

    val handleManualClip: (com.example.data.local.MonitoredChannelEntity) -> Unit = { channel ->
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm && !askedSpeechCaptionAudio) {
            askedSpeechCaptionAudio = true
            pendingManualCutIsFreeform = false
            pendingManualClipChannel = channel
            speechAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.triggerManualLiveClip(channel)
        }
    }

    val handleStartManualCut: (com.example.data.local.MonitoredChannelEntity) -> Unit = { channel ->
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm && !askedSpeechCaptionAudio) {
            askedSpeechCaptionAudio = true
            pendingManualCutIsFreeform = true
            pendingManualClipChannel = channel
            speechAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.startManualCut(channel)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_MONITOR
    val showTopLevelNav = topDestinations.any { it.route == currentRoute }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail && showTopLevelNav) {
                    NavigationBar(
                        containerColor = studio.rackSurface,
                        tonalElevation = 0.dp
                    ) {
                        topDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(ROUTE_MONITOR) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("nav_${dest.route}"),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = studio.velocityCyan,
                                    selectedTextColor = studio.velocityCyan,
                                    indicatorColor = studio.velocityCyanContainer,
                                    unselectedIconColor = studio.mutedText,
                                    unselectedTextColor = studio.mutedText
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail && showTopLevelNav) {
                    NavigationRail(
                        containerColor = studio.rackSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        topDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(ROUTE_MONITOR) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("rail_${dest.route}"),
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = studio.velocityCyan,
                                    selectedTextColor = studio.velocityCyan,
                                    indicatorColor = studio.velocityCyanContainer,
                                    unselectedIconColor = studio.mutedText,
                                    unselectedTextColor = studio.mutedText
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_MONITOR,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                    ) {
                        composable(ROUTE_MONITOR) {
                            MonitorScreen(
                                uiState = uiState,
                                embeddedPlayerManager = viewModel.embeddedPlayerManager,
                                needsNotificationPermission = !hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onToggleEngine = viewModel::toggleEngine,
                                onForcePoll = viewModel::forcePollAll,
                                onAddChannel = viewModel::addChannel,
                                onClearAddError = viewModel::clearAddChannelError,
                                onToggleChannelEnabled = viewModel::toggleChannelEnabled,
                                onRemoveChannel = viewModel::removeChannel,
                                onToggleManualClipCut = viewModel::toggleManualClipCut,
                                onManualClip = handleManualClip,
                                onStartManualCut = handleStartManualCut,
                                onStopManualCut = viewModel::stopManualCut,
                                onCancelManualCut = viewModel::cancelManualCut,
                                onTogglePlayerMute = viewModel::togglePlayerMute,
                                onReloadPlayer = viewModel::reloadPlayer,
                                onOpenYouTubeSignIn = viewModel::openYouTubeSignIn,
                                onOpenClip = { clipId ->
                                    navController.navigate("player/$clipId")
                                },
                                onDismissBanner = viewModel::dismissPipelineBanner,
                                onRequestScreenCapture = requestScreenAndAudioBridge
                            )
                        }

                        composable(ROUTE_PIPELINE) {
                            PipelineScreen(
                                uiState = uiState,
                                onUpdateConfig = viewModel::updateConfig,
                                onRequestScreenCapture = requestScreenAndAudioBridge,
                                onDetachScreenCapture = viewModel::detachScreenCapture,
                                onStopManualCut = viewModel::stopManualCut,
                                onCancelManualCut = viewModel::cancelManualCut,
                                onOpenClip = { clipId ->
                                    navController.navigate("player/$clipId")
                                },
                                onDismissBanner = viewModel::dismissPipelineBanner
                            )
                        }

                        composable(ROUTE_CLIPS) {
                            ClipsScreen(
                                uiState = uiState,
                                onSelectClip = { clipId ->
                                    navController.navigate("player/$clipId")
                                },
                                onDeleteClip = viewModel::deleteClip,
                                onDismissBanner = viewModel::dismissPipelineBanner
                            )
                        }

                        composable(ROUTE_DIAGNOSTICS) {
                            DiagnosticsScreen(uiState = uiState)
                        }

                        composable(
                            route = ROUTE_PLAYER,
                            arguments = listOf(navArgument("clipId") { type = NavType.LongType })
                        ) { entry ->
                            val clipId = entry.arguments?.getLong("clipId") ?: -1L
                            val clip = uiState.clips.firstOrNull { it.id == clipId }
                            if (clip != null) {
                                ClipPlayerScreen(
                                    clip = clip,
                                    onBack = { navController.popBackStack() },
                                    onDelete = {
                                        viewModel.deleteClip(clip)
                                        navController.popBackStack()
                                    }
                                )
                            } else {
                                LaunchedEffect(Unit) {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uiState.showYouTubeLoginPopup) {
            YouTubeSignInDialog(
                onDismiss = viewModel::dismissYouTubeSignIn,
                onSignedIn = viewModel::completeYouTubeSignIn
            )
        }
    }
}

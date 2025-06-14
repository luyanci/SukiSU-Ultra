package com.sukisu.ultra.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.ExecuteModuleActionScreenDestination
import com.ramcosta.composedestinations.spec.NavHostGraphSpec
import com.ramcosta.composedestinations.spec.RouteOrDirection
import com.ramcosta.composedestinations.utils.isRouteOnBackStackAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import io.sukisu.ultra.UltraToolInstall
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.data.AppData
import com.sukisu.ultra.ui.screen.BottomBarDestination
import com.sukisu.ultra.ui.theme.*
import com.sukisu.ultra.ui.theme.CardConfig.cardAlpha
import com.sukisu.ultra.ui.util.*
import androidx.core.content.edit
import com.sukisu.ultra.ui.data.AppData.DataRefreshManager
import com.sukisu.ultra.ui.theme.CardConfig.cardElevation
import com.sukisu.ultra.ui.webui.initPlatform
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.lifecycleScope
import com.sukisu.ultra.ui.data.AppData.getKpmVersionUse
import com.sukisu.ultra.ui.viewmodel.HomeViewModel
import com.sukisu.ultra.ui.viewmodel.SuperUserViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private lateinit var superUserViewModel: SuperUserViewModel
    private lateinit var homeViewModel: HomeViewModel

    private inner class ThemeChangeContentObserver(
        handler: Handler,
        private val onThemeChanged: () -> Unit
    ) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            onThemeChanged()
        }
    }

    // 应用保存的语言设置
    @SuppressLint("ObsoleteSdkInt")
    private fun applyLanguageSetting() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", "") ?: ""

        if (languageCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageCode)
            Locale.setDefault(locale)

            val resources = resources
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createConfigurationContext(config)
            } else {
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", "") ?: ""

        var context = newBase
        if (languageCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageCode)
            Locale.setDefault(locale)

            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            context = newBase.createConfigurationContext(config)
        }

        super.attachBaseContext(context)
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 确保应用正确的语言设置
        applyLanguageSetting()

        // 应用自定义 DPI
        applyCustomDpi()

        // Enable edge to edge
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        // 初始化 SuperUserViewModel
        superUserViewModel = SuperUserViewModel()

        lifecycleScope.launch {
            superUserViewModel.fetchAppList()
        }

        // 初始化 HomeViewModel
        homeViewModel = HomeViewModel()

        // 预加载数据
        lifecycleScope.launch {
            homeViewModel.initializeData()
        }

        // 数据刷新协程
        startDataRefreshCoroutine()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("is_first_run", true)

        if (isFirstRun) {
            ThemeConfig.preventBackgroundRefresh = false
            getSharedPreferences("theme_prefs", MODE_PRIVATE).edit {
                putBoolean("prevent_background_refresh", false)
            }
            prefs.edit { putBoolean("is_first_run", false) }
        }

        // 加载保存的背景设置
        loadThemeMode()
        loadThemeColors()
        loadDynamicColorState()
        CardConfig.load(applicationContext)

        val contentObserver = ThemeChangeContentObserver(Handler(mainLooper)) {
            runOnUiThread {
                if (!ThemeConfig.preventBackgroundRefresh) {
                    ThemeConfig.backgroundImageLoaded = false
                    loadCustomBackground()
                }
            }
        }

        contentResolver.registerContentObserver(
            android.provider.Settings.System.getUriFor("ui_night_mode"),
            false,
            contentObserver
        )

        val destroyListeners = mutableListOf<() -> Unit>()
        destroyListeners.add {
            contentResolver.unregisterContentObserver(contentObserver)
        }

        val isManager = AppData.isManager(ksuApp.packageName)
        if (isManager) {
            install()
            UltraToolInstall.tryToInstall()
        }

        setContent {
            KernelSUTheme {
                val navController = rememberNavController()
                val snackBarHostState = remember { SnackbarHostState() }
                val currentDestination = navController.currentBackStackEntryAsState().value?.destination

                val showBottomBar = when (currentDestination?.route) {
                    ExecuteModuleActionScreenDestination.route -> false // Hide for ExecuteModuleActionScreen
                    else -> true
                }

                // pre-init platform to faster start WebUI X activities
                LaunchedEffect(Unit) {
                    initPlatform()
                }

                CompositionLocalProvider(
                    LocalSnackbarHost provides snackBarHostState
                ) {
                    Scaffold(
                        bottomBar = {
                            AnimatedVisibility(
                                visible = showBottomBar,
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                            ) {
                                BottomBar(navController)
                            }
                        },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) { innerPadding ->
                        DestinationsNavHost(
                            modifier = Modifier.padding(innerPadding),
                            navGraph = NavGraphs.root as NavHostGraphSpec,
                            navController = navController,
                            defaultTransitions = object : NavHostAnimatedDestinationStyle() {
                                override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
                                    get() = { fadeIn(animationSpec = tween(340)) }
                                override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
                                    get() = { fadeOut(animationSpec = tween(340)) }
                            }
                        )
                    }
                }
            }
        }
    }

    // 数据刷新协程
    private fun startDataRefreshCoroutine() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                DataRefreshManager.refreshData()
                delay(5000)
            }
        }
    }

    // 应用自定义DPI设置
    private fun applyCustomDpi() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val customDpi = prefs.getInt("app_dpi", 0)

        if (customDpi > 0) {
            try {
                val resources = resources
                val metrics = resources.displayMetrics
                metrics.density = customDpi / 160f
                @Suppress("DEPRECATION")
                metrics.scaledDensity = customDpi / 160f
                metrics.densityDpi = customDpi
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CardConfig.save(applicationContext)
        getSharedPreferences("theme_prefs", MODE_PRIVATE).edit {
            putBoolean("prevent_background_refresh", true)
        }
        ThemeConfig.preventBackgroundRefresh = true
    }

    override fun onResume() {
        super.onResume()
        applyLanguageSetting()

        if (!ThemeConfig.backgroundImageLoaded && !ThemeConfig.preventBackgroundRefresh) {
            loadCustomBackground()
        }

        lifecycleScope.launch {
            superUserViewModel.fetchAppList()
        }

        lifecycleScope.launch {
            homeViewModel.initializeData()
        }

        lifecycleScope.launch {
            DataRefreshManager.refreshData()
        }
    }

    private val destroyListeners = mutableListOf<() -> Unit>()

    override fun onDestroy() {
        destroyListeners.forEach { it() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLanguageSetting()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomBar(navController: NavHostController) {
    val navigator = navController.rememberDestinationsNavigator()
    val isFullFeatured = AppData.isFullFeatured(ksuApp.packageName)
    val kpmVersion = getKpmVersionUse()
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val context = LocalContext.current

    // 收集计数数据
    val superuserCount by DataRefreshManager.superuserCount.collectAsState()
    val moduleCount by DataRefreshManager.moduleCount.collectAsState()
    val kpmModuleCount by DataRefreshManager.kpmModuleCount.collectAsState()

    // 检查是否显示KPM
    val showKpmInfo = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean("show_kpm_info", true)

    val isHideOtherInfo = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean("is_hide_other_info", false)

    NavigationBar(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
        ),
        containerColor = TopAppBarDefaults.topAppBarColors(
            containerColor = cardColor.copy(alpha = cardAlpha),
            scrolledContainerColor = cardColor.copy(alpha = cardAlpha)
        ).containerColor,
        tonalElevation = cardElevation
    ) {
        BottomBarDestination.entries.forEach { destination ->
            if (destination == BottomBarDestination.Kpm) {
                if (kpmVersion.isNotEmpty() && !kpmVersion.startsWith("Error") && showKpmInfo && Natives.version >= Natives.MINIMAL_SUPPORTED_KPM) {
                    if (!isFullFeatured && destination.rootRequired) return@forEach
                    val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(destination.direction)
                    NavigationBarItem(
                        selected = isCurrentDestOnBackStack,
                        onClick = {
                            if (!isCurrentDestOnBackStack) {
                                navigator.popBackStack(destination.direction, false)
                            }
                            navigator.navigate(destination.direction) {
                                popUpTo(NavGraphs.root as RouteOrDirection) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (kpmModuleCount > 0 && !isHideOtherInfo) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ) {
                                            Text(
                                                text = kpmModuleCount.toString(),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            ) {
                                if (isCurrentDestOnBackStack) {
                                    Icon(destination.iconSelected, stringResource(destination.label))
                                } else {
                                    Icon(destination.iconNotSelected, stringResource(destination.label))
                                }
                            }
                        },
                        label = { Text(stringResource(destination.label),style = MaterialTheme.typography.labelMedium) },
                        alwaysShowLabel = false
                    )
                }
            } else if (destination == BottomBarDestination.SuperUser) {
                if (!isFullFeatured && destination.rootRequired) return@forEach
                val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(destination.direction)

                NavigationBarItem(
                    selected = isCurrentDestOnBackStack,
                    onClick = {
                        if (isCurrentDestOnBackStack) {
                            navigator.popBackStack(destination.direction, false)
                        }
                        navigator.navigate(destination.direction) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (superuserCount > 0 && !isHideOtherInfo) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    ) {
                                        Text(
                                            text = superuserCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            if (isCurrentDestOnBackStack) {
                                Icon(destination.iconSelected, stringResource(destination.label))
                            } else {
                                Icon(destination.iconNotSelected, stringResource(destination.label))
                            }
                        }
                    },
                    label = { Text(stringResource(destination.label),style = MaterialTheme.typography.labelMedium) },
                    alwaysShowLabel = false
                )
            } else if (destination == BottomBarDestination.Module) {
                if (!isFullFeatured && destination.rootRequired) return@forEach
                val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(destination.direction)

                NavigationBarItem(
                    selected = isCurrentDestOnBackStack,
                    onClick = {
                        if (isCurrentDestOnBackStack) {
                            navigator.popBackStack(destination.direction, false)
                        }
                        navigator.navigate(destination.direction) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (moduleCount > 0 && !isHideOtherInfo) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondary                              ) {
                                        Text(
                                            text = moduleCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            if (isCurrentDestOnBackStack) {
                                Icon(destination.iconSelected, stringResource(destination.label))
                            } else {
                                Icon(destination.iconNotSelected, stringResource(destination.label))
                            }
                        }
                    },
                    label = { Text(stringResource(destination.label),style = MaterialTheme.typography.labelMedium) },
                    alwaysShowLabel = false
                )
            } else {
                if (!isFullFeatured && destination.rootRequired) return@forEach
                val isCurrentDestOnBackStack by navController.isRouteOnBackStackAsState(destination.direction)

                NavigationBarItem(
                    selected = isCurrentDestOnBackStack,
                    onClick = {
                        if (isCurrentDestOnBackStack) {
                            navigator.popBackStack(destination.direction, false)
                        }
                        navigator.navigate(destination.direction) {
                            popUpTo(NavGraphs.root) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        if (isCurrentDestOnBackStack) {
                            Icon(destination.iconSelected, stringResource(destination.label))
                        } else {
                            Icon(destination.iconNotSelected, stringResource(destination.label))
                        }
                    },
                    label = { Text(stringResource(destination.label),style = MaterialTheme.typography.labelMedium) },
                    alwaysShowLabel = false
                )
            }
        }
    }
}
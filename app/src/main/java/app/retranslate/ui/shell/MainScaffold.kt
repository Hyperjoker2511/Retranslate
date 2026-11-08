package app.retranslate.ui.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WorkHistory
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.retranslate.ui.common.bounceClickable
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.retranslate.core.motion.MotionTokens
import app.retranslate.core.motion.rememberMotionEnabled
import app.retranslate.di.AppContainer
import app.retranslate.providers.ProviderId
import app.retranslate.ui.jobs.JobsScreen
import app.retranslate.ui.more.MoreScreen
import app.retranslate.ui.settings.InstancesScreen
import app.retranslate.ui.settings.InstancesViewModel
import app.retranslate.ui.settings.KeyEntryScreen
import app.retranslate.ui.settings.KeyEntryViewModel
import app.retranslate.ui.settings.ProvidersScreen
import app.retranslate.ui.settings.ProvidersViewModel
import app.retranslate.ui.translate.TranslateScreen
import app.retranslate.ui.translate.TranslateViewModel

/** Sub-routes inside the More tab (P2: Providers & keys and its screens). */
private const val MORE_HOME = "more/home"
private const val MORE_PROVIDERS = "more/providers"
private const val MORE_KEY_ENTRY = "more/providers/key/{providerId}"
private const val MORE_INSTANCES = "more/providers/instances/{providerId}"

/** P6: Backup & restore, inside the More tab's stack. */
private const val MORE_BACKUP = "more/backup"

/** P7: Appearance completion (§9.5), inside the More tab's stack. */
private const val MORE_APPEARANCE = "more/appearance"

/** P8: open-source licences, inside the More tab's stack. */
private const val MORE_LICENCES = "more/licences"

/** P7: Ollama set-up (§4.3), reached from the Providers screen's row. */
private const val MORE_OLLAMA = "more/providers/ollama"

/** Sub-routes inside the Library tab (P3: shelf, book page, import review). */
private const val LIB_HOME = "library/home"
private const val LIB_BOOK = "library/book/{bookId}"
private const val LIB_IMPORT_REVIEW = "library/import-review"

/** P5: Context-Learning screens, inside the Library tab's stack. */
private const val LIB_STUDY = "library/book/{bookId}/study"
private const val LIB_KB = "library/book/{bookId}/kb"

/** P6: the chapter review (quality-checking) screen. */
private const val LIB_REVIEW = "library/book/{bookId}/chapter/{chapterId}"

private fun bookRoute(bookId: Long) = "library/book/$bookId"
private fun studyRoute(bookId: Long) = "library/book/$bookId/study"
private fun kbRoute(bookId: Long) = "library/book/$bookId/kb"
private fun reviewRoute(bookId: Long, chapterId: Long) =
    "library/book/$bookId/chapter/$chapterId"

private fun keyEntryRoute(id: ProviderId) = "more/providers/key/${id.name}"
private fun instancesRoute(id: ProviderId) = "more/providers/instances/${id.name}"

/**
 * The main shell (PLAN.md §9.2): four LABELLED bottom tabs — Library
 * (start), Translate, Jobs, More. Labels are always shown (§9.1 rule 2);
 * each tab keeps its own back stack; tab switches and pushes use the
 * Shared Axis X motion (§9.4), degrading to fades under reduce-motion.
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Library("library", "Library", Icons.Filled.LibraryBooks),
    Translate("translate", "Translate", Icons.Filled.Translate),
    Jobs("jobs", "Jobs", Icons.Filled.WorkHistory),
    More("more", "More", Icons.Filled.MoreHoriz)
}

@Composable
fun MainScaffold(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // §9.4/§9.5 (P7): "Bouncy animations" off (or the system's own
    // reduce-motion) degrades every token in this shell to fades.
    val appearance by container.appearancePrefs.state
        .collectAsStateWithLifecycle(
            initialValue = app.retranslate.data.prefs.AppearanceState()
        )
    val motionOn = rememberMotionEnabled(userEnabled = appearance.bouncyAnimations)
    val reduceMotion = !motionOn

    fun selectTab(tab: TopDestination) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // v1.1.3 (§B item 6): the Review screen is a reading surface —
    // the app tab bar does NOT sit on it (the user's report: "nav
    // bar sits on it, features clustered"). Review keeps its own
    // slim top bar with a Back arrow.
    val onReview = currentDestination?.route == LIB_REVIEW
    Scaffold(
        bottomBar = {
            if (!onReview) {
                FluidNavBar(
                    selectedTab = TopDestination.entries.firstOrNull { tab ->
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    },
                    motionOn = motionOn,
                    onSelect = ::selectTab
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Library.route,
            modifier = Modifier.padding(padding),
            enterTransition = { MotionTokens.sharedAxisXEnter(reduceMotion) },
            exitTransition = { MotionTokens.sharedAxisXExit(reduceMotion) },
            popEnterTransition = { MotionTokens.sharedAxisXPopEnter(reduceMotion) },
            popExitTransition = { MotionTokens.sharedAxisXPopExit(reduceMotion) }
        ) {
            navigation(route = TopDestination.Library.route, startDestination = LIB_HOME) {
                composable(LIB_HOME) {
                    val libraryViewModel: app.retranslate.ui.library.LibraryViewModel =
                        viewModel(factory = container.libraryViewModelFactory())
                    app.retranslate.ui.library.LibraryScreen(
                        viewModel = libraryViewModel,
                        onOpenBook = { bookId -> navController.navigate(bookRoute(bookId)) },
                        onOpenImportReview = { navController.navigate(LIB_IMPORT_REVIEW) },
                        onOpenReview = { bookId, chapterId ->
                            navController.navigate(reviewRoute(bookId, chapterId))
                        }
                    )
                }
                composable(LIB_IMPORT_REVIEW) {
                    val reviewViewModel: app.retranslate.ui.library.ImportReviewViewModel =
                        viewModel(factory = container.importReviewViewModelFactory())
                    app.retranslate.ui.library.ImportReviewScreen(
                        viewModel = reviewViewModel,
                        onDone = { bookId ->
                            navController.popBackStack(LIB_HOME, inclusive = false)
                            navController.navigate(bookRoute(bookId))
                        },
                        onOpenExisting = { bookId ->
                            navController.popBackStack(LIB_HOME, inclusive = false)
                            navController.navigate(bookRoute(bookId))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    LIB_BOOK,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: 0L
                    val bookViewModel: app.retranslate.ui.library.BookDetailViewModel =
                        viewModel(factory = container.bookDetailViewModelFactory(bookId))
                    app.retranslate.ui.library.BookDetailScreen(
                        viewModel = bookViewModel,
                        describeFile = { uri -> container.pickedFiles.describe(uri) },
                        onBack = { navController.popBackStack() },
                        onLearnFromSamples = { navController.navigate(studyRoute(bookId)) },
                        onOpenKnowledgeBase = { navController.navigate(kbRoute(bookId)) },
                        onOpenChapter = { chapterId ->
                            navController.navigate(reviewRoute(bookId, chapterId))
                        }
                    )
                }
                composable(
                    LIB_REVIEW,
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.LongType },
                        navArgument("chapterId") { type = NavType.LongType }
                    )
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: 0L
                    val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
                    val reviewViewModel: app.retranslate.ui.review.ChapterReviewViewModel =
                        viewModel(
                            factory = container.chapterReviewViewModelFactory(
                                bookId, chapterId
                            )
                        )
                    app.retranslate.ui.review.ChapterReviewScreen(
                        viewModel = reviewViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenChapter = { nextChapterId ->
                            // Prev/next REPLACES this chapter (no stack of
                            // chapters to back through — Back means the book).
                            navController.navigate(reviewRoute(bookId, nextChapterId)) {
                                popUpTo(LIB_REVIEW) { inclusive = true }
                            }
                        }
                    )
                }
                composable(
                    LIB_STUDY,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: 0L
                    val studyViewModel: app.retranslate.ui.kb.StudySetupViewModel =
                        viewModel(factory = container.studySetupViewModelFactory(bookId))
                    app.retranslate.ui.kb.StudySetupScreen(
                        viewModel = studyViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenKnowledgeBase = { navController.navigate(kbRoute(bookId)) },
                        onSetUpKey = {
                            selectTab(TopDestination.More)
                            navController.navigate(MORE_PROVIDERS)
                        }
                    )
                }
                composable(
                    LIB_KB,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: 0L
                    val kbViewModel: app.retranslate.ui.kb.KnowledgeBaseViewModel =
                        viewModel(factory = container.knowledgeBaseViewModelFactory(bookId))
                    app.retranslate.ui.kb.KnowledgeBaseScreen(
                        viewModel = kbViewModel,
                        onBack = { navController.popBackStack() },
                        onLearnFromSamples = { navController.navigate(studyRoute(bookId)) }
                    )
                }
            }
            composable(TopDestination.Translate.route) {
                val translateViewModel: TranslateViewModel =
                    viewModel(factory = container.translateViewModelFactory())
                TranslateScreen(
                    viewModel = translateViewModel,
                    // v1.1 picker: a row that needs set-up routes here,
                    // to Providers & keys in the More tab (same pattern
                    // as StudySetup's "set up a key" path).
                    onOpenProviderSettings = {
                        selectTab(TopDestination.More)
                        navController.navigate(MORE_PROVIDERS)
                    }
                )
            }
            composable(TopDestination.Jobs.route) {
                val jobsViewModel: app.retranslate.ui.jobs.JobsViewModel =
                    viewModel(factory = container.jobsViewModelFactory())
                JobsScreen(
                    onGoToLibrary = { selectTab(TopDestination.Library) },
                    onOpenBook = { bookId ->
                        selectTab(TopDestination.Library)
                        navController.navigate(bookRoute(bookId))
                    },
                    viewModel = jobsViewModel
                )
            }
            navigation(route = TopDestination.More.route, startDestination = MORE_HOME) {
                composable(MORE_HOME) {
                    // v1.1.2: the More banner tells the truth about the
                    // Gemini key — presence only, never the key itself.
                    val presentKeys by container.keyVault.presentKeys
                        .collectAsStateWithLifecycle(initialValue = emptySet())
                    MoreScreen(
                        geminiKeyPresent = ProviderId.GEMINI.name in presentKeys,
                        onProvidersClick = { navController.navigate(MORE_PROVIDERS) },
                        onBackupClick = { navController.navigate(MORE_BACKUP) },
                        onAppearanceClick = { navController.navigate(MORE_APPEARANCE) },
                        onLicencesClick = { navController.navigate(MORE_LICENCES) }
                    )
                }
                composable(MORE_APPEARANCE) {
                    val appearanceViewModel: app.retranslate.ui.settings.AppearanceViewModel =
                        viewModel(factory = container.appearanceViewModelFactory())
                    app.retranslate.ui.settings.AppearanceScreen(
                        viewModel = appearanceViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(MORE_LICENCES) {
                    app.retranslate.ui.settings.LicencesScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(MORE_BACKUP) {
                    val backupViewModel: app.retranslate.ui.more.BackupViewModel =
                        viewModel(factory = container.backupViewModelFactory())
                    app.retranslate.ui.more.BackupScreen(
                        viewModel = backupViewModel,
                        describeFile = { uri -> container.pickedFiles.describe(uri) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(MORE_PROVIDERS) {
                    val providersViewModel: ProvidersViewModel =
                        viewModel(factory = container.providersViewModelFactory())
                    ProvidersScreen(
                        viewModel = providersViewModel,
                        onBack = { navController.popBackStack() },
                        onAddKey = { id -> navController.navigate(keyEntryRoute(id)) },
                        onEditInstances = { id -> navController.navigate(instancesRoute(id)) },
                        onOllamaSetup = { navController.navigate(MORE_OLLAMA) }
                    )
                }
                composable(MORE_OLLAMA) {
                    val ollamaViewModel: app.retranslate.ui.settings.OllamaSetupViewModel =
                        viewModel(factory = container.ollamaSetupViewModelFactory())
                    app.retranslate.ui.settings.OllamaSetupScreen(
                        viewModel = ollamaViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    MORE_KEY_ENTRY,
                    arguments = listOf(navArgument("providerId") { type = NavType.StringType })
                ) { entry ->
                    val id = ProviderId.valueOf(
                        entry.arguments?.getString("providerId") ?: ProviderId.GEMINI.name
                    )
                    val keyEntryViewModel: KeyEntryViewModel =
                        viewModel(factory = container.keyEntryViewModelFactory(id))
                    KeyEntryScreen(
                        viewModel = keyEntryViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    MORE_INSTANCES,
                    arguments = listOf(navArgument("providerId") { type = NavType.StringType })
                ) { entry ->
                    val id = ProviderId.valueOf(
                        entry.arguments?.getString("providerId") ?: ProviderId.MOZHI.name
                    )
                    val instancesViewModel: InstancesViewModel =
                        viewModel(factory = container.instancesViewModelFactory(id))
                    InstancesScreen(
                        viewModel = instancesViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/**
 * v1.1 (Fluid Glass): the bottom bar from the Stitch design — a
 * frosted surface with four LABELLED tabs and a pill that SPRINGS
 * (slides) behind the selected tab instead of snapping; reduce-motion
 * → no travel animation. Labels are always visible (user rule:
 * labelled tabs, never icons alone).
 *
 * v1.1.1 hotfix: the bar's height is the FIXED constant
 * [FluidNavBarHeight] and the pill is sized and positioned ONLY
 * through [NavBarGeometry], clamped inside the bar's measured bounds
 * on every frame. The v1.1 version put the pill in this same Box with
 * `Modifier.fillMaxHeight()`: Compose measures a Box's children
 * against the Box's incoming constraints before the Box has a height
 * of its own, and Scaffold's bottomBar constraints allow the full
 * screen height — so the pill was measured a full screen tall, the
 * Box sized itself to it, Scaffold reserved the whole screen for the
 * bar, and the content got zero height (the giant-pill screenshot).
 * Nothing here derives a height from a child anymore, so that cannot
 * recur regardless of content, font scale, or theme.
 */
private val FluidNavBarHeight = 64.dp

@Composable
private fun FluidNavBar(
    selectedTab: TopDestination?,
    motionOn: Boolean,
    onSelect: (TopDestination) -> Unit
) {
    val tabs = TopDestination.entries
    val selectedIndex = tabs.indexOfFirst { it == selectedTab }.coerceAtLeast(0)
    val density = LocalDensity.current
    val pillInsetXPx = with(density) { 4.dp.roundToPx() }
    val pillInsetYPx = with(density) { 6.dp.roundToPx() }
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        var barWidthPx by remember { mutableIntStateOf(0) }
        var barHeightPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(FluidNavBarHeight)
                .padding(horizontal = 8.dp)
                .onSizeChanged {
                    barWidthPx = it.width
                    barHeightPx = it.height
                }
        ) {
            val pillWidthPx = NavBarGeometry.pillWidthPx(
                barWidthPx, tabs.size, pillInsetXPx
            )
            val pillHeightPx = NavBarGeometry.pillHeightPx(barHeightPx, pillInsetYPx)
            val pillTargetX = NavBarGeometry.pillTargetXPx(
                barWidthPx, tabs.size, selectedIndex, pillInsetXPx
            )
            val pillX by animateFloatAsState(
                targetValue = pillTargetX.toFloat(),
                animationSpec = if (motionOn) {
                    MotionTokens.spatialSpring()
                } else {
                    androidx.compose.animation.core.tween(0)
                },
                label = "navPillX"
            )
            if (pillWidthPx > 0 && pillHeightPx > 0) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                NavBarGeometry.clampPillXPx(
                                    pillX, barWidthPx, pillWidthPx
                                ).roundToInt(),
                                NavBarGeometry.pillTargetYPx(barHeightPx, pillInsetYPx)
                            )
                        }
                        .width(with(density) { pillWidthPx.toDp() })
                        .height(with(density) { pillHeightPx.toDp() })
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            androidx.compose.material3.MaterialTheme.colorScheme
                                .primaryContainer
                        )
                )
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize()
            ) {
                tabs.forEach { tab ->
                    val selected = tab == selectedTab
                    val contentColor = if (selected) {
                        androidx.compose.material3.MaterialTheme.colorScheme
                            .onPrimaryContainer
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme
                            .onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .bounceClickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout
                            .Arrangement.Center
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor
                        )
                        Text(
                            tab.label,
                            style = androidx.compose.material3.MaterialTheme
                                .typography.labelMedium,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

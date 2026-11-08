package app.retranslate.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import app.retranslate.core.network.OkHttpTransport
import app.retranslate.core.security.AndroidKeystoreCipher
import app.retranslate.core.security.KeyVault
import app.retranslate.data.db.ReTranslateDb
import app.retranslate.data.db.RoomLanguageListCache
import app.retranslate.data.db.RoomTxRunner
import app.retranslate.data.files.BookFiles
import app.retranslate.data.files.PickedFiles
import app.retranslate.data.files.TempFiles
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.ImportSessionStore
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.data.prefs.SavedLanguages
import app.retranslate.data.prefs.keyVaultDataStore
import app.retranslate.data.prefs.providerConfigDataStore
import app.retranslate.data.prefs.settingsDataStore
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.free.LibreTranslateProvider
import app.retranslate.providers.free.LingvaProvider
import app.retranslate.providers.free.MozhiProvider
import app.retranslate.providers.free.MyMemoryProvider
import app.retranslate.providers.free.SimplyTranslateProvider
import app.retranslate.providers.hub.ChainOrdering
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import app.retranslate.providers.key.AzureProvider
import app.retranslate.providers.key.DeepLProvider
import app.retranslate.providers.key.GeminiProvider
import app.retranslate.providers.key.KeyTestable
import app.retranslate.ui.library.BookDetailViewModel
import app.retranslate.ui.library.ImportReviewViewModel
import app.retranslate.ui.library.LibraryViewModel
import app.retranslate.ui.settings.InstancesViewModel
import app.retranslate.ui.settings.KeyEntryViewModel
import app.retranslate.ui.settings.ProvidersViewModel
import app.retranslate.ui.translate.TranslateViewModel

/**
 * Manual dependency injection (PLAN.md §2.7): one container, created by the
 * Application, holding the app's repositories and services. Manual on
 * purpose: fakes are trivial to substitute in tests, and there is no
 * codegen failure class in this sandbox.
 */
class AppContainer(context: Context) {

    /** Application context — the only Context anything long-lived may hold. */
    val appContext: Context = context.applicationContext

    // ---- P1: provider hub ---------------------------------------------

    /** Non-secret provider config: instances, health stamps, budgets. */
    val providerConfigStore = ProviderConfigStore(appContext.providerConfigDataStore)

    /** The Translate screen's language pair — its one owner (§3.5). */
    val savedLanguages = SavedLanguages(appContext.settingsDataStore)

    // ---- P3: Room, book files, import pipeline -------------------------

    /** The app database — schema v1 (§8), exported from day one. */
    val database = ReTranslateDb.create(appContext)

    val bookRepository = BookRepository(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        knowledgeBaseDao = database.knowledgeBaseDao(),
        tx = RoomTxRunner(database),
        // v1.1 (workstream E): full owned-row cleanup on "Remove book".
        sampleDao = database.sampleDao(),
        jobRunDao = database.jobRunDao()
    )

    /** App-private per-book files (covers, samples) — §8.2. */
    val bookFiles = BookFiles(BookFiles.rootOf(appContext.filesDir))

    /** Import/export temp working files — §7.1/§7.5. */
    val tempFiles = TempFiles(TempFiles.rootOf(appContext.cacheDir))

    /** The one in-flight import, shared by Library and the review screen. */
    val importSessionStore = ImportSessionStore()

    val bookImporter = BookImporter(tempFiles, bookRepository, bookFiles)

    /** SAF picker results → importer inputs. */
    val pickedFiles = PickedFiles(appContext)

    /** §4.6: provider language lists persisted in Room (7-day TTL). */
    val languageListCache = RoomLanguageListCache(database.providerLanguageDao())

    val providerHealth = ProviderHealth(providerConfigStore)

    /**
     * The free-provider HTTP stack (§2.3/§4.5): 10s connect / 15s read /
     * 20s whole-call. One transport shared by all free providers.
     * (Connect raised 5s → 10s in v1.0.1, with the IPv4-first DNS.)
     */
    private val freeTransport = OkHttpTransport(
        client = OkHttpTransport.appClient(
            connectTimeoutSec = 10,
            readTimeoutSec = 15,
            callTimeoutSec = 20
        ),
        providerTag = "free"
    )

    val mozhiProvider = MozhiProvider(freeTransport, providerHealth, providerConfigStore)
    val lingvaProvider = LingvaProvider(
        freeTransport, providerHealth, providerConfigStore,
        languageCache = languageListCache
    )
    val simplyTranslateProvider =
        SimplyTranslateProvider(freeTransport, providerHealth, providerConfigStore)
    val myMemoryProvider = MyMemoryProvider(
        transport = freeTransport,
        health = providerHealth,
        usageRecorder = { words -> providerConfigStore.addMyMemoryWords(words) }
    )

    // ---- P2: KeyVault + keyed providers --------------------------------

    /** The only holder of the user's API keys (§2.5): Keystore-encrypted. */
    val keyVault = KeyVault(appContext.keyVaultDataStore, AndroidKeystoreCipher())

    /**
     * The keyed-provider HTTP stack (§4.5 key-classic budgets: 10s connect
     * / 20s read / 30s whole-call) for quick text and key tests.
     * (Connect raised 5s → 10s in v1.0.1, with the IPv4-first DNS.)
     */
    private val keyedTransport = OkHttpTransport(
        client = OkHttpTransport.appClient(
            connectTimeoutSec = 10,
            readTimeoutSec = 20,
            callTimeoutSec = 30
        ),
        providerTag = "keyed"
    )

    /** v1.1.2: the Gemini key screen's persisted model choice (settings store). */
    val geminiModelPrefs = app.retranslate.data.prefs.GeminiModelPrefs(
        appContext.settingsDataStore
    )

    /**
     * v1.1.2: ONE cascade session memory shared by the quick and chapter
     * Gemini instances — a model proven working by either is tried
     * first by both.
     */
    val geminiSession = app.retranslate.providers.key.GeminiModelSession()

    val geminiProvider = GeminiProvider(
        transport = keyedTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.GEMINI.name) },
        isConfigured = { keyVault.isPresent(ProviderId.GEMINI.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.GEMINI, chars) },
        modelPreference = { geminiModelPrefs.choice() },
        session = geminiSession
    )

    val deepLProvider = DeepLProvider(
        transport = keyedTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.DEEPL.name) },
        isConfigured = { keyVault.isPresent(ProviderId.DEEPL.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.DEEPL, chars) }
    )

    val azureProvider = AzureProvider(
        transport = keyedTransport,
        health = providerHealth,
        secretProvider = { keyVault.get(ProviderId.AZURE.name) },
        isConfigured = { keyVault.isPresent(ProviderId.AZURE.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.AZURE, chars) }
    )

    val libreTranslateProvider = LibreTranslateProvider(
        transport = freeTransport,
        health = providerHealth,
        instances = providerConfigStore,
        keyProvider = { keyVault.get(ProviderId.LIBRETRANSLATE.name) },
        usageRecorder = { chars ->
            providerConfigStore.addUsageChars(ProviderId.LIBRETRANSLATE, chars)
        }
    )

    // ---- P7: OpenAI + Claude adapters, Ollama local --------------------

    val openAiProvider = app.retranslate.providers.key.OpenAiProvider(
        transport = keyedTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.OPENAI.name) },
        isConfigured = { keyVault.isPresent(ProviderId.OPENAI.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.OPENAI, chars) }
    )

    val claudeProvider = app.retranslate.providers.key.ClaudeProvider(
        transport = keyedTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.CLAUDE.name) },
        isConfigured = { keyVault.isPresent(ProviderId.CLAUDE.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.CLAUDE, chars) }
    )

    /**
     * The dormant local entry (§4.3): inert until the user saves a PC
     * address + model in its setup screen; the config is refreshed at
     * startup (init block) and by the setup screen after every save.
     */
    val ollamaProvider = app.retranslate.providers.local.OllamaProvider(
        transport = keyedTransport,
        health = providerHealth,
        configSource = providerConfigStore,
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.OLLAMA, chars) }
    )

    // ---- v1.1.3: APInex (§J) + add-a-provider gateways (§K) + Offline (§H)

    /**
     * §J: APInex — a named OpenAI-compatible gateway on the
     * parameterised adapter: prefilled base URL, its own key slot,
     * its picked (or default free) model, models-list key test.
     * Labelled third-party/promotional in the UI; never silent.
     */
    val apinexProvider = app.retranslate.providers.key.OpenAiProvider(
        transport = keyedTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.APINEX.name) },
        isConfigured = { keyVault.isPresent(ProviderId.APINEX.name) },
        usageRecorder = { chars ->
            providerConfigStore.addUsageChars(ProviderId.APINEX, chars)
        },
        apiBase = app.retranslate.providers.key.ApinexGateway.API_BASE,
        providerId = ProviderId.APINEX,
        providerDisplayName = app.retranslate.providers.key.ApinexGateway.DISPLAY_NAME,
        modelPreference = {
            providerConfigStore.apinexModel()
                ?: app.retranslate.providers.key.ApinexGateway.DEFAULT_FREE_MODEL
        },
        testViaModelsList = true
    )

    /** §K: the six user-added gateway slots, quick-text budget. */
    val gatewayProviders: List<app.retranslate.providers.gateway.GatewaySlotProvider> =
        app.retranslate.providers.gateway.GatewayConfig.slotKeys().map { slot ->
            app.retranslate.providers.gateway.GatewaySlotProvider(
                slot = slot,
                configSource = providerConfigStore,
                transport = keyedTransport,
                health = providerHealth,
                keyProvider = { entry -> keyVault.get(entry) },
                keyPresent = { entry -> keyVault.isPresent(entry) },
                usageRecorder = { chars ->
                    providerConfigStore.addUsageChars(ProviderId.CUSTOM, chars)
                }
            )
        }

    /**
     * §H: the Offline provider (Google ML Kit) — on-device, no key,
     * no network. Reached ONLY by an explicit pick or an offline job
     * (§I: never silently substituted into the Automatic chains).
     */
    val offlineEngine =
        app.retranslate.providers.local.MlKitTranslateEngine()
    val offlineProvider = app.retranslate.providers.local.OfflineProvider(offlineEngine)

    /** Every provider the hub knows, keyed for the settings screens. */
    val providersById: Map<ProviderId, Provider> = listOf<Provider>(
        geminiProvider, deepLProvider, azureProvider,
        openAiProvider, claudeProvider, apinexProvider,
        mozhiProvider, lingvaProvider, simplyTranslateProvider,
        libreTranslateProvider, myMemoryProvider, ollamaProvider,
        offlineProvider
    ).associateBy { it.id }

    /** Providers that can prove a candidate key before it is saved (§9.3). */
    val keyTestables: Map<ProviderId, KeyTestable> = mapOf(
        ProviderId.GEMINI to geminiProvider,
        ProviderId.DEEPL to deepLProvider,
        ProviderId.AZURE to azureProvider,
        ProviderId.OPENAI to openAiProvider,
        ProviderId.CLAUDE to claudeProvider,
        ProviderId.APINEX to apinexProvider,
        ProviderId.LIBRETRANSLATE to libreTranslateProvider
    )

    /**
     * The cascading fallback chain (§4.4). Static list order is only the
     * final tiebreak: §4.4.1 tiers (via [ChainOrdering], driven by live
     * Gemini-key presence) group the chain, health orders within a tier.
     */
    val providerRouter = ProviderRouter(
        providers = listOf(
            geminiProvider,
            deepLProvider,
            azureProvider,
            openAiProvider,
            claudeProvider,
            apinexProvider,
            mozhiProvider,
            lingvaProvider,
            simplyTranslateProvider,
            libreTranslateProvider,
            myMemoryProvider,
            ollamaProvider
        ) + gatewayProviders,
        health = providerHealth,
        myMemoryBudgetAvailable = {
            providerConfigStore.myMemoryWordsUsedToday() < MyMemoryProvider.DAILY_WORD_BUDGET
        },
        tierOf = { provider ->
            ChainOrdering.tierOf(
                provider,
                geminiKeyPresent = keyVault.isPresent(ProviderId.GEMINI.name)
            )
        }
    )

    // ---- P4: chapter pipeline + jobs -----------------------------------

    /** Pacing value's single owner (§8.3); read by the shared pacer. */
    val jobPrefs = app.retranslate.data.prefs.JobPrefs(appContext.settingsDataStore)

    /** Anti-429 spacing shared by every chapter of every job (§5.5). */
    val requestPacer = app.retranslate.domain.translate.RequestPacer(
        minDelayMs = { jobPrefs.interCallDelayMs() }
    )

    /** §5.5 translation cache over the Room table P3 created. */
    val translationCache = app.retranslate.domain.translate.TranslationCache(
        database.cachedTranslationDao()
    )

    /**
     * The chapter-call HTTP stack (§4.5 AI budgets: 10s connect / 120s
     * read / 150s whole-call). Chapter-sized AI calls must not inherit
     * the quick-text 30s cap, so the keyed AI/classic providers get
     * chapter instances on this transport; health, keys and usage
     * recording are shared with the quick instances.
     */
    private val chapterTransport = OkHttpTransport(
        client = OkHttpTransport.appClient(
            connectTimeoutSec = 10,
            readTimeoutSec = 120,
            callTimeoutSec = 150
        ),
        providerTag = "chapter"
    )

    private val geminiChapterProvider = GeminiProvider(
        transport = chapterTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.GEMINI.name) },
        isConfigured = { keyVault.isPresent(ProviderId.GEMINI.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.GEMINI, chars) },
        modelPreference = { geminiModelPrefs.choice() },
        session = geminiSession
    )

    private val deepLChapterProvider = DeepLProvider(
        transport = chapterTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.DEEPL.name) },
        isConfigured = { keyVault.isPresent(ProviderId.DEEPL.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.DEEPL, chars) }
    )

    private val azureChapterProvider = AzureProvider(
        transport = chapterTransport,
        health = providerHealth,
        secretProvider = { keyVault.get(ProviderId.AZURE.name) },
        isConfigured = { keyVault.isPresent(ProviderId.AZURE.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.AZURE, chars) }
    )

    private val openAiChapterProvider = app.retranslate.providers.key.OpenAiProvider(
        transport = chapterTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.OPENAI.name) },
        isConfigured = { keyVault.isPresent(ProviderId.OPENAI.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.OPENAI, chars) }
    )

    private val claudeChapterProvider = app.retranslate.providers.key.ClaudeProvider(
        transport = chapterTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.CLAUDE.name) },
        isConfigured = { keyVault.isPresent(ProviderId.CLAUDE.name) },
        usageRecorder = { chars -> providerConfigStore.addUsageChars(ProviderId.CLAUDE, chars) }
    )

    /** v1.1.3 (§J): APInex on the chapter budget. */
    private val apinexChapterProvider = app.retranslate.providers.key.OpenAiProvider(
        transport = chapterTransport,
        health = providerHealth,
        keyProvider = { keyVault.get(ProviderId.APINEX.name) },
        isConfigured = { keyVault.isPresent(ProviderId.APINEX.name) },
        usageRecorder = { chars ->
            providerConfigStore.addUsageChars(ProviderId.APINEX, chars)
        },
        apiBase = app.retranslate.providers.key.ApinexGateway.API_BASE,
        providerId = ProviderId.APINEX,
        providerDisplayName = app.retranslate.providers.key.ApinexGateway.DISPLAY_NAME,
        modelPreference = {
            providerConfigStore.apinexModel()
                ?: app.retranslate.providers.key.ApinexGateway.DEFAULT_FREE_MODEL
        },
        testViaModelsList = true
    )

    /** v1.1.3 (§K): the gateway slots on the chapter budget. */
    private val gatewayChapterProviders:
        List<app.retranslate.providers.gateway.GatewaySlotProvider> =
        app.retranslate.providers.gateway.GatewayConfig.slotKeys().map { slot ->
            app.retranslate.providers.gateway.GatewaySlotProvider(
                slot = slot,
                configSource = providerConfigStore,
                transport = chapterTransport,
                health = providerHealth,
                keyProvider = { entry -> keyVault.get(entry) },
                keyPresent = { entry -> keyVault.isPresent(entry) },
                usageRecorder = { chars ->
                    providerConfigStore.addUsageChars(ProviderId.CUSTOM, chars)
                }
            )
        }

    /** "Configured" for any AI candidate, gateways included (§K). */
    private fun Provider.isAiConfigured(): Boolean =
        if (this is app.retranslate.providers.gateway.GatewaySlotProvider) {
            hasConfigAndKey()
        } else {
            keyVault.isPresent(id.name)
        }

    /** The first configured KEY_AI provider, Gemini first (§5.2/§5.6). */
    private fun firstConfiguredChapterAi(): Provider? =
        (listOf(
            geminiChapterProvider, openAiChapterProvider, claudeChapterProvider,
            apinexChapterProvider
        ) + gatewayChapterProviders).firstOrNull { it.isAiConfigured() }

    private fun firstConfiguredQuickAi(): Provider? =
        (listOf(
            geminiProvider, openAiProvider, claudeProvider, apinexProvider
        ) + gatewayProviders).firstOrNull { it.isAiConfigured() }

    /**
     * The chapter chain: same §4.4 construction as [providerRouter]
     * (same tiers, same health, same MyMemory budget) over the chapter
     * instances. Free providers are shared — they are stateless apart
     * from the shared health memory.
     */
    val chapterRouter = ProviderRouter(
        providers = listOf(
            geminiChapterProvider,
            deepLChapterProvider,
            azureChapterProvider,
            openAiChapterProvider,
            claudeChapterProvider,
            apinexChapterProvider,
            mozhiProvider,
            lingvaProvider,
            simplyTranslateProvider,
            libreTranslateProvider,
            myMemoryProvider,
            ollamaProvider
        ) + gatewayChapterProviders,
        health = providerHealth,
        myMemoryBudgetAvailable = {
            providerConfigStore.myMemoryWordsUsedToday() < MyMemoryProvider.DAILY_WORD_BUDGET
        },
        tierOf = { provider ->
            ChainOrdering.tierOf(
                provider,
                geminiKeyPresent = keyVault.isPresent(ProviderId.GEMINI.name)
            )
        }
    )

    /** §5 chapter translator: chunking, cache, verify, partial state. */
    val chapterTranslator = app.retranslate.domain.translate.ChapterTranslator(
        router = chapterRouter,
        chapterDao = database.chapterDao(),
        cache = translationCache,
        tx = RoomTxRunner(database),
        pacer = requestPacer
    )

    /** §9.3 Large text / paste-a-chapter: the chapter chain, one-off. */
    val largeTextTranslator = app.retranslate.domain.translate.LargeTextTranslator(
        router = chapterRouter,
        pacer = requestPacer
    )

    /** §7.4 job engine — the Worker is a thin adapter over this. */
    val bookTranslationEngine = app.retranslate.domain.jobs.BookTranslationEngine(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        jobRunDao = database.jobRunDao(),
        translator = chapterTranslator,
        tx = RoomTxRunner(database),
        // P5 (§6.4): every chapter call carries the book's KB context.
        kbContext = { book -> kbRepository.buildContext(book) },
        // P7 (§5.2 dual-model): the per-book polish hook runs the P6
        // polisher (verified, KB-aware) without the one-chapter Undo
        // snapshot. The lambda resolves chapterPolisher at call time
        // (declared in the P6 section below); its outcome — applied,
        // rejected, or failed — never changes the chapter's accounting.
        polishHook = { book, chapter ->
            chapterPolisher.polish(book, chapter, snapshotFirst = false)
            Unit
        }
    )

    val jobNotifications = app.retranslate.domain.jobs.JobNotifications(appContext)

    /** The shared WorkManager adapter (translate + study jobs, P4/P5). */
    val workEnqueuer = app.retranslate.domain.jobs.WorkManagerEnqueuer(appContext)

    /** §7.4 lifecycle decisions + the WorkManager adapter. */
    val jobScheduler = app.retranslate.domain.jobs.JobScheduler(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        jobRunDao = database.jobRunDao(),
        enqueuer = workEnqueuer,
        // v1.1.3 (§G): polish scopes read the chapter flags + KB version.
        flaggedChapterIds = { bookId -> chapterFlags.flagsFor(bookId).keys },
        activeKbVersion = { bookId -> kbRepository.activeKb(bookId)?.version }
    )

    // ---- v1.1.3 job engines: bulk Improve (§A2, below in P6) + Offline (§H)

    /** §H: a single-provider router over the on-device engine only. */
    private val offlineRouter = ProviderRouter(
        providers = listOf(offlineProvider),
        health = providerHealth,
        myMemoryBudgetAvailable = { true },
        tierOf = { 0 }
    )

    /** §H: chapter pipeline over Offline alone — same discipline. */
    val offlineChapterTranslator = app.retranslate.domain.translate.ChapterTranslator(
        router = offlineRouter,
        chapterDao = database.chapterDao(),
        cache = translationCache,
        tx = RoomTxRunner(database),
        pacer = requestPacer
    )

    /**
     * §H: whole-book offline jobs run the standard engine over the
     * offline translator (KB context still applies — name enforcement
     * is the KB block + verifier, which are provider-agnostic). No
     * polish hook: offline chapters are marked for a later AI Improve.
     */
    val offlineBookTranslationEngine =
        app.retranslate.domain.jobs.BookTranslationEngine(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            jobRunDao = database.jobRunDao(),
            translator = offlineChapterTranslator,
            tx = RoomTxRunner(database),
            kbContext = { book -> kbRepository.buildContext(book) }
        )

    /** Chapter-budget instances by id, for §I job picks. */
    private val chapterProvidersById: Map<ProviderId, Provider> = listOf<Provider>(
        geminiChapterProvider, deepLChapterProvider, azureChapterProvider,
        openAiChapterProvider, claudeChapterProvider, apinexChapterProvider,
        mozhiProvider, lingvaProvider, simplyTranslateProvider,
        libreTranslateProvider, myMemoryProvider, ollamaProvider,
        offlineProvider
    ).associateBy { it.id }

    /**
     * §I: the engine a job runs with. No pick (or an unknown one —
     * defensive) = the shared Automatic engine. A pick = an engine
     * whose ONLY provider is the picked one: its failure is the job's
     * failure, reported plainly — never a silent switch.
     */
    fun bookEngineForPick(pick: String?): app.retranslate.domain.jobs.BookTranslationEngine {
        if (pick.isNullOrBlank()) return bookTranslationEngine
        val provider = resolvePickProvider(pick) ?: return bookTranslationEngine
        val router = ProviderRouter(
            providers = listOf(provider),
            health = providerHealth,
            myMemoryBudgetAvailable = { true },
            tierOf = { 0 }
        )
        val translator = app.retranslate.domain.translate.ChapterTranslator(
            router = router,
            chapterDao = database.chapterDao(),
            cache = translationCache,
            tx = RoomTxRunner(database),
            pacer = requestPacer
        )
        return app.retranslate.domain.jobs.BookTranslationEngine(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            jobRunDao = database.jobRunDao(),
            translator = translator,
            tx = RoomTxRunner(database),
            kbContext = { book -> kbRepository.buildContext(book) }
        )
    }

    /** A §I pick string → the chapter-budget provider it names. */
    private fun resolvePickProvider(pick: String): Provider? {
        if (pick.startsWith("gw")) {
            val index = pick.removePrefix("gw").toIntOrNull() ?: return null
            return gatewayChapterProviders.getOrNull(index - 1)
        }
        val id = runCatching { ProviderId.valueOf(pick) }.getOrNull() ?: return null
        return chapterProvidersById[id]
    }

    /** §I: the quick-budget provider a pick names (Translate tab). */
    fun quickProviderForPick(pick: String): Provider? {
        if (pick.startsWith("gw")) {
            val index = pick.removePrefix("gw").toIntOrNull() ?: return null
            return gatewayProviders.getOrNull(index - 1)
        }
        val id = runCatching { ProviderId.valueOf(pick) }.getOrNull() ?: return null
        return providersById[id]
    }

    // ---- P5: Context-Learning (Knowledge Base, study, samples) ---------

    /** §6.3 KB store: versioned entries, injection, warning scans. */
    val kbRepository = app.retranslate.domain.kb.KbRepository(
        knowledgeBaseDao = database.knowledgeBaseDao(),
        chapterDao = database.chapterDao(),
        tx = RoomTxRunner(database)
    )

    /** §6.1: sample files (separate files) → parsed sample chapters. */
    val sampleImporter = app.retranslate.domain.kb.SampleImporter(
        importer = bookImporter,
        sampleDao = database.sampleDao(),
        tx = RoomTxRunner(database)
    )

    /**
     * §6.2 extraction over the chapter-budget Gemini instance. Study
     * NEVER routes through the fallback chain — a classic provider
     * cannot judge a novel (see StudyAi).
     */
    val studyAi = app.retranslate.domain.kb.GeminiStudyAi(
        gemini = geminiChapterProvider,
        configured = { keyVault.isPresent(ProviderId.GEMINI.name) }
    )

    val studyEngine = app.retranslate.domain.kb.StudyEngine(
        bookDao = database.bookDao(),
        sampleDao = database.sampleDao(),
        jobRunDao = database.jobRunDao(),
        kbRepository = kbRepository,
        ai = studyAi
    )

    val studyScheduler = app.retranslate.domain.kb.StudyScheduler(
        bookDao = database.bookDao(),
        jobRunDao = database.jobRunDao(),
        enqueuer = workEnqueuer
    )

    /** §6.3 "Re-apply names": preview/apply/undo over translated chapters. */
    val reapplyNames = app.retranslate.domain.kb.ReapplyNames(
        chapterDao = database.chapterDao(),
        bookFiles = bookFiles
    )

    // ---- P6: review & export -------------------------------------------

    /** §9.3 paragraph flags — a small JSON file in each book's folder. */
    val chapterFlags = app.retranslate.domain.export.ChapterFlags(bookFiles)

    /** §9.3 chapter-change snapshots: the one-chapter Undo. */
    val chapterSnapshots = app.retranslate.domain.translate.ChapterSnapshots(
        chapterDao = database.chapterDao(),
        bookFiles = bookFiles
    )

    /** §7.5 EPUB/TXT export: streaming writers over paged Room reads. */
    val bookExporter = app.retranslate.domain.export.BookExporter(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        jobRunDao = database.jobRunDao(),
        tempFiles = tempFiles
    )

    /**
     * §5.2 polish over the first configured KEY_AI chapter instance
     * (P7: Gemini, then OpenAI, then Claude). Like study, polish never
     * routes through the fallback chain (see PolishAi).
     */
    val polishAi = app.retranslate.domain.translate.ProviderPolishAi(
        aiOf = { firstConfiguredChapterAi() }
    )

    /** v1.1.3 (§G): per-chapter originals, archived before any improve. */
    val chapterOriginalArchive =
        app.retranslate.domain.translate.ChapterOriginalArchive(
            chapterDao = database.chapterDao(),
            bookFiles = bookFiles
        )

    /** §5.2 "Polish this chapter": verified editor pass + snapshot. */
    val chapterPolisher = app.retranslate.domain.translate.ChapterPolisher(
        chapterDao = database.chapterDao(),
        kbRepository = kbRepository,
        snapshots = chapterSnapshots,
        ai = polishAi,
        tx = RoomTxRunner(database),
        // v1.1.3 (§G): the archive above — written before any improve
        // touches a chapter, so bulk improve can never destroy an
        // original; and the shared pacer (§G quota discipline).
        archive = chapterOriginalArchive,
        pacer = requestPacer
    )

    /** v1.1.3 (§A2/§G): the bulk-Improve engine — polish-only loop. */
    val bookPolishEngine = app.retranslate.domain.jobs.BookPolishEngine(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        jobRunDao = database.jobRunDao(),
        polisher = chapterPolisher,
        tx = RoomTxRunner(database),
        flaggedChapterIds = { bookId -> chapterFlags.flagsFor(bookId).keys },
        activeKbVersion = { bookId -> kbRepository.activeKb(bookId)?.version }
    )

    /** v1.1.3 (§G): the free book-wide Consistency check. */
    val consistencyScanner = app.retranslate.domain.kb.ConsistencyScanner(
        chapterDao = database.chapterDao(),
        kbRepository = kbRepository
    )

    /** v1.1.3 (§E): save one result as a .txt/.epub file (ask-first). */
    val resultFileExporter = app.retranslate.domain.export.ResultFileExporter(tempFiles)

    /** v1.1.3 (§C): the standalone pasted-chapter flow's engine. */
    val chapterMakeBetter = app.retranslate.domain.translate.ChapterMakeBetter(
        largeText = largeTextTranslator,
        polishAi = polishAi,
        pacer = requestPacer
    )

    /** §9.3 settings side of backup — structurally unable to reach keys. */
    val settingsBackup = app.retranslate.data.prefs.DataStoreSettingsBackup(
        settingsStore = appContext.settingsDataStore,
        providerConfig = providerConfigStore
    )

    /** §9.3 backup & restore: books + KBs + settings, streamed records. */
    val backupManager = app.retranslate.domain.export.BackupManager(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        knowledgeBaseDao = database.knowledgeBaseDao(),
        tx = RoomTxRunner(database),
        settings = settingsBackup
    )

    // ---- P7: appearance, AI merge --------------------------------------

    /** §9.5 appearance state — MainActivity + the review screen reflect it. */
    val appearancePrefs = app.retranslate.data.prefs.AppearancePrefs(
        appContext.settingsDataStore
    )

    /** §5.6 merge toggle + daily cap (the count lives in ProviderConfigStore). */
    val mergePrefs = app.retranslate.data.prefs.MergePrefs(appContext.settingsDataStore)

    /** v1.1 provider picker: the Translate tab's persisted choice. */
    val providerChoicePrefs = app.retranslate.data.prefs.ProviderChoicePrefs(
        appContext.settingsDataStore
    )

    /** §5.6's judge: the first configured KEY_AI quick provider. */
    val mergeAi = app.retranslate.domain.translate.ProviderMergeAi(
        judgeOf = { firstConfiguredQuickAi() }
    )

    /**
     * §5.6 AI Compare & Merge over the quick instances, in the same
     * tier order as [providerRouter]; the soft cap counts in
     * ProviderConfigStore like every other budget (§5.5).
     */
    val mergeOrchestrator = app.retranslate.domain.translate.MergeOrchestrator(
        providers = listOf(
            geminiProvider, deepLProvider, azureProvider,
            openAiProvider, claudeProvider,
            mozhiProvider, lingvaProvider, simplyTranslateProvider,
            libreTranslateProvider, myMemoryProvider, ollamaProvider
        ),
        health = providerHealth,
        tierOf = { provider ->
            ChainOrdering.tierOf(
                provider,
                geminiKeyPresent = keyVault.isPresent(ProviderId.GEMINI.name)
            )
        },
        judge = mergeAi,
        mergesUsedToday = { providerConfigStore.mergesUsedToday() },
        dailyCap = { mergePrefs.dailyCap() },
        recordMergeUsed = { providerConfigStore.addMergeUsed() }
    )

    /** Any KEY_AI key at all — gates the merge switch's visibility (§5.6). */
    private fun anyAiKeyPresent(): Boolean =
        keyVault.isPresent(ProviderId.GEMINI.name) ||
            keyVault.isPresent(ProviderId.OPENAI.name) ||
            keyVault.isPresent(ProviderId.CLAUDE.name) ||
            // v1.1.3 (§J/§K): APInex and added gateways are AI too.
            keyVault.isPresent(ProviderId.APINEX.name) ||
            gatewayProviders.any { it.hasConfigAndKey() }

    fun translateViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            TranslateViewModel(
                router = providerRouter,
                savedLanguages = savedLanguages,
                largeTextTranslator = largeTextTranslator,
                mergeOrchestrator = mergeOrchestrator,
                mergePrefs = mergePrefs,
                mergesUsedToday = { providerConfigStore.mergesUsedToday() },
                aiKeyPresent = { anyAiKeyPresent() },
                speech = app.retranslate.domain.speech.AndroidSpeechController(appContext),
                // v1.1 picker: persisted choice + the shared status rows.
                providerChoicePrefs = providerChoicePrefs,
                pickerRows = {
                    app.retranslate.ui.settings.ProviderStatus.buildRows(
                        keyVault, providerConfigStore, providerHealth, providersById,
                        offlinePackCount = { offlineEngine.downloadedPairs().size }
                    )
                },
                // v1.1.3 (§C/§I/§K/§E): chapter flow, engine picks,
                // save-as-file, and the "Save this base" souvenir.
                makeBetter = chapterMakeBetter,
                providerForPick = { pick -> quickProviderForPick(pick) },
                gatewayPicks = {
                    providerConfigStore.gatewayConfigs().map { config ->
                        app.retranslate.ui.translate.GatewayPickOption(
                            pickKey = config.slot, name = config.name
                        )
                    }
                },
                resultExporter = resultFileExporter,
                souvenirSaver = object :
                    app.retranslate.ui.translate.ChapterSouvenirSaver {
                    override suspend fun save(
                        title: String,
                        sourceLang: String,
                        targetLang: String,
                        sourceText: String?,
                        translatedText: String,
                        drafts: List<app.retranslate.domain.kb.KbEntryDraft>
                    ): app.retranslate.core.error.AppResult<Unit> {
                        val created = bookRepository.createEmptyBook(
                            app.retranslate.domain.books.ImportChoices(
                                title = title,
                                author = null,
                                sourceLang = sourceLang,
                                targetLang = targetLang,
                                genrePresetId = "other",
                                qualityMode = "BALANCED"
                            )
                        )
                        val bookId = when (created) {
                            is app.retranslate.core.error.AppResult.Ok ->
                                created.value
                            is app.retranslate.core.error.AppResult.Err ->
                                return created
                        }
                        val added = bookRepository.addChapterWithTexts(
                            bookId = bookId,
                            title = title,
                            sourceText = sourceText,
                            translatedText = translatedText,
                            origin = app.retranslate.data.db.ChapterOrigin
                                .IMPORTED_TRANSLATION
                        )
                        if (added is app.retranslate.core.error.AppResult.Err) {
                            return added
                        }
                        if (drafts.isNotEmpty()) {
                            val seeded = kbRepository.createFromStudy(
                                bookId = bookId,
                                drafts = drafts,
                                sourceNote = "Saved from a pasted chapter's base"
                            )
                            if (seeded is app.retranslate.core.error.AppResult.Err) {
                                return seeded
                            }
                        }
                        return app.retranslate.core.error.AppResult.Ok(Unit)
                    }
                }
            )
        }
    }

    fun providersViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProvidersViewModel(
                keyVault, providerConfigStore, providerHealth, providersById,
                mergePrefs = mergePrefs,
                offlinePackCount = { offlineEngine.downloadedPairs().size },
                // v1.1.3 (§H/§J/§K): packs, gateway test-drive, allowance.
                offlineEngine = offlineEngine,
                gatewayProbe = app.retranslate.providers.gateway.GatewayProbe(
                    keyedTransport
                ),
                apinexAllowanceLine = {
                    app.retranslate.providers.key.ApinexAllowance(keyedTransport)
                        .allowanceLine(keyVault.get(ProviderId.APINEX.name))
                },
                onGatewaysChanged = {
                    gatewayProviders.forEach { it.refreshConfig() }
                    gatewayChapterProviders.forEach { it.refreshConfig() }
                }
            )
        }
    }

    fun ollamaSetupViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.settings.OllamaSetupViewModel(
                configStore = providerConfigStore,
                ollama = ollamaProvider
            )
        }
    }

    fun appearanceViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.settings.AppearanceViewModel(appearancePrefs)
        }
    }

    fun keyEntryViewModelFactory(id: ProviderId): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            KeyEntryViewModel(
                id, keyTestables.getValue(id), keyVault,
                // v1.1.2: only Gemini's key screen shows the model picker.
                geminiModelPrefs = if (id == ProviderId.GEMINI) geminiModelPrefs else null
            )
        }
    }

    fun instancesViewModelFactory(id: ProviderId): ViewModelProvider.Factory = viewModelFactory {
        initializer { InstancesViewModel(id, providerConfigStore) }
    }

    /** v1.1.3 (F24): the What's-new card's one-time memory. */
    val whatsNewPrefs = app.retranslate.data.prefs.WhatsNewPrefs(
        appContext.settingsDataStore
    )

    fun libraryViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            LibraryViewModel(
                bookRepository, bookImporter, importSessionStore,
                chapterDao = database.chapterDao(),
                // v1.1 (workstream E): shelf long-press removal cancels
                // the book's jobs and deletes its files too.
                jobScheduler = jobScheduler,
                bookFiles = bookFiles,
                // Named, not trailing: v1.1.3 added a param after it.
                describeFile = { uri -> pickedFiles.describe(uri) },
                whatsNewPrefs = whatsNewPrefs
            )
        }
    }

    fun importReviewViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { ImportReviewViewModel(importSessionStore, bookImporter, bookRepository) }
    }

    fun bookDetailViewModelFactory(bookId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            BookDetailViewModel(
                bookRepository, bookFiles, bookId,
                jobScheduler = jobScheduler,
                jobRunDao = database.jobRunDao(),
                kbRepository = kbRepository,
                bookExporter = bookExporter,
                chapterFlags = chapterFlags,
                chapterSnapshots = chapterSnapshots,
                bookImporter = bookImporter,
                // v1.1.3 (§A/§G): Improve with AI + Consistency check.
                polisher = chapterPolisher,
                originals = chapterOriginalArchive,
                consistencyScanner = consistencyScanner,
                aiConfigured = { polishAi.isConfigured() },
                chapterDao = database.chapterDao()
            )
        }
    }

    fun chapterReviewViewModelFactory(
        bookId: Long,
        chapterId: Long
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.review.ChapterReviewViewModel(
                repository = bookRepository,
                chapterDao = database.chapterDao(),
                kbRepository = kbRepository,
                chapterFlags = chapterFlags,
                polisher = chapterPolisher,
                chapterTranslator = chapterTranslator,
                snapshots = chapterSnapshots,
                // v1.1.3 (§A): Improve works with ANY configured AI
                // (Gemini, OpenAI, Claude, APInex, added gateways) —
                // the parameter keeps its v1.1.2 name for the tests.
                geminiKeyPresent = { polishAi.isConfigured() },
                originals = chapterOriginalArchive,
                bookId = bookId,
                chapterId = chapterId,
                // P7 (§9.3/§9.5): Listen + the review theme/Aa state.
                speech = app.retranslate.domain.speech.AndroidSpeechController(appContext),
                appearancePrefs = appearancePrefs
            )
        }
    }

    fun backupViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.more.BackupViewModel(backupManager, tempFiles)
        }
    }

    fun studySetupViewModelFactory(bookId: Long): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.kb.StudySetupViewModel(
                bookId = bookId,
                repository = bookRepository,
                sampleDao = database.sampleDao(),
                sampleImporter = sampleImporter,
                studyScheduler = studyScheduler,
                jobScheduler = jobScheduler,
                jobRunDao = database.jobRunDao(),
                geminiKeyPresent = { keyVault.isPresent(ProviderId.GEMINI.name) },
                describeFile = { uri -> pickedFiles.describe(uri) }
            )
        }
    }

    fun knowledgeBaseViewModelFactory(bookId: Long): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                app.retranslate.ui.kb.KnowledgeBaseViewModel(
                    bookId = bookId,
                    repository = bookRepository,
                    kbRepository = kbRepository,
                    reapplyNames = reapplyNames
                )
            }
        }

    fun jobsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.retranslate.ui.jobs.JobsViewModel(
                jobRunDao = database.jobRunDao(),
                bookDao = database.bookDao(),
                scheduler = jobScheduler
            )
        }
    }

    init {
        // PDFBox-Android needs its resources registered once (§2.4); a
        // failure here must not take the app down — PDF import would
        // report its own plain error instead (§10).
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext)
        } catch (t: Throwable) {
            // Ignored on purpose: extraction surfaces typed errors.
        }
        // §7.5 maintenance: sweep temp files a crashed import left behind.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            runCatching { tempFiles.cleanupStale(System.currentTimeMillis()) }
            // §4.3 (P7): wake Ollama if the user configured it earlier —
            // its router-visible config is a cache this refresh fills.
            runCatching { ollamaProvider.refreshConfig() }
            // v1.1.3 (§K): same wake-up for the added-gateway slots —
            // both budgets hold a per-slot config cache.
            gatewayProviders.forEach { slot ->
                runCatching { slot.refreshConfig() }
            }
            gatewayChapterProviders.forEach { slot ->
                runCatching { slot.refreshConfig() }
            }
        }    }
}

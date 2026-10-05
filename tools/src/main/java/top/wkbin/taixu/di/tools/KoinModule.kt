package top.wkbin.taixu.di.tools

import org.koin.dsl.module
import top.wkbin.taixu.core.tools.ProviderManager
import top.wkbin.taixu.core.tools.AgentModelConnectionTester
import top.wkbin.taixu.core.tools.AgentModelDiscovery
import top.wkbin.taixu.core.tools.AgentProviderCatalog
import top.wkbin.taixu.core.tools.AiProfileBackupCodec
import top.wkbin.taixu.core.tools.AiProfileWriter
import top.wkbin.taixu.core.tools.DependencyManagerImpl
import top.wkbin.taixu.core.tools.DependencyResolver
import top.wkbin.taixu.core.tools.FlutterSdkDownloader
import top.wkbin.taixu.core.tools.InstallLogRepository
import top.wkbin.taixu.core.tools.InstallTaskRepository
import top.wkbin.taixu.core.tools.InstallTransactionManager
import top.wkbin.taixu.core.tools.LocalPluginPayloadManager
import top.wkbin.taixu.core.tools.ProviderRepository
import top.wkbin.taixu.core.tools.RuntimeManagerImpl
import top.wkbin.taixu.core.tools.RuntimeRepository
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.ToolNotificationNotifier
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.core.tools.ToolRepository
import top.wkbin.taixu.core.tools.ToolServiceController
import top.wkbin.taixu.runtime.tools.AuthUrlDetector
import top.wkbin.taixu.runtime.tools.CodexToolInstaller
import top.wkbin.taixu.runtime.tools.HelloToolInstaller
import top.wkbin.taixu.runtime.tools.RemoteScriptRunner
import top.wkbin.taixu.runtime.tools.RuntimeBinaryInstaller
import top.wkbin.taixu.runtime.tools.ToolCommandLinker
import org.koin.core.qualifier.named

/** Dependency registrations owned by the tools module. */
val toolsModule = module {
    single<ProviderManager> { ProviderManager(providerRepository = get()) }

    single<AgentModelConnectionTester> { AgentModelConnectionTester(http = get()) }

    single<AgentModelDiscovery> { AgentModelDiscovery(http = get()) }

    single<AgentProviderCatalog> { AgentProviderCatalog(context = get()) }

    single<AiProfileBackupCodec> {
        AiProfileBackupCodec(
            aiModelDao = get(),
            providerRepository = get(),
            profileWriter = get(),
        )
    }

    single<AiProfileWriter> { AiProfileWriter(aiModelDao = get(), providerRepository = get()) }

    single<DependencyManagerImpl> { DependencyManagerImpl(resolver = get(), runtimeManager = get()) }

    single<DependencyResolver> { DependencyResolver() }

    single<FlutterSdkDownloader> {
        FlutterSdkDownloader(
            fileDownloader = get(),
            checksumVerifier = get(),
            pathManager = get(),
            json = get(),
        )
    }

    single<InstallLogRepository> { InstallLogRepository(dao = get()) }

    single<InstallTaskRepository> { InstallTaskRepository(dao = get()) }

    single<InstallTransactionManager> { InstallTransactionManager(pathManager = get(), logger = get()) }

    single<LocalPluginPayloadManager> { LocalPluginPayloadManager(registry = get(), pathManager = get()) }

    single<ProviderRepository> { ProviderRepository(providerPreferences = get()) }

    single<RuntimeManagerImpl> {
        RuntimeManagerImpl(
            linuxRuntime = get(),
            runtimeRepository = get(),
            runtimeBinaryInstaller = get(),
        )
    }

    single<RuntimeRepository> { RuntimeRepository(runtimeDao = get()) }

    single<ToolManager> {
        ToolManager(
            toolRepository = get(),
            installLogRepository = get(),
            installTaskRepository = get(),
            installTransactionManager = get(),
            dependencyManager = get(),
            linuxRuntime = get(),
            backgroundTaskRegistry = get(),
            providerManager = get(),
            toolCommandLinker = get(),
            notificationNotifier = get(),
            secretRedactor = get(),
            toolSettingsRepository = get(),
            settingsDataStore = get(),
            assetSynchronizer = get(),
            flutterSdkDownloader = get(),
            localPluginPayloadManager = get(),
            serviceController = get(),
            installerAdapters = get(named("toolAdapters")),
        )
    }

    single<ToolNotificationNotifier> { ToolNotificationNotifier(context = get()) }

    single<ToolRegistry> {
        ToolRegistry(
            context = get(),
            httpClient = get(),
            logger = get(),
        )
    }

    single<ToolRepository> { ToolRepository(toolDao = get(), toolRegistry = get()) }

    single<ToolServiceController> { ToolServiceController(linuxRuntime = get()) }

    single<AuthUrlDetector> { AuthUrlDetector() }

    single<CodexToolInstaller> {
        CodexToolInstaller(
            linuxRuntime = get(),
            dependencyManager = get(),
            providerManager = get(),
            remoteScriptRunner = get(),
            toolCommandLinker = get(),
        )
    }

    single<HelloToolInstaller> { HelloToolInstaller(linuxRuntime = get(), pathManager = get()) }

    single<RemoteScriptRunner> { RemoteScriptRunner(linuxRuntime = get()) }

    single<RuntimeBinaryInstaller> {
        RuntimeBinaryInstaller(
            pathManager = get(),
            linuxRuntime = get(),
            fileDownloader = get(),
            checksumVerifier = get(),
            tarStreamExtractor = get(),
        )
    }

    single<ToolCommandLinker> { ToolCommandLinker(linuxRuntime = get()) }

    single { top.wkbin.taixu.core.tools.skill.SkillPackageParser() }

    single { top.wkbin.taixu.core.tools.skill.SkillPackageInspector() }

    single { top.wkbin.taixu.core.tools.skill.SkillCompatibilityEvaluator(toolRegistry = getOrNull()) }

    single { top.wkbin.taixu.core.tools.skill.ClawHubClient(httpClient = get()) }

    single {
        top.wkbin.taixu.core.tools.skill.SkillInstallationManager(
            packageParser = get(),
            inspector = get(),
            compatibilityEvaluator = get(),
            clawHubClient = get(),
            agentSkillRepository = get(),
        )
    }
}

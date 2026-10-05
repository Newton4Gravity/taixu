package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.model.InstalledRuntime
import top.wkbin.taixu.core.model.RuntimeRequirement
import top.wkbin.taixu.core.model.ToolManifest

/** Owns dependency resolution at the Tool boundary; adapters do not access RuntimeManager directly. */
interface DependencyManager {
    fun requirements(manifest: ToolManifest): List<RuntimeRequirement>

    suspend fun acquire(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime>

    suspend fun release(runtimeId: String, toolId: String): AppResult<Unit>
}

class DependencyManagerImpl(
    private val resolver: DependencyResolver,
    private val runtimeManager: RuntimeManager,
) : DependencyManager {
    override fun requirements(manifest: ToolManifest): List<RuntimeRequirement> =
        resolver.resolve(manifest)

    override suspend fun acquire(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime> = runtimeManager.acquire(requirement, toolId)

    override suspend fun release(runtimeId: String, toolId: String): AppResult<Unit> =
        runtimeManager.release(runtimeId, toolId)
}

package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.database.ToolDao
import top.wkbin.taixu.core.database.ToolEntity
import top.wkbin.taixu.core.model.ToolManifest
import android.net.Uri
import top.wkbin.taixu.core.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Tool persistence and registry boundary; UI layers do not access Room directly. */
class ToolRepository(
    private val toolDao: ToolDao,
    private val toolRegistry: ToolRegistry,
) {
    fun observeTools(distroId: String): Flow<List<ToolEntity>> = toolDao.observeForDistro(distroId)
    suspend fun getForDistro(distroId: String): List<ToolEntity> = toolDao.getForDistro(distroId)
    suspend fun findById(distroId: String, id: String): ToolEntity? = toolDao.findById(distroId, id)
    suspend fun upsert(tool: ToolEntity) = toolDao.upsert(tool)
    suspend fun updateState(distroId: String, id: String, state: String) =
        toolDao.updateState(distroId, id, state)
    suspend fun updateStateAndInstalledVersion(distroId: String, id: String, state: String, installedVersion: String?) =
        toolDao.updateStateAndInstalledVersion(distroId, id, state, installedVersion)
    suspend fun deleteByDistro(distroId: String) = toolDao.deleteByDistro(distroId)
    suspend fun deleteByIds(ids: Collection<String>) = toolDao.deleteByIds(ids)
    fun manifests(): List<ToolManifest> = toolRegistry.load()
    fun manifest(id: String): ToolManifest? = manifests().firstOrNull { it.id == id }
    suspend fun importLocal(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> = toolRegistry.importLocal(uri, onProgress)
    suspend fun inspectLocal(uri: Uri): AppResult<LocalPluginPreview> = toolRegistry.inspectLocal(uri)
}

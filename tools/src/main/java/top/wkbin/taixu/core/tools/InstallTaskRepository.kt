package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.database.InstallTaskDao
import top.wkbin.taixu.core.database.InstallTaskEntity

/** Persistence boundary for durable install transactions. */
class InstallTaskRepository(
    private val dao: InstallTaskDao,
) {
    suspend fun upsert(task: InstallTaskEntity) = dao.upsert(task)
    suspend fun findByTool(distroId: String, toolId: String): InstallTaskEntity? = dao.findByTool(distroId, toolId)
    suspend fun listByState(state: String): List<InstallTaskEntity> = dao.listByState(state)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}


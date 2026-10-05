package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tools: List<ToolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tool: ToolEntity)

    @Update
    suspend fun update(tool: ToolEntity)

    @Delete
    suspend fun delete(tool: ToolEntity)

    @Query("DELETE FROM tools WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM tools WHERE distroId = :distroId ORDER BY name ASC")
    fun observeTools(distroId: String): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE distroId = :distroId AND id = :toolId")
    suspend fun findById(distroId: String, toolId: String): ToolEntity?

    @Query("SELECT * FROM tools WHERE distroId = :distroId AND state = :state")
    suspend fun findByState(distroId: String, state: String): List<ToolEntity>

    @Query("UPDATE tools SET state = :state, updatedAt = :updatedAt WHERE distroId = :distroId AND id = :toolId")
    suspend fun updateState(distroId: String, toolId: String, state: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE tools SET state = :state, installedVersion = :version, updatedAt = :updatedAt WHERE distroId = :distroId AND id = :toolId")
    suspend fun updateStateAndInstalledVersion(distroId: String, toolId: String, state: String, installedVersion: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tools WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)

    @Query("SELECT * FROM tools WHERE distroId = :distroId")
    suspend fun getForDistro(distroId: String): List<ToolEntity>
}

@Dao
interface InstallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: InstallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<InstallLogEntity>)

    @Query("SELECT * FROM install_logs WHERE distroId = :distroId AND toolId = :toolId ORDER BY timestamp DESC")
    fun observeForTool(distroId: String, toolId: String): Flow<List<InstallLogEntity>>

    @Query("DELETE FROM install_logs WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun deleteForTool(distroId: String, toolId: String)

    @Query("DELETE FROM install_logs WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)

    @Query("SELECT * FROM install_logs WHERE distroId = :distroId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(distroId: String, limit: Int = 100): List<InstallLogEntity>
}

@Dao
interface InstallTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: InstallTaskEntity)

    @Query("SELECT * FROM install_tasks WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun findByTool(distroId: String, toolId: String): InstallTaskEntity?

    @Query("SELECT * FROM install_tasks WHERE state = :state")
    suspend fun listByState(state: String): List<InstallTaskEntity>

    @Query("DELETE FROM install_tasks WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun delete(distroId: String, toolId: String)

    @Query("DELETE FROM install_tasks WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

@Dao
interface RuntimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRuntime(entity: RuntimeEntity)

    @Query("SELECT * FROM runtimes WHERE id = :id")
    suspend fun findRuntime(id: String): RuntimeEntity?

    @Query("SELECT * FROM runtimes WHERE state = 'INSTALLED'")
    suspend fun listInstalledRuntimes(): List<RuntimeEntity>

    @Query("DELETE FROM runtimes WHERE id = :id")
    suspend fun deleteRuntime(id: String)

    @Query("SELECT COUNT(*) FROM runtime_references WHERE runtimeId = :runtimeId")
    suspend fun referenceCount(runtimeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addReference(ref: RuntimeReferenceEntity)

    @Query("DELETE FROM runtime_references WHERE runtimeId = :runtimeId AND toolId = :toolId")
    suspend fun removeReference(runtimeId: String, toolId: String)
}

@Dao
interface StorageMountBindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bindings: List<StorageMountBindingEntity>)

    @Query("SELECT * FROM storage_mount_bindings WHERE distroId = :distroId AND enabled = 1")
    fun observeBindings(distroId: String): Flow<List<StorageMountBindingEntity>>

    @Query("DELETE FROM storage_mount_bindings WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

@Dao
interface WorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun findById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BuildScriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: BuildScriptEntity)

    @Query("SELECT * FROM build_scripts WHERE enabled = 1 ORDER BY category, name")
    fun observeEnabled(): Flow<List<BuildScriptEntity>>

    @Query("SELECT * FROM build_scripts WHERE id = :id")
    suspend fun findById(id: String): BuildScriptEntity?
}

@Dao
interface TerminalSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TerminalSessionEntity)

    @Query("SELECT * FROM terminal_sessions WHERE distroId = :distroId ORDER BY updatedAt DESC")
    fun observeByDistro(distroId: String): Flow<List<TerminalSessionEntity>>

    @Query("SELECT * FROM terminal_sessions WHERE id = :id")
    suspend fun findById(id: String): TerminalSessionEntity?

    @Query("DELETE FROM terminal_sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ToolSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ToolSettingsEntity)

    @Query("SELECT * FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun find(distroId: String, toolId: String): ToolSettingsEntity?

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun delete(distroId: String, toolId: String)

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

@Dao
interface PluginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plugin: PluginEntity)

    @Query("SELECT * FROM plugins ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE id = :id")
    suspend fun findById(id: String): PluginEntity?

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun findById(id: String): SkillEntity?

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)
}
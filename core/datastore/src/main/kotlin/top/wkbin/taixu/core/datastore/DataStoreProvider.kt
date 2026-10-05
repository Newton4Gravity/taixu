package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * DataStore provider for type-safe preference access.
 */
class DataStoreProvider(private val context: Context) {

    private val dataStore = context.preferencesDataStore("taixu_preferences")

    val preferencesFlow: Flow<Preferences> = dataStore.data

    // Runtime preferences
    val selectedDistribution: Flow<String> = preferencesFlow.map { it.getString(RuntimePreferences.selectedDistribution) ?: RuntimePreferences.DEFAULT_DISTRIBUTION }
    val qemuCompatibilityEnabled: Flow<Boolean> = preferencesFlow.map { it.getBoolean(RuntimePreferences.qemuCompatibilityEnabled) ?: RuntimePreferences.DEFAULT_QEMU_ENABLED }
    val mountSharedStorageEnabled: Flow<Boolean> = preferencesFlow.map { it.getBoolean(RuntimePreferences.mountSharedStorageEnabled) ?: RuntimePreferences.DEFAULT_MOUNT_SHARED }
    val mountDownloadEnabled: Flow<Boolean> = preferencesFlow.map { it.getBoolean(RuntimePreferences.mountDownloadEnabled) ?: RuntimePreferences.DEFAULT_MOUNT_DOWNLOAD }
    val mountDocumentsEnabled: Flow<Boolean> = preferencesFlow.map { it.getBoolean(RuntimePreferences.mountDocumentsEnabled) ?: RuntimePreferences.DEFAULT_MOUNT_DOCUMENTS }
    val autoStartRuntime: Flow<Boolean> = preferencesFlow.map { it.getBoolean(RuntimePreferences.autoStartRuntime) ?: RuntimePreferences.DEFAULT_AUTO_START }
    val preferredRegistryRoute: Flow<String> = preferencesFlow.map { it.getString(RuntimePreferences.preferredRegistryRoute) ?: RuntimePreferences.DEFAULT_REGISTRY_ROUTE }

    suspend fun setSelectedDistribution(value: String) {
        dataStore.edit { it.putString(RuntimePreferences.selectedDistribution, value) }
    }

    suspend fun setQemuCompatibilityEnabled(value: Boolean) {
        dataStore.edit { it.putBoolean(RuntimePreferences.qemuCompatibilityEnabled, value) }
    }

    suspend fun setMountSharedStorageEnabled(value: Boolean) {
        dataStore.edit { it.putBoolean(RuntimePreferences.mountSharedStorageEnabled, value) }
    }

    suspend fun setMountDownloadEnabled(value: Boolean) {
        dataStore.edit { it.putBoolean(RuntimePreferences.mountDownloadEnabled, value) }
    }

    suspend fun setMountDocumentsEnabled(value: Boolean) {
        dataStore.edit { it.putBoolean(RuntimePreferences.mountDocumentsEnabled, value) }
    }

    suspend fun setAutoStartRuntime(value: Boolean) {
        dataStore.edit { it.putBoolean(RuntimePreferences.autoStartRuntime, value) }
    }

    suspend fun setPreferredRegistryRoute(value: String) {
        dataStore.edit { it.putString(RuntimePreferences.preferredRegistryRoute, value) }
    }

    // Tool preferences
    suspend fun setToolAccessToken(distroId: String, toolId: String, token: String?) {
        val key = "${ToolPreferences.toolAccessTokenPrefix}${distroId}_$toolId"
        dataStore.edit { prefs ->
            token?.let { prefs.putString(key, it) } ?: prefs.remove(key)
        }
    }

    suspend fun getToolAccessToken(distroId: String, toolId: String): String? {
        val key = "${ToolPreferences.toolAccessTokenPrefix}${distroId}_$toolId"
        return dataStore.data.first().getString(key)
    }

    suspend fun setToolConfig(distroId: String, toolId: String, config: String?) {
        val key = "${ToolPreferences.toolConfigPrefix}${distroId}_$toolId"
        dataStore.edit { prefs ->
            config?.let { prefs.putString(key, it) } ?: prefs.remove(key)
        }
    }

    suspend fun getToolConfig(distroId: String, toolId: String): String? {
        val key = "${ToolPreferences.toolConfigPrefix}${distroId}_$toolId"
        return dataStore.data.first().getString(key)
    }
}
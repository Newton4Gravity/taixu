package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesKeys
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore preferences keys for runtime configuration.
 */
object RuntimePreferences {
    private val selectedDistribution = PreferencesKeys.stringKey("selected_distribution")
    private val qemuCompatibilityEnabled = PreferencesKeys.booleanKey("qemu_compatibility_enabled")
    private val mountSharedStorageEnabled = PreferencesKeys.booleanKey("mount_shared_storage_enabled")
    private val mountDownloadEnabled = PreferencesKeys.booleanKey("mount_download_enabled")
    private val mountDocumentsEnabled = PreferencesKeys.booleanKey("mount_documents_enabled")
    private val autoStartRuntime = PreferencesKeys.booleanKey("auto_start_runtime")
    private val preferredRegistryRoute = PreferencesKeys.stringKey("preferred_registry_route")

    // Default values
    const val DEFAULT_DISTRIBUTION = "ubuntu"
    const val DEFAULT_QEMU_ENABLED = false
    const val DEFAULT_MOUNT_SHARED = true
    const val DEFAULT_MOUNT_DOWNLOAD = false
    const val DEFAULT_MOUNT_DOCUMENTS = false
    const val DEFAULT_AUTO_START = false
    const val DEFAULT_REGISTRY_ROUTE = "auto"

    suspend fun Preferences.getSelectedDistribution(): String =
        getString(selectedDistribution) ?: DEFAULT_DISTRIBUTION

    suspend fun Preferences.setSelectedDistribution(value: String) {
        edit { putString(selectedDistribution, value) }
    }

    val selectedDistributionFlow: (Flow<Preferences>) -> Flow<String> = { prefsFlow ->
        prefsFlow.map { it.getString(selectedDistribution) ?: DEFAULT_DISTRIBUTION }
    }

    suspend fun Preferences.getQemuCompatibilityEnabled(): Boolean =
        getBoolean(qemuCompatibilityEnabled) ?: DEFAULT_QEMU_ENABLED

    suspend fun Preferences.setQemuCompatibilityEnabled(value: Boolean) {
        edit { putBoolean(qemuCompatibilityEnabled, value) }
    }

    val qemuCompatibilityEnabledFlow: (Flow<Preferences>) -> Flow<Boolean> = { prefsFlow ->
        prefsFlow.map { it.getBoolean(qemuCompatibilityEnabled) ?: DEFAULT_QEMU_ENABLED }
    }

    suspend fun Preferences.getMountSharedStorageEnabled(): Boolean =
        getBoolean(mountSharedStorageEnabled) ?: DEFAULT_MOUNT_SHARED

    suspend fun Preferences.setMountSharedStorageEnabled(value: Boolean) {
        edit { putBoolean(mountSharedStorageEnabled, value) }
    }

    val mountSharedStorageEnabledFlow: (Flow<Preferences>) -> Flow<Boolean> = { prefsFlow ->
        prefsFlow.map { it.getBoolean(mountSharedStorageEnabled) ?: DEFAULT_MOUNT_SHARED }
    }

    suspend fun Preferences.getMountDownloadEnabled(): Boolean =
        getBoolean(mountDownloadEnabled) ?: DEFAULT_MOUNT_DOWNLOAD

    suspend fun Preferences.setMountDownloadEnabled(value: Boolean) {
        edit { putBoolean(mountDownloadEnabled, value) }
    }

    suspend fun Preferences.getMountDocumentsEnabled(): Boolean =
        getBoolean(mountDocumentsEnabled) ?: DEFAULT_MOUNT_DOCUMENTS

    suspend fun Preferences.setMountDocumentsEnabled(value: Boolean) {
        edit { putBoolean(mountDocumentsEnabled, value) }
    }

    suspend fun Preferences.getAutoStartRuntime(): Boolean =
        getBoolean(autoStartRuntime) ?: DEFAULT_AUTO_START

    suspend fun Preferences.setAutoStartRuntime(value: Boolean) {
        edit { putBoolean(autoStartRuntime, value) }
    }

    suspend fun Preferences.getPreferredRegistryRoute(): String =
        getString(preferredRegistryRoute) ?: DEFAULT_REGISTRY_ROUTE

    suspend fun Preferences.setPreferredRegistryRoute(value: String) {
        edit { putString(preferredRegistryRoute, value) }
    }
}

/**
 * DataStore preferences keys for tool configuration.
 */
object ToolPreferences {
    private val toolAccessTokenPrefix = "tool_access_token_"
    private val toolConfigPrefix = "tool_config_"

    fun Preferences.getToolAccessToken(distroId: String, toolId: String): String? =
        getString(PreferencesKeys.stringKey("${toolAccessTokenPrefix}${distroId}_$toolId"))

    suspend fun Preferences.setToolAccessToken(distroId: String, toolId: String, token: String?) {
        edit {
            val key = PreferencesKeys.stringKey("${toolAccessTokenPrefix}${distroId}_$toolId")
            token?.let { putString(key, it) } ?: remove(key)
        }
    }

    fun Preferences.getToolConfig(distroId: String, toolId: String): String? =
        getString(PreferencesKeys.stringKey("${toolConfigPrefix}${distroId}_$toolId"))

    suspend fun Preferences.setToolConfig(distroId: String, toolId: String, config: String?) {
        edit {
            val key = PreferencesKeys.stringKey("${toolConfigPrefix}${distroId}_$toolId")
            config?.let { putString(key, it) } ?: remove(key)
        }
    }
}

/**
 * SSH preferences for remote access.
 */
object SshPreferences {
    private val sshEnabled = PreferencesKeys.booleanKey("ssh_enabled")
    private val sshPort = PreferencesKeys.intKey("ssh_port")
    private val sshPasswordAuth = PreferencesKeys.booleanKey("ssh_password_auth")
    private val sshPublicKey = PreferencesKeys.stringKey("ssh_public_key")

    const val DEFAULT_PORT = 2222

    suspend fun Preferences.getSshEnabled(): Boolean = getBoolean(sshEnabled) ?: false
    suspend fun Preferences.setSshEnabled(value: Boolean) { edit { putBoolean(sshEnabled, value) } }
    suspend fun Preferences.getSshPort(): Int = getInt(sshPort) ?: DEFAULT_PORT
    suspend fun Preferences.setSshPort(value: Int) { edit { putInt(sshPort, value) } }
    suspend fun Preferences.getSshPasswordAuth(): Boolean = getBoolean(sshPasswordAuth) ?: false
    suspend fun Preferences.setSshPasswordAuth(value: Boolean) { edit { putBoolean(sshPasswordAuth, value) } }
    suspend fun Preferences.getSshPublicKey(): String? = getString(sshPublicKey)
    suspend fun Preferences.setSshPublicKey(value: String?) {
        edit { value?.let { putString(sshPublicKey, it) } ?: remove(sshPublicKey) }
    }
}

/**
 * Workshop preferences for build configuration.
 */
object WorkshopPreferences {
    private val keystorePath = PreferencesKeys.stringKey("workshop_keystore_path")
    private val keystorePassword = PreferencesKeys.stringKey("workshop_keystore_password")
    private val keyAlias = PreferencesKeys.stringKey("workshop_key_alias")
    private val keyPassword = PreferencesKeys.stringKey("workshop_key_password")
    private val signingEnabled = PreferencesKeys.booleanKey("workshop_signing_enabled")

    suspend fun Preferences.getKeystorePath(): String? = getString(keystorePath)
    suspend fun Preferences.setKeystorePath(value: String?) {
        edit { value?.let { putString(keystorePath, it) } ?: remove(keystorePath) }
    }
    suspend fun Preferences.getKeystorePassword(): String? = getString(keystorePassword)
    suspend fun Preferences.setKeystorePassword(value: String?) {
        edit { value?.let { putString(keystorePassword, it) } ?: remove(keystorePassword) }
    }
    suspend fun Preferences.getKeyAlias(): String? = getString(keyAlias)
    suspend fun Preferences.setKeyAlias(value: String?) {
        edit { value?.let { putString(keyAlias, it) } ?: remove(keyAlias) }
    }
    suspend fun Preferences.getKeyPassword(): String? = getString(keyPassword)
    suspend fun Preferences.setKeyPassword(value: String?) {
        edit { value?.let { putString(keyPassword, it) } ?: remove(keyPassword) }
    }
    suspend fun Preferences.getSigningEnabled(): Boolean = getBoolean(signingEnabled) ?: false
    suspend fun Preferences.setSigningEnabled(value: Boolean) { edit { putBoolean(signingEnabled, value) } }
}
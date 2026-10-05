package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.model.ToolManifest

/**
 * Validates the data-only part of a registry before it can affect the app.
 * Manifests never carry executable shell commands; adapters remain allow-listed
 * in the app and are selected by tool id.
 */
object ToolManifestValidator {
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{1,63}")
    private val allowedDependencies = setOf("node", "python", "git", "curl", "ca-certificates")
    private val allowedLaunchTypes = setOf("one_shot", "pty", "web", "service", "command")
    private val allowedPermissions = setOf("NETWORK", "WORKSPACE_READ", "WORKSPACE_WRITE", "LOCAL_WEB")
    private val allowedUpdateStrategies = setOf("REINSTALL", "IN_PLACE")
    private val allowedInstallMethods = setOf("SCRIPT", "LOCAL_PACKAGE")

    fun validateAll(manifests: List<ToolManifest>): List<ToolManifest> {
        require(manifests.isNotEmpty()) { "Tool manifest list cannot be empty" }
        manifests.forEach { manifest ->
            require(manifest.schemaVersion == 1) { "Unsupported tool manifest schema: ${manifest.schemaVersion}" }
            require(idPattern.matches(manifest.id)) { "Illegal tool ID: ${manifest.id}" }
            require(manifest.name.isNotBlank()) { "Tool name cannot be empty: ${manifest.id}" }
            require(manifest.description.isNotBlank()) { "Tool description cannot be empty: ${manifest.id}" }
            require(manifest.publisher.length <= 128) { "Tool publisher name too long: ${manifest.id}" }
            require(manifest.version.isNotBlank()) { "Tool version cannot be empty: ${manifest.id}" }
            val latest = manifest.latestVersion
            require(latest == null || latest.isNotBlank()) {
                "Tool latest version cannot be empty: ${manifest.id}"
            }
            require(manifest.architectures.any { it.equals("ARM64", ignoreCase = true) }) {
                "Tool does not support ARM64: ${manifest.id}"
            }
            require(manifest.launchType in allowedLaunchTypes) {
                "Unsupported launch type: ${manifest.launchType}"
            }
            require(manifest.updateStrategy in allowedUpdateStrategies) {
                "Unsupported update strategy: ${manifest.updateStrategy}"
            }
            require(manifest.installMethod in allowedInstallMethods) { "Unsupported install method: ${manifest.installMethod}" }
            require(manifest.source in setOf("REMOTE", "LOCAL")) { "Unsupported tool source: ${manifest.source}" }
            if (manifest.source == "LOCAL") {
                require(manifest.offlineOnly) { "Local plugin must declare offlineOnly=true: ${manifest.id}" }
                require(manifest.installMethod == "LOCAL_PACKAGE") { "Local plugin must use LOCAL_PACKAGE: ${manifest.id}" }
            }
            manifest.permissions.forEach { permission ->
                require(permission in allowedPermissions) { "Unsupported tool permission: $permission" }
            }
            manifest.homepage?.let { homepage ->
                require(homepage.startsWith("https://", ignoreCase = true)) {
                    "Tool homepage must use HTTPS: ${manifest.id}"
                }
            }
            manifest.servicePort?.let { port ->
                require(port in 1..65535) { "Invalid local service port: ${manifest.id}" }
                require(manifest.launchType in setOf("web", "service")) {
                    "Only web/service tools can declare local service: ${manifest.id}"
                }
                require(manifest.servicePath.startsWith('/') && !manifest.servicePath.contains("..")) {
                    "Local service path unsafe: ${manifest.id}"
                }
            }
            if (!manifest.offlineOnly) {
                manifest.dependencies.forEach { dependency ->
                    val parsed = ManifestDependencyParser.parse(dependency)
                    require(parsed != null && parsed.name in allowedDependencies) {
                        "Unsupported dependency type: $dependency"
                    }
                }
            }
        }
        require(manifests.map { it.id }.toSet().size == manifests.size) { "Duplicate tool IDs" }
        return manifests
    }
}

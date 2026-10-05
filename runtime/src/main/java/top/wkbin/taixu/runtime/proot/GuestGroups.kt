package top.wkbin.taixu.runtime.proot

import java.io.File

/**
 * Android host injects supplementary GIDs (inet/everybody/OEM groups etc) into app process;
 * these GIDs have no entries in distro /etc/group, so login shell enumerating supplementary groups
 * prints "groups: cannot find name for group ID xxx" warnings. Idempotently writing missing host GIDs
 * into guest /etc/group eliminates this. Pure function logic placed here for JVM unit test coverage.
 */
internal fun syncGuestGroups(rootfsDir: File, hostGroupIds: List<Int>) {
    if (hostGroupIds.isEmpty()) return
    val groupFile = File(rootfsDir, "etc/group")
    if (!groupFile.isFile) return
    val existingGids = groupFile.readLines()
        .mapNotNullTo(HashSet()) { line -> line.split(':').getOrNull(2)?.trim()?.toIntOrNull() }
    val missing = hostGroupIds.filterNot { it in existingGids }.distinct()
    if (missing.isEmpty()) return
    groupFile.appendText(missing.joinToString("") { gid -> "host_g$gid:x:$gid:\n" })
}

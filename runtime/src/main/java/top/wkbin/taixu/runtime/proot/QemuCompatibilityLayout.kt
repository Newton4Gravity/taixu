package top.wkbin.taixu.runtime.proot

import java.io.File

/** Filesystem contract for the optional, isolated x86_64 compatibility payload. */
object QemuCompatibilityLayout {
    const val PLUGIN_ID = "qemu-x86-64-compat"
    const val ROOT_RELATIVE_TO_TAIXU = "compat/x86_64"
    const val QEMU_BINARY_NAME = "qemu-x86_64"

    fun root(taixuRoot: File): File = File(taixuRoot, ROOT_RELATIVE_TO_TAIXU)
    fun qemuBinary(taixuRoot: File): File = File(root(taixuRoot), QEMU_BINARY_NAME)
    fun guestRootfs(taixuRoot: File): File = File(root(taixuRoot), "rootfs")

    /**
     * QEMU alone is insufficient: require an x86_64 shell and dynamic loader as
     * a minimal guard against accidentally emulating the normal ARM64 rootfs.
     */
    fun isReady(taixuRoot: File): Boolean {
        val qemu = qemuBinary(taixuRoot)
        val rootfs = guestRootfs(taixuRoot)
        val loader = sequenceOf(
            File(rootfs, "lib64/ld-linux-x86-64.so.2"),
            File(rootfs, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"),
        ).any(File::isFile)
        return qemu.isFile && qemu.canExecute() &&
            File(rootfs, "bin/sh").isFile && loader
    }
}

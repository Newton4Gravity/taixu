package top.wkbin.taixu.runtime

/** Commands may run concurrently; cleanup needs an idle runtime and prevents new launches. */
internal class StorageActivityGate {
    private val monitor = Any()
    private var active = 0
    private var cleaning = false

    suspend fun <T> activity(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning) { "Storage cleanup in progress, please start task later" }
            active++
        }
        try { return block() } finally { synchronized(monitor) { active-- } }
    }

    suspend fun cleanup(block: suspend () -> Unit) {
        synchronized(monitor) {
            check(!cleaning && active == 0) { "Command or build running, please finish before cleanup" }
            cleaning = true
        }
        try { block() } finally { synchronized(monitor) { cleaning = false } }
    }
}

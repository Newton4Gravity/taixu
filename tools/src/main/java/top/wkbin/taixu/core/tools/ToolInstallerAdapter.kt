package top.wkbin.taixu.core.tools

import top.wkbin.taixu.runtime.tools.InstallEvent
import kotlinx.coroutines.flow.Flow

/** The install contract shared by every manifest-backed tool adapter. */
interface ToolInstallerAdapter {
    val toolId: String
    fun install(): Flow<InstallEvent>
}

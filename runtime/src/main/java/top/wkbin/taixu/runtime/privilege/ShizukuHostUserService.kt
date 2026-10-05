package top.wkbin.taixu.runtime.privilege

import android.content.Context
import org.json.JSONObject

/**
 * UserService running under Shizuku shell UID (or Sui root UID).
 *
 * This class cannot rely on regular Android app Context; it only executes
 * controlled shell and returns bounded results via Binder; permission selection,
 * approval, and audit remain in app process.
 */
class ShizukuHostUserService : IShizukuHostService.Stub {
    private val runner = HostProcessRunner { command ->
        ProcessBuilder("/system/bin/sh", "-c", command).start()
    }
    @Suppress("unused")
    constructor()

    /** Shizuku 13+ prefers Context-arg constructor. */
    @Suppress("unused")
    constructor(context: Context) : this()

    override fun execute(operationId: String, command: String): String = runner.execute(operationId, command).let { result ->
        encode(result.success, result.exitCode, result.stdout, result.stderr)
    }

    override fun cancel(operationId: String): Boolean = runner.cancel(operationId)

    override fun destroy() {
        System.exit(0)
    }

    private fun encode(success: Boolean, exitCode: Int, stdout: String, stderr: String): String =
        JSONObject()
            .put("success", success)
            .put("exitCode", exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .toString()

}

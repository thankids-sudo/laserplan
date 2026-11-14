package io.github.thankids_sudo.laserplan.survey

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Where 3D scans live: app-private permanent storage, one folder per project, one sub-folder per scan.
 *   files/projects/<projectId>/scans/<scanId>/   (manifest.json, frames/, depth/, confidence/, rgb/, cloud.xyzc, preview.bin)
 * The project in the drawing keeps only a small reference to each scan (id, date, counts, size, room).
 * Scans are kept until the user deletes them, or deletes the project.
 */
object ScanStore {
    private val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    fun isSafeId(id: String?) = id != null && SAFE.matches(id)

    fun root(ctx: Context) = File(ctx.filesDir, "projects")

    fun projectDir(ctx: Context, projectId: String): File? =
        if (isSafeId(projectId)) File(root(ctx), "$projectId/scans") else null

    fun scanDir(ctx: Context, projectId: String, scanId: String): File? =
        if (isSafeId(projectId) && isSafeId(scanId)) File(root(ctx), "$projectId/scans/$scanId") else null

    fun newScan(ctx: Context, projectId: String): Pair<String, File>? {
        val base = projectDir(ctx, projectId) ?: return null
        val id = "s" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(base, id)
        if (!dir.mkdirs() && !dir.isDirectory) return null
        return id to dir
    }

    fun sizeBytes(f: File?): Long = f?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun freeBytes(ctx: Context): Long = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun delete(ctx: Context, projectId: String, scanId: String): Boolean = scanDir(ctx, projectId, scanId)?.deleteRecursively() ?: false

    fun deleteProject(ctx: Context, projectId: String): Boolean =
        if (isSafeId(projectId)) File(root(ctx), projectId).deleteRecursively() else false

    /** Writes who the scan belongs to, so the folder can be identified without the drawing. */
    fun writeOwner(dir: File, projectId: String, roomId: String, roomName: String) {
        File(dir, "owner.json").writeText(JSONObject().put("projectId", projectId).put("roomId", roomId).put("roomName", roomName).toString(2))
    }
}

/**
 * Scan events for the drawing (e.g. "scan saved"). Queued here and delivered by MainActivity when the plan is on
 * screen, so an event produced while the AR screen is open is not lost and is delivered exactly once.
 */
object ScanEvents {
    private val queue = ConcurrentLinkedQueue<String>()
    fun post(json: String) { queue.add(json) }
    fun drain(deliver: (String) -> Unit) { while (true) deliver(queue.poll() ?: return) }
}

package io.github.opencodeandroid.diagnostics

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File

/** Only this app UID's process metadata; no names, argv, environment, or other UID records retained. */
internal object WorkstationProcessMetrics {
    private data class Task(val pid: Int, val parent: Int, val ticks: Long, val rss: Long?)
    fun sample(context: Context, status: JSONObject): JSONObject {
        val ownUid = android.os.Process.myUid()
        fun task(pid: Int): Task? = runCatching {
            val lines = File("/proc/$pid/status").readLines()
            val uid = lines.first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
            if (uid != ownUid) return null
            val fields = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ').filter(String::isNotEmpty)
            val rss = lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()
            Task(pid, fields[1].toInt(), fields[11].toLong() + fields[12].toLong(), rss)
        }.getOrNull()
        val owned = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull()?.let(::task) }
        val byId = owned.associateBy { it.pid }; val byParent = owned.groupBy { it.parent }
        val seeds = mutableSetOf(status.getInt("pid"), status.getInt("guestPid"))
        status.getJSONArray("terminalPids").let { array -> repeat(array.length()) { seeds += array.getInt(it) } }
        seeds.remove(0)
        val queue = java.util.ArrayDeque<Int>().apply { addAll(seeds) }; val seen = seeds.toMutableSet(); val tasks = mutableListOf<Task>()
        while (queue.isNotEmpty()) {
            val current = byId[queue.removeFirst()] ?: continue; tasks += current; check(tasks.size <= 256)
            byParent[current.pid].orEmpty().forEach { if (seen.add(it.pid)) queue.add(it.pid) }
        }
        val pss = runCatching { context.getSystemService(ActivityManager::class.java).getProcessMemoryInfo(tasks.map { it.pid }.toIntArray()) }.getOrNull()
        val measured = pss?.count { it.totalPss > 0 } ?: 0
        return JSONObject().put("processes", tasks.size).put("rssMeasuredProcesses", tasks.count { it.rss != null })
            .put("rssSumKiB", if (tasks.isNotEmpty() && tasks.all { it.rss != null }) tasks.sumOf { it.rss!! } else JSONObject.NULL)
            .put("pssMeasuredProcesses", measured)
            .put("pssSumKiB", if (pss != null && tasks.isNotEmpty() && measured == tasks.size) pss.sumOf { it.totalPss.toLong() } else JSONObject.NULL)
            .put("cpuTicksOfLiveTasks", tasks.sumOf { it.ticks })
    }
}

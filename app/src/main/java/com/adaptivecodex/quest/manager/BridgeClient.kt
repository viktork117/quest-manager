package com.adaptivecodex.quest.manager

import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

internal enum class EntryKind { USER, SYSTEM, SYSTEM_PROCESS }
internal enum class ControlAction(val wireName: String, val title: String) {
  STOP("stop", "Остановить"),
  RESTART("restart", "Перезапустить"),
  SUSPEND("suspend", "Запретить запуск"),
  UNSUSPEND("unsuspend", "Разрешить запуск"),
}

internal data class PackageStatus(
  val packageName: String,
  val running: Boolean,
  val stopped: Boolean,
  val suspended: Boolean,
  val launchComponent: String?,
  val pids: List<Int>,
)

internal data class ActionResult(val verified: Boolean, val status: PackageStatus)

internal data class ManagedApp(
  val packageName: String,
  val kind: EntryKind,
  val processes: Int,
  val rssKb: Long,
  val pssKb: Long,
  val pids: List<Int>,
)

internal data class MemoryBreakdown(
  val usedPssKb: Long,
  val kernelKb: Long,
  val zramKb: Long,
)

internal data class DeviceSnapshot(
  val model: String,
  val android: String,
  val display: String,
  val density: String,
  val brightness: String,
  val memoryTotalKb: Long,
  val memoryAvailableKb: Long,
  val memoryBreakdown: MemoryBreakdown?,
  val apps: List<ManagedApp>,
)

internal data class CoreLoad(val name: String, val percent: Double?)

internal data class PerformanceSnapshot(
  val cpuPercent: Double?,
  val cpuCores: List<CoreLoad>,
  val gpuPercent: Double?,
  val memoryTotalKb: Long,
  val memoryAvailableKb: Long,
  val gpuAllocatedBytes: Long,
  val gpuBytesByPid: Map<Int, Long>,
  val gpuNamesByPid: Map<Int, String>,
)

internal object BridgeClient {
  fun performance(token: String): PerformanceSnapshot {
    val data = request(token, "performance").getJSONObject("performance")
    val cores = data.getJSONArray("cpuCores")
    val coreLoads = buildList {
      for (i in 0 until cores.length()) {
        val core = cores.getJSONObject(i)
        add(CoreLoad(core.getString("core"), core.optDoubleOrNull("percent")))
      }
    }
    val processes = data.getJSONArray("gpuProcesses")
    val gpuBytesByPid = buildMap {
      for (i in 0 until processes.length()) {
        val process = processes.getJSONObject(i)
        put(process.getInt("pid"), process.getLong("bytes"))
      }
    }
    val gpuNamesByPid = buildMap {
      for (i in 0 until processes.length()) {
        val process = processes.getJSONObject(i)
        put(process.getInt("pid"), process.optString("name", "PID " + process.getInt("pid")))
      }
    }
    return PerformanceSnapshot(
      cpuPercent = data.optDoubleOrNull("cpuPercent"),
      cpuCores = coreLoads,
      gpuPercent = data.optDoubleOrNull("gpuPercent"),
      memoryTotalKb = data.optLong("memoryTotalKb"),
      memoryAvailableKb = data.optLong("memoryAvailableKb"),
      gpuAllocatedBytes = data.optLong("gpuAllocatedBytes"),
      gpuBytesByPid = gpuBytesByPid,
      gpuNamesByPid = gpuNamesByPid,
    )
  }

  fun snapshot(token: String): DeviceSnapshot {
    val response = request(token, "snapshot")
    val device = response.getJSONObject("device")
    val rows = response.getJSONArray("apps")
    val apps = buildList {
      for (i in 0 until rows.length()) {
        val row = rows.getJSONObject(i)
        val pidArray = row.optJSONArray("pids")
        val pids = buildList {
          if (pidArray != null) for (index in 0 until pidArray.length()) add(pidArray.optInt(index))
        }
        add(
          ManagedApp(
            packageName = row.getString("package"),
            kind = when (row.optString("kind")) {
              "system" -> EntryKind.SYSTEM
              "system_process" -> EntryKind.SYSTEM_PROCESS
              else -> EntryKind.USER
            },
            processes = row.optInt("processes"),
            rssKb = row.optLong("rssKb"),
            pssKb = row.optLong("pssKb"),
            pids = pids,
          )
        )
      }
    }
    val breakdown = response.optJSONObject("memoryBreakdown")?.let {
      MemoryBreakdown(it.optLong("usedPssKb"), it.optLong("kernelKb"), it.optLong("zramKb"))
    }
    return DeviceSnapshot(
      model = device.optString("model", "—"),
      android = device.optString("android", "—"),
      display = device.optString("display", "—"),
      density = device.optString("density", "—"),
      brightness = device.optString("brightness", "—"),
      memoryTotalKb = device.optLong("memoryTotalKb"),
      memoryAvailableKb = device.optLong("memoryAvailableKb"),
      memoryBreakdown = breakdown,
      apps = apps,
    )
  }

  fun forceStopTestApp(token: String): Boolean {
    val response = request(token, "forceStop", BridgeProtocol.TEST_PACKAGE)
    return response.optBoolean("stopped")
  }

  fun packageStatus(token: String, packageName: String): PackageStatus =
    parsePackageStatus(request(token, "packageStatus", packageName).getJSONObject("status"))

  fun packageAction(token: String, packageName: String, action: ControlAction): ActionResult {
    val response = request(token, "packageAction", packageName, action.wireName)
    return ActionResult(response.optBoolean("verified"),
      parsePackageStatus(response.getJSONObject("status")))
  }

  private fun parsePackageStatus(data: JSONObject): PackageStatus {
    val rawPids = data.optJSONArray("pids")
    val pids = buildList {
      if (rawPids != null) for (index in 0 until rawPids.length()) add(rawPids.getInt(index))
    }
    return PackageStatus(
      packageName = data.getString("package"),
      running = data.optBoolean("running"),
      stopped = data.optBoolean("stopped"),
      suspended = data.optBoolean("suspended"),
      launchComponent = data.optString("launchComponent")
        .takeIf { it.isNotBlank() && it != "null" },
      pids = pids,
    )
  }

  private fun request(token: String, operation: String, packageName: String? = null,
                      action: String? = null): JSONObject {
    val payload = JSONObject().put("token", token).put("op", operation)
    if (packageName != null) payload.put("package", packageName)
    if (action != null) payload.put("action", action)

    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", BridgeProtocol.PORT), 3_000)
      socket.soTimeout = 10_000
      val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
      writer.write(payload.toString())
      writer.newLine()
      writer.flush()
      val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
        ?: error("Мост закрыл соединение без ответа")
      if (line.length > 2_000_000) error("Слишком большой ответ моста")
      val response = JSONObject(line)
      if (!response.optBoolean("ok")) error(response.optString("error", "Ошибка моста"))
      return response
    }
  }

  private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}

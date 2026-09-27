package com.adaptivecodex.quest.manager

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ManagerActivity : ComponentActivity() {
  private var panel: VulkanPanelView? = null
  private val handler = Handler(Looper.getMainLooper())
  private val performancePoll = object : Runnable {
    override fun run() {
      if (!active || panel == null) return
      refreshPerformance()
      handler.postDelayed(this, 3_000)
    }
  }
  private var snapshot by mutableStateOf<DeviceSnapshot?>(null)
  private var status by mutableStateOf("Ожидание связи с ПК")
  private var busy by mutableStateOf(false)
  private var confirmStop by mutableStateOf(false)
  private var active by mutableStateOf(false)
  private var performance by mutableStateOf<PerformanceSnapshot?>(null)
  private var performanceBusy by mutableStateOf(false)
  private var performanceError by mutableStateOf<String?>(null)
  private var cpuHistory by mutableStateOf<List<Float>>(emptyList())
  private var gpuHistory by mutableStateOf<List<Float>>(emptyList())
  private var memoryHistory by mutableStateOf<List<Float>>(emptyList())
  private var selectedPackage by mutableStateOf<String?>(null)
  private var selectedStatus by mutableStateOf<PackageStatus?>(null)
  private var actionFeedback by mutableStateOf<String?>(null)
  private var managementRequest by mutableStateOf(0)
  private var appMenuPending = false
  private var appMenuDialog: android.app.AlertDialog? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (VulkanPanelView.isSupported()) {
      panel = VulkanPanelView(
        context = this,
        onRefresh = ::refresh,
        onSearch = ::showSearchDialog,
        onStop = ::forceStopTestApp,
        onSelectApp = ::selectApp,
        onOpenAppMenu = ::showAppMenu,
        onControlAction = ::confirmPackageAction,
        onRendererFailure = ::showComposeFallback,
      )
      setContentView(panel)
      updatePanel()
    } else {
      showComposeFallback()
    }
  }

  private fun showComposeFallback() {
    if (panel != null) {
      panel?.shutdown()
      panel = null
      handler.removeCallbacks(performancePoll)
    }
    setContent {
      ManagerScreen(
        snapshot = snapshot,
        performance = performance,
        status = status,
        busy = busy,
        active = active,
        performanceError = performanceError,
        cpuHistory = cpuHistory,
        gpuHistory = gpuHistory,
        memoryHistory = memoryHistory,
        selectedPackage = selectedPackage,
        selectedStatus = selectedStatus,
        actionFeedback = actionFeedback,
        managementRequest = managementRequest,
        onRefresh = ::refresh,
        onPerformanceRefresh = ::refreshPerformance,
        onStop = { confirmStop = true },
        onSelectApp = ::selectApp,
        onOpenAppMenu = ::showAppMenu,
        onControlAction = ::confirmPackageAction,
      )
      if (confirmStop) {
        AlertDialog(
          onDismissRequest = { confirmStop = false },
          title = { Text("Принудительно остановить приложение?") },
          text = { Text("Пакет ${BridgeProtocol.TEST_PACKAGE}. Его процессы будут завершены.") },
          confirmButton = {
            Button(onClick = {
              confirmStop = false
              forceStopTestApp()
            }) { Text("Остановить") }
          },
          dismissButton = {
            OutlinedButton(onClick = { confirmStop = false }) { Text("Отмена") }
          },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    active = true
    refresh()
    if (panel != null) {
      handler.removeCallbacks(performancePoll)
      handler.post(performancePoll)
    }
  }

  override fun onPause() {
    active = false
    handler.removeCallbacks(performancePoll)
    super.onPause()
  }

  override fun onDestroy() {
    appMenuDialog?.dismiss()
    appMenuDialog = null
    appMenuPending = false
    panel?.shutdown()
    panel = null
    super.onDestroy()
  }

  private fun updatePanel() {
    panel?.update(PanelState(
      snapshot = snapshot,
      performance = performance,
      status = status,
      busy = busy,
      performanceError = performanceError,
      cpuHistory = cpuHistory,
      gpuHistory = gpuHistory,
      memoryHistory = memoryHistory,
      selectedPackage = selectedPackage,
      selectedStatus = selectedStatus,
      actionFeedback = actionFeedback,
    ))
  }

  private fun selectApp(app: ManagedApp) {
    selectedPackage = app.packageName
    selectedStatus = null
    actionFeedback = null
    updatePanel()
    if (app.kind == EntryKind.SYSTEM_PROCESS) return
    val token = bridgeToken() ?: return
    Thread {
      try {
        val detail = BridgeClient.packageStatus(token, app.packageName)
        runOnUiThread {
          if (selectedPackage == app.packageName) {
            selectedStatus = detail
            updatePanel()
          }
        }
      } catch (error: Exception) {
        runOnUiThread {
          if (selectedPackage == app.packageName) {
            status = "Состояние пакета недоступно: ${error.message ?: "ошибка"}"
            updatePanel()
          }
        }
      }
    }.start()
  }

  private fun showAppMenu(app: ManagedApp) {
    if (appMenuPending || isFinishing || isDestroyed) return
    appMenuPending = true
    val token = bridgeToken()
    if (app.kind == EntryKind.SYSTEM_PROCESS || token == null) {
      showAppMenuItems(app, null)
      return
    }
    Thread {
      val detail = try { BridgeClient.packageStatus(token, app.packageName) }
      catch (_: Exception) { null }
      runOnUiThread {
        if (isFinishing || isDestroyed) appMenuPending = false
        else showAppMenuItems(app, detail)
      }
    }.start()
  }

  private fun showAppMenuItems(app: ManagedApp, detail: PackageStatus?) {
    val actions = buildList<Pair<String, ControlAction?>> {
      add("Подробнее" to null)
      if (app.kind == EntryKind.USER && app.packageName != "com.adaptivecodex.quest.manager") {
        if (detail != null && !detail.stopped) add("Остановить" to ControlAction.STOP)
        if (detail?.launchComponent != null && !detail.suspended)
          add("Перезапустить" to ControlAction.RESTART)
        if (detail?.suspended == true) add("Разрешить запуск" to ControlAction.UNSUSPEND)
        else if (detail != null) add("Запретить запуск" to ControlAction.SUSPEND)
      }
    }
    val dialog = AlertDialog.Builder(this)
      .setTitle(app.packageName)
      .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
        val action = actions[index].second
        if (action == null) {
          selectApp(app)
          if (panel != null) panel?.showManagement() else managementRequest++
        } else confirmPackageAction(app, action)
      }
      .setOnDismissListener {
        appMenuDialog = null
        appMenuPending = false
      }
      .create()
    appMenuDialog = dialog
    try { dialog.show() } catch (error: Exception) {
      appMenuDialog = null
      appMenuPending = false
      throw error
    }
  }

  private fun confirmPackageAction(app: ManagedApp, action: ControlAction) {
    if (app.kind != EntryKind.USER || busy || app.packageName == "com.adaptivecodex.quest.manager") return
    val detail = when (action) {
      ControlAction.STOP -> "Процессы пакета будут завершены."
      ControlAction.RESTART -> "Приложение будет остановлено и открыто заново."
      ControlAction.SUSPEND -> "Horizon OS запретит открывать приложение до отмены запрета."
      ControlAction.UNSUSPEND -> "Приложение снова можно будет открыть."
    }
    AlertDialog.Builder(this)
      .setTitle(action.title)
      .setMessage("${app.packageName}\n\n$detail")
      .setNegativeButton("Отмена", null)
      .setPositiveButton(action.title) { _, _ -> runPackageAction(app, action) }
      .show()
  }

  private fun runPackageAction(app: ManagedApp, action: ControlAction) {
    val token = bridgeToken() ?: return
    busy = true
    status = "${action.title}: ${app.packageName}…"
    updatePanel()
    Thread {
      try {
        val result = BridgeClient.packageAction(token, app.packageName, action)
        runOnUiThread {
          busy = false
          status = if (result.verified) "${action.title}: состояние подтверждено"
            else "${action.title}: команда выполнена, состояние не подтверждено"
          actionFeedback = status
          if (selectedPackage == app.packageName) selectedStatus = result.status
          updatePanel()
          refresh()
        }
      } catch (error: Exception) {
        runOnUiThread {
          busy = false
          status = "${action.title}: ${error.message ?: "ошибка"}"
          actionFeedback = status
          updatePanel()
        }
      }
    }.start()
  }

  private fun showSearchDialog(current: String, onSearch: (String) -> Unit) {
    val input = EditText(this).apply {
      setSingleLine(true)
      setText(current)
      setSelection(current.length)
      hint = "Пакет или PID"
      setPadding(24, 16, 24, 16)
    }
    AlertDialog.Builder(this)
      .setTitle("Поиск процессов")
      .setView(input)
      .setPositiveButton("Найти") { _, _ -> onSearch(input.text.toString()) }
      .setNegativeButton("Отмена", null)
      .show()
  }

  private fun bridgeToken(): String? =
    getSharedPreferences(BridgeProtocol.PREFS_NAME, MODE_PRIVATE)
      .getString(BridgeProtocol.PREF_TOKEN, null)

  private fun refresh() {
    if (busy) return
    val token = bridgeToken()
    if (token == null) {
      status = "Нет ключа связи. Запустите bridge.ps1 на ПК."
      updatePanel()
      return
    }
    busy = true
    status = "Получение данных с Quest через ПК…"
    updatePanel()
    Thread {
      try {
        val result = BridgeClient.snapshot(token)
        runOnUiThread {
          snapshot = result
          status = "ПК подключён · ${result.apps.size} приложений"
          busy = false
          updatePanel()
        }
      } catch (error: Exception) {
        runOnUiThread {
          status = "Нет связи с ПК: ${error.message ?: "неизвестная ошибка"}"
          busy = false
          updatePanel()
        }
      }
    }.start()
  }

  private fun refreshPerformance() {
    if (performanceBusy || !active) return
    val token = bridgeToken() ?: return
    performanceBusy = true
    Thread {
      try {
        val result = BridgeClient.performance(token)
        runOnUiThread {
          performance = result
          result.cpuPercent?.let { cpuHistory = (cpuHistory + (it / 100.0).toFloat()).takeLast(40) }
          result.gpuPercent?.let { gpuHistory = (gpuHistory + (it / 100.0).toFloat()).takeLast(40) }
          if (result.memoryTotalKb > 0) {
            val used = (result.memoryTotalKb - result.memoryAvailableKb).toFloat() / result.memoryTotalKb
            memoryHistory = (memoryHistory + used.coerceIn(0f, 1f)).takeLast(40)
          }
          performanceError = null
          performanceBusy = false
          updatePanel()
        }
      } catch (error: Exception) {
        runOnUiThread {
          performanceError = error.message ?: "Не удалось получить показатели"
          performanceBusy = false
          updatePanel()
        }
      }
    }.start()
  }

  private fun forceStopTestApp() {
    if (busy) return
    val token = bridgeToken() ?: return
    busy = true
    status = "Останавливаю тестовое приложение…"
    updatePanel()
    Thread {
      try {
        val stopped = BridgeClient.forceStopTestApp(token)
        runOnUiThread {
          busy = false
          status = if (stopped) "Тестовое приложение остановлено" else "Остановка не подтверждена"
          updatePanel()
          refresh()
        }
      } catch (error: Exception) {
        runOnUiThread {
          status = "Ошибка остановки: ${error.message ?: "неизвестная ошибка"}"
          busy = false
          updatePanel()
        }
      }
    }.start()
  }
}

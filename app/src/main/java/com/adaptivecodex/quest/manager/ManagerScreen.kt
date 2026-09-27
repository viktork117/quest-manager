package com.adaptivecodex.quest.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val Background = Color(0xFF080F18)
private val Sidebar = Color(0xFF0C1723)
private val Panel = Color(0xFF101E2B)
private val PanelAlt = Color(0xFF142534)
private val Line = Color(0xFF294353)
private val Cyan = Color(0xFF56E7EE)
private val Violet = Color(0xFFAE98FF)
private val Mint = Color(0xFF83E9BF)
private val TextPrimary = Color(0xFFE7F3F7)
private val Muted = Color(0xFFA1B8C3)

private enum class Section(val title: String) {
  PERFORMANCE("Показатели"),
  PROCESSES("Процессы"),
  DEVICE("Устройство"),
}

@Composable
internal fun ManagerScreen(
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  status: String,
  busy: Boolean,
  active: Boolean,
  performanceError: String?,
  cpuHistory: List<Float>,
  gpuHistory: List<Float>,
  memoryHistory: List<Float>,
  selectedPackage: String?,
  selectedStatus: PackageStatus?,
  actionFeedback: String?,
  managementRequest: Int,
  onRefresh: () -> Unit,
  onPerformanceRefresh: () -> Unit,
  onStop: () -> Unit,
  onSelectApp: (ManagedApp) -> Unit,
  onOpenAppMenu: (ManagedApp) -> Unit,
  onControlAction: (ManagedApp, ControlAction) -> Unit,
) {
  var section by remember { mutableStateOf(Section.PERFORMANCE) }
  var managementOpen by remember { mutableStateOf(false) }
  var search by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current

  LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }
  LaunchedEffect(managementRequest) { if (managementRequest > 0) managementOpen = true }
  BackHandler(managementOpen) { managementOpen = false }
  LaunchedEffect(active) {
    while (active) {
      onPerformanceRefresh()
      delay(3_000)
    }
  }

  MaterialTheme(
    colorScheme = darkColorScheme(
      background = Background,
      surface = Panel,
      onBackground = TextPrimary,
      onSurface = TextPrimary,
      primary = Cyan,
      onPrimary = Background,
    )
  ) {
    Surface(Modifier.fillMaxSize(), color = Background) {
      BoxWithConstraints {
        if (maxWidth >= 760.dp) {
          Row(Modifier.fillMaxSize()) {
            SidebarNavigation(section, onSelect = { section = it })
            MainContent(
              section, snapshot, performance, status, busy, performanceError,
              cpuHistory, gpuHistory, memoryHistory, selectedPackage, selectedStatus, actionFeedback, search,
              onSearch = { search = it }, onRefresh, onOpenAppMenu, onControlAction,
              modifier = Modifier.weight(1f),
            )
          }
        } else {
          Column(Modifier.fillMaxSize()) {
            Row(
              Modifier.fillMaxWidth().background(Sidebar).padding(8.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Section.entries.forEach { item ->
                OutlinedButton(onClick = { section = item }, enabled = section != item) {
                  Text(item.title, maxLines = 1)
                }
              }
            }
            MainContent(
              section, snapshot, performance, status, busy, performanceError,
              cpuHistory, gpuHistory, memoryHistory, selectedPackage, selectedStatus, actionFeedback, search,
              onSearch = { search = it }, onRefresh, onOpenAppMenu, onControlAction,
              modifier = Modifier.weight(1f),
            )
          }
        }
        if (managementOpen) {
          Column(Modifier.fillMaxSize().background(Background)) {
            Row(Modifier.fillMaxWidth().background(Sidebar).padding(12.dp),
              verticalAlignment = Alignment.CenterVertically) {
              TextButton(onClick = { managementOpen = false }) { Text("← Назад") }
              Spacer(Modifier.width(16.dp))
              Text("Управление", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            ControlPage(snapshot, performance, selectedPackage, selectedStatus,
              actionFeedback, busy, onControlAction)
          }
        }
      }
    }
  }
}

@Composable
private fun SidebarNavigation(section: Section, onSelect: (Section) -> Unit) {
  Column(
    Modifier.width(202.dp).fillMaxHeight().background(Sidebar)
      .border(width = 1.dp, color = Line.copy(alpha = 0.55f))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "QUEST / MANAGER",
      color = Cyan,
      fontSize = 17.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
    )
    Text(
      "CPU · GPU · RAM",
      color = Muted,
      fontSize = 14.sp,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(18.dp))
    Section.entries.forEach { item ->
      val selected = section == item
      Surface(
        onClick = { onSelect(item) },
        color = if (selected) PanelAlt else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border = androidx.compose.foundation.BorderStroke(
          1.dp, if (selected) Cyan.copy(alpha = 0.70f) else Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          Modifier.padding(horizontal = 11.dp, vertical = 15.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            item.title,
            color = if (selected) TextPrimary else Muted,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          )
        }
      }
    }
    Spacer(Modifier.weight(1f))
    Text("QUEST 3S", color = Cyan, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 8.dp))
    Text("ADB / ПК", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
  }
}

@Composable
private fun MainContent(
  section: Section,
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  status: String,
  busy: Boolean,
  performanceError: String?,
  cpuHistory: List<Float>,
  gpuHistory: List<Float>,
  memoryHistory: List<Float>,
  selectedPackage: String?,
  selectedStatus: PackageStatus?,
  actionFeedback: String?,
  search: String,
  onSearch: (String) -> Unit,
  onRefresh: () -> Unit,
  onOpenAppMenu: (ManagedApp) -> Unit,
  onControlAction: (ManagedApp, ControlAction) -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxHeight()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column {
        Text("QUEST 3S · ADB", color = Cyan, fontSize = 14.sp)
        Text(section.title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.weight(1f))
      if (section == Section.PROCESSES) {
        OutlinedTextField(
          value = search,
          onValueChange = onSearch,
          label = { Text("Пакет или PID") },
          singleLine = true,
          modifier = Modifier.width(240.dp),
        )
      }
      OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Обновить") }
    }
    Row(
      Modifier.fillMaxWidth().background(Panel).padding(horizontal = 22.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Canvas(Modifier.width(8.dp).height(8.dp)) {
        drawCircle(if (status.startsWith("ПК подключён")) Mint else Muted)
      }
      Spacer(Modifier.width(10.dp))
      val displayStatus = if (status.startsWith("ПК подключён") && snapshot != null) {
        val packages = snapshot.apps.count { it.kind != EntryKind.SYSTEM_PROCESS }
        "ПК подключён · $packages пакетов · ${snapshot.apps.size - packages} отдельных процессов"
      } else status
      Text(displayStatus, color = Muted, fontSize = 14.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
    }
    when (section) {
      Section.PERFORMANCE -> PerformancePage(
        snapshot, performance, performanceError, cpuHistory, gpuHistory, memoryHistory
      )
      Section.PROCESSES -> ProcessesPage(snapshot, performance, search, busy, onOpenAppMenu)
      Section.DEVICE -> DevicePage(snapshot, performance)
    }
  }
}

@Composable
private fun PerformancePage(
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  error: String?,
  cpuHistory: List<Float>,
  gpuHistory: List<Float>,
  memoryHistory: List<Float>,
) {
  val total = performance?.memoryTotalKb ?: 0L
  val available = performance?.memoryAvailableKb ?: 0L
  val used = (total - available).coerceAtLeast(0)
  val memoryPercent = if (total > 0) used.toDouble() * 100.0 / total else null
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Text("ЗАГРУЗКА И ИСПОЛЬЗОВАНИЕ ПАМЯТИ", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalCard(
          "ЗАГРУЗКА CPU", performance?.cpuPercent, "среднее по ядрам",
          cpuHistory, Cyan, Modifier.weight(1f),
        )
        SignalCard(
          "ЗАГРУЗКА GPU", performance?.gpuPercent, "доля времени работы",
          gpuHistory, Violet, Modifier.weight(1f),
        )
        SignalCard(
          "ИСПОЛЬЗОВАНИЕ RAM", memoryPercent,
          if (total > 0) formatGiB(used) + " / " + formatGiB(total) + " ГиБ" else "всего − доступно",
          memoryHistory, Mint, Modifier.weight(1f),
        )
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GpuMemoryCard(snapshot, performance, Modifier.weight(1.2f))
        CpuCoresCard(performance, Modifier.weight(1f))
      }
    }
    item {
      val breakdown = snapshot?.memoryBreakdown
      Column(
        Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp))
          .background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Text("РАЗБИВКА RAM", color = Mint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("PSS процессов: " + (breakdown?.let { formatGiB(it.usedPssKb) + " ГиБ" } ?: "—"))
        Text("Ядро: " + (breakdown?.let { formatGiB(it.kernelKb) + " ГиБ" } ?: "—"))
        Text("ZRAM: " + (breakdown?.let { formatGiB(it.zramKb) + " ГиБ" } ?: "—"))
        Text("Доступно: " + (performance?.let { formatGiB(it.memoryAvailableKb) + " ГиБ" } ?: "—"))
        Text("PSS, ядро и ZRAM — снимок при обновлении списка.", color = Muted, fontSize = 13.sp)
      }
    }
    item {
      Text(
        "GPU-память и RSS не прибавляются к PSS. CPU и GPU: загрузка за интервал, " +
          "не доля от пиковой вычислительной мощности. Обновление каждые 3 с при открытом окне.",
        color = Muted,
        fontSize = 14.sp,
      )
    }
    if (error != null) {
      item { Text("Нет данных о загрузке и памяти: " + error, color = Violet, fontSize = 14.sp) }
    }
  }
}

@Composable
private fun SignalCard(
  title: String,
  percent: Double?,
  detail: String,
  history: List<Float>,
  accent: Color,
  modifier: Modifier,
) {
  Column(
    modifier.border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
      .background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(formatPercent(percent), color = TextPrimary, fontSize = 43.sp, fontWeight = FontWeight.Light)
    MeterBar(percent?.let { (it / 100.0).toFloat() } ?: 0f, accent)
    HistoryChart(history, accent)
    Text(detail, color = Muted, fontSize = 14.sp, maxLines = 2)
  }
}

@Composable
private fun GpuMemoryCard(
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  modifier: Modifier,
) {
  val entries = performance?.gpuBytesByPid.orEmpty().entries.sortedByDescending { it.value }.take(5)
  Column(
    modifier.border(1.dp, Line, RoundedCornerShape(12.dp))
      .background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("ПАМЯТЬ, ВЫДЕЛЕННАЯ GPU", color = Violet, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(
      if (performance == null) "—" else formatBytes(performance.gpuAllocatedBytes),
      fontSize = 28.sp, fontWeight = FontWeight.Medium,
    )
    Text("часть общей RAM · распределение по процессам", color = Muted, fontSize = 14.sp)
    if (entries.isEmpty()) {
      Text("Нет данных о памяти GPU", color = Muted, fontSize = 15.sp)
    } else {
      entries.forEach { entry ->
        val app = snapshot?.apps?.firstOrNull { entry.key in it.pids }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            app?.packageName ?: performance?.gpuNamesByPid?.get(entry.key) ?: "PID " + entry.key,
            color = TextPrimary, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
          )
          Spacer(Modifier.width(8.dp))
          Text(formatBytes(entry.value), color = Violet, fontSize = 14.sp)
        }
      }
    }
  }
}

@Composable
private fun CpuCoresCard(performance: PerformanceSnapshot?, modifier: Modifier) {
  Column(
    modifier.border(1.dp, Line, RoundedCornerShape(12.dp))
      .background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("ЗАГРУЗКА ЯДЕР CPU", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    if (performance?.cpuCores.isNullOrEmpty()) {
      Text("Нет данных о загрузке CPU", color = Muted, fontSize = 15.sp)
    } else {
      performance.cpuCores.forEach { core ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(core.name.uppercase(), color = Muted, fontSize = 14.sp, modifier = Modifier.width(58.dp))
          Box(Modifier.weight(1f)) {
            MeterBar(core.percent?.let { (it / 100.0).toFloat() } ?: 0f, Cyan)
          }
          Spacer(Modifier.width(10.dp))
          Text(formatPercent(core.percent), fontSize = 14.sp, modifier = Modifier.width(48.dp))
        }
      }
    }
  }
}

@Composable
private fun MeterBar(fraction: Float, accent: Color) {
  Canvas(Modifier.fillMaxWidth().height(7.dp)) {
    drawRect(Line.copy(alpha = 0.8f))
    drawRect(accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
  }
}

@Composable
private fun HistoryChart(history: List<Float>, accent: Color) {
  Canvas(Modifier.fillMaxWidth().height(48.dp)) {
    for (i in 1..2) {
      val y = size.height * i / 3f
      drawLine(Line.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    if (history.isNotEmpty()) {
      val values = if (history.size == 1) listOf(history[0], history[0]) else history
      val line = Path()
      val fill = Path()
      values.forEachIndexed { index, value ->
        val x = size.width * index / (values.size - 1)
        val y = size.height * (1f - value.coerceIn(0f, 1f))
        if (index == 0) {
          line.moveTo(x, y)
          fill.moveTo(x, size.height)
          fill.lineTo(x, y)
        } else {
          line.lineTo(x, y)
          fill.lineTo(x, y)
        }
      }
      fill.lineTo(size.width, size.height)
      fill.close()
      drawPath(fill, accent.copy(alpha = 0.12f))
      drawPath(line, accent, style = Stroke(width = 2.dp.toPx()))
    }
  }
}

@Composable
private fun ProcessesPage(
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  search: String,
  busy: Boolean,
  onOpenAppMenu: (ManagedApp) -> Unit,
) {
  val query = search.trim()
  val apps = snapshot?.apps.orEmpty().filter { app ->
    query.isBlank() || app.packageName.contains(query, ignoreCase = true) ||
      app.pids.any { it.toString().contains(query) }
  }
  val runningSystem = apps.filter { it.processes > 0 && it.kind == EntryKind.SYSTEM }
  val systemProcesses = apps.filter { it.kind == EntryKind.SYSTEM_PROCESS }
  val runningUser = apps.filter { it.processes > 0 && it.kind == EntryKind.USER }
  val inactiveUser = apps.filter { it.processes == 0 && it.kind == EntryKind.USER }
  val inactiveSystem = apps.filter { it.processes == 0 && it.kind == EntryKind.SYSTEM }
  Column(Modifier.fillMaxSize()) {
    Text(
      "PSS — доля RAM процесса; RSS включает общие страницы; GPU-память не суммируется с ними.",
      color = Muted, fontSize = 14.sp,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
      Text("ПАКЕТ ИЛИ ПРОЦЕСС / PID", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Text("PSS", color = Muted, fontSize = 14.sp, modifier = Modifier.width(80.dp))
      Text("RSS", color = Muted, fontSize = 14.sp, modifier = Modifier.width(80.dp))
      Text("GPU", color = Muted, fontSize = 14.sp, modifier = Modifier.width(92.dp))
      Spacer(Modifier.width(52.dp))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
      val groups = listOf(
        "ЗАПУЩЕННЫЕ ПРИЛОЖЕНИЯ" to runningUser,
        "НЕ ЗАПУЩЕННЫЕ ПРИЛОЖЕНИЯ" to inactiveUser,
        "ЗАПУЩЕННЫЕ СИСТЕМНЫЕ ПАКЕТЫ" to runningSystem,
        "СИСТЕМНЫЕ И ПРОЧИЕ ПРОЦЕССЫ" to systemProcesses,
        "НЕ ЗАПУЩЕННЫЕ СИСТЕМНЫЕ ПАКЕТЫ" to inactiveSystem,
      )
      groups.forEach { (title, rows) ->
        item { GroupHeader(title, rows.size) }
        items(rows, key = { it.kind.name + ":" + it.packageName + ":" + it.pids.firstOrNull() }) {
          app -> ProcessRow(app, performance, busy, onOpenAppMenu)
        }
      }
    }
  }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
  Text(
    title + ": " + count,
    color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.fillMaxWidth().background(Background)
      .padding(horizontal = 22.dp, vertical = 12.dp),
  )
}

@Composable
private fun ProcessRow(
  app: ManagedApp,
  performance: PerformanceSnapshot?,
  busy: Boolean,
  onOpenAppMenu: (ManagedApp) -> Unit,
) {
  val running = app.processes > 0
  val gpuBytes = app.pids.sumOf { performance?.gpuBytesByPid?.get(it) ?: 0L }
  val kind = when (app.kind) {
    EntryKind.USER -> "ПРИЛОЖЕНИЕ"
    EntryKind.SYSTEM -> "СИСТЕМА"
    EntryKind.SYSTEM_PROCESS -> "СЛУЖБА"
  }
  Row(
    Modifier.fillMaxWidth().background(if (running) PanelAlt else Panel)
      .padding(horizontal = 22.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(app.packageName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(kind + if (app.pids.isNotEmpty()) " · PID " + app.pids.joinToString() else " · не запущен",
        color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(if (app.pssKb > 0) formatKiB(app.pssKb) else "—",
      color = Mint, fontSize = 14.sp, modifier = Modifier.width(80.dp))
    Text(if (app.rssKb > 0) formatKiB(app.rssKb) else "—",
      fontSize = 14.sp, modifier = Modifier.width(80.dp))
    Text(if (gpuBytes > 0) formatBytes(gpuBytes) else "—", color = Violet, fontSize = 14.sp, modifier = Modifier.width(92.dp))
    Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
      TextButton(onClick = { onOpenAppMenu(app) }, enabled = !busy) {
        Text("⋮", fontSize = 22.sp)
      }
    }
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.40f)))
}

@Composable
private fun ControlPage(
  snapshot: DeviceSnapshot?,
  performance: PerformanceSnapshot?,
  selectedPackage: String?,
  detail: PackageStatus?,
  actionFeedback: String?,
  busy: Boolean,
  onControlAction: (ManagedApp, ControlAction) -> Unit,
) {
  val app = snapshot?.apps?.firstOrNull { it.packageName == selectedPackage }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { Text("УПРАВЛЕНИЕ ПАКЕТОМ", color = Cyan, fontSize = 15.sp) }
    if (app == null) {
      item { Text("Выберите строку в разделе «Процессы» и откройте ⋮ → Подробнее.") }
      return@LazyColumn
    }
    val gpuBytes = app.pids.sumOf { performance?.gpuBytesByPid?.get(it) ?: 0L }
    item {
      Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
        .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(app.packageName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(when (app.kind) {
          EntryKind.USER -> "ПРИЛОЖЕНИЕ"
          EntryKind.SYSTEM -> "СИСТЕМНЫЙ ПАКЕТ"
          EntryKind.SYSTEM_PROCESS -> "СИСТЕМНЫЙ ПРОЦЕСС"
        }, color = Cyan)
        Text("Процессы: " + if (detail == null) "—" else if (detail.running) "запущены" else "не запущены")
        Text("Запуск: " + if (detail == null) "—" else if (detail.suspended) "запрещён" else "разрешён")
        Text("Принудительно остановлен: " + if (detail == null) "—" else if (detail.stopped) "да" else "нет")
        Text("PID: " + (detail?.pids ?: app.pids).joinToString().ifBlank { "—" }, color = Muted)
        Text("PSS ${formatKiB(app.pssKb)} · RSS ${formatKiB(app.rssKb)}", color = Mint)
        Text("GPU-память " + if (gpuBytes > 0) formatBytes(gpuBytes) else "—", color = Violet)
      }
    }
    if (app.kind != EntryKind.USER || app.packageName == "com.adaptivecodex.quest.manager") {
      item { Text("Системный объект: только просмотр", color = Muted) }
    } else if (detail == null) {
      item { Text("Получение состояния пакета…", color = Muted) }
    } else {
      val actions = buildList {
        if (!detail.stopped) add(ControlAction.STOP)
        if (detail.launchComponent != null && !detail.suspended) add(ControlAction.RESTART)
        add(if (detail.suspended) ControlAction.UNSUSPEND else ControlAction.SUSPEND)
      }
      actions.forEach { action ->
        item {
          OutlinedButton(onClick = { onControlAction(app, action) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text(action.title) }
        }
      }
    }
    item { Text("Запрет запуска — системная приостановка пакета, не сон с сохранением процесса.",
      color = Muted, fontSize = 13.sp) }
    if (actionFeedback != null) item { Text(actionFeedback, color = Mint) }
  }
}

@Composable
private fun DevicePage(snapshot: DeviceSnapshot?, performance: PerformanceSnapshot?) {
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(22.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { Text("ПАРАМЕТРЫ ШЛЕМА", color = Cyan, fontSize = 15.sp) }
    if (snapshot == null) {
      item { Text("Нет данных. Подключите ПК и обновите экран.", color = Muted) }
    } else {
      item { DeviceField("Модель", snapshot.model) }
      item { DeviceField("Android", snapshot.android) }
      item { DeviceField("Разрешение", snapshot.display) }
      item { DeviceField("Плотность", snapshot.density) }
      item { DeviceField("Яркость", snapshot.brightness) }
      item {
        DeviceField(
          "RAM, доступная ОС",
          if (performance != null) formatGiB(performance.memoryTotalKb) + " ГиБ" else "—"
        )
      }
      item { Text("ADB-диагностика сохраняется на подключённом ПК.", color = Muted, fontSize = 14.sp) }
    }
  }
}

@Composable
private fun DeviceField(name: String, value: String) {
  Row(
    Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp))
      .background(Panel, RoundedCornerShape(8.dp)).padding(14.dp),
  ) {
    Text(name, color = Muted, fontSize = 16.sp, modifier = Modifier.width(180.dp))
    Text(value, fontSize = 16.sp)
  }
}

private fun formatPercent(percent: Double?): String =
  if (percent == null) "—" else String.format(Locale.US, "%.0f%%", percent.coerceIn(0.0, 100.0))

private fun formatGiB(kb: Long): String = String.format(Locale.US, "%.2f", kb / 1048576.0)

private fun formatKiB(kb: Long): String = String.format(Locale.US, "%.0f МБ", kb / 1024.0)

private fun formatBytes(bytes: Long): String =
  if (bytes >= 1073741824L) String.format(Locale.US, "%.2f ГиБ", bytes / 1073741824.0)
  else String.format(Locale.US, "%.0f МиБ", bytes / 1048576.0)

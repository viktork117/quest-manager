package com.adaptivecodex.quest.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class PanelState(
  val snapshot: DeviceSnapshot?,
  val performance: PerformanceSnapshot?,
  val status: String,
  val busy: Boolean,
  val performanceError: String?,
  val cpuHistory: List<Float>,
  val gpuHistory: List<Float>,
  val memoryHistory: List<Float>,
  val selectedPackage: String? = null,
  val selectedStatus: PackageStatus? = null,
  val actionFeedback: String? = null,
)

private enum class PanelSection(val title: String, val compactTitle: String) {
  PERFORMANCE("Показатели", "Обзор"),
  PROCESSES("Процессы", "Процессы"),
  DEVICE("Устройство", "Шлем"),
}

/** The bitmap is rasterized in software; only Vulkan presents the final window surface. */
internal class VulkanPanelView(
  context: Context,
  private val onRefresh: () -> Unit,
  private val onSearch: (String, (String) -> Unit) -> Unit,
  private val onStop: () -> Unit,
  private val onSelectApp: (ManagedApp) -> Unit,
  private val onOpenAppMenu: (ManagedApp) -> Unit,
  private val onControlAction: (ManagedApp, ControlAction) -> Unit,
  private val onRendererFailure: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {
  companion object {
    private const val TAG = "QuestManagerVulkan"
    private const val BASE_WIDTH = 1100f
    private const val BASE_HEIGHT = 720f
    private const val COMPACT_MIN_WIDTH = 360f
    private const val COMPACT_CONTENT_TOP = 145f
    private const val COMPACT_SEARCH_BOTTOM = 198f
    private val BACKGROUND = Color.rgb(8, 15, 24)
    private val SIDEBAR = Color.rgb(12, 23, 35)
    private val PANEL = Color.rgb(16, 30, 43)
    private val PANEL_ALT = Color.rgb(20, 37, 52)
    private val LINE = Color.rgb(41, 67, 83)
    private val CYAN = Color.rgb(86, 231, 238)
    private val VIOLET = Color.rgb(174, 152, 255)
    private val MINT = Color.rgb(131, 233, 191)
    private val PRIMARY = Color.rgb(231, 243, 247)
    private val MUTED = Color.rgb(161, 184, 195)
    private var libraryLoaded = false

    fun isSupported(): Boolean {
      if (libraryLoaded) return true
      return try {
        System.loadLibrary("questmanager_vulkan")
        libraryLoaded = true
        true
      } catch (error: UnsatisfiedLinkError) {
        Log.e(TAG, "Vulkan library unavailable", error)
        false
      }
    }
  }

  private external fun nativeCreate(surface: Surface): Long
  private external fun nativeRender(handle: Long, bitmap: Bitmap): Boolean
  private external fun nativeWidth(handle: Long): Int
  private external fun nativeHeight(handle: Long): Int
  private external fun nativeDestroy(handle: Long)

  private val renderer = Executors.newSingleThreadExecutor()
  private val renderQueued = AtomicBoolean(false)
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  @Volatile private var state = PanelState(null, null, "Ожидание связи с ПК", false, null,
    emptyList(), emptyList(), emptyList())
  @Volatile private var section = PanelSection.PERFORMANCE
  @Volatile private var managementOpen = false
  @Volatile private var search = ""
  @Volatile private var processScroll = 0f
  @Volatile private var compactScroll = 0f
  @Volatile private var confirmStop = false
  @Volatile private var closed = false
  private var handle = 0L // renderer thread only
  private var bitmap: Bitmap? = null // renderer thread only
  private var touchDownY = 0f
  private var touchLastY = 0f
  private var dragged = false
  private data class FocusTarget(val kind: FocusKind, val index: Int = 0)
  private enum class FocusKind { TAB, REFRESH, SEARCH, ROW, BACK, ACTION, CANCEL, STOP }
  @Volatile private var focusedTarget: FocusTarget? = null
  private var lastStickTime = 0L
  private val observedInputSources = mutableSetOf<String>()

  private fun noteInput(kind: String, source: Int) {
    if (observedInputSources.add("$kind:$source"))
      Log.i(TAG, "Input $kind source=0x${source.toString(16)}")
  }
  private data class Viewport(val scale: Float, val x: Float, val y: Float,
                              val width: Float, val height: Float, val compact: Boolean)

  private fun viewport(width: Int, height: Int): Viewport {
    val density = resources.displayMetrics.density.coerceAtLeast(1f)
    if (width >= BASE_WIDTH * density && height >= BASE_HEIGHT * density) {
      val fit = min(width / BASE_WIDTH, height / BASE_HEIGHT)
      return Viewport(fit, (width - BASE_WIDTH * fit) / 2f,
        (height - BASE_HEIGHT * fit) / 2f, BASE_WIDTH, BASE_HEIGHT, false)
    }
    val scale = min(density, width / COMPACT_MIN_WIDTH)
    return Viewport(scale, 0f, 0f, width / scale, height / scale, true)
  }

  init {
    holder.addCallback(this)
    isFocusable = true
    isFocusableInTouchMode = true
    keepScreenOn = true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestFocus()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus) requestFocus()
  }

  fun update(value: PanelState) {
    state = value
    requestRender()
  }

  fun showManagement() {
    managementOpen = true
    compactScroll = 0f
    focusedTarget = FocusTarget(FocusKind.BACK)
    requestRender()
  }

  private fun closeManagement() {
    managementOpen = false
    compactScroll = 0f
    focusedTarget = FocusTarget(FocusKind.TAB, section.ordinal)
    requestRender()
  }

  fun shutdown() {
    closed = true
    renderer.execute {
      releaseRenderer()
      bitmap?.recycle()
      bitmap = null
    }
    renderer.shutdown()
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    if (closed) return
    renderer.execute { createRenderer(holder.surface) }
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    if (closed) return
    renderer.execute {
      releaseRenderer()
      createRenderer(holder.surface)
    }
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    if (closed) return
    renderer.execute { releaseRenderer() }
  }

  private fun createRenderer(surface: Surface) {
    if (closed || !surface.isValid) return
    handle = nativeCreate(surface)
    if (handle == 0L) {
      post { if (!closed) onRendererFailure() }
      return
    }
    Log.i(TAG, "Native Vulkan window ${nativeWidth(handle)}x${nativeHeight(handle)}")
    drawFrame()
  }

  private fun releaseRenderer() {
    if (handle != 0L) nativeDestroy(handle)
    handle = 0L
  }

  private fun requestRender() {
    if (closed || !renderQueued.compareAndSet(false, true)) return
    renderer.execute {
      try { drawFrame() } finally { renderQueued.set(false) }
    }
  }

  private fun drawFrame() {
    if (closed || handle == 0L) return
    val width = nativeWidth(handle)
    val height = nativeHeight(handle)
    if (width <= 0 || height <= 0) return
    var target = bitmap
    if (target == null || target.width != width || target.height != height) {
      target?.recycle()
      target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      bitmap = target
    }
    val canvas = Canvas(target)
    canvas.drawColor(BACKGROUND)
    canvas.save()
    val layout = viewport(width, height)
    canvas.translate(layout.x, layout.y)
    canvas.scale(layout.scale, layout.scale)
    if (layout.compact) drawCompactPanel(canvas, state, layout)
    else drawPanel(canvas, state)
    canvas.restore()
    if (!nativeRender(handle, target)) {
      Log.e(TAG, "Vulkan frame failed")
      post { if (!closed) onRendererFailure() }
    }
  }

  private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
                   color: Int, radius: Float = 0f) {
    paint.style = Paint.Style.FILL
    paint.color = color
    if (radius > 0f) canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
    else canvas.drawRect(left, top, right, bottom, paint)
  }

  private fun outline(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
                      color: Int, radius: Float = 0f, width: Float = 1f) {
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = width
    paint.color = color
    canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
    paint.style = Paint.Style.FILL
  }

  private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
                   color: Int, width: Float = 1f) {
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = width
    paint.color = color
    canvas.drawLine(x1, y1, x2, y2, paint)
    paint.style = Paint.Style.FILL
  }

  private fun label(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float = 15f,
                    color: Int = PRIMARY, bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE) {
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textSize = size
    paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    val clipped = if (paint.measureText(text) <= maxWidth) text else {
      var count = text.length
      while (count > 0 && paint.measureText(text.substring(0, count) + "…") > maxWidth) count--
      text.substring(0, count) + "…"
    }
    canvas.drawText(clipped, x, baseline, paint)
  }

  private fun drawPanel(canvas: Canvas, data: PanelState) {
    drawSidebar(canvas)
    drawHeader(canvas, data)
    when (section) {
      PanelSection.PERFORMANCE -> drawPerformance(canvas, data)
      PanelSection.PROCESSES -> drawProcesses(canvas, data)
      PanelSection.DEVICE -> drawDevice(canvas, data)
    }
    if (managementOpen) {
      fill(canvas, 0f, 0f, BASE_WIDTH, BASE_HEIGHT, BACKGROUND)
      fill(canvas, 0f, 0f, BASE_WIDTH, 111f, SIDEBAR)
      outline(canvas, 20f, 23f, 170f, 66f,
        if (focusedTarget == FocusTarget(FocusKind.BACK)) MINT else CYAN, 8f)
      label(canvas, "← Назад", 39f, 51f, 17f, PRIMARY)
      label(canvas, "Управление", 224f, 54f, 25f, PRIMARY, true)
      drawControl(canvas, data)
    }
    if (confirmStop) drawConfirmation(canvas)
  }

  private fun compactColumns(width: Float) = if (width >= 650f) 2 else 1

  private fun compactContentTop() = if (managementOpen) 80f else if (section == PanelSection.PROCESSES)
    COMPACT_SEARCH_BOTTOM else COMPACT_CONTENT_TOP

  private fun compactContentHeight(width: Float, data: PanelState): Float = if (managementOpen)
    620f else when (section) {
    PanelSection.PERFORMANCE -> {
      val cardRows = (3 + compactColumns(width) - 1) / compactColumns(width)
      cardRows * 248f - 12f + 16f + 238f + 14f + 238f + 276f
    }
    PanelSection.PROCESSES -> processEntries(data).fold(8f) { height, entry ->
      height + if (entry.header != null) 32f else 132f
    }
    PanelSection.DEVICE -> 6 * 88f + 66f
  }

  private fun compactScrollMax(view: Viewport, data: PanelState): Float =
    (compactContentHeight(view.width, data) - (view.height - compactContentTop()))
      .coerceAtLeast(0f)

  private fun drawCompactPanel(canvas: Canvas, data: PanelState, view: Viewport) {
    val width = view.width
    val height = view.height
    if (managementOpen) {
      fill(canvas, 0f, 0f, width, height, BACKGROUND)
      fill(canvas, 0f, 0f, width, 70f, SIDEBAR)
      outline(canvas, 16f, 14f, 115f, 55f,
        if (focusedTarget == FocusTarget(FocusKind.BACK)) MINT else CYAN, 8f)
      label(canvas, "← Назад", 29f, 41f, 15f, PRIMARY)
      label(canvas, "Управление", 128f, 42f, 19f, PRIMARY, true,
        maxWidth = width - 144f)
      val scroll = compactScroll.coerceIn(0f, compactScrollMax(view, data))
      canvas.save()
      canvas.clipRect(0f, 80f, width, height)
      canvas.translate(0f, 80f - scroll)
      drawCompactControl(canvas, data, width)
      canvas.restore()
      return
    }
    drawCompactHeader(canvas, data, width)
    if (section == PanelSection.PROCESSES) {
      outline(canvas, 16f, 148f, width - 16f, 188f, LINE, 8f)
      if (focusedTarget == FocusTarget(FocusKind.SEARCH))
        outline(canvas, 16f, 148f, width - 16f, 188f, MINT, 8f, 2f)
      label(canvas, search.ifBlank { "Поиск по пакету или PID" }, 28f, 174f, 14f,
        if (search.isBlank()) MUTED else PRIMARY, maxWidth = width - 56f)
    }
    val top = compactContentTop()
    val scroll = compactScroll.coerceIn(0f, compactScrollMax(view, data))
    canvas.save()
    canvas.clipRect(0f, top, width, height)
    canvas.translate(0f, top - scroll)
    when (section) {
      PanelSection.PERFORMANCE -> drawCompactPerformance(canvas, data, width)
      PanelSection.PROCESSES -> drawCompactProcesses(canvas, data, width)
      PanelSection.DEVICE -> drawCompactDevice(canvas, data, width)
    }
    canvas.restore()
    if (confirmStop) drawCompactConfirmation(canvas, view)
  }

  private fun drawCompactHeader(canvas: Canvas, data: PanelState, width: Float) {
    fill(canvas, 0f, 0f, width, 140f, SIDEBAR)
    label(canvas, "QUEST / MANAGER", 16f, 31f, 17f, CYAN, true,
      maxWidth = width - 145f)
    outline(canvas, width - 120f, 7f, width - 16f, 41f, CYAN, 8f)
    if (focusedTarget == FocusTarget(FocusKind.REFRESH))
      outline(canvas, width - 122f, 5f, width - 14f, 43f, MINT, 8f, 2f)
    label(canvas, if (data.busy) "Загрузка" else "Обновить", width - 111f, 30f, 13f, CYAN)
    val tabWidth = (width - 32f) / PanelSection.entries.size
    PanelSection.entries.forEachIndexed { index, item ->
      val left = 16f + index * tabWidth
      if (section == item) {
        fill(canvas, left, 52f, left + tabWidth - 4f, 95f, PANEL_ALT, 7f)
        outline(canvas, left, 52f, left + tabWidth - 4f, 95f, CYAN, 7f)
      }
      if (focusedTarget == FocusTarget(FocusKind.TAB, index))
        outline(canvas, left, 52f, left + tabWidth - 4f, 95f, MINT, 7f, 2f)
      label(canvas, item.compactTitle, left + 6f, 79f, 12f,
        if (section == item) PRIMARY else MUTED, section == item,
        maxWidth = tabWidth - 11f)
    }
    fill(canvas, 16f, 113f, 23f, 120f,
      if (data.status.startsWith("ПК подключён")) MINT else MUTED, 4f)
    label(canvas, panelStatus(data), 32f, 123f, 13f, MUTED, maxWidth = width - 48f)
  }

  private fun drawCompactPerformance(canvas: Canvas, data: PanelState, width: Float) {
    val performance = data.performance
    val total = performance?.memoryTotalKb ?: 0L
    val used = (total - (performance?.memoryAvailableKb ?: 0L)).coerceAtLeast(0)
    val memoryPercent = if (total > 0) used.toDouble() * 100.0 / total else null
    val columns = compactColumns(width)
    val gap = 12f
    val cardWidth = (width - 32f - gap * (columns - 1)) / columns
    listOf(
      Triple("ЗАГРУЗКА CPU", performance?.cpuPercent, data.cpuHistory),
      Triple("ЗАГРУЗКА GPU", performance?.gpuPercent, data.gpuHistory),
      Triple("ИСПОЛЬЗОВАНИЕ RAM", memoryPercent, data.memoryHistory),
    ).forEachIndexed { index, entry ->
      val x = 16f + (index % columns) * (cardWidth + gap)
      val y = 8f + (index / columns) * 248f
      val detail = when (index) {
        0 -> "среднее по ядрам"
        1 -> "доля времени работы"
        else -> if (total > 0) "${giB(used)} / ${giB(total)} ГиБ" else "всего − доступно"
      }
      signalCard(canvas, x, y, cardWidth, entry.first, entry.second, detail,
        entry.third, when (index) { 0 -> CYAN; 1 -> VIOLET; else -> MINT })
    }
    val cardRows = (3 + columns - 1) / columns
    val memoryTop = 8f + cardRows * 248f + 4f
    fill(canvas, 16f, memoryTop, width - 16f, memoryTop + 238f, PANEL, 12f)
    outline(canvas, 16f, memoryTop, width - 16f, memoryTop + 238f, LINE, 12f)
    label(canvas, "ПАМЯТЬ, ВЫДЕЛЕННАЯ GPU", 32f, memoryTop + 27f, 15f, VIOLET, true,
      maxWidth = width - 64f)
    label(canvas, performance?.let { bytes(it.gpuAllocatedBytes) } ?: "—",
      32f, memoryTop + 64f, 29f, PRIMARY)
    label(canvas, "часть общей RAM · по процессам", 32f, memoryTop + 87f,
      13f, MUTED, maxWidth = width - 64f)
    val entries = performance?.gpuBytesByPid.orEmpty().entries.sortedByDescending { it.value }.take(5)
    if (entries.isEmpty()) label(canvas, "Нет данных о памяти GPU", 32f, memoryTop + 121f,
      14f, MUTED)
    entries.forEachIndexed { index, entry ->
      val app = data.snapshot?.apps?.firstOrNull { entry.key in it.pids }
      val name = app?.packageName ?: performance?.gpuNamesByPid?.get(entry.key) ?: "PID ${entry.key}"
      val y = memoryTop + 116f + index * 22f
      label(canvas, name, 32f, y, 13f, PRIMARY, maxWidth = width - 150f)
      label(canvas, bytes(entry.value), width - 111f, y, 13f, VIOLET, maxWidth = 95f)
    }
    val cpuTop = memoryTop + 252f
    fill(canvas, 16f, cpuTop, width - 16f, cpuTop + 238f, PANEL, 12f)
    outline(canvas, 16f, cpuTop, width - 16f, cpuTop + 238f, LINE, 12f)
    label(canvas, "ЗАГРУЗКА ЯДЕР CPU", 32f, cpuTop + 27f, 15f, CYAN, true)
    val cores = performance?.cpuCores.orEmpty()
    if (cores.isEmpty()) label(canvas, "Нет данных о загрузке CPU", 32f, cpuTop + 71f,
      14f, MUTED)
    cores.take(6).forEachIndexed { index, core ->
      val y = cpuTop + 59f + index * 27f
      label(canvas, core.name.uppercase(), 32f, y, 13f, MUTED)
      meter(canvas, 85f, y - 9f, (width - 178f).coerceAtLeast(30f), 7f,
        ((core.percent ?: 0.0) / 100.0).toFloat(), CYAN)
      label(canvas, percent(core.percent), width - 79f, y, 13f, PRIMARY)
    }
    val ramTop = cpuTop + 252f
    val breakdown = data.snapshot?.memoryBreakdown
    fill(canvas, 16f, ramTop, width - 16f, ramTop + 166f, PANEL, 12f)
    outline(canvas, 16f, ramTop, width - 16f, ramTop + 166f, LINE, 12f)
    label(canvas, "РАЗБИВКА RAM", 32f, ramTop + 27f, 15f, MINT, true)
    val rows = listOf(
      "PSS процессов" to breakdown?.usedPssKb,
      "Ядро" to breakdown?.kernelKb,
      "ZRAM" to breakdown?.zramKb,
      "Доступно" to performance?.memoryAvailableKb,
    )
    rows.forEachIndexed { index, row ->
      val y = ramTop + 56f + index * 27f
      label(canvas, row.first, 32f, y, 13f, MUTED, maxWidth = width - 160f)
      label(canvas, row.second?.let { "${giB(it)} ГиБ" } ?: "—", width - 119f, y,
        13f, PRIMARY, maxWidth = 103f)
    }
    label(canvas, "PSS, ядро и ZRAM — снимок при обновлении списка.",
      16f, ramTop + 190f, 13f, MUTED, maxWidth = width - 32f)
    label(canvas, "GPU-память и RSS не прибавляются к этим числам.",
      16f, ramTop + 211f, 13f, MUTED, maxWidth = width - 32f)
    data.performanceError?.let {
      label(canvas, "Ошибка показателей: $it", 16f, ramTop + 232f, 12f, VIOLET,
        maxWidth = width - 32f)
    }
  }

  private fun drawCompactProcesses(canvas: Canvas, data: PanelState, width: Float) {
    var y = 8f
    var rowIndex = 0
    for (entry in processEntries(data)) {
      if (entry.header != null) {
        label(canvas, entry.header, 16f, y + 24f, 14f, CYAN, true,
          maxWidth = width - 32f)
        y += 32f
        continue
      }
      val app = entry.app ?: continue
      fill(canvas, 16f, y, width - 16f, y + 122f,
        if (app.processes > 0) PANEL_ALT else PANEL, 8f)
      outline(canvas, 16f, y, width - 16f, y + 122f, LINE, 8f)
      if (focusedTarget == FocusTarget(FocusKind.ROW, rowIndex))
        outline(canvas, 16f, y, width - 16f, y + 122f, MINT, 8f, 2f)
      label(canvas, app.packageName, 28f, y + 23f, 14f, PRIMARY,
        maxWidth = width - 56f)
      label(canvas, kindLabel(app.kind) + if (app.pids.isEmpty()) " · PID —" else
        " · PID ${app.pids.joinToString()}",
        28f, y + 43f, 12f, MUTED, maxWidth = width - 56f)
      val metricWidth = width - 122f
      label(canvas, "PSS ${if (app.pssKb > 0) megaBytes(app.pssKb) else "—"}",
        28f, y + 66f, 12f, MINT,
        maxWidth = metricWidth)
      label(canvas, "RSS ${if (app.rssKb > 0) megaBytes(app.rssKb) else "—"}",
        28f, y + 88f, 12f, PRIMARY, maxWidth = metricWidth)
      val gpuBytes = app.pids.sumOf { data.performance?.gpuBytesByPid?.get(it) ?: 0L }
      label(canvas, "GPU-память ${if (gpuBytes > 0) bytes(gpuBytes) else "—"}",
        28f, y + 110f, 12f, VIOLET, maxWidth = metricWidth)
      label(canvas, "⋮", width - 61f, y + 105f, 24f, CYAN)
      y += 132f
      rowIndex++
    }
  }

  private fun drawCompactDevice(canvas: Canvas, data: PanelState, width: Float) {
    val snapshot = data.snapshot
    if (snapshot == null) {
      label(canvas, "Нет данных. Подключите ПК и обновите экран.", 16f, 31f,
        15f, MUTED, maxWidth = width - 32f)
      return
    }
    val fields = listOf(
      "Модель" to snapshot.model,
      "Android" to snapshot.android,
      "Разрешение" to snapshot.display,
      "Плотность" to snapshot.density,
      "Яркость" to snapshot.brightness,
      "RAM, доступная ОС" to (data.performance?.let { giB(it.memoryTotalKb) + " ГиБ" } ?: "—"),
    )
    fields.forEachIndexed { index, field ->
      val y = 8f + index * 88f
      fill(canvas, 16f, y, width - 16f, y + 78f, PANEL, 8f)
      outline(canvas, 16f, y, width - 16f, y + 78f, LINE, 8f)
      label(canvas, field.first, 28f, y + 27f, 14f, MUTED, maxWidth = width - 56f)
      label(canvas, field.second, 28f, y + 57f, 16f, PRIMARY, maxWidth = width - 56f)
    }
    label(canvas, "ADB-диагностика сохраняется на подключённом ПК.",
      16f, 6 * 88f + 37f, 13f, MUTED, maxWidth = width - 32f)
  }

  private fun selectedApp(data: PanelState): ManagedApp? =
    data.snapshot?.apps?.firstOrNull { it.packageName == data.selectedPackage }

  private fun availableActions(data: PanelState): List<ControlAction> {
    val app = selectedApp(data) ?: return emptyList()
    val detail = data.selectedStatus ?: return emptyList()
    if (app.kind != EntryKind.USER || app.packageName == "com.adaptivecodex.quest.manager")
      return emptyList()
    return buildList {
      if (!detail.stopped) add(ControlAction.STOP)
      if (detail.launchComponent != null && !detail.suspended) add(ControlAction.RESTART)
      add(if (detail.suspended) ControlAction.UNSUSPEND else ControlAction.SUSPEND)
    }
  }

  private fun drawCompactControl(canvas: Canvas, data: PanelState, width: Float) {
    val app = selectedApp(data)
    if (app == null) {
      label(canvas, "Выберите строку в разделе «Процессы»", 16f, 34f, 16f,
        PRIMARY, maxWidth = width - 32f)
      label(canvas, "и откройте меню ⋮ → Подробнее.", 16f, 62f, 14f, MUTED,
        maxWidth = width - 32f)
      return
    }
    val detail = data.selectedStatus
    val gpu = app.pids.sumOf { data.performance?.gpuBytesByPid?.get(it) ?: 0L }
    fill(canvas, 16f, 8f, width - 16f, 246f, PANEL, 9f)
    outline(canvas, 16f, 8f, width - 16f, 246f, LINE, 9f)
    label(canvas, app.packageName, 28f, 38f, 16f, PRIMARY, true, maxWidth = width - 56f)
    label(canvas, kindLabel(app.kind), 28f, 64f, 13f, CYAN)
    label(canvas, "Процессы: ${if (detail?.running == true) "запущены" else if (detail == null) "—" else "не запущены"}",
      28f, 96f, 14f, PRIMARY, maxWidth = width - 56f)
    label(canvas, "Запуск: ${if (detail?.suspended == true) "запрещён" else if (detail == null) "—" else "разрешён"}",
      28f, 122f, 14f, PRIMARY, maxWidth = width - 56f)
    label(canvas, "PID ${detail?.pids?.joinToString()?.ifBlank { "—" } ?: app.pids.joinToString().ifBlank { "—" }}",
      28f, 149f, 13f, MUTED, maxWidth = width - 56f)
    label(canvas, "PSS ${megaBytes(app.pssKb)} · RSS ${megaBytes(app.rssKb)}", 28f, 180f,
      13f, MINT, maxWidth = width - 56f)
    label(canvas, "GPU-память ${if (gpu > 0) bytes(gpu) else "—"}", 28f, 207f,
      13f, VIOLET, maxWidth = width - 56f)
    label(canvas, "Принудительно остановлен: ${if (detail?.stopped == true) "да" else if (detail == null) "—" else "нет"}",
      28f, 232f, 12f, MUTED, maxWidth = width - 56f)
    val actions = availableActions(data)
    if (actions.isEmpty()) {
      label(canvas, if (app.kind == EntryKind.USER) "Получение состояния или действия недоступны"
        else "Системный объект: только просмотр", 16f, 285f, 14f, MUTED,
        maxWidth = width - 32f)
    }
    actions.forEachIndexed { index, action ->
      val top = 276f + index * 60f
      outline(canvas, 16f, top, width - 16f, top + 48f,
        if (focusedTarget == FocusTarget(FocusKind.ACTION, index)) MINT else CYAN, 8f,
        if (focusedTarget == FocusTarget(FocusKind.ACTION, index)) 2f else 1f)
      label(canvas, action.title, 28f, top + 30f, 15f, PRIMARY,
        maxWidth = width - 56f)
    }
    label(canvas, "Запрет запуска — системная приостановка пакета.", 16f, 499f,
      12f, MUTED, maxWidth = width - 32f)
    label(canvas, "Это не сохранение процесса в памяти.", 16f, 523f,
      12f, MUTED, maxWidth = width - 32f)
    data.actionFeedback?.let { label(canvas, it, 16f, 558f, 13f, MINT,
      maxWidth = width - 32f) }
  }

  private fun drawCompactConfirmation(canvas: Canvas, view: Viewport) {
    val width = view.width
    val top = ((view.height - 226f) / 2f).coerceAtLeast(12f)
    fill(canvas, 0f, 0f, width, view.height, Color.argb(185, 0, 0, 0))
    fill(canvas, 16f, top, width - 16f, top + 226f, PANEL, 12f)
    outline(canvas, 16f, top, width - 16f, top + 226f, CYAN, 12f)
    label(canvas, "Принудительно остановить?", 30f, top + 39f, 19f, PRIMARY, true,
      maxWidth = width - 60f)
    label(canvas, "Пакет ${BridgeProtocol.TEST_PACKAGE}.", 30f, top + 77f, 14f, MUTED,
      maxWidth = width - 60f)
    label(canvas, "Его процессы будут завершены.", 30f, top + 104f, 14f, MUTED,
      maxWidth = width - 60f)
    outline(canvas, 30f, top + 159f, 135f, top + 205f, LINE, 8f)
    if (focusedTarget == FocusTarget(FocusKind.CANCEL))
      outline(canvas, 28f, top + 157f, 137f, top + 207f, MINT, 8f, 2f)
    label(canvas, "Отмена", 51f, top + 190f, 15f, PRIMARY)
    fill(canvas, width - 161f, top + 159f, width - 30f, top + 205f, CYAN, 8f)
    if (focusedTarget == FocusTarget(FocusKind.STOP))
      outline(canvas, width - 163f, top + 157f, width - 28f, top + 207f, MINT, 8f, 2f)
    label(canvas, "Остановить", width - 150f, top + 190f, 15f, BACKGROUND, true)
  }

  private fun drawSidebar(canvas: Canvas) {
    fill(canvas, 0f, 0f, 202f, BASE_HEIGHT, SIDEBAR)
    line(canvas, 202f, 0f, 202f, BASE_HEIGHT, LINE)
    label(canvas, "QUEST / MANAGER", 20f, 38f, 17f, CYAN, true)
    label(canvas, "CPU · GPU · RAM", 20f, 66f, 14f, MUTED)
    PanelSection.entries.forEachIndexed { index, item ->
      val top = 108f + index * 57f
      if (section == item) {
        fill(canvas, 12f, top, 190f, top + 45f, PANEL_ALT, 8f)
        outline(canvas, 12f, top, 190f, top + 45f, CYAN, 8f)
      }
      if (focusedTarget == FocusTarget(FocusKind.TAB, index))
        outline(canvas, 12f, top, 190f, top + 45f, MINT, 8f, 2f)
      label(canvas, item.title, 24f, top + 29f, 16f, if (section == item) PRIMARY else MUTED,
        section == item)
    }
    label(canvas, "QUEST 3S", 20f, 672f, 14f, CYAN)
    label(canvas, "ADB / ПК", 20f, 697f, 14f, MUTED)
  }

  private fun drawHeader(canvas: Canvas, data: PanelState) {
    label(canvas, "QUEST 3S · ADB", 224f, 26f, 14f, CYAN)
    label(canvas, section.title, 224f, 55f, 25f, PRIMARY, true)
    if (section == PanelSection.PROCESSES) {
      outline(canvas, 700f, 17f, 948f, 62f, LINE, 9f)
      if (focusedTarget == FocusTarget(FocusKind.SEARCH))
        outline(canvas, 700f, 17f, 948f, 62f, MINT, 9f, 2f)
      label(canvas, search.ifBlank { "Пакет или PID  ·  поиск" }, 715f, 45f, 14f,
        if (search.isBlank()) MUTED else PRIMARY, maxWidth = 220f)
    }
    outline(canvas, 973f, 18f, 1078f, 58f, CYAN, 9f)
    if (focusedTarget == FocusTarget(FocusKind.REFRESH))
      outline(canvas, 971f, 16f, 1080f, 60f, MINT, 9f, 2f)
    label(canvas, if (data.busy) "Загрузка" else "Обновить", 988f, 44f, 14f, CYAN)
    fill(canvas, 202f, 75f, BASE_WIDTH, 111f, PANEL)
    fill(canvas, 224f, 89f, 232f, 97f, if (data.status.startsWith("ПК подключён")) MINT else MUTED, 4f)
    label(canvas, panelStatus(data), 242f, 99f, 14f, MUTED, maxWidth = 820f)
  }

  private fun panelStatus(data: PanelState): String {
    val snapshot = data.snapshot ?: return data.status
    if (!data.status.startsWith("ПК подключён")) return data.status
    val packages = snapshot.apps.count { it.kind != EntryKind.SYSTEM_PROCESS }
    val processes = snapshot.apps.size - packages
    return "ПК подключён · $packages пакетов · $processes отдельных процессов"
  }

  private fun drawPerformance(canvas: Canvas, data: PanelState) {
    val performance = data.performance
    val total = performance?.memoryTotalKb ?: 0L
    val used = (total - (performance?.memoryAvailableKb ?: 0L)).coerceAtLeast(0)
    val memoryPercent = if (total > 0) used.toDouble() * 100.0 / total else null
    label(canvas, "ЗАГРУЗКА И ИСПОЛЬЗОВАНИЕ ПАМЯТИ", 224f, 142f, 15f, MUTED, true)
    signalCard(canvas, 224f, 157f, 276f, "ЗАГРУЗКА CPU", performance?.cpuPercent,
      "среднее по ядрам", data.cpuHistory, CYAN)
    signalCard(canvas, 512f, 157f, 276f, "ЗАГРУЗКА GPU", performance?.gpuPercent,
      "доля времени работы", data.gpuHistory, VIOLET)
    signalCard(canvas, 800f, 157f, 278f, "ИСПОЛЬЗОВАНИЕ RAM", memoryPercent,
      if (total > 0) "${giB(used)} / ${giB(total)} ГиБ" else "всего − доступно",
      data.memoryHistory, MINT)

    fill(canvas, 224f, 410f, 685f, 649f, PANEL, 12f)
    outline(canvas, 224f, 410f, 685f, 649f, LINE, 12f)
    label(canvas, "ПАМЯТЬ, ВЫДЕЛЕННАЯ GPU", 240f, 436f, 15f, VIOLET, true)
    label(canvas, performance?.let { bytes(it.gpuAllocatedBytes) } ?: "—", 240f, 472f, 29f, PRIMARY)
    label(canvas, "часть общей RAM · по процессам", 240f, 494f, 13f, MUTED)
    val entries = performance?.gpuBytesByPid.orEmpty().entries.sortedByDescending { it.value }.take(5)
    if (entries.isEmpty()) label(canvas, "Нет данных о памяти GPU", 240f, 528f, 14f, MUTED)
    entries.forEachIndexed { index, entry ->
      val app = data.snapshot?.apps?.firstOrNull { entry.key in it.pids }
      val name = app?.packageName ?: performance?.gpuNamesByPid?.get(entry.key) ?: "PID ${entry.key}"
      val y = 522f + index * 24f
      label(canvas, name, 240f, y, 13f, PRIMARY, maxWidth = 345f)
      label(canvas, bytes(entry.value), 598f, y, 13f, VIOLET, maxWidth = 72f)
    }

    fill(canvas, 698f, 410f, 1078f, 649f, PANEL, 12f)
    outline(canvas, 698f, 410f, 1078f, 649f, LINE, 12f)
    label(canvas, "ЗАГРУЗКА ЯДЕР CPU", 714f, 436f, 15f, CYAN, true)
    val cores = performance?.cpuCores.orEmpty()
    if (cores.isEmpty()) label(canvas, "Нет данных о загрузке CPU", 714f, 480f, 14f, MUTED)
    cores.take(6).forEachIndexed { index, core ->
      val y = 468f + index * 27f
      label(canvas, core.name.uppercase(), 714f, y, 13f, MUTED)
      meter(canvas, 770f, y - 9f, 238f, 7f, ((core.percent ?: 0.0) / 100.0).toFloat(), CYAN)
      label(canvas, percent(core.percent), 1022f, y, 13f, PRIMARY)
    }
    val breakdown = data.snapshot?.memoryBreakdown
    val pss = breakdown?.let { "${giB(it.usedPssKb)} ГиБ" } ?: "—"
    val kernel = breakdown?.let { "${giB(it.kernelKb)} ГиБ" } ?: "—"
    val zram = breakdown?.let { "${giB(it.zramKb)} ГиБ" } ?: "—"
    label(canvas, "RAM по dumpsys: PSS процессов $pss · ядро $kernel · ZRAM $zram.",
      224f, 678f, 13f, MUTED, maxWidth = 850f)
    label(canvas, "Доступно ${if (total > 0) giB(performance?.memoryAvailableKb ?: 0) + " ГиБ" else "—"}. " +
      "RSS и GPU-выделения не прибавляются к PSS. Снимок разбивки — при обновлении списка.",
      224f, 699f, 13f, MUTED, maxWidth = 850f)
    data.performanceError?.let { label(canvas, "Ошибка показателей: $it", 224f, 718f, 12f, VIOLET) }
  }

  private fun signalCard(canvas: Canvas, x: Float, y: Float, width: Float, title: String,
                         value: Double?, detail: String, history: List<Float>, accent: Int) {
    fill(canvas, x, y, x + width, y + 236f, PANEL, 12f)
    outline(canvas, x, y, x + width, y + 236f, accent, 12f)
    label(canvas, title, x + 15f, y + 27f, 15f, accent, true)
    label(canvas, percent(value), x + 15f, y + 86f, 43f, PRIMARY)
    meter(canvas, x + 15f, y + 105f, width - 30f, 7f, ((value ?: 0.0) / 100.0).toFloat(), accent)
    chart(canvas, x + 15f, y + 129f, width - 30f, 67f, history, accent)
    label(canvas, detail, x + 15f, y + 220f, 13f, MUTED, maxWidth = width - 30f)
  }

  private fun meter(canvas: Canvas, x: Float, y: Float, width: Float, height: Float,
                    fraction: Float, accent: Int) {
    fill(canvas, x, y, x + width, y + height, LINE)
    fill(canvas, x, y, x + width * fraction.coerceIn(0f, 1f), y + height, accent)
  }

  private fun chart(canvas: Canvas, x: Float, y: Float, width: Float, height: Float,
                    history: List<Float>, accent: Int) {
    line(canvas, x, y + height / 3f, x + width, y + height / 3f, LINE)
    line(canvas, x, y + 2f * height / 3f, x + width, y + 2f * height / 3f, LINE)
    val values = if (history.size == 1) listOf(history[0], history[0]) else history
    if (values.size < 2) return
    val path = Path()
    values.forEachIndexed { index, value ->
      val px = x + width * index / (values.size - 1)
      val py = y + height * (1f - value.coerceIn(0f, 1f))
      if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = accent
    canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL
  }

  private data class ProcessEntry(val header: String? = null, val app: ManagedApp? = null)

  private fun kindLabel(kind: EntryKind) = when (kind) {
    EntryKind.USER -> "ПРИЛОЖЕНИЕ"
    EntryKind.SYSTEM -> "СИСТЕМА"
    EntryKind.SYSTEM_PROCESS -> "СЛУЖБА"
  }

  private fun processEntries(data: PanelState): List<ProcessEntry> {
    val query = search.trim()
    val apps = data.snapshot?.apps.orEmpty().filter { app ->
      query.isEmpty() || app.packageName.contains(query, true) ||
        app.pids.any { it.toString().contains(query) }
    }
    return buildList {
      fun group(title: String, rows: List<ManagedApp>) {
        add(ProcessEntry(header = "$title: ${rows.size}"))
        rows.forEach { add(ProcessEntry(app = it)) }
      }
      group("ЗАПУЩЕННЫЕ ПРИЛОЖЕНИЯ", apps.filter {
        it.processes > 0 && it.kind == EntryKind.USER
      })
      group("НЕ ЗАПУЩЕННЫЕ ПРИЛОЖЕНИЯ", apps.filter {
        it.processes == 0 && it.kind == EntryKind.USER
      })
      group("ЗАПУЩЕННЫЕ СИСТЕМНЫЕ ПАКЕТЫ", apps.filter {
        it.processes > 0 && it.kind == EntryKind.SYSTEM
      })
      group("СИСТЕМНЫЕ И ПРОЧИЕ ПРОЦЕССЫ", apps.filter {
        it.kind == EntryKind.SYSTEM_PROCESS
      })
      group("НЕ ЗАПУЩЕННЫЕ СИСТЕМНЫЕ ПАКЕТЫ", apps.filter {
        it.processes == 0 && it.kind == EntryKind.SYSTEM
      })
    }
  }

  private fun drawProcesses(canvas: Canvas, data: PanelState) {
    label(canvas, "PSS — доля RAM процесса; RSS включает общие страницы; GPU-память не суммируется с ними.",
      224f, 139f, 13f, MUTED, maxWidth = 850f)
    label(canvas, "ПАКЕТ ИЛИ ПРОЦЕСС / PID", 224f, 173f, 13f, MUTED)
    label(canvas, "PSS", 655f, 173f, 13f, MUTED)
    label(canvas, "RSS", 765f, 173f, 13f, MUTED)
    label(canvas, "GPU-ПАМЯТЬ", 865f, 173f, 13f, MUTED)
    line(canvas, 224f, 181f, 1078f, 181f, LINE)
    canvas.save()
    canvas.clipRect(202f, 183f, 1100f, 720f)
    var y = 187f - processScroll
    var rowIndex = 0
    for (entry in processEntries(data)) {
      if (entry.header != null) {
        if (y + 32f >= 183f && y < 720f) {
          fill(canvas, 202f, y, 1100f, y + 32f, BACKGROUND)
          label(canvas, entry.header, 224f, y + 22f, 14f, CYAN, true)
        }
        y += 32f
        continue
      }
      val app = entry.app ?: continue
      if (y + 47f >= 183f && y < 720f) {
        fill(canvas, 202f, y, 1100f, y + 47f, if (app.processes > 0) PANEL_ALT else PANEL)
        if (focusedTarget == FocusTarget(FocusKind.ROW, rowIndex))
          outline(canvas, 207f, y + 2f, 1094f, y + 45f, MINT, 5f, 2f)
        label(canvas, app.packageName, 224f, y + 20f, 14f, PRIMARY, maxWidth = 415f)
        val detail = kindLabel(app.kind) + if (app.pids.isNotEmpty())
          " · PID ${app.pids.joinToString()}" else " · не запущен"
        label(canvas, detail, 224f, y + 39f, 12f, MUTED, maxWidth = 415f)
        label(canvas, if (app.pssKb > 0) megaBytes(app.pssKb) else "—",
          655f, y + 28f, 13f, MINT, maxWidth = 100f)
        label(canvas, if (app.rssKb > 0) megaBytes(app.rssKb) else "—",
          765f, y + 28f, 13f, PRIMARY, maxWidth = 90f)
        val gpuBytes = app.pids.sumOf { data.performance?.gpuBytesByPid?.get(it) ?: 0L }
        label(canvas, if (gpuBytes > 0) bytes(gpuBytes) else "—",
          865f, y + 28f, 13f, VIOLET, maxWidth = 100f)
        label(canvas, "⋮", 1044f, y + 33f, 25f, CYAN)
        line(canvas, 202f, y + 46f, 1100f, y + 46f, LINE)
      }
      y += 47f
      rowIndex++
    }
    canvas.restore()
  }

  private fun drawDevice(canvas: Canvas, data: PanelState) {
    label(canvas, "ПАРАМЕТРЫ ШЛЕМА", 224f, 146f, 15f, CYAN, true)
    val snapshot = data.snapshot
    if (snapshot == null) {
      label(canvas, "Нет данных. Подключите ПК и обновите экран.", 224f, 190f, 15f, MUTED)
      return
    }
    val fields = listOf(
      "Модель" to snapshot.model,
      "Android" to snapshot.android,
      "Разрешение" to snapshot.display,
      "Плотность" to snapshot.density,
      "Яркость" to snapshot.brightness,
      "RAM, доступная ОС" to (data.performance?.let { giB(it.memoryTotalKb) + " ГиБ" } ?: "—"),
    )
    fields.forEachIndexed { index, field ->
      val y = 166f + index * 68f
      fill(canvas, 224f, y, 1078f, y + 55f, PANEL, 8f)
      outline(canvas, 224f, y, 1078f, y + 55f, LINE, 8f)
      label(canvas, field.first, 240f, y + 34f, 16f, MUTED)
      label(canvas, field.second, 448f, y + 34f, 16f, PRIMARY, maxWidth = 610f)
    }
    label(canvas, "ADB-диагностика сохраняется на подключённом ПК.", 224f, 612f, 14f, MUTED)
  }

  private fun drawControl(canvas: Canvas, data: PanelState) {
    label(canvas, "УПРАВЛЕНИЕ ПАКЕТОМ", 224f, 145f, 15f, CYAN, true)
    val app = selectedApp(data)
    if (app == null) {
      label(canvas, "Выберите строку в разделе «Процессы» и откройте ⋮ → Подробнее.",
        224f, 202f, 17f, PRIMARY, maxWidth = 830f)
      return
    }
    val detail = data.selectedStatus
    val gpu = app.pids.sumOf { data.performance?.gpuBytesByPid?.get(it) ?: 0L }
    fill(canvas, 224f, 165f, 1078f, 390f, PANEL, 12f)
    outline(canvas, 224f, 165f, 1078f, 390f, LINE, 12f)
    label(canvas, app.packageName, 242f, 200f, 21f, PRIMARY, true, maxWidth = 800f)
    label(canvas, kindLabel(app.kind), 242f, 228f, 13f, CYAN)
    label(canvas, "Процессы: ${if (detail?.running == true) "запущены" else if (detail == null) "—" else "не запущены"}",
      242f, 266f, 16f, PRIMARY)
    label(canvas, "Запуск: ${if (detail?.suspended == true) "запрещён" else if (detail == null) "—" else "разрешён"}",
      600f, 266f, 16f, PRIMARY)
    label(canvas, "PID ${detail?.pids?.joinToString()?.ifBlank { "—" } ?: app.pids.joinToString().ifBlank { "—" }}",
      242f, 301f, 14f, MUTED, maxWidth = 750f)
    label(canvas, "Принудительно остановлен: ${if (detail?.stopped == true) "да" else if (detail == null) "—" else "нет"}",
      242f, 324f, 13f, MUTED)
    label(canvas, "PSS ${megaBytes(app.pssKb)}", 242f, 351f, 15f, MINT)
    label(canvas, "RSS ${megaBytes(app.rssKb)}", 485f, 351f, 15f, PRIMARY)
    label(canvas, "GPU-память ${if (gpu > 0) bytes(gpu) else "—"}", 730f, 351f, 15f, VIOLET)
    val actions = availableActions(data)
    label(canvas, "ДЕЙСТВИЯ", 224f, 431f, 15f, CYAN, true)
    if (actions.isEmpty()) label(canvas,
      if (app.kind == EntryKind.USER) "Получение состояния или действия недоступны"
      else "Системный объект: только просмотр", 224f, 475f, 15f, MUTED)
    actions.forEachIndexed { index, action ->
      val left = if (index % 2 == 0) 224f else 660f
      val top = 451f + (index / 2) * 70f
      outline(canvas, left, top, left + 418f, top + 54f,
        if (focusedTarget == FocusTarget(FocusKind.ACTION, index)) MINT else CYAN, 9f,
        if (focusedTarget == FocusTarget(FocusKind.ACTION, index)) 2f else 1f)
      label(canvas, action.title, left + 17f, top + 34f, 17f, PRIMARY)
    }
    label(canvas, "Запрет запуска — системная приостановка пакета, не сон с сохранением процесса.",
      224f, 660f, 13f, MUTED, maxWidth = 850f)
    label(canvas, "Состояние подтверждается после команды через ADB-мост на ПК.",
      224f, 685f, 13f, MUTED, maxWidth = 850f)
    data.actionFeedback?.let { label(canvas, it, 224f, 626f, 14f, MINT, maxWidth = 850f) }
  }

  private fun drawConfirmation(canvas: Canvas) {
    fill(canvas, 0f, 0f, BASE_WIDTH, BASE_HEIGHT, Color.argb(185, 0, 0, 0))
    fill(canvas, 280f, 240f, 820f, 474f, PANEL, 14f)
    outline(canvas, 280f, 240f, 820f, 474f, CYAN, 14f)
    label(canvas, "Принудительно остановить приложение?", 310f, 289f, 22f, PRIMARY, true)
    label(canvas, "Пакет ${BridgeProtocol.TEST_PACKAGE}.", 310f, 329f, 16f, MUTED)
    label(canvas, "Его процессы будут завершены.", 310f, 356f, 16f, MUTED)
    outline(canvas, 506f, 406f, 636f, 452f, LINE, 8f)
    if (focusedTarget == FocusTarget(FocusKind.CANCEL))
      outline(canvas, 504f, 404f, 638f, 454f, MINT, 8f, 2f)
    label(canvas, "Отмена", 541f, 436f, 16f, PRIMARY)
    fill(canvas, 650f, 406f, 790f, 452f, CYAN, 8f)
    if (focusedTarget == FocusTarget(FocusKind.STOP))
      outline(canvas, 648f, 404f, 792f, 454f, MINT, 8f, 2f)
    label(canvas, "Остановить", 668f, 436f, 16f, BACKGROUND, true)
  }

  private fun processScrollMax(data: PanelState): Float {
    val height = processEntries(data).fold(0) { sum, entry ->
      sum + if (entry.header != null) 32 else 47
    }
    return (height - 530).coerceAtLeast(0).toFloat()
  }

  private fun scrollCurrent(delta: Float): Boolean {
    if (confirmStop) return false
    val view = viewport(max(1, width), max(1, height))
    if (view.compact) {
      compactScroll = (compactScroll + delta).coerceIn(0f, compactScrollMax(view, state))
    } else if (section == PanelSection.PROCESSES) {
      processScroll = (processScroll + delta).coerceIn(0f, processScrollMax(state))
    } else return false
    requestRender()
    return true
  }

  private fun focusTargets(): List<FocusTarget> {
    if (confirmStop) return listOf(FocusTarget(FocusKind.CANCEL), FocusTarget(FocusKind.STOP))
    if (managementOpen) return buildList {
      add(FocusTarget(FocusKind.BACK))
      availableActions(state).indices.forEach { add(FocusTarget(FocusKind.ACTION, it)) }
    }
    return buildList {
      PanelSection.entries.indices.forEach { add(FocusTarget(FocusKind.TAB, it)) }
      add(FocusTarget(FocusKind.REFRESH))
      if (section == PanelSection.PROCESSES) {
        add(FocusTarget(FocusKind.SEARCH))
        val rows = processEntries(state).count { it.app != null }
        repeat(rows) { add(FocusTarget(FocusKind.ROW, it)) }
      }
    }
  }

  private fun focusRowVisible(index: Int) {
    val view = viewport(max(1, width), max(1, height))
    val top = if (view.compact) compactContentTop() else 187f
    val bottom = if (view.compact) view.height else BASE_HEIGHT
    val rowHeight = if (view.compact) 132f else 47f
    var offset = if (view.compact) 8f else 0f
    var current = 0
    for (entry in processEntries(state)) {
      if (entry.header != null) { offset += 32f; continue }
      if (current++ == index) break
      offset += rowHeight
    }
    val currentScroll = if (view.compact) compactScroll else processScroll
    val visibleHeight = bottom - top
    val targetScroll = when {
      offset < currentScroll -> offset
      offset + rowHeight > currentScroll + visibleHeight -> offset + rowHeight - visibleHeight
      else -> currentScroll
    }
    if (view.compact) compactScroll = targetScroll.coerceIn(0f, compactScrollMax(view, state))
    else processScroll = targetScroll.coerceIn(0f, processScrollMax(state))
  }

  private fun moveFocus(delta: Int) {
    val targets = focusTargets()
    if (targets.isEmpty()) return
    val index = targets.indexOf(focusedTarget)
    focusedTarget = targets[(if (index < 0) 0 else index + delta).coerceIn(0, targets.lastIndex)]
    focusedTarget?.takeIf { it.kind == FocusKind.ROW }?.let { focusRowVisible(it.index) }
    focusedTarget?.takeIf { it.kind == FocusKind.ACTION }?.let {
      val view = viewport(max(1, width), max(1, height))
      if (view.compact) {
        val actionBottom = 276f + it.index * 60f + 48f
        compactScroll = max(compactScroll, actionBottom - (view.height - compactContentTop()))
          .coerceIn(0f, compactScrollMax(view, state))
      }
    }
    requestRender()
  }

  private fun selectFocus() {
    val target = focusedTarget ?: if (managementOpen) FocusTarget(FocusKind.BACK)
      else FocusTarget(FocusKind.TAB, section.ordinal)
    when (target.kind) {
      FocusKind.TAB -> {
        section = PanelSection.entries[target.index]
        compactScroll = 0f
        focusedTarget = target
      }
      FocusKind.REFRESH -> onRefresh()
      FocusKind.SEARCH -> onSearch(search) { value ->
        search = value
        compactScroll = 0f
        processScroll = 0f
        requestFocus()
        requestRender()
      }
      FocusKind.ROW -> {
        val app = processEntries(state).mapNotNull { it.app }.getOrNull(target.index)
        if (app != null) onOpenAppMenu(app)
      }
      FocusKind.BACK -> closeManagement()
      FocusKind.ACTION -> {
        val app = selectedApp(state)
        val action = availableActions(state).getOrNull(target.index)
        if (app != null && action != null) onControlAction(app, action)
      }
      FocusKind.CANCEL -> { confirmStop = false; focusedTarget = null }
      FocusKind.STOP -> { confirmStop = false; focusedTarget = null; onStop() }
    }
    requestRender()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> { noteInput("key", event.source); moveFocus(-1); true }
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> {
      noteInput("key", event.source); moveFocus(1); true
    }
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
      noteInput("key", event.source)
      if (confirmStop || managementOpen) moveFocus(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
      else {
        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        section = PanelSection.entries[(section.ordinal + step).coerceIn(0, PanelSection.entries.lastIndex)]
        compactScroll = 0f
        focusedTarget = FocusTarget(FocusKind.TAB, section.ordinal)
        requestRender()
      }
      true
    }
    KeyEvent.KEYCODE_PAGE_UP -> scrollCurrent(-400f)
    KeyEvent.KEYCODE_PAGE_DOWN -> scrollCurrent(400f)
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> {
      noteInput("key", event.source)
      if (event.repeatCount == 0) selectFocus()
      true
    }
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
      if (confirmStop) { confirmStop = false; focusedTarget = null; requestRender(); true }
      else if (managementOpen) { closeManagement(); true }
      else super.onKeyDown(keyCode, event)
    }
    else -> super.onKeyDown(keyCode, event)
  }

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
      val amount = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
      if (abs(amount) > 0.001f && scrollCurrent(-amount * 96f)) {
        noteInput("scroll", event.source)
        return true
      }
    }
    if (event.actionMasked == MotionEvent.ACTION_MOVE &&
        event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
      val axis = listOf(MotionEvent.AXIS_Y, MotionEvent.AXIS_RY,
        MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_Y)
        .maxByOrNull { abs(event.getAxisValue(it)) } ?: MotionEvent.AXIS_Y
      val value = event.getAxisValue(axis)
      val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
      if (abs(value) > max(0.2f, flat)) {
        noteInput("stick axis=$axis", event.source)
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastStickTime == 0L) 16L else (now - lastStickTime).coerceIn(0, 50)
        lastStickTime = now
        if (scrollCurrent(value * 520f * elapsed / 1000f)) return true
      } else lastStickTime = 0L
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val view = viewport(max(1, width), max(1, height))
    val x = (event.x - view.x) / view.scale
    val y = (event.y - view.y) / view.scale
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        noteInput("pointer", event.source)
        requestFocus()
        focusedTarget = null
        requestRender()
        touchDownY = y
        touchLastY = y
        dragged = false
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (view.compact && touchDownY >= compactContentTop() && !confirmStop) {
          if (abs(y - touchDownY) > 8f) dragged = true
          if (dragged) {
            compactScroll = (compactScroll + touchLastY - y)
              .coerceIn(0f, compactScrollMax(view, state))
            requestRender()
          }
        } else if (!view.compact && section == PanelSection.PROCESSES &&
            touchDownY > 183f && !confirmStop) {
          if (abs(y - touchDownY) > 8f) dragged = true
          if (dragged) {
            processScroll = (processScroll + touchLastY - y)
              .coerceIn(0f, processScrollMax(state))
            requestRender()
          }
        }
        touchLastY = y
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (dragged) return true
        if (managementOpen) {
          if (view.compact) {
            if (x in 16f..115f && y in 14f..55f) { closeManagement(); return true }
            val topOffset = compactContentTop() - compactScroll
            availableActions(state).forEachIndexed { index, action ->
              val top = topOffset + 276f + index * 60f
              if (x in 16f..(view.width - 16f) && y in top..(top + 48f)) {
                selectedApp(state)?.let { onControlAction(it, action) }
                return true
              }
            }
          } else {
            if (x in 20f..170f && y in 23f..66f) { closeManagement(); return true }
            availableActions(state).forEachIndexed { index, action ->
              val left = if (index % 2 == 0) 224f else 660f
              val top = 451f + (index / 2) * 70f
              if (x in left..(left + 418f) && y in top..(top + 54f)) {
                selectedApp(state)?.let { onControlAction(it, action) }
                return true
              }
            }
          }
          return true
        }
        if (view.compact) {
          if (x !in 0f..view.width || y !in 0f..view.height) return true
          if (confirmStop) {
            val dialogTop = ((view.height - 226f) / 2f).coerceAtLeast(12f)
            if (x in (view.width - 161f)..(view.width - 30f) &&
                y in (dialogTop + 159f)..(dialogTop + 205f)) onStop()
            confirmStop = false
            requestRender()
            return true
          }
          if (x in (view.width - 120f)..(view.width - 16f) && y in 7f..41f) {
            onRefresh()
            return true
          }
          if (y in 52f..95f && x in 16f..(view.width - 16f)) {
            val tabWidth = (view.width - 32f) / PanelSection.entries.size
            val index = ((x - 16f) / tabWidth).toInt()
              .coerceIn(0, PanelSection.entries.lastIndex)
            section = PanelSection.entries[index]
            compactScroll = 0f
            requestRender()
            return true
          }
          if (section == PanelSection.PROCESSES) {
            if (x in 16f..(view.width - 16f) && y in 148f..188f) {
              onSearch(search) { value ->
                search = value
                compactScroll = 0f
                requestRender()
              }
              return true
            }
            if (y >= COMPACT_SEARCH_BOTTOM) {
              var rowTop = COMPACT_SEARCH_BOTTOM + 8f -
                compactScroll.coerceIn(0f, compactScrollMax(view, state))
              for (entry in processEntries(state)) {
                if (entry.header != null) { rowTop += 32f; continue }
                val app = entry.app ?: continue
                if (x in (view.width - 79f)..(view.width - 28f) &&
                    y in (rowTop + 78f)..(rowTop + 113f)) {
                  onOpenAppMenu(app)
                  return true
                }
                rowTop += 132f
              }
            }
          }
          return true
        }
        if (x !in 0f..BASE_WIDTH || y !in 0f..BASE_HEIGHT) return true
        if (confirmStop) {
          if (x in 650f..790f && y in 406f..452f) onStop()
          confirmStop = false
          requestRender()
          return true
        }
        if (x < 202f) {
          PanelSection.entries.forEachIndexed { index, item ->
            val top = 108f + index * 57f
            if (y in top..(top + 45f)) {
              section = item
              requestRender()
            }
          }
          return true
        }
        if (x in 973f..1078f && y in 18f..58f) { onRefresh(); return true }
        if (section == PanelSection.PROCESSES) {
          if (x in 700f..948f && y in 17f..62f) {
            onSearch(search) { value -> search = value; processScroll = 0f; requestRender() }
            return true
          }
          var rowTop = 187f - processScroll
          for (entry in processEntries(state)) {
            if (entry.header != null) { rowTop += 32f; continue }
            val app = entry.app ?: continue
            if (y in rowTop..(rowTop + 47f) && x in 1026f..1078f) {
              onOpenAppMenu(app)
              return true
            }
            rowTop += 47f
          }
        }
        return true
      }
    }
    return true
  }

  private fun percent(value: Double?): String = if (value == null) "—" else
    String.format(Locale.US, "%.0f%%", value.coerceIn(0.0, 100.0))
  private fun giB(kb: Long) = String.format(Locale.US, "%.2f", kb / 1048576.0)
  private fun megaBytes(kb: Long) = String.format(Locale.US, "%.0f МБ", kb / 1024.0)
  private fun bytes(value: Long): String = if (value >= 1073741824L)
    String.format(Locale.US, "%.2f ГиБ", value / 1073741824.0)
  else String.format(Locale.US, "%.0f МиБ", value / 1048576.0)
}

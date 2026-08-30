package cz.courierledger.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import cz.courierledger.backup.BackupManager
import cz.courierledger.security.BiometricGate
import cz.courierledger.security.PinManager
import cz.courierledger.security.LicenseManager
import cz.courierledger.security.LicenseState
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.courierledger.R
import cz.courierledger.BuildConfig
import cz.courierledger.db.*
import cz.courierledger.domain.CourierRepository
import cz.courierledger.domain.KurierXFeatureStore
import cz.courierledger.domain.EarningsCalculator
import cz.courierledger.domain.ShiftHistorySummary
import cz.courierledger.domain.PeriodStatistics
import cz.courierledger.domain.StatisticsSnapshotComparison
import cz.courierledger.domain.PeriodMoneyResult
import cz.courierledger.domain.AnalyticsOverview
import cz.courierledger.maps.MapLauncher
import cz.courierledger.ocr.CalendarImportEntry
import cz.courierledger.ocr.CalendarOcrParser
import cz.courierledger.ocr.OcrEngine
import cz.courierledger.ocr.OcrParsers
import cz.courierledger.ocr.FinancialRowParse
import cz.courierledger.ruian.RuianStreetIndex
import cz.courierledger.settings.AppSettings
import cz.courierledger.settings.MapProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class Tab(val title: String) {
    HOME("Главная"), CALENDAR("Календарь"), STATS("Статистика"), SCANNER("Сканер"), MORE("Ещё")
}

private val AppDarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF8BC34A),
    secondary = androidx.compose.ui.graphics.Color(0xFFB7D87A),
    surface = androidx.compose.ui.graphics.Color(0xFF181A1D),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF22252A),
    background = androidx.compose.ui.graphics.Color(0xFF101214)
)

@Composable
fun CourierApp(repo: CourierRepository) {
    val context = LocalContext.current
    val licenseManager = remember { LicenseManager(context.applicationContext) }
    MaterialTheme(colorScheme = AppDarkColors) {
        KurierXLicenseGate(licenseManager) { licenseState ->
            CourierMainApp(repo, licenseManager, licenseState)
        }
    }
}

@Composable
private fun CourierMainApp(repo: CourierRepository, licenseManager: LicenseManager, licenseState: LicenseState) {
    val context = LocalContext.current
    val featureStore = remember { KurierXFeatureStore(context) }
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(repo))
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var developerUnlocked by remember { mutableStateOf(false) }
    var moreResetKey by remember { mutableIntStateOf(0) }
    var forceDeveloperAuth by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(!featureStore.tutorialCompleted) }
    var availableRelease by remember { mutableStateOf<GithubReleaseInfo?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val requestDeveloperAuth: () -> Unit = { forceDeveloperAuth = true; tab = Tab.MORE }

    LaunchedEffect(featureStore.githubReleaseApiUrl) {
        val api = featureStore.githubReleaseApiUrl
        if (api.isNotBlank()) {
            runCatching { fetchGithubRelease(api) }.onSuccess { release ->
                if (versionIsNewer(release.version, BuildConfig.VERSION_NAME)) availableRelease = release
            }
        }
    }

    MaterialTheme(colorScheme = AppDarkColors) {
        BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                if (item == Tab.MORE) moreResetKey++
                                tab = item
                            },
                            icon = {
                                when (item) {
                                    Tab.HOME -> Text("⌂", fontSize = 28.sp, lineHeight = 28.sp)
                                    Tab.CALENDAR -> Icon(Icons.Rounded.CalendarMonth, contentDescription = item.title)
                                    Tab.STATS -> Icon(Icons.Rounded.BarChart, contentDescription = item.title)
                                    Tab.SCANNER -> Icon(Icons.Rounded.QrCodeScanner, contentDescription = item.title)
                                    Tab.MORE -> Text("•••", style = MaterialTheme.typography.titleMedium)
                                }
                            },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(repo, state, vm, developerUnlocked, onOpenScanner = { tab = Tab.SCANNER }, onRequireDeveloper = requestDeveloperAuth)
                    Tab.CALENDAR -> CalendarScreen(repo, state.calendar)
                    Tab.STATS -> StatsScreen(repo)
                    Tab.SCANNER -> ScannerScreen(repo, onRouteSaved = { tab = Tab.HOME })
                    Tab.MORE -> MoreScreen(repo, moreResetKey, developerUnlocked, forceDeveloperAuth, licenseManager, licenseState, onDeveloperUnlocked = { developerUnlocked = it }, onDeveloperAuthConsumed = { forceDeveloperAuth = false }, onStartTutorial = { showTutorial = true })
                }
            }
        }
        if (showTutorial) {
            KurierXTutorial(
                onFinish = { featureStore.tutorialCompleted = true; showTutorial = false },
                onSkip = { featureStore.tutorialCompleted = true; showTutorial = false }
            )
        }
        availableRelease?.let { release ->
            AlertDialog(
                onDismissRequest = { if (!updateDownloading) availableRelease = null },
                title = { Text("Доступно обновление KurierX ${release.version}") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Обновление устанавливается поверх текущей версии и не удаляет локальные данные.")
                    updateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                confirmButton = { Button(enabled = !updateDownloading, onClick = {
                    updateDownloading = true
                    updateError = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        runCatching { downloadReleaseApk(context, release) }
                            .onSuccess { apk -> launchApkInstaller(context, apk); availableRelease = null }
                            .onFailure { updateError = it.message }
                        updateDownloading = false
                    }
                }) { Text(if (updateDownloading) "Скачиваю…" else "Обновить") } },
                dismissButton = { TextButton(enabled = !updateDownloading, onClick = { availableRelease = null }) { Text("Позже") } }
            )
        }
    }
}

@Composable
private fun HomeScreen(repo: CourierRepository, state: MainUiState, vm: MainViewModel, developerUnlocked: Boolean, onOpenScanner: () -> Unit, onRequireDeveloper: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val featureStore = remember { KurierXFeatureStore(context) }
    var selectedRouteId by remember { mutableStateOf<Long?>(null) }
    var showPartialDialog by remember { mutableStateOf(false) }
    var partialComment by remember { mutableStateOf("") }
    var showPlanDialog by remember { mutableStateOf(false) }
    var planInput by remember { mutableStateOf("4") }
    var showMorningOdoDialog by remember { mutableStateOf(false) }
    var showQueueOdoDialog by remember { mutableStateOf(false) }
    var showClosingOdoDialog by remember { mutableStateOf(false) }
    var odometerInput by remember { mutableStateOf("") }
    var pendingCloseComment by remember { mutableStateOf<String?>(null) }
    var homeGoalProgress by remember { mutableStateOf<cz.courierledger.domain.GoalProgress?>(null) }
    val goals by repo.goals.collectAsState(initial = emptyList())
    val dataRevision by repo.dataRevision.collectAsState()
    val shift = state.activeShift

    LaunchedEffect(goals, state.calendar, dataRevision) {
        val currentMonth = YearMonth.now()
        homeGoalProgress = if (goals.any { it.month == currentMonth.toString() }) {
            runCatching { withContext(Dispatchers.IO) { repo.goalProgress(currentMonth) } }.getOrNull()
        } else null
    }

    val selectedSummary = selectedRouteId?.let { routeId -> state.routeSummaries.firstOrNull { it.route.id == routeId } }
    if (selectedSummary != null) {
        RouteDetailScreen(repo = repo, summary = selectedSummary, developerUnlocked = developerUnlocked, onBack = { selectedRouteId = null }, onRequireDeveloper = onRequireDeveloper)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.kurierx_icon),
                    contentDescription = "KurierX",
                    modifier = Modifier.size(48.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Kurier",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.3.sp
                        )
                    )
                    Text(
                        "X",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.3.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        homeGoalProgress?.takeIf { it.targetOrders != null && it.targetOrders > 0 }?.let { goal ->
            item {
                val target = goal.targetOrders ?: 0
                val progress = if (target > 0) (goal.completedOrders.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${goal.completedOrders}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                " / $target",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Цель: $target заказов",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        val needPerShift = if (goal.remainingOrders > 0 && goal.remainingWorkDays > 0) {
                            String.format(Locale.US, "%.1f", goal.requiredPerWorkDay)
                        } else "0"
                        Text(
                            if (goal.remainingOrders <= 0) "Цель выполнена ✓"
                            else "Осталось ${goal.remainingWorkDays} смен · примерно $needPerShift заказов за смену",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (goal.remainingOrders <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Заработок по обработанным трассам", style = MaterialTheme.typography.labelLarge)
                    Text(formatKc(state.routeGrossHellers), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.factualOrders} фактических заказов · чаевые ${formatKc(state.tipsHellers)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Итог учитывает трассы, бонусы / компенсации, штрафы, дизель и авансы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            if (shift == null) {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Смена не начата", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        val todayPlan = state.calendar.filter { it.date == LocalDate.now().toString() }.sortedBy { it.plannedStartMinutes }
                        if (todayPlan.isEmpty()) {
                            Text("На сегодня график не импортирован.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("План на сегодня", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            todayPlan.forEach { entry ->
                                Text(calendarEntryCompact(entry), color = calendarEntryColor(entry), fontWeight = FontWeight.SemiBold)
                            }
                            Text("Всего ${todayPlan.sumOf { it.plannedRings }} колечек", fontWeight = FontWeight.Medium)
                        }
                        Text("Рабочее время начнётся только после входа в очередь.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val morning = featureStore.pendingMorningOdometer(LocalDate.now())
                        OutlinedButton(onClick = { odometerInput = morning?.let { "%.1f".format(Locale.US, it) }.orEmpty(); showMorningOdoDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (morning == null) "Внести утренний спидометр" else "Утренний спидометр: %.1f км".format(morning))
                        }
                        Button(onClick = { odometerInput = ""; showQueueOdoDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Přihlásit se do fronty")
                        }
                    }
                }
            } else {
                ActiveShiftCard(state, onOpenScanner, onEditPlan = { planInput = (shift.plannedRings.takeIf { it > 0 } ?: 4).toString(); showPlanDialog = true }) {
                    if (state.completedRings < shift.plannedRings) showPartialDialog = true
                    else { pendingCloseComment = null; odometerInput = ""; showClosingOdoDialog = true }
                }
            }
        }

        state.lastReconciliation?.let { reconciliation ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (reconciliation.passed) "✓ Сверка пройдена" else "▲ Обнаружено несоответствие",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (reconciliation.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                        reconciliation.items.forEach { check ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (check.ok) "✓" else "▲", color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                Column(Modifier.weight(1f)) {
                                    Text(check.title, fontWeight = FontWeight.SemiBold)
                                    Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.activeRoutes.isNotEmpty()) {
            item { Text("Закрытые трассы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            items(state.routeSummaries, key = { it.route.id }) { summary -> RouteCard(summary, onClick = { selectedRouteId = summary.route.id }) }
        }

        items(state.notifications, key = { it.id }) { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚠ ${n.title}", fontWeight = FontWeight.SemiBold)
                    Text(n.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.dismissNotification(n.id) }) { Text("Скрыть") }
                }
            }
        }

        state.error?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = vm::clearError) { Text("OK") }
                    }
                }
            }
        }
    }

    if (showPlanDialog) {
        AlertDialog(
            onDismissRequest = { showPlanDialog = false },
            title = { Text("План колечек") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пока график не импортирован из календаря, план можно указать вручную. После импорта он будет браться из календаря автоматически.")
                    OutlinedTextField(
                        value = planInput,
                        onValueChange = { planInput = it.filter(Char::isDigit).take(2) },
                        label = { Text("Колечек по плану") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = planInput.toIntOrNull()?.let { it in 1..20 } == true,
                    onClick = { vm.updatePlan(planInput.toInt()); showPlanDialog = false }
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showPlanDialog = false }) { Text("Отмена") } }
        )
    }

    if (showPartialDialog) {
        AlertDialog(
            onDismissRequest = { showPartialDialog = false },
            title = { Text("Комментарий к недовыполнению") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("План: ${shift?.plannedRings ?: 0} колечка · факт: ${state.completedRings}. Причину приложение не придумывает — напиши её свободным текстом.")
                    OutlinedTextField(
                        value = partialComment,
                        onValueChange = { partialComment = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Комментарий") }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = partialComment.isNotBlank(),
                    onClick = {
                        pendingCloseComment = partialComment.trim()
                        partialComment = ""
                        showPartialDialog = false
                        odometerInput = ""
                        showClosingOdoDialog = true
                    }
                ) { Text("Закрыть смену") }
            },
            dismissButton = { TextButton(onClick = { showPartialDialog = false }) { Text("Отмена") } }
        )
    }

    if (showMorningOdoDialog) {
        OdometerDialog(
            title = "Утренний спидометр",
            value = odometerInput,
            onValue = { odometerInput = it },
            onDismiss = { showMorningOdoDialog = false },
            onConfirm = { km -> featureStore.setPendingMorningOdometer(LocalDate.now(), km); showMorningOdoDialog = false }
        )
    }
    if (showQueueOdoDialog) {
        OdometerDialog(
            title = "Спидометр при входе в очередь",
            value = odometerInput,
            onValue = { odometerInput = it },
            onDismiss = { showQueueOdoDialog = false },
            onConfirm = { km ->
                scope.launch {
                    runCatching {
                        val id = repo.startShift(LocalDate.now())
                        featureStore.consumePendingMorningOdometer(LocalDate.now())?.let { featureStore.setMorningOdometer(id, it) }
                        featureStore.setQueueOdometer(id, km)
                    }.onFailure { /* ViewModel/Room will surface operational errors elsewhere. */ }
                    showQueueOdoDialog = false
                }
            }
        )
    }
    if (showClosingOdoDialog && shift != null) {
        OdometerDialog(
            title = "Спидометр при закрытии смены",
            value = odometerInput,
            onValue = { odometerInput = it },
            onDismiss = { showClosingOdoDialog = false },
            onConfirm = { km ->
                featureStore.setClosingOdometer(shift.id, km)
                vm.closeShift(pendingCloseComment)
                pendingCloseComment = null
                showClosingOdoDialog = false
            }
        )
    }
}

@Composable
private fun ActiveShiftCard(state: MainUiState, onOpenScanner: () -> Unit, onEditPlan: () -> Unit, onClose: () -> Unit) {
    val shift = state.activeShift ?: return
    val start = shift.startedAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    } ?: "—"
    val remaining = (shift.plannedRings - state.completedRings).coerceAtLeast(0)

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Смена активна", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Старт $start", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(if (shift.plannedRings > 0) "${state.completedRings}/${shift.plannedRings} K" else "${state.completedRings} K", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
            if (shift.plannedRings > 0) {
                Text(if (remaining == 0) "План колечек выполнен" else "Осталось по плану: $remaining колечка")
                TextButton(onClick = onEditPlan, contentPadding = PaddingValues(0.dp)) { Text("Изменить план") }
            } else {
                Text("План смены ещё не импортирован из календаря.", color = MaterialTheme.colorScheme.tertiary)
                OutlinedButton(onClick = onEditPlan, modifier = Modifier.fillMaxWidth()) { Text("Задать план вручную") }
            }
            Button(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) { Text("Добавить закрытую трассу") }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть текущую смену") }
        }
    }
}

@Composable
private fun RouteCard(summary: RouteUiSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val featureStore = remember { KurierXFeatureStore(context) }
    val route = summary.route
    val routeKm = featureStore.routeKm(route.id)
    val rings = EarningsCalculator.rings(route.routeType)
    val type = when (route.routeType) { RouteType.OT -> "OT"; RouteType.REGION -> "Region"; RouteType.EXPRESS -> "Express" }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✓ $type · ${warehouseLabel(route.warehouse)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("$rings колеч${if (rings == 1) "ко" else "ка"} · трасса #${route.id}${routeKm?.let { " · %.1f км".format(it) } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (summary.clients > 0) Text(formatKc(summary.grossHellers), fontWeight = FontWeight.Bold)
            }
            if (summary.clients == 0) {
                Text("Заказники ещё не обработаны · по сообщению: ${route.reportedOrderCount ?: "?"} заказов", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${summary.clients} клиентов → ${summary.factualOrders} фактических заказов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (summary.mergedGroups > 0) Text("🔗 Объединённых групп: ${summary.mergedGroups}", color = MaterialTheme.colorScheme.primary)
                Text("За заказы ${formatKc(summary.baseHellers)} · Region ${formatSignedKc(summary.regionBonusHellers)} · чаевые ${formatSignedKc(summary.tipsHellers)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RouteDetailScreen(repo: CourierRepository, summary: RouteUiSummary, developerUnlocked: Boolean, onBack: () -> Unit, onRequireDeveloper: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val featureStore = remember { KurierXFeatureStore(context) }
    val scope = rememberCoroutineScope()
    val customers by repo.dao.observeRouteCustomers(summary.route.id).collectAsState(initial = emptyList())
    val activeGroups by repo.dao.observeActiveMergeGroups(summary.route.id).collectAsState(initial = emptyList())
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var editingRow by remember { mutableStateOf<RouteCustomerRow?>(null) }
    var deletingCustomerRow by remember { mutableStateOf<RouteCustomerRow?>(null) }
    var showRouteEdit by remember { mutableStateOf(false) }
    var showRouteDelete by remember { mutableStateOf(false) }
    var showDeveloperRequired by remember { mutableStateOf(false) }
    var showMileageDialog by remember { mutableStateOf(false) }
    var mileageInput by remember { mutableStateOf(featureStore.routeKm(summary.route.id)?.let { "%.1f".format(Locale.US, it) }.orEmpty()) }
    var mileageRevision by remember { mutableIntStateOf(0) }

    val groupsById = activeGroups.associateBy { it.id }
    val mergedRows = customers.filter { it.mergeGroupId != null }.groupBy { it.mergeGroupId!! }
    val standalone = customers.filter { it.mergeGroupId == null }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppBackHeader(
                title = routeTypeLabel(summary.route.routeType),
                subtitle = "Трасса #${summary.route.id} · ${warehouseLabel(summary.route.warehouse)}",
                onBack = onBack
            )
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Фактических заказов: ${summary.factualOrders}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Клиентов: ${summary.clients}")
                    Text("Объединённых групп: ${summary.mergedGroups}")
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Базовая оплата: ${formatKc(summary.baseHellers)}")
                    if (summary.regionBonusHellers != 0L) Text("Region: ${formatSignedKc(summary.regionBonusHellers)}")
                    Text("Чаевые: ${formatSignedKc(summary.tipsHellers)}")
                    Text("Всего: ${formatKc(summary.grossHellers)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showRouteEdit = true }, modifier = Modifier.weight(1f)) { Text("Изменить трассу") }
                        OutlinedButton(onClick = { if (developerUnlocked) showRouteDelete = true else showDeveloperRequired = true }, modifier = Modifier.weight(1f), colors = if(developerUnlocked) ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Удалить") }
                    }
                }
            }
        }

        if (activeGroups.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Объединения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { repo.splitAllMergeGroups(summary.route.id) }
                                .onSuccess { actionMessage = "Все объединения разделены. Сумма пересчитана." }
                                .onFailure { actionMessage = it.message ?: "Не удалось разделить объединения" }
                        }
                    }) { Text("Разделить все") }
                }
            }
        }

        activeGroups.forEach { group ->
            val members = mergedRows[group.id].orEmpty()
            if (members.isNotEmpty()) {
                item(key = "merge-${group.id}") {
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🔗 ${members.size} клиента → 1 заказ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(group.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(members.first().displayAddress, fontWeight = FontWeight.Medium)
                            members.forEach { row ->
                                CustomerLine(
                                    row,
                                    onAddress = { MapLauncher.openAddress(context, row.displayAddress, settings.mapProvider) },
                                    onEdit = { editingRow = row },
                                    onDelete = {
                                        if (developerUnlocked) deletingCustomerRow = row
                                        else showDeveloperRequired = true
                                    },
                                    deleteEnabled = developerUnlocked
                                )
                            }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        runCatching { repo.splitMergeGroup(group.id) }
                                            .onSuccess { actionMessage = "Объединение разделено. Перерасчёт выполнен." }
                                            .onFailure { actionMessage = it.message ?: "Не удалось разделить объединение" }
                                    }
                                }
                            ) { Text("Разделить") }
                        }
                    }
                }
            }
        }

        if (standalone.isNotEmpty()) {
            item {
            val km = remember(mileageRevision, summary.route.id) { featureStore.routeKm(summary.route.id) }
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Километраж трассы", fontWeight = FontWeight.SemiBold)
                    Text(km?.let { "%.1f км".format(it) } ?: "Не указан", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val canEdit = km == null || developerUnlocked
                    OutlinedButton(
                        onClick = {
                            if (canEdit) { mileageInput = km?.let { "%.1f".format(Locale.US, it) }.orEmpty(); showMileageDialog = true }
                            else showDeveloperRequired = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (km == null) "Добавить км" else "Изменить км${if (!developerUnlocked) " · только Developer Mode" else ""}") }
                }
            }
        }
        item { Text("Клиенты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            items(standalone, key = { "order-${it.orderId}" }) { row ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    CustomerLine(
                        row,
                        modifier = Modifier.padding(14.dp),
                        onAddress = { MapLauncher.openAddress(context, row.displayAddress, settings.mapProvider) },
                        onEdit = { editingRow = row },
                        onDelete = {
                            if (developerUnlocked) deletingCustomerRow = row
                            else showDeveloperRequired = true
                        },
                        deleteEnabled = developerUnlocked
                    )
                }
            }
        }

        actionMessage?.let { message ->
            item {
                Text(
                    message,
                    color = if (message.startsWith("Не")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showDeveloperRequired) {
        DeveloperRequiredDialog(onDismiss = { showDeveloperRequired = false }, onGo = { showDeveloperRequired = false; onRequireDeveloper() })
    }

    editingRow?.let { row ->
        EditCustomerDialog(
            row = row,
            onDismiss = { editingRow = null },
            onSave = { first, last, address, packages, tipHellers ->
                scope.launch {
                    runCatching { repo.updateCustomerOrder(row.orderId, first, last, address, packages, tipHellers) }
                        .onSuccess {
                            actionMessage = "✓ Клиент сохранён. Объединения и сумма трассы пересчитаны."
                            editingRow = null
                        }
                        .onFailure { actionMessage = "Не удалось сохранить клиента: ${it.message}" }
                }
            }
        )
    }


    deletingCustomerRow?.let { row ->
        AlertDialog(
            onDismissRequest = { deletingCustomerRow = null },
            title = { Text("Удалить клиента?") },
            text = { Text("Заказ клиента попадёт в корзину и перестанет влиять на трассу, статистику и расчёты до восстановления.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch {
                            runCatching { repo.deleteCustomerOrder(row.orderId) }
                                .onSuccess {
                                    deletingCustomerRow = null
                                    actionMessage = "Клиент отправлен в корзину. Трасса пересчитана."
                                }
                                .onFailure { actionMessage = "Не удалось удалить клиента: ${it.message}" }
                        }
                    }
                ) { Text("В корзину") }
            },
            dismissButton = { TextButton(onClick = { deletingCustomerRow = null }) { Text("Отмена") } }
        )
    }

    if (showMileageDialog) {
        MileageInputDialog(
            title = "Километраж трассы #${summary.route.id}",
            value = mileageInput,
            onValue = { mileageInput = it },
            onDismiss = { showMileageDialog = false },
            onSave = { km ->
                featureStore.setRouteKm(summary.route.id, km)
                mileageRevision++
                actionMessage = "✓ Километраж сохранён: %.1f км".format(km)
                showMileageDialog = false
            }
        )
    }

    if (showRouteEdit) {
        EditRouteDialog(
            route = summary.route,
            onDismiss = { showRouteEdit = false },
            onSave = { type, wh, orders, externalId ->
                scope.launch {
                    runCatching { repo.updateRouteDetails(summary.route.id, type, wh, orders, externalId) }
                        .onSuccess { actionMessage = "✓ Трасса изменена. Колечки и деньги пересчитаны."; showRouteEdit = false }
                        .onFailure { actionMessage = "Не удалось изменить трассу: ${it.message}" }
                }
            }
        )
    }

    if (showRouteDelete) {
        AlertDialog(
            onDismissRequest = { showRouteDelete = false },
            title = { Text("Удалить трассу?") },
            text = { Text("Трасса попадёт в корзину. Связанные данные не уничтожаются безвозвратно.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch {
                            runCatching { repo.deleteRoute(summary.route.id) }
                                .onSuccess { showRouteDelete = false; onBack() }
                                .onFailure { actionMessage = "Не удалось удалить трассу: ${it.message}" }
                        }
                    }
                ) { Text("В корзину") }
            },
            dismissButton = { TextButton(onClick = { showRouteDelete = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun CustomerLine(
    row: RouteCustomerRow,
    modifier: Modifier = Modifier,
    onAddress: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = true
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val name = listOf(row.firstName, row.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Без имени" }
            Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (onEdit != null) TextButton(onClick = onEdit) { Text("Изменить") }
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (deleteEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Удалить") }
            }
        }
        Text(
            row.displayAddress,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAddress),
            color = MaterialTheme.colorScheme.primary
        )
        Text("Пакеты ${row.packages} · чаевые ${formatKc(row.tipHellers)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EditCustomerDialog(
    row: RouteCustomerRow,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Long) -> Unit
) {
    var first by remember(row.orderId) { mutableStateOf(row.firstName) }
    var last by remember(row.orderId) { mutableStateOf(row.lastName) }
    var address by remember(row.orderId) { mutableStateOf(row.displayAddress) }
    var packages by remember(row.orderId) { mutableStateOf(row.packages.toString()) }
    var tip by remember(row.orderId) { mutableStateOf(hellersToInputKc(row.tipHellers)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать клиента") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(first, { first = it }, label = { Text("Имя") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(last, { last = it }, label = { Text("Фамилия") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(address, { address = it }, label = { Text("Адрес") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        packages,
                        { packages = it.filter(Char::isDigit).take(3) },
                        label = { Text("Пакеты") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        tip,
                        { tip = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(8) },
                        label = { Text("Чаевые, Kč") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Text("После изменения адреса merge-группы этой трассы будут безопасно пересчитаны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                enabled = address.isNotBlank(),
                onClick = { onSave(first.trim(), last.trim(), address.trim(), packages.toIntOrNull() ?: 0, inputKcToHellers(tip)) }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatKc(hellers: Long): String = if (hellers % 100L == 0L) "${hellers / 100} Kč" else "%.2f Kč".format(hellers / 100.0)
private fun formatSignedKc(hellers: Long): String = if (hellers == 0L) "0 Kč" else (if (hellers > 0) "+" else "") + formatKc(hellers)
private fun hellersToInputKc(hellers: Long): String = if (hellers % 100L == 0L) (hellers / 100L).toString() else "%.2f".format(java.util.Locale.US, hellers / 100.0)
private fun inputKcToHellers(value: String): Long = value.trim().replace(',', '.').toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.longValueExact() ?: 0L

private data class CalendarImportDraft(
    val date: String,
    val time: String,
    val rings: String,
    val warehouse: Warehouse
)

private data class CalendarStoredPhoto(val uri: Uri, val localPath: String, val sha256: String)

@Composable
private fun CalendarScreen(repo: CourierRepository, entries: List<CalendarEntryEntity>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { OcrEngine(context) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var importMonth by remember { mutableStateOf<YearMonth?>(null) }
    var importDrafts by remember { mutableStateOf<List<CalendarImportDraft>?>(null) }
    var importWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var importConfidence by remember { mutableStateOf(0.0) }
    var calendarMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }

    fun processCalendarImage(uri: Uri, existingPath: String? = null) {
        scope.launch {
            isImporting = true
            calendarMessage = "Распознаю график…"
            runCatching {
                val stored = withContext(Dispatchers.IO) { persistCalendarPhoto(context, uri, existingPath) }
                val existing = withContext(Dispatchers.IO) { repo.dao.photoByHash(stored.sha256) }
                val photoId = existing?.id ?: withContext(Dispatchers.IO) {
                    repo.dao.insertSourcePhoto(
                        SourcePhotoEntity(
                            uri = stored.uri.toString(),
                            localPath = stored.localPath,
                            sha256 = stored.sha256,
                            createdAt = System.currentTimeMillis(),
                            kind = "CALENDAR"
                        )
                    )
                }
                val ocr = engine.recognize(stored.uri)
                val parsed = CalendarOcrParser.parse(ocr)
                withContext(Dispatchers.IO) {
                    repo.dao.insertOcrResult(
                        OcrResultEntity(
                            photoId = photoId,
                            kind = "CALENDAR",
                            rawText = ocr.text,
                            confidence = ocr.confidence,
                            parsedJson = "month=${parsed.month};entries=${parsed.entries.size}"
                        )
                    )
                }
                val parsedMonth = parsed.month ?: error(parsed.warnings.firstOrNull() ?: "Не удалось определить месяц")
                importMonth = parsedMonth
                month = parsedMonth
                importDrafts = parsed.entries.map {
                    CalendarImportDraft(
                        date = it.date.toString(),
                        time = minutesToTime(it.plannedStartMinutes),
                        rings = it.plannedRings.toString(),
                        warehouse = it.warehouse
                    )
                }
                importWarnings = parsed.warnings
                importConfidence = parsed.confidence
                calendarMessage = "OCR нашёл ${parsed.entries.size} рабочих блоков. Проверь их перед сохранением."
            }.onFailure {
                calendarMessage = "Не удалось импортировать график: ${it.message ?: it::class.java.simpleName}"
            }
            isImporting = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processCalendarImage(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { processCalendarImage(it, pendingCameraPath) }
        else calendarMessage = "Съёмка отменена"
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCameraUri?.let { cameraLauncher.launch(it) }
        else calendarMessage = "Без разрешения на камеру можно импортировать скриншот из галереи."
    }

    fun launchCalendarCamera() {
        runCatching {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "calendar_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            pendingCameraUri = uri
            pendingCameraPath = file.absolutePath
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(uri)
            } else permissionLauncher.launch(Manifest.permission.CAMERA)
        }.onFailure { calendarMessage = "Не удалось открыть камеру: ${it.message}" }
    }

    val reviewDrafts = importDrafts
    val reviewMonth = importMonth
    if (reviewDrafts != null && reviewMonth != null) {
        CalendarImportReview(
            month = reviewMonth,
            drafts = reviewDrafts,
            warnings = importWarnings,
            confidence = importConfidence,
            onDraftsChange = { importDrafts = it },
            onCancel = { importDrafts = null; importMonth = null; calendarMessage = null },
            onSave = { drafts ->
                scope.launch {
                    runCatching {
                        val parsedEntries = drafts.map { draft ->
                            val date = LocalDate.parse(draft.date)
                            require(YearMonth.from(date) == reviewMonth) { "Дата ${draft.date} не относится к ${reviewMonth}" }
                            val minutes = parseTimeToMinutes(draft.time) ?: error("Некорректное время ${draft.time}")
                            val rings = draft.rings.toIntOrNull() ?: error("Некорректное количество колечек")
                            CalendarEntryEntity(
                                date = date.toString(),
                                plannedStartMinutes = minutes,
                                plannedRings = rings,
                                warehouse = draft.warehouse,
                                source = DataSource.IMPORT
                            )
                        }
                        repo.replaceCalendarMonth(reviewMonth, parsedEntries, DataSource.IMPORT)
                    }.onSuccess {
                        calendarMessage = "✓ График за ${monthTitle(reviewMonth)} сохранён. План смен теперь берётся из календаря."
                        importDrafts = null
                        importMonth = null
                    }.onFailure { calendarMessage = "Не удалось сохранить график: ${it.message}" }
                }
            }
        )
        return
    }

    val monthEntries = remember(entries, month) {
        entries.filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) == month }.getOrDefault(false) }
            .groupBy { LocalDate.parse(it.date) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Календарь", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("План смен и колечек", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { month = YearMonth.now() }) { Text("Сегодня") }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                        Text(monthTitle(month), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        FilledTonalIconButton(onClick = { month = month.plusMonths(1) }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                    }
                    CalendarMonthGrid(month, monthEntries) { selectedDate = it }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::launchCalendarCamera, enabled = !isImporting, modifier = Modifier.weight(1f)) { Text("Камера") }
                Button(onClick = { galleryLauncher.launch("image/*") }, enabled = !isImporting, modifier = Modifier.weight(1f)) { Text(if (isImporting) "OCR…" else "Импорт скриншота") }
            }
        }
        item {
            Text("После OCR график не записывается молча: сначала откроется проверка всех найденных рабочих дней.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { CalendarLegend() }
        calendarMessage?.let { message ->
            item {
                Text(message, color = if (message.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    selectedDate?.let { date ->
        CalendarDayDialog(
            date = date,
            initial = monthEntries[date].orEmpty(),
            onDismiss = { selectedDate = null },
            onSave = { dayEntries ->
                scope.launch {
                    runCatching { repo.replaceCalendarDate(date, dayEntries, DataSource.USER_CORRECTION) }
                        .onSuccess { calendarMessage = "✓ ${formatDate(date.toString())}: план сохранён"; selectedDate = null }
                        .onFailure { calendarMessage = "Не удалось сохранить день: ${it.message}" }
                }
            }
        )
    }
}

@Composable
private fun CalendarMonthGrid(
    month: YearMonth,
    entries: Map<LocalDate, List<CalendarEntryEntity>>,
    onDay: (LocalDate) -> Unit
) {
    val headers = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
    Row(Modifier.fillMaxWidth()) {
        headers.forEach { header ->
            Text(header, modifier = Modifier.weight(1f).padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = ((month.lengthOfMonth() + firstOffset + 6) / 7) * 7
    val weekCount = cells / 7
    val cellHeight = if (weekCount >= 6) 62.dp else 70.dp
    for (row in 0 until weekCount) {
        Row(Modifier.fillMaxWidth()) {
            for (col in 0..6) {
                val index = row * 7 + col
                val day = index - firstOffset + 1
                if (day !in 1..month.lengthOfMonth()) {
                    Spacer(Modifier.weight(1f).height(cellHeight))
                } else {
                    val date = month.atDay(day)
                    CalendarDayCell(date, entries[date].orEmpty(), Modifier.weight(1f), cellHeight, onDay)
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(date: LocalDate, dayEntries: List<CalendarEntryEntity>, modifier: Modifier, height: androidx.compose.ui.unit.Dp, onDay: (LocalDate) -> Unit) {
    val mainColor = dayEntries.firstOrNull()?.let(::calendarEntryColor)
    val today = date == LocalDate.now()
    val background = if (mainColor != null) mainColor.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
    Surface(
        modifier = modifier.height(height).padding(1.5.dp).clickable { onDay(date) },
        shape = RoundedCornerShape(8.dp),
        color = background,
        border = when {
            today -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            mainColor != null -> BorderStroke(1.dp, mainColor.copy(alpha = .65f))
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        }
    ) {
        Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = if (today) FontWeight.Bold else FontWeight.Medium)
            dayEntries.take(2).forEach { entry ->
                val color = calendarEntryColor(entry)
                Text(calendarEntryCellCompact(entry), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2)
            }
            if (dayEntries.size > 2) Text("+${dayEntries.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarLegend() {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Цвета плана", fontWeight = FontWeight.SemiBold)
            Text("Liboc: 6:00 зелёный · 6:30 красный · 7:30 фиолетовый", style = MaterialTheme.typography.bodySmall)
            Text("CH — жёлтый · HP — светло-зелёный", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarDayDialog(
    date: LocalDate,
    initial: List<CalendarEntryEntity>,
    onDismiss: () -> Unit,
    onSave: (List<CalendarEntryEntity>) -> Unit
) {
    var drafts by remember(date, initial) {
        mutableStateOf(initial.map { CalendarImportDraft(date.toString(), minutesToTime(it.plannedStartMinutes), it.plannedRings.toString(), it.warehouse) })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatDate(date.toString())) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                drafts.forEachIndexed { index, draft ->
                    CalendarDraftEditor(draft, onChange = { value -> drafts = drafts.toMutableList().also { it[index] = value } }, onDelete = { drafts = drafts.toMutableList().also { it.removeAt(index) } }, showDate = false)
                }
                OutlinedButton(onClick = { drafts = drafts + CalendarImportDraft(date.toString(), "6:00", "4", Warehouse.LIBOC) }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить блок") }
                if (drafts.isEmpty()) Text("Если сохранить пустой день, план на эту дату будет удалён.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = drafts.mapNotNull { d ->
                    val minutes = parseTimeToMinutes(d.time) ?: return@mapNotNull null
                    val rings = d.rings.toIntOrNull()?.takeIf { it in 1..20 } ?: return@mapNotNull null
                    CalendarEntryEntity(date = date.toString(), plannedStartMinutes = minutes, plannedRings = rings, warehouse = d.warehouse, source = DataSource.USER_CORRECTION)
                }
                if (result.size == drafts.size) onSave(result)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CalendarImportReview(
    month: YearMonth,
    drafts: List<CalendarImportDraft>,
    warnings: List<String>,
    confidence: Double,
    onDraftsChange: (List<CalendarImportDraft>) -> Unit,
    onCancel: () -> Unit,
    onSave: (List<CalendarImportDraft>) -> Unit
) {
    BackHandler(onBack = onCancel)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Проверка графика", "${monthTitle(month)} · OCR ${(confidence * 100).toInt()}%", onCancel) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Найдено рабочих блоков: ${drafts.size}", fontWeight = FontWeight.Bold)
                    Text("Исправь ошибки перед сохранением. Сохранение заменит текущий план только в этом месяце.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    warnings.forEach { Text("▲ $it", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        items(drafts.indices.toList(), key = { it }) { index ->
            CalendarDraftEditor(
                draft = drafts[index],
                onChange = { value -> onDraftsChange(drafts.toMutableList().also { it[index] = value }) },
                onDelete = { onDraftsChange(drafts.toMutableList().also { it.removeAt(index) }) },
                showDate = true
            )
        }
        item {
            OutlinedButton(
                onClick = { onDraftsChange(drafts + CalendarImportDraft(month.atDay(1).toString(), "6:00", "4", Warehouse.LIBOC)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("+ Добавить пропущенный блок") }
        }
        item {
            Button(onClick = { onSave(drafts) }, modifier = Modifier.fillMaxWidth(), enabled = drafts.isNotEmpty()) { Text("Сохранить график за месяц") }
        }
    }
}

@Composable
private fun CalendarDraftEditor(
    draft: CalendarImportDraft,
    onChange: (CalendarImportDraft) -> Unit,
    onDelete: () -> Unit,
    showDate: Boolean
) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showDate) draft.date else "Рабочий блок", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить") }
            }
            if (showDate) {
                OutlinedTextField(draft.date, { onChange(draft.copy(date = it.take(10))) }, modifier = Modifier.fillMaxWidth(), label = { Text("Дата YYYY-MM-DD") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.time, { onChange(draft.copy(time = it.take(5))) }, label = { Text("Начало") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(draft.rings, { onChange(draft.copy(rings = it.filter(Char::isDigit).take(2))) }, label = { Text("Колечек") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Warehouse.entries.forEach { wh ->
                    FilterChip(selected = draft.warehouse == wh, onClick = { onChange(draft.copy(warehouse = wh)) }, label = { Text(warehouseLabel(wh)) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun calendarEntryCompact(entry: CalendarEntryEntity): String {
    val wh = when (entry.warehouse) {
        Warehouse.LIBOC -> ""
        Warehouse.CHRASTANY -> " · CH"
        Warehouse.HORNI_POCERNICE -> " · HP"
    }
    return "${minutesToTime(entry.plannedStartMinutes)}\n${entry.plannedRings}K$wh"
}

// Extra-compact text only for narrow month-grid cells so ring count is never clipped.
private fun calendarEntryCellCompact(entry: CalendarEntryEntity): String {
    val wh = when (entry.warehouse) {
        Warehouse.LIBOC -> ""
        Warehouse.CHRASTANY -> "·CH"
        Warehouse.HORNI_POCERNICE -> "·HP"
    }
    return "${minutesToTime(entry.plannedStartMinutes)}\n${entry.plannedRings}K$wh"
}

private fun calendarEntryColor(entry: CalendarEntryEntity): Color = when (entry.warehouse) {
    Warehouse.CHRASTANY -> Color(0xFFE0A92F)
    Warehouse.HORNI_POCERNICE -> Color(0xFF9CCC65)
    Warehouse.LIBOC -> when (entry.plannedStartMinutes) {
        6 * 60 -> Color(0xFF67C23A)
        6 * 60 + 30 -> Color(0xFFE04B4B)
        7 * 60 + 30 -> Color(0xFF9C7CFF)
        else -> Color(0xFF56A8E8)
    }
}

private fun monthTitle(month: YearMonth): String = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() } + " ${month.year}"

private fun minutesToTime(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)
private fun parseTimeToMinutes(value: String): Int? {
    val match = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(value.trim()) ?: return null
    val h = match.groupValues[1].toIntOrNull() ?: return null
    val m = match.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private suspend fun persistCalendarPhoto(context: android.content.Context, inputUri: Uri, existingPath: String?): CalendarStoredPhoto {
    if (existingPath != null) {
        val file = File(existingPath)
        require(file.exists() && file.length() > 0) { "Камера не сохранила изображение" }
        return CalendarStoredPhoto(inputUri, file.absolutePath, sha256Calendar(file))
    }
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "calendar_import_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(inputUri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        ?: error("Не удалось прочитать изображение")
    require(file.length() > 0) { "Изображение пустое" }
    val stableUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return CalendarStoredPhoto(stableUri, file.absolutePath, sha256Calendar(file))
}

private fun sha256Calendar(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}


private enum class StatsPeriod(val label: String) { TODAY("День"), WEEK("Неделя"), MONTH("Месяц"), CUSTOM("Период"), ALL("Всё время") }
private enum class StatsInsight(val label:String) {
    TOP_EARNING("Самый большой заработок"), LOW_EARNING("Самый маленький заработок"),
    LONG_SHIFT("Самая длинная смена"), SHORT_SHIFT("Самая короткая смена"),
    TOP_ORDERS("Больше всего заказов"), LOW_ORDERS("Меньше всего заказов"),
    TOP_TIP_CUSTOMER("Самые большие чаевые · клиент"), TOP_TIP_DAY("Больше всего чаевых · день"), TOP_TIP_MONTH("Больше всего чаевых · месяц")
}

private data class StatsBundle(
    val stats: PeriodStatistics,
    val comparison: StatisticsSnapshotComparison,
    val money: PeriodMoneyResult,
    val analytics: AnalyticsOverview
)

@Composable
private fun StatsScreen(repo: CourierRepository) {
    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    var customFrom by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var customTo by remember { mutableStateOf(LocalDate.now()) }
    var showRangePicker by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<PeriodStatistics?>(null) }
    var comparison by remember { mutableStateOf<StatisticsSnapshotComparison?>(null) }
    var periodMoney by remember { mutableStateOf<PeriodMoneyResult?>(null) }
    var analytics by remember { mutableStateOf<AnalyticsOverview?>(null) }
    var insight by remember { mutableStateOf(StatsInsight.TOP_EARNING) }
    var insightExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snapshots by repo.dao.observeSnapshots().collectAsState(initial = emptyList())
    val financeEntries by repo.financialEntries.collectAsState(initial = emptyList())
    val advanceEntries by repo.advances.collectAsState(initial = emptyList())
    val fuelEntries by repo.fuelExpenses.collectAsState(initial = emptyList())
    val liveRoutes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    val liveCustomerRows by repo.dao.observeCustomerOrders().collectAsState(initial = emptyList())
    val liveShifts by repo.dao.observeShifts().collectAsState(initial = emptyList())
    val dataRevision by repo.dataRevision.collectAsState()

    val today = LocalDate.now()
    val range = when (period) {
        StatsPeriod.TODAY -> today to today
        StatsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        StatsPeriod.MONTH -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
        StatsPeriod.CUSTOM -> customFrom to customTo
        StatsPeriod.ALL -> null to null
    }

    LaunchedEffect(period, customFrom, customTo, snapshots, financeEntries, advanceEntries, fuelEntries, liveRoutes, liveCustomerRows, liveShifts, dataRevision) {
        runCatching {
            withContext(Dispatchers.IO) {
                StatsBundle(
                    stats = repo.periodStatistics(range.first, range.second),
                    comparison = repo.latestStatisticsComparison(),
                    money = repo.periodMoney(range.first, range.second),
                    analytics = repo.analyticsOverview(range.first, range.second)
                )
            }
        }.onSuccess { result ->
            stats = result.stats
            comparison = result.comparison
            periodMoney = result.money
            analytics = result.analytics
            error = null
        }
            .onFailure { error = it.message ?: "Не удалось рассчитать статистику" }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Статистика", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Удалённые трассы полностью исключаются из расчётов до восстановления из корзины.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach { item ->
                    FilterChip(
                        selected = period == item,
                        onClick = { if (item == StatsPeriod.CUSTOM) showRangePicker = true else period = item },
                        label = { Text(item.label, maxLines = 1) }
                    )
                }
            }
        }
        if (period == StatsPeriod.CUSTOM) {
            item {
                FilledTonalButton(onClick = { showRangePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${formatDate(customFrom.toString())} — ${formatDate(customTo.toString())}")
                }
            }
        }
        stats?.let { s ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${s.factualOrders} заказов", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${s.rings} колечек · ${s.shifts} смен · ${formatDuration(s.workedMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("OT ${s.ot} · Region ${s.region} · Express ${s.express}")
                        Text("Базовая оплата ${formatKc(s.baseHellers)}")
                        Text("Region ${formatSignedKc(s.regionBonusHellers)} · чаевые ${formatSignedKc(s.tipsHellers)}")
                        Text("По трассам ${formatKc(s.grossHellers)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatMetric("Kč / заказ", formatKc(s.kcPerOrderHellers), Modifier.weight(1f)); StatMetric("Kč / час", formatKc(s.kcPerHourHellers), Modifier.weight(1f)) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatMetric("Заказов / час", "%.2f".format(s.ordersPerHour), Modifier.weight(1f)); StatMetric("Колечек / час", "%.2f".format(s.ringsPerHour), Modifier.weight(1f)) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatMetric("Чаевые / клиент", formatKc(s.avgTipPerClientHellers), Modifier.weight(1f)); StatMetric("Чаевые / заказ", formatKc(s.avgTipPerOrderHellers), Modifier.weight(1f)) } }
        }
        periodMoney?.let { money ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Финансы за выбранный период", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Трассы ${formatKc(money.routeGross.hellers)}")
                        Text("Бонусы / компенсации ${formatSignedKc(money.bonuses.hellers)}")
                        Text("Штрафы −${formatKc(money.penalties.hellers)} · дизель −${formatKc(money.diesel.hellers)}")
                        HorizontalDivider()
                        Text("Начислено: ${formatKc(money.accrued.hellers)}", fontWeight = FontWeight.SemiBold)
                        Text("Чистый заработок: ${formatKc(money.net.hellers)}", fontWeight = FontWeight.SemiBold)
                        Text("Авансы: −${formatKc(money.advances.hellers)}")
                        Text("Осталось получить: ${formatKc(money.expectedPayout.hellers)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Фильтрация и рекорды",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Box {
                        OutlinedButton(onClick={insightExpanded=true},modifier=Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(insight.label);Text("⌄")}}
                        DropdownMenu(expanded=insightExpanded,onDismissRequest={insightExpanded=false}, containerColor = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatsInsight.entries.forEach { option ->
                                    val selected = insight == option
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable { insight=option; insightExpanded=false },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha=.14f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha=.65f) else MaterialTheme.colorScheme.outline.copy(alpha=.35f))
                                    ) {
                                        Text(option.label, Modifier.padding(horizontal=14.dp, vertical=11.dp), fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                    analytics?.let { a ->
                        val text=when(insight) {
                            StatsInsight.TOP_EARNING -> a.topDay?.let{"${formatDate(it.shift.date)} · ${formatKc(it.grossHellers)}"}?:"Нет данных"
                            StatsInsight.LOW_EARNING -> a.bottomDay?.let{"${formatDate(it.shift.date)} · ${formatKc(it.grossHellers)}"}?:"Нет данных"
                            StatsInsight.LONG_SHIFT -> a.longestShift?.let{"${formatDate(it.shift.date)} · ${formatDuration(it.workedMillis)}"}?:"Нет данных"
                            StatsInsight.SHORT_SHIFT -> a.shortestShift?.let{"${formatDate(it.shift.date)} · ${formatDuration(it.workedMillis)}"}?:"Нет данных"
                            StatsInsight.TOP_ORDERS -> a.mostOrdersDay?.let{"${formatDate(it.shift.date)} · ${it.factualOrders} заказов"}?:"Нет данных"
                            StatsInsight.LOW_ORDERS -> a.leastOrdersDay?.let{"${formatDate(it.shift.date)} · ${it.factualOrders} заказов"}?:"Нет данных"
                            StatsInsight.TOP_TIP_CUSTOMER -> a.topTipCustomerName?.let{"$it · ${formatKc(a.topTipCustomerHellers)}"}?:"Нет данных"
                            StatsInsight.TOP_TIP_DAY -> a.topTipDay?.let{"${formatDate(it)} · ${formatKc(a.topTipDayHellers)}"}?:"Нет данных"
                            StatsInsight.TOP_TIP_MONTH -> a.topTipMonth?.let{"$it · ${formatKc(a.topTipMonthHellers)}"}?:"Нет данных"
                        }
                        Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
                        Text("Рейтинг считается только внутри выбранного выше периода.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Text("Накопительная статистика курьера", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        comparison?.let { c ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val current = c.current
                        Text("Последний снимок: ${current?.cumulativeOrders ?: "—"} заказов" + (current?.cumulativeTipsHellers?.let { " · ${formatKc(it)} чаевых" } ?: ""), fontWeight = FontWeight.SemiBold)
                        if (c.cumulativeDelta != null && c.routeOrdersBetween != null) {
                            Text("Заказы: +${c.cumulativeDelta} · по трассам: ${c.routeOrdersBetween}")
                            Text(if (c.matches == true) "✓ Заказы совпадают" else "▲ Заказы: разница ${c.cumulativeDelta - c.routeOrdersBetween}", color = if (c.matches == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                            if (c.cumulativeTipsDeltaHellers != null && c.routeTipsBetweenHellers != null) {
                                Text("Чаевые: ${formatSignedKc(c.cumulativeTipsDeltaHellers)} · по клиентам: ${formatKc(c.routeTipsBetweenHellers)}")
                                Text(if (c.tipsMatch == true) "✓ Чаевые совпадают" else "▲ Чаевые: разница ${formatSignedKc(c.cumulativeTipsDeltaHellers - c.routeTipsBetweenHellers)}", color = if (c.tipsMatch == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                            }
                        } else Text("Нужны минимум два снимка для сравнения.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (snapshots.isNotEmpty()) items(snapshots.take(10), key = { it.id }) { snapshot -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatSnapshotTime(snapshot.capturedAt)); Text("${snapshot.cumulativeOrders} заказов", fontWeight = FontWeight.SemiBold) } } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }

    if (showRangePicker) {
        StatisticsRangeDialog(customFrom, customTo, onDismiss = { showRangePicker = false }, onApply = { from, to -> customFrom = from; customTo = to; period = StatsPeriod.CUSTOM; showRangePicker = false })
    }
}

@Composable
private fun StatisticsRangeDialog(initialFrom: LocalDate, initialTo: LocalDate, onDismiss: () -> Unit, onApply: (LocalDate, LocalDate) -> Unit) {
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    var month by remember { mutableStateOf(YearMonth.from(initialFrom)) }
    var selectingStart by remember { mutableStateOf(true) }
    var showMonthYear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать период") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectingStart, onClick = { selectingStart = true }, label = { Text("С ${formatDate(from.toString())}") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !selectingStart, onClick = { selectingStart = false }, label = { Text("По ${formatDate(to.toString())}") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    TextButton(onClick = { showMonthYear = true }, modifier = Modifier.weight(1f)) { Text(monthTitle(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    IconButton(onClick = { month = month.plusMonths(1) }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                }
                RangeMonthGrid(month, from, to) { date ->
                    if (selectingStart) { from = date; if (to.isBefore(date)) to = date; selectingStart = false }
                    else { to = date; if (from.isAfter(date)) from = date }
                }
            }
        },
        confirmButton = { Button(onClick = { onApply(from, to) }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
    if (showMonthYear) MonthYearPickerDialog(month, onDismiss = { showMonthYear = false }, onPick = { month = it; showMonthYear = false })
}

@Composable
private fun RangeMonthGrid(month: YearMonth, from: LocalDate, to: LocalDate, onPick: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth()) { listOf("ПН","ВТ","СР","ЧТ","ПТ","СБ","ВС").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
    val offset = month.atDay(1).dayOfWeek.value - 1
    val cells = ((month.lengthOfMonth() + offset + 6) / 7) * 7
    repeat(cells / 7) { row ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { col ->
                val day = row * 7 + col - offset + 1
                if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(42.dp)) else {
                    val date = month.atDay(day)
                    val selected = !date.isBefore(from) && !date.isAfter(to)
                    Surface(Modifier.weight(1f).height(42.dp).padding(1.dp).clickable { onPick(date) }, shape = RoundedCornerShape(10.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha=.25f) else Color.Transparent, border = if (date==from || date==to) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                        Box(contentAlignment = Alignment.Center) { Text(day.toString(), fontWeight = if (date==from || date==to) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthYearPickerDialog(initial: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(initial.year) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Месяц и год") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick={year--}){Text("‹")}; Text(year.toString(), style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); IconButton(onClick={year++}){Text("›")} }
            (1..12).chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { m -> val ym=YearMonth.of(year,m); FilterChip(selected=ym==initial,onClick={onPick(ym)},label={Text(ym.month.getDisplayName(TextStyle.SHORT_STANDALONE, Locale("ru")))},modifier=Modifier.weight(1f)) } } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick=onDismiss){Text("Закрыть")} })
}

@Composable
private fun StatMetric(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}


@Composable
private fun DeveloperRequiredDialog(onDismiss: () -> Unit, onGo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Нужен расширенный доступ") },
        text = { Text("Это действие невозможно выполнить в обычном режиме. Перейди в расширенный режим и авторизуйся, чтобы продолжить.") },
        confirmButton = { Button(onClick = onGo) { Text("Перейти в расширенный режим") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private enum class MorePage { MENU, ACCOUNT, CONTROL, SHIFTS, CLIENTS, MILEAGE, BONUSES, PENALTIES, ADVANCES, FUEL, SALARY, GOALS, BACKUPS, JOURNAL, TRASH, DEVELOPER, SETTINGS }

@Composable
private fun MoreScreen(repo: CourierRepository, resetKey: Int, developerUnlocked: Boolean, forceDeveloperAuth: Boolean, licenseManager: LicenseManager, licenseState: LicenseState, onDeveloperUnlocked: (Boolean)->Unit, onDeveloperAuthConsumed: () -> Unit, onStartTutorial: () -> Unit) {
    var page by remember { mutableStateOf(MorePage.MENU) }
    LaunchedEffect(resetKey) { if (!forceDeveloperAuth) page = MorePage.MENU }
    LaunchedEffect(forceDeveloperAuth) { if (forceDeveloperAuth) { page = MorePage.DEVELOPER; onDeveloperAuthConsumed() } }
    BackHandler(enabled = page != MorePage.MENU) { page = MorePage.MENU }
    when (page) {
        MorePage.ACCOUNT -> AccountScreen(licenseManager, licenseState, onBack = { page = MorePage.MENU })
        MorePage.CONTROL -> OwnerControlScreen(licenseManager, onBack = { page = MorePage.MENU })
        MorePage.SHIFTS -> ShiftsScreen(repo, developerUnlocked, onBack = { page = MorePage.MENU }, onRequireDeveloper = { page = MorePage.DEVELOPER })
        MorePage.CLIENTS -> ClientsScreen(repo, developerUnlocked, onBack = { page = MorePage.MENU }, onRequireDeveloper = { page = MorePage.DEVELOPER })
        MorePage.MILEAGE -> MileageRoutesScreen(repo, developerUnlocked, onBack = { page = MorePage.MENU }, onRequireDeveloper = { page = MorePage.DEVELOPER })
        MorePage.BONUSES -> FinancialEntriesScreen(repo, FinancialType.BONUS, "Бонусы / компенсации", onBack = { page = MorePage.MENU })
        MorePage.PENALTIES -> FinancialEntriesScreen(repo, FinancialType.PENALTY, "Штрафы", onBack = { page = MorePage.MENU })
        MorePage.ADVANCES -> AdvancesScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.FUEL -> FuelScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.SALARY -> SalaryScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.GOALS -> GoalsScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.BACKUPS -> BackupsScreen(onBack = { page = MorePage.MENU })
        MorePage.JOURNAL -> JournalScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.TRASH -> TrashScreen(repo, onBack = { page = MorePage.MENU })
        MorePage.DEVELOPER -> DeveloperModeScreen(developerUnlocked, onDeveloperUnlocked, onBack = { page = MorePage.MENU })
        MorePage.SETTINGS -> SettingsScreen(onBack = { page = MorePage.MENU })
        MorePage.MENU -> Column(Modifier.fillMaxSize()) {
            MoreMenuPanel(
                onAccount = { page = MorePage.ACCOUNT },
                onControl = if (licenseState is LicenseState.Owner) ({ page = MorePage.CONTROL }) else null,
                onClients = { page = MorePage.CLIENTS },
                onShifts = { page = MorePage.SHIFTS },
                onBonuses = { page = MorePage.BONUSES },
                onPenalties = { page = MorePage.PENALTIES },
                onFuel = { page = MorePage.FUEL },
                onAdvances = { page = MorePage.ADVANCES },
                onSalary = { page = MorePage.SALARY },
                onGoals = { page = MorePage.GOALS },
                onBackups = { page = MorePage.BACKUPS },
                onJournal = { page = MorePage.JOURNAL },
                onTrash = { page = MorePage.TRASH },
                onDeveloper = { page = MorePage.DEVELOPER },
                onSettings = { page = MorePage.SETTINGS },
                modifier = Modifier.weight(1f)
            )
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page = MorePage.MILEAGE }, modifier = Modifier.weight(1f)) { Text("Километраж") }
                    Button(onClick = onStartTutorial, modifier = Modifier.weight(1f)) { Text("Обучение / Как пользоваться") }
                }
            }
        }
    }
}

@Composable
private fun FinancialEntriesScreen(repo: CourierRepository, type: FinancialType, title: String, onBack: () -> Unit) {
    val all by repo.financialEntries.collectAsState(initial = emptyList())
    val entries = all.filter { if (type == FinancialType.BONUS) it.type == FinancialType.BONUS || it.type == FinancialType.COMPENSATION else it.type == type }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<FinancialEntryEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val total = entries.sumOf { it.amountHellers }
    val context = LocalContext.current
    val engine = remember { OcrEngine(context) }
    val featureStore = remember { KurierXFeatureStore(context) }
    var importing by remember { mutableStateOf(false) }
    var importDrafts by remember { mutableStateOf<List<FinancialImportDraft>>(emptyList()) }
    var showImportDuplicateConfirm by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            feedback = "Распознаю ${uris.size} фото…"
            val collected = mutableListOf<FinancialImportDraft>()
            var failed = 0
            uris.forEach { uri ->
                runCatching {
                    val stored = withContext(Dispatchers.IO) { persistCalendarPhoto(context, uri, null) }
                    val existing = withContext(Dispatchers.IO) { repo.dao.photoByHash(stored.sha256) }
                    val photoId = existing?.id ?: withContext(Dispatchers.IO) {
                        repo.dao.insertSourcePhoto(SourcePhotoEntity(uri=stored.uri.toString(), localPath=stored.localPath, sha256=stored.sha256, createdAt=System.currentTimeMillis(), kind="FINANCE_TABLE"))
                    }
                    val result = engine.recognize(stored.uri)
                    if (existing == null) withContext(Dispatchers.IO) {
                        repo.dao.insertOcrResult(OcrResultEntity(photoId=photoId, kind="FINANCE_TABLE", rawText=result.text, confidence=result.confidence, parsedJson="{}"))
                    }
                    OcrParsers.financialRows(result)
                        .filter { it.amountHellers != null }
                        .forEach { row ->
                            collected += FinancialImportDraft(
                                type=featureStore.correctedFinancialType(row.description, row.type ?: type),
                                amount=row.amountHellers?.let(::hellersToInputKc).orEmpty(),
                                date=normalizeFinanceDate(row.date) ?: LocalDate.now().toString(),
                                description=row.description
                            )
                        }
                }.onFailure { failed++ }
            }
            importDrafts = collected
            feedback = if (collected.isEmpty()) "OCR не нашёл финансовых строк с распознаваемой суммой." else "✓ Найдено записей: ${collected.size}${if(failed>0) " · ошибок фото: $failed" else ""}. Проверь перед подтверждением."
            importing = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader(title, onBack = onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (type == FinancialType.PENALTY) "Всего удержаний" else "Всего", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (type == FinancialType.PENALTY) "−${formatKc(total)}" else formatKc(total),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (type == FinancialType.PENALTY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить вручную") }
                    OutlinedButton(onClick = { importLauncher.launch("image/*") }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text(if (importing) "Распознаю…" else "Импортировать фото (можно несколько)") }
                    if (type == FinancialType.BONUS) {
                        Text("Компенсации хранятся здесь же как бонусы. Приложение может только напомнить о возможной компенсации — деньги автоматически не начисляются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (importDrafts.isNotEmpty()) {
            item { Text("Проверка импорта", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
            items(importDrafts.indices.toList(), key = { "import_$it" }) { index ->
                val draft = importDrafts[index]
                ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Запись ${index+1}", fontWeight=FontWeight.SemiBold)
                            TextButton(onClick={ importDrafts=importDrafts.toMutableList().also { it.removeAt(index) } }) { Text("Убрать") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(FinancialType.BONUS, FinancialType.PENALTY).forEach { ft -> FilterChip(selected=(if(draft.type==FinancialType.COMPENSATION) FinancialType.BONUS else draft.type)==ft, onClick={ importDrafts=importDrafts.updatedFinancialImport(index,draft.copy(type=ft)) }, label={Text(financialTypeLabelShort(ft))}, modifier=Modifier.weight(1f)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(draft.amount,{v->importDrafts=importDrafts.updatedFinancialImport(index,draft.copy(amount=v.filter{it.isDigit()||it==','||it=='.'}.take(12)))},label={Text("Сумма, Kč")},singleLine=true,modifier=Modifier.weight(1f))
                            OutlinedTextField(draft.date,{v->importDrafts=importDrafts.updatedFinancialImport(index,draft.copy(date=v.take(10)))},label={Text("Дата")},singleLine=true,modifier=Modifier.weight(1f))
                        }
                        OutlinedTextField(draft.description,{v->importDrafts=importDrafts.updatedFinancialImport(index,draft.copy(description=v.take(500)))},label={Text("Položka / Poznámka")},minLines=2,modifier=Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Button(onClick={
                    val fps=importDrafts.mapIndexedNotNull { index,d -> editableKcToHellers(d.amount)?.let { cz.courierledger.domain.FinancialDraftFingerprint(index,it,d.type,d.description) } }
                    if(featureStore.findFinancialDuplicates(fps).isNotEmpty()) showImportDuplicateConfirm=true
                    else scope.launch {
                        runCatching {
                            val count=importDrafts.size
                            importDrafts.forEach { d ->
                                val amount=editableKcToHellers(d.amount) ?: error("Проверь сумму")
                                val date=runCatching{LocalDate.parse(d.date)}.getOrElse{error("Проверь дату ${d.date}")}
                                repo.addFinancialEntry(d.type,amount,date,d.description,DataSource.OCR)
                            }
                            count
                        }.onSuccess { count -> feedback="✓ Сохранено: $count"; importDrafts=emptyList() }
                         .onFailure { feedback="Ошибка сохранения: ${it.message}" }
                    }
                },modifier=Modifier.fillMaxWidth()) { Text("Добавить / Подтвердить") }
            }
        }
        if (entries.isEmpty()) item { Text("Записей пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(entries, key = { it.id }) { entry ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.date, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (type == FinancialType.PENALTY) "−${formatKc(entry.amountHellers)}" else "+${formatKc(entry.amountHellers)}",
                            fontWeight = FontWeight.Bold,
                            color = if (type == FinancialType.PENALTY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (entry.description.isNotBlank()) Text(entry.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Источник: ${sourceLabel(entry.source)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = entry }) { Text("Изменить") }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repo.deleteFinancialEntry(entry) }
                                    .onSuccess { feedback = "✓ Запись перемещена в корзину" }
                                    .onFailure { feedback = it.message ?: "Ошибка удаления" }
                            }
                        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        feedback?.let { item { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
    }

    if (showImportDuplicateConfirm) {
        val groups=featureStore.findFinancialDuplicates(importDrafts.mapIndexedNotNull { index,d -> editableKcToHellers(d.amount)?.let { cz.courierledger.domain.FinancialDraftFingerprint(index,it,d.type,d.description) } })
        val g=groups.firstOrNull()
        AlertDialog(
            onDismissRequest={showImportDuplicateConfirm=false},
            title={Text("Возможный дубликат")},
            text={Text(if(g==null) "Проверь записи." else "Сумма ${formatKc(g.amountHellers)} с одинаковым комментарием повторяется ${g.count} раза. Это верно?")},
            confirmButton={Button(onClick={
                showImportDuplicateConfirm=false
                scope.launch {
                    runCatching {
                        val count=importDrafts.size
                        importDrafts.forEach { d ->
                            val amount=editableKcToHellers(d.amount) ?: error("Проверь сумму")
                            val date=runCatching{LocalDate.parse(d.date)}.getOrElse{error("Проверь дату ${d.date}")}
                            repo.addFinancialEntry(d.type,amount,date,d.description,DataSource.OCR)
                        }
                        count
                    }.onSuccess { count -> feedback="✓ Сохранено: $count"; importDrafts=emptyList() }.onFailure { feedback="Ошибка сохранения: ${it.message}" }
                }
            }){Text("Да, добавить все")}},
            dismissButton={TextButton(onClick={showImportDuplicateConfirm=false}){Text("Нет, проверить")}}
        )
    }

    if (showAdd || editing != null) {
        FinancialEntryDialog(
            type = type,
            existing = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { amount, date, description ->
                scope.launch {
                    runCatching {
                        val current = editing
                        if (current == null) repo.addFinancialEntry(type, amount, date, description)
                        else repo.updateFinancialEntry(current, type, amount, date, description)
                    }.onSuccess {
                        feedback = "✓ Сохранено"
                        showAdd = false
                        editing = null
                    }.onFailure { feedback = it.message ?: "Не удалось сохранить" }
                }
            }
        )
    }
}

@Composable
private fun FinancialEntryDialog(
    type: FinancialType,
    existing: FinancialEntryEntity?,
    onDismiss: () -> Unit,
    onSave: (Long, LocalDate, String) -> Unit
) {
    var amount by remember(existing?.id) { mutableStateOf(existing?.let { hellersToInputKc(it.amountHellers) }.orEmpty()) }
    var date by remember(existing?.id) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    val parsedAmount = editableKcToHellers(amount)
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Добавить ${financialTypeAccusative(type)}" else "Изменить запись") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(12) }, label = { Text("Сумма, Kč") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(240) }, label = { Text("Описание") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (type == FinancialType.PENALTY) Text("Даже штраф 0 Kč сохраняется как информационная запись.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = parsedAmount != null && parsedDate != null && parsedAmount >= 0, onClick = { onSave(parsedAmount!!, parsedDate!!, description) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AdvancesScreen(repo: CourierRepository, onBack: () -> Unit) {
    val entries by repo.advances.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AdvanceEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AppBackHeader("Авансы", onBack = onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Получено авансами", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatKc(entries.sumOf { it.amountHellers }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Аванс не является штрафом или расходом. Он уменьшает только сумму, которую ещё должны выплатить.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить аванс") }
                }
            }
        }
        items(entries, key = { it.id }) { entry ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.date, fontWeight = FontWeight.SemiBold)
                        Text(formatKc(entry.amountHellers), fontWeight = FontWeight.Bold)
                    }
                    entry.comment?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = entry }) { Text("Изменить") }
                        TextButton(onClick = { scope.launch { runCatching { repo.deleteAdvance(entry) }.onSuccess { feedback = "✓ Аванс перемещён в корзину" }.onFailure { feedback = it.message } } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        feedback?.let { item { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
    }
    if (showAdd || editing != null) {
        AdvanceDialog(editing, onDismiss = { showAdd = false; editing = null }) { amount, date, comment ->
            scope.launch {
                runCatching { editing?.let { repo.updateAdvance(it, amount, date, comment) } ?: repo.addAdvance(amount, date, comment) }
                    .onSuccess { feedback = "✓ Аванс сохранён"; showAdd = false; editing = null }
                    .onFailure { feedback = it.message }
            }
        }
    }
}

@Composable
private fun AdvanceDialog(existing: AdvanceEntity?, onDismiss: () -> Unit, onSave: (Long, LocalDate, String?) -> Unit) {
    var amount by remember(existing?.id) { mutableStateOf(existing?.let { hellersToInputKc(it.amountHellers) }.orEmpty()) }
    var date by remember(existing?.id) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    val parsedAmount = editableKcToHellers(amount)
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Добавить аванс" else "Изменить аванс") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(12) }, label = { Text("Сумма, Kč") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(comment, { comment = it.take(240) }, label = { Text("Комментарий (необязательно)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = parsedAmount != null && parsedAmount > 0 && parsedDate != null, onClick = { onSave(parsedAmount!!, parsedDate!!, comment.ifBlank { null }) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun FuelScreen(repo: CourierRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val featureStore = remember { KurierXFeatureStore(context) }
    val shifts by repo.dao.observeShifts().collectAsState(initial = emptyList())
    val routes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf(YearMonth.now().toString()) }
    var consumption by remember { mutableStateOf(settings.fuelConsumptionLPer100Km.toString()) }
    var price by remember { mutableStateOf(settings.lastDieselPriceKc.takeIf { it > 0 }?.toString().orEmpty()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val ym = runCatching { YearMonth.parse(month) }.getOrNull()
    val monthShifts = if (ym == null) emptyList() else shifts.filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) == ym }.getOrDefault(false) }
    val routeByShift = routes.groupBy { it.shiftId }
    val breakdowns = monthShifts.map { shift ->
        val sr = routeByShift[shift.id].orEmpty()
        val wh = sr.firstOrNull()?.warehouse ?: settings.defaultWarehouse
        val expectedRoundTrip = settings.warehouseDistanceKm(wh)?.times(2.0)
        featureStore.mileageBreakdown(shift.id, sr.map { it.id }, expectedRoundTrip)
    }
    val totalActual = breakdowns.mapNotNull { it.totalActualKm }.sum()
    val totalRoutes = breakdowns.sumOf { it.routeKm }
    val totalCommute = breakdowns.mapNotNull { it.homeWorkHomeKm }.sum()
    val totalOutside = breakdowns.mapNotNull { it.outsideRouteKm }.sum()
    val cons = consumption.replace(',','.').toDoubleOrNull()?.takeIf { it > 0 }
    val dieselPrice = price.replace(',','.').toDoubleOrNull()?.takeIf { it >= 0 }
    val liters = if (cons != null) totalActual * cons / 100.0 else null
    val cost = if (liters != null && dieselPrice != null) liters * dieselPrice else null

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppBackHeader("Дизель", "Фактический пробег и автоматический перерасчёт", onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(month, { month = it.take(7) }, label = { Text("Месяц YYYY-MM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(consumption, { consumption = it.filter { ch -> ch.isDigit() || ch==',' || ch=='.' }.take(6) }, label={Text("Расход, л/100 км")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch==',' || ch=='.' }.take(8) }, label={Text("Цена дизеля, Kč/л")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    HorizontalDivider()
                    SalaryStatLine("Километраж трасс", "%.1f км".format(totalRoutes))
                    SalaryStatLine("Дорога дом → работа → дом", "%.1f км".format(totalCommute))
                    SalaryStatLine("Перерасход мимо трасс", "%.1f км".format(totalOutside))
                    SalaryStatLine("Общий фактический пробег", "%.1f км".format(totalActual), true)
                    SalaryStatLine("Рассчитанные литры", liters?.let { "%.2f л".format(it) } ?: "—")
                    SalaryStatLine("Стоимость дизеля", cost?.let { "%.2f Kč".format(it) } ?: "—", true)
                    Text("Формула: литры = км × расход / 100; стоимость = литры × цена. Дорога дом–работа учитывается отдельно и не попадает в перерасход.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = ym != null && cons != null && dieselPrice != null && cost != null, modifier=Modifier.fillMaxWidth(), onClick={
                        settings.fuelConsumptionLPer100Km = cons!!
                        settings.lastDieselPriceKc = dieselPrice!!
                        scope.launch {
                            runCatching { repo.setFuelExpense(ym!!, (cost!! * 100).toLong()) }
                                .onSuccess { feedback="✓ Расчёт сохранён и связан со статистикой" }
                                .onFailure { feedback=it.message }
                        }
                    }) { Text("Сохранить рассчитанный расход") }
                    feedback?.let { Text(it, color=if(it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (monthShifts.isEmpty()) item { Text("За выбранный месяц смены не найдены.", color=MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(monthShifts, key={it.id}) { shift ->
            val sr = routeByShift[shift.id].orEmpty()
            val wh = sr.firstOrNull()?.warehouse ?: settings.defaultWarehouse
            val b = featureStore.mileageBreakdown(shift.id, sr.map { it.id }, settings.warehouseDistanceKm(wh)?.times(2.0))
            ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(formatDate(shift.date), fontWeight=FontWeight.SemiBold)
                    Text("Трассы %.1f км · дом↔работа %s · вне трасс %s · факт %s".format(b.routeKm, b.homeWorkHomeKm?.let { "%.1f км".format(it) } ?: "—", b.outsideRouteKm?.let { "%.1f км".format(it) } ?: "—", b.totalActualKm?.let { "%.1f км".format(it) } ?: "—"), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class SalaryMonthUi(
    val month: YearMonth,
    val stats: PeriodStatistics,
    val money: PeriodMoneyResult,
    val actualHellers: Long
)

@Composable
private fun SalaryScreen(repo: CourierRepository, onBack: () -> Unit) {
    val payments by repo.salaryPayments.collectAsState(initial = emptyList())
    val liveRoutes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    val liveCustomerRows by repo.dao.observeCustomerOrders().collectAsState(initial = emptyList())
    val liveShifts by repo.dao.observeShifts().collectAsState(initial = emptyList())
    val liveFinancial by repo.financialEntries.collectAsState(initial = emptyList())
    val dataRevision by repo.dataRevision.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var year by remember { mutableIntStateOf(YearMonth.now().year) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var yearRows by remember { mutableStateOf<List<SalaryMonthUi>>(emptyList()) }
    var editing by remember { mutableStateOf<SalaryPaymentEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(year, payments, liveRoutes, liveCustomerRows, liveShifts, liveFinancial, dataRevision) {
        yearRows = (1..12).map { m ->
            val ym = YearMonth.of(year, m)
            val stats = repo.periodStatistics(ym.atDay(1), ym.atEndOfMonth())
            val money = repo.monthSummary(ym)
            val actual = payments
                .filter { it.periodStart <= ym.atEndOfMonth().toString() && it.periodEnd >= ym.atDay(1).toString() }
                .sumOf { it.amountHellers }
            SalaryMonthUi(ym, stats, money, actual)
        }
        if (selectedMonth.year != year) selectedMonth = YearMonth.of(year, 1)
    }

    val selected = yearRows.firstOrNull { it.month == selectedMonth }
    val monthPayments = payments.filter { it.periodStart <= selectedMonth.atEndOfMonth().toString() && it.periodEnd >= selectedMonth.atDay(1).toString() }
    val expected = selected?.money?.expectedPayout?.hellers ?: 0L
    val actual = selected?.actualHellers ?: 0L
    val difference = actual - expected

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Зарплата", onBack = onBack) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Доход по месяцам", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(year.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { year++ }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val maxIncome = (yearRows.maxOfOrNull { maxOf(it.money.expectedPayout.hellers, it.actualHellers) } ?: 0L).coerceAtLeast(1L)
                    Row(
                        Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        yearRows.forEach { row ->
                            val value = if (row.actualHellers > 0L) row.actualHellers else row.money.expectedPayout.hellers
                            val fraction = (value.toFloat() / maxIncome.toFloat()).coerceIn(0f, 1f)
                            val selectedBar = row.month == selectedMonth
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { selectedMonth = row.month },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                if (selectedBar) Text(formatCompactKc(value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.72f)
                                        .height((18f + 94f * fraction).dp)
                                        .then(if (selectedBar) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                                        .background(
                                            if (selectedBar) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                        )
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(row.month.month.getDisplayName(TextStyle.SHORT, Locale("ru")).take(3), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                    Text("Нажми на месяц, чтобы открыть его статистику", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        selected?.let { row ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val title = row.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() } + " ${row.month.year}"
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        HorizontalDivider()
                        SalaryStatLine("Заказы", row.stats.factualOrders.toString())
                        SalaryStatLine("Чаевые", formatKc(row.stats.tipsHellers))
                        SalaryStatLine("Смены", row.stats.shifts.toString())
                        SalaryStatLine("Колечки OT", row.stats.ot.toString())
                        SalaryStatLine("Колечки Reg", (row.stats.region * 2).toString())
                        SalaryStatLine("Колечки EXP", row.stats.express.toString())
                        SalaryStatLine("Бонусы", formatKc(row.money.bonuses.hellers))
                        SalaryStatLine("Штрафы", formatKc(row.money.penalties.hellers))
                        HorizontalDivider()
                        SalaryStatLine("Расчётный заработок", formatKc(row.money.expectedPayout.hellers), true)
                        SalaryStatLine("Факт заработка", if (row.actualHellers > 0L) formatKc(row.actualHellers) else "—", true)
                        if (row.actualHellers > 0L) {
                            val diff = row.actualHellers - row.money.expectedPayout.hellers
                            Text(
                                if (diff == 0L) "✓ Факт совпадает с расчётом" else "Разница: ${if (diff > 0) "+" else ""}${formatKc(diff)}",
                                color = when { diff == 0L -> MaterialTheme.colorScheme.primary; diff < 0L -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить фактическую выплату") }
                    }
                }
            }
        }
        if (monthPayments.isNotEmpty()) {
            item { Text("Фактические выплаты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(monthPayments, key = { it.id }) { payment ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(payment.receivedDate, fontWeight = FontWeight.SemiBold)
                            Text(formatKc(payment.amountHellers), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Период: ${payment.periodStart} — ${payment.periodEnd}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        payment.comment?.let { Text(it) }
                        if (payment.payslipPhotoId != null) Text("✓ Фото расчётного листа сохранено", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editing = payment }) { Text("Изменить") }
                            TextButton(onClick = { scope.launch { runCatching { repo.deleteSalaryPayment(payment) }.onSuccess { feedback = "✓ Выплата удалена" }.onFailure { feedback = it.message } } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        feedback?.let { item { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
    }

    if (showAdd || editing != null) {
        SalaryPaymentDialog(
            repo = repo,
            context = context,
            existing = editing,
            defaultMonth = selectedMonth,
            onDismiss = { showAdd = false; editing = null },
            onSaved = { feedback = "✓ Выплата сохранена"; showAdd = false; editing = null }
        )
    }
}

@Composable
private fun SalaryStatLine(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold, color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatCompactKc(hellers: Long): String {
    val kc = hellers / 100.0
    return when {
        kotlin.math.abs(kc) >= 1_000_000 -> "%.1fM".format(Locale.US, kc / 1_000_000.0)
        kotlin.math.abs(kc) >= 1_000 -> "%.0fk".format(Locale.US, kc / 1_000.0)
        else -> "%.0f".format(Locale.US, kc)
    }
}

@Composable
private fun SalaryPaymentDialog(
    repo: CourierRepository,
    context: android.content.Context,
    existing: SalaryPaymentEntity?,
    defaultMonth: YearMonth,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var amount by remember(existing?.id) { mutableStateOf(existing?.let{hellersToInputKc(it.amountHellers)}.orEmpty()) }
    var received by remember(existing?.id) { mutableStateOf(existing?.receivedDate ?: LocalDate.now().toString()) }
    var periodStart by remember(existing?.id, defaultMonth) { mutableStateOf(existing?.periodStart ?: defaultMonth.atDay(1).toString()) }
    var periodEnd by remember(existing?.id, defaultMonth) { mutableStateOf(existing?.periodEnd ?: defaultMonth.atEndOfMonth().toString()) }
    var comment by remember(existing?.id) { mutableStateOf(existing?.comment.orEmpty()) }
    var photoId by remember(existing?.id) { mutableStateOf(existing?.payslipPhotoId) }
    var photoStatus by remember(existing?.id) { mutableStateOf(if(existing?.payslipPhotoId!=null) "Фото прикреплено" else "") }
    var error by remember { mutableStateOf<String?>(null) }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val stored=withContext(Dispatchers.IO){persistCalendarPhoto(context,uri,null)}
                val existingPhoto=withContext(Dispatchers.IO){repo.dao.photoByHash(stored.sha256)}
                photoId=existingPhoto?.id ?: withContext(Dispatchers.IO){ repo.dao.insertSourcePhoto(SourcePhotoEntity(uri=stored.uri.toString(),localPath=stored.localPath,sha256=stored.sha256,createdAt=System.currentTimeMillis(),kind="PAYSLIP")) }
            }.onSuccess { photoStatus="✓ Фото расчётного листа прикреплено" }.onFailure { photoStatus="Ошибка фото: ${it.message}" }
        }
    }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(existing==null) "Фактическая выплата" else "Изменить выплату")},
        text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(amount,{amount=it.filter{ch->ch.isDigit()||ch==','||ch=='.'}.take(12)},label={Text("Получено, Kč")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(received,{received=it.take(10)},label={Text("Дата получения")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(periodStart,{periodStart=it.take(10)},label={Text("Период с")},singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(periodEnd,{periodEnd=it.take(10)},label={Text("по")},singleLine=true,modifier=Modifier.weight(1f))
            }
            OutlinedTextField(comment,{comment=it.take(300)},label={Text("Комментарий")},minLines=2,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={gallery.launch("image/*")},modifier=Modifier.fillMaxWidth()){Text(if(photoId==null) "Прикрепить фото расчётного листа" else "Заменить фото")}
            if(photoStatus.isNotBlank()) Text(photoStatus,style=MaterialTheme.typography.bodySmall,color=if(photoStatus.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={Button(onClick={
            scope.launch {
                runCatching {
                    val a=editableKcToHellers(amount) ?: error("Проверь сумму")
                    val rd=LocalDate.parse(received); val ps=LocalDate.parse(periodStart); val pe=LocalDate.parse(periodEnd)
                    if(existing==null) repo.addSalaryPayment(rd,a,ps,pe,comment,photoId) else repo.updateSalaryPayment(existing,rd,a,ps,pe,comment,photoId)
                }.onSuccess{onSaved()}.onFailure{error=it.message ?: "Ошибка"}
            }
        }){Text("Сохранить")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}}
    )
}

@Composable
private fun GoalsScreen(repo: CourierRepository, onBack: () -> Unit) {
    val goals by repo.goals.collectAsState(initial=emptyList())
    val calendar by repo.calendar.collectAsState(initial=emptyList())
    val liveRoutes by repo.dao.observeAllRoutes().collectAsState(initial=emptyList())
    val liveCustomerRows by repo.dao.observeCustomerOrders().collectAsState(initial=emptyList())
    val liveShifts by repo.dao.observeShifts().collectAsState(initial=emptyList())
    val dataRevision by repo.dataRevision.collectAsState()
    val scope=rememberCoroutineScope()
    var month by remember{mutableStateOf(YearMonth.now())}
    var progress by remember{mutableStateOf<cz.courierledger.domain.GoalProgress?>(null)}
    var targetInput by remember{mutableStateOf("")}
    var feedback by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(month,goals,calendar,liveRoutes,liveCustomerRows,liveShifts,dataRevision){
        progress=runCatching{repo.goalProgress(month)}.getOrNull()
        targetInput=goals.firstOrNull{it.month==month.toString()}?.targetOrders?.toString().orEmpty()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(top=14.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{AppBackHeader("Цель по заказам",onBack=onBack)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={month=month.minusMonths(1)}){Text("‹",style=MaterialTheme.typography.headlineMedium)}
            Text("${month.month.getDisplayName(TextStyle.FULL_STANDALONE,Locale("ru"))} ${month.year}",fontWeight=FontWeight.Bold)
            IconButton(onClick={month=month.plusMonths(1)}){Text("›",style=MaterialTheme.typography.headlineMedium)}
        }}
        item{ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(targetInput,{targetInput=it.filter(Char::isDigit).take(6)},label={Text("Цель, заказов")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={scope.launch{runCatching{repo.setGoal(month,targetInput.toIntOrNull()?:error("Укажи цель"))}.onSuccess{feedback="✓ Цель сохранена"}.onFailure{feedback=it.message}}},modifier=Modifier.fillMaxWidth()){Text("Сохранить цель")}
        }}}
        progress?.let { p ->
            item{ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                Text("Прогресс",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("Цель: ${p.targetOrders ?: "—"}")
                Text("Сделано: ${p.completedOrders}")
                Text("Осталось: ${p.remainingOrders}")
                Text("Осталось рабочих дней: ${p.remainingWorkDays}")
                Text("Нужно: ${if(p.requiredPerWorkDay>0) String.format(Locale.US,"%.2f",p.requiredPerWorkDay) else "0"} заказа/день",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            }}}
        }
        feedback?.let{item{Text(it,color=if(it.startsWith("✓"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}}
    }
}

@Composable
private fun ShiftsScreen(repo: CourierRepository, developerUnlocked: Boolean, onBack: () -> Unit, onRequireDeveloper: () -> Unit) {
    val shifts by repo.dao.observeShifts().collectAsState(initial = emptyList())
    val liveRoutes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    val dataRevision by repo.dataRevision.collectAsState()
    var summaries by remember { mutableStateOf<List<ShiftHistorySummary>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(shifts, liveRoutes, dataRevision) {
        summaries = withContext(Dispatchers.IO) { repo.shiftHistorySummaries() }
    }

    val selected = selectedId?.let { id -> summaries.firstOrNull { it.shift.id == id } }
    if (selected != null) {
        ShiftDetailScreen(repo, selected, developerUnlocked, onBack = { selectedId = null }, onRequireDeveloper = onRequireDeveloper)
        return
    }

    val totalWorked = summaries.sumOf { it.workedMillis }
    val totalPlan = summaries.sumOf { it.plannedRings }
    val totalFact = summaries.sumOf { it.completedRings }
    val complete = summaries.count { it.shift.status == ShiftStatus.COMPLETE }
    val partial = summaries.count { it.shift.status == ShiftStatus.PARTIAL }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppBackHeader("Смены", onBack = onBack)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Всего смен: ${summaries.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Полностью выполнено: $complete · частично: $partial")
                    Text("Отработано: ${formatDuration(totalWorked)}")
                    Text("План: $totalPlan колечек · выполнено: $totalFact")
                }
            }
        }
        if (summaries.isEmpty()) item { Text("Смен пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(summaries, key = { it.shift.id }) { summary ->
            ElevatedCard(
                Modifier.fillMaxWidth().clickable { selectedId = summary.shift.id },
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDate(summary.shift.date), fontWeight = FontWeight.SemiBold)
                        Text(shiftStatusLabel(summary.shift.status), color = shiftStatusColor(summary.shift.status))
                    }
                    Text("${summary.completedRings}/${summary.plannedRings} K · ${summary.factualOrders} заказов · ${formatDuration(summary.workedMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatKc(summary.grossHellers), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ShiftDetailScreen(repo: CourierRepository, summary: ShiftHistorySummary, developerUnlocked: Boolean, onBack: () -> Unit, onRequireDeveloper: () -> Unit) {
    BackHandler(onBack = onBack)
    var reconciliation by remember(summary.shift.id) { mutableStateOf<cz.courierledger.domain.ReconciliationResult?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var shiftActionMessage by remember { mutableStateOf<String?>(null) }
    var showDeveloperRequired by remember { mutableStateOf(false) }
    var showShiftDelete by remember { mutableStateOf(false) }
    var showTimeEdit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shift = summary.shift

    LaunchedEffect(shift.id) {
        runCatching { withContext(Dispatchers.IO) { repo.reconciliationForShift(shift.id) } }
            .onSuccess { reconciliation = it }
            .onFailure { checkError = it.message }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppBackHeader(formatDate(shift.date), shiftStatusLabel(shift.status), onBack)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Рабочее время", fontWeight = FontWeight.SemiBold)
                    Text("Начало: ${formatClock(shift.startedAt)} · конец: ${formatClock(shift.endedAt)}")
                    Text("Отработано: ${formatDuration(summary.workedMillis)}")
                    HorizontalDivider()
                    Text("Колечки: ${summary.completedRings}/${summary.plannedRings}")
                    Text("Клиентов: ${summary.clients} · фактических заказов: ${summary.factualOrders}")
                    Text("Чаевые: ${formatKc(summary.tipsHellers)}")
                    Text("По трассам: ${formatKc(summary.grossHellers)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    shift.underfulfillmentComment?.takeIf { it.isNotBlank() }?.let { Text("Комментарий: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ if(developerUnlocked) showTimeEdit=true else showDeveloperRequired=true },modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=if(developerUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)){Text("Изменить время")}
                        OutlinedButton(onClick={ if(developerUnlocked) showShiftDelete=true else showDeveloperRequired=true },modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=if(developerUnlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)){Text("Удалить")}
                    }
                    if(!developerUnlocked) Text("Критические действия заблокированы · включи расширенный режим",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    shiftActionMessage?.let{Text(it,color=if(it.startsWith("✓"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)}
                }
            }
        }
        item { Text("Трассы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(summary.routes, key = { it.route.id }) { route ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${routeTypeLabel(route.route.routeType)} · ${warehouseLabel(route.route.warehouse)} · #${route.route.id}", fontWeight = FontWeight.SemiBold)
                    Text("${EarningsCalculator.rings(route.route.routeType)} K · ${route.clients} клиентов → ${route.factualOrders} заказов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Чаевые ${formatKc(route.tipsHellers)} · всего ${formatKc(route.grossHellers)}")
                }
            }
        }
        reconciliation?.let { result ->
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (result.passed) "✓ Сверка пройдена" else "▲ Есть несоответствия", fontWeight = FontWeight.Bold, color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                        result.items.forEach { item ->
                            Text("${if (item.ok) "✓" else "▲"} ${item.title}: ${item.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        checkError?.let { message -> item { Text("Не удалось выполнить сверку: $message", color = MaterialTheme.colorScheme.error) } }
    }
    if (showDeveloperRequired) {
        DeveloperRequiredDialog(onDismiss = { showDeveloperRequired = false }, onGo = { showDeveloperRequired = false; onRequireDeveloper() })
    }
    if(showShiftDelete) AlertDialog(onDismissRequest={showShiftDelete=false},title={Text("Удалить смену?")},text={Text("Смена, её трассы и клиенты исчезнут из всех расчётов и попадут в корзину.")},confirmButton={Button(colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),onClick={scope.launch{runCatching{repo.deleteShift(shift.id)}.onSuccess{showShiftDelete=false;onBack()}.onFailure{shiftActionMessage=it.message}}}){Text("В корзину")}},dismissButton={TextButton(onClick={showShiftDelete=false}){Text("Отмена")}})
    if(showTimeEdit) EditShiftTimeDialog(shift=shift,onDismiss={showTimeEdit=false},onSave={start,end->scope.launch{runCatching{repo.updateClosedShiftTimes(shift.id,start,end)}.onSuccess{shiftActionMessage="✓ Время смены изменено";showTimeEdit=false}.onFailure{shiftActionMessage=it.message}}})
}

@Composable
private fun EditShiftTimeDialog(shift:ShiftEntity,onDismiss:()->Unit,onSave:(Long,Long)->Unit){
    val zone=ZoneId.systemDefault(); val date=LocalDate.parse(shift.date)
    var start by remember{mutableStateOf(formatClock(shift.startedAt).takeIf{it!="—"}?:"06:00")}
    var end by remember{mutableStateOf(formatClock(shift.endedAt).takeIf{it!="—"}?:"14:00")}
    var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить время смены")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(start,{start=it.take(5)},label={Text("Начало HH:mm")},singleLine=true);OutlinedTextField(end,{end=it.take(5)},label={Text("Конец HH:mm")},singleLine=true);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={Button(onClick={runCatching{val st=java.time.LocalTime.parse(start);val en=java.time.LocalTime.parse(end);val sm=date.atTime(st).atZone(zone).toInstant().toEpochMilli();val endDate=if(en.isBefore(st))date.plusDays(1)else date;val em=endDate.atTime(en).atZone(zone).toInstant().toEpochMilli();onSave(sm,em)}.onFailure{error="Формат времени HH:mm"}}){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return "${minutes / 60} ч ${minutes % 60} мин"
}
private fun formatClock(value: Long?): String = value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—"
private fun formatDate(value: String): String = runCatching { java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrDefault(value)
private fun shiftStatusLabel(status: ShiftStatus): String = when (status) { ShiftStatus.PLANNED -> "Запланирована"; ShiftStatus.ACTIVE -> "Активна"; ShiftStatus.COMPLETE -> "Выполнена"; ShiftStatus.PARTIAL -> "Частично выполнена" }
@Composable private fun shiftStatusColor(status: ShiftStatus) = when (status) { ShiftStatus.COMPLETE -> MaterialTheme.colorScheme.primary; ShiftStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary; ShiftStatus.ACTIVE -> MaterialTheme.colorScheme.secondary; ShiftStatus.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant }
private fun routeTypeLabel(type: RouteType): String = when (type) { RouteType.OT -> "OT"; RouteType.REGION -> "Region"; RouteType.EXPRESS -> "Express" }

@Composable
private fun ClientsScreen(repo: CourierRepository, developerUnlocked: Boolean, onBack: () -> Unit, onRequireDeveloper: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val rawRows by repo.dao.observeCustomerOrders().collectAsState(initial = emptyList())
    val activeRoutes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    val dataRevision by repo.dataRevision.collectAsState()
    val activeRouteIds = remember(activeRoutes, dataRevision) { activeRoutes.mapTo(hashSetOf()) { it.id } }
    val rows = remember(rawRows, activeRouteIds, dataRevision) { rawRows.filter { it.routeId in activeRouteIds } }
    val scope = rememberCoroutineScope()
    var mapError by remember { mutableStateOf<String?>(null) }
    var editingOrder by remember { mutableStateOf<CustomerOrderRow?>(null) }
    var deleteOrder by remember { mutableStateOf<CustomerOrderRow?>(null) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var showDeveloperRequired by remember { mutableStateOf(false) }
    val byDate = rows.groupBy { it.routeDate }.toSortedMap(compareByDescending { it })

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Клиенты", onBack = onBack); Text("История сгруппирована по дням и колечкам. Адрес открывается в ${settings.mapProvider.label}.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (rows.isEmpty()) item { Text("Сохранённых клиентов пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        byDate.forEach { (date, dateRows) ->
            item(key="date_$date") { Text(formatDate(date), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top=8.dp)) }
            val routes = dateRows.groupBy { it.routeId }.toList().sortedBy { it.first }
            routes.forEachIndexed { routeIndex, (_, routeRows) ->
                val first = routeRows.first()
                item(key="route_${first.routeId}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${routeIndex + 1} kolo · ${routeTypeLabel(first.routeType)}", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold, color=MaterialTheme.colorScheme.primary)
                        Text(warehouseLabel(first.warehouse), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(routeRows, key = { it.orderId }) { row ->
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val name = listOf(row.firstName, row.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Без имени" }
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(row.displayAddress, modifier = Modifier.fillMaxWidth().clickable { MapLauncher.openAddress(context, row.displayAddress, settings.mapProvider).onFailure { mapError = it.message ?: "Не удалось открыть карту" } }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Пакеты ${row.packages} · чаевые ${formatKc(row.tipHellers)}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { editingOrder = row }) { Text("Изменить") }
                                TextButton(onClick = { if(developerUnlocked) deleteOrder=row else showDeveloperRequired=true }, colors=ButtonDefaults.textButtonColors(contentColor=if(developerUnlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
        mapError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        editMessage?.let { item { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
    }

    if (showDeveloperRequired) {
        DeveloperRequiredDialog(onDismiss = { showDeveloperRequired = false }, onGo = { showDeveloperRequired = false; onRequireDeveloper() })
    }

    editingOrder?.let { row ->
        EditCustomerDialog(
            row = RouteCustomerRow(row.orderId,row.customerId,row.firstName,row.lastName,row.displayAddress,"",row.packages,row.tipHellers,null),
            onDismiss = { editingOrder = null },
            onSave = { first, last, address, packages, tipHellers -> scope.launch { runCatching { repo.updateCustomerOrder(row.orderId, first, last, address, packages, tipHellers) }.onSuccess { editMessage = "✓ Клиент сохранён и трасса пересчитана"; editingOrder = null }.onFailure { editMessage = "Не удалось сохранить: ${it.message}" } } }
        )
    }
    deleteOrder?.let { row ->
        AlertDialog(onDismissRequest={deleteOrder=null},title={Text("Удалить клиента?")},text={Text("Запись попадёт в корзину и перестанет влиять на трассу, статистику и чаевые до восстановления.")},confirmButton={Button(colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),onClick={scope.launch{runCatching{repo.deleteCustomerOrder(row.orderId)}.onSuccess{editMessage="✓ Клиент перемещён в корзину";deleteOrder=null}.onFailure{editMessage=it.message}}}){Text("В корзину")}},dismissButton={TextButton(onClick={deleteOrder=null}){Text("Отмена")}})
    }
}

@Composable
private fun JournalScreen(repo: CourierRepository, onBack: () -> Unit) {
    val logs by repo.auditLog.collectAsState(initial = emptyList())
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Журнал изменений", "Последние 500 существенных действий", onBack) }
        if (logs.isEmpty()) item { Text("Журнал пока пуст.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(logs, key = { it.id }) { log ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(formatAuditTime(log.createdAt), fontWeight = FontWeight.SemiBold)
                    Text(auditActionLabel(log.action), color = MaterialTheme.colorScheme.primary)
                    Text("${log.entityType} #${log.entityId} · ${sourceLabel(log.source)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    log.oldValue?.takeIf { it.isNotBlank() }?.let { Text("Было: $it", style = MaterialTheme.typography.bodySmall) }
                    log.newValue?.takeIf { it.isNotBlank() }?.let { Text("Стало: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun TrashScreen(repo: CourierRepository, onBack: () -> Unit) {
    val routes by repo.deletedRoutes.collectAsState(initial = emptyList())
    val shifts by repo.deletedShifts.collectAsState(initial = emptyList())
    val deletedOrders by repo.deletedOrders.collectAsState(initial = emptyList())
    val financial by repo.deletedFinancial.collectAsState(initial = emptyList())
    val advances by repo.deletedAdvances.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val thirtyDays = 30L * 24L * 60L * 60L * 1000L

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Корзина", "Удалённые записи хранятся 30 дней", onBack) }
        item {
            Text("Всего: ${routes.size + shifts.size + deletedOrders.size + financial.size + advances.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
        items(shifts, key={"shift_${it.id}"}) { shift ->
            TrashCard(title="Смена ${formatDate(shift.date)}",subtitle="${shiftStatusLabel(shift.status)} · план ${shift.plannedRings}K",deletedAt=shift.deletedAt,canPurge=shift.deletedAt?.let{now-it>=thirtyDays}==true,onRestore={scope.launch{runCatching{repo.restoreShiftFromTrash(shift)}.onSuccess{message="✓ Смена восстановлена"}.onFailure{message=it.message}}},onPurge={scope.launch{runCatching{repo.permanentlyDeleteShift(shift)}.onSuccess{message="✓ Смена удалена навсегда"}.onFailure{message=it.message}}})
        }
        items(deletedOrders,key={"order_${it.id}"}) { order ->
            TrashCard(title="Клиент / заказ #${order.id}",subtitle="Удалён из трассы #${order.routeId}",deletedAt=order.deletedAt,canPurge=order.deletedAt?.let{now-it>=thirtyDays}==true,onRestore={scope.launch{runCatching{repo.restoreCustomerOrderFromTrash(order)}.onSuccess{message="✓ Клиент восстановлен"}.onFailure{message=it.message}}},onPurge={scope.launch{runCatching{repo.permanentlyDeleteCustomerOrder(order)}.onSuccess{message="✓ Клиент удалён навсегда"}.onFailure{message=it.message}}})
        }
        items(routes, key = { "route_${it.id}" }) { route ->
            TrashCard(
                title = "Трасса #${route.id} · ${routeTypeLabel(route.routeType)}",
                subtitle = "${route.routeDate} · ${warehouseLabel(route.warehouse)}",
                deletedAt = route.deletedAt,
                canPurge = route.deletedAt?.let { now - it >= thirtyDays } == true,
                onRestore = { scope.launch { runCatching { repo.restoreRouteFromTrash(route) }.onSuccess { message="✓ Трасса восстановлена" }.onFailure { message=it.message } } },
                onPurge = { scope.launch { runCatching { repo.permanentlyDeleteRoute(route) }.onSuccess { message="✓ Трасса удалена навсегда" }.onFailure { message=it.message } } }
            )
        }
        items(financial, key = { "fin_${it.id}" }) { entry ->
            TrashCard(
                title = "${if(entry.type==FinancialType.PENALTY) "Штраф" else "Бонус"} · ${formatKc(entry.amountHellers)}",
                subtitle = "${entry.date} · ${entry.description.take(80)}",
                deletedAt = entry.deletedAt,
                canPurge = entry.deletedAt?.let { now - it >= thirtyDays } == true,
                onRestore = { scope.launch { runCatching { repo.restoreFinancialFromTrash(entry) }.onSuccess { message="✓ Запись восстановлена" }.onFailure { message=it.message } } },
                onPurge = { scope.launch { runCatching { repo.permanentlyDeleteFinancial(entry) }.onSuccess { message="✓ Запись удалена навсегда" }.onFailure { message=it.message } } }
            )
        }
        items(advances, key = { "adv_${it.id}" }) { entry ->
            TrashCard(
                title = "Аванс · ${formatKc(entry.amountHellers)}",
                subtitle = "${entry.date} · ${entry.comment.orEmpty()}",
                deletedAt = entry.deletedAt,
                canPurge = entry.deletedAt?.let { now - it >= thirtyDays } == true,
                onRestore = { scope.launch { runCatching { repo.restoreAdvanceFromTrash(entry) }.onSuccess { message="✓ Аванс восстановлен" }.onFailure { message=it.message } } },
                onPurge = { scope.launch { runCatching { repo.permanentlyDeleteAdvance(entry) }.onSuccess { message="✓ Аванс удалён навсегда" }.onFailure { message=it.message } } }
            )
        }
        if (routes.isEmpty() && shifts.isEmpty() && deletedOrders.isEmpty() && financial.isEmpty() && advances.isEmpty()) item { Text("Корзина пуста.", color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TrashCard(title: String, subtitle: String, deletedAt: Long?, canPurge: Boolean, onRestore: () -> Unit, onPurge: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            deletedAt?.let { Text("Удалено ${formatAuditTime(it)}", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("Восстановить") }
                if (canPurge) Button(onClick = onPurge, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Навсегда") }
            }
            if (!canPurge) Text("Окончательное удаление станет доступно через 30 дней.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackupsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { BackupManager(context) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var autoBackups by remember { mutableStateOf(manager.automaticBackups()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingExport
        if (uri != null && file != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Не удалось открыть файл") } }
                .onSuccess { message = "✓ Backup сохранён" }
                .onFailure { message = "Ошибка экспорта: ${it.message}" }
            file.delete(); pendingExport = null
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), contentPadding=PaddingValues(top=14.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { AppBackHeader("Резервные копии", "Локальные + переносимый зашифрованный backup", onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(password, { password=it.take(64) }, label={Text("Пароль backup (минимум 6 символов)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                    Button(onClick={
                        scope.launch {
                            runCatching {
                                require(password.length >= 6) { "Введите пароль минимум из 6 символов" }
                                val temp=File(context.cacheDir,"KurierX-${System.currentTimeMillis()}.clbackup")
                                withContext(Dispatchers.IO) { manager.createPortableBackup(temp,password.toCharArray()) }
                                pendingExport=temp
                                exportLauncher.launch("KurierX-${LocalDate.now()}.clbackup")
                            }.onFailure { message="Ошибка backup: ${it.message}" }
                        }
                    }, modifier=Modifier.fillMaxWidth()) { Text("Создать полный backup") }
                    OutlinedButton(onClick={ restoreLauncher.launch(arrayOf("application/octet-stream","application/zip","*/*")) }, modifier=Modifier.fillMaxWidth()) { Text("Выбрать backup для восстановления") }
                    restoreUri?.let { uri ->
                        Button(onClick={
                            scope.launch {
                                runCatching {
                                    require(password.length >= 6) { "Введите пароль backup" }
                                    val temp=File(context.cacheDir,"incoming-${System.currentTimeMillis()}.clbackup")
                                    withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("Не удалось прочитать backup")
                                        manager.stagePortableRestore(temp,password.toCharArray()); temp.delete()
                                    }
                                }.onSuccess { message="✓ Восстановление подготовлено. Полностью закрой приложение и открой снова."; restoreUri=null }
                                 .onFailure { message="Ошибка восстановления: ${it.message}" }
                            }
                        }, colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary), modifier=Modifier.fillMaxWidth()) { Text("Подтвердить восстановление") }
                    }
                    message?.let { Text(it, color=if(it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Text("Автоматические локальные backup", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold) }
        items(autoBackups, key={it.absolutePath}) { f ->
            ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, fontWeight=FontWeight.Medium)
                    Text("${f.length()/1024} KB · ${formatAuditTime(f.lastModified())}", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { TextButton(onClick={ autoBackups=manager.automaticBackups() }) { Text("Обновить список") } }
    }
}

@Composable
private fun DeveloperModeScreen(unlocked: Boolean, onUnlockedChange: (Boolean)->Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val featureStore = remember { KurierXFeatureStore(context) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var baselineInput by remember { mutableStateOf(featureStore.orderBaseline?.toString().orEmpty()) }
    var regionWeekday by remember { mutableStateOf((featureStore.regionWeekdayHellers / 100.0).toString()) }
    var onTimeWeekday by remember { mutableStateOf((featureStore.onTimeWeekdayHellers / 100.0).toString()) }
    var regionWeekend by remember { mutableStateOf((featureStore.regionWeekendHellers / 100.0).toString()) }
    var onTimeWeekend by remember { mutableStateOf((featureStore.onTimeWeekendHellers / 100.0).toString()) }
    var githubApi by remember { mutableStateOf(featureStore.githubReleaseApiUrl) }
    val activity = context as? FragmentActivity

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), contentPadding=PaddingValues(top=14.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { AppBackHeader("Расширенный режим", "PIN + биометрия для опасных операций", onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(if(unlocked) "✓ Режим разблокирован" else "Режим заблокирован", fontWeight=FontWeight.Bold, color=if(unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    if (!pinManager.hasPin()) {
                        Text("Сначала задай резервный 6-значный PIN.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(6)},label={Text("Новый PIN")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(6)},label={Text("Повтори PIN")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Button(onClick={ runCatching { require(pin==confirm){"PIN не совпадают"}; pinManager.setPin(pin); message="✓ PIN установлен"; pin=""; confirm="" }.onFailure{message=it.message} }, modifier=Modifier.fillMaxWidth()) { Text("Установить PIN") }
                    } else if (!unlocked) {
                        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(6)},label={Text("PIN")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Button(onClick={ if(pinManager.verify(pin)){onUnlockedChange(true);message="✓ Расширенный режим открыт"} else message="Неверный PIN" },modifier=Modifier.fillMaxWidth()){Text("Войти по PIN")}
                        if (activity != null && BiometricGate.canAuthenticate(activity)) {
                            OutlinedButton(onClick={ BiometricGate.authenticate(activity){ok,err-> if(ok){onUnlockedChange(true);message="✓ Биометрия подтверждена"} else if(err!=null) message=err } },modifier=Modifier.fillMaxWidth()){Text("Войти по биометрии")}
                        }
                    } else {
                        Text("В этой сессии разрешены диагностические и опасные действия. В финальной версии все критические редактирования будут использовать этот же шлюз.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick={onUnlockedChange(false);pin=""},modifier=Modifier.fillMaxWidth()){Text("Заблокировать")}
                    }
                    message?.let { Text(it, color=if(it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Служебные параметры", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
                    Text("Изменение доступно только после разблокировки Developer Mode.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(baselineInput,{baselineInput=it.filter(Char::isDigit).take(9)},label={Text("Точка отсчёта заказов")},singleLine=true,enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=unlocked && baselineInput.toIntOrNull()!=null,onClick={featureStore.developerSetOrderBaseline(baselineInput.toInt());message="✓ Точка отсчёта сохранена"},modifier=Modifier.weight(1f)){Text("Сохранить")}
                        OutlinedButton(enabled=unlocked,onClick={featureStore.developerSetOrderBaseline(null);baselineInput="";message="✓ Точка отсчёта сброшена"},modifier=Modifier.weight(1f)){Text("Сбросить")}
                    }
                    HorizontalDivider()
                    Text("Тарифы, Kč", fontWeight=FontWeight.SemiBold)
                    OutlinedTextField(regionWeekday,{regionWeekday=it.moneyText()},label={Text("Region — будни")},enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(onTimeWeekday,{onTimeWeekday=it.moneyText()},label={Text("On-time — будни")},enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(regionWeekend,{regionWeekend=it.moneyText()},label={Text("Region — выходные")},enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(onTimeWeekend,{onTimeWeekend=it.moneyText()},label={Text("On-time — выходные")},enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    Button(enabled=unlocked,onClick={
                        featureStore.regionWeekdayHellers = moneyTextToHellers(regionWeekday)
                        featureStore.onTimeWeekdayHellers = moneyTextToHellers(onTimeWeekday)
                        featureStore.regionWeekendHellers = moneyTextToHellers(regionWeekend)
                        featureStore.onTimeWeekendHellers = moneyTextToHellers(onTimeWeekend)
                        message="✓ Тарифы сохранены"
                    },modifier=Modifier.fillMaxWidth()){Text("Сохранить тарифы")}
                    HorizontalDivider()
                    OutlinedTextField(githubApi,{githubApi=it.take(300)},label={Text("GitHub latest release API URL")},supportingText={Text("Напр.: https://api.github.com/repos/OWNER/REPO/releases/latest")},enabled=unlocked,modifier=Modifier.fillMaxWidth())
                    Button(enabled=unlocked,onClick={featureStore.githubReleaseApiUrl=githubApi;message="✓ URL обновлений сохранён"},modifier=Modifier.fillMaxWidth()){Text("Сохранить URL обновлений")}
                }
            }
        }
    }
}

private fun formatAuditTime(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
private fun auditActionLabel(action: String): String = when(action) {
    "ROUTE_CREATE" -> "Трасса создана"
    "ROUTE_EDIT" -> "Трасса изменена"
    "ROUTE_DELETE_TO_TRASH" -> "Трасса отправлена в корзину"
    "ROUTE_RESTORE" -> "Трасса восстановлена"
    "ORDER_EDIT" -> "Клиент / заказ изменён"
    "ORDER_DELETE_TO_TRASH" -> "Клиент / заказ отправлен в корзину"
    "ORDER_RESTORE" -> "Клиент / заказ восстановлен"
    "FINANCIAL_CREATE" -> "Финансовая запись добавлена"
    "FINANCIAL_EDIT" -> "Финансовая запись изменена"
    "FINANCIAL_DELETE_TO_TRASH" -> "Финансовая запись удалена"
    "SHIFT_START" -> "Смена начата"
    "SHIFT_CLOSE" -> "Смена закрыта"
    "SHIFT_TIME_EDIT" -> "Время смены изменено"
    "SHIFT_DELETE_TO_TRASH" -> "Смена отправлена в корзину"
    "SHIFT_RESTORE" -> "Смена восстановлена"
    "CALENDAR_MONTH_IMPORT" -> "График импортирован"
    else -> action.replace('_',' ').lowercase().replaceFirstChar { it.titlecase() }
}


@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    val streetIndex = remember { RuianStreetIndex(context) }
    var provider by remember { mutableStateOf(settings.mapProvider) }
    var defaultWarehouse by remember { mutableStateOf(settings.defaultWarehouse) }
    var homeAddress by remember { mutableStateOf(settings.homeAddress) }
    var consumption by remember { mutableStateOf(settings.fuelConsumptionLPer100Km.toString()) }
    var homeMessage by remember { mutableStateOf<String?>(null) }
    var fuelMessage by remember { mutableStateOf<String?>(null) }
    val fuelEstimator = remember { cz.courierledger.fuel.FuelEstimator(context) }
    var indexInfo by remember { mutableStateOf(streetIndex.info()) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val featureStore = remember { KurierXFeatureStore(context) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateWorking by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppBackHeader("Настройки", onBack = onBack)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Склад по умолчанию",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { Warehouse.entries.forEach { wh -> FilterChip(selected=defaultWarehouse==wh,onClick={defaultWarehouse=wh;settings.defaultWarehouse=wh},label={Text(warehouseLabel(wh))},modifier=Modifier.weight(1f)) } }
                    Text("Используется для новых трасс и как резерв, если в графике склад не указан.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Дом и автоматический дизель",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    OutlinedTextField(homeAddress,{homeAddress=it.take(180)},label={Text("Адрес проживания")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(consumption,{consumption=it.filter{ch->ch.isDigit()||ch=='.'||ch==','}.take(5)},label={Text("Расход автомобиля, л/100 км")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{homeMessage="Проверяю RÚIAN и расстояния…";runCatching{withContext(Dispatchers.IO){val c=consumption.replace(',','.').toDoubleOrNull()?:error("Укажи расход");settings.fuelConsumptionLPer100Km=c;val check=fuelEstimator.validateAndGeocodeHome(homeAddress);if(check.validStreet==false) error("Улица не найдена в RÚIAN");val lat=check.lat?:error(check.message);val lon=check.lon?:error(check.message);settings.homeAddress=homeAddress.trim();settings.homeLat=lat;settings.homeLon=lon;val d=fuelEstimator.refreshDistances(lat,lon);require(d.isNotEmpty()){"Адрес сохранён, но маршрутные расстояния пока не получены"};d}}.onSuccess{d->homeMessage="✓ Адрес сохранён · "+d.entries.joinToString(" · "){"${warehouseLabel(it.key)} %.1f км".format(it.value)}}.onFailure{homeMessage=it.message}}}){Text("Проверить адрес и расстояния")}
                    OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{fuelMessage="Получаю цену дизеля с ČEPRO…";runCatching{withContext(Dispatchers.IO){fuelEstimator.refreshOfficialDieselPrice() ?: error("Официальная страница ČEPRO сейчас не отдала цены. Оставлена последняя известная цена.")}}.onSuccess{fuelMessage="✓ Средняя цена дизеля: %.2f Kč/л".format(it)}.onFailure{fuelMessage=it.message}}}){Text("Обновить цену ČEPRO")}
                    val cached=settings.lastDieselPriceKc
                    Text(if(cached>0)"Последняя известная цена: %.2f Kč/л".format(cached) else "Цена ещё не получена. Пока используется ручной расход из меню Дизель.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    homeMessage?.let{Text(it,color=if(it.startsWith("✓"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)}
                    fuelMessage?.let{Text(it,color=if(it.startsWith("✓"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)}
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Карта по умолчанию", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    MapProvider.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                provider = option
                                settings.mapProvider = option
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = provider == option, onClick = {
                                provider = option
                                settings.mapProvider = option
                            })
                            Text(option.label)
                        }
                    }
                    Text("Адрес клиента открывается одним нажатием в выбранном приложении.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Обновление KurierX", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold)
                    Text("Текущая версия: ${BuildConfig.VERSION_NAME}", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("APK обновляется поверх текущей установки — локальная база и настройки не удаляются.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled=!updateWorking, modifier=Modifier.fillMaxWidth(), onClick={
                        scope.launch {
                            updateWorking=true
                            updateMessage="Проверяю GitHub…"
                            runCatching {
                                val release=fetchGithubRelease(featureStore.githubReleaseApiUrl)
                                if(!versionIsNewer(release.version,BuildConfig.VERSION_NAME)) null else release
                            }.onSuccess { release ->
                                if(release==null) updateMessage="✓ Установлена актуальная версия"
                                else {
                                    updateMessage="Найдена ${release.version}. Скачиваю APK…"
                                    runCatching { downloadReleaseApk(context,release) }
                                        .onSuccess { apk -> updateMessage="✓ APK скачан. Подтверди системное обновление."; launchApkInstaller(context,apk) }
                                        .onFailure { updateMessage="Ошибка загрузки: ${it.message}" }
                                }
                            }.onFailure { updateMessage="Ошибка проверки: ${it.message}" }
                            updateWorking=false
                        }
                    }) { Text(if(updateWorking) "Проверяю…" else "Проверить обновление") }
                    updateMessage?.let { Text(it,color=if(it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Проверка улиц RÚIAN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (indexInfo.available) "Официальный индекс установлен · ${indexInfo.streetCount} улиц" else "Индекс ещё не установлен",
                        color = if (indexInfo.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    indexInfo.updatedAt?.let { updated ->
                        val text = Instant.ofEpochMilli(updated).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                        Text("Обновлено: $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Каталог скачивается с ČÚZK и затем работает полностью локально. OCR использует его только для неоднозначных случаев имя ↔ улица.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = !syncing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                syncing = true
                                syncMessage = "Скачиваю официальный каталог…"
                                runCatching { withContext(Dispatchers.IO) { streetIndex.updateFromOfficialSource() } }
                                    .onSuccess { info -> indexInfo = info; syncMessage = "✓ Каталог обновлён" }
                                    .onFailure { syncMessage = "Ошибка обновления: ${it.message}" }
                                syncing = false
                            }
                        }
                    ) { Text(if (syncing) "Обновление…" else "Обновить официальный каталог улиц") }
                    syncMessage?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
 fun AppBackHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(42.dp).clickable(onClick = onBack),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EditRouteDialog(
    route: RouteEntity,
    onDismiss: () -> Unit,
    onSave: (RouteType, Warehouse, Int?, String?) -> Unit
) {
    var type by remember(route.id, route.routeType) { mutableStateOf(route.routeType) }
    var warehouse by remember(route.id, route.warehouse) { mutableStateOf(route.warehouse) }
    var orders by remember(route.id, route.reportedOrderCount) { mutableStateOf(route.reportedOrderCount?.toString().orEmpty()) }
    var externalId by remember(route.id, route.externalRouteId) { mutableStateOf(route.externalRouteId.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить трассу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Тип трассы", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteType.entries.forEach { item ->
                        FilterChip(selected = type == item, onClick = { type = item }, label = { Text(routeTypeLabel(item)) }, modifier = Modifier.weight(1f))
                    }
                }
                Text("Склад", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Warehouse.entries.forEach { item ->
                        FilterChip(selected = warehouse == item, onClick = { warehouse = item }, label = { Text(warehouseLabel(item)) })
                    }
                }
                OutlinedTextField(orders, { orders = it.filter(Char::isDigit).take(4) }, label = { Text("Заказов в сообщении") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(externalId, { externalId = it.take(64) }, label = { Text("ID трассы") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("После изменения типа Region/OT/Express число колечек и заработок пересчитываются автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(type, warehouse, orders.toIntOrNull(), externalId.trim().ifBlank { null }) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun editableKcToHellers(value: String): Long? = runCatching {
    val normalized = value.trim().replace(" ", "").replace(',', '.')
    if (normalized.isBlank()) return null
    java.math.BigDecimal(normalized).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
}.getOrNull()

private fun sourceLabel(source: DataSource): String = when (source) {
    DataSource.OCR -> "OCR"
    DataSource.MANUAL -> "ручной ввод"
    DataSource.AUTO_CALC -> "автоматический расчёт"
    DataSource.IMPORT -> "импорт"
    DataSource.USER_CORRECTION -> "исправление"
}

private fun financialTypeAccusative(type: FinancialType): String = when (type) {
    FinancialType.BONUS -> "бонус"
    FinancialType.COMPENSATION -> "компенсацию"
    FinancialType.PENALTY -> "штраф"
}

private fun formatSnapshotTime(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))

private fun warehouseLabel(warehouse: Warehouse) = when (warehouse) {
    Warehouse.LIBOC -> "Liboc"
    Warehouse.CHRASTANY -> "CH"
    Warehouse.HORNI_POCERNICE -> "HP"
}

private data class FinancialImportDraft(
    val type: FinancialType,
    val amount: String,
    val date: String,
    val description: String
)

private fun List<FinancialImportDraft>.updatedFinancialImport(index: Int, value: FinancialImportDraft): List<FinancialImportDraft> =
    toMutableList().also { it[index] = value }

private fun financialTypeLabelShort(type: FinancialType): String = when (type) {
    FinancialType.BONUS -> "Бонус"
    FinancialType.COMPENSATION -> "Компенс."
    FinancialType.PENALTY -> "Штраф"
}

private fun normalizeFinanceDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('.', '/', '-').mapNotNull { it.trim().toIntOrNull() }
    if (parts.size < 2) return null
    val year = when {
        parts.size >= 3 && parts[2] >= 1000 -> parts[2]
        parts.size >= 3 -> 2000 + parts[2]
        else -> LocalDate.now().year
    }
    return runCatching { LocalDate.of(year, parts[1], parts[0]).toString() }.getOrNull()
}

@Composable
private fun OdometerDialog(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val parsed = value.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Укажи текущее значение одометра. Оно используется только для расчёта фактического пробега.")
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValue(it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(10)) },
                    label = { Text("Показание, км") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(enabled = parsed != null && parsed >= 0, onClick = { onConfirm(parsed!!) }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MileageInputDialog(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    val parsed = value.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { onValue(it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(8)) }, label = { Text("Километраж трассы") }, suffix = { Text("км") }, singleLine = true) },
        confirmButton = { Button(enabled = parsed != null && parsed >= 0, onClick = { onSave(parsed!!) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MileageRoutesScreen(repo: CourierRepository, developerUnlocked: Boolean, onBack: () -> Unit, onRequireDeveloper: () -> Unit) {
    val context = LocalContext.current
    val store = remember { KurierXFeatureStore(context) }
    val routes by repo.dao.observeAllRoutes().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<RouteEntity?>(null) }
    var input by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AppBackHeader("Километраж трасс", "Каждая трасса хранит собственный пробег", onBack) }
        if (routes.isEmpty()) item { Text("Трасс пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(routes, key = { it.id }) { route ->
            val km = remember(revision, route.id) { store.routeKm(route.id) }
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${routeTypeLabel(route.routeType)} · #${route.id}", fontWeight = FontWeight.SemiBold)
                        Text(km?.let { "%.1f км".format(it) } ?: "не указан", color = if (km == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    }
                    Text("${formatDate(route.routeDate)} · ${warehouseLabel(route.warehouse)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            if (km == null || developerUnlocked) {
                                selected = route
                                input = km?.let { "%.1f".format(Locale.US, it) }.orEmpty()
                            } else onRequireDeveloper()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (km == null) "Добавить км" else if (developerUnlocked) "Изменить км" else "Изменение только через Developer Mode") }
                }
            }
        }
    }

    selected?.let { route ->
        MileageInputDialog(
            title = "Трасса #${route.id}",
            value = input,
            onValue = { input = it },
            onDismiss = { selected = null },
            onSave = { km -> store.setRouteKm(route.id, km); revision++; selected = null }
        )
    }
}

private val tutorialSteps = listOf(
    "Главная" to "Здесь начинается и закрывается смена, виден текущий заработок и список закрытых трасс.",
    "Зарплата" to "KurierX складывает доход по трассам, бонусы и компенсации, вычитает штрафы, дизель и авансы.",
    "Трассы" to "Нажми «Добавить закрытую трассу», проверь OCR, выбери тип трассы и склад. После сохранения можно добавить клиентов и километраж.",
    "Заказы и точка отсчёта" to "Первый подтверждённый скрин накопительной статистики становится точкой отсчёта. Например 10 000 → 10 127 означает 127 новых заказов.",
    "OCR" to "Сканер ничего финансового не сохраняет автоматически. После распознавания проверь карточки и только потом нажми «Добавить / Подтвердить».",
    "Штрафы и бонусы" to "Можно выбрать несколько полных скриншотов сразу. Подозрительные дубликаты приложение покажет перед сохранением.",
    "Километраж" to "У каждой трассы свой километраж. После первого сохранения обычный режим только показывает значение; изменение закрытой трассы доступно через Developer Mode.",
    "Спидометр" to "До очереди можно внести утреннее показание. При входе в очередь и закрытии смены приложение отдельно запрашивает одометр.",
    "Дизель" to "Фактический пробег делится на трассы, дом↔работа и километраж вне трасс. Литры = км × расход / 100, стоимость = литры × цена дизеля.",
    "Статистика и настройки" to "Статистика, зарплата, дизель, клиенты и прочие разделы находятся во вкладке «Ещё». Домашний адрес и расход автомобиля — в Настройках.",
    "Developer Mode" to "Расширенный режим защищён PIN/биометрией и нужен для критических изменений: точки отсчёта, тарифов, закрытых трасс и служебных параметров."
)

@Composable
private fun KurierXTutorial(onFinish: () -> Unit, onSkip: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val step = tutorialSteps[page]
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Обучение ${page + 1}/${tutorialSteps.size} · ${step.first}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { (page + 1f) / tutorialSteps.size }, modifier = Modifier.fillMaxWidth())
                Text(step.second, style = MaterialTheme.typography.bodyLarge)
                Text("Следуй подсказкам по разделам — обучение можно открыть повторно через «Ещё».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { if (page == tutorialSteps.lastIndex) onFinish() else page++ }) {
                Text(if (page == tutorialSteps.lastIndex) "Готово" else "Далее")
            }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Пропустить обучение") } }
    )
}

private data class GithubReleaseInfo(val version: String, val apkUrl: String, val name: String)

private suspend fun fetchGithubRelease(apiUrl: String): GithubReleaseInfo = withContext(Dispatchers.IO) {
    require(apiUrl.startsWith("https://")) { "Укажи HTTPS GitHub API URL в Developer Mode" }
    val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "KurierX-Android")
    }
    try {
        val code = connection.responseCode
        require(code in 200..299) { "GitHub вернул HTTP $code" }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        val assets = json.optJSONArray("assets") ?: error("В релизе нет assets")
        var apkUrl = ""
        var apkName = "KurierX.apk"
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkName = name
                break
            }
        }
        require(tag.isNotBlank()) { "Не удалось определить версию релиза" }
        require(apkUrl.startsWith("https://")) { "В latest release не найден APK" }
        GithubReleaseInfo(tag.removePrefix("v"), apkUrl, apkName)
    } finally { connection.disconnect() }
}

private fun versionIsNewer(remote: String, local: String): Boolean {
    fun parts(v: String) = v.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
    val r = parts(remote); val l = parts(local)
    val n = maxOf(r.size, l.size)
    return (0 until n).firstNotNullOfOrNull { i ->
        val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
        when { rv > lv -> true; rv < lv -> false; else -> null }
    } ?: false
}

private suspend fun downloadReleaseApk(context: android.content.Context, release: GithubReleaseInfo): File = withContext(Dispatchers.IO) {
    val target = File(context.cacheDir, "kurierx_update.apk")
    val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "KurierX-Android")
    }
    try {
        require(connection.responseCode in 200..299) { "Не удалось скачать APK: HTTP ${connection.responseCode}" }
        connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        require(target.length() > 100_000) { "Скачанный APK выглядит повреждённым" }
        target
    } finally { connection.disconnect() }
}

private fun launchApkInstaller(context: android.content.Context, apk: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun String.moneyText(): String = filter { it.isDigit() || it == ',' || it == '.' }.take(10)
private fun moneyTextToHellers(value: String): Long = ((value.replace(',', '.').toDoubleOrNull() ?: 0.0) * 100.0).toLong().coerceAtLeast(0L)

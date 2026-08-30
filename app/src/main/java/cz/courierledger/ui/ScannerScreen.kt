package cz.courierledger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cz.courierledger.db.*
import cz.courierledger.domain.CourierRepository
import cz.courierledger.ocr.*
import cz.courierledger.ruian.RuianStreetIndex
import cz.courierledger.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

private enum class ScanMode(val title: String, val subtitle: String) {
    ROUTE("Трасса", "Фото сообщения после завершения трассы"),
    CUSTOMERS("Заказники", "Одна или несколько фотографий списка клиентов"),
    STATISTICS("Статистика курьера", "Снимок накопительной статистики"),
    FINANCE("Бонусы / штрафы", "Бонус / компенсация или штраф")
}

private data class StoredPhoto(val uri: Uri, val localPath: String, val sha256: String)

private data class CustomerDraft(
    val firstName: String = "",
    val lastName: String = "",
    val address: String = "",
    val packages: String = "0",
    val tipKc: String = "0"
)

@Composable
fun ScannerScreen(repo: CourierRepository, onRouteSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { OcrEngine(context) }
    val streetIndex = remember { RuianStreetIndex(context) }

    var mode by remember { mutableStateOf(ScanMode.ROUTE) }
    var rawText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Выберите тип сканирования") }
    var isWorking by remember { mutableStateOf(false) }
    var lastPhotoId by remember { mutableStateOf<Long?>(null) }
    var targetRouteId by remember { mutableStateOf<Long?>(null) }
    var customerParses by remember { mutableStateOf<List<CustomerParse>>(emptyList()) }
    var financeParses by remember { mutableStateOf<List<FinancialRowParse>>(emptyList()) }
    var statisticsParse by remember { mutableStateOf<StatisticsParse?>(null) }
    var showRawCustomerOcr by remember { mutableStateOf(false) }
    var showRawStatisticsOcr by remember { mutableStateOf(false) }

    var routeType by remember { mutableStateOf(RouteType.OT) }
    var routeOrders by remember { mutableStateOf("") }
    var routeExternalId by remember { mutableStateOf("") }
    var warehouse by remember { mutableStateOf(AppSettings(context).defaultWarehouse) }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }

    fun applyRouteParse(text: String, confidence: Double) {
        val parsed = OcrParsers.route(text, confidence)
        parsed.orders?.let { routeOrders = it.toString() }
        parsed.routeId?.let { routeExternalId = it }
        parsed.warehouse?.let { warehouse = it }
    }

    fun processStored(stored: StoredPhoto, appendForCustomers: Boolean) {
        scope.launch {
            isWorking = true
            status = "Распознаю текст…"
            runCatching {
                val existing = withContext(Dispatchers.IO) { repo.dao.photoByHash(stored.sha256) }
                val reusedPhoto = existing != null
                val photoId = existing?.id ?: withContext(Dispatchers.IO) {
                    repo.dao.insertSourcePhoto(
                        SourcePhotoEntity(
                            uri = stored.uri.toString(),
                            localPath = stored.localPath,
                            sha256 = stored.sha256,
                            createdAt = System.currentTimeMillis(),
                            kind = mode.name
                        )
                    )
                }
                lastPhotoId = photoId

                val result = engine.recognize(stored.uri)
                rawText = if (appendForCustomers && rawText.isNotBlank()) rawText + "\n\n" + result.text else result.text
                if (mode == ScanMode.CUSTOMERS) {
                    val found = OcrParsers.customers(result) { street -> streetIndex.containsStreet(street) }
                    customerParses = mergeCustomerParses(if (appendForCustomers) customerParses else emptyList(), found)
                }
                if (mode == ScanMode.FINANCE) financeParses = OcrParsers.financialRows(result)
                if (mode == ScanMode.STATISTICS) {
                    statisticsParse = OcrParsers.statistics(result)
                    showRawStatisticsOcr = false
                }

                withContext(Dispatchers.IO) {
                    repo.dao.insertOcrResult(
                        OcrResultEntity(
                            photoId = photoId,
                            kind = mode.name,
                            rawText = result.text,
                            confidence = result.confidence,
                            parsedJson = "{}"
                        )
                    )
                }

                if (mode == ScanMode.ROUTE) applyRouteParse(rawText, result.confidence)
                status = if (result.text.isBlank()) {
                    "Текст не найден. Можно выбрать другое фото или заполнить данные вручную."
                } else if (reusedPhoto) {
                    "Фото уже было импортировано, но OCR выполнен заново. Можно добавить недостающие записи повторно после проверки."
                } else {
                    "OCR готов · уверенность ${(result.confidence * 100).toInt()}%"
                }
            }.onFailure { error ->
                status = "Ошибка: ${error.message ?: error::class.java.simpleName}"
            }
            isWorking = false
        }
    }

    fun processIncoming(uri: Uri, cameraPath: String? = null, appendForCustomers: Boolean = false) {
        scope.launch {
            isWorking = true
            status = "Сохраняю оригинал…"
            runCatching {
                val stored = withContext(Dispatchers.IO) { persistPhoto(context, uri, cameraPath) }
                processStored(stored, appendForCustomers)
            }.onFailure { error ->
                status = "Не удалось открыть изображение: ${error.message}"
                isWorking = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        val path = pendingCameraPath
        if (success && uri != null) {
            processIncoming(uri, path, appendForCustomers = mode == ScanMode.CUSTOMERS && rawText.isNotBlank())
        } else {
            status = "Съёмка отменена"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = pendingCameraUri
            if (uri != null) cameraLauncher.launch(uri)
        } else {
            status = "Без разрешения на камеру приложение не может сделать снимок. Галерея работает без него."
        }
    }

    fun launchCameraSafely() {
        runCatching {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            pendingCameraUri = uri
            pendingCameraPath = file.absolutePath

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(uri)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }.onFailure { error -> status = "Не удалось запустить камеру: ${error.message}" }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processIncoming(it) }
    }

    val multiGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            rawText = ""
            customerParses = emptyList()
            var recognized = 0
            var reused = 0
            var failed = 0
            for ((index, uri) in uris.withIndex()) {
                status = "Фото ${index + 1}/${uris.size}…"
                runCatching {
                    val stored = withContext(Dispatchers.IO) { persistPhoto(context, uri, null) }
                    val existing = withContext(Dispatchers.IO) { repo.dao.photoByHash(stored.sha256) }
                    val photoId = if (existing != null) {
                        reused++
                        existing.id
                    } else {
                        recognized++
                        withContext(Dispatchers.IO) {
                            repo.dao.insertSourcePhoto(SourcePhotoEntity(uri = stored.uri.toString(), localPath = stored.localPath, sha256 = stored.sha256, createdAt = System.currentTimeMillis(), kind = mode.name))
                        }
                    }
                    lastPhotoId = photoId
                    // Re-run OCR even for an already known image: v0.6 needs ML Kit line coordinates
                    // to pair Dýško/Tašky labels with values in the right column. Old cached OCR stored only plain text.
                    val result = engine.recognize(stored.uri)
                    if (existing == null) {
                        withContext(Dispatchers.IO) {
                            repo.dao.insertOcrResult(OcrResultEntity(photoId = photoId, kind = mode.name, rawText = result.text, confidence = result.confidence, parsedJson = "{}"))
                        }
                    }
                    if (result.text.isNotBlank()) rawText += (if (rawText.isBlank()) "" else "\n\n") + result.text
                    customerParses = mergeCustomerParses(customerParses, OcrParsers.customers(result) { street -> streetIndex.containsStreet(street) })
                }.onFailure { failed++ }
            }
            isWorking = false
            val parsedCount = customerParses.size
            status = when {
                rawText.isBlank() -> "Из выбранных фото не удалось получить текст. Новых: $recognized, повторных: $reused, ошибок: $failed."
                parsedCount == 0 -> "Текст получен из ${uris.size - failed} фото, но клиенты не разделились автоматически. Можно исправить текст или добавить клиента вручную."
                else -> "Фото обработано: ${uris.size - failed} · найдено клиентов: $parsedCount${if (reused > 0) " · повторных фото: $reused" else ""}. Проверь данные."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Сканер", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Каждый тип экрана распознаётся отдельно — так надёжнее.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        ScanMode.entries.forEach { item ->
            ElevatedCard(
                onClick = {
                    mode = item
                    rawText = ""
                    customerParses = emptyList()
                    financeParses = emptyList()
                    statisticsParse = null
                    showRawCustomerOcr = false
                    showRawStatisticsOcr = false
                    status = item.subtitle
                    if (item != ScanMode.ROUTE) {
                        routeOrders = ""
                        routeExternalId = ""
                    }
                    if (item == ScanMode.CUSTOMERS) {
                        scope.launch {
                            val active = repo.dao.activeShift()
                            targetRouteId = active?.let { repo.dao.routesForShift(it.id).lastOrNull()?.id }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (mode == item) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchCameraSafely() }, enabled = !isWorking, modifier = Modifier.weight(1f)) { Text("Камера") }
            OutlinedButton(
                onClick = {
                    if (mode == ScanMode.CUSTOMERS) multiGalleryLauncher.launch("image/*")
                    else galleryLauncher.launch("image/*")
                },
                enabled = !isWorking,
                modifier = Modifier.weight(1f)
            ) { Text(if (mode == ScanMode.CUSTOMERS) "Фото" else "Галерея") }
        }

        if (isWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (rawText.isNotBlank()) {
            val rawVisible = when (mode) {
                ScanMode.CUSTOMERS -> showRawCustomerOcr
                ScanMode.STATISTICS -> showRawStatisticsOcr
                else -> true
            }
            if (mode == ScanMode.CUSTOMERS || mode == ScanMode.STATISTICS) {
                TextButton(onClick = {
                    if (mode == ScanMode.CUSTOMERS) showRawCustomerOcr = !showRawCustomerOcr
                    else showRawStatisticsOcr = !showRawStatisticsOcr
                }) { Text(if (rawVisible) "Скрыть исходный OCR" else "Показать исходный OCR") }
            }
            if (rawVisible) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        if (mode == ScanMode.ROUTE) applyRouteParse(it, 0.8)
                        if (mode == ScanMode.CUSTOMERS) customerParses = OcrParsers.customers(it, .80)
                        if (mode == ScanMode.STATISTICS) statisticsParse = OcrParsers.statistics(it, .80)
                    },
                    label = { Text("Исходный OCR-текст") },
                    supportingText = { if (mode == ScanMode.CUSTOMERS || mode == ScanMode.STATISTICS) Text("Диагностический OCR. Основные значения показаны ниже.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6
                )
            }
        }

        when (mode) {
            ScanMode.ROUTE -> RouteConfirmation(
                repo = repo,
                rawText = rawText,
                routeType = routeType,
                onRouteType = { routeType = it },
                orders = routeOrders,
                onOrders = { routeOrders = it.filter(Char::isDigit).take(3) },
                externalId = routeExternalId,
                onExternalId = { routeExternalId = it },
                warehouse = warehouse,
                onWarehouse = { warehouse = it },
                sourcePhotoId = lastPhotoId,
                onStatus = { status = it },
                onSaved = { id -> targetRouteId = id; onRouteSaved() }
            )

            ScanMode.CUSTOMERS -> CustomersConfirmation(repo, rawText, customerParses, targetRouteId, { targetRouteId = it }, lastPhotoId) { status = it }
            ScanMode.STATISTICS -> StatisticsConfirmation(repo, rawText, statisticsParse, lastPhotoId) { status = it }
            ScanMode.FINANCE -> FinanceConfirmation(repo, rawText, financeParses, lastPhotoId) { status = it }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RouteConfirmation(
    repo: CourierRepository,
    rawText: String,
    routeType: RouteType,
    onRouteType: (RouteType) -> Unit,
    orders: String,
    onOrders: (String) -> Unit,
    externalId: String,
    onExternalId: (String) -> Unit,
    warehouse: Warehouse,
    onWarehouse: (Warehouse) -> Unit,
    sourcePhotoId: Long?,
    onStatus: (String) -> Unit,
    onSaved: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    Text("Проверка трассы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("Тип трассы всегда выбирается вручную.", color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RouteType.entries.forEach { type ->
            FilterChip(selected = routeType == type, onClick = { onRouteType(type) }, label = { Text(routeTypeLabel(type)) })
        }
    }

    OutlinedTextField(value = orders, onValueChange = onOrders, label = { Text("Количество заказов") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = externalId, onValueChange = onExternalId, label = { Text("ID трассы, если есть") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

    Text("Склад", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Warehouse.entries.forEach { item ->
            FilterChip(selected = warehouse == item, onClick = { onWarehouse(item) }, label = { Text(warehouseLabelScanner(item)) })
        }
    }

    Button(
        enabled = rawText.isNotBlank() || orders.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                runCatching {
                    val shift = repo.dao.activeShift() ?: error("Сначала нажмите Přihlásit se do fronty на главной")
                    repo.addRoute(shift.id, routeType, warehouse, orders.toIntOrNull(), externalId.trim().ifBlank { null }, sourcePhotoId)
                }.onSuccess { id ->
                    onStatus("Трасса #$id сохранена")
                    onSaved(id)
                }.onFailure { onStatus("Не удалось сохранить трассу: ${it.message}") }
            }
        }
    ) { Text("Подтвердить и закрыть трассу") }
}

@Composable
private fun CustomersConfirmation(
    repo: CourierRepository,
    rawText: String,
    parsedCustomers: List<CustomerParse>,
    routeId: Long?,
    onRouteId: (Long?) -> Unit,
    sourcePhotoId: Long?,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val streetIndex = remember { RuianStreetIndex(context) }
    val parsed = remember(rawText, parsedCustomers) { if (parsedCustomers.isNotEmpty()) parsedCustomers else OcrParsers.customers(rawText, .85) }
    var drafts by remember(rawText, parsedCustomers) {
        mutableStateOf(parsed.map { customer ->
            CustomerDraft(
                firstName = customer.firstName,
                lastName = customer.lastName,
                address = customer.address,
                packages = customer.packages.toString(),
                tipKc = hellersToEditableKc(customer.tipHellers)
            )
        })
    }
    var saving by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(routeId) {
        if (routeId == null) {
            repo.latestRouteForCurrentWork()?.let(onRouteId)
        }
    }

    Text("Проверка заказников", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        if (routeId == null) "Не найдена закрытая трасса — укажи её ID вручную." else "Привязано к трассе #$routeId · клиентов: ${drafts.size}",
        color = if (routeId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = routeId?.toString().orEmpty(),
        onValueChange = { onRouteId(it.filter(Char::isDigit).toLongOrNull()) },
        label = { Text("ID закрытой трассы") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    if (rawText.isBlank()) {
        Text("Добавь одну или несколько фотографий. После OCR каждый клиент появится отдельной редактируемой карточкой.")
    } else if (drafts.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "OCR не смог уверенно разделить текст на клиентов. Распознанный текст выше можно исправить вручную или добавить клиента кнопкой ниже.",
                modifier = Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    drafts.forEachIndexed { index, customer ->
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Клиент ${index + 1}", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                drafts = drafts.toMutableList().also { list ->
                                    val item = list.removeAt(index)
                                    list.add(index - 1, item)
                                }
                                saveFeedback = null
                            }
                        ) { Text("↑") }
                        TextButton(
                            enabled = index < drafts.lastIndex,
                            onClick = {
                                drafts = drafts.toMutableList().also { list ->
                                    val item = list.removeAt(index)
                                    list.add(index + 1, item)
                                }
                                saveFeedback = null
                            }
                        ) { Text("↓") }
                        TextButton(onClick = {
                            drafts = drafts.toMutableList().also { it.removeAt(index) }
                            saveFeedback = null
                        }) { Text("Удалить") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customer.firstName,
                        onValueChange = { value -> drafts = drafts.updated(index, customer.copy(firstName = value)) },
                        label = { Text("Имя") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customer.lastName,
                        onValueChange = { value -> drafts = drafts.updated(index, customer.copy(lastName = value)) },
                        label = { Text("Фамилия") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = customer.address,
                    onValueChange = { value -> drafts = drafts.updated(index, customer.copy(address = value)) },
                    label = { Text("Адрес") },
                    supportingText = {
                        val street = RuianStreetIndex.streetPartFromAddress(customer.address)
                        when (streetIndex.containsStreet(street)) {
                            true -> Text("✓ Улица найдена в официальном RÚIAN")
                            false -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("▲", color = androidx.compose.ui.graphics.Color(0xFF8B1E3F), fontWeight = FontWeight.Bold)
                                Text("Улица не найдена в RÚIAN — проверь OCR")
                            }
                            null -> Text("RÚIAN ещё не загружен · Ещё → Настройки → обновить каталог")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customer.packages,
                        onValueChange = { value -> drafts = drafts.updated(index, customer.copy(packages = value.filter(Char::isDigit).take(3))) },
                        label = { Text("Пакеты") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customer.tipKc,
                        onValueChange = { value ->
                            val cleaned = value.filter { it.isDigit() || it == ',' || it == '.' }.take(8)
                            drafts = drafts.updated(index, customer.copy(tipKc = cleaned))
                        },
                        label = { Text("Чаевые, Kč") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }
    }

    OutlinedButton(
        onClick = {
            drafts = drafts + CustomerDraft()
            saveFeedback = null
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("+ Добавить клиента вручную") }

    val validDrafts = drafts.filter { it.address.isNotBlank() }
    val missingAddresses = drafts.count { it.address.isBlank() }
    if (drafts.isNotEmpty() && missingAddresses > 0) {
        Text("Без адреса: $missingAddresses. Такие карточки не будут сохранены, пока адрес не заполнен.", color = MaterialTheme.colorScheme.tertiary)
    }
    Button(
        enabled = !saving,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                saving = true
                saveFeedback = "Сохраняю клиентов…"
                val result = runCatching {
                    val id = routeId ?: repo.latestRouteForCurrentWork() ?: error("Не удалось определить трассу. Сначала создай трассу, затем открой «Заказники».")
                    onRouteId(id)
                    repo.dao.route(id) ?: error("Трасса #$id не найдена в базе.")
                    if (drafts.isEmpty()) error("Нет клиентов для сохранения. Добавь клиента вручную или загрузи фото.")
                    if (validDrafts.isEmpty()) error("Ни у одного клиента не заполнен адрес. Заполни хотя бы один адрес.")
                    if (missingAddresses > 0) error("У $missingAddresses клиент(ов) не заполнен адрес. Заполни адрес или удали пустые карточки.")

                    validDrafts.forEach { customer ->
                        repo.addCustomerOrder(
                            routeId = id,
                            first = customer.firstName.trim(),
                            last = customer.lastName.trim(),
                            address = customer.address.trim(),
                            packages = customer.packages.toIntOrNull() ?: 0,
                            tipHellers = editableKcToHellers(customer.tipKc),
                            photoId = sourcePhotoId
                        )
                    }
                    val merged = repo.mergeExactAddresses(id)
                    val factual = repo.dao.factualOrderCount(id)
                    val tips = repo.dao.tipsForRoute(id)
                    val route = repo.dao.route(id) ?: error("Трасса #$id исчезла после сохранения.")
                    val money = repo.routeEarnings(route)
                    "✓ Сохранено ${validDrafts.size} клиентов · фактических заказов: $factual · объединений: $merged · чаевые: ${hellersToEditableKc(tips)} Kč · трасса: ${money.gross.czkText()}"
                }
                result.onSuccess { message ->
                    saveFeedback = message
                    onStatus(message)
                }.onFailure { error ->
                    val message = "Не сохранено: ${error.message ?: error::class.java.simpleName}"
                    saveFeedback = message
                    onStatus(message)
                }
                saving = false
            }
        }
    ) { Text(if (saving) "Сохраняю…" else "Сохранить заказников и пересчитать трассу") }

    saveFeedback?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (message.startsWith("✓")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                message,
                modifier = Modifier.padding(12.dp),
                color = if (message.startsWith("✓")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

private fun mergeCustomerParses(existing: List<CustomerParse>, incoming: List<CustomerParse>): List<CustomerParse> =
    (existing + incoming).distinctBy { customer ->
        Triple(
            (customer.firstName + " " + customer.lastName).trim().lowercase(),
            customer.normalizedAddress,
            customer.tipHellers to customer.packages
        )
    }

private fun List<CustomerDraft>.updated(index: Int, value: CustomerDraft): List<CustomerDraft> =
    toMutableList().also { it[index] = value }

private fun hellersToEditableKc(hellers: Long): String =
    if (hellers % 100L == 0L) (hellers / 100L).toString() else "%.2f".format(java.util.Locale.US, hellers / 100.0)

private fun editableKcToHellers(value: String): Long {
    val normalized = value.trim().replace(',', '.')
    return normalized.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.longValueExact() ?: 0L
}

@Composable
private fun StatisticsConfirmation(repo: CourierRepository, rawText: String, geometryParsed: StatisticsParse?, photoId: Long?, onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val parsed = geometryParsed ?: remember(rawText) { OcrParsers.statistics(rawText, .9) }
    var orders by remember(rawText, parsed.cumulativeOrders) { mutableStateOf(parsed.cumulativeOrders?.toString().orEmpty()) }
    var tips by remember(rawText, parsed.cumulativeTipsHellers) { mutableStateOf(parsed.cumulativeTipsHellers?.let(::hellersToEditableKc).orEmpty()) }
    var comparisonText by remember { mutableStateOf<String?>(null) }
    var internalStats by remember { mutableStateOf<cz.courierledger.domain.PeriodStatistics?>(null) }
    LaunchedEffect(rawText) { internalStats = runCatching { withContext(Dispatchers.IO) { repo.periodStatistics(null, null) } }.getOrNull() }

    Text("Накопительная статистика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(orders, { orders = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), label = { Text("Всего заказов") }, singleLine = true)
        OutlinedTextField(tips, { tips = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(12) }, Modifier.weight(1f), label = { Text("Чаевые, Kč") }, singleLine = true)
    }
    val missing = buildList { if (parsed.cumulativeOrders == null) add("заказы"); if (parsed.cumulativeTipsHellers == null) add("чаевые") }
    Text(if (missing.isEmpty()) "✓ Заказы и чаевые распознаны по расположению на экране." else "▲ Проверь: ${missing.joinToString()} — OCR не уверен.", color = if (missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
    internalStats?.let { local ->
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ориентир KurierX", fontWeight = FontWeight.SemiBold)
            Text("В приложении: ${local.factualOrders} заказов · ${hellersToEditableKc(local.tipsHellers)} Kč чаевых", style = MaterialTheme.typography.bodySmall)
            if (orders.isNotBlank() || tips.isNotBlank()) Text("OCR: ${orders.ifBlank { "—" }} заказов · ${tips.ifBlank { "—" }} Kč чаевых", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Официальный счётчик может включать данные до начала использования KurierX — это ориентир, а не автокоррекция.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    Button(enabled = orders.toIntOrNull() != null, modifier = Modifier.fillMaxWidth(), onClick = {
        val value = orders.toIntOrNull() ?: return@Button
        val tipValue = tips.takeIf { it.isNotBlank() }?.let(::editableKcToHellers)
        scope.launch {
            runCatching { repo.saveStatisticsSnapshot(value, tipValue, rawText, photoId) }.onSuccess { c ->
                val orderPart = if (c.cumulativeDelta == null) "Для заказов нужен ещё один снимок." else if (c.matches == true) "Заказы +${c.cumulativeDelta}, по трассам ${c.routeOrdersBetween} — совпадает." else "Заказы +${c.cumulativeDelta}, по трассам ${c.routeOrdersBetween}; разница ${c.cumulativeDelta!! - (c.routeOrdersBetween ?: 0)}."
                val tipPart = if (c.cumulativeTipsDeltaHellers == null) "Для чаевых нужны два снимка с распознанными чаевыми." else if (c.tipsMatch == true) "Чаевые +${hellersToEditableKc(c.cumulativeTipsDeltaHellers)} Kč, по клиентам ${hellersToEditableKc(c.routeTipsBetweenHellers ?: 0)} Kč — совпадает." else "Чаевые +${hellersToEditableKc(c.cumulativeTipsDeltaHellers)} Kč, по клиентам ${hellersToEditableKc(c.routeTipsBetweenHellers ?: 0)} Kč; разница ${hellersToEditableKc(c.cumulativeTipsDeltaHellers - (c.routeTipsBetweenHellers ?: 0))} Kč."
                comparisonText = (if (c.matches != false && c.tipsMatch != false) "✓ " else "▲ ") + orderPart + " " + tipPart
                onStatus("Снимок статистики сохранён")
            }.onFailure { onStatus("Ошибка: ${it.message}") }
        }
    }) { Text("Сохранить и сверить") }
    comparisonText?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary) }
}

@Composable
private fun FinanceConfirmation(
    repo: CourierRepository,
    rawText: String,
    parsedRows: List<FinancialRowParse>,
    sourcePhotoId: Long?,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val fallback = remember(rawText) { OcrParsers.financial(rawText, .9) }
    val initialRows = remember(rawText, parsedRows) {
        if (parsedRows.isNotEmpty()) parsedRows else listOf(
            FinancialRowParse(fallback.type, fallback.amountHellers, fallback.date, fallback.description, fallback.confidence)
        )
    }
    var drafts by remember(rawText, parsedRows) {
        mutableStateOf(initialRows.map { row ->
            FinanceDraft(
                type = row.type ?: FinancialType.BONUS,
                amount = row.amountHellers?.let(::hellersToEditableKc).orEmpty(),
                date = normalizeOcrDate(row.date) ?: LocalDate.now().toString(),
                description = row.description
            )
        })
    }
    var saving by remember { mutableStateOf(false) }

    Text("Проверка бонусов / штрафов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("Экран Rohlík разбирается построчно: Datum → Částka → Položka → Poznámka. Перед сохранением всё можно исправить.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (drafts.isEmpty()) Text("Записи не найдены. Попробуй более ровный скриншот или добавь запись вручную.", color = MaterialTheme.colorScheme.error)

    drafts.forEachIndexed { index, draft ->
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Запись ${index + 1}", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) } }) { Text("Удалить") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(FinancialType.BONUS, FinancialType.PENALTY).forEach { type ->
                        FilterChip(
                            selected = (if (draft.type == FinancialType.COMPENSATION) FinancialType.BONUS else draft.type) == type,
                            onClick = { drafts = drafts.updatedFinance(index, draft.copy(type = type)) },
                            label = { Text(financeTypeShort(type)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.amount, { value -> drafts = drafts.updatedFinance(index, draft.copy(amount=value.filter { it.isDigit() || it==',' || it=='.' }.take(12))) }, label={Text("Сумма, Kč")}, singleLine=true, modifier=Modifier.weight(1f))
                    OutlinedTextField(draft.date, { value -> drafts = drafts.updatedFinance(index, draft.copy(date=value.take(10))) }, label={Text("Дата")}, singleLine=true, modifier=Modifier.weight(1f))
                }
                OutlinedTextField(draft.description, { value -> drafts = drafts.updatedFinance(index, draft.copy(description=value.take(500))) }, label={Text("Položka / Poznámka")}, minLines=2, modifier=Modifier.fillMaxWidth())
                if (draft.type == FinancialType.PENALTY && moneyInputToHellers(draft.amount) == 0L) {
                    Text("0 Kč сохранится как информационный штраф.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }

    OutlinedButton(onClick={ drafts = drafts + FinanceDraft() }, modifier=Modifier.fillMaxWidth()) { Text("+ Добавить запись вручную") }
    Button(
        enabled = drafts.isNotEmpty() && !saving,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                saving = true
                runCatching {
                    drafts.forEach { draft ->
                        val amount = moneyInputToHellers(draft.amount) ?: error("Проверь сумму")
                        val date = runCatching { LocalDate.parse(draft.date) }.getOrElse { error("Проверь дату ${draft.date}") }
                        repo.addFinancialEntry(draft.type, amount, date, draft.description, DataSource.OCR)
                    }
                }.onSuccess { onStatus("Сохранено финансовых записей: ${drafts.size}") }
                    .onFailure { onStatus("Ошибка: ${it.message}") }
                saving = false
            }
        }
    ) { Text(if (saving) "Сохраняю…" else "Сохранить все записи") }
}

private data class FinanceDraft(
    val type: FinancialType = FinancialType.BONUS,
    val amount: String = "",
    val date: String = LocalDate.now().toString(),
    val description: String = ""
)

private fun List<FinanceDraft>.updatedFinance(index: Int, value: FinanceDraft): List<FinanceDraft> =
    toMutableList().also { it[index] = value }

private fun financeTypeShort(type: FinancialType): String = when(type) {
    FinancialType.BONUS -> "Бонус"
    FinancialType.COMPENSATION -> "Компенс."
    FinancialType.PENALTY -> "Штраф"
}

private fun moneyInputToHellers(value: String): Long? = runCatching {
    val normalized = value.trim().replace(" ", "").replace(',', '.')
    if (normalized.isBlank()) return null
    java.math.BigDecimal(normalized).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
}.getOrNull()

private fun normalizeOcrDate(raw: String?): String? {
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

private suspend fun persistPhoto(context: Context, inputUri: Uri, existingPath: String?): StoredPhoto {
    if (existingPath != null) {
        val file = File(existingPath)
        require(file.exists() && file.length() > 0) { "Камера не сохранила изображение" }
        return StoredPhoto(inputUri, file.absolutePath, sha256(file))
    }

    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "import_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
    context.contentResolver.openInputStream(inputUri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        ?: error("Не удалось прочитать изображение")
    require(file.length() > 0) { "Изображение пустое" }
    val stableUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return StoredPhoto(stableUri, file.absolutePath, sha256(file))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun routeTypeLabel(type: RouteType) = when (type) { RouteType.OT -> "OT"; RouteType.REGION -> "Region"; RouteType.EXPRESS -> "Express" }
private fun warehouseLabelScanner(warehouse: Warehouse) = when (warehouse) { Warehouse.LIBOC -> "Liboc"; Warehouse.CHRASTANY -> "CH"; Warehouse.HORNI_POCERNICE -> "HP" }

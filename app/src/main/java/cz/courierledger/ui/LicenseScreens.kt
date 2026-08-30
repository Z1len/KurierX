package cz.courierledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import cz.courierledger.security.*
import kotlinx.coroutines.launch

@Composable
fun KurierXLicenseGate(manager: LicenseManager, content: @Composable (LicenseState) -> Unit) {
    val state by manager.state.collectAsState()
    LaunchedEffect(Unit) { manager.start() }
    when (val s = state) {
        LicenseState.Loading -> FullCenterMessage("KurierX", "Проверяем лицензию…")
        is LicenseState.NeedsActivation -> ActivationScreen(manager, s.message)
        is LicenseState.Active -> content(s)
        LicenseState.Owner -> content(s)
        is LicenseState.Blocked -> BlockedLicenseScreen(s)
        is LicenseState.Error -> ErrorLicenseScreen(manager, s.message)
    }
}

@Composable
private fun ActivationScreen(manager: LicenseManager, initialMessage: String?) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var courierId by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember(initialMessage) { mutableStateOf(initialMessage) }
    var busy by remember { mutableStateOf(false) }
    var ownerLogin by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("KurierX", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(if (step == 1) "Регистрация устройства" else "Ключ доступа", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step == 1) {
                        OutlinedTextField(first, { first = it.take(40) }, label = { Text("Имя латиницей") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(last, { last = it.take(50) }, label = { Text("Фамилия латиницей") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(courierId, { courierId = it.take(30) }, label = { Text("Код курьера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = { error = null; step = 2 },
                            enabled = first.isNotBlank() && last.isNotBlank() && courierId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Продолжить") }
                    } else {
                        Text("${first.trim()} ${last.trim()} · #${courierId.trim()}", fontWeight = FontWeight.SemiBold)
                        Text("Устройство: ${manager.deviceLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            key, { key = it.uppercase().take(32) },
                            label = { Text("KX-XXXX-XXXX-XXXX") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                busy = true; error = null
                                scope.launch {
                                    runCatching { manager.activate(first, last, courierId, key) }
                                        .onFailure { error = it.message ?: "Не удалось активировать KurierX" }
                                    busy = false
                                }
                            },
                            enabled = key.length >= 12 && !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Активация…" else "Активировать KurierX") }
                        TextButton(onClick = { step = 1 }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Изменить данные") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { ownerLogin = true }) {
                Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Вход владельца")
            }
        }
    }

    if (ownerLogin) OwnerLoginDialog(manager, onDismiss = { ownerLogin = false })
}

@Composable
private fun OwnerLoginDialog(manager: LicenseManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("KurierX Owner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = email.isNotBlank() && password.isNotBlank() && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { manager.signInOwner(email, password) }
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: "Ошибка входа" }
                    busy = false
                }
            }) { Text(if (busy) "Вход…" else "Войти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun BlockedLicenseScreen(state: LicenseState.Blocked) {
    FullCenterMessage(
        if (state.status == "FROZEN") "Доступ приостановлен" else "Доступ заблокирован",
        state.message
    )
}

@Composable
private fun ErrorLicenseScreen(manager: LicenseManager, message: String) {
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(12.dp)); Text("KurierX", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp)); Button(onClick = { scope.launch { manager.start() } }) { Text("Повторить") }
        }
    }
}

@Composable
private fun FullCenterMessage(title: String, message: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AccountScreen(manager: LicenseManager, state: LicenseState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppBackHeader("Аккаунт", onBack = onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when (state) {
                        is LicenseState.Active -> {
                            Text("${state.profile.firstName} ${state.profile.lastName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Courier #${state.profile.courierId}")
                            Text("● Лицензия активна", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            HorizontalDivider()
                            Text(manager.deviceLabel(), fontWeight = FontWeight.SemiBold)
                            Text("Device code: ${manager.currentDeviceCode()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LicenseState.Owner -> {
                            Text("KurierX Owner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("● OWNER", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(manager.deviceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Text("Нет активной сессии")
                    }
                    OutlinedButton(onClick = { scope.launch { manager.signOutToActivation() } }, modifier = Modifier.fillMaxWidth()) { Text("Выйти из аккаунта") }
                }
            }
        }
    }
}

@Composable
fun OwnerControlScreen(manager: LicenseManager, onBack: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var users by remember { mutableStateOf<List<OwnerUserRecord>>(emptyList()) }
    var devices by remember { mutableStateOf<List<OwnerDeviceRecord>>(emptyList()) }
    var keys by remember { mutableStateOf<List<OwnerKeyRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<OwnerUserRecord?>(null) }
    var generatedKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<OwnerKeyRecord?>(null) }

    DisposableEffect(Unit) {
        val regs = mutableListOf<ListenerRegistration>()
        regs += db.collection("users").addSnapshotListener { snap, _ ->
            users = snap?.documents.orEmpty().map { d -> OwnerUserRecord(d.id, d.getString("firstName").orEmpty(), d.getString("lastName").orEmpty(), d.getString("courierId").orEmpty(), d.getString("deviceId").orEmpty(), d.getString("activationKeyId").orEmpty(), d.getString("status") ?: "?") }.sortedBy { it.firstName }
        }
        regs += db.collection("devices").addSnapshotListener { snap, _ ->
            devices = snap?.documents.orEmpty().map { d -> OwnerDeviceRecord(d.id, d.getString("uid").orEmpty(), d.getString("manufacturer").orEmpty(), d.getString("model").orEmpty(), d.getString("androidVersion").orEmpty(), d.getString("appVersion").orEmpty(), d.getString("status") ?: "?") }
        }
        regs += db.collection("activation_keys").addSnapshotListener { snap, _ ->
            keys = snap?.documents.orEmpty().map { d -> OwnerKeyRecord(d.id, d.getString("displayKey").orEmpty(), d.getString("status") ?: "?", d.getString("userId"), d.getString("deviceId")) }
        }
        onDispose { regs.forEach { it.remove() } }
    }

    val active = users.count { it.status == "ACTIVE" }
    val frozen = users.count { it.status == "FROZEN" }
    val blacklisted = users.count { it.status == "BLACKLISTED" }
    val unused = keys.count { it.status == "UNUSED" }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AppBackHeader("KurierX Control", "OWNER · Firebase", onBack) }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Пользователей ${users.size} · активных $active · заморожено $frozen · blacklist $blacklisted", fontWeight = FontWeight.SemiBold)
                    Text("Неиспользованных ключей: $unused", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { manager.generateActivationKey() }
                                .onSuccess { generatedKey = it; clipboard.setText(AnnotatedString(it)); message = "✓ Ключ создан и скопирован" }
                                .onFailure { message = it.message }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Создаю…" else "+ Создать ключ") }
                    generatedKey?.let { key ->
                        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=.45f)), color = MaterialTheme.colorScheme.primary.copy(alpha=.08f), modifier = Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(key)) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(key, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Rounded.ContentCopy, null)
                            }
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Text("Пользователи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (users.isEmpty()) item { Text("После первой активации курьер автоматически появится здесь.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(users, key = { it.uid }) { u ->
            val device = devices.firstOrNull { it.deviceId == u.deviceId }
            val key = keys.firstOrNull { it.keyId == u.activationKeyId }
            ElevatedCard(Modifier.fillMaxWidth().clickable { selected = u }, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${u.firstName} ${u.lastName}", fontWeight = FontWeight.Bold)
                        Text(u.status, color = when(u.status){"ACTIVE"->MaterialTheme.colorScheme.primary;"FROZEN"->MaterialTheme.colorScheme.tertiary;else->MaterialTheme.colorScheme.error}, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Courier #${u.courierId}")
                    Text(listOf(device?.manufacturer, device?.model).filterNotNull().joinToString(" ").ifBlank { "Устройство неизвестно" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!key?.displayKey.isNullOrBlank()) Text(key!!.displayKey, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Ключи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(keys.take(30), key = { it.keyId }) { k ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(k.displayKey.ifBlank { "••••${k.keyId.takeLast(4)}" }, fontWeight = FontWeight.SemiBold)
                        Text(k.status, style = MaterialTheme.typography.bodySmall, color = if (k.status == "UNUSED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (k.displayKey.isNotBlank()) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(k.displayKey))
                            message = "✓ Ключ скопирован"
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Скопировать ключ")
                        }
                    }
                    IconButton(
                        enabled = k.status == "UNUSED" && !busy,
                        onClick = { keyToDelete = k }
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить ключ",
                            tint = if (k.status == "UNUSED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
                        )
                    }
                }
            }
        }
    }

    selected?.let { user -> OwnerUserDialog(manager, user, devices.firstOrNull { it.deviceId == user.deviceId }, onDismiss = { selected = null }, onChanged = { selected = null }) }
}

@Composable
private fun OwnerUserDialog(manager: LicenseManager, user: OwnerUserRecord, device: OwnerDeviceRecord?, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember(user.uid) { mutableStateOf(user.firstName) }
    var last by remember(user.uid) { mutableStateOf(user.lastName) }
    var courier by remember(user.uid) { mutableStateOf(user.courierId) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${user.firstName} ${user.lastName}") },
        text = {
            Column(Modifier.heightIn(max=560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(first,{first=it.take(40)},label={Text("Имя")},singleLine=true)
                OutlinedTextField(last,{last=it.take(50)},label={Text("Фамилия")},singleLine=true)
                OutlinedTextField(courier,{courier=it.take(30)},label={Text("Courier ID")},singleLine=true)
                Text("${device?.manufacturer.orEmpty()} ${device?.model.orEmpty()} · Android ${device?.androidVersion.orEmpty()}", style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                Button(modifier=Modifier.fillMaxWidth(), onClick={scope.launch{runCatching{manager.updateUser(user.uid,first,last,courier)}.onSuccess{onChanged()}.onFailure{error=it.message}}}){Text("Сохранить данные")}
                if(user.status=="FROZEN") Button(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{runCatching{manager.setUserStatus(user,"ACTIVE")}.onSuccess{onChanged()}.onFailure{error=it.message}}}){Text("Разморозить")}
                else OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{runCatching{manager.setUserStatus(user,"FROZEN")}.onSuccess{onChanged()}.onFailure{error=it.message}}}){Text("Заморозить")}
                if(user.status=="BLACKLISTED") OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{runCatching{manager.removeFromBlacklist(user)}.onSuccess{onChanged()}.onFailure{error=it.message}}}){Text("Убрать из blacklist")}
                else OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={scope.launch{runCatching{manager.blacklist(user,"OWNER blacklist")}.onSuccess{onChanged()}.onFailure{error=it.message}}}){Text("Добавить в blacklist")}
                OutlinedButton(modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={confirmDelete=true}){Text("Удалить пользователя и ключ")}
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick=onDismiss){Text("Закрыть")} }
    )
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Удалить пользователя?")},text={Text("Профиль, устройство и использованный ключ будут удалены из Firebase. На телефоне пользователя сессия перестанет быть действительной при следующей синхронизации.")},confirmButton={Button(colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),onClick={scope.launch{runCatching{manager.deleteUser(user)}.onSuccess{confirmDelete=false;onChanged()}.onFailure{error=it.message}}}){Text("Удалить")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Отмена")}})
}

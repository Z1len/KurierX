package cz.courierledger.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

const val KURIERX_OWNER_UID = "dDUHublQoTccwtzPa1hmpyiDTd23"

data class RemoteProfile(
    val uid: String,
    val firstName: String,
    val lastName: String,
    val courierId: String,
    val deviceId: String,
    val activationKeyId: String,
    val status: String,
    val role: String,
)

sealed interface LicenseState {
    data object Loading : LicenseState
    data class NeedsActivation(val message: String? = null) : LicenseState
    data class Active(val profile: RemoteProfile) : LicenseState
    data object Owner : LicenseState
    data class Blocked(val status: String, val message: String) : LicenseState
    data class Error(val message: String) : LicenseState
}

data class OwnerUserRecord(
    val uid: String,
    val firstName: String,
    val lastName: String,
    val courierId: String,
    val deviceId: String,
    val activationKeyId: String,
    val status: String,
)

data class OwnerDeviceRecord(
    val deviceId: String,
    val uid: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val status: String,
)

data class OwnerKeyRecord(
    val keyId: String,
    val displayKey: String,
    val status: String,
    val userId: String?,
    val deviceId: String?,
)

class LicenseManager(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("kurierx_license", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<LicenseState>(LicenseState.Loading)
    val state: StateFlow<LicenseState> = _state.asStateFlow()
    private var profileListener: ListenerRegistration? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        val current = auth.currentUser
        if (current == null) {
            runCatching { Tasks.await(auth.signInAnonymously()) }
                .onSuccess { attachForCurrentUser() }
                .onFailure { _state.value = cachedStateOrError("Для первой активации нужен интернет: ${it.message}") }
        } else {
            attachForCurrentUser()
        }
    }

    private fun cachedStateOrError(error: String): LicenseState {
        val uid = auth.currentUser?.uid ?: prefs.getString("uid", null)
        val status = prefs.getString("status", null)
        if (uid != null && status == "ACTIVE") {
            return LicenseState.Active(
                RemoteProfile(
                    uid = uid,
                    firstName = prefs.getString("firstName", "").orEmpty(),
                    lastName = prefs.getString("lastName", "").orEmpty(),
                    courierId = prefs.getString("courierId", "").orEmpty(),
                    deviceId = prefs.getString("deviceId", deviceId()).orEmpty(),
                    activationKeyId = prefs.getString("activationKeyId", "").orEmpty(),
                    status = status,
                    role = "USER"
                )
            )
        }
        return LicenseState.Error(error)
    }

    private fun attachForCurrentUser() {
        profileListener?.remove()
        val user = auth.currentUser ?: run {
            _state.value = LicenseState.NeedsActivation()
            return
        }
        if (user.uid == KURIERX_OWNER_UID) {
            _state.value = LicenseState.Owner
            return
        }

        val ref = db.collection("users").document(user.uid)
        profileListener = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                _state.value = cachedStateOrError("Не удалось проверить лицензию: ${err.message}")
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                clearCachedProfile()
                _state.value = LicenseState.NeedsActivation()
                return@addSnapshotListener
            }
            val profile = RemoteProfile(
                uid = user.uid,
                firstName = snap.getString("firstName").orEmpty(),
                lastName = snap.getString("lastName").orEmpty(),
                courierId = snap.getString("courierId").orEmpty(),
                deviceId = snap.getString("deviceId").orEmpty(),
                activationKeyId = snap.getString("activationKeyId").orEmpty(),
                status = snap.getString("status") ?: "REVOKED",
                role = snap.getString("role") ?: "USER",
            )
            cache(profile)
            _state.value = when (profile.status.uppercase(Locale.ROOT)) {
                "ACTIVE" -> LicenseState.Active(profile)
                "FROZEN" -> LicenseState.Blocked("FROZEN", "Аккаунт временно заморожен. Обратитесь к владельцу KurierX.")
                "BLACKLISTED" -> LicenseState.Blocked("BLACKLISTED", "Это устройство находится в чёрном списке KurierX.")
                else -> LicenseState.Blocked(profile.status, "Лицензия больше недействительна. Обратитесь к владельцу KurierX.")
            }
        }
    }

    suspend fun activate(firstName: String, lastName: String, courierId: String, rawKey: String) = withContext(Dispatchers.IO) {
        require(firstName.matches(Regex("^[A-Za-z][A-Za-z '\\-]{1,39}$"))) { "Имя укажи латиницей" }
        require(lastName.matches(Regex("^[A-Za-z][A-Za-z '\\-]{1,49}$"))) { "Фамилию укажи латиницей" }
        require(courierId.matches(Regex("^[A-Za-z0-9_-]{2,30}$"))) { "Проверь ID курьера" }

        if (auth.currentUser == null || auth.currentUser?.uid == KURIERX_OWNER_UID) {
            auth.signOut()
            Tasks.await(auth.signInAnonymously())
        }
        val uid = auth.currentUser?.uid ?: error("Не удалось создать сессию")
        val key = normalizeKey(rawKey)
        require(key.startsWith("KX") && key.length == 14) { "Неверный формат ключа" }
        val keyId = sha256(key)
        val deviceId = deviceId()
        val userRef = db.collection("users").document(uid)
        val deviceRef = db.collection("devices").document(deviceId)
        val keyRef = db.collection("activation_keys").document(keyId)

        Tasks.await(db.runTransaction { tr ->
            val keySnap = tr.get(keyRef)
            if (!keySnap.exists()) error("Такого ключа не существует")
            if (keySnap.getString("status") != "UNUSED") error("Ключ уже использован или недействителен")

            tr.update(keyRef, mapOf(
                "status" to "USED",
                "userId" to uid,
                "deviceId" to deviceId,
                "activatedAt" to FieldValue.serverTimestamp(),
            ))
            tr.set(userRef, mapOf(
                "uid" to uid,
                "firstName" to firstName.trim(),
                "lastName" to lastName.trim(),
                "courierId" to courierId.trim(),
                "deviceId" to deviceId,
                "activationKeyId" to keyId,
                "status" to "ACTIVE",
                "role" to "USER",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
            tr.set(deviceRef, mapOf(
                "uid" to uid,
                "deviceId" to deviceId,
                "platform" to "ANDROID",
                "manufacturer" to Build.MANUFACTURER.orEmpty(),
                "model" to Build.MODEL.orEmpty(),
                "androidVersion" to Build.VERSION.RELEASE.orEmpty(),
                "appVersion" to appVersion(),
                "status" to "ACTIVE",
                "createdAt" to FieldValue.serverTimestamp(),
                "lastSeenAt" to FieldValue.serverTimestamp(),
            ))
            null
        })
        attachForCurrentUser()
    }

    suspend fun signInOwner(email: String, password: String) = withContext(Dispatchers.IO) {
        auth.signOut()
        val result = Tasks.await(auth.signInWithEmailAndPassword(email.trim(), password))
        if (result.user?.uid != KURIERX_OWNER_UID) {
            auth.signOut()
            Tasks.await(auth.signInAnonymously())
            attachForCurrentUser()
            error("Этот аккаунт не имеет прав владельца")
        }
        attachForCurrentUser()
    }

    suspend fun signOutToActivation() = withContext(Dispatchers.IO) {
        profileListener?.remove()
        auth.signOut()
        clearCachedProfile()
        Tasks.await(auth.signInAnonymously())
        attachForCurrentUser()
    }

    fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").trim()
    fun currentDeviceCode(): String = "KX-${deviceId().take(4).uppercase()}-${deviceId().drop(4).take(4).uppercase()}"

    suspend fun generateActivationKey(): String = withContext(Dispatchers.IO) {
        check(auth.currentUser?.uid == KURIERX_OWNER_UID) { "Нет OWNER-доступа" }
        val bytes = ByteArray(10).also { SecureRandom().nextBytes(it) }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val body = bytes.joinToString("") { b -> alphabet[(b.toInt() and 0xff) % alphabet.length].toString() }
        val display = "KX-${body.substring(0,4)}-${body.substring(4,8)}-${body.substring(8,10)}${randomPair(alphabet)}"
        val id = sha256(normalizeKey(display))
        Tasks.await(db.collection("activation_keys").document(id).set(mapOf(
            "displayKey" to display,
            "status" to "UNUSED",
            "createdBy" to KURIERX_OWNER_UID,
            "createdAt" to FieldValue.serverTimestamp(),
            "keySuffix" to display.takeLast(4),
        )))
        audit("KEY_CREATED", id, display.takeLast(4))
        display
    }

    suspend fun deleteActivationKey(keyId: String) = ownerOnly {
        val ref = db.collection("activation_keys").document(keyId)
        val snapshot = Tasks.await(ref.get())
        check(snapshot.exists()) { "Ключ уже удалён" }
        val status = snapshot.getString("status") ?: "?"
        check(status == "UNUSED") { "Использованный ключ удаляется вместе с пользователем" }
        Tasks.await(ref.delete())
        audit("KEY_DELETED", keyId, snapshot.getString("keySuffix").orEmpty())
    }

    suspend fun updateUser(uid: String, firstName: String, lastName: String, courierId: String) = ownerOnly {
        Tasks.await(db.collection("users").document(uid).update(mapOf(
            "firstName" to firstName.trim(), "lastName" to lastName.trim(), "courierId" to courierId.trim(), "updatedAt" to FieldValue.serverTimestamp()
        )))
        audit("USER_UPDATED", uid, "$firstName $lastName / $courierId")
    }

    suspend fun setUserStatus(user: OwnerUserRecord, status: String) = ownerOnly {
        val batch = db.batch()
        batch.update(db.collection("users").document(user.uid), mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
        if (user.deviceId.isNotBlank()) batch.update(db.collection("devices").document(user.deviceId), "status", status)
        Tasks.await(batch.commit())
        audit("USER_STATUS", user.uid, status)
    }

    suspend fun blacklist(user: OwnerUserRecord, reason: String) = ownerOnly {
        val batch = db.batch()
        batch.set(db.collection("blacklist").document(user.deviceId), mapOf(
            "deviceId" to user.deviceId, "uid" to user.uid, "reason" to reason, "createdAt" to FieldValue.serverTimestamp()
        ))
        batch.update(db.collection("users").document(user.uid), mapOf("status" to "BLACKLISTED", "updatedAt" to FieldValue.serverTimestamp()))
        batch.update(db.collection("devices").document(user.deviceId), "status", "BLACKLISTED")
        Tasks.await(batch.commit())
        audit("BLACKLIST", user.uid, reason)
    }

    suspend fun removeFromBlacklist(user: OwnerUserRecord) = ownerOnly {
        val batch = db.batch()
        batch.delete(db.collection("blacklist").document(user.deviceId))
        batch.update(db.collection("users").document(user.uid), mapOf("status" to "ACTIVE", "updatedAt" to FieldValue.serverTimestamp()))
        batch.update(db.collection("devices").document(user.deviceId), "status", "ACTIVE")
        Tasks.await(batch.commit())
        audit("BLACKLIST_REMOVED", user.uid, "")
    }

    suspend fun deleteUser(user: OwnerUserRecord) = ownerOnly {
        val batch = db.batch()
        batch.delete(db.collection("users").document(user.uid))
        if (user.deviceId.isNotBlank()) batch.delete(db.collection("devices").document(user.deviceId))
        if (user.activationKeyId.isNotBlank()) batch.delete(db.collection("activation_keys").document(user.activationKeyId))
        Tasks.await(batch.commit())
        audit("USER_DELETED", user.uid, user.deviceId)
    }

    private suspend fun ownerOnly(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        check(auth.currentUser?.uid == KURIERX_OWNER_UID) { "Нет OWNER-доступа" }
        block()
    }

    private fun audit(action: String, target: String, detail: String) {
        db.collection("audit_log").add(mapOf(
            "actor" to KURIERX_OWNER_UID,
            "action" to action,
            "target" to target,
            "detail" to detail,
            "createdAt" to FieldValue.serverTimestamp(),
        ))
    }

    private fun cache(p: RemoteProfile) {
        prefs.edit()
            .putString("uid", p.uid).putString("firstName", p.firstName).putString("lastName", p.lastName)
            .putString("courierId", p.courierId).putString("deviceId", p.deviceId)
            .putString("activationKeyId", p.activationKeyId).putString("status", p.status).apply()
    }

    private fun clearCachedProfile() = prefs.edit().clear().apply()

    private fun deviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return sha256("$androidId|${context.packageName}|${Build.MANUFACTURER}|${Build.MODEL}")
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun normalizeKey(v: String): String = v.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
    private fun sha256(v: String): String = MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun randomPair(alphabet: String): String = buildString { repeat(2) { append(alphabet[SecureRandom().nextInt(alphabet.length)]) } }
}


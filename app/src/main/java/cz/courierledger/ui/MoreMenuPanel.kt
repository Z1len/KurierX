package cz.courierledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * UI-only panel for the "Ещё" tab.
 *
 * It intentionally has no repository/database/navigation dependencies.
 * Wire the callbacks to the existing MorePage navigation in CourierApp.kt.
 */
@Composable
fun MoreMenuPanel(
    onAccount: () -> Unit,
    onControl: (() -> Unit)? = null,
    onClients: () -> Unit,
    onShifts: () -> Unit,
    onBonuses: () -> Unit,
    onPenalties: () -> Unit,
    onFuel: () -> Unit,
    onAdvances: () -> Unit,
    onSalary: () -> Unit,
    onGoals: () -> Unit,
    onBackups: () -> Unit,
    onJournal: () -> Unit,
    onTrash: () -> Unit,
    onDeveloper: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ещё",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Настройки",
                        modifier = Modifier.size(29.dp),
                        tint = accent,
                    )
                }
            }
        }

        item { MoreMenuRow(Icons.Rounded.AccountCircle, "Аккаунт", "Профиль, лицензия и устройство", onAccount) }
        if (onControl != null) item { MoreMenuRow(Icons.Rounded.AdminPanelSettings, "KurierX Control", "Ключи, пользователи и блокировки", onControl) }

        item { MoreSectionDivider() }

        item { MoreMenuRow(Icons.Rounded.PersonOutline, "Клиенты", "Все клиенты и история", onClients) }
        item { MoreMenuRow(Icons.Rounded.CalendarMonth, "Смены", "Все смены и их параметры", onShifts) }
        item { MoreMenuRow(Icons.Rounded.Redeem, "Бонусы и компенсации", "Бонусы, компенсации и доплаты", onBonuses) }
        item { MoreMenuRow(Icons.Rounded.WarningAmber, "Штрафы", "Просмотр и добавление штрафов", onPenalties) }
        item { MoreMenuRow(Icons.Rounded.LocalGasStation, "Дизель и авторасходы", "Расходы на топливо и поездки", onFuel) }
        item { MoreMenuRow(Icons.Rounded.AccountBalanceWallet, "Авансы", "Полученные авансы и возвраты", onAdvances) }

        item { MoreSectionDivider() }

        item { MoreMenuRow(Icons.Rounded.Payments, "Зарплата", "Выплаты и сверка с фактом", onSalary) }
        item { MoreMenuRow(Icons.Rounded.TrackChanges, "Цели", "Планирование и цели", onGoals) }

        item { MoreSectionDivider() }

        item { MoreMenuRow(Icons.Rounded.Backup, "Резервные копии", "Создание и восстановление резервных копий", onBackups) }
        item { MoreMenuRow(Icons.Rounded.ReceiptLong, "Журнал", "История действий в приложении", onJournal) }
        item { MoreMenuRow(Icons.Rounded.DeleteOutline, "Корзина", "Удалённые данные и восстановление", onTrash) }

        item { MoreSectionDivider() }

        item { MoreMenuRow(Icons.Rounded.Shield, "Расширенный режим", "Доступ к дополнительным возможностям", onDeveloper) }
        item { MoreMenuRow(Icons.Rounded.Settings, "Настройки", "Параметры приложения", onSettings) }
    }
}

@Composable
private fun MoreMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.11f)),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = accent.copy(alpha = 0.09f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.05f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = accent,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun MoreSectionDivider() {
    Spacer(Modifier.height(1.dp))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        color = Color.White.copy(alpha = 0.06f),
    )
}

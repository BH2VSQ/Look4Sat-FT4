$ErrorActionPreference = "Stop"

# Run from the Look4Sat-FT4 repository root.
$kotlinPath = "app/src/main/java/com/rtbishop/look4sat/PassSchedule.kt"
$englishPath = "core/presentation/src/main/res/values/strings.xml"
$simplifiedPath = "core/presentation/src/main/res/values-zh/strings.xml"
$traditionalPath = "core/presentation/src/main/res/values-b+zh+Hant/strings.xml"

foreach ($path in @($kotlinPath, $englishPath, $simplifiedPath, $traditionalPath)) {
    if (-not (Test-Path $path)) { throw "Required file not found: $path. Run this script from the repository root." }
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
function Normalize-Newlines([string]$Value) {
    return ($Value -replace "`r`n?", "`n")
}
function Replace-Once([string]$Source, [string]$Old, [string]$New, [string]$Label) {
    $Old = Normalize-Newlines $Old
    $New = Normalize-Newlines $New
    $first = $Source.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) { throw "Could not find expected source block: $Label. No files have been written." }
    $second = $Source.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) { throw "Source block is ambiguous ($Label). No files have been written." }
    return $Source.Substring(0, $first) + $New + $Source.Substring($first + $Old.Length)
}
function Replace-IfPresent([string]$Source, [string]$Old, [string]$New) {
    $Old = Normalize-Newlines $Old
    $New = Normalize-Newlines $New
    if ($Source.Contains($Old)) { return $Source.Replace($Old, $New) }
    return $Source
}
function Add-Resource([string]$Xml, [string]$Name, [string]$Line) {
    if ($Xml.Contains("name=`"$Name`"")) { return $Xml }
    $index = $Xml.LastIndexOf("</resources>", [System.StringComparison]::Ordinal)
    if ($index -lt 0) { throw "Could not find </resources> while adding $Name. No files have been written." }
    return $Xml.Insert($index, "    $Line`n")
}

$kotlin = Normalize-Newlines ([System.IO.File]::ReadAllText((Resolve-Path $kotlinPath).Path))
if ($kotlin.Contains("private fun PassScheduleItem(")) {
    throw "PassSchedule UI changes appear to be already applied. No files have been written."
}

$layoutImportOld = 'import androidx.compose.foundation.layout.padding'
$layoutImportNew = @'
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
'@
$kotlin = Replace-Once $kotlin $layoutImportOld $layoutImportNew 'layout imports'

$backgroundImportNew = @'
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
'@
$kotlin = Replace-Once $kotlin 'import androidx.compose.foundation.clickable' $backgroundImportNew 'background import'

$kotlin = Replace-Once $kotlin 'import androidx.compose.foundation.text.KeyboardOptions' '' 'keyboard options import'

$materialImportsOld = @'
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
'@
$materialImportsNew = @'
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
'@
$kotlin = Replace-Once $kotlin $materialImportsOld $materialImportsNew 'material imports'
$kotlin = Replace-Once $kotlin 'import androidx.compose.ui.text.input.KeyboardType' '' 'keyboard type import'

$unitImportOld = 'import androidx.compose.ui.unit.dp'
$unitImportNew = @'
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
'@
$kotlin = Replace-Once $kotlin $unitImportOld $unitImportNew 'text size imports'

$presentationImportOld = 'import com.rtbishop.look4sat.core.presentation.R'
$presentationImportNew = @'
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.elevationColor
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
'@
$kotlin = Replace-Once $kotlin $presentationImportOld $presentationImportNew 'presentation extension imports'

$delayImportNew = @'
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
'@
$kotlin = Replace-Once $kotlin 'import kotlinx.coroutines.flow.first' $delayImportNew 'delay import'
$timezoneImportNew = @'
import java.util.Locale
import java.util.TimeZone
'@
$kotlin = Replace-Once $kotlin 'import java.util.Locale' $timezoneImportNew 'timezone import'

$reminderReadNew = @'
reminderMinutes = prefs.getInt("reminder_minutes", 10).let { stored ->
                stored.takeIf { it in PASS_REMINDER_PRESETS } ?: 10
            }
'@
$kotlin = Replace-Once $kotlin 'reminderMinutes = prefs.getInt("reminder_minutes", 10).coerceIn(1, 1440)' $reminderReadNew 'reminder config read'
$kotlin = Replace-Once $kotlin '.putInt("reminder_minutes", config.reminderMinutes.coerceIn(1, 1440))' '.putInt("reminder_minutes", config.reminderMinutes.takeIf { it in PASS_REMINDER_PRESETS } ?: 10)' 'reminder config save'
$kotlin = Replace-Once $kotlin 'notifyBefore = prefs.getBoolean("notify_before", false),' 'notifyBefore = prefs.getBoolean("notify_before", false) && !prefs.getBoolean("notify_at_start", false),' 'exclusive legacy notification config'

$destinationStateOld = @'
    val currentlyTrackedIds by container.settingsRepo.selectedIds.collectAsStateWithLifecycle()
    var config by remember(context) { mutableStateOf(PassScheduleStore.readConfig(context)) }
    var passes by remember { mutableStateOf<List<OrbitalPass>>(emptyList()) }
    var isCalculating by remember { mutableStateOf(false) }
'@
$destinationStateNew = @'
    val currentlyTrackedIds by container.settingsRepo.selectedIds.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    val isUtc = otherSettings.stateOfUtc
    var config by remember(context) { mutableStateOf(PassScheduleStore.readConfig(context)) }
    var passes by remember { mutableStateOf<List<OrbitalPass>>(emptyList()) }
    var isCalculating by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }
'@
$kotlin = Replace-Once $kotlin $destinationStateOld $destinationStateNew 'pass schedule clock and UTC setting'

$customState = @'
    val customReminderMinutes = remember(context) {
        mutableStateOf(PassScheduleStore.readConfig(context).reminderMinutes
            .takeIf { it !in PASS_REMINDER_PRESETS }?.toString().orEmpty())
    }
'@
$kotlin = Replace-Once $kotlin $customState '' 'remove custom reminder state'

$notifyHeader = '                Text(stringResource(R.string.pass_schedule_notifications), style = MaterialTheme.typography.titleMedium)'
$headerIndex = $kotlin.IndexOf($notifyHeader, [System.StringComparison]::Ordinal)
if ($headerIndex -lt 0) { throw "Could not find notification settings section. No files have been written." }
$divider = '                HorizontalDivider()'
$dividerIndex = $kotlin.LastIndexOf($divider, $headerIndex, [System.StringComparison]::Ordinal)
$endMarker = '                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)'
$endIndex = $kotlin.IndexOf($endMarker, $headerIndex, [System.StringComparison]::Ordinal)
if ($dividerIndex -lt 0 -or $endIndex -lt 0) { throw "Could not locate the full notification settings block. No files have been written." }
$newNotifyBlock = @'
                HorizontalDivider()
                Text(stringResource(R.string.pass_schedule_notifications), style = MaterialTheme.typography.titleMedium)
                val selectedReminder = when {
                    config.notifyAtStart -> 0
                    config.notifyBefore -> config.reminderMinutes
                    else -> -1
                }
                val reminderRows = listOf(listOf(0, 5, 10), listOf(15, 30, 60))
                reminderRows.forEach { rowMinutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowMinutes.forEach { minutes ->
                            val selected = selectedReminder == minutes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = when {
                                        selected -> config.copy(notifyAtStart = false, notifyBefore = false)
                                        minutes == 0 -> config.copy(notifyAtStart = true, notifyBefore = false)
                                        else -> config.copy(
                                            notifyAtStart = false,
                                            notifyBefore = true,
                                            reminderMinutes = minutes
                                        )
                                    }
                                    updateConfig(next, requestNotificationPermissionIfNeeded = !selected)
                                },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        if (minutes == 0) stringResource(R.string.pass_schedule_notify_at_start)
                                        else stringResource(R.string.pass_schedule_minutes_short, minutes),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                }

'@
$newNotifyBlock = Normalize-Newlines $newNotifyBlock
$kotlin = $kotlin.Substring(0, $dividerIndex) + $newNotifyBlock + $kotlin.Substring($endIndex)

$itemsStart = '                items(passes, key = { "${it.catNum}-${it.aosTime}" }) { pass ->'
$itemsEnd = '                item { Spacer(Modifier.height(8.dp)) }'
$itemsStartIndex = $kotlin.IndexOf($itemsStart, [System.StringComparison]::Ordinal)
$itemsEndIndex = $kotlin.IndexOf($itemsEnd, $itemsStartIndex, [System.StringComparison]::Ordinal)
if ($itemsStartIndex -lt 0 -or $itemsEndIndex -lt 0) { throw "Could not locate pass list rows. No files have been written." }
$newItemsBlock = @'
                items(passes, key = { "${it.catNum}-${it.aosTime}" }) { pass ->
                    PassScheduleItem(pass = pass, now = currentTime, isUtc = isUtc)
                }
'@
$newItemsBlock = Normalize-Newlines $newItemsBlock
$kotlin = $kotlin.Substring(0, $itemsStartIndex) + $newItemsBlock + $kotlin.Substring($itemsEndIndex)

$component = @'

@Composable
private fun PassScheduleItem(pass: OrbitalPass, now: Long, isUtc: Boolean) {
    val timeZone = remember(isUtc) {
        if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    val timeFormat = remember(isUtc) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).also { it.timeZone = timeZone }
    }
    val aosTimeStr = remember(pass.aosTime, isUtc) { timeFormat.format(Date(pass.aosTime)) }
    val losTimeStr = remember(pass.losTime, isUtc) { timeFormat.format(Date(pass.losTime)) }
    val durationStr = remember(pass.aosTime, pass.losTime) {
        val seconds = (pass.losTime - pass.aosTime) / 1000L
        "${seconds / 60}m ${seconds % 60}s"
    }
    val remainingSeconds = ((pass.aosTime - now) / 1000L).coerceAtLeast(0L)
    val countdown = if (pass.aosTime <= now) {
        stringResource(R.string.pass_schedule_started)
    } else {
        String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            remainingSeconds / 3600L,
            (remainingSeconds % 3600L) / 60L,
            remainingSeconds % 60L
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pass.name,
                    modifier = Modifier.weight(1f).padding(end = 6.dp).infiniteMarquee(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                val elevColor = elevationColor(pass.maxElevation)
                Icon(
                    painter = painterResource(R.drawable.ic_elevation),
                    contentDescription = null,
                    tint = elevColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "${pass.maxElevation}°", color = elevColor)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pass.isDeepSpace) {
                        Text(
                            text = stringResource(R.string.pass_deep_space),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(text = durationStr, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${pass.altitude} km", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.pass_aosLos, pass.aosAzimuth.toInt(), pass.losAzimuth.toInt()),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                val defaultTime = "   - - : - -   "
                Text(
                    text = if (pass.isDeepSpace) defaultTime else aosTimeStr,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { if (pass.isDeepSpace) 100f else pass.progress },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth(0.75f)
                )
                Text(
                    text = if (pass.isDeepSpace) defaultTime else losTimeStr,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.pass_schedule_countdown, countdown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
    }
}
'@
$component = Normalize-Newlines $component
$kotlin = $kotlin.TrimEnd("`n") + $component + "`n"

$english = Normalize-Newlines ([System.IO.File]::ReadAllText((Resolve-Path $englishPath).Path))
$simplified = Normalize-Newlines ([System.IO.File]::ReadAllText((Resolve-Path $simplifiedPath).Path))
$english = Replace-IfPresent $english '<string name="pass_schedule_range">Upcoming passes in the next 3 days · local time</string>' '<string name="pass_schedule_range">Upcoming passes in the next 3 days</string>'
$english = Replace-IfPresent $english '<string name="pass_schedule_notify_at_start">Notify when the pass begins</string>' '<string name="pass_schedule_notify_at_start">Pass start</string>'
$english = Replace-IfPresent $english '<string name="pass_schedule_minutes_short">%1$dm</string>' '<string name="pass_schedule_minutes_short">%1$d min</string>'
$simplified = Replace-IfPresent $simplified '<string name="pass_schedule_range">未来 3 天的过境 · 本地时间</string>' '<string name="pass_schedule_range">未来 3 天的过境</string>'
$simplified = Replace-IfPresent $simplified '<string name="pass_schedule_notify_at_start">过境开始时通知</string>' '<string name="pass_schedule_notify_at_start">过境开始</string>'
$english = Add-Resource $english 'pass_schedule_countdown' '<string name="pass_schedule_countdown">Starts in: %1$s</string>'
$english = Add-Resource $english 'pass_schedule_started' '<string name="pass_schedule_started">Started</string>'
$simplified = Add-Resource $simplified 'pass_schedule_countdown' '<string name="pass_schedule_countdown">距离开始：%1$s</string>'
$simplified = Add-Resource $simplified 'pass_schedule_started' '<string name="pass_schedule_started">已开始</string>'

# Save backups outside the repository before writing modified files.
$backupRoot = Join-Path $env:TEMP ("Look4Sat-FT4-pass-schedule-ui-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
foreach ($path in @($kotlinPath, $englishPath, $simplifiedPath, $traditionalPath)) {
    $backupName = $path -replace '[/\\]', '__'
    Copy-Item $path (Join-Path $backupRoot $backupName) -Force
}
[System.IO.File]::WriteAllText((Resolve-Path $kotlinPath).Path, $kotlin, $utf8NoBom)
[System.IO.File]::WriteAllText((Resolve-Path $englishPath).Path, $english, $utf8NoBom)
[System.IO.File]::WriteAllText((Resolve-Path $simplifiedPath).Path, $simplified, $utf8NoBom)
[System.IO.File]::WriteAllText((Resolve-Path $traditionalPath).Path, $simplified, $utf8NoBom)

Write-Host "Updated pass schedule UI: main-screen pass row layout plus live AOS countdown, and a 2x3 single-choice reminder grid."
Write-Host "Backups saved to: $backupRoot"

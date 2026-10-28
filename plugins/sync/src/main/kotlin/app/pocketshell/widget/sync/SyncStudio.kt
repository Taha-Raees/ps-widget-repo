package app.pocketshell.widget.sync

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * THE MAXIMIZED MODE — the backup/restore app, in the shared flat language
 * (hairline rules, mono small-caps, accent text, a rule — not a pill — marking
 * the active item; no filled cards).
 *
 *   BACKUP    where it goes · what is in it · what is left out · encryption ·
 *             compression · BACK UP NOW · the backups at the location
 *   RESTORE   choose a file → preview → choose what and how → restore →
 *             reinstall packages
 *   SYNC      the rsync / rclone profile app, unchanged
 *
 * Presentation only: every action is a call into [VaultState], whose work runs
 * on its own scope. Wording states FACTS a step produced ("Backup written",
 * "checksum matches"), never a promise of safety.
 */
internal enum class StudioTab(val label: String, val icon: ImageVector) {
    BACKUP("Backup", Icons.Outlined.CloudUpload),
    RESTORE("Restore", Icons.Outlined.Restore),
    SYNC("Sync", Icons.Outlined.Sync);

    companion object {
        /** The tabs the page shows: SYNC (rsync/rclone profiles) only while [SyncApp.PROFILES_ENABLED]. */
        val visible: List<StudioTab> get() = entries.filter { it != SYNC || SyncApp.PROFILES_ENABLED }
    }
}

/** The two system-picker launches, remembered per composition. */
internal class Pickers(val folder: () -> Unit, val file: () -> Unit)

@Composable
internal fun rememberPickers(vault: VaultState): Pickers {
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vault.useFolder(uri)
    }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vault.chooseFile(uri)
    }
    return remember(tree, doc) { Pickers(folder = { tree.launch(null) }, file = { doc.launch(arrayOf("*/*")) }) }
}

// ------------------------------------------------------------------ shell

@Composable
internal fun SyncStudio(
    vault: VaultState,
    maxWidthDp: Float,
    nowMs: Long,
    profilesContent: @Composable () -> Unit,
) {
    val rail = SyncLayout.usesSideRail(maxWidthDp)
    val pickers = rememberPickers(vault)
    var askPassphrase by remember { mutableStateOf(false) }

    // While the studio shows, Back belongs to ITS flows; the profile screens' own Back
    // handler stands down unless the SYNC tab is the one on screen (see SyncApp).
    DisposableEffect(Unit) {
        vault.studioVisible = true
        onDispose { vault.studioVisible = false }
    }
    BackHandler(enabled = vault.tab == StudioTab.RESTORE && vault.restore !is RestoreUi.Idle) { vault.closeRestore() }

    if (askPassphrase) {
        PassphraseDialog(
            confirmTwice = true,
            onDismiss = { askPassphrase = false },
            onSubmit = { chars ->
                askPassphrase = false
                vault.startBackup(chars)
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            Text(text = SyncApp.spec.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary)
            VaultSummaryLine(vault, nowMs)
        }
        Rule()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rail) {
                Column(modifier = Modifier.width(88.dp).fillMaxHeight()) {
                    StudioTab.visible.forEach { t -> NavItem(t, t == vault.tab, ruleOnTop = false, modifier = Modifier.fillMaxWidth()) { vault.tab = t } }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize().padding(start = if (rail) 12.dp else 0.dp)) {
                when (vault.tab) {
                    StudioTab.BACKUP -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        BackupTab(vault, nowMs, pickers, onBackUp = { if (vault.settings.encrypt) askPassphrase = true else vault.startBackup(null) })
                        Spacer(Modifier.height(16.dp))
                    }
                    StudioTab.RESTORE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RestoreTab(vault, nowMs, pickers)
                        Spacer(Modifier.height(16.dp))
                    }
                    StudioTab.SYNC -> profilesContent()
                }
            }
        }
        if (!rail) {
            Column {
                Rule()
                Row(modifier = Modifier.fillMaxWidth()) {
                    StudioTab.visible.forEach { t -> NavItem(t, t == vault.tab, ruleOnTop = true, modifier = Modifier.weight(1f)) { vault.tab = t } }
                }
            }
        }
    }
}

/** The one-line truth under the title: the last backup's facts, or that there is none. */
@Composable
internal fun VaultSummaryLine(vault: VaultState, nowMs: Long) {
    val last = vault.settings.last
    val text = when (val j = vault.job) {
        is VaultRun.Working -> "${j.what} · ${j.label}${if (j.bytes > 0) " · ${VaultState.formatSize(j.bytes)}" else ""}"
        else -> if (last == null) "No backup yet" else lastBackupText(last, nowMs)
    }
    VText(text, color = HomeTokens.textDim, size = 11)
}

internal fun lastBackupText(last: LastBackup, nowMs: Long): String = listOf(
    "Last backup ${SyncProfiles.agoText(nowMs, last.atMs)}",
    VaultState.formatSize(last.sizeBytes),
    if (last.encrypted) "encrypted" else "not encrypted",
).joinToString(" · ")

// ---------------------------------------------------------------- backup tab

@Composable
private fun BackupTab(vault: VaultState, nowMs: Long, pickers: Pickers, onBackUp: () -> Unit) {
    val s = vault.settings
    val o = s.options
    val working = vault.job as? VaultRun.Working
    // Sizes are measured only while this tab is showing (disk work, low priority).
    LaunchedEffect(vault.loaded) { if (vault.loaded && vault.homeRows.isEmpty()) vault.refreshHome() }
    Column(modifier = Modifier.fillMaxWidth()) {
        // --- the action first: it is why the tab is opened
        Spacer(Modifier.height(12.dp))
        JobPanel(vault)
        if (working == null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VAction("BACK UP NOW", enabled = s.location != null, primary = true, onClick = onBackUp)
                if (s.location == null) VText("choose where backups go first", color = HomeTokens.textDim, size = 10)
            }
        }

        // --- location
        VSection("LOCATION")
        val loc = s.location
        VText(loc?.label ?: "Not chosen", size = 14, weight = FontWeight.SemiBold)
        VText(
            when (loc?.kind) {
                LocationKind.SAF_TREE -> "A folder you chose — it stays when PocketShell is uninstalled and you can open it from another phone."
                LocationKind.APP_STORAGE -> "App storage — Android removes it if PocketShell is uninstalled. Copy backups off the device to keep them."
                null -> "Pick a folder (Downloads, an SD card, a cloud folder) so the backup can leave this phone."
            },
            color = HomeTokens.textDim, size = 10, lines = 3,
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            VAction("CHOOSE FOLDER", onClick = pickers.folder)
            VAction("USE APP STORAGE", onClick = vault::useAppStorage)
        }
        vault.listingError?.let { VText("cannot read this location: $it", color = HomeTokens.danger, size = 10, lines = 3) }

        // --- what is in it
        VSection("WHAT TO INCLUDE")
        BackupScope.entries.forEach { sc ->
            ToggleRow(sc in o.scopes, sc.label, scopeHint(sc)) {
                vault.update { cur -> cur.copy(options = cur.options.copy(scopes = if (sc in cur.options.scopes) cur.options.scopes - sc else cur.options.scopes + sc)) }
            }
        }

        if (BackupScope.HOME in o.scopes) FoldersSection(vault)

        VSection("LEAVE OUT")
        VText("Regenerable weight. Turn any of these off to include it — the sizes above update.", color = HomeTokens.textDim, size = 10, lines = 2)
        ExcludePreset.entries.forEach { p ->
            ToggleRow(p in o.presets, p.label, null) {
                vault.update { cur -> cur.copy(options = cur.options.copy(presets = if (p in cur.options.presets) cur.options.presets - p else cur.options.presets + p)) }
                vault.scheduleHomeRefresh()
            }
        }

        // --- protection
        VSection("ENCRYPTION")
        ToggleRow(
            s.encrypt, "Encrypt with a passphrase",
            if (s.encrypt) "Key and credential files are included. You will be asked for the passphrase each time; it is never stored — if you lose it, the backup cannot be opened."
            else "Without a passphrase the file can be read by anyone who gets it.",
        ) { vault.update { it.copy(encrypt = !it.encrypt) }; vault.scheduleHomeRefresh() }
        if (!s.encrypt) {
            ToggleRow(
                o.includeSensitive, "Include key and credential files anyway",
                if (o.includeSensitive) "SSH keys, .env files and similar will be stored unencrypted." else "Left out by default: ~/.ssh, ~/.gnupg, .env files, *.pem, *.key and similar (best effort).",
            ) { vault.update { cur -> cur.copy(options = cur.options.copy(includeSensitive = !cur.options.includeSensitive)) }; vault.scheduleHomeRefresh() }
        }

        // --- compression
        VSection("COMPRESSION")
        when (vault.zstdInstalled) {
            true -> VText("zstd — fast, with a better ratio than gzip", size = 12)
            false -> {
                VText("gzip — works everywhere. zstd is faster and smaller.", size = 12)
                VAction("INSTALL ZSTD", enabled = working == null, onClick = vault::installZstd)
            }
            null -> VText("checking…", color = HomeTokens.textDim, size = 12)
        }

        // --- retention
        VSection("KEEP")
        Row(verticalAlignment = Alignment.CenterVertically) {
            VAction("−", enabled = o.keep > 1) { vault.update { cur -> cur.copy(options = cur.options.copy(keep = (cur.options.keep - 1).coerceAtLeast(1))) } }
            VText("newest ${o.keep}", size = 13)
            VAction("+", enabled = o.keep < BackupOptions.MAX_KEEP) { vault.update { cur -> cur.copy(options = cur.options.copy(keep = (cur.options.keep + 1).coerceAtMost(BackupOptions.MAX_KEEP))) } }
        }
        VText("After a new backup is written, this phone's older backups made by this app are removed. Backups from other phones, and any file this app did not write, are never touched.", color = HomeTokens.textDim, size = 10, lines = 4)

        // --- what is at the location
        VSection("BACKUPS HERE")
        if (vault.listing.isEmpty()) {
            VText(if (s.location == null) "—" else "None yet", color = HomeTokens.textDim, size = 12)
        }
        vault.listing.forEach { b -> BackupRow(vault, b, nowMs) }
        if (vault.leftovers.isNotEmpty()) {
            VSection("INTERRUPTED")
            VText("An earlier backup or restore did not finish and left temporary data on this phone.", color = HomeTokens.textDim, size = 10, lines = 3)
            VAction("CLEAN UP", onClick = vault::discardLeftovers)
        }
    }
}

private fun scopeHint(sc: BackupScope): String? = when (sc) {
    BackupScope.HOME -> "Your projects, dotfiles and tool settings."
    BackupScope.PACKAGES -> "The list of installed packages, to reinstall on a new phone."
    BackupScope.APP_SETTINGS -> "Notes, launchers and preferences. Never plugin permissions."
    BackupScope.USR_LOCAL -> null
    BackupScope.OPT -> null
}

/**
 * Choose what in `~` goes into the backup — however large. Known tools are
 * recognised and labelled, sizes are what will actually be archived (the
 * leave-out rules applied), and the user can tick exactly the folders they
 * want or add a path the list does not show.
 */
@Composable
private fun FoldersSection(vault: VaultState) {
    val o = vault.settings.options
    val chosen = o.homeMode == HomeMode.CHOSEN
    var typed by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    VSection("FOLDERS IN ~")
    ToggleRow(!chosen, "Everything in my home", "Every file under ~, except what is left out below.") { vault.setHomeMode(HomeMode.EVERYTHING) }
    ToggleRow(chosen, "Only the folders I tick", "Pick the tools and projects you want to take with you.") { vault.setHomeMode(HomeMode.CHOSEN) }

    val rows = vault.homeRows
    val selected = if (chosen) o.homeItems else rows.map { it.rel }.toSet()
    val total = HomeSelection.selectedBytes(rows, selected)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        VText(
            if (vault.homeScanning && total == null) "Measuring…" else "About ${HomeSelection.size(total)} before compression",
            color = HomeTokens.accent, size = 12, modifier = Modifier.weight(1f),
        )
        if (chosen) {
            VAction("ALL", onClick = vault::selectAllHome)
            VAction("NONE", onClick = vault::selectNoHome)
        }
        VAction("RE-MEASURE", enabled = !vault.homeScanning, onClick = vault::refreshHome)
    }
    vault.homeError?.let { VText(it, color = HomeTokens.danger, size = 10, lines = 2) }
    if (rows.isEmpty() && !vault.homeScanning) VText("Nothing found in ~ yet.", color = HomeTokens.textDim, size = 11)

    val tools = rows.filter { it.known }
    val others = rows.filter { !it.known }
    if (tools.isNotEmpty()) {
        VText("TOOLS AND APP DATA", color = HomeTokens.textDim, size = 9, modifier = Modifier.padding(top = 8.dp))
        tools.forEach { r -> HomeRowView(r, r.rel in selected, enabled = chosen) { vault.toggleHomeItem(r.rel) } }
    }
    if (others.isNotEmpty()) {
        VText("EVERYTHING ELSE IN ~", color = HomeTokens.textDim, size = 9, modifier = Modifier.padding(top = 8.dp))
        others.forEach { r -> HomeRowView(r, r.rel in selected, enabled = chosen) { vault.toggleHomeItem(r.rel) } }
    }

    VText("ADD A PATH", color = HomeTokens.textDim, size = 9, modifier = Modifier.padding(top = 10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { PassField(typed, "relative to ~, e.g. .config/kilo", plain = true) { typed = it; problem = null } }
        VAction("ADD", enabled = typed.isNotBlank()) {
            problem = vault.addHomePath(typed)
            if (problem == null) typed = ""
        }
    }
    problem?.let { VText(it, color = HomeTokens.danger, size = 10, lines = 2) }
}

@Composable
private fun HomeRowView(row: HomeRow, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = row.rel) { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VText(if (checked) "[x]" else "[ ]", color = if (checked && enabled) HomeTokens.accent else HomeTokens.textDim, size = 13)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            VText("${row.rel}${if (row.isDir) "/" else ""}", size = 12, color = if (enabled) HomeTokens.textPrimary else HomeTokens.textDim)
            row.label?.let { VText(it, color = HomeTokens.textDim, size = 10, lines = 2) }
        }
        Column(horizontalAlignment = Alignment.End) {
            VText(HomeSelection.size(row.bytes), size = 11, color = if (row.bytes == null) HomeTokens.textDim else HomeTokens.textPrimary)
            if (row.leftOut > 0) VText("${row.leftOut} left out", color = HomeTokens.textDim, size = 9)
        }
    }
}

@Composable
private fun BackupRow(vault: VaultState, b: StoredBackup, nowMs: Long) {
    var confirmDelete by remember { mutableStateOf(false) }
    val r = b.record
    Column(modifier = Modifier.fillMaxWidth()) {
        Rule()
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            VText(b.name, size = 12, weight = FontWeight.SemiBold)
            VText(
                if (r == null) "no checksum record — incomplete or from elsewhere"
                else listOf(
                    SyncProfiles.agoText(nowMs, r.manifest.createdAtMs),
                    VaultState.formatSize(r.sizeBytes),
                    r.manifest.codec.label,
                    if (r.manifest.encrypted) "encrypted" else "not encrypted",
                    r.manifest.device,
                ).joinToString(" · "),
                color = if (r == null) HomeTokens.danger else HomeTokens.textDim, size = 10, lines = 2,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                VAction("CHECK", enabled = !vault.busy && r != null) { vault.verify(b) }
                VAction("RESTORE", enabled = !vault.busy) {
                    vault.tab = StudioTab.RESTORE
                    vault.chooseFromListing(b)
                }
                VAction("DELETE", enabled = !vault.busy && BackupNames.isOurs(b.name), danger = true) { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this backup?", color = HomeTokens.textPrimary) },
            text = { VText("${b.name} and its checksum file will be deleted from ${vault.settings.location?.label ?: "the location"}. This cannot be undone.", size = 11, lines = 5) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vault.delete(b) }) { Text("DELETE", fontFamily = TerminalTheme.mono, color = HomeTokens.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim) } },
        )
    }
}

/** Progress / result of the current job, with CANCEL while it runs. */
@Composable
internal fun JobPanel(vault: VaultState) {
    when (val j = vault.job) {
        VaultRun.Idle -> Unit
        is VaultRun.Working -> Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            VText("${j.what.uppercase()} · ${j.label}", color = HomeTokens.accent, size = 12)
            VText(
                buildString {
                    append(VaultState.formatSize(j.bytes))
                    j.total?.let { append(" of ${VaultState.formatSize(it)}") }
                },
                color = HomeTokens.textDim, size = 10,
            )
            VAction("CANCEL", danger = true, onClick = vault::cancelCurrent)
        }
        is VaultRun.Finished -> Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .clickable(role = Role.Button, onClickLabel = "Dismiss result") { vault.dismissRun() },
        ) {
            VText(j.message, color = if (j.ok) HomeTokens.runningGreen else HomeTokens.danger, size = 12, lines = 4)
            VText("tap to dismiss", color = HomeTokens.textDim, size = 9)
        }
    }
}

// --------------------------------------------------------------- restore tab

@Composable
private fun RestoreTab(vault: VaultState, nowMs: Long, pickers: Pickers) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        when (val r = vault.restore) {
            RestoreUi.Idle -> {
                VText("Restore from a backup file", size = 16, weight = FontWeight.SemiBold)
                VText("On a new phone: install PocketShell, open Sync, choose the backup file from your storage. Nothing is changed until you confirm.", color = HomeTokens.textDim, size = 11, lines = 4)
                Spacer(Modifier.height(8.dp))
                VAction("CHOOSE BACKUP FILE", primary = true, enabled = !vault.busy, onClick = pickers.file)
                if (vault.listing.isNotEmpty()) {
                    VSection("OR FROM ${vault.settings.location?.label?.uppercase() ?: "THIS LOCATION"}")
                    vault.listing.forEach { b ->
                        Rule()
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Restore from ${b.name}") { vault.chooseFromListing(b) }.padding(vertical = 10.dp),
                        ) {
                            VText(b.name, size = 12, weight = FontWeight.SemiBold)
                            VText(
                                b.record?.let { listOf(SyncProfiles.agoText(nowMs, it.manifest.createdAtMs), VaultState.formatSize(it.sizeBytes), it.manifest.device).joinToString(" · ") } ?: "no checksum record",
                                color = HomeTokens.textDim, size = 10,
                            )
                        }
                    }
                }
                vault.leftovers.takeIf { it.isNotEmpty() }?.let {
                    VSection("INTERRUPTED")
                    VAction("CLEAN UP", onClick = vault::discardLeftovers)
                }
            }

            is RestoreUi.Working -> {
                VText("RESTORE · ${r.label}", color = HomeTokens.accent, size = 12)
                VText(
                    buildString {
                        append(VaultState.formatSize(r.bytes))
                        r.total?.let { append(" of ${VaultState.formatSize(it)}") }
                    },
                    color = HomeTokens.textDim, size = 10,
                )
                VAction("CANCEL", danger = true, onClick = vault::closeRestore)
            }

            is RestoreUi.NeedPassphrase -> {
                VText(r.source.name, size = 14, weight = FontWeight.SemiBold)
                VText("This backup is encrypted.", color = HomeTokens.textDim, size = 11)
                r.wrong?.let { VText(it, color = HomeTokens.danger, size = 11, lines = 2) }
                var pass by remember { mutableStateOf("") }
                PassField(pass, "PASSPHRASE") { pass = it }
                Row {
                    VAction("CONTINUE", primary = true, enabled = pass.isNotEmpty()) { vault.continueRestore(r.source, pass.toCharArray()); pass = "" }
                    VAction("CANCEL", onClick = vault::closeRestore)
                }
            }

            is RestoreUi.NeedTool -> {
                VText("Needs ${r.tool}", size = 14, weight = FontWeight.SemiBold)
                VText("This backup is compressed with ${r.tool}, which this Linux does not have yet. Installing it downloads one small package (network needed).", color = HomeTokens.textDim, size = 11, lines = 4)
                VaultJobLine(vault.job)
                Row {
                    VAction("INSTALL ${r.tool.uppercase()}", primary = true, enabled = !vault.busy) { vault.installZstd() }
                    VAction("TRY AGAIN", enabled = !vault.busy) { vault.continueRestore(r.source, null) }
                    VAction("CANCEL", onClick = vault::closeRestore)
                }
            }

            is RestoreUi.Failed -> {
                VText("Could not restore", color = HomeTokens.danger, size = 14, weight = FontWeight.SemiBold)
                VText(r.reason, color = HomeTokens.danger, size = 11, lines = 5)
                VAction("BACK", onClick = vault::closeRestore)
            }

            is RestoreUi.Ready -> RestoreReady(vault, r, nowMs)

            is RestoreUi.Done -> RestoreDone(vault, r)
        }
    }
}

@Composable
private fun VaultJobLine(job: VaultRun) {
    when (job) {
        is VaultRun.Working -> VText("${job.what} · ${job.label}", color = HomeTokens.accent, size = 11)
        is VaultRun.Finished -> VText(job.message, color = if (job.ok) HomeTokens.runningGreen else HomeTokens.danger, size = 11, lines = 3)
        VaultRun.Idle -> Unit
    }
}

@Composable
private fun RestoreReady(vault: VaultState, r: RestoreUi.Ready, nowMs: Long) {
    val i = r.inspected
    val m = i.manifest
    VText("Backup from ${m.device}", size = 16, weight = FontWeight.SemiBold)
    VText(SyncProfiles.agoText(nowMs, m.createdAtMs).let { "made $it" }, color = HomeTokens.textDim, size = 11)
    VSection("CONTENTS")
    KeyVal("FILE", r.source.name)
    r.source.sizeBytes?.let { KeyVal("SIZE", VaultState.formatSize(it)) }
    KeyVal("FORMAT", "${i.header.codec.label}${if (i.header.encrypted) " · encrypted" else ""}")
    KeyVal("INCLUDES", m.scopes.joinToString(", ") { it.label })
    if (BackupScope.HOME in m.scopes) KeyVal("HOME FOLDERS", if (m.homeItems.isEmpty()) "everything in ~" else m.homeItems.joinToString(", "))
    KeyVal("PACKAGES", "${i.packages.size}")
    if (i.appSettings.isNotEmpty()) KeyVal("APP SETTINGS", i.appSettings.joinToString(", "))
    KeyVal("KEY FILES", if (m.sensitiveIncluded) "included" else "not included")
    if (i.record != null) KeyVal("CHECKSUM", "matches the file's record")
    i.compatibility.errors.forEach { VText(it, color = HomeTokens.danger, size = 11, lines = 3) }
    i.compatibility.warnings.forEach { VText(it, color = HomeTokens.accentBright, size = 11, lines = 3) }

    if (!i.compatibility.ok) {
        VAction("CLOSE", onClick = vault::closeRestore)
        return
    }

    VSection("RESTORE WHAT")
    m.scopes.filter { it != BackupScope.PACKAGES && it != BackupScope.APP_SETTINGS }.forEach { sc ->
        ToggleRow(sc in vault.restoreScopes, sc.label, null) {
            vault.restoreScopes = if (sc in vault.restoreScopes) vault.restoreScopes - sc else vault.restoreScopes + sc
        }
    }
    if (i.appSettings.isNotEmpty()) {
        ToggleRow(
            vault.restoreAppSettings, "App settings (${i.appSettings.joinToString(", ")})",
            "Applied when PocketShell restarts. Your current files are kept as .pre-restore.",
        ) {
            vault.restoreAppSettings = !vault.restoreAppSettings
            vault.restoreScopes = if (vault.restoreAppSettings) vault.restoreScopes + BackupScope.APP_SETTINGS else vault.restoreScopes - BackupScope.APP_SETTINGS
        }
    }

    VSection("HOW")
    RestoreMode.entries.forEach { mode ->
        ToggleRow(vault.restoreMode == mode, mode.label, null) { vault.restoreMode = mode }
    }
    val inPlace = vault.restoreMode == RestoreMode.IN_PLACE
    if (inPlace) {
        VText("Files in the backup replace the same paths on this phone. Files that are not in the backup are left alone.", color = HomeTokens.textDim, size = 10, lines = 3)
        PassField(vault.confirmText, "TYPE ${RestorePlan.CONFIRM_WORD} TO CONFIRM", plain = true) { vault.confirmText = it }
    }
    Spacer(Modifier.height(8.dp))
    val hasTrees = vault.restoreScopes.any { it.guestPaths.isNotEmpty() }
    val canRestore = (hasTrees || (vault.restoreAppSettings && BackupScope.APP_SETTINGS in vault.restoreScopes)) &&
        (!inPlace || RestorePlan.confirmationOk(vault.confirmText)) && !vault.busy
    Row {
        VAction("RESTORE", primary = true, enabled = canRestore, onClick = vault::applyRestore)
        VAction("CANCEL", onClick = vault::closeRestore)
    }
}

@Composable
private fun RestoreDone(vault: VaultState, r: RestoreUi.Done) {
    VText("Restore finished", color = HomeTokens.runningGreen, size = 16, weight = FontWeight.SemiBold)
    KeyVal("INTO", if (r.applied.destination == "/") "the same paths (in place)" else r.applied.destination)
    if (r.applied.restoredPaths.isNotEmpty()) KeyVal("FROM THE BACKUP", r.applied.restoredPaths.joinToString(", "))
    if (r.applied.appSettingsRestored.isNotEmpty()) {
        KeyVal("APP SETTINGS", r.applied.appSettingsRestored.joinToString(", "))
        VText("Close PocketShell completely from the recent apps and open it again to apply them.", color = HomeTokens.accentBright, size = 11, lines = 3)
    }
    if (r.packages > 0) {
        VSection("PACKAGES")
        VText("${r.packages} packages were installed on the old phone. Reinstall them here (downloads from the Debian mirrors).", color = HomeTokens.textDim, size = 11, lines = 3)
        VaultJobLine(vault.reinstall)
        if (vault.reinstall !is VaultRun.Working) {
            VAction("REINSTALL ${r.packages} PACKAGES", enabled = !vault.busy && vault.reinstall !is VaultRun.Finished) { vault.reinstallPackages(r.stageId) }
        }
    }
    Spacer(Modifier.height(8.dp))
    VAction("DONE", primary = true, enabled = vault.reinstall !is VaultRun.Working, onClick = vault::closeRestore)
}

// ----------------------------------------------------------------- dialogs

/** A masked passphrase prompt; with [confirmTwice] the two entries must match (a typo would lock the backup). */
@Composable
internal fun PassphraseDialog(confirmTwice: Boolean, onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val mismatch = confirmTwice && b.isNotEmpty() && a != b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passphrase", color = HomeTokens.textPrimary) },
        text = {
            Column {
                VText("It is asked for each time and never stored. Without it the backup cannot be opened.", color = HomeTokens.textDim, size = 10, lines = 3)
                PassField(a, "PASSPHRASE") { a = it }
                if (confirmTwice) PassField(b, "REPEAT PASSPHRASE") { b = it }
                if (mismatch) VText("The two entries differ.", color = HomeTokens.danger, size = 10)
            }
        },
        confirmButton = {
            TextButton(
                enabled = a.isNotEmpty() && (!confirmTwice || a == b),
                onClick = { val chars = a.toCharArray(); a = ""; b = ""; onSubmit(chars) },
            ) { Text("START", fontFamily = TerminalTheme.mono, color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim) } },
    )
}

// --------------------------------------------------------------- primitives

@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
}

@Composable
internal fun VText(
    text: String,
    color: Color = HomeTokens.textPrimary,
    size: Int = 12,
    weight: FontWeight? = null,
    lines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun VSection(label: String) {
    Spacer(Modifier.height(16.dp))
    VText(label, color = HomeTokens.textDim, size = 10)
    Spacer(Modifier.height(4.dp))
    Rule()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun KeyVal(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        VText(label, color = HomeTokens.textDim, size = 10, modifier = Modifier.width(96.dp))
        VText(value, size = 11, lines = 3, modifier = Modifier.weight(1f))
    }
}

/** An action as accent text — no fill, no border; 48dp tall for a thumb. */
@Composable
internal fun VAction(label: String, enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(
            text = label,
            fontFamily = TerminalTheme.mono,
            fontSize = if (primary) 13.sp else 11.sp,
            fontWeight = if (primary) FontWeight.SemiBold else null,
            color = when {
                !enabled -> HomeTokens.textDim
                danger -> HomeTokens.danger
                else -> HomeTokens.accent
            },
        )
    }
}

/** A flat checkbox / radio row: "[x] label" with an optional dim hint. */
@Composable
private fun ToggleRow(checked: Boolean, label: String, hint: String?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox, onClickLabel = label) { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VText(if (checked) "[x]" else "[ ]", color = if (checked) HomeTokens.accent else HomeTokens.textDim, size = 13)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            VText(label, size = 12, lines = 2)
            hint?.let { VText(it, color = HomeTokens.textDim, size = 10, lines = 4) }
        }
    }
}

/** A plain underlined field; [plain] shows the text, otherwise it is masked. */
@Composable
private fun PassField(value: String, placeholder: String, plain: Boolean = false, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 14.sp, color = HomeTokens.textPrimary),
        cursorBrush = SolidColor(HomeTokens.accent),
        visualTransformation = if (plain) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (plain) KeyboardType.Text else KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column {
                Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) VText(placeholder, color = HomeTokens.textDim, size = 10)
                    inner()
                }
                Rule()
            }
        },
    )
}

/** The active item carries a 2dp accent rule (top on the bar, left on the rail) — never a pill. */
@Composable
private fun NavItem(tab: StudioTab, selected: Boolean, ruleOnTop: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (selected) HomeTokens.accent else HomeTokens.textDim
    Box(modifier = modifier.heightIn(min = 60.dp).clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() }) {
        if (selected) {
            // matchParentSize: a bare fillMaxHeight() here sized the whole item to the rail's height,
            // so on a wide screen the active tab swallowed every tab below it.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(if (ruleOnTop) Alignment.TopCenter else Alignment.CenterStart)
                        .then(if (ruleOnTop) Modifier.fillMaxWidth().height(2.dp) else Modifier.fillMaxHeight().width(3.dp))
                        .background(HomeTokens.accent),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            VText(tab.label.uppercase(), color = tint, size = 9, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------- card

/**
 * The MINI mode's backup status — what the user needs at a glance, and the one
 * safe action. Restore stays in the maximized page (destructive actions live
 * there, as everywhere else); choosing a folder works from the card because it
 * changes nothing.
 *
 *   Last backup 2h ago · 412 MB · encrypted
 *   Download/PocketShell                         BACK UP NOW
 */
@Composable
internal fun VaultCardHeader(vault: VaultState, nowMs: Long) {
    val pickers = rememberPickers(vault)
    var askPassphrase by remember { mutableStateOf(false) }
    val s = vault.settings
    if (askPassphrase) {
        PassphraseDialog(confirmTwice = true, onDismiss = { askPassphrase = false }, onSubmit = { askPassphrase = false; vault.startBackup(it) })
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        when (val j = vault.job) {
            is VaultRun.Working -> {
                VText("${j.what.uppercase()} · ${j.label}${if (j.bytes > 0) " · ${VaultState.formatSize(j.bytes)}" else ""}", color = HomeTokens.accent, size = 11)
                VAction("CANCEL", danger = true, onClick = vault::cancelCurrent)
            }
            is VaultRun.Finished -> Column(Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss result") { vault.dismissRun() }) {
                VText(j.message, color = if (j.ok) HomeTokens.runningGreen else HomeTokens.danger, size = 11, lines = 3)
            }
            VaultRun.Idle -> {
                val last = s.last
                VText(if (last == null) "No backup yet" else lastBackupText(last, nowMs), color = if (last == null) HomeTokens.textDim else HomeTokens.textPrimary, size = 11, lines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText(s.location?.label ?: "no location chosen", color = HomeTokens.textDim, size = 10, modifier = Modifier.weight(1f))
                    if (s.location == null) {
                        VAction("CHOOSE FOLDER", onClick = pickers.folder)
                    } else {
                        VAction("BACK UP NOW", enabled = !vault.busy, onClick = { if (s.encrypt) askPassphrase = true else vault.startBackup(null) })
                    }
                }
            }
        }
        Rule()
    }
}

/**
 * The card (not the maximized page): the title and the one-line backup status with its BACK UP NOW action.
 * Restore, scope and encryption live on the maximized page. Pure view of the vault state - no probe, no exec.
 */
@Composable
internal fun BackupCard(vault: VaultState, nowMs: Long, ready: Boolean, onOpenDiagnostics: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = SyncApp.spec.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Rule()
        if (!ready) {
            VText("Linux is not ready yet", color = HomeTokens.textDim, size = 11, modifier = Modifier.padding(top = 8.dp))
            VAction("DIAGNOSTICS", onClick = onOpenDiagnostics)
        } else {
            VaultCardHeader(vault, nowMs)
            VText("Open this card for restore, what is included and encryption.", color = HomeTokens.textDim, size = 10, lines = 2)
        }
    }
}

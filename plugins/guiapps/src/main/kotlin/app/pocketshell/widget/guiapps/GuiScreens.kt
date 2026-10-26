package app.pocketshell.widget.guiapps

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.WidgetNav

/** The maximized page: three tabs. Pure presentation - every action is a call on [GuiState]. */

internal val GuiTab.spec: TabSpec
    get() = when (this) {
        GuiTab.DISCOVER -> TabSpec(name, label, Icons.Outlined.Search)
        GuiTab.MINE -> TabSpec(name, label, Icons.Outlined.Dashboard)
        GuiTab.SETUP -> TabSpec(name, label, Icons.Outlined.Settings)
    }

internal fun statusLine(s: Snapshot?): String = when {
    s == null -> "reading…"
    s.sessionReady -> "desktop session ready"
    else -> "no desktop session"
}

internal fun summaryItems(state: GuiState): List<StripItem> {
    val s = state.snapshot
    return listOf(
        StripItem("${state.installedCount()}", "installed"),
        StripItem("${state.catalog.size}", "in library"),
        StripItem("${state.runningCount()}", "running", hot = state.runningCount() > 0),
        StripItem(if (s?.sessionReady == true) "ON" else "off", "desktop", hot = s?.sessionReady == true),
    )
}

internal fun hintFor(state: GuiState): String? {
    val s = state.snapshot ?: return null
    return when {
        state.installedCount() == 0 && state.otherLaunchers().isEmpty() -> "No GUI apps yet - open GUI Apps to install one."
        !s.sessionReady -> "The desktop session is not running - apps need it to show a window."
        else -> null
    }
}

@Composable
internal fun GuiStudio(state: GuiState, sideRail: Boolean, nav: WidgetNav) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    AppShell(
        title = GuiApp.spec.name,
        subtitle = statusLine(state.snapshot),
        tabs = GuiTab.entries.map { it.spec },
        selected = state.tab.name,
        onSelect = { key -> state.tab = GuiTab.valueOf(key); state.detail = null },
        sideRail = sideRail,
        busy = state.loading || state.checkingUpdates || state.ops.values.any { it is OpState.Working },
        banner = state.banner,
        onDismissBanner = { state.dismissBanner() },
        strip = summaryItems(state).mapIndexed { i, item -> item.copy(onClick = { state.tab = if (i == 0) GuiTab.MINE else if (i == 3) GuiTab.SETUP else GuiTab.DISCOVER; state.detail = null }) },
        onTerminal = { nav.openTerminal() },
        onRefresh = { state.reload() },
        refreshEnabled = !state.loading,
    ) {
        when (state.tab) {
            GuiTab.DISCOVER -> DiscoverTab(state) { confirm = it }
            GuiTab.MINE -> MineTab(state, nav) { confirm = it }
            GuiTab.SETUP -> SetupTab(state)
        }
    }
    confirm?.let { c -> ConfirmDialog(c.title, c.message, c.label, c.word, onConfirm = c.action, onDismiss = { confirm = null }) }
}

// ================================================================ DISCOVER

@Composable
private fun DiscoverTab(state: GuiState, onConfirm: (Confirm) -> Unit) {
    Field("Search the library", state.query, { state.query = it }, hint = "browser, editor, GIMP…")
    Row(Modifier.padding(top = 6.dp)) {
        Choices(listOf("All" to null, "Debian" to Runtime.DEB, "AppImage" to Runtime.APPIMAGE), state.runtimeFilter) { state.runtimeFilter = it }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
        Choices(listOf<Pair<String, GuiCategory?>>("Any" to null) + GuiCategory.entries.map { it.label to it }, state.categoryFilter) { state.categoryFilter = it }
    }
    MonoText("Debian = packages from the signed Debian archive. AppImage = prebuilt arm64 builds from the projects' own releases.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
    val apps = state.catalog.filter { a ->
        (state.runtimeFilter == null || a.runtime == state.runtimeFilter) &&
            (state.categoryFilter == null || a.category == state.categoryFilter) &&
            (state.query.isBlank() || a.name.contains(state.query.trim(), true) || a.summary.contains(state.query.trim(), true) || a.id.contains(state.query.trim(), true))
    }.sortedBy { it.name.lowercase() }
    if (apps.isEmpty()) MonoText("Nothing matches.", color = HomeTokens.textDim, modifier = Modifier.padding(top = 12.dp))
    apps.forEach { AppRow(state, it, onConfirm, showRun = false) }
}

// =================================================================== MINE

@Composable
private fun MineTab(state: GuiState, nav: WidgetNav, onConfirm: (Confirm) -> Unit) {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText(if (state.checkingUpdates) "Checking…" else "Check for updates", enabled = !state.checkingUpdates) { state.checkUpdates() }
    }
    val mine = state.catalog.filter { state.isInstalled(it) || state.ops[it.id] != null }.sortedBy { it.name.lowercase() }
    if (state.snapshot != null && mine.isEmpty()) {
        MonoText("Nothing installed from the library yet.", modifier = Modifier.padding(top = 12.dp))
        MonoText("Open DISCOVER and tap an app.", color = HomeTokens.textDim)
    }
    mine.forEach { AppRow(state, it, onConfirm, showRun = true) }

    val others = state.otherLaunchers()
    if (others.isNotEmpty()) {
        SectionLabel("ALSO ON THIS DEVICE")
        MonoText("Launchers found in the Linux guest (installed some other way).", color = HomeTokens.textDim, maxLines = 2)
        others.forEach { d ->
            val id = state.launchId(d)
            val info = state.running[id]
            val open = state.detail == id
            ListRow(
                dot = dotFor(info),
                title = d.entry.name,
                right = d.flavour.label,
                lines = listOfNotNull(d.entry.comment.takeIf { it.isNotBlank() }, info?.let { runLine(it) }),
                onClick = { state.detail = if (open) null else id },
            )
            if (open) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (state.isRunning(id)) ActionText("Stop") { state.stop(id) } else ActionText("Launch") { state.launch(d) }
                    ActionText("Log", enabled = info != null) { state.banner = Banner(state.logOf(id).ifBlank { "The log is empty." }.takeLast(900), false) }
                }
            }
        }
    }
}

// ================================================================== SETUP

@Composable
private fun SetupTab(state: GuiState) {
    val s = state.snapshot
    SectionLabel("THIS DEVICE")
    StatusLine(s?.sessionReady == true, "Desktop session", if (s?.sessionReady == true) "running (psgui)" else "not found - start the PocketShell desktop, then launch an app")
    StatusLine(s?.glibcReady == true, "C library", if (s?.glibcReady == true) "glibc (Debian) - AppImages run natively" else "loader missing - repair Linux in Diagnostics")
    StatusLine(true, "Package manager", "apt (Debian 13)")
    MonoText("Apps connect to the desktop session through WAYLAND_DISPLAY=psgui and DISPLAY=:0, set for you on every launch.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 6.dp))

    SectionLabel("ADD AN APPIMAGE")
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Field("Name", name, { name = it.take(40) }, hint = "My Tool")
    Field("https link to an aarch64 .AppImage", url, { url = it.trim() }, hint = "https://…/Tool-aarch64.AppImage")
    ErrorLine(error)
    Row { ActionText("Download and add") { state.addCustom(name, url) { err -> error = err; if (err == null) { name = ""; url = ""; state.tab = GuiTab.MINE } } } }
    MonoText("Only https. There is no published checksum for a link you paste, so only add files you trust.", color = HomeTokens.textDim, maxLines = 3)

    SectionLabel("TIPS")
    MonoText("A program that will not start: run it in the terminal to see its error; a missing library comes from a Debian package (apt-file search <library> finds which).", color = HomeTokens.textDim, maxLines = 4)
    MonoText("Chromium and Electron apps are started with --no-sandbox --disable-gpu --disable-dev-shm-usage, as the guest requires.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun StatusLine(ok: Boolean, label: String, detail: String) {
    Hairline()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (ok) HomeTokens.runningGreen else HomeTokens.danger)
        Column {
            MonoText(label, fontWeight = FontWeight.SemiBold)
            MonoText(detail, color = HomeTokens.textDim, maxLines = 3)
        }
    }
}

// ================================================================ app rows

private fun dotFor(info: RunInfo?) = when (info?.phase) {
    Phase.RUNNING, Phase.STARTING -> HomeTokens.runningGreen
    Phase.FAILED -> HomeTokens.danger
    else -> HomeTokens.hairline
}

private fun runLine(i: RunInfo): String = when (i.phase) {
    Phase.STARTING -> "starting…"
    Phase.RUNNING -> "running"
    Phase.EXITED -> i.detail ?: "closed"
    Phase.FAILED -> i.detail ?: "failed"
}

@Composable
private fun AppRow(state: GuiState, app: CatalogApp, onConfirm: (Confirm) -> Unit, showRun: Boolean) {
    val installed = state.isInstalled(app)
    val op = state.ops[app.id]
    val info = state.running[app.id]
    val update = state.hasUpdate(app)
    val open = state.detail == app.id
    val lines = buildList {
        add(app.summary)
        when {
            op is OpState.Working -> add(op.text)
            op is OpState.Failed -> add("failed: ${op.reason}")
            installed -> add("installed ${state.installedVersion(app).orEmpty()}" + (update?.let { "  -  update: $it" } ?: ""))
        }
        if (info != null && showRun) add(runLine(info))
    }
    ListRow(
        dot = if (installed) dotFor(info) else null,
        title = app.name,
        right = (if (app.heavy) "large  " else "") + app.runtime.label,
        lines = lines,
        onClick = { state.detail = if (open) null else app.id },
        lineColor = if (op is OpState.Failed) HomeTokens.danger else HomeTokens.textDim,
    )
    (op as? OpState.Working)?.fraction?.let { ProgressRule(it) }
    if (!open) return
    val busy = op is OpState.Working
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!installed) {
            ActionText(if (busy) "Working…" else "Install", enabled = !busy) { state.install(app) }
            if (busy && app.runtime == Runtime.APPIMAGE) ActionText("Cancel", danger = true) { state.cancelInstall(app.id) }
        } else {
            if (state.isRunning(app.id)) ActionText("Stop") { state.stop(app.id) } else ActionText("Launch", enabled = !busy) { state.launch(app) }
            if (update != null) ActionText("Update", enabled = !busy) { state.update(app) }
            ActionText("Log", enabled = info != null) { state.banner = Banner(state.logOf(app.id).ifBlank { "The log is empty." }.takeLast(900), false) }
            ActionText("Remove", danger = true, enabled = !busy) {
                onConfirm(Confirm("Remove ${app.name}?", if (app.runtime == Runtime.DEB) "The Debian package ${app.pkg} is removed. Its settings in your home folder stay." else "The downloaded file is deleted. Its settings in your home folder stay.", "Remove") { state.uninstall(app) })
            }
        }
        if (op is OpState.Failed) ActionText("Dismiss") { state.dismissOp(app.id) }
    }
    val source = if (app.runtime == Runtime.DEB) "apt-get install ${listOf(app.pkg!!) + app.extraPkgs}".replace("[", "").replace("]", "").replace(",", "")
    else app.directUrl ?: "github.com/${app.repo} (latest release${if (app.delivery == Delivery.TARBALL_WEB) ", opens in the browser" else ""})"
    MonoText(source, color = HomeTokens.textDim, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
}

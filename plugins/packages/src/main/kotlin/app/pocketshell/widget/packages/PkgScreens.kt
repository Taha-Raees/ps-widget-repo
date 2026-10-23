package app.pocketshell.widget.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** The maximized page: four tabs. Pure presentation - every action is a call on [PkgState]. */

internal val PkgTab.spec: TabSpec
    get() = when (this) {
        PkgTab.INSTALLED -> TabSpec(name, label, Icons.Outlined.Archive)
        PkgTab.ADD -> TabSpec(name, label, Icons.Outlined.Add)
        PkgTab.UPDATES -> TabSpec(name, label, Icons.Outlined.Sync)
        PkgTab.SYSTEM -> TabSpec(name, label, Icons.Outlined.Settings)
    }

internal fun statusLine(f: EnvFacts?): String = if (f == null) "reading…" else "${f.osName} ${f.osVersion} · ${f.arch}"

internal fun summaryItems(inv: Inventory?, updates: List<Upgradable>?): List<StripItem> = listOf(
    StripItem("${inv?.installed?.size ?: 0}", "packages"),
    StripItem("${inv?.world?.size ?: 0}", "added"),
    StripItem(updates?.size?.toString() ?: "-", "updates", hot = (updates?.size ?: 0) > 0),
    StripItem(Fmt.bytes(inv?.cacheBytes ?: 0).replace(" ", ""), "cache", hot = (inv?.cacheBytes ?: 0) > CACHE_HINT_BYTES),
)

internal const val CACHE_HINT_BYTES = 300L * 1024 * 1024

internal fun hintFor(inv: Inventory?): String? = when {
    inv == null -> null
    inv.cacheBytes > CACHE_HINT_BYTES -> "The package cache holds ${Fmt.bytes(inv.cacheBytes)} - open Packages > System to clear it."
    else -> null
}

@Composable
internal fun PkgStudio(state: PkgState, sideRail: Boolean, nav: WidgetNav) {
    AppShell(
        title = PkgApp.spec.name,
        subtitle = statusLine(state.facts),
        tabs = PkgTab.entries.map { it.spec },
        selected = state.tab.name,
        onSelect = { key -> state.tab = PkgTab.valueOf(key); state.detail = null },
        sideRail = sideRail,
        busy = state.loading || state.busy != null,
        banner = state.banner,
        onDismissBanner = { state.dismissBanner() },
        strip = summaryItems(state.inventory, state.updates).mapIndexed { i, item ->
            item.copy(onClick = { state.tab = when (i) { 2 -> PkgTab.UPDATES; 3 -> PkgTab.SYSTEM; else -> PkgTab.INSTALLED }; state.detail = null })
        },
        onTerminal = { nav.openTerminal() },
        onRefresh = { state.reload() },
        refreshEnabled = !state.loading,
    ) {
        state.busy?.let { MonoText(it, color = HomeTokens.accent, modifier = Modifier.padding(top = 6.dp)) }
        when (state.tab) {
            PkgTab.INSTALLED -> InstalledTab(state)
            PkgTab.ADD -> AddTab(state)
            PkgTab.UPDATES -> UpdatesTab(state)
            PkgTab.SYSTEM -> SystemTab(state)
        }
        state.lastRun?.let { RunPanel(it) { state.dismissRun() } }
    }
    state.pending?.let { PlanDialog(it, onApply = { state.applyPending() }, onDismiss = { state.dismissPending() }) }
    state.info?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { state.dismissInfo() },
            title = { Text(name) },
            text = { MonoText(text, maxLines = 40) },
            confirmButton = { TextButton(onClick = { state.dismissInfo() }) { Text("Close", color = HomeTokens.accent) } },
        )
    }
}

@Composable
private fun RunPanel(run: RunLog, onDismiss: () -> Unit) {
    SectionLabel("LAST RUN")
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        MonoText(run.title + if (run.ok) " - ok" else " - failed", color = if (run.ok) HomeTokens.runningGreen else HomeTokens.danger, modifier = Modifier.weight(1f))
        ActionText("Hide", onClick = onDismiss)
    }
    MonoText(run.text.ifBlank { "(no output)" }, color = HomeTokens.textDim, maxLines = 14)
}

// ============================================================== plan dialog

@Composable
private fun PlanDialog(p: Pending, onApply: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ready = p.refusal == null && !p.plan.isEmpty && (p.typeWord == null || typed.trim() == p.typeWord)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.title) },
        text = {
            Column {
                p.refusal?.let { MonoText(it, color = HomeTokens.danger, maxLines = 5) }
                if (p.plan.installs.isNotEmpty()) PlanList("INSTALL ${p.plan.installs.size}", p.plan.installs.map { "${it.name} ${it.version}" })
                if (p.plan.upgrades.isNotEmpty()) PlanList("UPDATE ${p.plan.upgrades.size}", p.plan.upgrades.map { "${it.name} ${it.from} -> ${it.version}" })
                if (p.plan.removals.isNotEmpty()) PlanList("REMOVE ${p.plan.removals.size}", p.plan.removals.map { it.name })
                p.deltaBytes?.let { MonoText("Disk: ${Fmt.signed(it)}", color = HomeTokens.textPrimary, modifier = Modifier.padding(top = 8.dp)) }
                p.stays.forEach { (name, by) ->
                    MonoText("$name stays installed - still needed by ${by.take(6).joinToString(", ")}${if (by.size > 6) " +${by.size - 6}" else ""}.", color = HomeTokens.textDim, maxLines = 4, modifier = Modifier.padding(top = 8.dp))
                }
                if (p.typeWord != null && p.refusal == null) Field("Type \"${p.typeWord}\" to confirm", typed, { typed = it })
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = onApply) {
                Text(if (p.op == ChangeOp.DEL) "Remove" else "Apply", color = if (!ready) HomeTokens.textDim else if (p.op == ChangeOp.DEL) HomeTokens.danger else HomeTokens.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

@Composable
private fun PlanList(label: String, items: List<String>) {
    MonoText(label, color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
    items.take(12).forEach { MonoText(it, maxLines = 2) }
    if (items.size > 12) MonoText("+ ${items.size - 12} more", color = HomeTokens.textDim)
}

// ================================================================ INSTALLED

@Composable
private fun InstalledTab(state: PkgState) {
    val inv = state.inventory
    Row(Modifier.padding(top = 6.dp)) { Choices(Scope.entries.map { it.label to it }, state.showScope) { state.showScope = it } }
    Field("Filter", state.filter, { state.filter = it }, hint = "name or words in the description")
    if (inv == null) { MonoText("Reading the package database…", color = HomeTokens.textDim, modifier = Modifier.padding(top = 8.dp)); return }
    val list = state.visible()
    MonoText("${list.size} of ${inv.installed.size} packages", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    if (state.showScope == Scope.ADDED) MonoText("Packages you asked for. The rest are libraries they need - switch to Everything to see them.", color = HomeTokens.textDim, maxLines = 3)
    list.take(200).forEach { p ->
        val open = state.detail == p.name
        val up = state.upgradeFor(p.name)
        ListRow(
            dot = null, title = p.name, right = Fmt.bytes(p.installedBytes),
            lines = listOfNotNull(p.version + (up?.let { "  ->  ${it.to}" } ?: ""), p.description.takeIf { it.isNotBlank() }),
            onClick = { state.detail = if (open) null else p.name },
        )
        if (open) {
            val by = inv.requiredBy[p.name].orEmpty()
            if (by.isNotEmpty()) MonoText("needed by ${by.take(8).joinToString(", ")}${if (by.size > 8) " +${by.size - 8}" else ""}", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(start = 4.dp))
            p.url?.let { MonoText(it, color = HomeTokens.textDim, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp)) }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (up != null) ActionText("Update", enabled = state.busy == null) { state.upgrade(p.name) }
                ActionText("Info", enabled = state.busy == null) { state.showInfo(p.name) }
                ActionText("Remove", danger = true, enabled = state.busy == null) { state.remove(p.name) }
            }
        }
    }
    if (list.size > 200) MonoText("Showing the first 200 - narrow the filter.", color = HomeTokens.textDim, modifier = Modifier.padding(top = 8.dp))
}

// ===================================================================== ADD

@Composable
private fun AddTab(state: PkgState) {
    val inv = state.inventory
    val installed = inv?.byName?.keys.orEmpty()

    SectionLabel("SEARCH THE REPOSITORIES")
    Field("Package or words", state.searchQuery, { state.searchQuery = it }, hint = "ripgrep, text editor, python…")
    Row { ActionText("Search", enabled = state.busy == null) { state.search() } }
    val hits = state.hits
    if (hits != null && hits.isEmpty()) MonoText("No package matches.", color = HomeTokens.textDim)
    hits?.forEach { h ->
        val have = h.name in installed
        ListRow(
            dot = null, title = h.name, right = if (have) "installed" else h.version,
            lines = listOf(h.description.ifBlank { h.version }), onClick = { state.detail = if (state.detail == "hit:${h.name}") null else "hit:${h.name}" },
        )
        if (state.detail == "hit:${h.name}") Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!have) ActionText("Install", enabled = state.busy == null) { state.install(listOf(h.name)) }
            ActionText("Info", enabled = state.busy == null) { state.showInfo(h.name) }
        }
    }

    SectionLabel("ADD BY NAME")
    Field("One or more package names", state.addText, { state.addText = it }, hint = "git curl jq")
    Row {
        ActionText("Preview and install", enabled = state.busy == null && state.addText.isNotBlank()) {
            val names = PkgNames.parseList(state.addText)
            if (names == null) state.banner = Banner("Package names only (letters, digits, . _ + -), at most ${PkgNames.MAX_AT_ONCE}", true)
            else state.install(names)
        }
    }

    SectionLabel("TOOL BUNDLES")
    MonoText("Command-line toolkits in one tap. You always see what changes before anything is installed.", color = HomeTokens.textDim, maxLines = 2)
    Bundles.all.forEach { b ->
        val missing = Bundles.missing(b, installed)
        ListRow(
            dot = null, title = b.name, right = if (missing.isEmpty()) "installed" else "${b.packages.size - missing.size}/${b.packages.size}",
            lines = listOf(b.summary), onClick = { state.detail = if (state.detail == "b:${b.id}") null else "b:${b.id}" },
        )
        if (state.detail == "b:${b.id}") {
            MonoText(b.packages.joinToString(" "), color = HomeTokens.textDim, fontSize = 10.sp, maxLines = 4, modifier = Modifier.padding(start = 4.dp))
            if (missing.isNotEmpty()) Row(Modifier.padding(start = 4.dp)) { ActionText("Install ${missing.size} missing", enabled = state.busy == null && inv != null) { state.installBundle(b) } }
        }
    }
}

// ================================================================= UPDATES

@Composable
private fun UpdatesTab(state: PkgState) {
    val list = state.updates
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText("Check for updates", enabled = state.busy == null) { state.checkUpdates() }
        ActionText("Update all", enabled = state.busy == null && !list.isNullOrEmpty()) { state.upgradeAll() }
    }
    MonoText(
        state.updatesCheckedAtMs?.let { "checked ${ageText(System.currentTimeMillis() - it)}" } ?: "Package index refreshed ${ageText(state.facts?.indexAgeMs)}",
        color = HomeTokens.textDim, fontSize = 10.sp,
    )
    if (list == null) { MonoText("Tap CHECK to refresh the index and list what can be updated.", color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(top = 8.dp)); return }
    if (list.isEmpty()) { MonoText("Everything is up to date.", modifier = Modifier.padding(top = 12.dp)); return }
    list.forEach { u ->
        ListRow(dot = null, title = u.name, right = null, lines = listOf("${u.from}  ->  ${u.to}"), onClick = { state.detail = if (state.detail == u.name) null else u.name })
        if (state.detail == u.name) Row(Modifier.padding(start = 4.dp)) { ActionText("Update", enabled = state.busy == null) { state.upgrade(u.name) } }
    }
}

// =================================================================== SYSTEM

@Composable
private fun SystemTab(state: PkgState) {
    val f = state.facts
    val inv = state.inventory
    SectionLabel("LINUX")
    if (f == null) { MonoText("Reading…", color = HomeTokens.textDim); return }
    FactRow("Distribution", "${f.osName} ${f.osVersion}")
    FactRow("Architecture", f.arch)
    if (f.kernel.isNotBlank()) FactRow("Kernel", f.kernel)
    f.appVersion?.let { FactRow("PocketShell layer", it) }
    FactRow("glibc layer", f.glibc ?: "not installed")
    f.devtools?.let { FactRow("Dev tools layer", it) }

    SectionLabel("PACKAGES")
    FactRow("Installed", "${inv?.installed?.size ?: 0} packages, ${Fmt.bytes(inv?.totalBytes ?: 0)}")
    FactRow("Added by you", "${inv?.world?.size ?: 0}")
    FactRow("Package cache", Fmt.bytes(inv?.cacheBytes ?: 0))
    FactRow("Index refreshed", ageText(f.indexAgeMs))

    SectionLabel("REPOSITORIES")
    if (f.repos.isEmpty()) MonoText("None configured - packages cannot be installed.", color = HomeTokens.danger, maxLines = 2)
    f.repos.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (!r.enabled) HomeTokens.hairline else if (r.secure) HomeTokens.runningGreen else HomeTokens.accent)
            MonoText(r.label + if (!r.enabled) "  (off)" else if (!r.secure) "  (not https)" else "", color = if (r.enabled) HomeTokens.textPrimary else HomeTokens.textDim, maxLines = 2)
        }
    }

    SectionLabel("MAINTENANCE")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText("Refresh index", enabled = state.busy == null) { state.refreshIndex() }
        ActionText("Repair", enabled = state.busy == null) { state.repair() }
        ActionText("Clear cache", enabled = state.busy == null && (inv?.cacheBytes ?: 0) > 0) { state.clearCache() }
    }
    MonoText("Clear cache deletes downloaded package files (${Fmt.bytes(inv?.cacheBytes ?: 0)}). Installed software is not touched; apk downloads again when it needs a file.", color = HomeTokens.textDim, maxLines = 4)
    MonoText("Repair runs apk fix: it re-installs files of packages that are damaged or half-installed.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FactRow(label: String, value: String) {
    Hairline()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        MonoText(label, color = HomeTokens.textDim, modifier = Modifier.weight(0.45f))
        MonoText(value, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(0.55f))
    }
}

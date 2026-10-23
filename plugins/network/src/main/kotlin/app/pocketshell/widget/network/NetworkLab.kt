package app.pocketshell.widget.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.WidgetNav

/**
 * The two Home Lab tabs. LAB = who is on this network and are my servers healthy;
 * TOOLS = the small diagnostics (connectivity, DNS, ping, traceroute, ports).
 * Pure presentation: every action is a call on [NetworkState]; nothing here touches a socket or a process.
 */

private fun healthColor(h: Health) = when (h) {
    Health.UP -> HomeTokens.runningGreen
    Health.SLOW -> HomeTokens.accent
    Health.ERROR, Health.DOWN -> HomeTokens.danger
    Health.UNKNOWN -> HomeTokens.hairline
}

internal fun vitalsLine(v: Vitals): String = listOfNotNull(
    v.load1?.let { "load %.2f".format(it) },
    v.memUsedPercent?.let { "mem $it%" },
    v.diskUsedPercent?.let { "disk $it%" },
    v.uptime,
).joinToString(" · ")

internal fun portsLine(open: List<Int>): String =
    if (open.isEmpty()) "no common ports open" else open.joinToString(" · ") { p -> PortSpec.serviceName(p)?.let { "$p $it" } ?: "$p" }

// ===================================================================== LAB

@Composable
internal fun LabTab(state: NetworkState, nav: WidgetNav, onForm: (LabServer?) -> Unit, onConfirm: (String, String, String, () -> Unit) -> Unit) {
    val context = LocalContext.current

    SectionLabel("DEVICES ON THIS NETWORK")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.scanning) ActionText("Stop") { state.cancelScan() } else ActionText("Scan network") { state.scanDevices() }
        state.scanProgress?.let { (d, t) -> MonoText("$d/$t", color = HomeTokens.textDim, fontSize = 10.sp) }
    }
    val devices = state.devices
    if (devices == null) {
        MonoText("Looks for live devices on your Wi-Fi (about 20 seconds). It tries a few ports on each address — it sends nothing else.", color = HomeTokens.textDim, maxLines = 4)
    } else if (devices.isEmpty() && !state.scanning) {
        MonoText("Nothing answered. A phone on mobile data has no local network; a firewalled device stays silent.", color = HomeTokens.textDim, maxLines = 4)
    }
    devices?.forEach { d ->
        val open = state.detail == "dev:${d.ip}"
        Hairline()
        Column(Modifier.fillMaxWidth().clickable { state.detail = if (open) null else "dev:${d.ip}" }.padding(vertical = if (LocalNetFull.current) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(d.ip, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MonoText(PortSpec.guess(d.open.toSet()), color = HomeTokens.textDim, fontSize = 10.sp)
            }
            d.name?.let { MonoText(it, color = HomeTokens.textPrimary) }
            MonoText(portsLine(d.open), color = HomeTokens.textDim, maxLines = 2)
            d.mac?.let { MonoText(it, color = HomeTokens.textDim, fontSize = 10.sp) }
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Ping") { state.tab = NetTab.TOOLS; state.runPing(d.ip) }
                ActionText("Ports") { state.tab = NetTab.TOOLS; state.runPorts(d.ip, PortSpec.COMMON.joinToString(",")) }
                ActionText("Save") {
                    val kind = when { 443 in d.open -> LabKind.HTTPS; 80 in d.open || 8080 in d.open -> LabKind.HTTP; else -> LabKind.TCP }
                    val port = when (kind) { LabKind.HTTPS -> 443; LabKind.HTTP -> if (80 in d.open) 80 else 8080; LabKind.TCP -> d.open.firstOrNull() ?: 22 }
                    onForm(LabServer(LabServers.newId(), d.name?.substringBefore('.')?.take(40) ?: d.ip, d.ip, port, kind))
                }
                ActionText("Copy IP") { copyToClipboard(context, "ip", d.ip); state.banner = Banner("Copied ${d.ip}", false) }
            }
        }
    }

    SectionLabel("LAB SERVERS")
    val servers = state.local?.lab.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionText("Add server") { onForm(null) }
        ActionText("Check all", enabled = servers.isNotEmpty()) { state.checkAllServers() }
        if (servers.isNotEmpty()) MonoText(LabHealth.summary(servers.map { state.health[it.id] }), color = HomeTokens.textDim, fontSize = 10.sp)
    }
    if (state.local != null && servers.isEmpty()) {
        MonoText("No servers yet.", modifier = Modifier.padding(top = 10.dp))
        MonoText("Add your NAS, Pi-hole or Home Assistant - or tap SAVE on a scanned device. Health is a TCP connect, plus one HTTP request for web servers.", color = HomeTokens.textDim, maxLines = 4)
    }
    servers.forEach { s ->
        val reading = state.health[s.id]
        val open = state.detail == "srv:${s.id}"
        Hairline()
        Column(Modifier.fillMaxWidth().clickable { state.detail = if (open) null else "srv:${s.id}" }.padding(vertical = if (LocalNetFull.current) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(healthColor(reading?.health ?: Health.UNKNOWN))
                MonoText(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                MonoText(if (s.id in state.healthChecking) "checking…" else (reading?.health ?: Health.UNKNOWN).label, color = HomeTokens.textDim, fontSize = 10.sp)
            }
            MonoText("${s.host}:${s.port} · ${s.kind.label}", color = HomeTokens.textDim, modifier = Modifier.padding(start = 16.dp))
            reading?.let { r -> MonoText(listOfNotNull(r.detail, r.millis?.let { "$it ms" }).joinToString(" · "), color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(start = 16.dp)) }
            state.vitals[s.id]?.let { MonoText(vitalsLine(it), color = HomeTokens.textPrimary, maxLines = 2, modifier = Modifier.padding(start = 16.dp)) }
            if (s.id in state.vitals && state.vitals[s.id] == null) MonoText("ssh to ${s.sshAlias} did not answer (trust the host key in HOSTS first)", color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(start = 16.dp))
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Check", enabled = s.id !in state.healthChecking) { state.checkServer(s) }
                LabServers.url(s)?.let { url -> ActionText("Open") { nav.openCompanion(url) } }
                if (s.sshAlias.isNotEmpty()) ActionText("SSH") {
                    val typed = HostCompat.typeInTerminal(nav, "ssh ${s.sshAlias}")
                    if (!typed) copyToClipboard(context, "ssh", "ssh ${s.sshAlias}")
                    state.banner = Banner(if (typed) "Typed at the prompt: ssh ${s.sshAlias} - press Enter" else "Copied: ssh ${s.sshAlias}", false)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Edit") { onForm(s) }
                ActionText("Delete", danger = true) { onConfirm("Remove ${s.name}?", "Only the saved entry is removed.", "Remove") { state.deleteLabServer(s.id) } }
            }
        }
    }
}

// =================================================================== TOOLS

private val DNS_TYPES = Dns.TYPES.keys.map { it to it }

@Composable
internal fun ToolsTab(state: NetworkState, onChecks: @Composable () -> Unit) {
    var host by remember { mutableStateOf("") }
    var dnsType by remember { mutableStateOf("A") }
    var dnsServer by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("22,80,443") }
    val busy = state.toolRunning != null

    SectionLabel("LOOK UP / REACH A HOST")
    Field("Hostname or IPv4 address", host, { host = it.trim() }, hint = "example.com or 192.168.1.1")
    MonoText("RECORD", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
    Choices(DNS_TYPES, dnsType) { dnsType = it }
    Field("DNS server (empty = the Linux one)", dnsServer, { dnsServer = it.trim() }, hint = "1.1.1.1")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
        ActionText("DNS lookup", enabled = !busy && host.isNotEmpty()) { state.runDns(host, dnsType, dnsServer) }
        ActionText("Ping", enabled = !busy && host.isNotEmpty()) { state.runPing(host) }
        ActionText("Traceroute", enabled = !busy && host.isNotEmpty()) { state.runTrace(host) }
    }
    Field("Ports (22, 80, 8000-8010)", ports, { ports = it }, hint = "22,80,443")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionText("Check ports", enabled = !busy && host.isNotEmpty()) { state.runPorts(host, ports) }
        ActionText("Common ports", enabled = !busy && host.isNotEmpty()) { state.runPorts(host, PortSpec.COMMON.joinToString(",")) }
    }
    if (busy) MonoText("${state.toolRunning}…", color = HomeTokens.textDim, modifier = Modifier.padding(top = 6.dp))
    state.toolResult?.let { ToolResultView(it) }

    SectionLabel("CONNECTIVITY")
    onChecks()
}

@Composable
private fun ToolResultView(result: ToolResult) {
    Hairline(Modifier.padding(top = 8.dp))
    when (result) {
        is ToolResult.DnsResult -> {
            MonoText("${result.type} ${result.name} @ ${result.server}", fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            when (val l = result.lookup) {
                null -> MonoText("No answer from ${result.server}.", color = HomeTokens.danger)
                DnsLookup.Malformed -> MonoText("The answer could not be understood.", color = HomeTokens.danger)
                is DnsLookup.Error -> MonoText(Checks.rcodeName(l.rcode), color = HomeTokens.danger)
                is DnsLookup.Records -> {
                    if (l.records.isEmpty()) MonoText("The name exists but has no ${result.type} records.", color = HomeTokens.textDim, maxLines = 2)
                    l.records.forEach { r -> MonoText("${r.type}  ${r.value}  (ttl ${r.ttl})", maxLines = 3) }
                    result.millis?.let { MonoText("$it ms", color = HomeTokens.textDim, fontSize = 10.sp) }
                }
            }
        }
        is ToolResult.PingResult -> {
            MonoText("ping ${result.host}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            when (val o = result.outcome) {
                is PingOutcome.CannotRun -> MonoText(o.reason, color = HomeTokens.danger, maxLines = 4)
                is PingOutcome.Done -> {
                    val s = o.stats
                    MonoText("${s.received}/${s.sent} answered · ${s.lossPercent}% loss", color = if (s.received == 0) HomeTokens.danger else HomeTokens.runningGreen)
                    if (s.avgMs != null) MonoText("min %.1f · avg %.1f · max %.1f ms".format(s.minMs ?: 0.0, s.avgMs, s.maxMs ?: 0.0))
                    MonoText("via ${o.via}", color = HomeTokens.textDim, fontSize = 10.sp, maxLines = 2)
                }
            }
        }
        is ToolResult.TraceResult -> {
            MonoText("traceroute ${result.host}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            val hops = result.hops
            if (hops == null) MonoText("Traceroute needs raw sockets, which this Linux does not allow. tracepath works without them: install it from Packages (Add > Network tools) or run  apk add iputils-tracepath  and try again.", color = HomeTokens.danger, maxLines = 6)
            else {
                hops.forEach { h -> MonoText("%2d  %s  %s".format(h.n, h.addr ?: "*", h.ms?.let { "%.1f ms".format(it) } ?: ""), maxLines = 1) }
                MonoText("via ${result.tool}", color = HomeTokens.textDim, fontSize = 10.sp)
            }
        }
        is ToolResult.PortsResult -> {
            MonoText("ports on ${result.host}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            val open = result.results.filterValues { it == NetFacts.Probe.OPEN }.keys.sorted()
            val refused = result.results.count { it.value == NetFacts.Probe.REFUSED }
            val silent = result.results.count { it.value == NetFacts.Probe.SILENT }
            MonoText(if (open.isEmpty()) "No port is open." else "OPEN  " + portsLine(open), color = if (open.isEmpty()) HomeTokens.textDim else HomeTokens.runningGreen, maxLines = 6)
            MonoText("$refused closed (host answered) · $silent filtered or no host", color = HomeTokens.textDim, fontSize = 10.sp, maxLines = 2)
        }
    }
}

// ============================================================== SERVER FORM

@Composable
internal fun LabServerFormDialog(initial: LabServer?, hostAliases: List<String>, onSave: (LabServer, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: LabKind.HTTP) }
    var port by remember { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var path by remember { mutableStateOf(initial?.path ?: "/") }
    var alias by remember { mutableStateOf(initial?.sshAlias.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null || initial.name.isEmpty()) "Add server" else "Edit ${initial.name}") },
        text = {
            Column {
                Field("Name", name, { name = it.take(40) }, hint = "Pi-hole")
                Field("Host or address", host, { host = it.trim() }, hint = "192.168.1.2")
                Choices(LabKind.entries.map { it.label to it }, kind) { kind = it; if (port.isEmpty()) port = it.defaultPort.toString() }
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, number = true, hint = kind.defaultPort.toString())
                if (kind != LabKind.TCP) Field("Path", path, { path = it.trim() }, hint = "/")
                Field("SSH host for load / memory / disk (optional)", alias, { alias = it.trim() }, hint = "pi")
                if (hostAliases.isNotEmpty()) Row(Modifier.padding(top = 4.dp)) { hostAliases.take(4).forEach { a -> ActionText(a) { alias = a } } }
                error?.let { MonoText(it, color = HomeTokens.danger, maxLines = 3, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = LabServer(initial?.id ?: LabServers.newId(), name.trim(), host.trim(), port.toIntOrNull() ?: kind.defaultPort, kind, if (kind == LabKind.TCP) "/" else path.ifEmpty { "/" }, alias.trim())
                val problem = LabServers.problem(s)
                if (problem != null) error = problem else onSave(s) { err -> if (err == null) onDismiss() else error = err }
            }) { Text("Save", color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

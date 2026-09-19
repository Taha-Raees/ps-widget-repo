package repo.hello

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeAppSpec
import app.pocketshell.widget.plugin.WidgetPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ps-widget-repo's PILOT plugin (M8.5): proves the whole loop —
 * source here → build → publish → app downloads, verifies, loads →
 * this widget renders on Home with full theme support. A tap on the
 * card re-stamps the load time, showing the plugin's OWN compose code
 * is alive inside the Home surface. The plan is for this widget to become
 * the app's WELCOME/onboarding guide — app tours, the plugin-catalog
 * guide and the companion/agent/appearance walkthroughs ship here.
 */
class HelloPlugin : WidgetPlugin {

    override val pluginId: String = "hello"
    override val pluginVersion: String = "1.0.0"

    override fun create(androidContext: Context): HomeApplication = HelloApp()
}

internal class HelloApp : HomeApplication() {

    override val spec = HomeAppSpec(
        id = "hello",
        name = "Hello",
        summary = "The first plugin widget — built from ps-widget-repo",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        var stamp by remember { mutableStateOf("—") }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = "Hello",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = HomeTokens.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "plugin 1.0.0",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.textDim,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This widget is not part of the app build. Its code " +
                    "was compiled in ps-widget-repo, downloaded over HTTPS, " +
                    "verified against the catalog hash and loaded into this " +
                    "card.",
                fontFamily = TerminalTheme.mono,
                fontSize = 12.sp,
                color = HomeTokens.textPrimary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                text = "Loaded: $stamp",
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.accent,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            }) {
                Text("Stamp now", color = HomeTokens.accent)
            }
        }
    }
}

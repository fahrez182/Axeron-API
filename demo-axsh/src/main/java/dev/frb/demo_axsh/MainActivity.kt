package dev.frb.demo_axsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.frb.demo_axsh.ui.theme.AxeronAPITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxeronAPITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** A test's state on screen: not run yet (no entry), running, or its outcome and duration. */
private sealed interface TestState {
    data object Running : TestState
    data class Done(val outcome: Outcome, val millis: Long) : TestState
}

private suspend fun runTest(test: AxshTest): TestState.Done = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    val outcome = try {
        test.run()
    } catch (e: Exception) {
        Outcome(false, e.message ?: e.javaClass.simpleName, e.stackTraceToString())
    }
    TestState.Done(outcome, System.currentTimeMillis() - start)
}

@Composable
private fun TestScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val states = remember { mutableStateMapOf<Int, TestState>() }
    var runningAll by remember { mutableStateOf(false) }
    var runner by remember { mutableStateOf(AxshRunner.runner) }
    // Bumped after each run, so the cached-shell line reads libsu's state again.
    var runs by remember { mutableStateOf(0) }

    fun run(index: Int, scope: CoroutineScope) = scope.launch {
        states[index] = TestState.Running
        states[index] = runTest(axshTests[index])
        runs++
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ModeCard(
                runningAll = runningAll,
                states = states.values.toList(),
                runner = runner,
                runs = runs,
                onRunner = {
                    // Results from the other runner would only confuse the comparison.
                    AxshRunner.runner = it
                    runner = it
                    states.clear()
                },
                onCloseCachedShell = {
                    AxshRunner.closeCachedShell()
                    runs++
                },
                onRunAll = {
                    runningAll = true
                    scope.launch {
                        states.clear()
                        // One after another: timings stay comparable and the permission dialog
                        // (if any) is answered once, by the first test.
                        axshTests.indices.forEach { i ->
                            states[i] = TestState.Running
                            states[i] = runTest(axshTests[i])
                        }
                        runningAll = false
                        runs++
                    }
                },
            )
        }
        itemsIndexed(axshTests) { index, test ->
            if (index == 0 || axshTests[index - 1].group != test.group) {
                Text(
                    test.group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }
            TestCard(test, states[index], enabled = !runningAll) { run(index, scope) }
        }
        item { CustomCommandCard() }
    }
}

@Composable
private fun ModeCard(
    runningAll: Boolean,
    states: List<TestState>,
    runner: Runner,
    runs: Int,
    onRunner: (Runner) -> Unit,
    onCloseCachedShell: () -> Unit,
    onRunAll: () -> Unit,
) {
    val context = LocalContext.current
    val direct = Application.directConnection
    val done = states.filterIsInstance<TestState.Done>()

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("axsh demo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("axsh_direct_connection", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text(
                        if (direct) "Own libaxsh.so with the in-process broker: the binder is fetched once."
                        else "Manager's libaxsh.so, no broker: every shell starts a bootstrap, as in Termux.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = direct,
                    enabled = !runningAll,
                    onCheckedChange = { Application.switchDirectConnection(context, it) },
                )
            }
            Text(
                Application.axsh?.absolutePath ?: "libaxsh.so not found: is AxManager installed?",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Switching restarts the app. Turn the manager's keep-alive for this app off to see the " +
                        "bootstrap cost on every run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Runner", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Runner.entries.forEach {
                    FilterChip(
                        selected = runner == it,
                        onClick = { onRunner(it) },
                        enabled = !runningAll,
                        label = { Text(it.label) },
                    )
                }
            }
            Text(
                runner.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runner == Runner.LIBSU) {
                // runs: read again after every run, as libsu starts the shell on first use.
                val alive = remember(runs) { AxshRunner.cachedShellAlive }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (alive) "Cached shell: alive" else "Cached shell: not started",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCloseCachedShell, enabled = alive && !runningAll) {
                        Text("Close it")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRunAll, enabled = !runningAll && Application.axsh != null) {
                    Text(if (runningAll) "Running…" else "Run all")
                }
                Spacer(Modifier.width(12.dp))
                if (done.isNotEmpty()) {
                    val passed = done.count { it.outcome.passed == true }
                    val failed = done.count { it.outcome.passed == false }
                    val info = done.size - passed - failed
                    Text(
                        "$passed pass · $failed fail · $info info · ${done.sumOf { it.millis }}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestCard(test: AxshTest, state: TestState?, enabled: Boolean, onRun: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val done = state as? TestState.Done

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = done?.outcome?.detail?.isNotEmpty() == true) { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    test.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when (state) {
                    TestState.Running -> CircularProgressIndicator(Modifier.padding(4.dp).width(20.dp), strokeWidth = 2.dp)
                    is TestState.Done -> StatusPill(state.outcome.passed)
                    null -> Unit
                }
                TextButton(onClick = onRun, enabled = enabled && state != TestState.Running) { Text("Run") }
            }
            Text(
                test.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (done != null) {
                Text(
                    "${done.outcome.summary}  ·  ${done.millis}ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                if (expanded && done.outcome.detail.isNotEmpty()) OutputBox(done.outcome.detail)
            }
        }
    }
}

@Composable
private fun StatusPill(passed: Boolean?) {
    val (label, color) = when (passed) {
        true -> "PASS" to Color(0xFF2E7D32)
        false -> "FAIL" to MaterialTheme.colorScheme.error
        null -> "INFO" to MaterialTheme.colorScheme.tertiary
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun OutputBox(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun CustomCommandCard() {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("axtest ping") }
    var result by remember { mutableStateOf<AxshResult?>(null) }
    var running by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Custom command", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        result = withContext(Dispatchers.IO) { AxshRunner.run(command) }
                        running = false
                    }
                },
                enabled = !running && command.isNotBlank() && Application.axsh != null,
            ) { Text(if (running) "Running…" else "Run") }
            result?.let { r ->
                OutputBox(buildString {
                    r.error?.let { appendLine("error: $it") }
                    appendLine("exit=${r.exitCode}  ${r.millis}ms")
                    if (r.stdout.isNotEmpty()) append(r.stdout.trimEnd().take(20_000)).appendLine()
                    if (r.stderr.isNotEmpty()) append("--- stderr\n").append(r.stderr.trimEnd())
                }.trimEnd())
            }
        }
    }
}

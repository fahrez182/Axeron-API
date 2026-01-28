package dev.frb.demo_axerish

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.topjohnwu.superuser.Shell
import dev.frb.demo_axerish.ui.theme.AxeronAPITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxeronAPITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()

            val result = Shell.cmd("env")
                .to(out)
                .exec()   // BLOCKING, tapi DI IO THREAD

            Log.i("Axerish", "exitCode=${result.code}")

            out.forEach {
                Log.i("Axerish", it)
            }
        }
    }

    Text("Hello $name!",
        modifier = modifier)
}

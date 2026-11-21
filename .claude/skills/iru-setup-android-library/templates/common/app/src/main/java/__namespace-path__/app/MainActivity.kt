package <namespace>.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Greeting(stringResource(R.string.app_name))
                }
            }
        }
    }
}

/**
 * Greets [name] — the sample component of the UI contract (state: `name`; events: none; visual states: `default`).
 *
 * @param name the name shown inside the localized greeting.
 * @param modifier modifier applied to the text.
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = stringResource(R.string.greeting, name), modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MaterialTheme {
        Greeting("<artifact-id>")
    }
}

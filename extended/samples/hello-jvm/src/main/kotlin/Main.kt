package hello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

@Composable
fun Content() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var count by remember { mutableStateOf(0) }
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Hello, Compose", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = { count++ }) { Text("Clicked $count times") }
            }
        }
    }
}

/**
 * With HELLO_SELF_CHECK set to a path, the window opens, the same content is also drawn
 * offscreen through Skia into a PNG at that path, and the application quits. It is how a
 * build that nobody watches proves both that a window came up and that Skia drew text.
 */
fun main() {
    val check = System.getenv("HELLO_SELF_CHECK")
    application {
        Window(onCloseRequest = ::exitApplication, title = "Hello JVM") {
            Content()
            if (check != null) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    val scene = ImageComposeScene(width = 480, height = 240, density = Density(1f)) { Content() }
                    val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                    scene.close()
                    File(check).writeBytes(png)
                    exitApplication()
                }
            }
        }
    }
}

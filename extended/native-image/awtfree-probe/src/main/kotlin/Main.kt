package probe

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat

fun main() {
    val scene = ImageComposeScene(width = 480, height = 240, density = Density(1f)) {
        MaterialTheme {
            var count by remember { mutableStateOf(0) }
            Column {
                Text("hello")
                Button(onClick = { count++ }) { Text("Clicked $count times") }
            }
        }
    }
    val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
    scene.close()
    println("png bytes: ${png.size}")
}

package hello

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.scene.ComposeWindowHost

/**
 * Text, a field and a control that changes when it is clicked. Text needs a font manager, a
 * shaper and a layout pass, the field needs the platform's text input, and the control shows
 * that pointer input reaches the scene.
 */
@Composable
fun Content() {
    var clicks by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xFF12321A))) {
        Column(Modifier.padding(top = 32.dp, start = 24.dp)) {
            BasicText("안녕하세요, no AWT here", style = TextStyle(color = Color.White, fontSize = 24.sp))
            BasicText(
                "composed by Compose, painted by Skia, shown by a window of our own",
                style = TextStyle(color = Color(0xFF9CCC9C), fontSize = 14.sp),
            )
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.padding(top = 16.dp).size(260.dp, 32.dp).background(Color(0xFF1E4620)),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
            )
            Box(
                Modifier.padding(top = 24.dp).size(220.dp, 56.dp).background(Color(0xFF2E7D32)).clickable { clicks++ },
            ) {
                BasicText(
                    if (clicks == 0) "click me" else "clicked $clicks",
                    Modifier.padding(16.dp),
                    style = TextStyle(color = Color.White, fontSize = 18.sp),
                )
            }
        }
    }
}

/**
 * With HELLO_SELF_CHECK set to a path, the window opens, draws a few dozen frames, writes
 * how many were presented and how many were drawn at a size other than the window's, and
 * quits with status 1 if any was. It is how a build that nobody watches proves that a
 * window came up and that every frame it showed was drawn at the size it had.
 */
fun main() {
    val check = System.getenv("HELLO_SELF_CHECK")
    val host = ComposeWindowHost(
        platform = newPlatform(),
        config = WindowConfig("Hello AWT-free", 520, 360, minWidth = 240, minHeight = 160),
        surfaceFor = ::newSurface,
        content = { Content() },
    )
    if (!host.open()) {
        System.err.println("hello-awtfree: the window did not open")
        System.exit(2)
    }
    if (check == null) {
        host.run()
        return
    }
    val deadline = System.nanoTime() + 20_000_000_000L
    try {
        while (host.presentLog.frames < 30 && System.nanoTime() < deadline && !host.closed) host.turn(16)
    } finally {
        val log = host.presentLog
        File(check).writeText("frames=${log.frames} mismatched=${log.mismatched}\n")
        host.close()
    }
    if (host.presentLog.frames == 0 || host.presentLog.mismatched != 0) System.exit(1)
}

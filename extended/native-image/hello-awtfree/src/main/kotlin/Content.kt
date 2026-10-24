package awtfree

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The content both AWT-free samples show: this GraalVM one and the Kotlin/Native one under
// extended/native-desktop/hello-awtfree, which links this same file. The field and the button
// carry the names a screen reader announces them by (the field's own label, "Name", and the
// button's text), which the Linux accessibility check requires.

/**
 * Text, a field with its label, and a control that changes when it is clicked. Text needs a
 * font manager, a shaper and a layout pass, the field needs the platform's text input, and the
 * control shows that pointer input reaches the scene.
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
            BasicText(FIELD_LABEL, Modifier.padding(top = 16.dp), style = TextStyle(color = Color(0xFF9CCC9C), fontSize = 14.sp))
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.padding(top = 4.dp).size(260.dp, 32.dp).background(Color(0xFF1E4620))
                    .semantics { contentDescription = FIELD_LABEL },
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
            )
            Box(
                Modifier.padding(top = 24.dp).size(220.dp, 56.dp).background(Color(0xFF2E7D32)).clickable { clicks++ },
            ) {
                BasicText(
                    if (clicks == 0) BUTTON_LABEL else "clicked $clicks",
                    Modifier.padding(16.dp),
                    style = TextStyle(color = Color.White, fontSize = 18.sp),
                )
            }
        }
    }
}

/** What the field is called, on the screen and to a reader. */
const val FIELD_LABEL = "Name"

/** The button's text before it is first clicked. */
const val BUTTON_LABEL = "click me"

package app.atlas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The one screen there is so far: the skeleton that proves the toolchain. The map comes next, drawn by our own renderer. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Blank() }
    }
}

@Composable
fun Blank() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF2EEE6)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Atlas", color = Color(0xFF14161B), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

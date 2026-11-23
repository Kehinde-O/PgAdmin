package dev.pgapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.pgapp.security.BiometricGate
import dev.pgapp.ui.PgAppRoot

// FragmentActivity (a ComponentActivity subclass) is required by BiometricPrompt.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording and recents thumbnails.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        val gate = BiometricGate(this)
        setContent { PgAppRoot(authenticate = gate::authenticate) }
    }
}

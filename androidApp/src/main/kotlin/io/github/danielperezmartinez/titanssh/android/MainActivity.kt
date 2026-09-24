package io.github.danielperezmartinez.titanssh.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.danielperezmartinez.titanssh.App
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import io.github.danielperezmartinez.titanssh.secret.AndroidSecretStoreContext
import io.github.danielperezmartinez.titanssh.ssh.AndroidHardwareKeys
import io.github.danielperezmartinez.titanssh.ssh.SshAndroidCrypto

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Give the shared SecretStore its application Context before anything can
        // build it (ADR-0001). Uses the application context, so no Activity leak.
        AndroidSecretStoreContext.init(applicationContext)
        // Same for the config store, which persists hosts/sessions under filesDir.
        AndroidConfigContext.init(applicationContext)
        // Let the shared SSH engine resolve hardware-key aliases to Android
        // Keystore handles for delegated signing (ADR-0005).
        AndroidHardwareKeys.install()
        // Register full BouncyCastle so sshj can negotiate with modern OpenSSH
        // on Android (ADR-0004).
        SshAndroidCrypto.install()
        // The app is dark-only (TitanTheme), so the system bars always get light
        // icons; the default would follow the system theme and draw dark icons
        // on the dark canvas when the device is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // Real product UI: the two-area shell (Configuración / Sesiones).
            // The hardware-signer manual test (HardwareSignerTestScreen) stays in
            // the source tree as a debug harness but is no longer the entrypoint.
            App()
        }
    }
}

package im.gar.titanssh.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import im.gar.titanssh.secret.AndroidSecretStoreContext
import im.gar.titanssh.ssh.AndroidHardwareKeys
import im.gar.titanssh.ssh.SshAndroidCrypto

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Give the shared SecretStore its application Context before anything can
        // build it (ADR-0001). Uses the application context, so no Activity leak.
        AndroidSecretStoreContext.init(applicationContext)
        // Let the shared SSH engine resolve hardware-key aliases to Android
        // Keystore handles for delegated signing (ADR-0005).
        AndroidHardwareKeys.install()
        // Register full BouncyCastle so sshj can negotiate with modern OpenSSH
        // on Android (ADR-0004).
        SshAndroidCrypto.install()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Debug build: show the hardware-signer test screen instead of the
            // skeleton until the real UI lands.
            MaterialTheme {
                Surface { HardwareSignerTestScreen() }
            }
        }
    }
}

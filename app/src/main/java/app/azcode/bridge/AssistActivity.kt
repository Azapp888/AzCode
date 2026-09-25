package app.azcode.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 系统助理入口（透明中转页）。
 *
 * 本身不显示任何界面：拿到悬浮窗与麦克风权限后，立即启动 [AssistOverlayService]
 * 在当前前台页面之上叠加透明背景的呼吸光晕，然后结束自己。
 * 这样"长按电源键/耳机、助理手势"唤起的就不再是本应用的页面，而是当前页面周围的蓝色光晕。
 */
class AssistActivity : AppCompatActivity() {

    private var launched = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            maybeLaunchOverlay()
        } else {
            toast(getString(R.string.warn_need_mic))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proceed()
    }

    override fun onResume() {
        super.onResume()
        // 从「显示在其他应用上层」设置页返回后重新检查。
        if (!launched) proceed()
    }

    private fun proceed() {
        if (launched) return
        if (!AssistOverlayService.canOverlay(this)) {
            requestOverlayPermission()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        maybeLaunchOverlay()
    }

    private fun maybeLaunchOverlay() {
        if (launched) return
        if (!AssistOverlayService.canOverlay(this)) {
            requestOverlayPermission()
            return
        }
        launched = true
        AssistOverlayService.start(this)
        finish()
    }

    private fun requestOverlayPermission() {
        toast(getString(R.string.assist_need_overlay))
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
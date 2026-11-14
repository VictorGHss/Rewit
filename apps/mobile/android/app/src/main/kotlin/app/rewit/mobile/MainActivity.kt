package app.rewit.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Activity principal do aplicativo mobile, expondo a consulta e solicitação
 * de permissão de câmera nativa com distinção real de negação permanente.
 */
class MainActivity : FlutterActivity() {
    companion object {
        private const val CAMERA_PERMISSION_CHANNEL =
            "app.rewit.mobile/camera_permission"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "rewit_camera_permission_prefs"
        private const val KEY_PERMANENTLY_DENIED =
            "camera_permission_permanently_denied"
        private const val KEY_HAS_REQUESTED_PERMISSION =
            "camera_permission_has_requested"
    }

    private var pendingCameraPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CAMERA_PERMISSION_CHANNEL,
        ).setMethodCallHandler(::handleCameraPermissionMethodCall)
    }

    private fun handleCameraPermissionMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        when (call.method) {
            "checkPermissionStatus" -> {
                val afterRequest = call.argument<Boolean>("afterRequest") ?: false
                result.success(resolveCameraPermissionStatus(afterRequest))
            }
            "requestPermission" -> requestCameraPermission(result)
            "openAppSettings" -> result.success(openApplicationSettings())
            else -> result.notImplemented()
        }
    }

    private fun resolveCameraPermissionStatus(afterRequest: Boolean): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val transition = CameraPermissionStateMachine.resolveStatusCheck(
            state = readPersistedPermissionState(prefs),
            isGranted =
                checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            shouldShowRationale =
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
            afterRequest = afterRequest,
        )
        writePersistedPermissionState(prefs, transition.nextState)
        return transition.status.wireValue
    }

    private fun requestCameraPermission(result: MethodChannel.Result) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val transition = CameraPermissionStateMachine.resolveStatusCheck(
                state = readPersistedPermissionState(prefs),
                isGranted = true,
                shouldShowRationale = false,
                afterRequest = false,
            )
            writePersistedPermissionState(prefs, transition.nextState)
            result.success(transition.status.wireValue)
            return
        }

        if (pendingCameraPermissionResult != null) {
            result.error(
                "PERMISSION_REQUEST_IN_PROGRESS",
                "Uma solicitação de permissão de câmera já está em andamento.",
                null,
            )
            return
        }

        pendingCameraPermissionResult = result
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }

        val pendingResult = pendingCameraPermissionResult
        pendingCameraPermissionResult = null
        if (pendingResult == null) {
            return
        }

        val outcome = when {
            grantResults.isEmpty() -> CameraPermissionRequestOutcome.CANCELLED
            grantResults[0] == PackageManager.PERMISSION_GRANTED ->
                CameraPermissionRequestOutcome.GRANTED
            else -> CameraPermissionRequestOutcome.DENIED
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val transition = CameraPermissionStateMachine.resolveRequestResult(
            state = readPersistedPermissionState(prefs),
            outcome = outcome,
            isGrantedAfterResult =
                checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            shouldShowRationale =
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
        )
        writePersistedPermissionState(prefs, transition.nextState)
        pendingResult.success(transition.status.wireValue)
    }

    private fun readPersistedPermissionState(
        prefs: SharedPreferences,
    ): CameraPermissionPersistedState {
        return CameraPermissionPersistedState(
            hasRequestedBefore = prefs.getBoolean(KEY_HAS_REQUESTED_PERMISSION, false),
            permanentlyDenied = prefs.getBoolean(KEY_PERMANENTLY_DENIED, false),
        )
    }

    private fun writePersistedPermissionState(
        prefs: SharedPreferences,
        state: CameraPermissionPersistedState,
    ) {
        prefs.edit().apply {
            if (state.hasRequestedBefore) {
                putBoolean(KEY_HAS_REQUESTED_PERMISSION, true)
            } else {
                remove(KEY_HAS_REQUESTED_PERMISSION)
            }
            if (state.permanentlyDenied) {
                putBoolean(KEY_PERMANENTLY_DENIED, true)
            } else {
                remove(KEY_PERMANENTLY_DENIED)
            }
        }.apply()
    }

    private fun openApplicationSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override fun onDestroy() {
        pendingCameraPermissionResult = null
        super.onDestroy()
    }
}

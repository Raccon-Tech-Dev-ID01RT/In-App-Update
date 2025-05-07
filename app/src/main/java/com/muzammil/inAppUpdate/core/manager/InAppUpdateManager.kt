package com.muzammil.inAppUpdate.core.manager

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.muzammil.inAppUpdate.ui.state.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow


private const val MAX_RETRY_COUNT = 8
private const val MAX_BACKOFF_SECONDS = 3600L // 1 hour

class InAppUpdateManager private constructor(
    private val activity: ComponentActivity,
    private val updateType: Int,
    private val callback: UpdateCallback,
    private val flexibleCallback: FlexibleUpdateCallback?,
    private val uiBinder: UIBinder,
    private val restartPrompt: () -> Unit
) : DefaultLifecycleObserver {

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(activity) }
    private var launcher: ActivityResultLauncher<IntentSenderRequest>
    private var installListener: InstallStateUpdatedListener? = null
    private var listenerRegistered = false
    private var retryCount = 0

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    interface UIBinder {
        fun showProgress(progress: Int)
        fun hideProgress()
    }

    interface UpdateCallback {
        fun onUpdateAvailable()
        fun onUpdateAccepted()
        fun onUpdateCanceled()
        fun onUpdateFailed(errorCode: Int)
        fun onNoUpdateAvailable()
    }

    interface FlexibleUpdateCallback {
        fun onDownloadProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onDownloadCompleted()
        fun onInstallStarted()
    }

    class Builder(private val context: Context) {
        private var type = com.google.android.play.core.install.model.AppUpdateType.IMMEDIATE
        private var cb: UpdateCallback? = null
        private var flexCb: FlexibleUpdateCallback? = null
        private var uiBinder: UIBinder? = null
        private var prompt: () -> Unit = {}

        fun setUpdateType(updateType: Int) = apply { this.type = updateType }
        fun setUpdateCallback(callback: UpdateCallback) = apply { this.cb = callback }
        fun setFlexibleCallback(callback: FlexibleUpdateCallback) = apply { this.flexCb = callback }
        fun setUiBinder(binder: UIBinder) = apply { this.uiBinder = binder }
        fun setRestartPrompt(prompt: () -> Unit) = apply { this.prompt = prompt }

        fun build(): InAppUpdateManager {
            val activity = context as? ComponentActivity
                ?: throw IllegalArgumentException("Context must be a ComponentActivity")
            requireNotNull(cb) { "UpdateCallback must be provided" }
            requireNotNull(uiBinder) { "UIBinder must be provided" }

            if (type == com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE) {
                requireNotNull(flexCb) { "FlexibleUpdateCallback required for FLEXIBLE updates" }
            }

            return InAppUpdateManager(activity, type, cb!!, flexCb, uiBinder!!, prompt).apply {
                activity.lifecycle.addObserver(this)
            }
        }
    }

    init {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> handleUpdateAccepted()
                Activity.RESULT_CANCELED -> handleUserCancel()
                else -> handleUnknownError(result.resultCode)
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        checkForAppUpdate()
        if (updateType == com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE) {
            registerInstallListener()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        unregisterInstallListener()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterInstallListener()
        owner.lifecycle.removeObserver(this)
    }

    private fun checkForAppUpdate() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    isUpdateAvailable(info) -> startUpdateFlow(info)
                    info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                        callback.onNoUpdateAvailable()
                        _updateState.value = UpdateState.Idle
                    }

                    else -> handleUpdateFailure(InstallErrorCode.ERROR_INSTALL_UNAVAILABLE)
                }
            }
            .addOnFailureListener {
                handleUpdateFailure(InstallErrorCode.ERROR_UNKNOWN)
            }
    }

    private fun isUpdateAvailable(info: AppUpdateInfo): Boolean {
        return info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(updateType)
    }

    private fun startUpdateFlow(info: AppUpdateInfo) {
        try {
            val options = AppUpdateOptions.newBuilder(updateType).build()
            appUpdateManager.startUpdateFlowForResult(info, launcher, options)
            callback.onUpdateAvailable()
            _updateState.value = UpdateState.Available
        } catch (e: Exception) {
            handleUpdateFailure(InstallErrorCode.ERROR_INVALID_REQUEST)
        }
    }

    private fun handleUpdateAccepted() {
        callback.onUpdateAccepted()
        _updateState.value = when (updateType) {
            com.google.android.play.core.install.model.AppUpdateType.IMMEDIATE -> UpdateState.Installing
            else -> UpdateState.Downloading(0)
        }
    }

    private fun handleUserCancel() {
        retryCount = (retryCount + 1).coerceAtMost(MAX_RETRY_COUNT)
        _updateState.value = UpdateState.Canceled(retryCount)
        callback.onUpdateCanceled()

        if (retryCount <= MAX_RETRY_COUNT) {
            activity.lifecycleScope.launch {
                delay(calculateBackoffDelay())
                checkForAppUpdate()
            }
        }
    }

    private fun calculateBackoffDelay(): Long {
        return (2.0.pow(retryCount.coerceAtMost(20)) * 1000)
            .toLong()
            .coerceAtMost(MAX_BACKOFF_SECONDS * 1000)
    }

    private fun handleUnknownError(resultCode: Int) {
        val errorCode = when (resultCode) {
            InstallErrorCode.NO_ERROR -> InstallErrorCode.ERROR_UNKNOWN
            InstallErrorCode.ERROR_API_NOT_AVAILABLE -> InstallErrorCode.ERROR_API_NOT_AVAILABLE
            InstallErrorCode.ERROR_INVALID_REQUEST -> InstallErrorCode.ERROR_INVALID_REQUEST
            InstallErrorCode.ERROR_INSTALL_UNAVAILABLE -> InstallErrorCode.ERROR_INSTALL_UNAVAILABLE
            InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED -> InstallErrorCode.ERROR_INSTALL_NOT_ALLOWED
            InstallErrorCode.ERROR_DOWNLOAD_NOT_PRESENT -> InstallErrorCode.ERROR_DOWNLOAD_NOT_PRESENT
            else -> InstallErrorCode.ERROR_UNKNOWN
        }
        handleUpdateFailure(errorCode)
    }

    private fun registerInstallListener() {
        if (listenerRegistered) return

        installListener = InstallStateUpdatedListener { state ->
            activity.runOnUiThread {
                when (state.installStatus()) {
                    InstallStatus.DOWNLOADING -> handleDownloadProgress(state)
                    InstallStatus.DOWNLOADED -> handleDownloadComplete()
                    InstallStatus.INSTALLING -> handleInstallStart()
                    InstallStatus.FAILED -> handleInstallFailure(state.installErrorCode())
                    InstallStatus.CANCELED -> handleUserCancel()
                    else -> Unit
                }
            }
        }

        appUpdateManager.registerListener(installListener!!)
        listenerRegistered = true
    }

    private fun handleDownloadProgress(state: InstallState) {
        val totalBytes = state.totalBytesToDownload().takeIf { it > 0 } ?: return
        val progress = ((state.bytesDownloaded() * 100) / totalBytes).toInt()

        flexibleCallback?.onDownloadProgress(state.bytesDownloaded(), totalBytes)
        _updateState.value = UpdateState.Downloading(progress)
        uiBinder.showProgress(progress)
    }

    private fun handleDownloadComplete() {
        flexibleCallback?.onDownloadCompleted()
        _updateState.value = UpdateState.Downloaded
        uiBinder.hideProgress()
        restartPrompt()
    }

    private fun handleInstallStart() {
        flexibleCallback?.onInstallStarted()
        _updateState.value = UpdateState.Installing
    }

    private fun handleInstallFailure(errorCode: Int) {
        callback.onUpdateFailed(errorCode)
        _updateState.value = UpdateState.Failed(errorCode)
    }

    private fun handleUpdateFailure(errorCode: Int) {
        callback.onUpdateFailed(errorCode)
        _updateState.value = UpdateState.Failed(errorCode)
    }

    private fun unregisterInstallListener() {
        installListener?.let {
            appUpdateManager.unregisterListener(it)
            listenerRegistered = false
        }
    }

    fun completeFlexibleUpdate() {
        if (_updateState.value != UpdateState.Downloaded) {
            handleUpdateFailure(InstallErrorCode.ERROR_INVALID_REQUEST)
            return
        }

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> {
                        try {
                            appUpdateManager.completeUpdate()
                            _updateState.value = UpdateState.Installing
                        } catch (e: Exception) {
                            handleUpdateFailure(InstallErrorCode.ERROR_INTERNAL_ERROR)
                        }
                    }

                    else -> handleUpdateFailure(InstallErrorCode.ERROR_DOWNLOAD_NOT_PRESENT)
                }
            }
            .addOnFailureListener { handleUpdateFailure(InstallErrorCode.ERROR_UNKNOWN) }
    }

    fun forceUpdateCheck() {
        retryCount = 0
        checkForAppUpdate()
    }
}
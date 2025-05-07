package com.muzammil.inAppUpdate.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.install.model.AppUpdateType
import com.muzammil.inAppUpdate.R
import com.muzammil.inAppUpdate.core.extensions.collectWithLifecycle
import com.muzammil.inAppUpdate.core.extensions.showToast
import com.muzammil.inAppUpdate.core.manager.InAppUpdateManager
import com.muzammil.inAppUpdate.core.utils.AppConstant
import com.muzammil.inAppUpdate.databinding.ActivityMainBinding
import com.muzammil.inAppUpdate.ui.state.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var inAppUpdateManager: InAppUpdateManager
    private var disableBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setPadding()
        handleBackPress()
        initializeInAppUpdateManager()
        observeUpdateState()

        binding.switchInAppUpdateType.isChecked = AppConstant.inAppUpdateType == AppUpdateType.IMMEDIATE
        binding.switchInAppUpdateType.setOnCheckedChangeListener { _, isChecked ->
            AppConstant.inAppUpdateType = if (isChecked) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        }
    }

    private fun setPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun handleBackPress() {
        // Always intercept back; disable if immediate update pending
        onBackPressedDispatcher.addCallback(this) {
            if (disableBack) {
                Snackbar.make(binding.root, getString(R.string.update_required_to_continue), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.update_now) { inAppUpdateManager.forceUpdateCheck() }
                    .show()
            } else {
                Log.i(TAG, "handleBackPress: do other operations on back here if needed")
            }
        }
    }

    private fun initializeInAppUpdateManager() {
        val uiBinder = object : InAppUpdateManager.UIBinder {
            override fun showProgress(progress: Int) {
                Log.i(TAG, "showProgress: $progress")
            }

            override fun hideProgress() {
                Log.i(TAG, "hideProgress")
            }
        }

        inAppUpdateManager = InAppUpdateManager.Builder(this)
            .setUpdateType(AppConstant.inAppUpdateType)
            .setUpdateCallback(object : InAppUpdateManager.UpdateCallback {
                override fun onUpdateAvailable() {}
                override fun onUpdateAccepted() {}
                override fun onUpdateCanceled() {}
                override fun onUpdateFailed(errorCode: Int) {}
                override fun onNoUpdateAvailable() {}
            })
            .setFlexibleCallback(object : InAppUpdateManager.FlexibleUpdateCallback {
                override fun onDownloadProgress(bytesDownloaded: Long, totalBytes: Long) {}
                override fun onDownloadCompleted() {}
                override fun onInstallStarted() = showToast(getString(R.string.installing_in_app_update))
            })
            .setUiBinder(uiBinder)
            .setRestartPrompt {
                Snackbar.make(binding.root, getString(R.string.in_app_update_downloaded_restart_to_apply), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.restart) { inAppUpdateManager.completeFlexibleUpdate() }
                    .show()
            }
            .build()
    }

    private fun observeUpdateState() {
        val isImmediate = AppConstant.inAppUpdateType == AppUpdateType.IMMEDIATE
        // Collect states to toggle back navigation
        inAppUpdateManager.updateState.collectWithLifecycle(this) { state ->
            disableBack = when {
                // For immediate updates, block unless idle (no update available)
                isImmediate && state !is UpdateState.Idle -> true
                else -> false
            }
            // If user canceled immediate, force re-check
            if (isImmediate && state is UpdateState.Canceled) {
                lifecycleScope.launch { delay(5000); inAppUpdateManager.forceUpdateCheck() }
            }

        }
    }

}

private const val TAG = "MainActivityLogsInformation"
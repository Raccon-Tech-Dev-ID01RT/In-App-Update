package com.muzammil.inAppUpdate.ui.state

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Available : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data object Downloaded : UpdateState()
    data object Installing : UpdateState()
    data class Failed(val code: Int) : UpdateState()
    data class Canceled(val retryCount: Int) : UpdateState()
}

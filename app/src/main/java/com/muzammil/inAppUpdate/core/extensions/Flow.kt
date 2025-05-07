package com.muzammil.inAppUpdate.core.extensions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects this Flow in the LifecycleOwner’s lifecycleScope,
 * observing only when at least [state].
 */
fun <T> Flow<T>.collectWithLifecycle(
  lifecycleOwner: LifecycleOwner,
  state: Lifecycle.State = Lifecycle.State.STARTED,
  collector: suspend (T) -> Unit
) {
  lifecycleOwner.lifecycleScope.launch {
    lifecycleOwner.repeatOnLifecycle(state) {
      collect { collector(it) }
    }
  }
}

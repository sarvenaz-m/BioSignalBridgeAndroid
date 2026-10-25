package dev.sarvenaz.biosignalbridge

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The application owns acquisition; this model only connects a screen to that workspace. */
@HiltViewModel
class BridgeViewModel @Inject constructor(val engine: BridgeEngine) : ViewModel() {
    val state = engine.state
    val actions = engine.actions
}

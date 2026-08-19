package com.rahgozar.app.ui.main

sealed class MainServiceEvent {
    data object StateRunning : MainServiceEvent()
    data object StateNotRunning : MainServiceEvent()
    data object StateStartSuccess : MainServiceEvent()
    data class StateStartFailure(val errorMessage: String) : MainServiceEvent()
    data object StateStopSuccess : MainServiceEvent()
    data class MeasureDelaySuccess(val content: String) : MainServiceEvent()

    /** The measurement as a number; negative means the tunnel carried nothing. */
    data class MeasureDelayResult(val delayMillis: Long) : MainServiceEvent()
    /** One server's measurement has begun. [guid] identifies which. */
    data class MeasureConfigTesting(val guid: String) : MainServiceEvent()

    /** One server finished measuring. [guid] identifies which. */
    data class MeasureConfigSuccess(val guid: String) : MainServiceEvent()
    data class MeasureConfigNotify(val progress: String) : MainServiceEvent()
    data class MeasureConfigFinish(val finishedCount: String?) : MainServiceEvent()

    /** Live throughput from the core process, in bytes per second. */
    data class SpeedUpdate(
        val upBytesPerSec: Long,
        val downBytesPerSec: Long,
        val sessionUpBytes: Long,
        val sessionDownBytes: Long,
    ) : MainServiceEvent()
}

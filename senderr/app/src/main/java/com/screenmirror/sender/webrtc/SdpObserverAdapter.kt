package com.screenmirror.sender.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * No-op SDP observer that just logs. Used for setLocalDescription / setRemoteDescription
 * where we don't need to react to success/failure in a granular way.
 */
class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        android.util.Log.w("SdpObserver", "onCreateFailure: $error")
    }
    override fun onSetFailure(error: String?) {
        android.util.Log.w("SdpObserver", "onSetFailure: $error")
    }
}

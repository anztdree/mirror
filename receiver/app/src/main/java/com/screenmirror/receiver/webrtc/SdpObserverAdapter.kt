package com.screenmirror.receiver.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

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

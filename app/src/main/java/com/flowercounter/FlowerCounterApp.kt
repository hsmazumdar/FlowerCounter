package com.flowercounter

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class FlowerCounterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Loads the native OpenCV library bundled in the Maven AAR (no OpenCV Manager app needed).
        openCvReady = OpenCVLoader.initLocal()
        if (openCvReady) {
            Log.i(TAG, "OpenCV ${OpenCVLoader.OPENCV_VERSION} loaded")
        } else {
            Log.e(TAG, "OpenCV failed to load")
        }
    }

    companion object {
        private const val TAG = "FlowerCounter"

        var openCvReady = false
            private set
    }
}

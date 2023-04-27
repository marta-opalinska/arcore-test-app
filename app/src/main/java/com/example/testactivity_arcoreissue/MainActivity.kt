/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.testactivity_arcoreissue

import ARCoreSessionLifecycleHelper
import CircleIdentificationActivityView
import SessionSaver
import YuvToRgbConverter
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.testactivity_arcoreissue.ui.AppRenderer
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.RecordingConfig
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.RecordingFailedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException


class MainActivity : AppCompatActivity() {
    val tag = "CircleActivity"
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper

    lateinit var renderer: AppRenderer
    lateinit var view: CircleIdentificationActivityView
    private val trackingStateHelper = TrackingStateHelper(this)
    lateinit var sessionSaver: SessionSaver
    lateinit var yuvConverter: YuvToRgbConverter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Needed to synchronise the timestamp of all the data that will be saved from the session
        val timeStamp = System.currentTimeMillis()
        val circlePhotoDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .toString() +
                    "/${"TestARCore"}" +
                    "/$timeStamp"
        Log.d(tag, circlePhotoDir)
        sessionSaver = SessionSaver(circlePhotoDir)

        val isArRecordingUsed = true

        val tapHelper = TapHelper( /*context=*/this)
        yuvConverter = YuvToRgbConverter(this)

        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
        // When session creation or session.resume fails, we display a message and log detailed information.
        arCoreSessionHelper.exceptionCallback = { exception ->
            val message = when (exception) {
                is UnavailableArcoreNotInstalledException,
                is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
                is UnavailableApkTooOldException -> "Please update ARCore"
                is UnavailableSdkTooOldException -> "Please update this app"
                is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                else -> "Failed to create AR session: $exception"
            }
            Log.e(tag, message, exception)
        }

        renderer = AppRenderer(this, tapHelper)
        lifecycle.addObserver(renderer)

        view = CircleIdentificationActivityView(this, renderer, tapHelper)
        setContentView(view.root)
        lifecycle.addObserver(view)

        // Determining AR core recording destination
        val arRecordingDestination = sessionSaver.getNewARrecordingDestination()
        Log.d(tag, "AR Recording destination: " + sessionSaver.getNewARrecordingDestination().toString())

        arCoreSessionHelper.beforeSessionResume = { session ->
            session.configure(
                session.config.apply {
                    // To get the best image of the object in question, enable autofocus.
                    focusMode = Config.FocusMode.FIXED
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
            )

            val filter = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
            val configs = session.getSupportedCameraConfigs(filter)
            val sort = compareByDescending<CameraConfig> { it.imageSize.width }
                .thenByDescending { it.imageSize.height }
            session.cameraConfig = configs.sortedWith(sort)[0]

            renderer.bindView(view)

            // Session recording configuration
            val recordingConfig = RecordingConfig(session).setMp4DatasetUri(arRecordingDestination)
                .setAutoStopOnPause(true);
            // applying recording configuration
            if (isArRecordingUsed) {
                try {
                    Log.i(tag, "session startRecording")
                    session.startRecording(recordingConfig)
                    this.runOnUiThread {
                        Toast.makeText(
                            this, "AR Core Recording in progress...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: RecordingFailedException) {
                    Log.e(tag, "Failed to start recording, RecordingFailedException", e)
                } catch (e: FatalException) {
                    Log.e(tag, "Failed to start recording, FatalException", e)
                }
            }
        }
        lifecycle.addObserver(arCoreSessionHelper)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        arCoreSessionHelper.onRequestPermissionsResult()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    fun showToast(message: String) {
        this.runOnUiThread(
            Runnable {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun triggerRebirth() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        this.startActivity(intent)
        if (this is Activity) {
            (this as Activity).finish()
        }
        Runtime.getRuntime().exit(0)
    }

    fun showTrackingState(trackingState: TrackingState) {
        trackingStateHelper.updateKeepScreenOnFlag(trackingState)
    }
}

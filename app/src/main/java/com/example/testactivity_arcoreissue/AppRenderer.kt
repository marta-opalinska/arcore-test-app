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
package com.example.testactivity_arcoreissue.ui

import BackgroundRenderer
import CircleIdentificationActivityView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.opengl.Matrix
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.testactivity_arcoreissue.DisplayRotationHelper
import com.example.testactivity_arcoreissue.MainActivity
import com.example.testactivity_arcoreissue.TapHelper
import com.example.testactivity_arcoreissue.calculateAverageDiameterAndDepth
import com.example.testactivity_arcoreissue.render.MainRender
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Collections
import java.util.Timer
import kotlin.concurrent.timerTask
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


/**
 * Renders the HelloAR application into using our example Renderer.
 */
class AppRenderer(val activity: MainActivity, val tapHelper: TapHelper) :
    DefaultLifecycleObserver, MainRender.Renderer, CoroutineScope by MainScope() {
    companion object {
        val TAG: String = AppRenderer::class.java.simpleName
        const val MATRIX_SIZE = 16
        const val CIRCLE_DETECT_COUNTER = 20
        const val AUTO_FOCUS_COUNTER = 10

        /**
         * Size of temporary arrays to prevent allocations in [ createAnchor].
         */
        private const val floatArraySize = 4

        // Values for OpenGL projection Matrix
        const val projection_matrix_offset = 0
        const val projection_matrix_near = 0.01f
        const val projection_matrix_far = 100f

        // the relationship between overlay radius and the smaller screen dimension - from 0 to 1.0
        private const val SCREEN_OVERLAY_RATE = 0.8f
        private const val SCREEN_OVERLAY_OPACITY = 150
        private const val CONVERSION_TO_CM = 100
        private const val CONVERSION_TO_MM = 1000
        private const val RAW_CONVERSION_TO_CM = 1000
        // private const val SEARCHING_PLANE_MESSAGE = "Please move around slowly..."

        private const val DIAMETER_CALCULATIONS_ANGLE_STEP = 2
        private const val DIAMETER_CALCULATIONS_START_ANGLE = 0
        private const val DIAMETER_CALCULATIONS_STOP_ANGLE = 180 - DIAMETER_CALCULATIONS_ANGLE_STEP

        // how many times raw depth will be calculated to establish the distance from the phone
        private const val RAW_DEPT_HIT_REPEATS = 10
        private const val MIN_RAW_DEPTH_CONFIDENCE = 0.9

        // radius from the circle center to do hit tests
        private const val MAX_DEPTH_CORD_OFFSET = 50

        // timeout of the circle identification action - 10s
        private const val MAX_ANALYSIS_TIMEOUT: Long = 10000
    }

    lateinit var view: CircleIdentificationActivityView

    val displayRotationHelper = DisplayRotationHelper(activity)
    lateinit var backgroundRenderer: BackgroundRenderer

    private val viewMatrix = FloatArray(MATRIX_SIZE)
    private val projectionMatrix = FloatArray(MATRIX_SIZE)
    private val viewProjectionMatrix = FloatArray(MATRIX_SIZE)

    private val arCirclesDetectionDataList: MutableList<ARCircleDetectionData> =
        Collections.synchronizedList(mutableListOf<ARCircleDetectionData>())

    private val diameterCalculationPoints: MutableList<ARPoint> =
        Collections.synchronizedList(mutableListOf<ARPoint>())
    private val distanceCalculationPoints: MutableList<ARPoint> =
        Collections.synchronizedList(mutableListOf<ARPoint>())

    // values describing the depth confidence image - height, width, overlay indexes etc.
    private val confidenceImage: ConfidenceImage = ConfidenceImage()
    

    var needsFocusResetFlag = false
    var savePictureFlag = false
    private var startScan = -1
    private var startAutoFocus = AUTO_FOCUS_COUNTER
    private var firstAutofocus = true
    private lateinit var timeHandler: Handler
    private lateinit var analysisTimer: Timer

    var showDepth = false

    var mWidth: Int? = null
    var mHeight: Int? = null

    lateinit var currentFrame: Frame

    private var latestFrameBitmap: Bitmap? = null
    private var latestFullFrameBitmap: Bitmap? = null
    private var circle = Circle()
//    private var anchorForDistance: Anchor? = null

    private var overlayRadius = 0f
    private var overlayCenterY = 0f
    private var overlayCenterX = 0f
    private var overlayCornerX = 0
    private var overlayCornerY = 0
    private var overlayWidth = 0
    private var overlayHeight = 0

    private var originalImageWidth = 0
    private var originalImageHeight = 0

    private var arCoreSessionTimestamp: String = ""
    private var imageTimestamp: String = ""

    private var restartSession = false

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    fun bindView(view: CircleIdentificationActivityView) {
        this.view = view

        view.analysisText.visibility = View.GONE

        view.saveButton.setOnClickListener {
            restartSession = true
        }
    }

    override fun onSurfaceCreated(render: MainRender) {
        backgroundRenderer = BackgroundRenderer(render).apply {
            setUseDepthVisualization(render, showDepth)
        }
    }

    override fun onSurfaceChanged(render: MainRender?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        mWidth = width
        mHeight = height
        activity.sessionSaver.setSize(width, height)

        if (this::view.isInitialized) {
            // choosing shorter dimension to make sure the overlay will fit the screen
            overlayRadius = (min(width, height) * SCREEN_OVERLAY_RATE) / 2
            createOverlay(
                width, height, (mWidth!! / 2).toFloat(), (mHeight!! / 2).toFloat(), overlayRadius
            )
        }
    }

    private fun createOverlay(
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radius: Float
    ) {
        val customBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(customBitmap)

        // background paint in white
        val paintStyle = Paint(Paint.ANTI_ALIAS_FLAG)
        paintStyle.style = Paint.Style.FILL_AND_STROKE
        paintStyle.color = Color.WHITE

        view.post {
            canvas.drawPaint(paintStyle)

            // clear circle
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

            canvas.drawCircle(centerX, centerY, radius, paint)

            val drawable = BitmapDrawable(null, customBitmap)

            view.guideSurface.background = drawable
            // Setting the opacity
            view.guideSurface.background.alpha = SCREEN_OVERLAY_OPACITY
        }
    }

    override fun onDrawFrame(render: MainRender) {
        val session = activity.arCoreSessionHelper.sessionCache ?: return
        session.setCameraTextureNames(intArrayOf(backgroundRenderer.getTexture(showDepth)))

        timeHandler = Handler(Looper.getMainLooper())

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)

        currentFrame = obtainCurrentFrame(session) ?: return

        backgroundRenderer.update(currentFrame)
        backgroundRenderer.drawBackground(render)

        if (startScan > 0) {
            startScan -= 1
        }

        if (startScan == 0) {
            overlayWidth = (overlayRadius * 2).toInt()
            overlayHeight = (overlayRadius * 2).toInt()
            val timestamp = LocalDateTime.now().toString()
            val bitmapsArray = getCPUImagesAsBitmaps(currentFrame, overlayWidth, overlayHeight)

            if (bitmapsArray.size == 2 && bitmapsArray[0] != null) {
                latestFullFrameBitmap = bitmapsArray[0]
                latestFrameBitmap = bitmapsArray[1]
                launch(Dispatchers.IO) {
                    imageTimestamp = timestamp
                    circle = Circle(
                        x = (latestFrameBitmap!!.width / 2).toFloat(),
                        y = (latestFrameBitmap!!.height / 2).toFloat(),
                        rOuter = overlayRadius * 0.8f,
                        found = true
                    )
                }
            }
            view.post {
                view.analysisText.visibility = View.VISIBLE
            }
        }

        // start autofocus after 500ms
        startAutoFocus = updateAutoFocus(startAutoFocus)

        // check if we have detected any circles
        if (circle.found) {
            val rotation =
                displayRotationHelper.getCameraSensorToDisplayRotation(
                    session.cameraConfig.cameraId
                )
            // create anchors for the circles
            createAnchors(currentFrame, rotation)

            // cleaning the analysis result
            circle.clear()
        } else {
            if (startScan == 0) {
                startScan = CIRCLE_DETECT_COUNTER
            }
        }

        // Get camera and projection matrices.
        val camera = currentFrame.camera

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(
            projectionMatrix,
            projection_matrix_offset,
            projection_matrix_near,
            projection_matrix_far
        )
        Matrix.multiplyMM(
            viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0
        )

        // Handle tracking failures.

        view.activity.showTrackingState(camera.trackingState)

        if (camera.trackingState != TrackingState.TRACKING) {
            return
        } else {
            checkFlags(session, render)
            if (savePictureFlag) {
                render.closeSession()
            }
        }
    }

    private fun updateAutoFocus(startAutoFocus: Int): Int {
        var newStartAutoFocus = startAutoFocus
        newStartAutoFocus -= 1

        if (newStartAutoFocus <= 0 && firstAutofocus) {
            firstAutofocus = false
            needsFocusResetFlag = true
        }
        return newStartAutoFocus
    }

    fun startTimer(delayInMillis: Long): Timer {
        Log.d("TIMER!", "Timer starts....")
        val timer = Timer()

        timer.schedule(
            timerTask {
                onTimerExpired()
            },
            delayInMillis
        )

        return timer
    }

    private fun cancelTimer(timer: Timer) {
        Log.d("TIMER!", "Canceling the analysis timer")
        timer.cancel()
    }

    private fun onTimerExpired() {
        // Your action to be executed after 5 seconds
        Log.d("TIMER!", "5 seconds have passed!")
        startScan = -1
        view.post {
            view.snackbarHelper.showError(
                activity,
                "Could not identify the circle. Please try to move the phone " +
                        "around to enable more precise measurement and try " +
                        "starting the analysis again."
            )
            view.post {
                view.analysisText.visibility = View.GONE
            }
        }
    }

    private fun obtainCurrentFrame(session: Session): Frame? {
        try {
            return session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            view.snackbarHelper.showError(
                activity, "Camera not available. Try restarting the app."
            )
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Session Destroyed", e)
            view.snackbarHelper.showError(
                activity, "Session Destroyed. Try restarting the app."
            )
            return null
        } catch (e: SessionPausedException) {
            Log.e(TAG, "Session Paused", e)
            view.snackbarHelper.showError(
                activity, "Session Paused Abruptly. Try restarting the app."
            )
            return null
        }
    }

    private fun checkFlags(session: Session, render: MainRender) {
        val tapPoll = tapHelper.poll()
        if (tapPoll != null) {
            needsFocusResetFlag = true
            Log.d(TAG, "Tap detected.")

            // use the first tap on the screen to begin the circle detection
            if (startScan == -1) {
                startScan = CIRCLE_DETECT_COUNTER
            }
            analysisTimer = startTimer(MAX_ANALYSIS_TIMEOUT)
        }

        if (needsFocusResetFlag) {
            val config = session.config
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session.configure(config)
            Log.d(TAG, "Autofocus triggered.")
            try {
                session.resume()
            } catch (e: CameraNotAvailableException) {
                Log.e("CameraNotAvailableException", "CameraNotAvailableException occurred")
            }
            needsFocusResetFlag = false
        }

        if (savePictureFlag) {
            savePictureFlag = false
            cancelTimer(analysisTimer)

            val isDataSaved = backgroundRenderer.saveFrame(
                currentFrame,
                activity.sessionSaver,
                latestFrameBitmap!!,
                latestFullFrameBitmap,
                arCirclesDetectionDataList
            )
            if (isDataSaved) {
                view.snackbarHelper.showError(
                    activity, "Data saved. R outer: ${arCirclesDetectionDataList[0].rCm}"
                )
                val timeStamp = System.currentTimeMillis()
                val circlePhotoDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .toString() +
                            "/${"TestARCore"}" +
                            "/$timeStamp"

                activity.sessionSaver.setDirectory(circlePhotoDir)
            } else {
                view.snackbarHelper.showError(
                    activity, "Error while saving the circle identification data. Please try again."
                )
            }

        }
        if(restartSession){
            Log.d(TAG, "Triggering rebirth! With recoridng status: ${session.recordingStatus}")
            restartSession = false
            session.stopRecording()
            if (session.recordingStatus == RecordingStatus.IO_ERROR) {
                Log.d("Recording", "Recording Error!")
                // removing faulty recording
//                activity.sessionSaver.removeARRecording()
            }
            // BUG - sometimes not reliable - even though the recording status says OK, it does
            // not display as Direct3D11 file on VLC under Windows
            if (session.recordingStatus == RecordingStatus.OK) {
                Log.d("Recording", "Recording OK!")
            }
            activity.triggerRebirth()
        }
    }

    private fun createAnchors(frame: Frame, rotation: Int) {
        Log.d(TAG, "Creating Anchors")
        Log.d(TAG, "Rotation: $rotation")
        try {
            arCoreSessionTimestamp = LocalDateTime.now().toString()
            val (atX, atY) = Pair(
                overlayCenterX - overlayRadius + circle.x, overlayCenterY - overlayRadius + circle.y
            )

            val anchor = createAnchor(atX, atY, frame).anchor

            if (anchor != null) {
                arCirclesDetectionDataList.clear()
                diameterCalculationPoints.clear()
                distanceCalculationPoints.clear()
                val distanceFromThePhoneRAW = calculateDistanceFromThePhoneRAW(frame, atX, atY)
                val (rOuterCm, distanceFromThePhone) = calculateDiameter(
                    circle,
                    frame,
                    atX,
                    atY,
                )

                Log.d(
                    "CalculationResults",
                    "Calculation result: rOuterCm: $rOuterCm, $distanceFromThePhone"
                )

                val distanceFromThePhoneRAWCenter =
                    checkDepth(frame, overlayCenterX.toInt(), overlayCenterY.toInt())
                Log.d(
                    "CalculateDistance",
                    "Distance form the phone: " +
                            "$distanceFromThePhone VS " +
                            "$distanceFromThePhoneRAW VS " +
                            "$distanceFromThePhoneRAWCenter... " + "${
                        Math.abs(distanceFromThePhone - distanceFromThePhoneRAW)
                    }"
                )
                // making sure that the distance from depth and from diameter calculation average
                // is mostly the same
                frame.timestamp
                arCirclesDetectionDataList.add(
                    ARCircleDetectionData(
                        anchor,
                        circle.clone(),
                        rOuterCm,
                        distanceFromThePhone * CONVERSION_TO_CM,
                        distanceFromThePhoneRAW * CONVERSION_TO_CM,
                        rotation.toFloat(),
                        originalImageWidth,
                        originalImageHeight,
                        overlayCornerX,
                        overlayCornerY,
                        overlayWidth,
                        overlayHeight,
                        arCoreSessionTimestamp,
                        imageTimestamp,
                        diameterCalculationPoints,
                        distanceCalculationPoints,
                    )
                )
            }

            if (arCirclesDetectionDataList.isNotEmpty() &&
                arCirclesDetectionDataList[0].rCm != 0f
            ) {
                // we have now processed the circles we found so clear the list
                Log.d(TAG, "clear found circles")

                // Automatically jumping to the result screen
                savePictureFlag = true
                // stop scanning
                startScan = -1
            }
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, e)
            view.post {
//                view.resetButton.isEnabled = arcircleDetectionDataList.isNotEmpty()
                view.snackbarHelper.showError(
                    activity,
                    "View was not available yet. Please, move the camera around and try again"
                )
            }
        }
    }

    private fun calculateDistanceFromThePhoneRAW(
        frame: Frame,
        atX: Float,
        atY: Float
    ): Float {
        var distanceList = mutableListOf<Pair<Float, Float>>()
        var distanceFromThePhone: Float? = null
        var coordinatesImage: FloatArray
        var coordinatesView: FloatArray
        var rawValues: List<Float>?

        repeat(RAW_DEPT_HIT_REPEATS) {
            // generated random X value near the center of the circle
            val randomX =
                (atX.toInt() - MAX_DEPTH_CORD_OFFSET..atX.toInt() + MAX_DEPTH_CORD_OFFSET)
                    .random()
            // generated random Y value near the center of the circle
            val randomY =
                ((atY.toInt() - MAX_DEPTH_CORD_OFFSET)..atY.toInt() + MAX_DEPTH_CORD_OFFSET)
                    .random()

            coordinatesImage = floatArrayOf(randomX.toFloat(), randomY.toFloat())
            coordinatesView = FloatArray(2)

            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS, coordinatesImage, Coordinates2d.VIEW, coordinatesView
            )

            rawValues = backgroundRenderer.getRawDepthAndConfidenceCoordinates(
                frame, coordinatesView[0].toInt(), coordinatesView[1].toInt()
            )

            if (rawValues != null && rawValues!![1] > MIN_RAW_DEPTH_CONFIDENCE) {

                distanceFromThePhone = rawValues!!.get(0)
            }

            if (distanceFromThePhone != null && distanceFromThePhone!! > 0) {
                Log.d("CalculateDistance ", distanceFromThePhone.toString())
                distanceList.add(Pair(distanceFromThePhone!!, 0f))

                distanceCalculationPoints.add(
                    ARPoint(
                        randomX.toFloat(),
                        randomY.toFloat(),
                        distanceFromThePhone!!,
                        depthConfidence = rawValues!![1],
                        includedInCalculations = true
                    )
                )
            }
        }
//        return
//            distanceList.map { it.first }.average() / RAW_CONVERSION_TO_CM
        return calculateAverageDiameterAndDepth(distanceList).first / RAW_CONVERSION_TO_CM
    }

    private fun calculateDistance(
        anchorStart: Pose?,
        anchorEnd: Pose?,
    ): Float {
        // Compute the difference between the two vectors only on x and y in World Coordinate System
        if (anchorStart != null && anchorEnd != null) {
            val dxWorld = anchorStart.tx() - anchorEnd.tx()
            val dyWorld = anchorStart.ty() - anchorEnd.ty()
            val dzWorld = anchorStart.tz() - anchorEnd.tz()
            return (
                    sqrt(
                        (dxWorld * dxWorld + dyWorld * dyWorld + dzWorld * dzWorld).toDouble()
                    )
                    ).toFloat() * CONVERSION_TO_CM
        }
        return -1f
    }

    private fun calculateDiameter(
        circle: Circle,
        frame: Frame,
        atXCenterImage: Float,
        atYCenterImage: Float,
    ): Pair<Float, Float> {
        var atXImage: Float
        var atYImage: Float
        var radians: Double
        var oppositeAngle: Double
        var oppositeXImage: Float
        var oppositeYImage: Float
        var distanceList = mutableListOf<Pair<Float, Float>>()
        var calculatedDiameter: Float
        var distanceFromThePhone: Float
//        var numberOfIncludedPairs = 0

        val rDivider = 2
        val distanceFromCenter = circle.rOuter / rDivider

        val list = checkDepth(currentFrame, overlayCenterX.toInt(), overlayCenterY.toInt())
        Log.d(
            TAG, "Distance calculations: Depth ${list?.get(0)} and confidence ${list?.get(1)}"
        )

        // Calculate the two opposite coordinates of the circle with 5-degree step

        for (
        angle in DIAMETER_CALCULATIONS_START_ANGLE..DIAMETER_CALCULATIONS_STOP_ANGLE
                step DIAMETER_CALCULATIONS_ANGLE_STEP
        ) {
//            Log.d("CalculateDistance", "Angle: $angle")
            radians = Math.toRadians(angle.toDouble())
            atXImage = (atXCenterImage + distanceFromCenter * sin(radians)).toFloat()
            atYImage = (atYCenterImage + distanceFromCenter * cos(radians)).toFloat()

            // Calculate the opposite point on the circle
            oppositeAngle = radians + PI
            oppositeXImage = (atXCenterImage + distanceFromCenter * sin(oppositeAngle)).toFloat()
            oppositeYImage = (atYCenterImage + distanceFromCenter * cos(oppositeAngle)).toFloat()

            var anchorPointEdge1 = createAnchor(atXImage, atYImage, frame)
            var anchorPointEdge2 = createAnchor(
                oppositeXImage, oppositeYImage, frame
            )
            calculatedDiameter = -1f
            distanceFromThePhone = 0f

            if (anchorPointEdge1.anchor != null && anchorPointEdge2.anchor != null) {
                if (anchorPointEdge2.anchor != null) {
                    calculatedDiameter = (
                            calculateDistance(
                                anchorPointEdge1.anchor?.pose,
                                anchorPointEdge2.anchor?.pose
                            ) * (circle.rOuter / distanceFromCenter)
                            ).toFloat()

                    distanceFromThePhone =
                        (
                                anchorPointEdge1.distanceFromThePhone +
                                        anchorPointEdge2.distanceFromThePhone
                                ) / 2

                    Log.d(
                        "CalculateDistance",
                        "Point included with calculated diamter : $calculatedDiameter and distnaceFromThePhone: $distanceFromThePhone "
                    )
                    diameterCalculationPoints.add(
                        ARPoint(
                            atXImage,
                            atYImage,
                            anchorPointEdge1.distanceFromThePhone * CONVERSION_TO_MM,
                            anchorPointEdge1.anchor!!.pose.tx(),
                            anchorPointEdge1.anchor!!.pose.ty(),
                            anchorPointEdge1.anchor!!.pose.tz(),
                            trackableType = anchorPointEdge1.trackableTag,
                            includedInCalculations = true,
                        )
                    )
                    diameterCalculationPoints.add(
                        ARPoint(
                            oppositeXImage,
                            oppositeYImage,
                            anchorPointEdge2.distanceFromThePhone * CONVERSION_TO_MM,
                            anchorPointEdge2.anchor!!.pose.tx(),
                            anchorPointEdge2.anchor!!.pose.ty(),
                            anchorPointEdge2.anchor!!.pose.tz(),
                            trackableType = anchorPointEdge2.trackableTag,
                            includedInCalculations = true,
                        )
                    )
                } else {
                    diameterCalculationPoints.add(
                        ARPoint(
                            atXImage,
                            atYImage,
                            anchorPointEdge1.distanceFromThePhone * CONVERSION_TO_MM,
                            anchorPointEdge1.anchor!!.pose.tx(),
                            anchorPointEdge1.anchor!!.pose.ty(),
                            anchorPointEdge1.anchor!!.pose.tz(),
                            trackableType = anchorPointEdge1.trackableTag,
                            includedInCalculations = false
                        )
                    )
                    diameterCalculationPoints.add(
                        ARPoint(
                            oppositeXImage,
                            oppositeYImage,
                            anchorPointEdge2.distanceFromThePhone * CONVERSION_TO_MM,
                            anchorPointEdge2.anchor!!.pose.tx(),
                            anchorPointEdge2.anchor!!.pose.ty(),
                            anchorPointEdge2.anchor!!.pose.tz(),
                            trackableType = anchorPointEdge2.trackableTag,
                            includedInCalculations = false
                        )
                    )
                }
            }
            if (calculatedDiameter > 0) {
                Log.d(
                    "CalculateDistance",
                    "Diameter value $calculatedDiameter and " +
                            "distance to the phone $distanceFromThePhone," +
                            "number of points found ${distanceList.size}"
                )
                distanceList.add(Pair(calculatedDiameter, distanceFromThePhone))
            }
        }


        Log.d("CalculateDistance ", "Distance list : $distanceList")
        Log.d("CalculateDistance ", "Diameter value ---------------------------")
        Log.d(TAG, "Median: ${calculateAverageDiameterAndDepth(distanceList)}")

        if (distanceList.size > 0) {
            Log.d("CalculateDistance ", "Not enough points included in the calculation!")
//            return calculateAverageDiameterAndDepth(distanceList)
            return Pair(
                distanceList.map { it.first }.average().toFloat(),
                distanceList.map { it.second }.average().toFloat()
            )
        }
        return Pair(0f, 0f)
    }

    /**
     * Temporary arrays to prevent allocations in [createAnchor].
     */

    private val convertFloats = FloatArray(floatArraySize)
    private val convertFloatsOut = FloatArray(floatArraySize)

    /** Create an anchor using (x, y) coordinates in the [Coordinates2d.IMAGE_PIXELS]
     * coordinate space. */
    private fun createAnchor(xImage: Float, yImage: Float, frame: Frame): AnchorPoint {
        convertFloats[0] = xImage
        convertFloats[1] = yImage
        // transforming coordinated from IMAGE_PIXELS (CPU image) -> VIEW
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS, convertFloats, Coordinates2d.VIEW, convertFloatsOut
        )
        val hits = frame.hitTest(convertFloatsOut[0], convertFloatsOut[1])
        val result = hits.getOrNull(0) ?: return AnchorPoint(null, 0.0f, "")
        Log.d(
            TAG,
            "Hit result type Point : ${result.trackable is Point} " +
                    ", Plane ${result.trackable is Plane} ,"
        )
        if (result.trackable is DepthPoint) {
            Log.d(TAG, "DEPTH POINT: Distance from the phone ${result.distance}")
            return AnchorPoint(
                result.trackable.createAnchor(result.hitPose),
                result.distance,
                "DepthPoint"
            )
        }
        if (result.trackable is Plane) {
            return AnchorPoint(
                result.trackable.createAnchor(result.hitPose),
                result.distance,
                "Plane"
            )
        }
        if (result.trackable is Point) {
            return AnchorPoint(
                result.trackable.createAnchor(result.hitPose),
                result.distance,
                "Point"
            )
        }
        Log.d(TAG, "Distance from the phone ${result.distance}")
        return AnchorPoint(
            result.trackable.createAnchor(result.hitPose),
            result.distance,
            "Undefined"
        )
    }

    private fun getCPUImagesAsBitmaps(
        frame: Frame,
        width: Int,
        height: Int
    ): ArrayList<Bitmap?> {
        @Suppress("SwallowedException")
        try {
            val bitmapFull = getFullCPUImage(frame)

            Log.d(TAG, "Height: ${bitmapFull.height}, Weight : ${bitmapFull.width} ")

            originalImageWidth = bitmapFull.width
            originalImageHeight = bitmapFull.height

            overlayCenterX = (bitmapFull.width / 2).toFloat()
            overlayCenterY = (bitmapFull.height / 2).toFloat()

            overlayCornerX = (overlayCenterX - width / 2).toInt()
            overlayCornerY = (overlayCenterY - height / 2).toInt()

            val croppedBitmap = Bitmap.createBitmap(
                bitmapFull,
                overlayCornerX,
                overlayCornerY,
                width,
                height
            )
            return arrayListOf(bitmapFull, croppedBitmap)
        } catch (e: NotYetAvailableException) {
            return arrayListOf()
        }
    }

    private fun checkDepth(frame: Frame, xView: Int, yView: Int): List<Float>? {
        val list = backgroundRenderer.getRawDepthAndConfidenceCoordinates(frame, xView, yView)
        if (list != null) {
            Log.d(TAG, "NEW X check depth: $xView , NEX Y: $yView")
            Log.d(TAG, "-------CHECK Depth : ${list[0]} mm , confidence : ${list[1]}")
            return list
        }
        return null
    }

    private fun getFullCPUImage(frame: Frame): Bitmap {
        val image = frame.acquireCameraImage()

        var bitmapRGB =
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                activity.yuvConverter.yuvToRgb(image, this)
            }
        image.close()
        return bitmapRGB
    }
}

@kotlinx.serialization.Serializable
data class ARCircleDetectionData(
    // Anchor data will not be serialised to JSON file
    @kotlinx.serialization.Transient val anchor: Anchor? = null,
    val circle: Circle,
    val rCm: Float,
    val averageDistanceFromThePhone: Float,
    val averageDistanceFromThePhoneRAW: Float,
    val frameRotation: Float,
    val originalImageWidth: Int,
    val originalImageHeight: Int,
    // Top-left corner
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val arCoreSessionTimestamp: String,
    val imageTimestamp: String,
    val diameterCalculationPoints: List<ARPoint>,
    val distanceCalculationPoints: List<ARPoint>,
)

data class ConfidenceImage(
    var width: Int = 0,
    var height: Int = 0,
    var confidenceBufferOverlaySize: Int = 0,
    var confidenceBufferCenterX: Int = 0,
    var confidenceBufferCenterY: Int = 0,
    var overlayXMin: Int = 0,
    var overlayXMax: Int = 0,
    var overlayYMin: Int = 0,
    var overlayYMax: Int = 0,
)

data class AnchorPoint(
    var anchor: Anchor?,
    var distanceFromThePhone: Float,
    var trackableTag: String,
)

@kotlinx.serialization.Serializable
class ARPoint(
    var imageX: Float = 0.0f,
    var imageY: Float = 0.0f,
    var distanceFromThePhone: Float = 0.0f,
    var spacePoseTX: Float = 0.0f,
    var spacePoseTY: Float = 0.0f,
    var spacePoseTZ: Float = 0.0f,
    var depthConfidence: Float = -1.0f,
    var trackableType: String = "",
    var includedInCalculations: Boolean,
) {

    fun clear() {
        this.imageX = 0.0f
        this.imageY = 0.0f
        this.distanceFromThePhone = 0.0f
        this.spacePoseTX = 0.0f
        this.spacePoseTY = 0.0f
        this.spacePoseTZ = 0.0f
        this.depthConfidence = -1.0f
        this.trackableType = ""
        this.includedInCalculations = true
    }

    fun clone(): ARPoint {
        return ARPoint(
            imageX,
            imageY,
            distanceFromThePhone,
            spacePoseTX,
            spacePoseTY,
            spacePoseTZ,
            depthConfidence,
            trackableType,
            includedInCalculations,
        )
    }
}

@kotlinx.serialization.Serializable
class Circle(
    var x: Float = 0.0f,
    var y: Float = 0.0f,
    var rOuter: Float = 0.0f,
    var found: Boolean = false,
) {

    fun clear() {
        this.found = false
        this.x = 0.0f
        this.y = 0.0f
        this.rOuter = 0.0f
    }

    fun clone(): Circle {
        return Circle(x, y, rOuter, found)
    }
}
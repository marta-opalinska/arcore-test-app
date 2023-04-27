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


import android.opengl.GLSurfaceView
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.testactivity_arcoreissue.MainActivity
import com.example.testactivity_arcoreissue.R
import com.example.testactivity_arcoreissue.SnackbarHelper
import com.example.testactivity_arcoreissue.TapHelper
import com.example.testactivity_arcoreissue.render.MainRender
import com.example.testactivity_arcoreissue.ui.AppRenderer

/**
 * Wraps [R.layout.activity_main_1] and controls lifecycle operations for [GLSurfaceView].
 */
class CircleIdentificationActivityView(
    val activity: MainActivity,
    renderer: AppRenderer,
    tapHelper: TapHelper
) : DefaultLifecycleObserver {
    companion object {
        const val MAX_LINES = 10

        private const val PROGRESS_YELLOW_MIN = 40
        private const val PROGRESS_GREEN_MIN = 60
    }

    val root = View.inflate(activity, R.layout.activity_main, null)
    val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceView).apply {
        MainRender(
            this,
            renderer,
            activity.assets,
            tapHelper
        )
    }

    val guideSurface = root.findViewById<SurfaceView>(R.id.guidance_layer)
    val saveButton = root.findViewById<AppCompatButton>(R.id.save_button)
    val analysisText = root.findViewById<AppCompatTextView>(R.id.analysis_text)
    val snackbarHelper = SnackbarHelper().apply {
        setParentView(root.findViewById(R.id.coordinatorLayout))
        setMaxLines(MAX_LINES)
    }

    override fun onResume(owner: LifecycleOwner) {
        surfaceView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        surfaceView.onPause()
    }

    fun post(action: Runnable) = root.post(action)

    /**
     * Toggles the scan button depending on if scanning is in progress.
     */
}

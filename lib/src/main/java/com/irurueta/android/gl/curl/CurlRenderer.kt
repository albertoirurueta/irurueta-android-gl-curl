/*
 * Copyright (C) 2021 Alberto Irurueta Carro (alberto@irurueta.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.irurueta.android.gl.curl

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.opengl.GLU
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Actual renderer class for the GLSurfaceView implementation of a curl view.
 * This class is based on https://github.com/harism/android-pagecurl
 *
 * @property observer observer in charge of receiving notifications from this renderer that can be
 * user to known the status or draw animations.
 * @param usePerspectiveProjection determines whether perspective projection must be used or not.
 */
class CurlRenderer(
    private var observer: Observer? = null,
    val usePerspectiveProjection: Boolean = USE_PERSPECTIVE_PROJECTION
) : GLSurfaceView.Renderer {

    /**
     * Background fill color.
     */
    var backgroundColor: Int = Color.TRANSPARENT

    /**
     * Curl meshes used for static and dynamic rendering.
     */
    private val curlMeshes: Vector<CurlMesh> = Vector()

    private val margins: RectF = RectF()

    /**
     * Left page rectangle.
     */
    private val pageRectLeft: RectF = RectF()

    /**
     * Right page rectangle.
     */
    private val pageRectRight: RectF = RectF()

    /**
     * View mode. Either single page or two pages side by side.
     */
    private var viewMode: Int = SHOW_ONE_PAGE

    /**
     * OpenGL drawing area width expressed in pixels.
     */
    private var viewportWidth: Int = 0

    /**
     * OpenGL drawing area height expressed in pixels.
     */
    private var viewportHeight: Int = 0

    /**
     * Rect for render area.
     */
    private var viewRect = RectF()

    /**
     * Adds CurlMesh to this renderer.
     */
    @Synchronized
    fun addCurlMesh(mesh: CurlMesh) {
        removeCurlMesh(mesh)
        curlMeshes.add(mesh)
    }

    /**
     * Returns rect reserved for left or right page. Value page should be
     * [PAGE_LEFT] or [PAGE_RIGHT].
     */
    fun getPageRect(page: Int): RectF? {
        return when (page) {
            PAGE_LEFT -> pageRectLeft
            PAGE_RIGHT -> pageRectRight
            else -> null
        }
    }

    @Synchronized
    override fun onDrawFrame(gl: GL10?) {
        if (gl == null) return

        observer?.onDrawFrame()

        gl.glClearColor(
            Color.red(backgroundColor) / 255.0f,
            Color.green(backgroundColor) / 255.0f,
            Color.blue(backgroundColor) / 255.0f,
            Color.alpha(backgroundColor) / 255.0f
        )
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT)
        gl.glLoadIdentity()

        if (usePerspectiveProjection) {
            gl.glTranslatef(0.0f, 0.0f, -6.0f)
        }

        synchronized(this) {
            for (curlMesh in curlMeshes) {
                curlMesh.onDrawFrame(gl)
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (gl == null) return

        gl.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height

        val ratio = width.toFloat() / height.toFloat()
        viewRect.top = 1.0f
        viewRect.bottom = -1.0f
        viewRect.left = -ratio
        viewRect.right = ratio
        updatePageRects()

        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        if (usePerspectiveProjection) {
            GLU.gluPerspective(gl, 20f, ratio, .1f, 100f)
        } else {
            GLU.gluOrtho2D(
                gl, viewRect.left, viewRect.right,
                viewRect.bottom, viewRect.top
            )
        }

        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadIdentity()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (gl == null) return

        gl.glClearColor(0f, 0f, 0f, 1f)
        gl.glShadeModel(GL10.GL_SMOOTH)
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST)
        gl.glHint(GL10.GL_LINE_SMOOTH_HINT, GL10.GL_NICEST)
        gl.glHint(GL10.GL_POLYGON_SMOOTH_HINT, GL10.GL_NICEST)
        gl.glEnable(GL10.GL_LINE_SMOOTH)
        gl.glDisable(GL10.GL_DEPTH_TEST)
        gl.glDisable(GL10.GL_CULL_FACE)

        observer?.onSurfaceCreated()
    }

    @Suppress("ControlFlowWithEmptyBody")
    @Synchronized
    fun removeCurlMesh(mesh: CurlMesh) : Boolean {
        // Remove repeated instance until no more instances remain
        var result = false
        while (curlMeshes.remove(mesh)) {
            result = true
        }

        return result
    }

    /**
     * Sets actual screen pixel margins.
     * @param left left margin expressed in pixels.
     * @param top top margin expressed in pixels.
     * @param right right margin expressed in pixels.
     * @param bottom bottom margin expressed in pixels.
     */
    @Synchronized
    fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
        setProportionalMargins(
            left.toFloat() / viewportWidth.toFloat(),
            top.toFloat() / viewportHeight.toFloat(),
            right.toFloat() / viewportWidth.toFloat(),
            bottom.toFloat() / viewportHeight.toFloat()
        )
    }

    /**
     * Sets proportional margins or padding.
     * Note: margins are proportional. Meaning a value of 0.1f will produce a 10% margin
     *
     * @param left percentage of left margin.
     * @param top percentage of top margin.
     * @param right percentage of right margin.
     * @param bottom percentage of bottom margin.
     */
    @Synchronized
    fun setProportionalMargins(left: Float, top: Float, right: Float, bottom: Float) {
        margins.left = left
        margins.top = top
        margins.right = right
        margins.bottom = bottom
        updatePageRects()
    }

    /**
     * Sets visible page count to one or two. Should be either [SHOW_ONE_PAGE] or
     * [SHOW_TWO_PAGES].
     */
    @Synchronized
    fun setViewMode(viewMode: Int) {
        if (viewMode == SHOW_ONE_PAGE || viewMode == SHOW_TWO_PAGES) {
            this.viewMode = viewMode
            updatePageRects()
        }
    }

    /**
     * Translates screen coordinates into view coordinates.
     */
    fun translate(pt: PointF) {
        pt.x = viewRect.left + (viewRect.width() * pt.x / viewportWidth)
        pt.y = viewRect.top + (viewRect.height() * pt.y / viewportHeight)
    }

    fun inverseTranslateX(x: Float): Float {
        return viewportWidth * (x - viewRect.left) / viewRect.width()
    }

    fun inverseTranslateY(y: Float): Float {
        return viewportHeight * (y - viewRect.top) / viewRect.height()
    }

    /**
     * Recalculates page rectangles.
     */
    private fun updatePageRects() {
        if (viewRect.width() == 0.0f || viewRect.height() == 0.0f) {
            return
        }

        if (viewMode == SHOW_ONE_PAGE) {
            pageRectRight.set(viewRect)
            pageRectRight.left += viewRect.width() * margins.left
            pageRectRight.right -= viewRect.width() * margins.right
            pageRectRight.top += viewRect.height() * margins.top
            pageRectRight.bottom -= viewRect.height() * margins.bottom

            pageRectLeft.set(pageRectRight)
            pageRectLeft.offset(-pageRectRight.width(), 0.0f)
        } else if (viewMode == SHOW_TWO_PAGES) {
            pageRectRight.set(viewRect)
            pageRectRight.left += viewRect.width() * margins.left
            pageRectRight.right -= viewRect.width() * margins.right
            pageRectRight.top += viewRect.height() * margins.top
            pageRectRight.bottom -= viewRect.height() * margins.bottom

            pageRectLeft.set(pageRectRight)
            pageRectLeft.right = (pageRectLeft.right + pageRectLeft.left) / 2.0f
            pageRectRight.left = pageRectLeft.right
        }

        if (viewMode == SHOW_ONE_PAGE || viewMode == SHOW_TWO_PAGES) {
            observer?.let { observer ->
                val bitmapW = ((pageRectRight.width() * viewportWidth) / viewRect.width()).toInt()
                val bitmapH =
                    ((pageRectRight.height() * viewportHeight) / viewRect.height()).toInt()
                observer.onPageSizeChanged(bitmapW, bitmapH)
            }
        }
    }

    companion object {
        /**
         * Constant for requesting left page rect.
         */
        const val PAGE_LEFT = 1

        /**
         * Constant for requesting right page rect.
         */
        const val PAGE_RIGHT = 2

        // Constants for changing view mode

        /**
         * Displays only one page.
         */
        const val SHOW_ONE_PAGE = 1

        /**
         * Displays two pages side by side.
         */
        const val SHOW_TWO_PAGES = 2

        /**
         * Enables perspective projection.
         */
        private const val USE_PERSPECTIVE_PROJECTION = false
    }

    /**
     * Observer for waiting render engine/state updates.
     */
    interface Observer {
        /**
         * Called from onDrawFrame before rendering is started. This is
         * intended to be used for animation purposes.
         */
        fun onDrawFrame()

        /**
         * Called once page size is changed. Width and height tell the page size
         * in pixels making it possible to update textures accordingly.
         */
        fun onPageSizeChanged(width: Int, height: Int)

        /**
         * Called from onSurfaceCreated to enable texture reinitialization.
         */
        fun onSurfaceCreated()
    }
}
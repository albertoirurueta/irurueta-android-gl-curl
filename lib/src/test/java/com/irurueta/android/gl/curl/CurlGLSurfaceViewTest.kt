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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import javax.microedition.khronos.opengles.GL10

@RunWith(RobolectricTestRunner::class)
class CurlGLSurfaceViewTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var currentIndexChangedListener: CurlGLSurfaceView.CurrentIndexChangedListener

    @MockK
    private lateinit var pageProvider: CurlGLSurfaceView.PageProvider

    @MockK
    private lateinit var gestureDetector: GestureDetector

    @MockK
    private lateinit var motionEvent: MotionEvent

    @MockK
    private lateinit var curlAnimator: ValueAnimator

    @MockK
    private lateinit var animator: ValueAnimator

    @After
    fun afterTest() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun constructor_setsDefaultValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.allowLastPageCurl)
        assertEquals(CurlGLSurfaceView.ANIMATION_DURATION_MILLIS, view.animationDurationTime)
        assertEquals(
            CurlGLSurfaceView.PAGE_JUMP_ANIMATION_DURATION_MILLIS,
            view.pageJumpDurationTime
        )
        assertFalse(view.enableTouchPressure)
        assertNull(view.pageProvider)
        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)
        assertTrue(view.renderLeftPage)
        assertNull(view.sizeChangedObserver)
        assertNull(view.currentIndexChangedListener)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertNull(view.pageClickListener)
        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)
        assertFalse(view.drawCurlPositionInMesh)
        assertFalse(view.drawPolygonOutlinesInMesh)
        assertTrue(view.drawShadowInMesh)
        assertTrue(view.drawTextureInMesh)
        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(view.shadowInnerColorInMesh))
        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(view.shadowOuterColorInMesh))
        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertFalse(animate)
        assertNotNull(view.getPrivateProperty("animationSource"))
        val animationStartTime: Long? = view.getPrivateProperty("animationStartTime")
        assertEquals(0L, animationStartTime)
        assertNotNull(view.getPrivateProperty("animationTarget"))
        val animationTargetEvent: Int? = view.getPrivateProperty("animationTargetEvent")
        assertEquals(0, animationTargetEvent)
        assertNotNull(view.getPrivateProperty("curlDir"))
        assertNotNull(view.getPrivateProperty("curlPos"))
        assertNull(view.getPrivateProperty("targetIndex"))
        assertNotNull(view.getPrivateProperty("dragStartPos"))
        val pageBitmapWidth: Int? = view.getPrivateProperty("pageBitmapWidth")
        assertEquals(-1, pageBitmapWidth)
        val pageBitmapHeight: Int? = view.getPrivateProperty("pageBitmapHeight")
        assertEquals(-1, pageBitmapHeight)
        assertNotNull(view.getPrivateProperty("pageCurl"))
        assertNotNull(view.getPrivateProperty("pageLeft"))
        assertNotNull(view.getPrivateProperty("pageRight"))
        assertNull(view.getPrivateProperty("pointerPost"))
        assertNotNull(view.getPrivateProperty("curlRenderer"))
        assertNotNull(view.getPrivateProperty("gestureDetector"))
        assertNull(view.getPrivateProperty("curlAnimator"))
        val scrollX: Float? = view.getPrivateProperty("scrollX")
        assertEquals(0.0f, scrollX)
        val scrollY: Float? = view.getPrivateProperty("scrollY")
        assertEquals(0.0f, scrollY)
        val scrollP: Float? = view.getPrivateProperty("scrollP")
        assertEquals(0.0f, scrollP)
        assertNotNull(view.getPrivateProperty("observer"))
    }

    @Test
    fun observer_onDrawFrameWhenNoCurlRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer1)

        // set null curl renderer
        view.setPrivateProperty("curlRenderer", null)

        val curlRenderer2: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNull(curlRenderer2)

        val notifySmoothChange1: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange1)
        assertTrue(notifySmoothChange1)

        observer.onDrawFrame()

        // check
        val notifySmoothChange2: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange2)
        assertTrue(notifySmoothChange2)
    }

    @Test
    fun observer_onDrawFrameWhenNotAnimateAndNotifySmoothChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertFalse(animate)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertTrue(notifySmoothChange)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }
    }

    @LooperMode(LooperMode.Mode.LEGACY)
    @Test
    fun observer_onDrawFrameWhenNotAnimateAndNotNotifySmoothChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val currentIndexChangedListener =
            mockk<CurlGLSurfaceView.CurrentIndexChangedListener>(relaxUnitFun = true)
        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertFalse(animate)

        val notifySmoothChange1: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange1)
        assertTrue(notifySmoothChange1)

        view.setPrivateProperty("notifySmoothChange", false)

        val notifySmoothChange2: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange2)
        assertFalse(notifySmoothChange2)

        observer.onDrawFrame()

        verify { currentIndexChangedListener.onCurrentIndexChanged(view, 0) }

        val notifySmoothChange3: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange3)
        assertTrue(notifySmoothChange3)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedButNoCurl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlRightAndNoPreviousCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_RIGHT)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlRightAndNoPageCurl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_RIGHT)

        // set page curl to null
        assertNotNull(view.getPrivateProperty("pageCurl"))
        view.setPrivateProperty("pageCurl", null)
        assertNull(view.getPrivateProperty("pageCurl"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        // set curl state and current index, and check they are not modified
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)
        view.setPrivateProperty("currentIndex", 1)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlRightAndNoPageRight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_RIGHT)

        // set page right to null
        assertNotNull(view.getPrivateProperty("pageRight"))
        view.setPrivateProperty("pageRight", null)
        assertNull(view.getPrivateProperty("pageRight"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        // set curl state and current index, and check they are not modified
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)
        view.setPrivateProperty("currentIndex", 1)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlRightPreviousLeftCurlStateAndNoTargetIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_RIGHT)

        // set previous curl state
        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)
        assertEquals(0, view.currentIndex)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(-1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlRightPreviousLeftCurlStateAndTargetIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_RIGHT)

        // set previous curl state
        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)
        assertEquals(0, view.currentIndex)

        // set targetIndex
        assertNull(view.getPrivateProperty("targetIndex"))

        view.setPrivateProperty("targetIndex", 1)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftAndNoPreviousCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftAndNoPageCurl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        // set page curl to null
        assertNotNull(view.getPrivateProperty("pageCurl"))
        view.setPrivateProperty("pageCurl", null)
        assertNull(view.getPrivateProperty("pageCurl"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        // set curl state and current index, and check they are not modified
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)
        view.setPrivateProperty("currentIndex", 1)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftAndNoPageLeft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        // set page left to null
        assertNotNull(view.getPrivateProperty("pageLeft"))
        view.setPrivateProperty("pageLeft", null)
        assertNull(view.getPrivateProperty("pageLeft"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        // set curl state and current index, and check they are not modified
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)
        view.setPrivateProperty("currentIndex", 1)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftNoPreviousCurlStateAndNotRenderLeftPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
        assertEquals(0, view.currentIndex)

        // set not renderLeftPage
        val renderLeftPage1: Boolean? = view.getPrivateProperty("renderLeftPage")
        requireNotNull(renderLeftPage1)
        assertTrue(renderLeftPage1)

        view.setPrivateProperty("renderLeftPage", false)

        val renderLeftPage2: Boolean? = view.getPrivateProperty("renderLeftPage")
        requireNotNull(renderLeftPage2)
        assertFalse(renderLeftPage2)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftPreviousLeftCurlStateAndNoTargetIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        // set previous curl state
        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)
        assertEquals(0, view.currentIndex)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAnimationFinishedCurlLeftPreviousLeftCurlStateAndTargetIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        val animationTargetEvent1: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent1)
        assertEquals(0, animationTargetEvent1)

        // set animation target event
        view.setPrivateProperty("animationTargetEvent", CurlGLSurfaceView.SET_CURL_TO_LEFT)

        // set previous curl state
        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)
        assertEquals(0, view.currentIndex)

        // set targetIndex
        assertNull(view.getPrivateProperty("targetIndex"))

        view.setPrivateProperty("targetIndex", 2)

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)

        val animate3: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate3)
        assertFalse(animate3)

        val notifySmoothChange: Boolean? = view.getPrivateProperty("notifySmoothChange")
        requireNotNull(notifySmoothChange)
        assertFalse(notifySmoothChange)

        assertEquals(2, view.currentIndex)
    }

    @Test
    fun observer_onDrawFrameWhenAnimatedAndAnimationFinished() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.currentIndexChangedListener = currentIndexChangedListener

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val animate1: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate1)
        assertFalse(animate1)

        // set animate
        view.setPrivateProperty("animate", true)

        val animate2: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate2)
        assertTrue(animate2)

        // set animation start time
        view.setPrivateProperty("animationStartTime", System.nanoTime())
        view.animationDurationTime = ANIMATION_DURATION_MILLIS

        observer.onDrawFrame()

        verify { currentIndexChangedListener wasNot Called }
    }

    @Test
    fun observer_onPageSizeChanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        // check default values
        val pageBitmapWidth1: Int? = view.getPrivateProperty("pageBitmapWidth")
        requireNotNull(pageBitmapWidth1)
        assertEquals(-1, pageBitmapWidth1)

        val pageBitmapHeight1: Int? = view.getPrivateProperty("pageBitmapHeight")
        requireNotNull(pageBitmapHeight1)
        assertEquals(-1, pageBitmapHeight1)

        observer.onPageSizeChanged(WIDTH, HEIGHT)

        // check
        val pageBitmapWidth2: Int? = view.getPrivateProperty("pageBitmapWidth")
        requireNotNull(pageBitmapWidth2)
        assertEquals(WIDTH, pageBitmapWidth2)

        val pageBitmapHeight2: Int? = view.getPrivateProperty("pageBitmapHeight")
        requireNotNull(pageBitmapHeight2)
        assertEquals(HEIGHT, pageBitmapHeight2)
    }

    @Test
    fun observer_onSurfaceCreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        // set spies
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)
        val pageRight: CurlMesh? = view.getPrivateProperty("pageRight")
        requireNotNull(pageRight)
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageLeftSpy = spyk(pageLeft)
        view.setPrivateProperty("pageLeft", pageLeftSpy)
        val pageRightSpy = spyk(pageRight)
        view.setPrivateProperty("pageRight", pageRightSpy)
        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        observer.onSurfaceCreated()

        // check
        verify(exactly = 1) { pageLeftSpy.resetTexture() }
        verify(exactly = 1) { pageRightSpy.resetTexture() }
        verify(exactly = 1) { pageCurlSpy.resetTexture() }
    }

    @Test
    fun observer_onSurfaceCreatedWhenNoPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val observer: CurlRenderer.Observer? = view.getPrivateProperty("observer")
        requireNotNull(observer)

        // set null pages
        view.setPrivateProperty("pageLeft", null)
        view.setPrivateProperty("pageRight", null)
        view.setPrivateProperty("pageCurl", null)

        observer.onSurfaceCreated()

        // check
        assertNull(view.getPrivateProperty("pageLeft"))
        assertNull(view.getPrivateProperty("pageRight"))
        assertNull(view.getPrivateProperty("pageCurl"))
    }

    @Test
    fun allowLastPageCurl_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.allowLastPageCurl)

        // set new value
        view.allowLastPageCurl = false

        // check
        @Suppress("KotlinConstantConditions")
        assertFalse(view.allowLastPageCurl)
    }

    @Test
    fun animationDurationTime_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(CurlGLSurfaceView.ANIMATION_DURATION_MILLIS, view.animationDurationTime)

        // set new value
        view.animationDurationTime = 500

        // check
        assertEquals(500, view.animationDurationTime)
    }

    @Test(expected = IllegalArgumentException::class)
    fun animationDurationTie_whenNegative_throwsException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.animationDurationTime = -1
    }

    @Test
    fun pageJumpDurationTime_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(
            CurlGLSurfaceView.PAGE_JUMP_ANIMATION_DURATION_MILLIS,
            view.pageJumpDurationTime
        )

        // set new value
        view.pageJumpDurationTime = 300L

        // check
        assertEquals(300L, view.pageJumpDurationTime)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageJumpDurationTime_whenNegative_throwsException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.pageJumpDurationTime = -1
    }

    @Test
    fun enableTouchPressure_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.enableTouchPressure)

        // set new value
        view.enableTouchPressure = true

        // check
        @Suppress("KotlinConstantConditions")
        assertTrue(view.enableTouchPressure)
    }

    @Test
    fun pageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertNull(view.pageProvider)

        // set new value
        view.pageProvider = pageProvider

        // check
        assertSame(pageProvider, view.pageProvider)
        assertEquals(0, view.currentIndex)
    }

    @Test
    fun maxCurlSplitsInMesh_whenNoPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.maxCurlSplitsInMesh = 20

        // check
        assertEquals(20, view.maxCurlSplitsInMesh)
    }

    @Test
    fun maxCurlSplitsInMesh_whenNoPageProviderAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.maxCurlSplitsInMesh = CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH

        // check
        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)
    }

    @Test
    fun maxCurlSplitsInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.maxCurlSplitsInMesh = 20 }
    }

    @Test
    fun drawCurlPositionInMesh_whenNoPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawCurlPositionInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawCurlPositionInMesh = true

        // check
        assertTrue(view.drawCurlPositionInMesh)
    }

    @Test
    fun drawCurlPositionInMesh_whenNoPageProviderAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawCurlPositionInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawCurlPositionInMesh = true

        // check
        assertTrue(view.drawCurlPositionInMesh)
    }

    @Test
    fun drawCurlPositionInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawCurlPositionInMesh)
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.drawCurlPositionInMesh = true }
    }

    @Test
    fun drawPolygonOutlinesInMesh_whenNoPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawPolygonOutlinesInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawPolygonOutlinesInMesh = true

        // check
        assertTrue(view.drawPolygonOutlinesInMesh)
    }

    @Test
    fun drawPolygonOutlinesInMesh_whenNoPageProviderAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawPolygonOutlinesInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawPolygonOutlinesInMesh = true

        // check
        assertTrue(view.drawPolygonOutlinesInMesh)
    }

    @Test
    fun drawPolygonOutlinesInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertFalse(view.drawPolygonOutlinesInMesh)
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.drawPolygonOutlinesInMesh = true }
    }

    @Test
    fun drawShadowInMesh_whenNoPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawShadowInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawShadowInMesh = false

        // check
        assertFalse(view.drawShadowInMesh)
    }

    @Test
    fun drawShadowInMesh_whenNoPageProviderAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawShadowInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawShadowInMesh = false

        // check
        assertFalse(view.drawShadowInMesh)
    }

    @Test
    fun drawShadowInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawShadowInMesh)
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.drawShadowInMesh = false }
    }

    @Test
    fun drawTextureInMesh_whenNoPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawTextureInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawTextureInMesh = false

        // check
        assertFalse(view.drawTextureInMesh)
    }

    @Test
    fun drawTextureInMesh_whenNoPageProviderAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawTextureInMesh)
        assertNull(view.pageProvider)

        // set new value
        view.drawTextureInMesh = false

        // check
        assertFalse(view.drawTextureInMesh)
    }

    @Test
    fun drawTextureInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(view.drawTextureInMesh)
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.drawTextureInMesh = false }
    }

    @Test
    fun shadowInnerColorInMesh_whenValid_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(
            view.shadowInnerColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.5f)
        view.shadowInnerColorInMesh = color

        // check
        assertSame(color, view.shadowInnerColorInMesh)
    }

    @Test
    fun shadowInnerColorInMesh_whenValidAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(
            view.shadowInnerColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        view.shadowInnerColorInMesh = CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH

        // check
        assertSame(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH, view.shadowInnerColorInMesh)
    }

    @Test
    fun shadowInnerColorInMesh_whenInvalidLength_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(
            view.shadowInnerColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(1.0f, 0.5f, 0.25f)
        assertThrows(IllegalArgumentException::class.java) {
            view.shadowInnerColorInMesh = color
        }
    }

    @Test
    fun shadowInnerColorInMesh_whenInvalidValue_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(
            view.shadowInnerColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(2.0f, 0.0f, 0.0f, 0.5f)
        assertThrows(IllegalArgumentException::class.java) {
            view.shadowInnerColorInMesh = color
        }
    }

    @Test
    fun shadowInnerColorInMesh_whenPageProvider_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(
            view.shadowInnerColorInMesh))
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.5f)
        assertThrows(IllegalArgumentException::class.java) { view.shadowInnerColorInMesh = color }
    }

    @Test
    fun shadowOuterColorInMesh_whenValid_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(
            view.shadowOuterColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        view.shadowOuterColorInMesh = color

        // check
        assertSame(color, view.shadowOuterColorInMesh)
    }

    @Test
    fun shadowOuterColorInMesh_whenValidAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(
            view.shadowOuterColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        view.shadowOuterColorInMesh = CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH

        // check
        assertSame(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH, view.shadowOuterColorInMesh)
    }

    @Test
    fun shadowOuterColorInMesh_whenInvalidLength_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(
            view.shadowOuterColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(1.0f, 0.5f, 0.25f)
        assertThrows(IllegalArgumentException::class.java) {
            view.shadowOuterColorInMesh = color
        }
    }

    @Test
    fun shadowOuterColorInMesh_whenInvalidValue_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(
            view.shadowOuterColorInMesh))
        assertNull(view.pageProvider)

        // set new value
        val color = floatArrayOf(2.0f, 0.0f, 0.0f, 0.5f)
        assertThrows(IllegalArgumentException::class.java) {
            view.shadowOuterColorInMesh = color
        }
    }

    @Test
    fun shadowOuterColorInMesh_whenPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(
            view.shadowOuterColorInMesh))
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        assertThrows(IllegalArgumentException::class.java) { view.shadowOuterColorInMesh = color }
    }

    @Test
    fun colorFactorOffsetInMesh_whenValid_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )
        assertNull(view.pageProvider)

        // set new value
        view.colorFactorOffsetInMesh = 0.5f

        assertEquals(0.5f, view.colorFactorOffsetInMesh)
    }

    @Test
    fun colorFactorOffsetInMesh_whenValidAndNoValueChange_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )
        assertNull(view.pageProvider)

        // set new value
        view.colorFactorOffsetInMesh = CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH

        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )
    }

    @Test
    fun colorFactorOffsetInMesh_whenInvalid_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )

        // set new value
        assertThrows(IllegalArgumentException::class.java) {
            view.colorFactorOffsetInMesh = -1.0f
        }
    }

    @Test
    fun colorFactorOffsetInMesh_whenPageProvider_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )
        assertNull(view.pageProvider)

        // set page provider
        view.pageProvider = pageProvider

        // set new value
        assertThrows(IllegalArgumentException::class.java) { view.colorFactorOffsetInMesh = 0.5f }
    }

    @Test
    fun renderLeftPage_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // check
        assertTrue(view.renderLeftPage)

        // set new value
        view.renderLeftPage = false

        // check
        @Suppress("KotlinConstantConditions")
        assertFalse(view.renderLeftPage)
    }

    @Test
    fun sizeChangeObserver_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // check
        assertNull(view.sizeChangedObserver)

        // set new value
        val observer = mockk<CurlGLSurfaceView.SizeChangedObserver>()
        view.sizeChangedObserver = observer

        // check
        assertSame(observer, view.sizeChangedObserver)
    }

    @Test
    fun currentIndexChangedListener_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // check
        assertNull(view.currentIndexChangedListener)

        // set new value
        val listener = mockk<CurlGLSurfaceView.CurrentIndexChangedListener>()
        view.currentIndexChangedListener = listener

        // check
        assertSame(listener, view.currentIndexChangedListener)
    }

    @Test
    fun viewMode_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // check default value
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        // set two pages
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // check
        assertEquals(CurlGLSurfaceView.SHOW_TWO_PAGES, view.viewMode)

        // set one page
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        // check
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
    }

    @Test
    fun viewMode_setsExpectedValueWhenNoPageOrRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageLeft", null)
        view.setPrivateProperty("curlRenderer", null)

        // check default value
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        // set two pages
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // check
        assertEquals(CurlGLSurfaceView.SHOW_TWO_PAGES, view.viewMode)

        // set one page
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        // check
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
    }

    @Test
    fun viewMode_whenNotSupportedValue_setsExpectedValueButMakesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // setup spies
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val pageLeftSpy = spyk(pageLeft)
        view.setPrivateProperty("pageLeft", pageLeftSpy)
        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        // check default value
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        // set not supported value
        view.viewMode = 0

        // check
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        verify { pageLeftSpy wasNot Called }
        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun pageClickListener_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // check default value
        assertNull(view.pageClickListener)

        // set new value
        val listener = mockk<CurlGLSurfaceView.PageClickListener>()
        view.pageClickListener = listener

        // check
        assertSame(listener, view.pageClickListener)
    }

    @Test
    fun onTouchEvent_whenActionCancel_handlesTouchUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set mock for gesture detector
        every { gestureDetector.onTouchEvent(any()) }.returns(true)
        view.setPrivateProperty("gestureDetector", gestureDetector)

        every { motionEvent.action }.returns(MotionEvent.ACTION_CANCEL)
        every { motionEvent.x }.returns(100.0f)
        every { motionEvent.y }.returns(200.0f)
        every { motionEvent.pressure }.returns(1.0f)

        assertTrue(view.onTouchEvent(motionEvent))

        verify(exactly = 1) { gestureDetector.onTouchEvent(motionEvent) }
    }

    @Test
    fun onTouchEvent_whenActionUp_handlesTouchUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set mock for gesture detector
        every { gestureDetector.onTouchEvent(any()) }.returns(true)
        view.setPrivateProperty("gestureDetector", gestureDetector)

        every { motionEvent.action }.returns(MotionEvent.ACTION_UP)
        every { motionEvent.x }.returns(100.0f)
        every { motionEvent.y }.returns(200.0f)
        every { motionEvent.pressure }.returns(1.0f)

        assertTrue(view.onTouchEvent(motionEvent))

        verify(exactly = 1) { gestureDetector.onTouchEvent(motionEvent) }
    }

    @Test
    fun onTouchEvent_whenOtherAction_callsGestureDetector() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set mock for gesture detector
        every { gestureDetector.onTouchEvent(any()) }.returns(true)
        view.setPrivateProperty("gestureDetector", gestureDetector)

        every { motionEvent.action }.returns(MotionEvent.ACTION_MOVE)

        assertTrue(view.onTouchEvent(motionEvent))

        verify(exactly = 1) { gestureDetector.onTouchEvent(motionEvent) }
    }

    @Test
    fun onTouchEvent_whenNoGestureDetector_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set null gesture detector
        view.setPrivateProperty("gestureDetector", null)

        every { motionEvent.action }.returns(MotionEvent.ACTION_MOVE)

        assertTrue(view.onTouchEvent(motionEvent))
    }

    @Test
    fun setCurrentIndex_whenNoPageProvider_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(0, view.currentIndex)

        view.setCurrentIndex(1)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun setCurrentIndex_whenPageProviderAndNegativeValue_setsZeroCurrentIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(-1)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun setCurrentIndex_whenPageProviderAndLastPageCurlAllowed_setsExpectedCurrentIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.allowLastPageCurl = true

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(2)

        assertEquals(2, view.currentIndex)
    }

    @Test
    fun setCurrentIndex_whenPageProviderAndLastPageCurlNotAllowed_setsExpectedCurrentIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.allowLastPageCurl = false

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(2)

        assertEquals(1, view.currentIndex)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test
    fun setSmoothCurrentIndex_whenNoPageProvider_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(0, view.currentIndex)

        view.setSmoothCurrentIndex(1)

        //finish animation
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, view.currentIndex)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test
    fun setSmoothCurrentIndex_whenPageProviderAndNegativeValue_setsZeroCurrentIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        every { pageProvider.updatePage(any(), any(), any(), any(), any()) }
            .answers { call ->
                val page = call.invocation.args[0] as CurlPage
                val width = call.invocation.args[1] as Int
                val height = call.invocation.args[2] as Int

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            }
        view.pageProvider = pageProvider

        view.setSmoothCurrentIndex(-1)

        //finish animation
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, view.currentIndex)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test
    fun setSmoothCurrentIndex_whenPageProviderButNoPageSizeChangeNotified_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        view.allowLastPageCurl = true

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        every { pageProvider.updatePage(any(), any(), any(), any(), any()) }
            .answers { call ->
                val page = call.invocation.args[0] as CurlPage
                val width = call.invocation.args[1] as Int
                val height = call.invocation.args[2] as Int

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            }
        view.pageProvider = pageProvider

        view.setSmoothCurrentIndex(2)

        //finish animation
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, view.currentIndex)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test
    fun setSmoothCurrentIndex_whenPageProviderNoPageSizeChangeNotifiedAndGoingToPrevious_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        view.allowLastPageCurl = true

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        every { pageProvider.updatePage(any(), any(), any(), any(), any()) }
            .answers { call ->
                val page = call.invocation.args[0] as CurlPage
                val width = call.invocation.args[1] as Int
                val height = call.invocation.args[2] as Int

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            }
        view.pageProvider = pageProvider

        view.setCurrentIndex(2)

        assertEquals(2, view.currentIndex)

        view.setSmoothCurrentIndex(1)

        //finish animation
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, view.currentIndex)
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test
    fun setSmoothCurrentIndex_whenPageProviderButNoPageSizeChangeNotifiedAndLastPageCurlNotAllowed_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        view.allowLastPageCurl = false

        assertEquals(0, view.currentIndex)

        every { pageProvider.pageCount }.returns(2)
        every { pageProvider.updatePage(any(), any(), any(), any(), any()) }
            .answers { call ->
                val page = call.invocation.args[0] as CurlPage
                val width = call.invocation.args[1] as Int
                val height = call.invocation.args[2] as Int

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            }
        view.pageProvider = pageProvider

        view.setSmoothCurrentIndex(2)

        //finish animation
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun setMargins_setsExpectedMargins() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        val margins: RectF? = renderer.getPrivateProperty("margins")
        requireNotNull(margins)
        assertEquals(0.0f, margins.left, 0.0f)
        assertEquals(0.0f, margins.right, 0.0f)
        assertEquals(0.0f, margins.top, 0.0f)
        assertEquals(0.0f, margins.bottom, 0.0f)

        // set margins
        view.setMargins(100, 200, 300, 400)

        // check
        assertEquals(100.0f / WIDTH, margins.left, 0.0f)
        assertEquals(200.0f / HEIGHT, margins.top, 0.0f)
        assertEquals(300.0f / WIDTH, margins.right, 0.0f)
        assertEquals(400.0f / HEIGHT, margins.bottom, 0.0f)
    }

    @Test
    fun setMargins_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        // set margins
        view.setMargins(100, 200, 300, 400)
    }

    @Test
    fun setProportionalMargins_setsExpectedMargins() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        val margins: RectF? = renderer.getPrivateProperty("margins")
        requireNotNull(margins)
        assertEquals(0.0f, margins.left, 0.0f)
        assertEquals(0.0f, margins.right, 0.0f)
        assertEquals(0.0f, margins.top, 0.0f)
        assertEquals(0.0f, margins.bottom, 0.0f)

        // set margins
        view.setProportionalMargins(100.0f, 200.0f, 300.0f, 400.0f)

        // check
        assertEquals(100.0f, margins.left, 0.0f)
        assertEquals(200.0f, margins.top, 0.0f)
        assertEquals(300.0f, margins.right, 0.0f)
        assertEquals(400.0f, margins.bottom, 0.0f)
    }

    @Test
    fun setProportionalMargins_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        // set margins
        view.setProportionalMargins(100.0f, 200.0f, 300.0f, 400.0f)
    }

    @Test
    fun setBackgroundColor_setsExpectedValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        // check default value
        assertEquals(Color.TRANSPARENT, renderer.backgroundColor)

        // set new background color
        view.setBackgroundColor(Color.RED)

        // check
        assertEquals(Color.RED, renderer.backgroundColor)
    }

    @Test
    fun setBackgroundColor_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        // set new background color
        view.setBackgroundColor(Color.RED)
    }

    @Test
    fun onSizeChange_whenHasSizeChangeObserver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val sizeChangedObserver = mockk<CurlGLSurfaceView.SizeChangedObserver>(relaxUnitFun = true)
        view.sizeChangedObserver = sizeChangedObserver

        val onSizeChangedMethod = view::class.java.getDeclaredMethod(
            "onSizeChanged",
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java
        )
        onSizeChangedMethod.isAccessible = true
        onSizeChangedMethod.invoke(view, WIDTH, HEIGHT, WIDTH, HEIGHT)

        verify(exactly = 1) { sizeChangedObserver.onSizeChanged(WIDTH, HEIGHT) }
    }

    @Test
    fun onSizeChange_whenNoSizeChangeObserver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("sizeChangedObserver", null)

        val onSizeChangedMethod = view::class.java.getDeclaredMethod(
            "onSizeChanged",
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java
        )
        onSizeChangedMethod.isAccessible = true
        onSizeChangedMethod.invoke(view, WIDTH, HEIGHT, WIDTH, HEIGHT)
    }

    @Test
    fun updateLastCurlPos_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        assertNull(view.getPrivateProperty("targetIndex"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateLastCurlPos_whenNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        assertNull(view.getPrivateProperty("targetIndex"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateLastCurlPos_whenNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        assertNull(view.getPrivateProperty("targetIndex"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesAndNoCurlState_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesEnabledTouchPressureAndNoCurlState_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.enableTouchPressure = true

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(3.0f, pressure2)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesLeftCurlStateAndOnePageViewMode_setsAnimationTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        // set curlState
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val curlState: Int? = view.getPrivateProperty("curlState")
        requireNotNull(curlState)
        assertEquals(CurlGLSurfaceView.CURL_LEFT, curlState)

        // initially no animation start time is set
        val animationStartTime1: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime1)
        assertEquals(0L, animationStartTime1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        val targetIndex: Int? = view.getPrivateProperty("targetIndex")
        requireNotNull(targetIndex)
        assertEquals(1, targetIndex)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        val animationStartTime2: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime2)
        assertTrue(animationStartTime2 > 0)

        val animationTargetEvent: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent)
        assertEquals(CurlGLSurfaceView.SET_CURL_TO_LEFT, animationTargetEvent)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertTrue(animate)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesLeftCurlStateAndTwoPageViewMode_setsAnimationTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer : CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        // set curlState
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val curlState: Int? = view.getPrivateProperty("curlState")
        requireNotNull(curlState)
        assertEquals(CurlGLSurfaceView.CURL_LEFT, curlState)

        // initially no animation start time is set
        val animationStartTime1: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime1)
        assertEquals(0L, animationStartTime1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        val targetIndex: Int? = view.getPrivateProperty("targetIndex")
        requireNotNull(targetIndex)
        assertEquals(1, targetIndex)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        val animationStartTime2: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime2)
        assertTrue(animationStartTime2 > 0)

        val animationTargetEvent: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent)
        assertEquals(CurlGLSurfaceView.SET_CURL_TO_LEFT, animationTargetEvent)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertTrue(animate)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesRightCurlStateAndOnePageViewMode_setsAnimationTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        // set curlState
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val curlState: Int? = view.getPrivateProperty("curlState")
        requireNotNull(curlState)
        assertEquals(CurlGLSurfaceView.CURL_RIGHT, curlState)

        // initially no animation start time is set
        val animationStartTime1: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime1)
        assertEquals(0L, animationStartTime1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        val targetIndex: Int? = view.getPrivateProperty("targetIndex")
        requireNotNull(targetIndex)
        assertEquals(1, targetIndex)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        val animationStartTime2: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime2)
        assertTrue(animationStartTime2 > 0)

        val animationTargetEvent: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent)
        assertEquals(CurlGLSurfaceView.SET_CURL_TO_LEFT, animationTargetEvent)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertTrue(animate)
    }

    @Test
    fun updateLastCurlPos_whenRendererWithPagesAndTwoPageViewMode_setsAnimationTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        // set curlState
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val curlState: Int? = view.getPrivateProperty("curlState")
        requireNotNull(curlState)
        assertEquals(CurlGLSurfaceView.CURL_RIGHT, curlState)

        // initially no animation start time is set
        val animationStartTime1: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime1)
        assertEquals(0L, animationStartTime1)

        val updateLastCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateLastCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateLastCurlPosMethod.isAccessible = true

        updateLastCurlPosMethod.invoke(view, 2.0f * WIDTH.toFloat(), 2.0f, 3.0f, 1)

        // check
        val targetIndex: Int? = view.getPrivateProperty("targetIndex")
        requireNotNull(targetIndex)
        assertEquals(1, targetIndex)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(1.6875f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        val animationStartTime2: Long? = view.getPrivateProperty("animationStartTime")
        requireNotNull(animationStartTime2)
        assertTrue(animationStartTime2 > 0)

        val animationTargetEvent: Int? = view.getPrivateProperty("animationTargetEvent")
        requireNotNull(animationTargetEvent)
        assertEquals(CurlGLSurfaceView.SET_CURL_TO_RIGHT, animationTargetEvent)

        val animate: Boolean? = view.getPrivateProperty("animate")
        requireNotNull(animate)
        assertTrue(animate)
    }

    @Test
    fun handleFirstScrollEvent_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        assertNull(view.getPrivateProperty("curlAnimator"))

        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun handleFirstScrollEvent_whenNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        assertNull(view.getPrivateProperty("curlAnimator"))

        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun handleFirstScrollEvent_whenNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        assertNull(view.getPrivateProperty("curlAnimator"))

        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun handleFirstScrollEvent_whenRightCurlState_initializesAnimator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectRight"))
        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        assertNull(view.getPrivateProperty("curlAnimator"))

        every { motionEvent.x }.returns(1.0f)
        every { motionEvent.y }.returns(2.0f)
        every { motionEvent.pressure }.returns(0.0f)
        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNotNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun handleFirstScrollEvent_whenLeftCurlStateAndOnePageViewMode_initializesAnimator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectRight"))
        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        assertNull(view.getPrivateProperty("curlAnimator"))

        every { motionEvent.x }.returns(1.0f)
        every { motionEvent.y }.returns(2.0f)
        every { motionEvent.pressure }.returns(0.0f)
        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNotNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun handleFirstScrollEvent_whenLeftCurlStateAndTwoPageViewMode_initializesAnimator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectRight"))
        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        assertNull(view.getPrivateProperty("curlAnimator"))

        every { motionEvent.x }.returns(1.0f)
        every { motionEvent.y }.returns(2.0f)
        every { motionEvent.pressure }.returns(0.0f)
        view.callPrivateFunc("handleFirstScrollEvent", motionEvent)

        // check that curl animator has not been initialized
        assertNotNull(view.getPrivateProperty("curlAnimator"))
    }

    @Test
    fun updateFirstCurlPos_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)
        assertNull(view.getPrivateProperty("curlRenderer"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateFirstCurlPos_whenNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer : CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateFirstCurlPos_whenNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer : CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateFirstCurlPos_whenNoPageProvider_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer : CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        view.pageProvider = null

        // check default value
        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        requireNotNull(posField)
        posField.isAccessible = true
        val pos1: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 0.0f, 1)

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(0.0f, pos2.x)
        assertEquals(0.0f, pos2.y)
    }

    @Test
    fun updateFirstCurlPos_whenNoTouchPressure_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.enableTouchPressure = false

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenEnableTouchPressure_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.enableTouchPressure = true

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f, 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(0.99791664f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(3.0f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenStartDragPosIsGreaterThanTop_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, -2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(5.0f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenStartDragPosIsLessThanBottom_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(-3.0f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenTwoPageViewModeLeftCurlAndZeroCurrentIndex_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(-3.0f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenTwoPageViewModeLeftCurlAndNonZeroCurrentIndex_setsLeftCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenTwoPageViewModeRightCurlAndNotAllowLastPageCurl_keepsNoneCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES
        view.allowLastPageCurl = false

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(
            view,
            2.0f * WIDTH.toFloat(),
            2.0f * HEIGHT.toFloat(),
            3.0f,
            1
        )

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenTwoPageViewModeRightCurlAndAllowLastPageCurl_setsRightCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES
        view.allowLastPageCurl = true

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(
            view,
            2.0f * WIDTH.toFloat(),
            2.0f * HEIGHT.toFloat(),
            3.0f,
            1
        )

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenOnePageViewModeLeftCurlAndZeroCurrentIndex_updatesPointerPos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        val pointerPos: Any? = view.getPrivateProperty("pointerPos")
        requireNotNull(pointerPos)

        val classes = view.javaClass.declaredClasses
        val pointerPositionClass: Class<*>? =
            classes.firstOrNull { it.name.endsWith("PointerPosition") }

        val posField = pointerPositionClass?.getDeclaredField("pos")
        posField?.isAccessible = true
        val pos1: PointF? = posField?.get(pointerPos) as PointF?
        requireNotNull(pos1)

        assertEquals(0.0f, pos1.x)
        assertEquals(0.0f, pos1.y)

        val pressureField = pointerPositionClass.getDeclaredField("pressure")
        pressureField.isAccessible = true
        val pressure1: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.0f, pressure1)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        val pos2: PointF? = posField.get(pointerPos) as PointF?
        requireNotNull(pos2)

        assertEquals(-0.56145835f, pos2.x)
        assertEquals(-3.0f, pos2.y)

        val pressure2: Float? = pressureField.getFloat(pointerPos)
        assertEquals(0.8f, pressure2)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenOnePageViewModeLeftCurlAndNonZeroCurrentIndex_setsLeftCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(view, 1.0f, 2.0f * HEIGHT.toFloat(), 3.0f, 1)

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenOnePageViewModeRightCurlAndNotAllowLastPageCurl_keepsNoneCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE
        view.allowLastPageCurl = false

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(
            view,
            2.0f * WIDTH.toFloat(),
            2.0f * HEIGHT.toFloat(),
            3.0f,
            1
        )

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun updateFirstCurlPos_whenOnePageViewModeRightCurlAndAllowLastPageCurl_setsRightCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE
        view.allowLastPageCurl = true

        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val gl = mockk<GL10>(relaxUnitFun = true)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // set page provider
        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxUnitFun = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setCurrentIndex(1)

        assertEquals(1, view.currentIndex)

        val updateFirstCurlPosMethod = CurlGLSurfaceView::class.java.getDeclaredMethod(
            "updateFirstCurlPos",
            Float::class.java,
            Float::class.java,
            Float::class.java,
            Integer::class.java
        )
        updateFirstCurlPosMethod.isAccessible = true

        updateFirstCurlPosMethod.invoke(
            view,
            2.0f * WIDTH.toFloat(),
            2.0f * HEIGHT.toFloat(),
            3.0f,
            1
        )

        // check
        assertNull(view.getPrivateProperty("targetIndex"))

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
    }

    @Test
    fun handleScrollEvent_setsScrollCoordinatesAndPressure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        every { motionEvent.x }.returns(1.0f)
        every { motionEvent.y }.returns(2.0f)
        every { motionEvent.pressure }.returns(0.5f)
        view.callPrivateFunc("handleScrollEvent", motionEvent)

        val scrollX: Float? = view.getPrivateProperty("scrollX")
        val scrollY: Float? = view.getPrivateProperty("scrollY")
        val scrollP: Float? = view.getPrivateProperty("scrollP")

        assertEquals(1.0f, scrollX)
        assertEquals(2.0f, scrollY)
        assertEquals(0.5f, scrollP)
    }

    @Test
    fun rebuildPages_removesAndAddsCurlMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        every { curlRendererSpy.removeCurlMesh(any()) }.returns(true)
        justRun { curlRendererSpy.addCurlMesh(any()) }
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        // execute rebuildPages
        view.callPrivateFunc("rebuildPages")

        // check
        verify(exactly = 3) { curlRendererSpy.addCurlMesh(any()) }
    }

    @Test
    fun gestureDetector_onSingleTapUpAndNoClickListener_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        assertFalse(gestureDetectorListener.onSingleTapUp(motionEvent))
    }

    @Test
    fun gestureDetector_onSingleTapUpAndClickListener_callsClickListener() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val pageClickListener = mockk<CurlGLSurfaceView.PageClickListener>()
        every { pageClickListener.onPageClick(view, any()) }.returns(true)
        view.pageClickListener = pageClickListener

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        assertTrue(gestureDetectorListener.onSingleTapUp(motionEvent))
    }

    @Test
    fun gestureDetector_onScrollWhenNotScrolling_startsScrolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        val scrollingField = gestureDetectorListener::class.java.getDeclaredField("scrolling")
        scrollingField.isAccessible = true
        val scrolling1 = scrollingField.getBoolean(gestureDetectorListener)
        assertFalse(scrolling1)

        val e1 = mockk<MotionEvent>(relaxed = true)
        val e2 = mockk<MotionEvent>(relaxed = true)
        assertTrue(gestureDetectorListener.onScroll(e1, e2, 1.0f, 2.0f))

        val scrolling2 = scrollingField.getBoolean(gestureDetectorListener)
        assertTrue(scrolling2)

        verify { e2 wasNot Called }
    }

    @Test
    fun gestureDetector_onScrollWhenAlreadyScrolling_keepsScrolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        val scrollingField = gestureDetectorListener::class.java.getDeclaredField("scrolling")
        scrollingField.isAccessible = true
        scrollingField.setBoolean(gestureDetectorListener, true)

        val e1 = mockk<MotionEvent>(relaxed = true)
        val e2 = mockk<MotionEvent>(relaxed = true)
        assertTrue(gestureDetectorListener.onScroll(e1, e2, 1.0f, 2.0f))

        val scrolling = scrollingField.getBoolean(gestureDetectorListener)
        assertTrue(scrolling)

        verify { e1 wasNot Called }
    }

    @Test
    fun gestureDetector_onFlingWhenNotScrolling_startsScrolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        every { curlAnimator.isRunning }.returns(true)
        justRun { curlAnimator.cancel() }
        view.setPrivateProperty("curlAnimator", curlAnimator)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        val scrollingField = gestureDetectorListener::class.java.getDeclaredField("scrolling")
        scrollingField.isAccessible = true
        val scrolling1 = scrollingField.getBoolean(gestureDetectorListener)
        assertFalse(scrolling1)

        val e1 = mockk<MotionEvent>(relaxed = true)
        val e2 = mockk<MotionEvent>(relaxed = true)
        assertTrue(gestureDetectorListener.onFling(e1, e2, 1.0f, 2.0f))

        val scrolling2 = scrollingField.getBoolean(gestureDetectorListener)
        assertTrue(scrolling2)

        verify { e2 wasNot Called }
    }

    @Test
    fun gestureDetector_onFlingWhenAlreadyScrolling_keepsScrolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        every { curlAnimator.isRunning }.returns(true)
        justRun { curlAnimator.cancel() }
        view.setPrivateProperty("curlAnimator", curlAnimator)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        val scrollingField = gestureDetectorListener::class.java.getDeclaredField("scrolling")
        scrollingField.isAccessible = true
        scrollingField.setBoolean(gestureDetectorListener, true)

        val e1 = mockk<MotionEvent>(relaxed = true)
        val e2 = mockk<MotionEvent>(relaxed = true)
        assertTrue(gestureDetectorListener.onFling(e1, e2, 1.0f, 2.0f))

        val scrolling = scrollingField.getBoolean(gestureDetectorListener)
        assertTrue(scrolling)

        verify { e1 wasNot Called }
    }

    @Test
    fun gestureDetector_onDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val gestureDetector: GestureDetector? = view.getPrivateProperty("gestureDetector")
        requireNotNull(gestureDetector)

        val gestureDetectorListenerField = GestureDetector::class.java.getDeclaredField("mListener")
        gestureDetectorListenerField.isAccessible = true
        val gestureDetectorListener =
            gestureDetectorListenerField.get(gestureDetector) as GestureDetector.SimpleOnGestureListener

        val scrollingField = gestureDetectorListener::class.java.getDeclaredField("scrolling")
        scrollingField.isAccessible = true
        scrollingField.setBoolean(gestureDetectorListener, true)

        val e = mockk<MotionEvent>(relaxed = true)
        gestureDetectorListener.onDown(e)

        val scrolling = scrollingField.getBoolean(gestureDetectorListener)
        assertFalse(scrolling)
    }

    @Test
    fun animateCurlRight_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val curlRenderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer1)

        // set null curl renderer
        view.setPrivateProperty("curlRenderer", null)

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 1)
    }

    @Test
    fun animateCurlRight_whenRendererAndNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 1)
    }

    @Test
    fun animateCurlRight_whenRendererAndNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 1)
    }

    @Test
    fun animateCurlRight_whenRendererLeftAndRightPagesAndOnePageViewMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 1)
    }

    @Test
    fun animateCurlRight_whenRendererLeftAndRightPagesAndTwoPageViewMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 1)
    }

    @Test
    fun animateCurlRight_whenRendererLeftAndRightPagesAndZeroNewIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlRight", 0)
    }

    @Test
    fun animateCurlLeft_whenNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val curlRenderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer1)

        // set null curl renderer
        view.setPrivateProperty("curlRenderer", null)

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlLeft", 1)
    }

    @Test
    fun animateCurlLeft_whenRendererAndNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlLeft", 1)
    }

    @Test
    fun animateCurlLeft_whenRendererAndNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlLeft", 1)
    }

    @Test
    fun animateCurlLeft_whenRendererLeftAndRightPagesAndOnePageViewMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlLeft", 1)
    }

    @Test
    fun animateCurlLeft_whenRendererLeftAndRightPagesAndTwoPageViewMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke animateCurlRight
        view.callPrivateFunc("animateCurlLeft", 1)
    }

    @Test
    fun cancelCurlAnimator_whenNoAnimator_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertNull(view.getPrivateProperty("curlAnimator"))

        view.callPrivateFunc("cancelCurlAnimator")
    }

    @Test
    fun cancelCurlAnimator_whenAnimatorNotRunning_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        every { animator.isRunning }.returns(false)
        view.setPrivateProperty("curlAnimator", animator)

        view.callPrivateFunc("cancelCurlAnimator")

        verify(exactly = 1) { animator.isRunning }
        verify(exactly = 0) { animator.cancel() }
    }

    @Test
    fun cancelCurlAnimator_whenAnimatorRunning_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        every { animator.isRunning }.returns(true)
        justRun { animator.cancel() }
        view.setPrivateProperty("curlAnimator", animator)

        view.callPrivateFunc("cancelCurlAnimator")

        verify(exactly = 1) { animator.isRunning }
        verify(exactly = 1) { animator.cancel() }
    }

    @Test
    fun setCurlPos_whenRightCurlStateAndNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNotNull(renderer1)

        view.setPrivateProperty("curlRenderer", null)

        val renderer2: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNull(renderer2)

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)
    }

    @Test
    fun setCurlPos_whenLeftCurlStateOnePageViewModeAndNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNotNull(renderer1)

        view.setPrivateProperty("curlRenderer", null)

        val renderer2: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNull(renderer2)

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererAndNoRightPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectRight", null)
        assertNull(renderer.getPrivateProperty("pageRectRight"))

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererRightPageAndPosGreaterThanRight_resetsPageCurl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectRight")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        verify(exactly = 1) { pageCurlSpy.reset() }
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererRightPageAndPosLessThanLeft_setsPosAtLeft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectRight")
        requireNotNull(pageRect)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = -1.0f
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererRightPageAndNegativeCurlDir_setsCurlDir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectRight")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = -1.0f
        curlPos.y = -1.0f
        val curlDir = PointF()
        curlDir.y = -1.0f
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
        assertEquals(-1.0f, curlPos.y, 0.0f)
        assertEquals(-1.0f, curlDir.x, 0.0f)
        assertEquals(0.0f, curlDir.y, 0.0f)

        verify(exactly = 1) { pageCurlSpy.curl(curlPos, curlDir, 1.0) }
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererRightPageAndPositiveCurlDir_setsCurlDir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectRight")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = -1.0f
        curlPos.y = 1.0f
        val curlDir = PointF()
        curlDir.y = 1.0f
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
        assertEquals(1.0f, curlPos.y, 0.0f)
        assertEquals(-1.0f, curlDir.x, 0.0f)
        assertEquals(0.0f, curlDir.y, 0.0f)

        verify(exactly = 1) { pageCurlSpy.curl(curlPos, curlDir, 1.0) }
    }

    @Test
    fun setCurlPos_whenLeftCurlStateAndNoRenderer_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer1: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNotNull(renderer1)

        view.setPrivateProperty("curlRenderer", null)

        val renderer2: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        assertNull(renderer2)

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)
    }

    @Test
    fun setCurlPos_whenLeftCurlStateRendererAndNoLeftPage_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        renderer.setPrivateProperty("pageRectLeft", null)
        assertNull(renderer.getPrivateProperty("pageRectLeft"))

        // invoke setCurlPos
        val curlPos = PointF()
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)
    }

    @Test
    fun setCurlPos_whenRightCurlStateRendererLeftPageAndPosLessThanLeft_resetsPageCurl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectLeft")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = -1.0f
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        verify(exactly = 1) { pageCurlSpy.reset() }
    }

    @Test
    fun setCurlPos_whenLeftCurlStateRendererLeftPageAndPosGreaterThanRight_setsPosAtRight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectLeft")
        requireNotNull(pageRect)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = 1.0f
        val curlDir = PointF()
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
    }

    @Test
    fun setCurlPos_whenLeftCurlStateRendererLeftPageAndNegativeCurlDir_setsCurlDir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectLeft")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = 1.0f
        curlPos.y = -1.0f
        val curlDir = PointF()
        curlDir.y = -1.0f
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
        assertEquals(-1.0f, curlPos.y, 0.0f)
        assertEquals(1.0f, curlDir.x, 0.0f)
        assertEquals(0.0f, curlDir.y, 0.0f)

        verify(exactly = 1) { pageCurlSpy.curl(curlPos, curlDir, 1.0) }
    }

    @Test
    fun setCurlPos_whenLeftCurlStateRendererLeftPageAndPositiveCurlDir_setsCurlDir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        // set right curl state
        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val renderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(renderer)

        val pageRect: RectF? = renderer.getPrivateProperty("pageRectLeft")
        requireNotNull(pageRect)

        // set pgeCurl spy
        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)

        val pageCurlSpy = spyk(pageCurl)
        view.setPrivateProperty("pageCurl", pageCurlSpy)

        // invoke setCurlPos
        val curlPos = PointF()
        curlPos.x = 1.0f
        curlPos.y = 1.0f
        val curlDir = PointF()
        curlDir.y = 1.0f
        view.callPrivateFunc("setCurlPos", curlPos, curlDir, 1.0)

        assertEquals(0.0f, curlPos.x, 0.0f)
        assertEquals(1.0f, curlPos.y, 0.0f)
        assertEquals(1.0f, curlDir.x, 0.0f)
        assertEquals(0.0f, curlDir.y, 0.0f)

        verify(exactly = 1) { pageCurlSpy.curl(curlPos, curlDir, 1.0) }
    }

    @Test
    fun startCurl_whenNoPageLeft_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageLeft", null)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun startCurl_whenNoPageRight_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageRight", null)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun startCurl_whenNoPageCurl_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageCurl", null)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_NONE, view.curlState)
    }

    @Test
    fun startCurl_whenNoRendererCurlLeft_setsCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
    }

    @Test
    fun startCurl_whenNoRendererCurlRight_setsCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("curlRenderer", null)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
    }

    @Test
    fun startCurl_whenRendererCurlLeft_setsCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertNotNull(view.getPrivateProperty("curlRenderer"))

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)
    }

    @Test
    fun startCurl_whenRendererCurlRight_setsCurlState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        assertNotNull(view.getPrivateProperty("curlRenderer"))

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)
    }

    @Test
    fun startCurl_whenCurlRightCurrentIndexGreaterThanZeroAndRenderLeftPage_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)
        val pageRight: CurlMesh? = view.getPrivateProperty("pageRight")
        requireNotNull(pageRight)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)

        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageRight) }
    }

    @Test
    fun startCurl_whenCurlRightCurrentIndexGreaterThanZeroAndNotRenderLeftPage_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = false

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageLeft: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageLeft)
        val pageRight: CurlMesh? = view.getPrivateProperty("pageRight")
        requireNotNull(pageRight)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)

        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 0) { curlRendererSpy.addCurlMesh(pageLeft) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageRight) }
    }

    @Test
    fun startCurl_whenCurlRightZeroCurrentIndexAndTargetLessThanPageCount_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true

        every { pageProvider.pageCount }.returns(2)
        justRun { pageProvider.updatePage(any(), any(), any(), any(), any()) }
        view.pageProvider = pageProvider

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 0)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageRight: CurlMesh? = view.getPrivateProperty("pageRight")
        requireNotNull(pageRight)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_RIGHT, 1)

        assertEquals(CurlGLSurfaceView.CURL_RIGHT, view.curlState)

        verify(exactly = 2) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageRight) }
    }

    @Test
    fun startCurl_whenCurlLeftTargetIndexGreaterThanZeroRenderLeftPageAndOnePageViewMode_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)

        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
    }

    @Test
    fun startCurl_whenCurlLeftTargetIndexGreaterThanZeroRenderLeftPageAndTwoPageViewModeAndCurlLeftState_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)

        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
    }

    @Test
    fun startCurl_whenCurlLeftTargetIndexGreaterThanZeroNoRenderLeftPage_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = false
        view.viewMode = CurlGLSurfaceView.SHOW_ONE_PAGE

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)

        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 0) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
    }

    @Test
    fun startCurl_whenCurlLeftTargetIndexGreaterThanZeroRenderLeftPageAndTwoPageViewMode_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)

        verify(exactly = 2) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 0) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
    }

    @Test
    fun startCurl_whenCurlLeftTargetIndexGreaterThanZeroRenderLeftPageCurrentIndexLessThanPageCountAndTwoPageViewMode_addsMeshes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)
        view.renderLeftPage = true
        view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES

        every { pageProvider.pageCount }.returns(2)
        justRun { pageProvider.updatePage(any(), any(), any(), any(), any()) }
        view.pageProvider = pageProvider

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.setPrivateProperty("currentIndex", 1)

        val pageCurl: CurlMesh? = view.getPrivateProperty("pageCurl")
        requireNotNull(pageCurl)
        val pageLeft: CurlMesh? = view.getPrivateProperty("pageLeft")
        requireNotNull(pageLeft)
        val pageRight: CurlMesh? = view.getPrivateProperty("pageRight")
        requireNotNull(pageRight)

        view.callPrivateFunc("startCurl", CurlGLSurfaceView.CURL_LEFT, 1)

        assertEquals(CurlGLSurfaceView.CURL_LEFT, view.curlState)

        verify(exactly = 2) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_LEFT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageCurl) }
        verify(exactly = 1) { curlRendererSpy.getPageRect(CurlRenderer.PAGE_RIGHT) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageLeft) }
        verify(exactly = 1) { curlRendererSpy.addCurlMesh(pageRight) }
    }

    @Test
    fun updatePages_whenNegativePageBitmapWidth_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        val pageBitmapWidth: Int? = view.getPrivateProperty("pageBitmapWidth")
        requireNotNull(pageBitmapWidth)
        assertEquals(-1, pageBitmapWidth)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNegativePageBitmapHeight_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)

        val pageBitmapHeight: Int? = view.getPrivateProperty("pageBitmapHeight")
        requireNotNull(pageBitmapHeight)
        assertEquals(-1, pageBitmapHeight)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNoPageProvider_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        assertNull(view.pageProvider)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNoPageLeft_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        view.setPrivateProperty("pageLeft", null)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNoPageRight_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        view.setPrivateProperty("pageRight", null)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNoPageCurl_makesNoAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        view.setPrivateProperty("pageCurl", null)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify { curlRendererSpy wasNot Called }
    }

    @Test
    fun updatePages_whenNoCurlState_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 3) { curlRendererSpy.removeCurlMesh(any()) }
    }

    @Test
    fun updatePages_whenLeftCurlState_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 3) { curlRendererSpy.removeCurlMesh(any()) }
    }

    @Test
    fun updatePages_whenRightCurlState_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        view.pageProvider = pageProvider

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 3) { curlRendererSpy.removeCurlMesh(any()) }
    }

    @Test
    fun updatePages_whenRightIdxInRange_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 2) { curlRendererSpy.addCurlMesh(any()) }
    }

    @Test
    fun updatePages_whenLeftIdxInRange_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setPrivateProperty("currentIndex", 1)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_RIGHT)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 2) { curlRendererSpy.addCurlMesh(any()) }
    }

    @Test
    fun updatePages_whenCurlIdxInRange_callsRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = CurlGLSurfaceView(context)

        view.setPrivateProperty("pageBitmapWidth", 1)
        view.setPrivateProperty("pageBitmapHeight", 1)

        val pageProvider = mockk<CurlGLSurfaceView.PageProvider>(relaxed = true)
        every { pageProvider.pageCount }.returns(2)
        view.pageProvider = pageProvider

        view.setPrivateProperty("currentIndex", 1)

        view.setPrivateProperty("curlState", CurlGLSurfaceView.CURL_LEFT)

        val curlRenderer: CurlRenderer? = view.getPrivateProperty("curlRenderer")
        requireNotNull(curlRenderer)

        val curlRendererSpy = spyk(curlRenderer)
        view.setPrivateProperty("curlRenderer", curlRendererSpy)

        view.callPrivateFunc("updatePages", null, null)

        verify(exactly = 2) { curlRendererSpy.addCurlMesh(any()) }
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920

        const val ANIMATION_DURATION_MILLIS = 10000
    }
}
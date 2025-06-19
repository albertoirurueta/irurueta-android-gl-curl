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
package com.irurueta.android.gl.curl.test

import android.content.res.Resources
import android.graphics.*
import android.opengl.GLSurfaceView
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.internal.runner.junit4.statement.UiThreadStatement
import com.irurueta.android.gl.curl.InstrumentationTestHelper
import com.irurueta.android.gl.curl.CurlGLSurfaceView
import com.irurueta.android.gl.curl.CurlPage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RequiresDevice
@RunWith(AndroidJUnit4::class)
class CurlGLSurfaceViewTest {

    @get:Rule
    val rule = activityScenarioRule<CurlGLSurfaceViewActivity>()

    private var activity: CurlGLSurfaceViewActivity? = null
    private var view: CurlGLSurfaceView? = null

    private var textView: TextView? = null

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private var currentIndex = 0
    private var pageClicked = 0
    private var sizeChanged = 0

    private var bitmaps = arrayOfNulls<Bitmap>(3)

    private val pageProvider = object : CurlGLSurfaceView.PageProvider {
        override val pageCount: Int
            get() = PAGE_COUNT

        override fun updatePage(
            page: CurlPage,
            width: Int,
            height: Int,
            index: Int,
            backIndex: Int?
        ) {
            // Load new bitmap for curl page view.
            // In real world scenario, images should be cached in memory for
            // performance reasons.
            val bitmap = loadBitmap(width, height, index)
            if (backIndex == null) {
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            } else {
                // load bitmap for back side
                val backBitmap = loadBitmap(width, height, backIndex)
                page.setTexture(bitmap, CurlPage.SIDE_FRONT)
                page.setTexture(backBitmap, CurlPage.SIDE_BACK)
            }

            // set semi transparent white for background to get a bit of transparency
            // on back images when being flipped
            val context = view?.context ?: return
            page.setColor(
                ContextCompat.getColor(context, R.color.translucid_white),
                CurlPage.SIDE_BACK
            )
        }
    }

    private val sizeChangeObserver = object : CurlGLSurfaceView.SizeChangedObserver {
        override fun onSizeChanged(width: Int, height: Int) {
            lock.withLock {
                this@CurlGLSurfaceViewTest.sizeChanged++
                condition.signalAll()
            }
        }
    }

    private val currentIndexChangeListener =
        object : CurlGLSurfaceView.CurrentIndexChangedListener {
            override fun onCurrentIndexChanged(view: CurlGLSurfaceView, currentIndex: Int) {
                lock.withLock {
                    this@CurlGLSurfaceViewTest.currentIndex++
                    condition.signalAll()
                }
            }
        }

    private val pageClickListener = object : CurlGLSurfaceView.PageClickListener {
        override fun onPageClick(view: CurlGLSurfaceView, currentIndex: Int): Boolean {
            lock.withLock {
                this@CurlGLSurfaceViewTest.pageClicked++
                condition.signalAll()
            }
            return true
        }
    }

    @Before
    fun setUp() {
        rule.scenario.onActivity { activity ->
            this.activity = activity
            view = activity.findViewById(R.id.curl_gl_surface_view_test)
            textView = activity?.findViewById(R.id.title)
            reset()

            loadAllBitmaps()
        }
    }

    @After
    fun tearDown() {
        unloadAllBitmaps()

        view = null
        activity = null
        reset()
    }

    @Test
    fun constructor_whenNotAttached_setsDefaultValues() {
        val activity = this.activity ?: return fail()
        UiThreadStatement.runOnUiThread {
            val view = CurlGLSurfaceView(activity)

            assertTrue(view.allowLastPageCurl)
            assertEquals(
                CurlGLSurfaceView.ANIMATION_DURATION_MILLIS,
                view.animationDurationTime
            )
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
            assertEquals(0, view.debugFlags)
            assertFalse(view.preserveEGLContextOnPause)
            assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, view.renderMode)
        }
    }

    @Test
    fun constructor_whenAttached_setsDefaultValues() {
        val view = this.view ?: return fail()
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
        assertEquals(0, view.debugFlags)
        assertFalse(view.preserveEGLContextOnPause)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, view.renderMode)
    }

    @Test
    fun pageProvider_setsExpectedValue() {
        val view = this.view ?: return fail()
        view.pageProvider = pageProvider

        assertSame(pageProvider, view.pageProvider)
    }

    @Test
    fun sizeChangeObserver_setsExpectedValue() {
        val view = this.view ?: return fail()
        view.pageProvider = pageProvider
        view.sizeChangedObserver = sizeChangeObserver

        assertSame(sizeChangeObserver, view.sizeChangedObserver)

        // change size
        val width = view.width
        val height = view.height

        assertTrue(width > 0)
        assertTrue(height > 0)

        val newWidth = width / 2
        val newHeight = height / 2

        view.measure(
            View.MeasureSpec.makeMeasureSpec(newWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(newHeight, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, newWidth, newHeight)

        waitOnCondition({ sizeChanged == 0 })

        assertEquals(1, sizeChanged)
    }

    @Test
    fun currentIndexChangedListener_whenSinglePageModeNotAnimated_setsExpectedValue() {
        val view = this.view ?: return fail()
        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        Thread.sleep(SLEEP)

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)

        Thread.sleep(SLEEP)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setCurrentIndex(2)
        }

        waitOnCondition({ currentIndex == 1 })

        assertEquals(2, view.currentIndex)

        Thread.sleep(SLEEP)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setCurrentIndex(0)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(0, view.currentIndex)

        Thread.sleep(SLEEP)
    }

    @Test
    fun currentIndexChangedListener_whenOnePageAnimated_setsExpectedValue() {
        val view = this.view ?: return fail()
        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertTrue(view.allowLastPageCurl)

        reset()

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(2)
        }

        waitOnCondition({ currentIndex == 1 })

        assertEquals(2, view.currentIndex)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(0)
        }

        waitOnCondition({ currentIndex == 2 })

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun currentIndexChangedListener_whenTwoPageAnimated_setsExpectedValue() {
        val view = this.view ?: return fail()
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)

        UiThreadStatement.runOnUiThread {
            view.viewMode = CurlGLSurfaceView.SHOW_TWO_PAGES
        }

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_TWO_PAGES, view.viewMode)
        assertTrue(view.allowLastPageCurl)

        reset()

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(2)
        }

        waitOnCondition({ currentIndex == 1 })

        assertEquals(2, view.currentIndex)

        // change current index again
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(0)
        }

        waitOnCondition({ currentIndex == 2 })

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun pageClickListener_whenPageClick_isNotified() {
        val view = this.view ?: return fail()
        view.pageProvider = pageProvider
        view.pageClickListener = pageClickListener

        assertSame(pageClickListener, view.pageClickListener)

        // perform click
        InstrumentationTestHelper.tap(view)

        waitOnCondition({ pageClicked == 0 })

        assertEquals(1, pageClicked)
    }

    @Test
    fun setMargins_drawsPagesWithTransparentBackground() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertTrue(view.allowLastPageCurl)

        reset()

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun setProportionalMargins_drawsPagesWithTransparentBackground() {
        val view = this.view ?: return fail()

        UiThreadStatement.runOnUiThread {
            view.setProportionalMargins(0.1f, 0.1f, 0.1f, 0.1f)
        }

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertTrue(view.allowLastPageCurl)

        reset()

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun drag_whenLastPageCurlAllowed_changesPage() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertTrue(view.allowLastPageCurl)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)

        // drag to next page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 1 })

        assertEquals(2, view.currentIndex)

        // drag to next page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 2 })

        assertEquals(3, view.currentIndex)

        // curl last page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        // drag to previous page
        val fromX2 = viewLeft + view.width / 3
        val fromY2 = viewTop + view.height / 2
        val toX2 = viewLeft + view.width - margin
        val toY2 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX2, fromY2, toX2, toY2)

        waitOnCondition({ currentIndex == 3 })

        Thread.sleep(SLEEP)

        assertEquals(2, view.currentIndex)

        // drag to previous page again
        InstrumentationTestHelper.drag(fromX2, fromY2, toX2, toY2)

        Thread.sleep(SLEEP)

        assertEquals(1, view.currentIndex)

        // drag to previous page again
        InstrumentationTestHelper.drag(fromX2, fromY2, toX2, toY2)

        Thread.sleep(SLEEP)

        assertEquals(0, view.currentIndex)
    }

    @Test
    fun drag_whenLastPageCurlDisallowed_changesPage() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener
        view.allowLastPageCurl = false

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.SHOW_ONE_PAGE, view.viewMode)
        assertFalse(view.allowLastPageCurl)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)

        // drag to next page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 1 })

        assertEquals(2, view.currentIndex)

        // drag to next page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        assertEquals(2, view.currentIndex)

        // attempt to curl last page
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        // doesn't change page
        assertEquals(2, view.currentIndex)
    }

    @Test
    fun maxCurlSplitsInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()

        assertEquals(0, view.currentIndex)
        assertEquals(CurlGLSurfaceView.MAX_CURL_SPLITS_IN_MESH, view.maxCurlSplitsInMesh)

        UiThreadStatement.runOnUiThread {
            view.maxCurlSplitsInMesh = 20
        }

        assertEquals(20, view.maxCurlSplitsInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        // change current index
        UiThreadStatement.runOnUiThread {
            view.setSmoothCurrentIndex(1)
        }

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun drawCurlPositionInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertFalse(view.drawCurlPositionInMesh)

        UiThreadStatement.runOnUiThread {
            view.drawCurlPositionInMesh = true
        }

        assertTrue(view.drawCurlPositionInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun drawPolygonOutlinesInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertFalse(view.drawPolygonOutlinesInMesh)

        UiThreadStatement.runOnUiThread {
            view.drawPolygonOutlinesInMesh = true
        }

        assertTrue(view.drawPolygonOutlinesInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun drawShadowInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertTrue(view.drawShadowInMesh)

        UiThreadStatement.runOnUiThread {
            view.drawShadowInMesh = false
        }

        assertFalse(view.drawShadowInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun drawTextureInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertTrue(view.drawTextureInMesh)

        UiThreadStatement.runOnUiThread {
            view.drawTextureInMesh = false
        }

        assertFalse(view.drawTextureInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun shadowInnerColorInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertTrue(CurlGLSurfaceView.SHADOW_INNER_COLOR_IN_MESH.contentEquals(view.shadowInnerColorInMesh))

        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.5f)
        UiThreadStatement.runOnUiThread {
            view.shadowInnerColorInMesh = color
        }

        assertSame(color, view.shadowInnerColorInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun shadowOuterColorInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertTrue(CurlGLSurfaceView.SHADOW_OUTER_COLOR_IN_MESH.contentEquals(view.shadowOuterColorInMesh))

        val color = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        UiThreadStatement.runOnUiThread {
            view.shadowOuterColorInMesh = color
        }

        assertSame(color, view.shadowOuterColorInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    @Test
    fun colorFactorOffsetInMesh_setsExpectedValue() {
        val view = this.view ?: return fail()
        val textView = this.textView ?: return fail()
        val topMargin = textView.measuredHeight
        val margin = dp2px(MARGIN_DP)

        UiThreadStatement.runOnUiThread {
            view.setMargins(margin, topMargin, margin, margin)
        }

        assertEquals(0, view.currentIndex)
        assertEquals(
            CurlGLSurfaceView.DEFAULT_COLOR_FACTOR_OFFSET_IN_MESH,
            view.colorFactorOffsetInMesh
        )

        UiThreadStatement.runOnUiThread {
            view.colorFactorOffsetInMesh = 1.0f
        }

        assertEquals(1.0f, view.colorFactorOffsetInMesh)

        view.pageProvider = pageProvider
        view.currentIndexChangedListener = currentIndexChangeListener

        assertSame(currentIndexChangeListener, view.currentIndexChangedListener)

        reset()

        val xy = IntArray(2)
        view.getLocationOnScreen(xy)

        val viewLeft = xy[0]
        val viewTop = xy[1]

        // drag to next page
        val fromX1 = viewLeft + view.width / 2
        val fromY1 = viewTop + view.height - margin
        val toX1 = viewLeft + margin
        val toY1 = viewTop + topMargin
        InstrumentationTestHelper.drag(fromX1, fromY1, toX1, toY1)

        waitOnCondition({ currentIndex == 0 })

        assertEquals(1, view.currentIndex)
    }

    private fun loadAllBitmaps() {
        // bitmaps need to be loaded in memory first for performance reasons
        val drawables = listOf(R.drawable.image1, R.drawable.image2, R.drawable.image3)
        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            if (bitmap == null || bitmap.isRecycled) {
                bitmaps[i] = BitmapFactory.decodeResource(activity?.resources, drawables[i])
            }
        }
    }

    private fun unloadAllBitmaps() {
        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            bitmaps[i] = null
        }
    }

    private fun loadBitmap(width: Int, height: Int, index: Int): Bitmap? {
        val bitmap = bitmaps[index] ?: return null

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        val rect = Rect(0, 0, width, height)

        val imageHeight = rect.height()
        val scale = imageHeight.toFloat() / bitmapHeight.toFloat()
        val imageWidth = (scale * bitmapWidth.toFloat()).toInt()

        // center image on page
        rect.left += ((rect.width() - imageWidth) / 2)
        rect.right = rect.left + imageWidth
        rect.top += ((rect.height() - imageHeight) / 2)
        rect.bottom = rect.top + imageHeight

        // draw resized bitmap using canvas
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        val canvas = Canvas(b)
        canvas.drawBitmap(bitmap, null, rect, null)

        return b
    }

    private fun reset() {
        currentIndex = 0
        pageClicked = 0
        sizeChanged = 0
    }

    private fun waitOnCondition(
        condition: () -> Boolean,
        maxRetries: Int = MAX_RETRIES,
        timeout: Long = TIMEOUT_MILLIS
    ) {
        lock.withLock {
            var count = 0
            while (condition() && count < maxRetries) {
                this.condition.await(timeout, TimeUnit.MILLISECONDS)
                count++
            }
        }
    }

    private companion object {
        const val SLEEP = 2000L
        const val PAGE_COUNT = 3
        const val MAX_RETRIES = 2
        const val TIMEOUT_MILLIS = 10000L

        const val MARGIN_DP = 20.0f

        @Suppress("SameParameterValue")
        private fun dp2px(dp: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                Resources.getSystem().displayMetrics
            ).toInt()
        }
    }
}
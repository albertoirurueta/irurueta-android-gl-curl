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
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@RunWith(RobolectricTestRunner::class)
class CurlRendererTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var observer: CurlRenderer.Observer

    @MockK
    private lateinit var gl: GL10

    @MockK
    private lateinit var config: EGLConfig

    @After
    fun afterTest() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun constructor_whenNoParameters_setsDefaultValues() {
        val renderer = CurlRenderer()

        assertNull(renderer.getPrivateProperty("observer"))
        assertFalse(renderer.usePerspectiveProjection)
        assertEquals(Color.TRANSPARENT, renderer.backgroundColor)
        assertNotNull(renderer.getPrivateProperty("curlMeshes"))
        assertNotNull(renderer.getPrivateProperty("margins"))
        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))
        val viewMode: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_ONE_PAGE, viewMode)
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(0, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(0, viewportHeight)
        assertNotNull(renderer.getPrivateProperty("viewRect"))
    }

    @Test
    fun constructor_whenProvidedParameters_setsDefaultValues() {
        val renderer = CurlRenderer(observer, true)

        assertSame(observer, renderer.getPrivateProperty("observer"))
        assertTrue(renderer.usePerspectiveProjection)
        assertEquals(Color.TRANSPARENT, renderer.backgroundColor)
        val curlMeshes: Vector<CurlMesh>? = renderer.getPrivateProperty("curlMeshes")
        requireNotNull(curlMeshes)
        assertTrue(curlMeshes.isEmpty())
        assertNotNull(renderer.getPrivateProperty("margins"))
        assertNotNull(renderer.getPrivateProperty("pageRectLeft"))
        assertNotNull(renderer.getPrivateProperty("pageRectRight"))
        val viewMode: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_ONE_PAGE, viewMode)
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(0, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(0, viewportHeight)
        assertNotNull(renderer.getPrivateProperty("viewRect"))
    }

    @Test
    fun backgroundColor_returnsExpectedValue() {
        val renderer = CurlRenderer()

        // check default value
        assertEquals(Color.TRANSPARENT, renderer.backgroundColor)

        // set new value
        renderer.backgroundColor = Color.RED

        // check
        assertEquals(Color.RED, renderer.backgroundColor)
    }

    @Test
    fun addCurlMesh_addsProvidedCurlMesh() {
        val renderer = CurlRenderer()

        // check default value
        val curlMeshes: Vector<CurlMesh>? = renderer.getPrivateProperty("curlMeshes")
        requireNotNull(curlMeshes)
        assertTrue(curlMeshes.isEmpty())

        // add
        val mesh = CurlMesh(MAX_CURL_SPLITS)
        renderer.addCurlMesh(mesh)

        // check
        assertFalse(curlMeshes.isEmpty())
        assertTrue(curlMeshes.contains(mesh))
    }

    @Test
    fun getPageRect_returnsExpectedValues() {
        val renderer = CurlRenderer()

        val pageRectLeft: RectF? = renderer.getPrivateProperty("pageRectLeft")
        val pageRectRight: RectF? = renderer.getPrivateProperty("pageRectRight")

        requireNotNull(pageRectLeft)
        requireNotNull(pageRectRight)

        assertSame(pageRectLeft, renderer.getPageRect(CurlRenderer.PAGE_LEFT))
        assertSame(pageRectRight, renderer.getPageRect(CurlRenderer.PAGE_RIGHT))
        assertNull(renderer.getPageRect(0))
    }

    @Test
    fun onDrawFrame_whenNoGL_makesNoAction() {
        val renderer = CurlRenderer(observer)

        renderer.onDrawFrame(null)

        verify { observer wasNot Called }
    }

    @Test
    fun onDrawFrame_whenGlAndNoPerspectiveProjectionAndNoCurlMeshes_drawsFrame() {
        justRun { observer.onDrawFrame() }
        val renderer = CurlRenderer(observer)

        justRun { gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) }
        justRun { gl.glClear(GL10.GL_COLOR_BUFFER_BIT) }
        justRun { gl.glLoadIdentity() }

        renderer.onDrawFrame(gl)

        verify(exactly = 1) { observer.onDrawFrame() }
    }

    @Test
    fun onDrawFrame_whenPerspectiveProjection_drawsFrame() {
        val renderer = CurlRenderer(usePerspectiveProjection = true)

        justRun { gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) }
        justRun { gl.glClear(GL10.GL_COLOR_BUFFER_BIT) }
        justRun { gl.glLoadIdentity() }
        justRun { gl.glTranslatef(0.0f, 0.0f, -6.0f) }

        renderer.onDrawFrame(gl)

        verify(exactly = 1) { gl.glTranslatef(0.0f, 0.0f, -6.0f) }
    }

    @Test
    fun onDrawFrame_whenCurlMeshes_drawsFrame() {
        val renderer = CurlRenderer()

        justRun { gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) }
        justRun { gl.glClear(GL10.GL_COLOR_BUFFER_BIT) }
        justRun { gl.glLoadIdentity() }

        // add mesh
        val mesh = mockk<CurlMesh>()
        justRun { mesh.onDrawFrame(any()) }
        renderer.addCurlMesh(mesh)

        renderer.onDrawFrame(gl)

        verify(exactly = 1) { mesh.onDrawFrame(gl) }
    }

    @Test
    fun onSurfaceChanged_whenNoGL_makesNoAction() {
        val renderer = CurlRenderer(observer)

        renderer.onSurfaceChanged(null, WIDTH, HEIGHT)

        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(0, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(0, viewportHeight)

        verify { observer wasNot Called }
    }

    @Test
    fun onSurfaceChanged_whenGLOnePageModeAndOrtho_reloadsCamera() {
        justRun { observer.onPageSizeChanged(any(), any()) }
        val renderer = CurlRenderer(observer)

        val viewMode: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_ONE_PAGE, viewMode)
        assertFalse(renderer.usePerspectiveProjection)

        justRun { gl.glViewport(0, 0, WIDTH, HEIGHT) }
        justRun { gl.glMatrixMode(GL10.GL_PROJECTION) }
        justRun { gl.glLoadIdentity() }
        justRun { gl.glOrthof(any(), any(), any(), any(), any(), any()) }
        justRun { gl.glMatrixMode(GL10.GL_MODELVIEW) }

        // execute onSurfaceChanged
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        verify(exactly = 1) { observer.onPageSizeChanged(WIDTH, HEIGHT) }
    }

    @Test
    fun onSurfaceChanged_whenGLTwoPagesModeAndProjective_reloadsCamera() {
        justRun { observer.onPageSizeChanged(any(), any()) }
        val renderer = CurlRenderer(observer, usePerspectiveProjection = true)
        renderer.setViewMode(CurlRenderer.SHOW_TWO_PAGES)

        val viewMode: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_TWO_PAGES, viewMode)
        assertTrue(renderer.usePerspectiveProjection)

        justRun { gl.glViewport(0, 0, WIDTH, HEIGHT) }
        justRun { gl.glMatrixMode(GL10.GL_PROJECTION) }
        justRun { gl.glLoadIdentity() }
        justRun { gl.glFrustumf(any(), any(), any(), any(), any(), any()) }
        justRun { gl.glMatrixMode(GL10.GL_MODELVIEW) }

        // execute onSurfaceChanged
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        verify(exactly = 1) { observer.onPageSizeChanged(WIDTH / 2, HEIGHT) }
    }

    @Test
    fun onSurfaceCreate_whenNoGl_makesNoAction() {
        val renderer = CurlRenderer(observer)

        renderer.onSurfaceCreated(null, config)

        verify { observer wasNot Called }
    }

    @Test
    fun onSurfaceCreated_whenGl_setupsGl() {
        justRun { observer.onSurfaceCreated() }
        val renderer = CurlRenderer(observer)

        justRun { gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f) }
        justRun { gl.glShadeModel(GL10.GL_SMOOTH) }
        justRun { gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST) }
        justRun { gl.glHint(GL10.GL_LINE_SMOOTH_HINT, GL10.GL_NICEST) }
        justRun { gl.glHint(GL10.GL_POLYGON_SMOOTH_HINT, GL10.GL_NICEST) }
        justRun { gl.glEnable(GL10.GL_LINE_SMOOTH) }
        justRun { gl.glDisable(GL10.GL_DEPTH_TEST) }
        justRun { gl.glDisable(GL10.GL_CULL_FACE) }
        renderer.onSurfaceCreated(gl, config)

        verify(exactly = 1) { observer.onSurfaceCreated() }
    }

    @Test
    fun removeCurlMesh_removesProvidedMesh() {
        val renderer = CurlRenderer()

        // check default value
        val curlMeshes: Vector<CurlMesh>? = renderer.getPrivateProperty("curlMeshes")
        requireNotNull(curlMeshes)
        assertTrue(curlMeshes.isEmpty())

        // add mesh
        val mesh = CurlMesh(MAX_CURL_SPLITS)
        renderer.addCurlMesh(mesh)

        // check
        assertFalse(curlMeshes.isEmpty())
        assertTrue(curlMeshes.contains(mesh))

        // remove mesh
        assertTrue(renderer.removeCurlMesh(mesh))

        // check
        assertTrue(curlMeshes.isEmpty())
        assertFalse(curlMeshes.contains(mesh))

        // remove again
        assertFalse(renderer.removeCurlMesh(mesh))

        // check
        assertTrue(curlMeshes.isEmpty())
        assertFalse(curlMeshes.contains(mesh))
    }

    @Test
    fun setMargins_setsExpectedMargins() {
        val renderer = CurlRenderer()
        val gl = mockk<GL10>(relaxUnitFun = true)

        // initialize viewport size
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
        renderer.setMargins(100, 200, 300, 400)

        // check
        assertEquals(100.0f / WIDTH, margins.left, 0.0f)
        assertEquals(200.0f / HEIGHT, margins.top, 0.0f)
        assertEquals(300.0f / WIDTH, margins.right, 0.0f)
        assertEquals(400.0f / HEIGHT, margins.bottom, 0.0f)
    }

    @Test
    fun setProportionalMargins_setsExpectedMargins() {
        val renderer = CurlRenderer()
        val gl = mockk<GL10>(relaxUnitFun = true)

        // initialize viewport size
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

        // set proportional margins
        renderer.setProportionalMargins(100.0f, 200.0f, 300.0f, 400.0f)

        // check
        assertEquals(100.0f, margins.left, 0.0f)
        assertEquals(200.0f, margins.top, 0.0f)
        assertEquals(300.0f, margins.right, 0.0f)
        assertEquals(400.0f, margins.bottom, 0.0f)
    }

    @Test
    fun setViewMode_setsExpectedViewMode() {
        val renderer = CurlRenderer()

        val viewMode1: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_ONE_PAGE, viewMode1)

        // set new view mode
        renderer.setViewMode(CurlRenderer.SHOW_TWO_PAGES)

        // check
        val viewMode2: Int? = renderer.getPrivateProperty("viewMode")
        assertEquals(CurlRenderer.SHOW_TWO_PAGES, viewMode2)
    }

    @Test
    fun translate_setsExpectedValue() {
        val renderer = CurlRenderer()
        val gl = mockk<GL10>(relaxUnitFun = true)

        // initialize viewport size
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        val ratio = WIDTH.toFloat() / HEIGHT.toFloat()
        val viewRect: RectF? = renderer.getPrivateProperty("viewRect")
        requireNotNull(viewRect)
        assertEquals(1.0f, viewRect.top, 0.0f)
        assertEquals(-1.0f, viewRect.bottom, 0.0f)
        assertEquals(-ratio, viewRect.left, 0.0f)
        assertEquals(ratio, viewRect.right, 0.0f)

        // translate
        val result = PointF(100.0f, 200.0f)
        renderer.translate(result)

        assertEquals(result.x, -ratio + (2.0f * ratio * 100.0f / WIDTH), 0.0f)
        assertEquals(result.y, 1.0f + (-2.0f * 200.0f / HEIGHT), 0.0f)
    }

    @Test
    fun inverseTranslateX_returnsExpectedValue() {
        val renderer = CurlRenderer()
        val gl = mockk<GL10>(relaxUnitFun = true)

        // initialize viewport size
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        val ratio = WIDTH.toFloat() / HEIGHT.toFloat()
        val viewRect: RectF? = renderer.getPrivateProperty("viewRect")
        requireNotNull(viewRect)
        assertEquals(1.0f, viewRect.top, 0.0f)
        assertEquals(-1.0f, viewRect.bottom, 0.0f)
        assertEquals(-ratio, viewRect.left, 0.0f)
        assertEquals(ratio, viewRect.right, 0.0f)

        // translate
        val result = renderer.inverseTranslateX(100.0f)

        assertEquals(WIDTH * (100.0f + ratio) / (2.0f * ratio), result, 0.0f)
    }

    @Test
    fun inverseTranslateY_returnsExpectedValue() {
        val renderer = CurlRenderer()
        val gl = mockk<GL10>(relaxUnitFun = true)

        // initialize viewport size
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)
        renderer.onSurfaceChanged(gl, WIDTH, HEIGHT)

        // check
        val viewportWidth: Int? = renderer.getPrivateProperty("viewportWidth")
        assertEquals(WIDTH, viewportWidth)
        val viewportHeight: Int? = renderer.getPrivateProperty("viewportHeight")
        assertEquals(HEIGHT, viewportHeight)

        val ratio = WIDTH.toFloat() / HEIGHT.toFloat()
        val viewRect: RectF? = renderer.getPrivateProperty("viewRect")
        requireNotNull(viewRect)
        assertEquals(1.0f, viewRect.top, 0.0f)
        assertEquals(-1.0f, viewRect.bottom, 0.0f)
        assertEquals(-ratio, viewRect.left, 0.0f)
        assertEquals(ratio, viewRect.right, 0.0f)

        // translate
        val result = renderer.inverseTranslateY(100.0f)

        assertEquals(HEIGHT * (100.0f - 1.0f) / -2.0f, result, 0.0f)
    }

    private companion object {
        const val MAX_CURL_SPLITS = 10

        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
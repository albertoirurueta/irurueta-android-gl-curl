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

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.justRun
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.microedition.khronos.opengles.GL10

@RunWith(RobolectricTestRunner::class)
class CurlMeshTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var gl: GL10

    @Test
    fun constructor_whenDefaultValues_setsExpectedValues() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // check values
        assertEquals(MAX_CURL_SPLITS, mesh.getPrivateProperty("maxCurlSplits"))
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)
        assertEquals(CurlMesh.SHADOW_INNER_COLOR, mesh.shadowInnerColor)
        assertEquals(CurlMesh.SHADOW_OUTER_COLOR, mesh.shadowOuterColor)
        assertNotNull(mesh.texturePage)
        assertEquals(
            CurlMesh.DEFAULT_COLOR_FACTOR_OFFSET,
            mesh.getPrivateProperty("colorFactorOffset")
        )
        assertNotNull(mesh.getPrivateProperty("arrScanLines"))
        assertNotNull(mesh.getPrivateProperty("arrOutputVertices"))
        assertNotNull(mesh.getPrivateProperty("arrRotatedVertices"))
        assertNotNull(mesh.getPrivateProperty("arrIntersections"))
        assertNotNull(mesh.getPrivateProperty("arrTempVertices"))
        assertNotNull(mesh.getPrivateProperty("arrSelfShadowVertices"))
        assertNotNull(mesh.getPrivateProperty("arrDropShadowVertices"))
        assertNotNull(mesh.getPrivateProperty("arrTempShadowVertices"))
        val rectangle: Array<*>? = mesh.getPrivateProperty("rectangle")
        requireNotNull(rectangle)
        assertEquals(4, rectangle.size)
        for (i in 0..3) {
            assertNotNull(rectangle[i])
        }

        val curlPositionLinesCount: Int? = mesh.getPrivateProperty("curlPositionLinesCount")
        requireNotNull(curlPositionLinesCount)
        assertEquals(0, curlPositionLinesCount)
        assertNull(mesh.getPrivateProperty("bufCurlPositionLines"))

        assertNotNull(mesh.getPrivateProperty("bufVertices"))

        assertNotNull(mesh.getPrivateProperty("bufTexCoords"))

        assertNotNull(mesh.getPrivateProperty("bufColors"))

        assertNotNull(mesh.getPrivateProperty("bufShadowColors"))
        assertNotNull(mesh.getPrivateProperty("bufShadowVertices"))

        val dropShadowCount: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount)

        val selfShadowCount: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount)
    }

    @Test
    fun constructor_whenNotDefaultValues_setsExpectedValues() {
        val mesh = CurlMesh(
            MAX_CURL_SPLITS,
            drawCurlPosition = true,
            drawPolygonOutlines = true,
            drawShadow = false,
            drawTexture = false,
            shadowInnerColor = SHADOW_INNER_COLOR,
            shadowOuterColor = SHADOW_OUTER_COLOR,
            colorFactorOffset = COLOR_FACTOR_OFFSET
        )

        // check values
        assertEquals(MAX_CURL_SPLITS, mesh.getPrivateProperty("maxCurlSplits"))
        assertTrue(mesh.drawCurlPosition)
        assertTrue(mesh.drawPolygonOutlines)
        assertFalse(mesh.drawShadow)
        assertFalse(mesh.drawTexture)
        assertEquals(SHADOW_INNER_COLOR, mesh.shadowInnerColor)
        assertEquals(SHADOW_OUTER_COLOR, mesh.shadowOuterColor)
        assertNotNull(mesh.texturePage)
        assertEquals(
            COLOR_FACTOR_OFFSET,
            mesh.getPrivateProperty("colorFactorOffset")
        )
        assertNotNull(mesh.getPrivateProperty("arrScanLines"))
        assertNotNull(mesh.getPrivateProperty("arrOutputVertices"))
        assertNotNull(mesh.getPrivateProperty("arrRotatedVertices"))
        assertNotNull(mesh.getPrivateProperty("arrIntersections"))
        assertNotNull(mesh.getPrivateProperty("arrTempVertices"))
        assertNull(mesh.getPrivateProperty("arrSelfShadowVertices"))
        assertNull(mesh.getPrivateProperty("arrDropShadowVertices"))
        assertNull(mesh.getPrivateProperty("arrTempShadowVertices"))
        val rectangle: Array<*>? = mesh.getPrivateProperty("rectangle")
        requireNotNull(rectangle)
        assertEquals(4, rectangle.size)
        for (i in 0..3) {
            assertNotNull(rectangle[i])
        }

        val curlPositionLinesCount: Int? = mesh.getPrivateProperty("curlPositionLinesCount")
        requireNotNull(curlPositionLinesCount)
        assertEquals(3, curlPositionLinesCount)
        assertNotNull(mesh.getPrivateProperty("bufCurlPositionLines"))

        assertNotNull(mesh.getPrivateProperty("bufVertices"))

        assertNull(mesh.getPrivateProperty("bufTexCoords"))

        assertNotNull(mesh.getPrivateProperty("bufColors"))

        assertNull(mesh.getPrivateProperty("bufShadowColors"))
        assertNull(mesh.getPrivateProperty("bufShadowVertices"))

        val dropShadowCount: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount)

        val selfShadowCount: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount)
    }

    @Test
    fun constructor_whenZeroCurlSplits_setsMinimumCurlSplits() {
        val mesh = CurlMesh(0)

        // check
        assertEquals(1, mesh.getPrivateProperty("maxCurlSplits"))
    }

    @Test
    fun curl_whenDrawCurlPosition_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawCurlPosition = true)

        // check initial values
        assertTrue(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenNotDrawCurlPosition_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawCurlPosition = false)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenDrawTexture_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawTexture = true)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenNotDrawTexture_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawTexture = false)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertFalse(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenDrawShadow_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawShadow = true)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenNotDrawShadow_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawShadow = false)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertFalse(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(0.0f, 0.0f), PointF(1.0f, 1.0f), 1.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(8, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenDropShadowIsCastBehindTheCurl_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(10.0f, 0.0f), PointF(1.0f, 0.0f), 100.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(4, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(8, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun curl_whenDropShadowIsCastPartlyOverMesh_preparesMesh() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // set curl
        mesh.curl(PointF(200.0f, 0.0f), PointF(1.0f, 0.0f), 100.0)

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(4, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(8, selfShadowCount2)
    }

    @Test
    fun onDrawFrame_whenDrawTextureAndNoExistingTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenDrawTextureAnExistingTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set equal texture on both sides of the page
        val texture = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(texture, CurlPage.SIDE_BOTH)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenDrawTextureExistingTextureAndBackTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set different front and back textures
        val frontTexture =
            Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(frontTexture, CurlPage.SIDE_FRONT)
        val backTexture =
            Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(backTexture, CurlPage.SIDE_BACK)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenFlippedTextureAndNoBackTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set different front and back textures
        val frontTexture =
            Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(frontTexture, CurlPage.SIDE_FRONT)
        mesh.setFlipTexture(true)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenNotFlippedTextureAndBackTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set equal texture on both sides
        // set different front and back textures
        val frontTexture =
            Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(frontTexture, CurlPage.SIDE_FRONT)
        mesh.setFlipTexture(false)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenDrawPolygonOutlinesAnExistingTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawPolygonOutlines = true)

        // set equal texture on both sides of the page
        val texture = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(texture, CurlPage.SIDE_BOTH)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertTrue(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        justRun { gl.glLineWidth(1.0f) }
        justRun { gl.glColor4f(0.5f, 0.5f, 1.0f, 1.0f) }
        justRun { gl.glDrawArrays(GL10.GL_LINE_STRIP, 0, any()) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun onDrawFrame_whenDrawCurlPositionAnExistingTexture_drawsExpectedFrame() {
        val mesh = CurlMesh(MAX_CURL_SPLITS, drawCurlPosition = true)

        // set equal texture on both sides of the page
        val texture = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(texture, CurlPage.SIDE_BOTH)

        // check initial values
        assertTrue(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        justRun { gl.glLineWidth(1.0f) }
        justRun { gl.glColor4f(1.0f, 0.5f, 0.5f, 1.0f) }
        justRun { gl.glVertexPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_LINES, 0, any()) }

        // execute
        mesh.onDrawFrame(gl)
    }

    @Test
    fun reset_whenTextureAvailable_resetsCounts() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set equal texture on both sides of the page
        val texture = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(texture, CurlPage.SIDE_BOTH)

        // check initial values
        val verticesCountFront1: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(0, verticesCountFront1)
        val verticesCountBack1: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack1)
        val dropShadowCount1: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount1)
        val selfShadowCount1: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount1)

        // reset
        mesh.reset()

        // check
        val verticesCountFront2: Int? = mesh.getPrivateProperty("verticesCountFront")
        assertEquals(4, verticesCountFront2)
        val verticesCountBack2: Int? = mesh.getPrivateProperty("verticesCountBack")
        assertEquals(0, verticesCountBack2)
        val dropShadowCount2: Int? = mesh.getPrivateProperty("dropShadowCount")
        assertEquals(0, dropShadowCount2)
        val selfShadowCount2: Int? = mesh.getPrivateProperty("selfShadowCount")
        assertEquals(0, selfShadowCount2)
    }

    @Test
    fun resetTextures_setsTextureIdsToNull() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        // set equal texture on both sides of the page
        val texture = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        mesh.texturePage.setTexture(texture, CurlPage.SIDE_BOTH)

        // check initial values
        assertFalse(mesh.drawCurlPosition)
        assertFalse(mesh.drawPolygonOutlines)
        assertTrue(mesh.drawShadow)
        assertTrue(mesh.drawTexture)

        // prepare mock
        justRun { gl.glGenTextures(2, any(), 0) }
        justRun { gl.glBindTexture(GL10.GL_TEXTURE_2D, 0) }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER,
                GL10.GL_NEAREST.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_S,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glTexParameterf(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_WRAP_T,
                GL10.GL_CLAMP_TO_EDGE.toFloat()
            )
        }
        justRun {
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        }
        justRun { gl.glDisable(GL10.GL_TEXTURE_2D) }
        justRun { gl.glEnable(GL10.GL_BLEND) }
        justRun { gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, any()) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glDisable(GL10.GL_BLEND) }
        justRun { gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glVertexPointer(3, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glEnableClientState(GL10.GL_COLOR_ARRAY) }
        justRun { gl.glColorPointer(4, GL10.GL_FLOAT, 0, any()) }

        justRun { gl.glEnable(GL10.GL_TEXTURE_2D) }

        justRun { gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY) }
        justRun { gl.glDisableClientState(GL10.GL_COLOR_ARRAY) }

        justRun { gl.glDisableClientState(GL10.GL_VERTEX_ARRAY) }

        justRun { gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 2, 2) }

        justRun { gl.glLineWidth(1.0f) }
        justRun { gl.glColor4f(1.0f, 0.5f, 0.5f, 1.0f) }
        justRun { gl.glVertexPointer(2, GL10.GL_FLOAT, 0, any()) }
        justRun { gl.glDrawArrays(GL10.GL_LINES, 0, any()) }

        // execute
        mesh.onDrawFrame(gl)

        val textureIds1: IntArray? = mesh.getPrivateProperty("textureIds")
        assertNotNull(textureIds1)

        // reset texture
        mesh.resetTexture()

        val textureIds2: IntArray? = mesh.getPrivateProperty("textureIds")
        assertNull(textureIds2)
    }

    @Test
    fun setFlipTexture_setsExpectedValue() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        val flipTexture1: Boolean? = mesh.getPrivateProperty("flipTexture")
        requireNotNull(flipTexture1)
        assertFalse(flipTexture1)

        // set new value
        mesh.setFlipTexture(true)

        // check
        val flipTexture2: Boolean? = mesh.getPrivateProperty("flipTexture")
        requireNotNull(flipTexture2)
        assertTrue(flipTexture2)
    }

    @Test
    fun setRect_whenNoRectangle_makesNoAction() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        mesh.setRect(null)
    }

    @Test
    fun setRect_whenRectangle_setsProvidedRectangle() {
        val mesh = CurlMesh(MAX_CURL_SPLITS)

        val rect = RectF(1.0f, 2.0f, 3.0f, 4.0f)
        mesh.setRect(rect)
    }

    private companion object {
        const val MAX_CURL_SPLITS = 10
        val SHADOW_INNER_COLOR = floatArrayOf(0.5f, 0.5f, 0.5f, 1.0f)
        val SHADOW_OUTER_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        const val COLOR_FACTOR_OFFSET = 0.5f
        const val TEXTURE_WIDTH = 640
        const val TEXTURE_HEIGHT = 480
    }
}
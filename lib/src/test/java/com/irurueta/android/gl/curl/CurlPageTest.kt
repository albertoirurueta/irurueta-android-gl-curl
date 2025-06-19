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
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurlPageTest {

    @Test
    fun constructor_setsDefaultValues() {
        val page = CurlPage()

        assertFalse(page.texturesChanged)
        assertTrue(page.hasBackTexture)

        val colorBack: Int? = page.getPrivateProperty("colorBack")
        assertEquals(Color.WHITE, colorBack)
        val colorFront: Int? = page.getPrivateProperty("colorFront")
        assertEquals(Color.WHITE, colorFront)
        val textureBack: Bitmap? = page.getPrivateProperty("textureBack")
        assertNotNull(textureBack)
        val textureFront: Bitmap? = page.getPrivateProperty("textureFront")
        assertNotNull(textureFront)
    }

    @Test
    fun getSetColorAndReset_setsAndReturnsExpectedColor() {
        val page = CurlPage()

        // check default values
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        // set front side color
        page.setColor(Color.RED, CurlPage.SIDE_FRONT)

        // check
        assertEquals(Color.RED, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        // reset
        page.reset()

        // check
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        // set back side color
        page.setColor(Color.GREEN, CurlPage.SIDE_BACK)

        // check
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.GREEN, page.getColor(CurlPage.SIDE_BACK))

        // reset
        page.reset()

        // check
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        // set front and back side color
        page.setColor(Color.BLUE, CurlPage.SIDE_BOTH)

        // check
        assertEquals(Color.BLUE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.BLUE, page.getColor(CurlPage.SIDE_BACK))
    }

    @Test
    fun getSetTexture_whenNotNull_setsAndReturnsExpectedTextures() {
        val page = CurlPage()

        // check default values
        val rect = RectF()
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)

        // set front side texture
        val texture = Bitmap.createBitmap(
            TEXTURE_WIDTH,
            TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888
        )
        page.setTexture(texture, CurlPage.SIDE_FRONT)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotSame(texture, page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // set back side texture
        page.setTexture(texture, CurlPage.SIDE_BACK)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertNotSame(texture, page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // set texture for both sides
        page.setTexture(texture, CurlPage.SIDE_BOTH)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotSame(texture, page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertNotSame(texture, page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // reset
        page.reset()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
    }

    @Test
    fun getSetTexture_whenNull_setsAndReturnsExpectedTextures() {
        val page = CurlPage()

        // check default values
        val rect = RectF()
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)

        // set front side texture
        page.setTexture(null, CurlPage.SIDE_FRONT)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // set back side texture
        page.setTexture(null, CurlPage.SIDE_BACK)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // set texture for both sides
        page.setTexture(null, CurlPage.SIDE_BOTH)

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertTrue(page.texturesChanged)

        // reset
        page.reset()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
    }

    @Test
    fun recycle_resetsTextures() {
        val page = CurlPage()

        // check default values
        val rect = RectF()
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)

        page.recycle()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)

        // set texture
        val texture = Bitmap.createBitmap(
            TEXTURE_WIDTH,
            TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888
        )
        page.setTexture(texture, CurlPage.SIDE_BOTH)

        // check
        assertTrue(page.texturesChanged)

        page.recycle()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
    }

    @Test
    fun reset_resetsTexturesAndColor() {
        val page = CurlPage()

        // check default values
        val rect = RectF()
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        page.reset()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.WHITE, page.getColor(CurlPage.SIDE_BACK))

        // set texture and color
        val texture = Bitmap.createBitmap(
            TEXTURE_WIDTH,
            TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888
        )
        page.setTexture(texture, CurlPage.SIDE_BOTH)
        page.setColor(Color.RED, CurlPage.SIDE_BOTH)

        // check
        assertTrue(page.texturesChanged)
        assertEquals(Color.RED, page.getColor(CurlPage.SIDE_FRONT))
        assertEquals(Color.RED, page.getColor(CurlPage.SIDE_BACK))

        page.recycle()

        // check
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_FRONT))
        assertNotNull(page.getTexture(rect, CurlPage.SIDE_BACK))
        assertFalse(page.texturesChanged)
    }

    private companion object {
        const val TEXTURE_WIDTH = 640
        const val TEXTURE_HEIGHT = 480
    }
}
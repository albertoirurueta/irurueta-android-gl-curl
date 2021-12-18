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
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/**
 * Class implementing actual curl page rendering.
 * This class is based on https://github.com/harism/android-pagecurl
 *
 * @property drawCurlPosition Flag for rendering some lines used for development.
 * Shows curl position and one for the direction from the given position.
 * Comes handy one playing around with different ways for following pointer.
 * @property drawPolygonOutlines Flag for drawing polygon outlines.
 * Seeing polygon outlines gives good insight on how original rectangle is divided.
 * @property drawShadow Flag for enabling shadow rendering.
 * @property drawTexture Flag for texture rendering.
 * @property shadowInnerColor Inner color for shadow. Inner color is the color drawn next to
 * surface where shadowed area starts.
 * @property shadowOuterColor Outer color for shadow. Outer color is the color the shadow ends to.
 */
class CurlMesh private constructor(
    val drawCurlPosition: Boolean,
    val drawPolygonOutlines: Boolean,
    val drawShadow: Boolean,
    val drawTexture: Boolean,
    val shadowInnerColor: FloatArray,
    val shadowOuterColor: FloatArray
) {
    /**
     * Contains texture and blend colors for a page.
     */
    val texturePage: CurlPage = CurlPage()

    // Let's avoid using 'new' as much as possible. Meaning we introduce arrays
    // once here and reuse them on runtime. Doesn't really have very much effect
    // but avoids some garbage collections from happening.

    private var arrDropShadowVertices: CurlArray<ShadowVertex>? = null

    private val arrIntersections: CurlArray<Vertex> = CurlArray(2)

    private val arrOutputVertices: CurlArray<Vertex> = CurlArray(7)

    private val arrRotatedVertices: CurlArray<Vertex> = CurlArray(4)

    private var arrScanLines: CurlArray<Double>? = null

    private var arrSelfShadowVertices: CurlArray<ShadowVertex>? = null

    private var arrTempShadowVertices: CurlArray<ShadowVertex>? = null

    private val arrTempVertices: CurlArray<Vertex> = CurlArray(11) // 7 + 4

    // Buffers for feeding rasterizer

    private var bufColors: FloatBuffer? = null

    private var bufCurlPositionLines: FloatBuffer? = null

    private var bufShadowColors: FloatBuffer? = null

    private var bufShadowVertices: FloatBuffer? = null

    private var bufTexCoords: FloatBuffer? = null

    private var bufVertices: FloatBuffer? = null

    private var curlPositionLinesCount: Int = 0

    private var dropShadowCount: Int = 0

    /**
     * Boolean for 'flipping' texture sideways.
     */
    private var flipTexture: Boolean = false

    /**
     * Maximum number of split lines used for creating a curl.
     */
    private var maxCurlSplits: Int = 0

    /**
     * Bounding rectangle for this mesh.
     * rectangle[0] = top-left corner,
     * rectangle[1] = bottom-left corner
     * rectangle[2] = top-right corner
     * rectangle[3] = bottom right corner.
     */
    private val rectangle: Array<Vertex?> = Array(4) { null }

    private var selfShadowCount: Int = 0

    private var textureBack: Boolean = false

    private var textureIds: IntArray? = null

    private val textureRectBack = RectF()

    private val textureRectFront = RectF()

    private var verticesCountBack: Int = 0

    private var verticesCountFront: Int = 0

    /**
     * Internal array to compute lines indices and be reused.
     */
    private val lines = arrayOf(
        intArrayOf(0, 1),
        intArrayOf(0, 2),
        intArrayOf(1, 3),
        intArrayOf(2, 3)
    )

    /**
     * Sets color factor offset to make darker or clearer the area
     * of the texture close to the curl.
     * Value must be between 0.0f and 1.0f (both included).
     * The larger the value, the clearer the area will be.
     * The smaller the value, the darker the area will be.
     */
    private var colorFactorOffset: Float = DEFAULT_COLOR_FACTOR_OFFSET

    /**
     * Constructor for mesh object.
     *
     * @param maxCurlSplits maximum number of times the curl can be divided into. The bigger the
     * value the smoother the curl will be, at the expense of having more polygons to be drawn.
     */
    constructor(
        maxCurlSplits: Int,
        drawCurlPosition: Boolean = DRAW_CURL_POSITION,
        drawPolygonOutlines: Boolean = DRAW_POLYGON_OUTLINES,
        drawShadow: Boolean = DRAW_SHADOW,
        drawTexture: Boolean = DRAW_TEXTURE,
        shadowInnerColor: FloatArray = SHADOW_INNER_COLOR,
        shadowOuterColor: FloatArray = SHADOW_OUTER_COLOR,
        colorFactorOffset: Float = DEFAULT_COLOR_FACTOR_OFFSET
    ) : this(
        drawCurlPosition, drawPolygonOutlines, drawShadow, drawTexture,
        shadowInnerColor, shadowOuterColor
    ) {
        require(colorFactorOffset >= 0.0 || colorFactorOffset <= 1.0)
        this.colorFactorOffset = colorFactorOffset

        this.maxCurlSplits = max(1, maxCurlSplits)

        arrScanLines = CurlArray(this.maxCurlSplits + 2)
        for (i in 0 until 11) {
            arrTempVertices.add(Vertex())
        }

        if (drawShadow) {
            val num = (this.maxCurlSplits + 2) * 2
            arrSelfShadowVertices = CurlArray(num)
            arrDropShadowVertices = CurlArray(num)
            arrTempShadowVertices = CurlArray(num)
            for (i in 0 until num) {
                arrTempShadowVertices?.add(ShadowVertex())
            }
        }

        // Rectangle consists of 4 vertices.
        // Index 0 = top-left,
        // Index 1 = bottom-left
        // Index 2 = top-right
        // Index 3 = bottom-right
        for (i in 0..3) {
            rectangle[i] = Vertex()
        }

        // Set up shadow penumbra direction to each vertex.
        // We do face 'self shadow' calculations based on this information.
        rectangle[0]?.penumbraX = -1.0
        rectangle[1]?.penumbraX = -1.0
        rectangle[1]?.penumbraY = -1.0
        rectangle[3]?.penumbraY = -1.0

        rectangle[0]?.penumbraY = 1.0
        rectangle[2]?.penumbraX = 1.0
        rectangle[2]?.penumbraY = 1.0
        rectangle[3]?.penumbraX = 1.0

        if (drawCurlPosition) {
            curlPositionLinesCount = 3
            val hvbb = ByteBuffer.allocateDirect(curlPositionLinesCount * 16) // 2 * 2 * 4 = 16
            hvbb.order(ByteOrder.nativeOrder())
            bufCurlPositionLines = hvbb.asFloatBuffer()
            bufCurlPositionLines?.position(0)
        }

        // Vertex byte buffer

        // There are 4 vertices from bounding rect, max 2 from adding split line
        // to two corners and curl consists of maxCurlSplits lines each outputting
        // 2 vertices.
        val maxVerticesCount = 6 + (2 * this.maxCurlSplits) // 4 + 2 + (2 * maxCurlSplits)
        val vbb = ByteBuffer.allocateDirect(maxVerticesCount * 12) // 3 * 4
        vbb.order(ByteOrder.nativeOrder())
        bufVertices = vbb.asFloatBuffer()
        bufVertices?.position(0)

        if (drawTexture) {
            // Texture byte buffer
            val tbb = ByteBuffer.allocateDirect(maxVerticesCount * 8) // 8 = 2 * 4
            tbb.order(ByteOrder.nativeOrder())
            bufTexCoords = tbb.asFloatBuffer()
            bufTexCoords?.position(0)
        }

        // Color byte buffer

        val cbb =
            ByteBuffer.allocateDirect(maxVerticesCount * 16) // 4 colors * 4 bytes/float = 16 bytes
        cbb.order(ByteOrder.nativeOrder())
        bufColors = cbb.asFloatBuffer()
        bufColors?.position(0)

        if (drawShadow) {
            // Shadow color byte buffer
            val maxShadowVerticesCount = (this.maxCurlSplits + 2) * 4 // (maxCurlSplits + 2) * 2 * 2
            val scbb =
                ByteBuffer.allocateDirect(maxShadowVerticesCount * 16) // 4 colors * 4 bytes/float = 16 bytes
            scbb.order(ByteOrder.nativeOrder())
            bufShadowColors = scbb.asFloatBuffer()
            bufShadowColors?.position(0)

            // Shadow vertex index byte buffer
            val sibb =
                ByteBuffer.allocateDirect(maxShadowVerticesCount * 12) // 3 components * 4 bytes/float = 12 bytes
            sibb.order(ByteOrder.nativeOrder())
            bufShadowVertices = sibb.asFloatBuffer()
            bufShadowVertices?.position(0)

            dropShadowCount = 0
            selfShadowCount = 0
        }
    }

    /**
     * Sets curl for this mesh.
     *
     * @param curlPos position for curl 'center'. Can be any point on the
     * colinear line to the curl.
     * @param curlDir curl direction, should be normalized.
     * @param radius radius of curl.
     */
    @Synchronized
    fun curl(curlPos: PointF, curlDir: PointF, radius: Double) {
        // First add some 'helper' lines used for development
        if (drawCurlPosition) {
            val bufCurlPositionLines = bufCurlPositionLines ?: return

            bufCurlPositionLines.position(0)

            bufCurlPositionLines.put(curlPos.x)
            bufCurlPositionLines.put(curlPos.y - 1.0f)
            bufCurlPositionLines.put(curlPos.x)
            bufCurlPositionLines.put(curlPos.y + 1.0f)
            bufCurlPositionLines.put(curlPos.x - 1.0f)
            bufCurlPositionLines.put(curlPos.y)
            bufCurlPositionLines.put(curlPos.x + 1.0f)
            bufCurlPositionLines.put(curlPos.y)

            bufCurlPositionLines.put(curlPos.x)
            bufCurlPositionLines.put(curlPos.y)
            bufCurlPositionLines.put(curlPos.x + curlDir.x * 2)
            bufCurlPositionLines.put(curlPos.y + curlDir.y * 2)

            bufCurlPositionLines.position(0)
        }

        // Actual 'curl' implementation starts here.
        bufVertices?.position(0)
        bufColors?.position(0)
        if (drawTexture) {
            bufTexCoords?.position(0)
        }

        // Calculate curl angle from direction.
        var curlAngle = acos(curlDir.x.toDouble())
        curlAngle = if (curlDir.y > 0.0f) -curlAngle else curlAngle

        // Initiate rotated rectangle which is translated to curlPos and
        // rotated so that curl direction heads to right (1, 0). Vertices are
        // ordered in ascending order based on x-coordinate at the same time.
        // And using y-coordinate in very rare case in which two vertices have
        // same x-coordinate.
        arrTempVertices.addAll(arrRotatedVertices)
        arrRotatedVertices.clear()
        for (i in 0 until 4) {
            val v = arrTempVertices.remove(0)
            rectangle[i]?.let { vr ->
                v.set(vr)
            }
            v.translate(-curlPos.x.toDouble(), -curlPos.y.toDouble())
            v.rotateZ(-curlAngle)

            val size = arrRotatedVertices.size
            var j = 0
            while (j < size) {
                val v2 = arrRotatedVertices[j]
                if (v.posX > v2.posX) {
                    break
                }
                if (v.posX == v2.posX && v.posY > v2.posY) {
                    break
                }
                ++j
            }
            arrRotatedVertices.add(j, v)
        }

        // Rotated rectangle lines/vertex indices. We need to find bounding
        // lines for rotated rectangle. After sorting vertices according to
        // their x-coordinate we don't have to worry about vertices at indices
        // 0 and 1. But due to inaccuracy it's possible that vertex 3 is not the
        // opposing corner from vertex 0. So we are calculating distance from
        // vertex 0 to vertices 2 and 3 - and altering line indices if needed.
        // Also vertices/lines are given in an order first one has x-coordinate
        // at least the latter one. This property is used in getIntersections to
        // see if there is an intersection.
        // NOTE: to reduce computations com square roots, squared distances are
        // used to compare values

        // reset lines values
        lines[0][0] = 0
        lines[0][1] = 1
        lines[1][0] = 0
        lines[1][1] = 2
        lines[2][0] = 1
        lines[2][1] = 3
        lines[3][0] = 2
        lines[3][1] = 3

        val v0 = arrRotatedVertices[0]
        val v2 = arrRotatedVertices[2]
        val v3 = arrRotatedVertices[3]

        val diffX02 = v0.posX - v2.posX
        val diffY02 = v0.posY - v2.posY
        val diffX03 = v0.posX - v3.posX
        val diffY03 = v0.posY - v3.posY
        val sqrDiffX02 = diffX02 * diffX02
        val sqrDiffY02 = diffY02 * diffY02
        val sqrDiffX03 = diffX03 * diffX03
        val sqrDiffY03 = diffY03 * diffY03

        val sqrDist2 = sqrDiffX02 + sqrDiffY02
        val sqrDist3 = sqrDiffX03 + sqrDiffY03

        if (sqrDist2 > sqrDist3) {
            lines[1][1] = 3
            lines[2][1] = 2
        }

        verticesCountFront = 0
        verticesCountBack = 0

        if (drawShadow) {
            val arrTempShadowVertices = arrTempShadowVertices
            val arrDropShadowVertices = arrDropShadowVertices
            val arrSelfShadowVertices = arrSelfShadowVertices

            if (arrTempShadowVertices != null && arrDropShadowVertices != null
                && arrSelfShadowVertices != null
            ) {
                arrTempShadowVertices.addAll(arrDropShadowVertices)
                arrTempShadowVertices.addAll(arrSelfShadowVertices)
                arrDropShadowVertices.clear()
                arrSelfShadowVertices.clear()
            }
        }

        val arrScanLines = arrScanLines ?: return

        // Length of 'curl' curve
        val curlLength = Math.PI * radius
        // Calculate scan lines
        arrScanLines.clear()
        if (this.maxCurlSplits > 0) {
            arrScanLines.add(0.0)
        }
        for (i in 1 until this.maxCurlSplits) {
            arrScanLines.add((-curlLength * i) / (this.maxCurlSplits - 1).toDouble())
        }

        // As rotatedVertices is ordered regarding x-coordinate, adding
        // this scan line produces scan area picking up vertices which are
        // completely rotated. One could say 'until infinity'.
        arrScanLines.add(arrRotatedVertices[3].posX - 1)

        // Start from right most vertex. Pretty much the same as first scan area
        // is starting from 'infinity'.
        var scanXmax = arrRotatedVertices[0].posX + 1

        for (i in 0 until arrScanLines.size) {
            // Once we have scanXmin and scanXmax we have a scan area to start
            // working with.
            val scanXmin = arrScanLines[i]
            // First iterate 'original' rectangle vertices within scan area.
            for (j in 0 until arrRotatedVertices.size) {
                val v = arrRotatedVertices[j]

                // Test if vertex lies within this scan area.
                if (v.posX in scanXmin..scanXmax) {
                    // Pop out a vertex from temp vertices
                    val n = arrTempVertices.remove(0)
                    n.set(v)

                    // This is done solely for triangulation reasons. Given a
                    // rotated rectangle it has max 2 vertices having intersection.
                    val intersections = getIntersections(arrRotatedVertices, lines, n.posX)
                    if (intersections.size == 1 && intersections[0].posY > v.posY) {
                        // In case intersecting vertex is higher add it first.
                        arrOutputVertices.addAll(intersections)
                        arrOutputVertices.add(n)
                    } else if (intersections.size <= 1) {
                        // Otherwise add original vertex first
                        arrOutputVertices.add(n)
                        arrOutputVertices.addAll(intersections)
                    } else {
                        // There should never be more than 1 intersecting
                        // vertex. But if it happens as a fallback simply skip
                        // everything.
                        arrTempVertices.add(n)
                        arrTempVertices.addAll(intersections)
                    }
                }
            }

            // Search for scan line intersections
            val intersections = getIntersections(arrRotatedVertices, lines, scanXmin)
            if (intersections.size == 2) {
                // There were two intersections, add them based on y-coordinate,
                // higher first, lower last.
                val v1 = intersections[0]
                val v2b = intersections[1]
                if (v1.posY < v2b.posY) {
                    arrOutputVertices.add(v2b)
                    arrOutputVertices.add(v1)
                } else {
                    arrOutputVertices.addAll(intersections)
                }
            } else if (intersections.size != 0) {
                // This happens in a case in which there is a original vertex
                // exactly at scan line or something wen very much wrong if
                // there are 3+ vertices. What ever the reason just return the
                // vertices to temp vertices for later use. In former case it
                // was handled already earlier once iterating through
                // rotatedVertices, in latter case it's better to avoid doing
                // anything with them
                arrTempVertices.addAll(intersections)
            }

            // Add vertices found during this iteration to vertex etc buffers.
            while (arrOutputVertices.size > 0) {
                val v = arrOutputVertices.remove(0)
                arrTempVertices.add(v)

                // Local texture front-facing flag
                val textureFront: Boolean
                if (i == 0) {
                    // Untouched vertices
                    textureFront = true
                    verticesCountFront++
                } else if (i == arrScanLines.size - 1 || curlLength == 0.0) {
                    // 'Completely' rotated vertices.
                    v.posX = -(curlLength + v.posX)
                    v.posZ = 2 * radius
                    v.penumbraX = -v.penumbraX

                    textureFront = false
                    verticesCountBack++
                } else {
                    // vertex lies within 'curl'

                    // Even though it's not obvious from the if-else clause,
                    // here v.posX is between [-curlLength, 0]. And we can do
                    // calculations around a half cylinder.
                    val rotY = Math.PI * (v.posX / curlLength)
                    val sinRotY = sin(rotY)
                    val cosRotY = cos(rotY)
                    v.posX = radius * sinRotY
                    v.posZ = radius - (radius * cosRotY)
                    v.penumbraX *= cosRotY
                    // Map color multiplier to [0.1f, 1f] range.
                    v.colorFactor =
                        (colorFactorOffset + (1.0f - colorFactorOffset) * sqrt(sinRotY + 1.0)).toFloat()

                    if (v.posZ >= radius) {
                        textureFront = false
                        verticesCountBack++
                    } else {
                        textureFront = true
                        verticesCountFront++
                    }
                }

                // We use local textureFront for flipping backside texture
                // locally. Plus additionally if mesh is in flip texture mode,
                // we'll make the procedure "backwards". Also, until this point,
                // texture coordinates are within [0, 1] range so we'll adjust
                // them to final texture coordinates too.
                if (textureFront != flipTexture) {
                    v.texX *= textureRectFront.right
                    v.texY *= textureRectFront.bottom
                    v.color = texturePage.getColor(CurlPage.SIDE_FRONT)
                } else {
                    v.texX *= textureRectBack.right
                    v.texY *= textureRectBack.bottom
                    v.color = texturePage.getColor(CurlPage.SIDE_BACK)
                }

                // Move vertex back to 'world' coordinates.
                v.rotateZ(curlAngle)
                v.translate(curlPos.x.toDouble(), curlPos.y.toDouble())
                addVertex(v)

                if (drawShadow) {
                    val arrTempShadowVertices = arrTempShadowVertices
                    val arrSelfShadowVertices = arrSelfShadowVertices
                    val arrDropShadowVertices = arrDropShadowVertices
                    if (arrTempShadowVertices != null && arrSelfShadowVertices != null
                        && arrDropShadowVertices != null
                    ) {

                        if (v.posZ > 0.0 && v.posZ <= radius) {
                            // Drop shadow is cast 'behind' the curl
                            val sv = arrTempShadowVertices.remove(0)
                            sv.posX = v.posX
                            sv.posY = v.posY
                            sv.posZ = v.posZ
                            val tmp = v.posZ / 2.0
                            sv.penumbraX = -curlDir.x * tmp
                            sv.penumbraY = -curlDir.y * tmp
                            sv.penumbraColor = (v.posZ / radius)
                            val idx = (arrDropShadowVertices.size + 1) / 2
                            arrDropShadowVertices.add(idx, sv)
                        }

                        if (v.posZ > radius) {
                            // Self shadow is cast partly over mesh
                            val sv = arrTempShadowVertices.remove(0)
                            sv.posX = v.posX
                            sv.posY = v.posY
                            sv.posZ = v.posZ
                            val tmp = (v.posZ - radius) / 3.0
                            sv.penumbraX = v.penumbraX * tmp
                            sv.penumbraY = v.penumbraY * tmp
                            sv.penumbraColor = (v.posZ - radius) / (2.0 * radius)
                            val idx = (arrSelfShadowVertices.size + 1) / 2
                            arrSelfShadowVertices.add(idx, sv)
                        }
                    }
                }
            }

            // Switch scanXmin as scanXmax for next iteration
            scanXmax = scanXmin
        }

        bufVertices?.position(0)
        bufColors?.position(0)
        if (drawTexture) {
            bufTexCoords?.position(0)
        }

        // Add shadow vertices
        if (drawShadow) {
            val arrDropShadowVertices = arrDropShadowVertices
            val bufShadowVertices = bufShadowVertices
            val bufShadowColors = bufShadowColors
            val arrSelfShadowVertices = arrSelfShadowVertices
            if (arrDropShadowVertices != null && bufShadowVertices != null
                && bufShadowColors != null && arrSelfShadowVertices != null
            ) {

                bufShadowColors.position(0)
                bufShadowVertices.position(0)
                dropShadowCount = 0

                for (i in 0 until arrDropShadowVertices.size) {
                    val sv = arrDropShadowVertices[i]
                    bufShadowVertices.put(sv.posX.toFloat())
                    bufShadowVertices.put(sv.posY.toFloat())
                    bufShadowVertices.put(sv.posZ.toFloat())
                    bufShadowVertices.put((sv.posX + sv.penumbraX).toFloat())
                    bufShadowVertices.put((sv.posY + sv.penumbraY).toFloat())
                    bufShadowVertices.put(sv.posZ.toFloat())

                    for (j in 0..3) {
                        val color =
                            shadowOuterColor[j] + (shadowInnerColor[j] - shadowOuterColor[j]) * sv.penumbraColor
                        bufShadowColors.put(color.toFloat())
                    }
                    bufShadowColors.put(shadowOuterColor)
                    dropShadowCount += 2
                }

                selfShadowCount = 0

                for (i in 0 until arrSelfShadowVertices.size) {
                    val sv = arrSelfShadowVertices[i]
                    bufShadowVertices.put(sv.posX.toFloat())
                    bufShadowVertices.put(sv.posY.toFloat())
                    bufShadowVertices.put(sv.posZ.toFloat())
                    bufShadowVertices.put((sv.posX + sv.penumbraX).toFloat())
                    bufShadowVertices.put((sv.posY + sv.penumbraY).toFloat())
                    bufShadowVertices.put(sv.posZ.toFloat())
                    for (j in 0..3) {
                        val color =
                            (shadowOuterColor[j] + (shadowInnerColor[j] - shadowOuterColor[j]) * sv.penumbraColor)
                        bufShadowColors.put(color.toFloat())
                    }
                    bufShadowColors.put(shadowOuterColor)
                    selfShadowCount += 2
                }
                bufShadowColors.position(0)
                bufShadowVertices.position(0)
            }
        }
    }

    /**
     * Renders out page curl mesh
     */
    @Synchronized
    fun onDrawFrame(gl: GL10) {
        var textureIds = this.textureIds
        // First allocate texture if there is not one yet.
        if (drawTexture && textureIds == null) {
            // Generate texture
            textureIds = IntArray(2)
            this.textureIds = textureIds

            gl.glGenTextures(2, textureIds, 0)
            for (textureId in textureIds) {
                // Set texture attributes.
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
                gl.glTexParameterf(
                    GL10.GL_TEXTURE_2D,
                    GL10.GL_TEXTURE_MIN_FILTER,
                    GL10.GL_NEAREST.toFloat()
                )
                gl.glTexParameterf(
                    GL10.GL_TEXTURE_2D,
                    GL10.GL_TEXTURE_MAG_FILTER,
                    GL10.GL_NEAREST.toFloat()
                )
                gl.glTexParameterf(
                    GL10.GL_TEXTURE_2D,
                    GL10.GL_TEXTURE_WRAP_S,
                    GL10.GL_CLAMP_TO_EDGE.toFloat()
                )
                gl.glTexParameterf(
                    GL10.GL_TEXTURE_2D,
                    GL10.GL_TEXTURE_WRAP_T,
                    GL10.GL_CLAMP_TO_EDGE.toFloat()
                )
            }
        }

        if (drawTexture && texturePage.texturesChanged && textureIds != null) {
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[0])
            var texture = texturePage.getTexture(textureRectFront, CurlPage.SIDE_FRONT)
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, texture, 0)
            texture?.recycle()

            textureBack = texturePage.hasBackTexture
            if (textureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[1])
                texture = texturePage.getTexture(
                    textureRectBack,
                    CurlPage.SIDE_BACK
                )
                GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, texture, 0)
                texture?.recycle()
            } else {
                textureRectBack.set(textureRectFront)
            }

            texturePage.recycle()
            reset()
        }

        // Some 'global' settings
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)

        // Drop shadow drawing is done temporarily here to hide some
        // problems with its calculation
        if (drawShadow) {
            gl.glDisable(GL10.GL_TEXTURE_2D)
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, bufShadowColors)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, bufShadowVertices)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, dropShadowCount)
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
            gl.glDisable(GL10.GL_BLEND)
        }

        if (drawTexture) {
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, bufTexCoords)
        }
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, bufVertices)
        // Enable color array.
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
        gl.glColorPointer(4, GL10.GL_FLOAT, 0, bufColors)

        // Draw front facing blank vertices.
        gl.glDisable(GL10.GL_TEXTURE_2D)
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, verticesCountFront)

        // Draw front facing texture
        if (drawTexture && textureIds != null) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glEnable(GL10.GL_TEXTURE_2D)

            if (!flipTexture || !textureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[0])
            } else {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[1])
            }

            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, verticesCountFront)

            gl.glDisable(GL10.GL_BLEND)
            gl.glDisable(GL10.GL_TEXTURE_2D)
        }

        val backStartIdx = max(0, verticesCountFront - 2)
        val backCount = verticesCountFront + verticesCountBack - backStartIdx

        // Draw back facing blank vertices.
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, backStartIdx, backCount)

        // Draw back facing texture.
        if (drawTexture && textureIds != null) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glEnable(GL10.GL_TEXTURE_2D)

            if (flipTexture || !textureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[0])
            } else {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureIds[1])
            }

            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, backStartIdx, backCount)

            gl.glDisable(GL10.GL_BLEND)
            gl.glDisable(GL10.GL_TEXTURE_2D)
        }

        // Disable textures and color array.
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY)

        if (drawPolygonOutlines) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glLineWidth(1.0f)
            gl.glColor4f(0.5f, 0.5f, 1.0f, 1.0f)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, bufVertices)
            gl.glDrawArrays(GL10.GL_LINE_STRIP, 0, verticesCountFront)
            gl.glDisable(GL10.GL_BLEND)
        }

        if (drawCurlPosition) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glLineWidth(1.0f)
            gl.glColor4f(1.0f, 0.5f, 0.5f, 1.0f)
            gl.glVertexPointer(2, GL10.GL_FLOAT, 0, bufCurlPositionLines)
            gl.glDrawArrays(GL10.GL_LINES, 0, curlPositionLinesCount * 2)
            gl.glDisable(GL10.GL_BLEND)
        }

        if (drawShadow) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, bufShadowColors)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, bufShadowVertices)
            gl.glDrawArrays(
                GL10.GL_TRIANGLE_STRIP, dropShadowCount,
                selfShadowCount
            )
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
            gl.glDisable(GL10.GL_BLEND)
        }

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY)
    }

    /**
     * Resets mesh to 'initial' state. Meaning this mesh will draw a plain
     * textured rectangle after call to this method.
     */
    @Synchronized
    fun reset() {
        bufVertices?.position(0)
        bufColors?.position(0)
        if (drawTexture) {
            bufTexCoords?.position(0)
        }

        for (i in 0 until 4) {
            val tmp = (arrTempVertices[0])
            val rectVertex = rectangle[i] ?: continue
            tmp.set(rectVertex)

            if (flipTexture) {
                tmp.texX *= textureRectBack.right
                tmp.texY *= textureRectBack.bottom
                tmp.color = texturePage.getColor(CurlPage.SIDE_BACK)
            } else {
                tmp.texX *= textureRectFront.right
                tmp.texY *= textureRectFront.bottom
                tmp.color = texturePage.getColor(CurlPage.SIDE_FRONT)
            }

            addVertex(tmp)
        }

        verticesCountFront = 4
        verticesCountBack = 0
        bufVertices?.position(0)
        bufColors?.position(0)
        if (drawTexture) {
            bufTexCoords?.position(0)
        }

        dropShadowCount = 0
        selfShadowCount = 0
    }

    /**
     * Resets allocated texture id forcing creation of new one. After calling
     * this method you most likely want to set bitmap too as it's lost. This
     * method should be called only once e.g GL context is re-created as this
     * method does not release previous texture id, only makes sure new one is
     * requested on next render.
     */
    @Synchronized
    fun resetTexture() {
        textureIds = null
    }

    /**
     * If true, flips texture sideways.
     */
    @Synchronized
    fun setFlipTexture(flipTexture: Boolean) {
        this.flipTexture = flipTexture
        if (flipTexture) {
            setTexCoords(1.0, 0.0)
        } else {
            setTexCoords(0.0, 1.0)
        }
    }

    /**
     * Update mesh bounds.
     */
    fun setRect(r: RectF?) {
        if (r == null) return

        rectangle[0]?.posX = r.left.toDouble()
        rectangle[0]?.posY = r.top.toDouble()
        rectangle[1]?.posX = r.left.toDouble()
        rectangle[1]?.posY = r.bottom.toDouble()
        rectangle[2]?.posX = r.right.toDouble()
        rectangle[2]?.posY = r.top.toDouble()
        rectangle[3]?.posX = r.right.toDouble()
        rectangle[3]?.posY = r.bottom.toDouble()
    }

    /**
     * Sets texture coordinates to rectangle vertices.
     */
    @Synchronized
    private fun setTexCoords(left: Double, right: Double) {
        val top = 0.0
        val bottom = 1.0
        rectangle[0]?.texX = left
        rectangle[0]?.texY = top
        rectangle[1]?.texX = left
        rectangle[1]?.texY = bottom
        rectangle[2]?.texX = right
        rectangle[2]?.texY = top
        rectangle[3]?.texX = right
        rectangle[3]?.texY = bottom
    }

    /**
     * Calculates intersections for a given scan line.
     */
    private fun getIntersections(
        vertices: CurlArray<Vertex>,
        lineIndices: Array<IntArray>,
        scanX: Double
    ): CurlArray<Vertex> {
        arrIntersections.clear()

        // Iterate through rectangle lines each represented as a pair of vertices.
        for (j in lineIndices.indices) {
            val v1 = vertices[lineIndices[j][0]]
            val v2 = vertices[lineIndices[j][1]]

            // Here we expect that v1.posX >= v2.posX and want to do intersection
            // test the opposite way.
            if (v1.posX > scanX && v2.posX < scanX) {
                // There is an intersection, calculate coefficient telling 'how far'
                // scanX is from v2
                val c = (scanX - v2.posX) / (v1.posX - v2.posX)
                val n = arrTempVertices.remove(0)
                n.set(v2)
                n.posX = scanX
                n.posY += (v1.posY - v2.posY) * c

                if (drawTexture) {
                    n.texX += (v1.texX - v2.texX) * c
                    n.texY += (v1.texY - v2.texY) * c
                }

                if (drawShadow) {
                    n.penumbraX += (v1.penumbraX - v2.penumbraX) * c
                    n.penumbraY += (v1.penumbraY - v2.penumbraY) * c
                }
                arrIntersections.add(n)
            }
        }

        return arrIntersections
    }

    /**
     * Adds vertex to buffers.
     */
    private fun addVertex(vertex: Vertex) {
        bufVertices?.put(vertex.posX.toFloat())
        bufVertices?.put(vertex.posY.toFloat())
        bufVertices?.put(vertex.posZ.toFloat())
        bufColors?.put(vertex.colorFactor * Color.red(vertex.color) / 255.0f)
        bufColors?.put(vertex.colorFactor * Color.green(vertex.color) / 255.0f)
        bufColors?.put(vertex.colorFactor * Color.blue(vertex.color) / 255.0f)
        bufColors?.put(Color.alpha(vertex.color) / 255.0f)

        if (drawTexture) {
            bufTexCoords?.put(vertex.texX.toFloat())
            bufTexCoords?.put(vertex.texY.toFloat())
        }
    }

    companion object {
        /**
         * Flag for rendering some lines used for development.
         * Shows curl position and one for the direction from the given position.
         * Comes handy one playing around with different ways for following pointer.
         */
        const val DRAW_CURL_POSITION = false

        /**
         * Flag for drawing polygon outlines.
         * Seeing polygon outlines gives good insight on how original rectangle is divided.
         */
        const val DRAW_POLYGON_OUTLINES = false

        /**
         * Flag for enabling shadow rendering.
         */
        const val DRAW_SHADOW = true

        /**
         * Flag for texture rendering.
         */
        const val DRAW_TEXTURE = true

        /**
         * Inner color for shadow.
         * Inner color is the color drawn next to surface where shadowed area starts.
         */
        val SHADOW_INNER_COLOR = floatArrayOf(0.0f, 0.0f, 0.0f, 0.5f)

        /**
         * Outer color for shadow.
         * Outer color is the color the shadow ends to.
         */
        val SHADOW_OUTER_COLOR = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)

        /**
         * Default color factor offset to make darker or clearer the area
         * of the texture close to the curl.
         * Value must be between 0.0f and 1.0f (both included).
         * The larger the value, the clearer the area will be.
         * The smaller the value, the darker the area will be.
         */
        const val DEFAULT_COLOR_FACTOR_OFFSET = 0.1f
    }

    /**
     * Simple fixed size array implementation.
     */
    private class CurlArray<T> private constructor() {

        /**
         * Internal array.
         */
        private val array: Array<Any?> by lazy { Array(capacity) { null } }

        /**
         * Maximum capacity of array.
         */
        private var capacity: Int = 0

        /**
         * Current number of elements stored in array.
         */
        var size: Int = 0
            private set

        /**
         * Constructor.
         *
         * @param capacity maximum array capacity.
         * @throws IllegalArgumentException if provided capacity is zero or negative.
         */
        @Throws(IllegalArgumentException::class)
        constructor(capacity: Int) : this() {
            require(capacity > 0)

            this.capacity = capacity
        }

        /**
         * Adds an item to the array.
         *
         * @param index position where item will be stored.
         * @param item item to be added.
         * @throws IndexOutOfBoundsException if provided index exceeds current size or capacity,
         * or if index is negative.
         */
        @Throws(IndexOutOfBoundsException::class)
        fun add(index: Int, item: T) {
            if (index < 0 || index > size || size >= capacity) {
                throw IndexOutOfBoundsException()
            }
            for (i in size downTo index + 1) {
                array[i] = array[i - 1]
            }
            array[index] = item
            ++size
        }

        /**
         * Adds an item at the end of the array.
         *
         * @param item item to be added.
         * @throws IndexOutOfBoundsException if size will exceed capacity after executing this
         * method.
         */
        @Throws(IndexOutOfBoundsException::class)
        fun add(item: T) {
            if (size >= capacity) {
                throw IndexOutOfBoundsException()
            }
            array[size++] = item
        }

        /**
         * Adds provided array at the end.
         *
         * @param array array to be added.
         * @throws IndexOutOfBoundsException if size will exceed capacity after executing this
         * method.
         */
        @Throws(IndexOutOfBoundsException::class)
        fun addAll(array: CurlArray<T>) {
            if (size + array.size > capacity) {
                throw IndexOutOfBoundsException()
            }
            for (i in 0 until array.size) {
                this.array[size++] = array[i]
            }
        }

        /**
         * Clears the contents of this array.
         */
        fun clear() {
            size = 0
        }

        /**
         * Gets value at provided index.
         *
         * @param index index of value to be retrieved.
         */
        @Suppress("UNCHECKED_CAST")
        @Throws(IndexOutOfBoundsException::class)
        operator fun get(index: Int): T {
            if (index < 0 || index >= size) {
                throw IndexOutOfBoundsException()
            }

            return array[index] as T
        }

        /**
         * Removes element at provided position.
         *
         * @param index index of value to be removed.
         */
        @Suppress("UNCHECKED_CAST")
        @Throws(IndexOutOfBoundsException::class)
        fun remove(index: Int): T {
            if (index < 0 || index >= size) {
                throw IndexOutOfBoundsException()
            }

            val item = array[index] as T
            for (i in index until size - 1) {
                array[i] = array[i + 1]
            }
            --size
            return item
        }
    }

    /**
     * Holder for shadow vertex information
     */
    private data class ShadowVertex(
        var penumbraColor: Double = 0.0,
        var penumbraX: Double = 0.0,
        var penumbraY: Double = 0.0,
        var posX: Double = 0.0,
        var posY: Double = 0.0,
        var posZ: Double = 0.0
    )

    private class Vertex(
        var color: Int = 0,
        var colorFactor: Float = 1.0f,
        var penumbraX: Double = 0.0,
        var penumbraY: Double = 0.0,
        var posX: Double = 0.0,
        var posY: Double = 0.0,
        var posZ: Double = 0.0,
        var texX: Double = 0.0,
        var texY: Double = 0.0
    ) {

        /**
         * Rotates x, y coordinates around z axis.
         *
         * @param theta angle to be rotated expressed in radians.
         */
        fun rotateZ(theta: Double) {
            val cos = cos(theta)
            val sin = sin(theta)
            val x = posX * cos + posY * sin
            val y = posX * -sin + posY * cos
            posX = x
            posY = y
            val px = penumbraX * cos + penumbraY * sin
            val py = penumbraX * -sin + penumbraY * cos
            penumbraX = px
            penumbraY = py
        }

        /**
         * Copies provided vertex data.
         *
         * @param vertex instance to copy data from.
         */
        fun set(vertex: Vertex) {
            posX = vertex.posX
            posY = vertex.posY
            posZ = vertex.posZ
            texX = vertex.texX
            texY = vertex.texY
            penumbraX = vertex.penumbraX
            penumbraY = vertex.penumbraY
            color = vertex.color
            colorFactor = vertex.colorFactor
        }

        /**
         * Translates vertex the specified amount.
         *
         * @param dx amount to be translated horizontally.
         * @param dy amount to be translated vertically.
         */
        fun translate(dx: Double, dy: Double) {
            posX += dx
            posY += dy
        }
    }
}
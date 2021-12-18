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

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs


/**
 * Utility class for instrumentation tests.
 */
object InstrumentationTestHelper {

    /**
     * Default step size between generated drag touch events (expressed in pixels).
     */
    private const val DEFAULT_DRAG_STEP_SIZE = 10

    /**
     * Makes a drag or scroll gesture from a given start point towards provided destination point.
     * @param toX horizontal position where gesture will end.
     * @param toY vertical position where gesture will end.
     * @param stepSize step size in pixels between generated touch events.
     */
    fun drag(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        stepSize: Int = DEFAULT_DRAG_STEP_SIZE
    ) {
        val stepCountX = abs((fromX - toX) / stepSize)
        val stepCountY = abs((fromY - toY) / stepSize)
        val stepCount = (stepCountX + stepCountY) / 2

        val inst = InstrumentationRegistry.getInstrumentation()

        val downTime = SystemClock.uptimeMillis()
        var eventTime = SystemClock.uptimeMillis()

        val xStep = (toX - fromX).toFloat() / stepCount
        val yStep = (toY - fromY).toFloat() / stepCount

        var x = fromX.toFloat()
        var y = fromY.toFloat()

        var event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        inst.sendPointerSync(event)
        inst.waitForIdleSync()

        for (i in 1..stepCount) {
            x += xStep
            y += yStep
            eventTime = SystemClock.uptimeMillis()
            event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0)
            inst.sendPointerSync(event)
            inst.waitForIdleSync()
        }

        eventTime = SystemClock.uptimeMillis()
        event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
        inst.sendPointerSync(event)
        inst.waitForIdleSync()
    }

    /**
     * Generates a tap touch event at the center of provided view.
     *
     * @param v view where tap will be made.
     */
    fun tap(v: View) {
        tap((v.left + v.right) / 2, (v.top + v.bottom) / 2)
    }

    /**
     * Generates a touch tap at provided x,y coordinates on the screen.
     * @param x horizontal coordinate.
     * @param y vertical coordinate.
     */
    private fun tap(x: Int, y: Int) {
        touchDown(x, y)
        touchUp(x, y)
    }

    /**
     * Creates a touch event where one finger is pressed against the screen
     * at provided x,y position.
     * @param x horizontal coordinate.
     * @param y vertical coordinate.
     */
    private fun touchDown(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_DOWN,
            x.toFloat(),
            y.toFloat(),
            0
        )
        sendMotionEvent(event)
    }

    /**
     * Creates a touch event where one finger is raised from the screen at
     * provided x,y position.
     * @param x horizontal coordinate.
     * @param y vertical coordinate.
     */
    private fun touchUp(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
            0
        )
        sendMotionEvent(event)
    }

    /**
     * Sends a touch event.
     * @param event touch event to be sent.
     */
    private fun sendMotionEvent(event: MotionEvent) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(event)
    }
}
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

import android.app.Activity
import android.os.Bundle
import com.irurueta.android.gl.curl.CurlTextureView

/**
 * Activity to test curl texture view.
 */
class CurlTextureViewActivity : Activity() {

    /**
     * Curl texture view.
     */
    private var view: CurlTextureView? = null

    /**
     * Called when the activity is created.
     *
     * @param savedInstanceState saved instance state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.curl_texture_view_activity)
        view = findViewById(R.id.curl_texture_view_test)
    }

    /**
     * Called when the activity is paused.
     */
    override fun onPause() {
        super.onPause()
        view?.onPause()
    }

    /**
     * Called when the activity is resumed.
     */
    override fun onResume() {
        super.onResume()
        view?.onResume()
    }
}
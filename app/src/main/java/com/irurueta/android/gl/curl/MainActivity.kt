package com.irurueta.android.gl.curl

import android.content.res.Resources
import android.graphics.*
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    /**
     * Reference to view in charge of drawing page curls.
     */
    private var view: CurlTextureView? = null

    /**
     * View responsible to draw a title behind the curl view.
     */
    private var title: TextView? = null

    /**
     * Loaded bitmaps. These are kept in memory for performance reasons.
     */
    private var bitmaps = arrayOfNulls<Bitmap>(PAGE_COUNT)

    /**
     * A tage provider to load bitmaps to draw on each page.
     */
    private val pageProvider = object : CurlTextureView.PageProvider {
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

    /**
     * Called when activity is created.
     * References views in te activity, loads bitmaps and registers an observer to know when
     * the curl view is ready.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        view = findViewById(R.id.curl_gl_surface_view)
        title = findViewById(R.id.title)

        loadAllBitmaps()

        view?.sizeChangedObserver = object : CurlTextureView.SizeChangedObserver {
            override fun onSizeChanged(width: Int, height: Int) {
                val margin = dp2px(MARGIN_DP)
                val topMargin = 2 * margin + (title?.measuredHeight ?: 0)

                val percentTopMargin = topMargin.toFloat() / height.toFloat()
                val percentMargin = margin.toFloat() / width.toFloat()
                view?.setProportionalMargins(
                    percentMargin,
                    percentTopMargin,
                    percentMargin,
                    percentMargin
                )
                view?.pageProvider = pageProvider
            }

        }
    }

    /**
     * Called when activity is paused. Also pauses curl view.
     */
    override fun onPause() {
        super.onPause()
        view?.onPause()
    }

    /**
     * Called when activity is resumed. Also resumes curl view.
     */
    override fun onResume() {
        super.onResume()
        view?.onResume()
    }

    /**
     * Called when activity is destroyed.
     * Unloads all bitmaps.
     */
    override fun onDestroy() {
        super.onDestroy()
        unloadAllBitmaps()
    }

    /**
     * Loads all bitmaps into memory.
     * For large collections of bitmaps, only the ones close to the page being displayed should
     * be kept in memory, to ensure a good balance between performance and memory usage.
     */
    private fun loadAllBitmaps() {
        // bitmaps need to be loaded in memory first for performance reasons
        val drawables = listOf(R.drawable.image1, R.drawable.image2, R.drawable.image3)
        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            if (bitmap == null || bitmap.isRecycled) {
                bitmaps[i] = BitmapFactory.decodeResource(resources, drawables[i])
            }
        }
    }

    /**
     * Unloads all loaded bitmaps from memory.
     */
    private fun unloadAllBitmaps() {
        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            bitmaps[i] = null
        }
    }

    /**
     * Loads a single bitmap if not already loaded.
     *
     * @param width width of curl page expressed in pixels.
     * @param height height of curl page expressed in pixels.
     * @param index page position corresponding to the bitmap to be loaded.
     */
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

    private companion object {
        /**
         * Number of pages.
         */
        const val PAGE_COUNT = 3

        /**
         * Default margin.
         */
        const val MARGIN_DP = 20.0f

        /**
         * Converts from DP's to pixels.
         */
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
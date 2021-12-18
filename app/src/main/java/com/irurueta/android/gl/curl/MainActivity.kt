package com.irurueta.android.gl.curl

import android.content.res.Resources
import android.graphics.*
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var view: CurlTextureView? = null

    private var title: TextView? = null

    private var bitmaps = arrayOfNulls<Bitmap>(PAGE_COUNT)

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

    override fun onPause() {
        super.onPause()
        view?.onPause()
    }

    override fun onResume() {
        super.onResume()
        view?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        unloadAllBitmaps()
    }

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

    private companion object {
        const val PAGE_COUNT = 3
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
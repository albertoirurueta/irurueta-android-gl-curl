import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.irurueta.android.gl.curl.CurlPage
import com.irurueta.android.gl.curl.CurlTextureView

class BookActivity : AppCompatActivity() {

    private lateinit var curlView: CurlTextureView

    // Source images, decoded once and kept in memory
    private val bitmaps = mutableListOf<Bitmap>()

    private val pageProvider = object : CurlTextureView.PageProvider {
        override val pageCount: Int
            get() = bitmaps.size

        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int, backIndex: Int?) {
            page.setTexture(renderPage(width, height, index), CurlPage.SIDE_BOTH)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book)

        listOf(R.drawable.page1, R.drawable.page2, R.drawable.page3).forEach {
            bitmaps.add(BitmapFactory.decodeResource(resources, it))
        }

        curlView = findViewById(R.id.curl_view)
        curlView.pageProvider = pageProvider
    }

    override fun onResume() {
        super.onResume()
        curlView.onResume()
    }

    override fun onPause() {
        super.onPause()
        curlView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }

    private fun renderPage(width: Int, height: Int, index: Int): Bitmap {
        // Scale the source bitmap to the page size (see "Pages and textures")
        return Bitmap.createScaledBitmap(bitmaps[index], width, height, true)
    }
}

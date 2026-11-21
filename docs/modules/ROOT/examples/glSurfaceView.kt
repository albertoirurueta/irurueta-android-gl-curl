// Same API, different base class. The page provider interface is nested in each view class.
val surfaceView = findViewById<CurlGLSurfaceView>(R.id.curl_surface_view)
surfaceView.pageProvider = object : CurlGLSurfaceView.PageProvider {
    override val pageCount: Int
        get() = bitmaps.size

    override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int, backIndex: Int?) {
        page.setTexture(renderPage(width, height, index), CurlPage.SIDE_BOTH)
    }
}

private val pageProvider = object : CurlTextureView.PageProvider {

    // Total number of pages of the "book"
    override val pageCount: Int
        get() = bitmaps.size

    // Called every time a page needs its texture, possibly from the OpenGL thread.
    // width and height are the size in pixels the page is drawn with.
    override fun updatePage(
        page: CurlPage,
        width: Int,
        height: Int,
        index: Int,
        backIndex: Int?
    ) {
        val front = renderPage(width, height, index)
        if (backIndex == null) {
            // Same bitmap on both sides of the page
            page.setTexture(front, CurlPage.SIDE_BOTH)
        } else {
            // backIndex is the content to reveal on the back side (for instance, the target page
            // while jumping with setSmoothCurrentIndex)
            page.setTexture(front, CurlPage.SIDE_FRONT)
            page.setTexture(renderPage(width, height, backIndex), CurlPage.SIDE_BACK)
        }
    }
}

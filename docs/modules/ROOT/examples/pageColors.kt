override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int, backIndex: Int?) {
    page.setTexture(renderPage(width, height, index), CurlPage.SIDE_BOTH)

    // Blend the back side with a translucent white (0xCCFFFFFF), so the flipped page looks like
    // paper through which the printed content shows faintly
    page.setColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF), CurlPage.SIDE_BACK)
}

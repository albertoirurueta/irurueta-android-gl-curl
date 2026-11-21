/**
 * Draws the source bitmap of a page scaled to fit and centered on a white page of the requested size, so
 * that the aspect ratio of the source image is kept.
 */
private fun renderPage(width: Int, height: Int, index: Int): Bitmap? {
    val source = bitmaps[index] ?: return null

    val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
    val imageWidth = (scale * source.width).toInt()
    val imageHeight = (scale * source.height).toInt()
    val left = (width - imageWidth) / 2
    val top = (height - imageHeight) / 2

    val page = createBitmap(width, height)
    page.eraseColor(Color.WHITE)
    Canvas(page).drawBitmap(source, null, Rect(left, top, left + imageWidth, top + imageHeight), null)
    return page
}

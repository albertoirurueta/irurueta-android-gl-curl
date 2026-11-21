curlView.sizeChangedObserver = object : CurlTextureView.SizeChangedObserver {
    override fun onSizeChanged(width: Int, height: Int) {
        // Leave room for the title above the pages and a 20dp margin elsewhere
        val margin = dp2px(20.0f)
        val topMargin = 2 * margin + titleView.measuredHeight

        curlView.setProportionalMargins(
            margin.toFloat() / width,    // left
            topMargin.toFloat() / height, // top
            margin.toFloat() / width,    // right
            margin.toFloat() / width     // bottom
        )

        // Pages are requested with the final size once the provider is set
        curlView.pageProvider = pageProvider
    }
}

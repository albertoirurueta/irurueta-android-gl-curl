curlView.currentIndexChangedListener = object : CurlTextureView.CurrentIndexChangedListener {
    override fun onCurrentIndexChanged(view: CurlTextureView, currentIndex: Int) {
        // Called on the main thread
        pageLabel.text = "${currentIndex + 1} / ${pageProvider.pageCount}"
    }
}

curlView.pageClickListener = object : CurlTextureView.PageClickListener {
    override fun onPageClick(view: CurlTextureView, currentIndex: Int): Boolean {
        toggleToolbar()
        return true // click handled
    }
}

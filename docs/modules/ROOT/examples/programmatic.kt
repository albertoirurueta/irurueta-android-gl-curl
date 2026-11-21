val curlView = CurlTextureView(context)
curlView.layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.MATCH_PARENT
)
container.addView(curlView)
curlView.pageProvider = pageProvider

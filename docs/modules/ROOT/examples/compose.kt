@Composable
fun Book(pageProvider: CurlTextureView.PageProvider, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var curlView by remember { mutableStateOf<CurlTextureView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CurlTextureView(context).also {
                it.pageProvider = pageProvider
                curlView = it
            }
        }
    )

    // Forward resume/pause to the OpenGL thread of the view
    DisposableEffect(lifecycleOwner, curlView) {
        val view = curlView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

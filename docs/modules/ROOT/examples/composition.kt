// CurlTextureView is part of the view hierarchy, so regular view properties and animations apply
curlView.alpha = 0.9f
curlView.animate()
    .rotationY(15.0f)
    .scaleX(0.9f)
    .scaleY(0.9f)
    .setDuration(400L)
    .start()

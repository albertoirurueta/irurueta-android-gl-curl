// Mesh properties can only be changed while no page provider is set
// (they throw IllegalArgumentException otherwise), so configure them first.
curlView.maxCurlSplitsInMesh = 20                // smoother curl, more polygons
curlView.drawShadowInMesh = true
curlView.shadowInnerColorInMesh = floatArrayOf(0.0f, 0.0f, 0.0f, 0.6f) // RGBA in [0, 1]
curlView.shadowOuterColorInMesh = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
curlView.colorFactorOffsetInMesh = 0.5f          // lighter area close to the curl

curlView.pageProvider = pageProvider

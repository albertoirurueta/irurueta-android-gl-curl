# irurueta-android-gl-curl
A 3D page curl view

[![Build Status](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions/workflows/main.yml/badge.svg)](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions)

[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=code_smells)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=coverage)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)

[![Duplicated lines](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=duplicated_lines_density)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Lines of code](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=ncloc)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)

[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Quality gate](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=alert_status)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)

[![Security](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=security_rating)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Technical debt](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=sqale_index)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=albertoirurueta_irurueta-android-gl-curl)

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.irurueta/irurueta-android-gl-curl/badge.svg)](https://search.maven.org/artifact/com.irurueta/irurueta-android-gl-curl/1.0.1/aar)

[API Documentation](http://albertoirurueta.github.io/irurueta-android-gl-curl)

## Overview

This library contains views to draw pages of a book with a 3D animation when page flipping is done.
Two views are available:
- CurlGLSurfaceView: extends from GLSurfaceView, consequently the view does not belong to the normal
    view hierarchy preventing typical view animations or transparencies when views are composed. 
    Because of tat, it has slightly better performance.
- CurlTextureView: extends from GLTextureView, which belongs to the normal view hierarchy and allows
    normal view composition including transparencies or view animations.

![Demo](docs/video.gif)

## Usage

Add the following dependency to your project:

```
implementation 'com.irurueta:irurueta-android-gl-curl:1.0.2'
```

The view can be added to your layout as:

```
    <com.irurueta.android.gl.curl.CurlTextureView
        android:id="@+id/curl_gl_surface_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"/>
```

In order to draw something, a page provider must be provided as in the example below:

```
    view?.pageProvider = object : CurlTextureView.PageProvider {
        override val pageCount: Int
            get() = PAGE_COUNT

        override fun updatePage(
            page: CurlPage,
            width: Int,
            height: Int,
            index: Int,
            backIndex: Int?
        ) {
            // Load new bitmap for curl page view.
            // In real world scenario, images should be cached in memory for
            // performance reasons.
            val bitmap = loadBitmap(width, height, index)
            if (backIndex == null) {
                page.setTexture(bitmap, CurlPage.SIDE_BOTH)
            } else {
                // load bitmap for back side
                val backBitmap = loadBitmap(width, height, backIndex)
                page.setTexture(bitmap, CurlPage.SIDE_FRONT)
                page.setTexture(backBitmap, CurlPage.SIDE_BACK)
            }

            // set semi transparent white for background to get a bit of transparency
            // on back images when being flipped
            val context = view?.context ?: return
            page.setColor(
                ContextCompat.getColor(context, R.color.translucid_white),
                CurlPage.SIDE_BACK
            )
        }
    }
```

For more information, please refer to the demo application activity contained in the app module.


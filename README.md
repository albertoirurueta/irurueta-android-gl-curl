# irurueta-android-gl-curl

A view that displays pages that can be flipped like the pages of a paper book, with a 3D page curl effect.

[![Develop](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions/workflows/develop.yml/badge.svg)](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions/workflows/develop.yml)
[![Release](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions/workflows/main.yml/badge.svg)](https://github.com/albertoirurueta/irurueta-android-gl-curl/actions/workflows/main.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.irurueta/irurueta-android-gl-curl)](https://central.sonatype.com/artifact/com.irurueta/irurueta-android-gl-curl)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=bugs)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=coverage)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=albertoirurueta_irurueta-android-gl-curl&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)

![Demo](docs/video.gif)

## Overview

This library contains views that draw the pages of a book with OpenGL ES and flip them with a 3D page curl animation
that follows the finger. Pages are provided as bitmaps by a `PageProvider`, with independent textures and blend colors
for the front and back side of each page.

Two views with the same API are available:

- `CurlTextureView` extends `GLTextureView` (from
  [irurueta-android-glutils](https://github.com/albertoirurueta/irurueta-android-glutils)), a `TextureView` based
  OpenGL view. It belongs to the normal view hierarchy, so it can be composed with other views: it has a transparent
  background, can be placed on top of or below other views, and can be translucent, moved, scaled, rotated or animated.
  In the demo above, the title is a plain `TextView` placed behind the pages.
- `CurlGLSurfaceView` extends `GLSurfaceView`. It does not belong to the normal view hierarchy, which prevents view
  animations and transparency when composing views, but it has slightly better performance.

The page curl implementation is based on [harism/android-pagecurl](https://github.com/harism/android-pagecurl).

## Project Status

| | |
| --- | --- |
| Language | Kotlin (Java 17 bytecode) |
| Build tool | Gradle (Kotlin DSL) |
| Current development version | 1.2.0-SNAPSHOT |
| Latest release | 1.1.7 |
| Platform | Android |
| Min / compile SDK | minSdk 26, compileSdk 36 |
| Kotlin / AGP | Kotlin 2.3.10, AGP 9.0.1 |
| License | Apache License 2.0 |
| CI | GitHub Actions: `develop` builds (Develop) and `main` releases (Release, publishing to Maven Central); both deploy docs to GitHub Pages |
| Quality | SonarCloud, JaCoCo coverage, Android Lint, Dokka |

## Documentation

- [Documentation site](https://albertoirurueta.github.io/irurueta-android-gl-curl/)
- [API documentation (Dokka)](https://albertoirurueta.github.io/irurueta-android-gl-curl/api/index.html)
- [SonarCloud dashboard](https://sonarcloud.io/summary/new_code?id=albertoirurueta_irurueta-android-gl-curl)
- [Changelog](CHANGELOG.md)

## Installation

The library is published on Maven Central. Add the dependency to your module's build file.

Gradle (Groovy DSL):

```groovy
implementation 'com.irurueta:irurueta-android-gl-curl:1.1.7'
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.irurueta:irurueta-android-gl-curl:1.1.7")
```

## Usage

Add a `CurlTextureView` to your layout. Its background is transparent, so any view behind it stays visible:

```xml
<com.irurueta.android.gl.curl.CurlTextureView
    android:id="@+id/curl_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Provide the pages with a `PageProvider`, and forward the activity lifecycle to the view:

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.irurueta.android.gl.curl.CurlPage
import com.irurueta.android.gl.curl.CurlTextureView

class BookActivity : AppCompatActivity() {

    private lateinit var curlView: CurlTextureView

    // Source images, decoded once and kept in memory
    private val bitmaps = mutableListOf<Bitmap>()

    private val pageProvider = object : CurlTextureView.PageProvider {
        override val pageCount: Int
            get() = bitmaps.size

        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int, backIndex: Int?) {
            // width and height are the size of the page on screen, in pixels
            val front = Bitmap.createScaledBitmap(bitmaps[index], width, height, true)
            if (backIndex == null) {
                page.setTexture(front, CurlPage.SIDE_BOTH)
            } else {
                // Different content on the back side of the page
                val back = Bitmap.createScaledBitmap(bitmaps[backIndex], width, height, true)
                page.setTexture(front, CurlPage.SIDE_FRONT)
                page.setTexture(back, CurlPage.SIDE_BACK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book)

        listOf(R.drawable.page1, R.drawable.page2, R.drawable.page3).forEach {
            bitmaps.add(BitmapFactory.decodeResource(resources, it))
        }

        curlView = findViewById(R.id.curl_view)
        curlView.pageProvider = pageProvider
    }

    override fun onResume() {
        super.onResume()
        curlView.onResume()
    }

    override fun onPause() {
        super.onPause()
        curlView.onPause()
    }
}
```

Pages can also be changed from code, with or without animation, and page changes and taps can be observed:

```kotlin
curlView.setSmoothCurrentIndex(2)
curlView.currentIndexChangedListener = object : CurlTextureView.CurrentIndexChangedListener {
    override fun onCurrentIndexChanged(view: CurlTextureView, currentIndex: Int) {
        // called on the main thread when the visible page changes
    }
}
```

See the [documentation site](https://albertoirurueta.github.io/irurueta-android-gl-curl/) for back side textures and
colors, margins, one or two page modes, shadows, animation settings and Jetpack Compose usage, and the `app` module
for a complete sample.

## Branching

This repository follows gitflow:

- `develop` is the integration branch: feature branches are merged into it, and every push runs the Develop
  workflow (build, tests, lint, SonarCloud, snapshot publishing and docs deployed to GitHub Pages).
- `main` holds releases: release branches are merged into it, and the Release workflow publishes the library to
  Maven Central and the documentation to GitHub Pages. After each release, a pull request merging `main` back into
  `develop` with the next `-SNAPSHOT` version is opened automatically.

## License

Licensed under the [Apache License 2.0](LICENSE).

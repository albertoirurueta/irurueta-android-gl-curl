Notes specific to this template:

- `build-mode: none` is correct for `javascript-typescript` regardless of flavor — CodeQL's JS/TS extractor
  works directly from source, no compile step needed, unlike the Java template's `codeql` job which must run
  `mvn compile` first.
- **`nestjs`/`nextjs`**: the `codeql` job also scans the `actions` language (workflow injection, over-broad
  token permissions — these two flavors' workflows push images and deploy), as a matrix with one SARIF
  category per language, like `iru-setup-php-github-workflows`. Replace the `codeql` job above with:

  ```yaml
  codeql:
    name: CodeQL analysis (${{ matrix.language }})
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    strategy:
      fail-fast: false
      matrix:
        language: [ actions, javascript-typescript ]
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: ${{ matrix.language }}
          build-mode: none
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:${{ matrix.language }}
  ```

  The other flavors keep the single-language job unchanged — except `react-native` with `toolchain: metro`
  below (`toolchain: expo` keeps the single-language job: its native projects are generated at build time, so
  there is no committed native source to analyze).
- **`react-native` with `toolchain: metro`**: the committed `android/` (Kotlin `MainActivity`/`MainApplication`
  plus any native modules) and `ios/` (Swift `AppDelegate` plus any native modules) are first-party source, so the
  `codeql` job becomes a matrix with per-language build modes. Decision, documented here so every run makes the
  same one: `java-kotlin` uses `build-mode: manual` — CodeQL's `none` mode extracts Java only, never Kotlin, and
  the template's Android sources are all Kotlin — with a compile of just the app module's Kotlin/Java (no C++,
  no packaging) on Linux; `swift` always needs a real build on macOS (`build-mode: manual`, the same unsigned
  simulator build as `build.yml`), so its leg is included only when `visibility: public` (macOS minutes are free
  there) and omitted on private/internal repositories, which Step 8 reports along with how to add it back. Replace
  the `codeql` job with:

  ```yaml
  codeql:
    name: CodeQL analysis (${{ matrix.language }})
    runs-on: ${{ matrix.runner }}
    env:
      LANG: en_US.UTF-8   # CocoaPods (swift leg) needs a UTF-8 locale
    permissions:
      contents: read
      security-events: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - language: javascript-typescript
            build-mode: none
            runner: ubuntu-latest
          - language: actions
            build-mode: none
            runner: ubuntu-latest
          - language: java-kotlin
            build-mode: manual
            runner: ubuntu-latest
          # visibility: public only — drop this entry on private/internal repositories (Step 1's cost context)
          - language: swift
            build-mode: manual
            runner: <macos-runner>
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Set up Node <node-version>
        if: matrix.build-mode == 'manual'
        uses: actions/setup-node@v7
        with:
          node-version: <node-version>
          cache: <package-manager>
      - name: Install dependencies
        if: matrix.build-mode == 'manual'
        run: <install-command>
      - name: Set up JDK <rn-java-version>
        if: matrix.language == 'java-kotlin'
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<rn-java-version>'
      - name: Set up Android SDK
        if: matrix.language == 'java-kotlin'
        uses: android-actions/setup-android@v4
      - name: Set up Gradle
        if: matrix.language == 'java-kotlin'
        uses: gradle/actions/setup-gradle@v6
      - name: Set up Ruby and the Gemfile's CocoaPods
        if: matrix.language == 'swift'
        uses: ruby/setup-ruby@v1
        with:
          ruby-version: '<ruby-version>'
          bundler-cache: true
      - name: Install pods
        if: matrix.language == 'swift'
        working-directory: ios
        run: bundle exec pod install
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: ${{ matrix.language }}
          build-mode: ${{ matrix.build-mode }}
      # --no-build-cache: CodeQL only sees compiler invocations that actually run, so a cache hit would leave the
      # database empty
      - name: Compile the Android app's Kotlin/Java sources
        if: matrix.language == 'java-kotlin'
        working-directory: android
        run: ./gradlew --no-build-cache :app:compileDebugKotlin :app:compileDebugJavaWithJavac
      - name: Build the iOS app for the simulator
        if: matrix.language == 'swift'
        working-directory: ios
        run: |
          xcodebuild -workspace <ios-workspace>.xcworkspace -scheme <ios-scheme> -configuration Debug \
            -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
            CODE_SIGNING_ALLOWED=NO build
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:${{ matrix.language }}
  ```

  `actions` is scanned for metro for the same reason as nestjs/nextjs: its `release.yml` handles signing keys and
  store credentials.
- None of these four jobs need a repository secret beyond the automatically provided `GITHUB_TOKEN` — don't
  add any of them to Step 7's secrets table.
- If `github.com` public-repository default CodeQL setup is simpler for this repository than a workflow-based
  CodeQL job, note that as an alternative in Step 8's report instead of writing the `codeql` job, same as the
  Java skill.

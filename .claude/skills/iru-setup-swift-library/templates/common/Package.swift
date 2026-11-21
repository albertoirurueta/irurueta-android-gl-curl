// swift-tools-version: 6.0
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "<package-name>",
    platforms: [
        .iOS(.v<ios-min-major>),
        .macOS(.v<macos-min-major>),
    ],
    products: [
        .library(
            name: "<Name>",
            targets: ["<Name>"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/swiftlang/swift-docc-plugin", from: "<swift-docc-plugin-version>"),
    ],
    targets: [
        .target(
            name: "<Name>"
        ),
        .testTarget(
            name: "<Name>Tests",
            dependencies: ["<Name>"]
        ),
    ],
    swiftLanguageModes: [.v6]
)

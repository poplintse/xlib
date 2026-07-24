// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "AppleShared",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "AppleShared", targets: ["AppleShared"]),
    ],
    targets: [
        .target(name: "AppleShared"),
        .testTarget(name: "AppleSharedTests", dependencies: ["AppleShared"]),
    ]
)

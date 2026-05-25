// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "FluxzeroLaunchpad",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "FluxzeroLaunchpad", targets: ["FluxzeroLaunchpad"])
    ],
    targets: [
        .executableTarget(
            name: "FluxzeroLaunchpad",
            path: "Sources"
        )
    ]
)

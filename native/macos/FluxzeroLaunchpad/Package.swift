// swift-tools-version: 5.10

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

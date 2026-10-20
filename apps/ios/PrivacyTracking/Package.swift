// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PrivacyTracking",
    platforms: [.iOS("18.0")],
    products: [.library(name: "PrivacyTracking", targets: ["PrivacyTracking"])],
    dependencies: [.package(url: "https://github.com/yeatse/opencv-spm.git", exact: "4.12.0")],
    targets: [.target(name: "PrivacyTracking", dependencies: [.product(name: "OpenCV", package: "opencv-spm")],
                      publicHeadersPath: "include")],
    cxxLanguageStandard: .cxx17
)

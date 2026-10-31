fn main() {
    // The pinned static compiler needs the platform C++ runtime on macOS.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-lib=c++");
    }
}

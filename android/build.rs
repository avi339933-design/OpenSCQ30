fn main() {
    println!("cargo:rerun-if-changed=uniffi.toml");
    cc::Build::new()
        .file("dl_iterate_phdr_shim.c")
        .compile("dl_iterate_phdr_shim");
}

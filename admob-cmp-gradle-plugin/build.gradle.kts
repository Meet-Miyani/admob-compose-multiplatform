import java.util.Properties

plugins {
    `kotlin-dsl`
    // Must match version in root gradle/libs.versions.toml
    id("com.vanniktech.maven.publish") version "0.37.0"
}


group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

dependencies {
    // The plugin configures Kotlin Multiplatform test binaries, so it compiles against KGP.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("admobCmp") {
            id = "dev.avinya.ads.admob-cmp"
            implementationClass = "dev.avinya.ads.gradle.AdMobCmpPlugin"
            displayName = "AdMob CMP"
            description = "Links Google Mobile Ads into Kotlin/Native test executables for admob-cmp consumers."
        }
    }
}

// The GMA version these bindings were generated from lives in the root catalog, and the
// archive checksums in the root gradle.properties. Read them at build time so the plugin
// can never drift from the bindings it is shipped alongside.
val rootDirOfLibrary = layout.projectDirectory.dir("..")

fun tomlVersion(key: String): String {
    val toml = rootDirOfLibrary.file("gradle/libs.versions.toml").asFile.readText()
    return Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(toml)?.groupValues?.get(1)
        ?: error("Version '$key' not found in gradle/libs.versions.toml")
}

fun rootProperty(key: String): String {
    val props = Properties().apply {
        rootDirOfLibrary.file("gradle.properties").asFile.inputStream().use { load(it) }
    }
    return props.getProperty(key) ?: error("Property '$key' not found in gradle.properties")
}

val generateNativeDeps by tasks.registering(WriteProperties::class) {
    destinationFile.set(layout.buildDirectory.file("generated/admobCmp/admob-cmp-native-deps.properties"))
    property("gmaIosVersion", tomlVersion("gmaIos"))
    property("gmaUmpIosVersion", tomlVersion("gmaUmpIos"))
    property("gmaIosSha256", rootProperty("gmaIosHeadersSha256"))
    property("umpIosSha256", rootProperty("umpIosHeadersSha256"))
}

sourceSets.main {
    resources.srcDir(generateNativeDeps.map { it.destinationFile.get().asFile.parentFile })
}


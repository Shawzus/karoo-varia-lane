pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Hammerhead's karoo-ext library is on GitHub Packages, which always needs a GitHub login.
// On GitHub Actions the workflow sets GPR_USER/GPR_KEY automatically.
// For a local build, put gpr.user=<github username> and gpr.key=<token with read:packages> in local.properties.
fun localProperty(key: String): String? {
    val file = File(settingsDir, "local.properties")
    if (!file.isFile) return null
    val props = java.util.Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(key)
}

val gprUser: String? = System.getenv("GPR_USER") ?: localProperty("gpr.user")
val gprKey: String? = System.getenv("GPR_KEY") ?: localProperty("gpr.key")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprUser
                password = gprKey
            }
        }
    }
}

rootProject.name = "VariaLanes"
include(":app")

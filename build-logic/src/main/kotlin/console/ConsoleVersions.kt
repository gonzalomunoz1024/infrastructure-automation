package console

import java.util.Properties

/** The versions the convention plugins add to a module's classpath, written from the catalog when build-logic builds. */
object ConsoleVersions {
    private val properties: Properties by lazy {
        Properties().apply {
            ConsoleVersions::class.java.getResourceAsStream("/console-versions.properties")?.use { load(it) }
                ?: error("console-versions.properties is missing from build-logic")
        }
    }

    val springBoot: String get() = properties.getProperty("springBoot")
    val archunit: String get() = properties.getProperty("archunit")
}

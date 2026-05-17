import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

abstract class GenerateAboutLibrariesJsonTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        notCompatibleWithConfigurationCache("Reads the live Gradle dependency model while generating license metadata.")
    }

    @TaskAction
    fun generate() {
        val library =
            { id: String, version: String, name: String, description: String, website: String, scmUrl: String, licenses: List<String> ->
                mapOf(
                    "uniqueId" to id,
                    "artifactVersion" to version,
                    "name" to name,
                    "description" to description,
                    "website" to website,
                    "scm" to mapOf("url" to scmUrl),
                    "licenses" to licenses,
                )
            }
        val text = { element: Element, tag: String ->
            element.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        val licenseName = { value: String ->
            when {
                value.equals("MIT License", ignoreCase = true) -> "MIT"
                value.equals("The MIT License", ignoreCase = true) -> "MIT"
                value.contains("Apache", ignoreCase = true) && value.contains("2.0") -> "Apache-2.0"
                value.contains("BSD", ignoreCase = true) && value.contains("3") -> "BSD-3-Clause"
                else -> value
            }
        }
        val dependencyBucketSuffixes = listOf("api", "implementation", "compileonly", "runtimeonly")
        val libraryOverrides = mapOf(
            "com.github.topjohnwu.libsu:core" to
                { version: String ->
                    library(
                        "com.github.topjohnwu.libsu:core",
                        version,
                        "libsu",
                        "A complete solution for apps using root permissions.",
                        "https://github.com/topjohnwu/libsu",
                        "https://github.com/topjohnwu/libsu",
                        listOf("Apache-2.0"),
                    )
                },
        )
        val bundledRuntimeLibraries = listOf(
            library(
                "github:2dust/AndroidLibXrayLite",
                ProjectConfig.ANDROID_LIB_XRAY_LITE_VERSION,
                "AndroidLibXrayLite",
                "A lightweight Android AAR wrapper for xray-core.",
                "https://github.com/2dust/AndroidLibXrayLite",
                "https://github.com/2dust/AndroidLibXrayLite",
                listOf("LGPL-3.0"),
            ),
            library(
                "github:XTLS/Xray-core",
                ProjectConfig.XRAY_CORE_VERSION,
                "xray-core",
                "An open platform for proxy and anti-censorship networking.",
                "https://github.com/XTLS/Xray-core",
                "https://github.com/XTLS/Xray-core",
                listOf("MPL-2.0"),
            ),
        )

        val gradleLibraries = project.rootProject.allprojects
            .flatMap { it.configurations }
            .filter { configuration ->
                val name = configuration.name.lowercase()
                "test" !in name && dependencyBucketSuffixes.any { suffix ->
                    name.endsWith(suffix) || name.endsWith("${suffix}dependenciesmetadata")
                }
            }
            .flatMap { it.dependencies.withType(ExternalModuleDependency::class.java) }
            .mapNotNull { dependency ->
                val group = dependency.group.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = dependency.name
                val version = dependency.version?.takeIf { it.isNotBlank() }
                    ?: dependency.versionConstraint.requiredVersion.takeIf { it.isNotBlank() }
                    ?: dependency.versionConstraint.preferredVersion.takeIf { it.isNotBlank() }
                    ?: dependency.versionConstraint.strictVersion.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                if (group == "org.jetbrains.compose.hot-reload") return@mapNotNull null
                val id = "$group:$name"
                libraryOverrides[id]?.let { override -> return@mapNotNull override(version) }
                val fallbackUrl = "https://mvnrepository.com/artifact/$group/$name"
                runCatching {
                    val artifact = project.dependencies.createArtifactResolutionQuery()
                        .forModule(group, name, version)
                        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                        .execute()
                        .resolvedComponents
                        .flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
                        .filterIsInstance<ResolvedArtifactResult>()
                        .firstOrNull()
                        ?: return@runCatching null

                    val pom =
                        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(artifact.file).documentElement
                    val website = text(pom, "url") ?: fallbackUrl
                    val scmUrl =
                        (pom.getElementsByTagName("scm").item(0) as? Element)?.let { text(it, "url") } ?: website
                    val licenses = pom.getElementsByTagName("license")
                        .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                        .mapNotNull { text(it, "name")?.let(licenseName) }
                        .takeIf { it.isNotEmpty() }
                        ?: listOf("Unknown")

                    library(
                        id,
                        version,
                        text(pom, "name") ?: name,
                        text(pom, "description") ?: id,
                        website,
                        scmUrl,
                        licenses,
                    )
                }.getOrNull() ?: library(id, version, name, id, fallbackUrl, fallbackUrl, listOf("Unknown"))
            }

        val libraries = (gradleLibraries + bundledRuntimeLibraries)
            .distinctBy { it["uniqueId"] }
            .sortedBy { it["uniqueId"] as String }

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("libraries" to libraries))) + "\n")
        }
    }
}

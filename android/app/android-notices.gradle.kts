import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

val noticesDirectory = layout.buildDirectory.dir("generated/androidNotices")

tasks.register("generateAndroidNotices") {
    group = "build"
    description = "Generate copyright notices from the actual resolved Android runtime artifacts and POMs."
    val runtimeNames = listOf("releaseRuntimeClasspath", "debugRuntimeClasspath")
    inputs.files(runtimeNames.map { configurations.named(it) })
    inputs.file(file("android-notices.gradle.kts"))
    inputs.file(file("../third_party/licenses/Apache-2.0.txt"))
    inputs.file(file("../third_party/licenses/rustls-platform-verifier-android-0.2.0/LICENSE-MIT"))
    inputs.file(file("../../crates/app/assets/fonts/OFL-IBMPlex.txt"))
    inputs.file(file("../../crates/app/assets/fonts/OFL-SpaceGrotesk.txt"))
    outputs.dir(noticesDirectory)
    doLast {
        val artifacts = runtimeNames.flatMap { configurations.getByName(it).resolvedConfiguration.resolvedArtifacts }
            .distinctBy { "${it.moduleVersion.id}:${it.classifier}:${it.extension}" }
            .sortedBy { "${it.moduleVersion.id}:${it.classifier}:${it.extension}" }
        val components = runtimeNames.flatMap { configurations.getByName(it).incoming.resolutionResult.allComponents }
            .mapNotNull { it.id as? ModuleComponentIdentifier }.distinctBy { it.displayName }
        val queried = dependencies.createArtifactResolutionQuery().forComponents(components)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        val pomFiles = queried.resolvedComponents.associate { component ->
            component.id.displayName to component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>().singleOrNull()?.file
        }
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun isNotice(name: String): Boolean = name.substringAfterLast('/').lowercase().let {
            it.startsWith("license") || it.startsWith("licence") || it.startsWith("notice") || it.startsWith("copying")
        }
        val text = StringBuilder("Black Marlin Downloader — Android dependency notices\n\n")
        text.append("Generated from resolved release and debug runtime artifacts. Debug-only dependencies are included for transparency.\n")
        text.append("POM-declared license names/URLs and embedded copyright/NOTICE files are preserved below.\n")
        text.append("Apache-2.0 license text is included once at the end; the Rustls Android support component uses its MIT option.\n")
        val report = mutableListOf<Map<String, Any>>()
        val unresolved = mutableListOf<String>()
        for ((id, entries) in artifacts.groupBy { it.moduleVersion.id.toString() }.toSortedMap()) {
            val pom = pomFiles[id]
            val pomEvidence = mutableListOf<Map<String, String>>()
            val licenses = mutableListOf<Map<String, String>>()
            var sourceUrl = ""
            var licensePom = pom
            var licensePomId = id
            var depth = 0
            while (licensePom != null && licenses.isEmpty() && depth++ < 6) {
                pomEvidence += mapOf("coordinate" to licensePomId, "sha256" to sha256(licensePom.readBytes()))
                val document = xml.parse(licensePom)
                val nodes = document.getElementsByTagName("license")
                for (i in 0 until nodes.length) {
                    val children = nodes.item(i).childNodes
                    val values = mutableMapOf<String, String>()
                    for (j in 0 until children.length) {
                        val child = children.item(j)
                        if (child.nodeName in setOf("name", "url", "distribution")) values[child.nodeName] = child.textContent.trim()
                    }
                    licenses += values
                }
                val scm = document.getElementsByTagName("scm")
                if (scm.length > 0 && sourceUrl.isEmpty()) {
                    val children = scm.item(0).childNodes
                    for (j in 0 until children.length) if (children.item(j).nodeName == "url") sourceUrl = children.item(j).textContent.trim()
                }
                val parent = document.getElementsByTagName("parent")
                licensePom = null
                if (licenses.isEmpty() && parent.length > 0) {
                    val children = parent.item(0).childNodes
                    val coordinate = mutableMapOf<String, String>()
                    for (j in 0 until children.length) {
                        val child = children.item(j)
                        if (child.nodeName in setOf("groupId", "artifactId", "version")) coordinate[child.nodeName] = child.textContent.trim()
                    }
                    val parts = listOf("groupId", "artifactId", "version").map { coordinate[it].orEmpty() }
                    if (parts.all { it.isNotBlank() && !it.contains('$') }) {
                        licensePomId = parts.joinToString(":")
                        val inherited = configurations.detachedConfiguration(dependencies.create("$licensePomId@pom"))
                        inherited.isTransitive = false
                        licensePom = inherited.singleFile
                    }
                }
            }
            val embedded = linkedMapOf<String, String>()
            for (artifact in entries) {
                if (artifact.extension !in setOf("jar", "aar")) continue
                ZipFile(artifact.file).use { archive ->
                    for (entry in archive.entries()) {
                        if (!entry.isDirectory && isNotice(entry.name) && entry.size in 1..2_097_152) {
                            embedded[artifact.file.name + "!/" + entry.name] = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                        }
                        if (artifact.extension == "aar" && entry.name == "classes.jar" && entry.size in 1..67_108_864) {
                            ZipInputStream(archive.getInputStream(entry)).use { nested ->
                                var part = nested.nextEntry
                                while (part != null) {
                                    if (!part.isDirectory && isNotice(part.name)) {
                                        val bytes = nested.readNBytes(2_097_153)
                                        check(bytes.size <= 2_097_152) { "Oversized embedded license: $id" }
                                        embedded[artifact.file.name + "!/classes.jar!/" + part.name] = bytes.toString(Charsets.UTF_8)
                                    }
                                    part = nested.nextEntry
                                }
                            }
                        }
                    }
                }
            }
            val isRustls = id == "org.rustls:rustls-platform-verifier:0.2.0"
            val apache = licenses.any { (it["name"].orEmpty() + " " + it["url"].orEmpty()).contains("apache", ignoreCase = true) }
            if (isRustls) embedded["Upstream MIT license at published support-crate revision 252e25161a91af476cbea620e29d277592d27ec7"] =
                file("../third_party/licenses/rustls-platform-verifier-android-0.2.0/LICENSE-MIT").readText()
            if (pom == null || (licenses.isEmpty() && !isRustls) || (!apache && !isRustls && embedded.isEmpty())) unresolved += id
            text.append("\n" + "=".repeat(72) + "\n$id\n")
            text.append("Source: $sourceUrl\n")
            for (evidence in pomEvidence) text.append("POM evidence: ${evidence["coordinate"]} SHA-256 ${evidence["sha256"]}\n")
            for (license in licenses) text.append("License: ${license["name"].orEmpty()} — ${license["url"].orEmpty()}\n")
            if (isRustls) text.append("Selected license: MIT\n")
            val artifactReport = entries.map { artifact ->
                val hash = sha256(artifact.file.readBytes())
                text.append("Artifact: ${artifact.file.name}\nSHA-256: $hash\n")
                mapOf("name" to artifact.file.name, "sha256" to hash)
            }
            for ((name, content) in embedded) text.append("\n--- $name ---\n$content\n")
            report += mapOf("coordinate" to id, "sourceUrl" to sourceUrl, "licenses" to licenses,
                "artifacts" to artifactReport, "embeddedNotices" to embedded.keys.toList(), "pomEvidence" to pomEvidence)
        }
        check(unresolved.isEmpty()) { "Missing Android dependency license evidence: ${unresolved.joinToString()}. Review the resolved POM/artifact before shipping." }
        for ((name, license) in listOf(
            "IBM Plex Sans and IBM Plex Mono" to "OFL-IBMPlex.txt",
            "Space Grotesk" to "OFL-SpaceGrotesk.txt",
        )) {
            text.append("\n" + "=".repeat(72) + "\n$name — bundled app fonts\n")
            text.append(file("../../crates/app/assets/fonts/$license").readText())
        }
        text.append("\n" + "=".repeat(72) + "\nApache License, Version 2.0\n\n")
        text.append(file("../third_party/licenses/Apache-2.0.txt").readText())
        val directory = noticesDirectory.get().asFile.apply { mkdirs() }
        val noticesFile = directory.resolve("THIRD_PARTY_ANDROID_NOTICES.txt")
        noticesFile.writeText(text.toString())
        directory.resolve("android-dependencies.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
            mapOf("schemaVersion" to 1, "configurations" to runtimeNames, "noticesSha256" to sha256(noticesFile.readBytes()), "dependencies" to report))))
        logger.lifecycle("Generated Android notices for ${report.size} resolved runtime components; all have license evidence.")
    }
}

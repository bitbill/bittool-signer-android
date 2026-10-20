import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.bittool.android.library)
}

android {
    namespace = "com.bittool.signer.core.l10n"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}

/**
 * An explicit English locale (activity-settings.md §14.5 pitfall (a); ARCHITECTURE §3 W2-shell): with only `values/`
 * holding English, "System" on a device whose language list is `[en-GB, de]` resolves to **German** — Android looks
 * for each listed language in turn and finds no `en`. A `values-en/` copy of every translatable string of
 * `values/strings_*.xml` makes English a real match for any English locale. Generated into the build directory (never
 * committed, so it cannot drift); non-translatable entries stay in `values/` only.
 */
abstract class GenerateEnglishLocale : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val target = File(root, "values-en").apply { mkdirs() }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        sources.files.filter { it.isFile }.sortedBy { it.name }.forEach { source ->
            val document = factory.newDocumentBuilder().parse(source)
            val resources = document.documentElement
            val children = resources.childNodes
            for (i in children.length - 1 downTo 0) {
                val node = children.item(i)
                val keep = node is Element && node.getAttribute("translatable") != "false"
                if (!keep) resources.removeChild(node)
            }
            if (resources.getElementsByTagName("*").length == 0) return@forEach
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "utf-8")
                setOutputProperty(OutputKeys.INDENT, "yes")
            }
            File(target, source.name).outputStream().use { out -> transformer.transform(DOMSource(document), StreamResult(out)) }
        }
    }
}

val generateEnglishLocale = tasks.register<GenerateEnglishLocale>("generateEnglishLocale") {
    sources.from(fileTree("src/main/res/values") { include("strings_*.xml") })
    outputDirectory.set(layout.buildDirectory.dir("generated/res/englishLocale"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateEnglishLocale, GenerateEnglishLocale::outputDirectory)
    }
}

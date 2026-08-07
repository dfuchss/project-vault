plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))

    // Tier-2 embeddings: DJL HuggingFace tokenizer + onnxruntime (native in-jar) to run the bundled
    // multilingual ONNX model directly.
    implementation(libs.djl.tokenizers)
    implementation(libs.onnxruntime)

    testImplementation(libs.kotlin.test.junit)
}

// Fetch the bundled multilingual embedding model (~128MB, gitignored) at build time if missing, so a
// fresh checkout packages it. The app still runs (Tier 1 only) if it's absent.
//
// ATTRIBUTION: this model (Xenova/multilingual-e5-small, an ONNX build of intfloat/multilingual-e5-small)
// is third-party work under the MIT License. It is redistributed in the packaged app, so the license
// and citations ship alongside it in resources/EMBEDDING_MODEL_NOTICE.txt (see also THIRD_PARTY_NOTICES.md).
val fetchEmbeddingModel by tasks.registering {
    val modelDir = layout.projectDirectory.dir("src/main/resources/embedding/model")
    outputs.dir(modelDir)
    onlyIf { !modelDir.file("model.onnx").asFile.exists() }
    doLast {
        val base = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"
        val files = mapOf(
            "model.onnx" to "$base/onnx/model_quantized.onnx",
            "tokenizer.json" to "$base/tokenizer.json",
            "config.json" to "$base/config.json",
        )
        modelDir.asFile.mkdirs()
        files.forEach { (name, url) ->
            val target = modelDir.file(name).asFile
            if (!target.exists()) {
                logger.lifecycle("Fetching embedding model file: $name")
                uri(url).toURL().openStream().use { input -> target.outputStream().use(input::copyTo) }
            }
        }
    }
}

tasks.named("processResources") { dependsOn(fetchEmbeddingModel) }

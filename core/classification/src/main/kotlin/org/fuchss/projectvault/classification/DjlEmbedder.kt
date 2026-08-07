package org.fuchss.projectvault.classification

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * Real [Embedder] backed by a bundled multilingual sentence model (multilingual-e5-small, quantized
 * ONNX) run on onnxruntime. The model + tokenizer ship as app resources under `/embedding/model/`;
 * they're extracted and loaded once, lazily. If anything fails (resources absent / native libs
 * unavailable) [available] is false and callers fall back to Tier 1.
 *
 * The model is third-party work (MIT License) — attribution and license text ship in
 * `resources/EMBEDDING_MODEL_NOTICE.txt`; see also `THIRD_PARTY_NOTICES.md`.
 *
 * Tokenization is driven directly (via the HuggingFace tokenizer) and inference too, because the
 * e5 (XLM-RoBERTa) ONNX export expects a `token_type_ids` input the tokenizer doesn't emit — we feed
 * zeros. Token vectors are mean-pooled over the attention mask and L2-normalized, so cosine == dot.
 * e5 expects a task prefix; we use "query: " consistently so similarities are comparable.
 */
class DjlEmbedder(private val prefix: String = "query: ") : Embedder {

    private val engine: Engine? by lazy { load() }

    override fun available(): Boolean = engine != null

    override fun embed(texts: List<String>): List<FloatArray> {
        val e = engine ?: return texts.map { FloatArray(0) }
        return synchronized(e) { texts.map { embedOne(e, prefix + it) } }
    }

    private class Engine(
        val env: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
        val inputNames: Set<String>,
    )

    private fun load(): Engine? = runCatching {
        val dir = extractBundledModel()
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(File(dir, "model.onnx").absolutePath, OrtSession.SessionOptions())
        val tokenizer = HuggingFaceTokenizer.newInstance(File(dir, "tokenizer.json").toPath())
        Engine(env, session, tokenizer, session.inputNames.toSet())
    }.getOrNull()

    private fun embedOne(engine: Engine, text: String): FloatArray {
        val encoding = engine.tokenizer.encode(text)
        val ids = encoding.ids
        val mask = encoding.attentionMask
        val length = ids.size

        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(engine.env, arrayOf(ids))
        inputs["attention_mask"] = OnnxTensor.createTensor(engine.env, arrayOf(mask))
        if ("token_type_ids" in engine.inputNames) {
            inputs["token_type_ids"] = OnnxTensor.createTensor(engine.env, arrayOf(LongArray(length)))
        }

        try {
            engine.session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (result[0].value as Array<Array<FloatArray>>)[0] // [tokens][dim]
                return meanPoolAndNormalize(hidden, mask)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden.firstOrNull()?.size ?: return FloatArray(0)
        val pooled = FloatArray(dim)
        var count = 0f
        for (i in hidden.indices) {
            if (mask[i] != 0L) {
                count++
                val row = hidden[i]
                for (d in 0 until dim) pooled[d] += row[d]
            }
        }
        if (count > 0f) for (d in 0 until dim) pooled[d] /= count
        var norm = 0f
        for (v in pooled) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) for (d in 0 until dim) pooled[d] /= norm
        return pooled
    }

    private fun extractBundledModel(): File {
        val dir = File(Files.createTempDirectory("pv-embed").toFile(), "model").apply { mkdirs() }
        for (name in listOf("model.onnx", "tokenizer.json", "config.json")) {
            val stream = javaClass.getResourceAsStream("/embedding/model/$name")
                ?: error("bundled embedding resource missing: $name")
            stream.use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
        return dir
    }
}

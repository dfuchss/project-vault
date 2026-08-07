# Third-party notices

Project Vault (licensed under the GNU GPL v3, see [`LICENSE.md`](LICENSE.md)) bundles or depends on
third-party components that carry their own licenses. The most notable is the on-device embedding
model, which is **redistributed** in the packaged app and therefore requires attribution.

## Bundled embedding model — `multilingual-e5-small`

Classification Tier 2 (semantic categorization) runs a bundled multilingual sentence-embedding model
on-device. It is fetched at build time and packaged into the app's resources.

- **Bundled build:** [`Xenova/multilingual-e5-small`](https://huggingface.co/Xenova/multilingual-e5-small)
  — quantized ONNX + tokenizer, converted by Xenova.
- **Original model:** [`intfloat/multilingual-e5-small`](https://huggingface.co/intfloat/multilingual-e5-small)
  — by Liang Wang, Nan Yang, Xiaolong Huang, Linjun Yang, Rangan Majumder, Furu Wei (Microsoft).
- **License:** MIT (both the original model and the ONNX conversion). Full text ships with the app in
  `core/classification/src/main/resources/EMBEDDING_MODEL_NOTICE.txt`.

Citations:

> Wang et al., *Multilingual E5 Text Embeddings: A Technical Report*, arXiv:2402.05672, 2024.
>
> Wang et al., *Text Embeddings by Weakly-Supervised Contrastive Pre-training*, arXiv:2212.03533, 2022.

## Key runtime libraries

These are used as dependencies (not redistributed as source) and retain their own licenses:

| Component | Purpose | License |
|---|---|---|
| Apache PDFBox | PDF text extraction | Apache-2.0 |
| ONNX Runtime (`com.microsoft.onnxruntime`) | runs the embedding model | MIT |
| DJL HuggingFace tokenizers (`ai.djl.huggingface:tokenizers`) | tokenization for the model | Apache-2.0 |
| SQLDelight + `sqlite-jdbc` | persistence / vault | Apache-2.0 |
| JetBrains Compose Multiplatform | desktop UI | Apache-2.0 |
| SLF4J (`slf4j-simple`) | logging backend | MIT |

See each project's distribution for the authoritative license text.

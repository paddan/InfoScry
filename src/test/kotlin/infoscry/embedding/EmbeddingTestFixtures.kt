package infoscry.embedding

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The shared pieces the embedding tests build on: a model manifest shaped like the pinned one, and the
 * committed tokenizer fixture that lets the measuring code run without the 1.1 GB model.
 *
 * The manifest here is a *test* manifest. Its checksums are the digests of the bytes the test itself serves,
 * which is legitimate for a fixture and is exactly what must never happen for the pinned manifest — there the
 * expected checksums come from the pinned revision, and [ModelManagerTest] asserts that they do.
 */
internal fun testManifest(
    maxSequenceTokens: Int = 512,
    files: List<ModelFile> = listOf(
        ModelFile("model.onnx", bytes = 8, sha256 = "0".repeat(64), sha256Source = "lfs-oid"),
        ModelFile("tokenizer.json", bytes = 8, sha256 = "1".repeat(64), sha256Source = "lfs-oid"),
    ),
    baseUrl: String = "http://127.0.0.1:1/onnx",
): ModelManifest = ModelManifest(
    model = "intfloat/multilingual-e5-base",
    revision = "d128750597153bb5987e10b1c3493a34e5a4502a",
    baseUrl = baseUrl,
    dimension = 768,
    maxSequenceTokens = maxSequenceTokens,
    pooling = "attention-mask-mean",
    prefixVersion = "e5-query-passage-1",
    passagePrefix = "passage: ",
    queryPrefix = "query: ",
    nativeRuntime = NativeRuntime("com.microsoft.onnxruntime:onnxruntime", "1.22.0"),
    executionProvider = ExecutionProvider(
        name = "CoreML",
        options = mapOf(
            "MLComputeUnits" to "CPUAndGPU",
            "ModelFormat" to "MLProgram",
            "EnableOnSubgraphs" to "1",
        ),
    ),
    files = files,
)

/** The committed tokenizer fixture: a word-level tokenizer with the same shape as the pinned one. */
internal fun testTokenizerPath(): Path = Path.of(
    requireNotNull(object {}.javaClass.getResource("/embedding/tokenizer.json")) {
        "the tokenizer fixture is missing from the test resources"
    }.toURI(),
)

internal fun sha256OfBytes(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** A manifest whose files are the given contents, so a test can serve and verify them. */
internal fun manifestForFiles(contents: Map<String, ByteArray>, baseUrl: String): ModelManifest =
    testManifest(
        baseUrl = baseUrl,
        files = contents.entries.map { (name, bytes) ->
            ModelFile(
                name = name,
                bytes = bytes.size.toLong(),
                sha256 = sha256OfBytes(bytes),
                sha256Source = "test-fixture",
            )
        },
    )

/** A content unit whose search text is [text], for tests that need something to measure or chunk. */
internal fun testUnit(text: String): infoscry.domain.ContentUnit = infoscry.domain.ContentUnit(
    id = infoscry.domain.ContentUnitId.new(),
    documentId = infoscry.domain.DocumentId.new(),
    ordinal = 0,
    locator = infoscry.domain.SourceLocation.TextLines(1, 1),
    extractedText = text,
    searchText = text,
)

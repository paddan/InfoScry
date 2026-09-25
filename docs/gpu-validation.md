# GPU validation

How InfoScry proves that its embedding model runs on the accelerator it requires, what was measured, and
what is deliberately outside the v1 matrix.

## The validated matrix

| Platform | Provider | Provider options | Status |
|---|---|---|---|
| macOS arm64, Apple GPU | ONNX Runtime CoreML | `MLComputeUnits=CPUAndGPU`, `ModelFormat=MLProgram`, `EnableOnSubgraphs=1` | **The only validated v1 target** |
| macOS arm64 without CoreML available | — | — | Refused: `GPU_UNAVAILABLE` |
| macOS x86_64, Linux (any GPU), Windows | — | — | Outside the matrix, refused |

There is no CUDA path in v1 and none is planned here: the Linux x86_64/NVIDIA target was deferred on
2026-09-21 because no NVIDIA hardware was available to validate it, and untested GPU code is worse than
absent GPU code. The manifest therefore pins one export (`onnx/model.onnx`), declares no `onnxruntime_gpu`
dependency, and ships one provider configuration.

`CPUAndGPU` excludes the Neural Engine on purpose. `MLComputeUnits=ALL` would also be accelerated, but it
would make the claim "the GPU executed this" unverifiable from outside: with the ANE in the pool, a trace
cannot distinguish a GPU kernel from an ANE one.

## Why registration is not evidence

ONNX Runtime accepts a provider, then decides per node which execution provider takes it. A model whose
graph the provider cannot take runs entirely on the CPU and reports no error at all: the session is
created, inference succeeds, vectors come back, and every operation was scalar code on the CPU. That
failure mode has no symptom, so the gate is built out of evidence instead:

1. the readiness probe refuses a machine outside the matrix and a runtime that does not offer CoreML, and
2. the session itself is created with profiling enabled, its warm-up is profiled, and the profile must show
   CoreML kernel events **carrying more than half the measured kernel time**
   (`GpuRuntime.REQUIRED_CORE_ML_SHARE`, the same bound the validation run asserts) — a graph that ran
   entirely on the CPU, or one the CPU carries, is refused with `GPU_UNAVAILABLE`.

The second check is `GpuRuntime.requireCoreMlExecution`, unit-tested against synthetic profiles in
`GpuRuntimeTest` (a CoreML-only profile is accepted; a CoreML kernel that carries the kernel time is accepted;
a graph the CPU carries is refused even when CoreML ran something; a CPU-only profile is refused; an unreadable
or empty profile is refused rather than read as "no evidence") and exercised against the real model by
`GpuModelIntegrationTest`, which reads the bound from the same constant.

## The local run verified on 2026-09-24

`./gradlew gpuIntegrationTest` on the machine below, with the model installed by `./gradlew embeddingModel`.

| | |
|---|---|
| Machine | Apple M1 Max, 24 cores, Metal 4 |
| OS | macOS 27.0 (build 26A428) |
| Platform string | `macos-aarch64` |
| ONNX Runtime | 1.22.0 (`com.microsoft.onnxruntime:onnxruntime`) |
| Model | `intfloat/multilingual-e5-base` @ `d128750597153bb5987e10b1c3493a34e5a4502a` |
| Model fingerprint | `5034403955dafca8f7aa509f0041ed02f7bbe75e7a539f05f6e2157be3241811` |
| Model on disk | 1,132 MB in `~/.infoscry/models/d128750597153bb5987e10b1c3493a34e5a4502a/` |

Measured, and written by the run to `build/gpu-validation/coreml-profile-summary.json`:

| Measurement | Value |
|---|---|
| First session creation, including CoreML's compilation of the MLProgram | 6,622 ms |
| Steady-state inference, one 8-token passage | **17 ms** |
| CoreML kernel events in the profile | 38 |
| CoreML kernel time | 856.2 ms (99.92% of measured kernel time) |
| CPU kernel events | 80 |
| CPU kernel time | 0.66 ms (0.08%) |
| Largest CoreML activation buffer reported | 129,080 bytes |

ONNX Runtime's own partition report, from the same run:

```text
CoreMLExecutionProvider::GetCapability, number of partitions supported by CoreML: 38
  number of nodes in the graph: 637
  number of nodes supported by CoreML: 557
```

So 557 of 637 nodes (87%) are CoreML's, and by measured kernel time CoreML carries 99.9%. What stays on the
CPU is exactly the category the requirement allows:

- `embeddings.word_embeddings.weight` (shape `{250002, 768}`) cannot go to CoreML, which rejects inputs with
  a dimension above 16,384 — the vocabulary lookup stays scalar, which is a lookup rather than compute;
- ONNX Runtime assigns shape and control operators to the CPU on purpose, and says so:
  `Some nodes were not assigned to the preferred execution providers ... ORT explicitly assigns shape
  related ops to CPU to improve perf`.

An earlier probe of the same model and provider with a smaller input measured the same split (152 CoreML
kernels at 99.7% of kernel time, 320 CPU kernels totalling 1.9 ms), which is what a 152-way partition of the
28-layer encoder looks like on an 8-token input.

### What these tests prove

`GpuModelIntegrationTest` (tags `model`, `gpu`; nine tests) asserts:

- the platform is ready and the session's profile contains CoreML kernel events carrying at least half the
  measured kernel time;
- 768-dimensional, finite, unit-length vectors for Swedish and English text;
- a Swedish and an English query each rank their paraphrase above an unrelated passage;
- a passage at the model's 512-token budget is embedded, and the next word measures **past** the budget and
  is refused with `EMBEDDING_INPUT_TOO_LONG` — truncation is off, so the overflow is visible rather than
  silently cut;
- a batch of two full-length passages is embedded;
- the chunker measures with the real tokenizer, every chunk fits 512 tokens including a repeated header,
  every chunk's offsets address the text it cites, and the walk covers the whole unit;
- the real tokenizer reports a body for every non-empty passage, which is the contract the chunker refuses
  to proceed without.

The tests fail — never skip — when the model files or the accelerator are missing. The default suite
excludes the `model` and `gpu` tags, so the offline suite proves the code paths and this run proves the
hardware.

## How to reproduce

```bash
./gradlew embeddingModel      # downloads and verifies 1.1 GB into ~/.infoscry/models
./gradlew gpuIntegrationTest  # the hardware gate; fails when the model or the GPU is absent
./gradlew check               # the offline suite, which excludes the model and gpu tags
./gradlew externalTest        # the real-Tesseract tests this machine can run
```

Use `-PdataDir=/path/to/dir` for both `embeddingModel` and `gpuIntegrationTest` to work against a data
directory other than `~/.infoscry`.

## Local acceptance check

The real CoreML/model test passed from the current local build on 2026-09-24.
A redistributable text fixture also imported into a disposable archive and
appeared in semantic and hybrid browser search. These checks validate this
Mac's embedding path, not Ask/Investigate provider behavior. No
packaged-runtime or CI check is planned.

## Known limits

- **Device memory is not observable.** The CoreML execution provider does not report device-memory use
  through the Java API, so the run records the largest activation buffer the provider reported
  (129,080 bytes) instead of claiming a GPU-memory figure.
- **Intra-op threading** is left at ONNX Runtime's default; the measured steady-state figure above is for a
  single 8-token passage on an idle machine.
- **The Neural Engine is not used** (see `CPUAndGPU` above). Enabling it would need a separate validation
  run that can show ANE execution, which no available trace format does on this platform.
- **Model weights are 1.11 GB** on disk in the data directory. They are never committed; the manifest pins
  their checksums and `ModelManager` verifies them.

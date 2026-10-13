# Persisting the index between sessions (research)

> **Status:** research, not implemented. This document records an analysis of the code as of commit
> `ecda0b190b6` (October 2026) and is meant as a starting point if the idea is revisited. Before
> implementing, check it against the current code: class and method names may have changed.

## Question

Can the workspace indexes be saved to disk so that the next start does not rebuild them, and would
that noticeably speed up startup?

## Conclusion

- Persistence is technically feasible, and there is a natural point for it (see the "Key observation" section).
- Persisting **only the reference index** (`ReferenceIndex`) is pointless: the main startup cost is
  parsing (ANTLR) every module, and a loaded reference index cannot answer a single query without
  the symbol tree. Parsing would have to happen anyway.
- A real gain comes only from a **snapshot of the document state after population**: the symbol
  tree, reference occurrences, entries of the other indexes that survive population, and frozen
  metrics. The snapshot key is a hash of the file text. Unchanged files are then not parsed at all.
- The `lsp`, `websocket` and `mcp` modes benefit. The `analyze` mode does not: diagnostics need the
  AST of every file.
- Before implementing, **measure** what share of startup time parsing takes
  (see the "Plan" section).

## How the workspace is populated today

`ServerContext.populateContext(Collection<File>)` walks the files in parallel and, for every new
document, under the write lock:

1. `createDocumentContext(uri)`;
2. `rebuildDocument(documentContext)` → `DocumentContext.rebuildFromFileSystem()` → `rebuild(content, 0)`:
   reads the file, computes a SHA-256 fingerprint of the content, tokenizes and parses
   (`BSLTokenizer`), builds the `SymbolTree` (`SymbolTreeComputer`);
3. after `rebuild`, `EventPublisherAspect` publishes `DocumentContextContentChangedEvent`, and its
   listeners run synchronously (see below);
4. `documentContext.freezeComputedData()` — metrics, complexity and diagnostic-ignorance data
   survive the clearing;
5. `tryClearDocument(documentContext)` → `clearSecondaryData()`: text, tokens, AST, queries, module
   type and diagnostics are dropped. `ServerContextDocumentClearedEvent` is published.

After population `ServerContextPopulatedEvent` is published. On it:

- `SymbolTypeIndex.handleServerContextPopulated` rebuilds declared return types for the whole
  workspace — **without parsing**, from symbol trees only;
- `MethodReturnTypeIndexer.handleServerContextPopulated` runs a cross-module fixed-point pass that
  resolves what stayed unresolved because of the parallel parse order. This pass **parses some
  documents again** (`withDocumentsLoaded`, `recomputeLoading` → `ServerContext.rebuildDocument` …
  `tryClearDocument`).

So the startup cost consists of:

- reading the configuration metadata (mdclasses) and walking the files for `MetadataFingerprint`;
- reading and **parsing every module**;
- the `DocumentContextContentChangedEvent` listeners;
- the post-population `MethodReturnTypeIndexer` pass, including the repeated parses.

## Key observation

`clearSecondaryData()` **does not touch the `SymbolTree`** and keeps the frozen metrics. So after
population every document not open in the editor is in the `WITHOUT_CONTENT` state: no AST, but the
symbol tree, metrics and the entries of the indexes that survived clearing are there. This is the
state worth saving and restoring: it is already proven sufficient for the server while the
document is not open.

## Index inventory

### `DocumentContextContentChangedEvent` listeners

| Listener | What it does | Order |
|---|---|---|
| `ConfigurationModuleMembersProvider` | registers configuration module members in `TypeRegistry`, fills `moduleTypeRefByUri` | 100 |
| `OScriptModuleMembersProvider` | the same for `.os`, resets subtype members | 100 |
| `ReferenceIndexFiller` | AST walk → occurrences in `ReferenceIndex` | 200 |
| `SymbolTypeIndex` | declared method and parameter types | 200 |
| `MethodReturnTypeIndexer` | return types inferred from method bodies, document dependency graph | 300 |
| `WorkspaceSymbolIndex` | symbol snapshots for `workspace/symbol` | — |
| `AnnotationReferenceFinder` | `.os` annotations in `AnnotationRepository` | — |
| `AbstractOScriptLazyIndex` (subclasses) | lazy OneScript indexes | — |
| `OScriptMetaAnnotationResolver` | resets the annotation role closure cache | — |
| `DocumentContextLazyDataMeasurer` | measurements (only with `app.measures.enabled`) | last |

In addition, the base `AbstractDocumentLifecycleClearableIndex` subscribes its subclasses to this
event and to the document cleared/closed/removed events: `ControlFlowGraphIndex`,
`AssignmentByReceiverIndex`, `CallStatementByReceiverIndex`, `EventContractsIndex`,
`InferredExpressionTypeIndex`, `PropertyMethodCallIndex`, `GuardConditionNarrowing`,
`VariableFlowAnalyzer`, plus `MethodReturnTypeIndexer` and `WorkspaceSymbolIndex`.

### What survives population and therefore needs persisting

Subclasses of `AbstractDocumentLifecycleClearableIndex` are cleared on
`ServerContextDocumentClearedEvent` by default, so they are empty after population. They are lazy
caches that refill when a document is opened, and **do not need** persisting. Only
`MethodReturnTypeIndexer` ("entries stay valid") and `WorkspaceSymbolIndex` (no-op) override the
clearing.

| Data | Where it lives | Serializability | Depends on |
|---|---|---|---|
| Reference occurrences | `SymbolOccurrenceRepository` (symbol → sorted array), `LocationRepository` (URI → array) | Easy: `Symbol` is a record `(mdoRef, moduleType, scopeName, symbolKind, symbolName)`; an occurrence is a type, a symbol, a URI and 4 coordinates | File text; metadata (`findCommonModule`, `getConfiguration`); the `references.commonModuleAccessors` setting; `OScriptLibraryIndex`; the module self type from `TypeRegistry`. **Other modules' text does not matter** |
| Symbol tree | `DocumentContext.symbolTree` | Medium: a tree with `parent`/`children` links and `owner = DocumentContext`. Symbols hold no AST; descriptions (`MethodDescription`, `VariableDescription`, `ParameterDescription` from `bsl-parser`) are plain data: strings, ranges, lists, no ANTLR tokens | File text; `SelfMemberClassifier` and `EventHandlerClassifier` (metadata and types) |
| Metrics, complexity, diagnostic ignorance | `DocumentContext` (frozen `Lazy`) | Easy | File text |
| `WorkspaceSymbolIndex` | self-contained snapshots (name, kind, range, tags, container) | Easy | Symbol tree |
| `SymbolTypeIndex` | keyed by `MethodSymbol` objects | Optional: rebuilt from symbol trees without parsing on `ServerContextPopulatedEvent` | Symbol trees, `TypeRegistry` |
| `MethodReturnTypeIndexer` | `MethodSymbol` → types; `methodsByUri`, `dependentsByUri`, `dependenciesByUri`; bookkeeping `pending`/`provisional`/`indexed` | Medium: `TypeRef` is a record `(TypeKind, qualifiedName)`, stored by name | **Cross-module**: a method's value depends on methods called in other documents |
| Module members | `TypeRegistry` (via `ConfigurationModuleMembersProvider`, `OScriptModuleMembersProvider`) | Medium | Symbol tree, metadata |
| `.os` annotations | `AnnotationRepository` | Easy | File text |
| OneScript indexes | `OScriptLibraryIndex`, `AbstractOScriptLazyIndex` subclasses | Needs separate analysis | `.os` texts, `lib.config`, library directory layout |

## Why `ReferenceIndex` alone is not enough

1. `ReferenceIndex.buildReference` calls `getSymbolTree()` for every occurrence:
   - on the target document (`getSourceDefinedSymbol` → `serverContext.getDocument(mdoRef, moduleType)`);
   - on the source document (`getFromSymbol` → `getDocumentNoLock(uri)` … `orElseThrow()`).
   
   Without symbol trees the index cannot build a single `Reference`.
2. `ReferenceIndexFiller.fill()` is just a walk over an already built AST. Skipping it still leaves
   parsing and the other listeners, so only a small share of the time is saved.

## Proposed design

### Invalidation keys

- **Document:** SHA-256 of the text. It is already computed in `DocumentContext.contentFingerprint`
  on every `rebuild`. Comparing `mtime`/size is cheaper but unreliable (see the `MetadataFingerprint`
  limitation); reading a file is cheap compared with parsing it anyway.
- **Whole workspace** (on mismatch the whole snapshot is discarded):
  - the metadata fingerprint (`MetadataFingerprint.of(root)`) — with its known `mtime`/size
    limitation;
  - a hash of the `LanguageServerConfiguration` settings that affect indexing (at least
    `references.commonModuleAccessors`, excluded paths, language);
  - the set and fingerprint of OneScript libraries;
  - the BSL LS version and the snapshot format version.

### Load flow

1. Read the snapshot header and check the workspace keys. Mismatch → regular population.
2. In `populateContext`, for each file: read the text and compute SHA-256.
   - Hash matches → **restore** the document into the `WITHOUT_CONTENT` state: symbol tree (owned
     by the new `DocumentContext`), frozen metrics, index entries.
   - Hash differs or the document is not in the snapshot → regular `rebuildDocument`.
3. Restoring **must not** publish `DocumentContextContentChangedEvent`: the listeners expect an
   AST, and `ReferenceIndexFiller.fill()` fails with `DocumentContentUnavailableException` on
   `getAst()`. A separate path is needed, for example:
   - a `PersistableDocumentIndex` interface with methods like `export(uri)` / `restore(uri, data)`, or
   - a dedicated document-restored event that the persisted indexes subscribe to.
4. After population, the usual `ServerContextPopulatedEvent`. `SymbolTypeIndex` rebuilds declared
   types from symbol trees (cheap). For `MethodReturnTypeIndexer`, see below.

### Cross-module inferred types

A `MethodReturnTypeIndexer` value depends on methods in other documents, so it can only be restored
together with invalidation:

- changed documents and their transitive dependents (via `dependentsByUri`) are marked as changed;
- then the existing `propagate` / post-population pass does the rest.

In a first iteration inferred types can be left unpersisted, but then the post-population pass
parses documents again and part of the gain is lost.

### Format and location

A **custom binary file per workspace** is recommended rather than the existing EhCache:

- a string table (mdoRefs, names, URIs) plus per-document sections keyed by URI and text SHA-256;
- write to a temporary file and rename atomically;
- write after population and then, debounced and asynchronously, after changes. Relying on process
  shutdown alone is not enough: the client may kill the server.

Why not EhCache (`infrastructure/CacheConfiguration`):

- a shared disk limit of `DISK_SIZE_MB = 50` for all caches;
- `CachePathProvider` derives the cache directory from the md5 of the **process working
  directory**, not the workspace: with several workspaces the snapshots would mix;
- complex values need Java serialization or custom `Serializer`s;
- the key → value model is awkward for a snapshot that is restored as a whole.

The snapshot for a large configuration would be on the order of tens of megabytes. This is an
estimate from the number of occurrences (millions on large projects, a few bytes each with dense
encoding), not a measurement.

## Risks and limitations

- **Maintenance is the main risk.** Every new index that is filled per document and survives
  document clearing must be part of the snapshot. Otherwise its data is simply missing after a
  restore, with no error at all. Two tests are needed:
  - every bean that listens to `DocumentContextContentChangedEvent` and survives
    `ServerContextDocumentClearedEvent` supports persistence or is explicitly allow-listed;
  - equivalence: indexes and LSP answers after a restore match those built from scratch on test
    fixtures.
- **Symbol identity.** `AbstractMethodSymbol.equals` includes `owner` (`DocumentContext`), so
  symbols are restored with the new owner. Indexes keyed by `MethodSymbol` can be filled only after
  the trees are restored.
- **Restore order.** Listeners are ordered with `@Order` today (module member registration →
  reference index and declared types → inferred types). Restoring must keep the same order: for
  example, the self-member pass of the reference index depends on `moduleTypeRefByUri`.
  `TypeRegistry` (configuration types) must be populated before restoring.
- **`analyze` does not get faster**: diagnostics need the AST of every file. Caching diagnostic
  results is a separate and risky task, since diagnostics depend on other documents.
- **Metadata does not get faster.** Reading the mdclasses configuration and walking the files for
  `MetadataFingerprint` (about 1.4 s for the walk alone on the SSL 3.1.12 export) remain.
- **Several server instances** on one workspace: the snapshot file needs a lock, or `@N`-suffixed
  directories as EhCache does.
- **The snapshot is only an optimization.** Any read error or incompatible format → full
  population from scratch, without failing the server.
- **OneScript.** Library indexes depend on the directory layout and `lib.config`. In the early
  stages `.os` files should simply always be parsed.

## Plan

1. **Measure.** Break `populateContext` down by phase on a large configuration (SSL, ERP): metadata,
   file reading, parsing, `ReferenceIndexFiller.fill`, other listeners, the post-population
   `MethodReturnTypeIndexer` pass. JFR or `MeasureCollector.measureIt` around the phases will do; the
   current `DocumentContextLazyDataMeasurer` (see [Measures](Measures.md)) does not measure parsing
   itself. If parsing and listeners take less than roughly half of the startup time, stop here.
2. **Infrastructure and core:** snapshot format, invalidation keys, the restore path in
   `populateContext`, `SymbolTree`, frozen metrics, `ReferenceIndex`, `WorkspaceSymbolIndex`,
   configuration module member registration. BSL only; `.os` files are parsed.
   Plus the equivalence and completeness tests.
3. **Inferred return types** with invalidation by dependent documents.
4. **OneScript:** `AnnotationRepository`, `OScriptModuleMembersProvider`, library indexes.

## Key places in the code

- `context/ServerContext` — `populateContext`, `rebuildDocument`, `tryClearDocument`,
  `refreshConfigurationIfStale`;
- `context/DocumentContext` — `rebuild`, `contentFingerprint`, `clearSecondaryData`,
  `freezeComputedData`;
- `context/MetadataFingerprint` — metadata file fingerprint;
- `context/symbol/` — `SymbolTree`, `AbstractMethodSymbol`, `AbstractVariableSymbol`;
- `references/` — `ReferenceIndex`, `ReferenceIndexFiller`, `model/SymbolOccurrenceRepository`,
  `model/LocationRepository`, `model/AnnotationRepository`;
- `types/index/` — `SymbolTypeIndex`, `MethodReturnTypeIndexer`, `WorkspaceSymbolIndex`;
- `types/registry/` — `ConfigurationModuleMembersProvider`, `TypeRegistry`;
- `index/AbstractDocumentLifecycleClearableIndex` — base index clearing on document events;
- `infrastructure/` — `CacheConfiguration`, `CachePathProvider` (the existing on-disk EhCache).

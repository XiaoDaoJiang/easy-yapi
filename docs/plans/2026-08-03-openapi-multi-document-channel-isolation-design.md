# OpenAPI Multi-Document Channel Isolation Design

## Status

Approved on 2026-08-03.

This document supersedes the channel-integration decision in
`2026-07-31-openapi-multi-document-controller-paths-design.md`. The previously
approved output structure, path ownership, reference layout, and conservative
file-writing behavior remain valid. The multi-document feature now lives in a
separate export channel instead of adding a mode to the original OpenAPI
channel.

## Goals

- Keep the original single-file `openapi` channel behavior and source files
  unchanged so upstream changes can be rebased with minimal conflicts.
- Add an independently enabled multi-document OpenAPI export channel.
- Continue using the existing OpenAPI formatter, rules, format-after hook, and
  output-format settings exactly once per export.
- Write one Paths document per Controller and keep all schemas in one shared
  schema document.
- Improve schema names for humans and LLM code search without adding Java
  source indexes to the exported schema document.

## Non-goals

The first isolated-channel implementation does not add:

- another formatter or a fork of the existing formatting pipeline;
- new Channel SPI abstractions, factories, or grouping strategy interfaces;
- folder, tag, package, URL-prefix, or user-defined grouping strategies;
- schema files grouped by package or Java type;
- a second OpenAPI settings page or a new project configuration file;
- `x-java-type` or another schema-to-source index;
- automatic cleanup of unrelated or stale files in the selected directory;
- OpenAPI 3.1 output.

## Channel surface

Register a second channel with these properties:

```text
id: openapi-multi
displayName: OpenAPI Multi-Document (Beta)
enabledByDefault: false
exposeAsAction: true
actionText: Export to OpenAPI Multi-Document
```

The existing Channel registry makes the new channel available in both the
general export dialog and an independent Action. No new Action class is
needed.

The channel reuses `OpenApiOptionsPanel`, `OpenApiConfig`, and the persistent
`OpenApiSettings` output-format value. It does not contribute another settings
panel or settings type, so Settings continues to show only the original
OpenAPI configuration.

## Isolation boundary

The new channel treats `OpenApiChannel` as a black-box producer:

```text
ApiEndpoint list
  -> build endpoint ownership and response-type index
  -> validate normalized path ownership
  -> OpenApiChannel.export(context)
  -> read OpenApiExportMetadata.document and outputFormat
  -> semantic schema rename
  -> Controller Paths split and external $ref rewrite
  -> serialize and write the multi-document directory
```

`OpenApiChannel.export` continues to perform HTTP filtering, output-format
resolution, rule-based document metadata, formatting, and the
`openapi.format.after` hook. The new channel calls it once and does not invoke
the original channel's `handleResult`, because that handler writes a single
file. `Error` and cancellation outcomes pass through without transformation.

The new channel owns its output DTOs and serializer because the original
`PathItemObject` intentionally does not model an external Path Item `$ref`.
This avoids changing `OpenApiDocument`, `PathItemObject`, or
`OpenApiSerializer`. The new serializer uses the already installed Gson and
Jackson YAML dependencies with the same formatting settings as the original
channel.

Production code is limited to the existing `channel/openapi` feature bucket:

```text
channel/openapi/multidocument/
  OpenApiMultiDocumentChannel.kt
  OpenApiMultiDocumentTransformer.kt
  OpenApiSemanticSchemaNamer.kt
```

No new interface is introduced. Small metadata and wire DTOs stay next to the
class that uses them instead of becoming separate files.

## Endpoint index

Before delegation, build one immutable index keyed by normalized OpenAPI path
and HTTP method. Each entry records data already present on `ApiEndpoint`:

- Controller class name;
- source method identity when available;
- folder fallback;
- qualified response type, including generic arguments.

No PSI rescan is required. Building the index before the format-after hook is
important because the hook may rename or add paths that no longer map to an
original endpoint.

A normalized path may be owned by only one output document. GET and POST for
the same path are valid when they belong to the same Controller. Operations
for the same normalized path owned by different Controllers abort export
before formatting and before any file is written.

## Output structure

YAML output uses this layout; JSON uses the same layout with `.json`
extensions:

```text
<selected directory>/
  openapi.yaml
  paths/
    UserController.yaml
    OrderController.yaml
    Unresolved.yaml
  schemas/
    schemas.yaml
```

The entry document enumerates every path and references the Path Item inside
its owner document:

```yaml
paths:
  /users:
    $ref: "./paths/UserController.yaml#/paths/~1users"
  /users/{id}:
    $ref: "./paths/UserController.yaml#/paths/~1users~1{id}"
```

JSON Pointer escaping follows RFC 6901: `~` becomes `~0` and `/` becomes
`~1`. URI path segments are encoded separately from JSON Pointer tokens.

Each fragment retains the diagnostic vendor extension appropriate to its
owner:

```yaml
x-java-controller: com.acme.user.controller.UserController
paths: {}
```

```yaml
x-easyapi-folder: User Management
paths: {}
```

```yaml
x-easyapi-unresolved: true
paths: {}
```

These three extensions belong only to the multi-document channel. They are
not original OpenAPI channel metadata.

Controller ownership wins, folder is the fallback, and unresolved is the
last resort. Paths introduced or renamed by `openapi.format.after` without an
endpoint owner are written to `Unresolved` and reported as warnings.

## Semantic schema naming

Schema renaming runs only on the in-memory document returned to the new
channel. The original single-file export retains its existing names and
collision behavior.

Top-level response names come from the indexed qualified response type. The
wire-safe semantic name removes package qualifiers and represents generic
structure with underscores:

```text
BaseResponse<List<SelfAssessmentTemplateVO>>
  -> BaseResponse_List_SelfAssessmentTemplateVO
```

Already meaningful and unique DTO component names remain unchanged.
Anonymous `GeneratedSchemaN` components receive a deterministic name from the
root response type and the property traversal path:

```text
SelfAssessmentTaskVO.questions[]
  -> SelfAssessmentTaskVO_questions_Item
```

When several candidate paths reach the same anonymous component, choose the
lexicographically first semantic path so endpoint iteration order cannot
change the result.

A short hash is added only when one semantic name identifies different wire
structures:

```text
BaseResponse_UserVO
BaseResponse_UserVO__7f3a9c2d
```

The eight-hex-character hash is derived from a canonical schema structure.
Map keys are sorted, cycles use a stable cycle marker, and descriptive fields
such as `description` and `example` are excluded. Documentation-only changes
therefore do not rename components.

If one old component is used by operations with different qualified response
types, the transformer clones it per semantic type and rewrites each
operation's reference. All references in the root document, Paths fragments,
and schema graph are rewritten after final names are allocated.

When a reliable semantic mapping cannot be established, preserve the old
component name and emit a warning. A wrong semantic name is worse than a
visible legacy name.

`schemas.yaml` contains only standard OpenAPI components. It does not emit
`x-java-type`, method lists, or another source index. This keeps the document
compact, with the accepted trade-off that equal Java simple names from
different packages may require code search plus the stable hash to
disambiguate.

## File writing and failure behavior

The new channel owns directory selection and multi-file result handling.
Before writing it:

1. resolves every target under the selected root;
2. rejects absolute, escaping, symlink-escaping, and duplicate targets;
3. serializes all documents in memory;
4. asks once before overwriting existing target files;
5. writes each file through a temporary sibling followed by replacement.

The channel does not delete other files in the selected directory. Old files
that are no longer referenced by the newly written root document remain on
disk but are not part of that OpenAPI document. Automatic cleanup is deferred
because the plugin cannot prove that an unreferenced file belongs to it.

Concurrent exports to the same canonical directory are serialized within the
IDE process. A failure is logged with its target and throwable and is reported
as a failed user operation; it must not produce a success notification.

## Migration from the current branch

Restore the pre-multi-document versions of these original production files:

- `OpenApiChannel.kt`
- `OpenApiConfig.kt`
- `OpenApiDocument.kt`
- `OpenApiExportMetadata.kt`
- `OpenApiOptionsPanel.kt`
- `OpenApiSerializer.kt`

Restore their existing tests to the same baseline. Move the still-valid split,
reference, filename, and writer behavior into the new channel package instead
of retaining conditional multi-file branches in the original classes.

The only shared registration change is one `plugin.xml` channel entry. README
documentation describes the two channels as separate export choices.

## Verification

Before implementation tests are written, invoke the repository's
`write-test-case` skill. Verification must cover:

1. The original OpenAPI test suite passes without multi-document assertions or
   changed single-file snapshots.
2. Transformer tests cover Controller/folder/unresolved ownership, path
   conflicts, URI and JSON Pointer encoding, semantic names, stable collision
   hashes, anonymous field paths, reference rewriting, and legacy-name
   fallback.
3. Channel tests prove the original export is invoked once, cancellation and
   errors pass through, existing outputs are confirmed once, unsafe targets
   are rejected, and write failures do not report success.
4. `./gradlew test` and `./gradlew buildPlugin` pass before distribution.

## Upstream workflow

Configure the canonical repository as a second remote:

```text
upstream = https://github.com/tangcent/easy-yapi.git
```

Keep `origin` pointed at `XiaoDaoJiang/easy-yapi`. Synchronize with
`git fetch upstream` followed by a rebase of the feature branch onto the
chosen upstream branch. Since the feature adds new channel-local files and
one registration line, routine upstream changes to the original OpenAPI
channel should apply without feature-code conflicts.

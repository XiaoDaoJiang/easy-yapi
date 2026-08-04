# OpenAPI Controller-Grouped Multi-Document Export Design

## Goal

Keep the existing single-file OpenAPI 3.0.3 export unchanged and add an
optional multi-document mode that writes one Paths fragment per Controller.

The entry document continues to enumerate every OpenAPI path and references
the matching Path Item inside its Controller file. Schemas remain centralized
in one file for the first phase.

## Non-goals

The first phase does not add:

- folder, tag, package-regex, or URL-prefix path grouping strategies;
- schema grouping by package, annotation, regex, or rule;
- a new export channel or extension-point SPI;
- OpenAPI 3.1 output;
- a new project configuration file;
- a `$ref` directly under the root `paths` object.

## User flow

The existing OpenAPI options panel adds a document-mode choice:

```text
Document:
  Single file
  Multiple files by Controller
```

Single file remains the default. The existing JSON/YAML choice remains
available in both modes, and every file in one export uses the selected
format.

Single-file mode retains the current save-file dialog and output behavior.
Multi-document mode opens a folder chooser. The selected folder is the
OpenAPI root directory; the plugin does not create another `openapi`
subdirectory inside it.

## Output structure

For YAML:

```text
<selected directory>/
├── openapi.yaml
├── paths/
│   ├── UserController.yaml
│   ├── patient-OrderController.yaml
│   └── Unresolved.yaml
└── schemas/
    └── schemas.yaml
```

JSON mode uses the same layout with `.json` extensions.

The `schemas` directory and file are omitted when the formatted document has
no component schemas.

## Reference layout

OpenAPI 3.0.3 does not allow a `$ref` directly under the root `paths` object.
The entry document therefore lists every path and references the matching
Path Item inside a Controller fragment:

```yaml
paths:
  /users:
    $ref: "./paths/UserController.yaml#/paths/~1users"
  /users/{id}:
    $ref: "./paths/UserController.yaml#/paths/~1users~1{id}"
```

The Controller fragment preserves the actual paths and records the source
class:

```yaml
x-java-controller: com.acme.user.controller.UserController
paths:
  /users:
    get:
      operationId: listUsers
  /users/{id}:
    get:
      operationId: getUser
```

JSON Pointer escaping follows the standard rules: `~` becomes `~0` and `/`
becomes `~1`.

## Selected architecture

Generate the complete OpenAPI document once, execute the existing
`openapi.format.after` hook once, and split only after the hook finishes:

```text
ApiEndpoint list
  → resolve normalized-path ownership
  → validate ownership conflicts
  → OpenApiFormatter
  → openapi.format.after
  → OpenApiMultiDocumentSplitter
  → serialize every output in memory
  → confirm overwrite once
  → write files
```

This keeps `OpenApiFormatter` unchanged and preserves global operation ID
deduplication, schema collision handling, tag ordering, and the existing rule
hook semantics.

Formatting each Controller separately is rejected because it would duplicate
schemas, weaken global operation ID uniqueness, repeat formatting work, and
make the document-level rule hook ambiguous.

A second OpenAPI channel is also rejected because it would duplicate settings,
format selection, notifications, and future maintenance.

## Ownership resolution

Use the already-populated `ApiEndpoint.className` rather than reading PSI
during splitting.

Ownership is resolved in this order:

1. Non-blank `className` → Controller owner.
2. Missing `className` with non-blank `folder` → folder fallback owner.
3. Missing `className` and `folder` → unresolved owner.

The emitted fragment identifies the fallback:

```yaml
x-easyapi-folder: 用户管理
paths:
  /users:
    get: {}
```

```yaml
x-easyapi-unresolved: true
paths:
  /generated:
    get: {}
```

Paths added or renamed by `openapi.format.after` that have no original
endpoint ownership are placed in the unresolved fragment and reported as a
warning.

## Path conflict rule

A normalized OpenAPI path can appear only once in the entry document and can
reference only one Path Item. Multi-document export therefore requires every
operation on a normalized path to resolve to the same output owner.

These endpoints are valid because they share one Controller:

```text
GET    /users/{id}  com.acme.UserController
DELETE /users/{id}  com.acme.UserController
```

These endpoints abort multi-document export before any file is written:

```text
GET  /users/{id}  com.acme.UserController
POST /users/{id}  com.acme.AdminController
```

The error lists the normalized path, HTTP methods, and Controller names.
Single-file mode keeps its current behavior and continues merging different
methods into one Path Item.

The same validation applies when fallback ownership would place operations
for one normalized path into different files.

## File naming

Controller fragments normally use the simple class name:

```text
com.acme.user.UserController → UserController.yaml
```

When simple names collide, prepend the shortest package suffix that makes
every name unique:

```text
com.acme.patient.UserController → patient-UserController.yaml
com.acme.admin.UserController   → admin-UserController.yaml
```

Add more package segments only while needed. Any collision remaining after
filename sanitization receives a stable short suffix derived from the owner
key.

Folder fallback files use the sanitized folder name. Endpoints with neither
Controller nor folder go to `Unresolved.yaml` or `Unresolved.json`.

Generated names are sanitized for the current filesystem, including Windows
reserved characters and trailing dots or spaces.

## Schema extraction and reference rewriting

The complete formatter output initially uses internal references:

```yaml
$ref: "#/components/schemas/UserVO"
```

The splitter writes all schemas under the standard wrapper:

```yaml
components:
  schemas:
    UserVO:
      type: object
```

References are rewritten by document location:

```text
entry document:
  ./schemas/schemas.yaml#/components/schemas/UserVO

Controller/fallback fragment:
  ../schemas/schemas.yaml#/components/schemas/UserVO

schemas.yaml internal reference:
  #/components/schemas/UserVO
```

The entry document retains its info, servers, and tags. Its `paths` values
become external Path Item references. Its component schema entries become
external Schema references.

## Components

Add one channel-local pure splitter:

```text
channel/openapi/OpenApiMultiDocumentSplitter.kt
```

It accepts the selected endpoints and the fully formatted document and returns
ordered relative output paths with serializable content objects. It performs
no PSI access, UI work, or filesystem I/O.

Existing channel-local types receive only the fields required by this mode:

- `OpenApiConfig` gains a document mode with single-file default.
- `OpenApiOptionsPanel` gains the mode controls.
- `PathItemObject` gains the OpenAPI `$ref` field.
- `OpenApiSerializer` can serialize the entry document and fragment objects
  using the existing Gson and YAMLMapper instances.
- `OpenApiExportMetadata` carries the root content plus additional files.
- `OpenApiChannel` selects the single or multi branch and writes the result.

No shared core model or framework exporter changes are required.

## Overwrite and write behavior

All documents are split and serialized before any filesystem write.

If any target file already exists, show one confirmation that reports the
number of files that will be overwritten. On confirmation:

- overwrite only files generated by the current export;
- do not delete unrelated or stale files in the selected directory;
- write each file to a temporary sibling and then replace its target, avoiding
  partially written YAML or JSON files.

Cancelling the confirmation writes nothing. A filesystem failure reports the
failed target. Files already replaced during the operation are not rolled
back, because replacing the selected directory transactionally would risk
unrelated user files.

The success notification reports endpoint, Paths-fragment, schema, and
unresolved-path counts.

## Error and warning behavior

Abort before writing when:

- a normalized path resolves to multiple output owners;
- fragment creation or reference rewriting fails;
- any output object cannot be serialized;
- the selected directory cannot be created or used.

Warn but continue when:

- an endpoint falls back from Controller to folder ownership;
- an endpoint is written to `Unresolved`;
- `openapi.format.after` produces a path with no original owner.

Single-file errors and notifications remain unchanged.

## Performance

The formatter still runs once. Ownership indexing and document splitting are
linear in the number of endpoints, paths, and schemas. No additional project
scan, PSI lookup, or per-Controller formatting pass is introduced.

## Verification

Before writing tests, invoke the repository `write-test-case` skill.

Pure splitter tests cover:

- two Controllers producing two fragments;
- one Controller with multiple paths and repeated references to one file;
- JSON Pointer escaping for `/` and `~`;
- Controller-fragment and schema-fragment reference rebasing;
- cross-Controller normalized-path rejection;
- folder and unresolved ownership fallbacks;
- shortest unique package-prefix filenames;
- paths added by `openapi.format.after` falling back to unresolved output.

Serializer and channel tests cover:

- equivalent JSON and YAML multi-document structures;
- standard `components.schemas` wrapping;
- one overwrite confirmation for existing targets;
- no write after cancellation or pre-write validation failure;
- unchanged single-file serialization and handling.

# Payload offloader wire formats

## Status and scope

This document defines version 1 of the Java SDK payload-offloader formats. Other SDKs can implement the same contract
when exchanging offloaded chained-invoke payloads or results.

Three independent formats are involved:

1. a chained-invoke request frame selects the target handler's persisted SerDes-plus-offloader path;
2. a chained-invoke output frame distinguishes SDK codec data from ordinary Lambda result/error data; and
3. an SDK payload envelope stores inline data or an external reference.

## Chained-invoke source frame

Version 1 has two exact forms:

```text
__durable_execution_chained_invoke_payload:1:value:<opaque-payload>
__durable_execution_chained_invoke_payload:1:null
```

The `value:` form appends the opaque SDK payload without additional encoding. The `null` form represents a null request
while preserving the protocol handshake.

- Producers MUST use the frame only when `InvokeConfig.usePayloadOffloaderForPayload(true)` is enabled.
- Consumers MUST interpret the frame only when
  `DurableConfig.withPayloadOffloaderForChainedInvokePayloads(true)` is enabled.
- Acceptance MUST be disabled by default.
- A recognized unsupported or malformed frame MUST fail closed.
- Without both opt-ins, invoke requests retain the ordinary Lambda serialized-input contract.

The frame is application-controlled data, not authenticated service metadata. Enable target acceptance only when every
principal allowed to invoke the function may select the persisted payload path.

## Chained-invoke output frame

A compatible durable target returns non-null results and error data in one of these exact version 1 forms:

```text
__durable_execution_chained_invoke_output:1:codec:<payload>
__durable_execution_chained_invoke_output:1:raw:<payload>
```

- `codec:` means the payload is an SDK payload envelope and the caller MUST resolve it through the payload codec.
- `raw:` means the payload is ordinary serialized data and the caller MUST pass it directly to SerDes.
- Null results or error data remain null and are not framed.
- Callers MUST interpret output frames only for invokes that enabled
  `InvokeConfig.usePayloadOffloaderForPayload(true)`.
- A recognized unsupported or malformed output frame MUST fail closed.

## SDK payload envelope

The envelope is the concatenation of:

```text
@aws-durable-payload:v1:<compact JSON>
```

The JSON representation uses these fields:

| Field | Meaning |
|---|---|
| `mode` | `INLINE` or `REFERENCE` |
| `data` | Serialized payload for inline mode |
| `reference` | External storage reference for reference mode |
| `preview` | Optional informational preview metadata |
| `ownerDurableExecutionArn` | Producing durable execution |
| `ownerEntityId` | Producing payload entity |
| `payloadDigest` | Lowercase SHA-256 digest of the exact UTF-8 serialized payload |
| `producerContext` | Exact SDK payload context used to create the reference |
| `requiresLoad` | Whether the producer's payload offloader must restore the serialized data |

Exactly one of `data` and `reference` is non-null. Version 1 SDK envelopes bind all custom-offloader results to
`producerContext`, populate the ownership and digest fields, and require the nested context to match them. A recognized
version 1 envelope missing this metadata MUST fail closed. Custom-offloader results set `requiresLoad` to `true`,
including `INLINE` results whose data may use an offloader-specific encoding. SDK-created inline envelopes that only
escape the reserved marker set it to `false`; consumers MUST use their inline `data` directly and MUST NOT pass it to a
configured offloader. Every version 1 envelope MUST include a boolean `requiresLoad` value; a missing or non-boolean
value is invalid. A `REFERENCE` envelope with `requiresLoad` set to `false` is also invalid.

Legacy checkpoint strings without the versioned prefix are passed directly to the configured SerDes.

## Ownership and cross-execution boundaries

For ordinary checkpoint replay, `ownerDurableExecutionArn` and `ownerEntityId` MUST match the current payload context.
A different owner is accepted only where values legitimately cross executions:

- the input of an invoked durable execution; or
- the result of a chained invoke operation.

The declared producer remains authoritative for resolving the filesystem path. Consumers MUST NOT rewrite ownership to
the receiving execution. The SDK passes `producerContext`, rather than the consuming operation context, to
`PayloadOffloader.load` so context-keyed custom storage remains usable across executions.

## Filesystem payloads

The filesystem offloader stores the exact UTF-8 bytes produced by SerDes.

### Path construction

The configured base path and all of its ancestors MUST already exist. Payload files are direct children of that base
path. The owner prefix binds the producing execution and entity:

- `URI` mode uses a bounded escaped entity prefix followed by the lowercase SHA-256 digest of
  `ownerDurableExecutionArn + NUL + ownerEntityId`.
- `HASH` mode uses only that fixed-length SHA-256 owner digest.

Payload filenames use:

```text
<owner-prefix>-<payload-digest>-<unique-suffix>.payload
```

The unique suffix has no payload semantics. Each serialization publishes a new immutable file using `CREATE_NEW`; an
existing payload file is never replaced.

### Validation

Consumers MUST:

- normalize the reference as a direct child of the configured base path;
- derive the expected filename prefix from the declared producer and payload digest;
- read without following symbolic links;
- keep secure directory handles open through file access; and
- verify the restored bytes against `payloadDigest`.

The Java implementation traverses to the pre-provisioned base path through `SecureDirectoryStream` handles with
symbolic-link following disabled, then keeps the base-directory handle open through file access. This avoids pathname
races and temporary-file rename requirements while remaining compatible with EFS and S3 Files providers that support
normal `CREATE_NEW` writes.

The external file reference is a capability, not an authentication credential. Producers and consumers MUST restrict
access to the shared base path and protect references with the same controls as the payload.

### Structured previews

`preview` is informational and is not used to restore the payload. The built-in preview builder supports:

- include-all and exclude-all defaults;
- include, exclude, and mask selectors;
- exact path and field-name-at-any-depth matching;
- configurable mask text; and
- a serialized UTF-8 byte budget.

Exact path selectors use dots as segment separators. Escape a literal dot as `\.` and a literal backslash as `\\`.
Field-name-at-any-depth selectors always treat their names literally. Preview metadata is snapshotted when the envelope
is created; arrays are normalized to immutable lists, and custom preview generators must return JSON-compatible values.

The complete reference envelope, including preview data, must remain within the configured checkpoint-envelope limit.

## Versioning

The chained-invoke frame version and SDK payload-envelope version are independent. Incompatible changes require a new
version. Producers MUST NOT emit a version until the intended consumer supports it, and consumers MUST fail closed for
recognized unsupported versions.

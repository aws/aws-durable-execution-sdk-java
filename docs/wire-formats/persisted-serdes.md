# Persisted SerDes wire formats

## Status and scope

This document defines version 1 of the wire formats used to exchange persisted SerDes values between durable
executions. The Java SDK implements these formats. Other SDKs can implement the same contract to exchange persisted
payloads with Java and with each other.

There are two independent, composable formats:

1. The chained-invoke source frame tells an invoked durable handler to deserialize an input with its persisted SerDes
   pipeline instead of its ordinary external-input codec.
2. The filesystem envelope represents the output of `FileSystemSerDesStage`, either inline or as a reference to a
   payload on a shared durable filesystem.

A chained-invoke source frame can contain any persisted pipeline output. When the pipeline contains
`FileSystemSerDesStage`, the source frame contains a filesystem envelope:

```text
chained-invoke source frame
└── persisted SerDes output
    └── filesystem envelope, when that stage handled the value
```

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, and **MAY** in this document are to be
interpreted as normative requirements.

## Chained-invoke source frame

### Purpose

Normal Lambda invocation input is decoded with a context-free external-input codec. A persisted SerDes pipeline can
require durable execution context and can produce a value, such as a filesystem reference, that is not valid input for
that codec.

The source frame explicitly identifies an invocation payload as persisted pipeline output. It is application-controlled
payload data, not authenticated metadata from the Lambda Durable Functions service.

### Version 1 encoding

Version 1 is the following concatenation:

```text
__durable_execution_chained_invoke_payload:1:<persisted-payload>
```

The exact version 1 prefix is:

```text
__durable_execution_chained_invoke_payload:1:
```

The prefix and payload are Unicode strings transported in the chained invoke operation's `payload` field. A producer:

1. MUST serialize the user value with the selected persisted SerDes pipeline.
2. MUST preserve the serialized string exactly.
3. MUST prepend the exact version 1 prefix without JSON encoding, escaping, Base64 encoding, or another delimiter.
4. MUST send the resulting string as the chained invoke payload.

The remainder after the prefix is opaque. It MAY be empty and MAY contain colons, newlines, JSON, or either reserved
marker defined by this document. Consumers MUST remove only the first exact prefix.

If serialization returns `null`, the version 1 Java implementation sends `null` without a frame. A portable persisted
pipeline SHOULD encode a logical null as a non-null serialized string when selecting the persisted pipeline at the
callee is required.

Example with plain JSON persisted output:

```text
__durable_execution_chained_invoke_payload:1:{"orderId":"123"}
```

### Reserved namespace

The following marker reserves the chained-invoke source-frame namespace:

```text
__durable_execution_chained_invoke_payload:
```

Characters between that marker and the next colon identify the version. Version 1 consumers MUST accept only the exact
version 1 prefix. A payload that starts with the reserved marker but does not start with the exact supported prefix is
an unsupported or malformed frame.

Future versions MUST use a new version token after the reserved marker. They MUST NOT change the meaning of version 1.

### Consumer algorithm

Acceptance of framed inputs MUST be disabled by default. Given an execution input string and a callee setting that
controls persisted chained-invoke input:

1. If acceptance is disabled, the consumer MUST NOT interpret the reserved marker. It MUST pass the complete input to
   the ordinary external-input codec.
2. If acceptance is enabled and the input does not start with the reserved marker, the consumer MUST pass the complete
   input to the ordinary external-input codec.
3. If acceptance is enabled and the input starts with the exact version 1 prefix, the consumer MUST remove that prefix
   and pass the opaque remainder to its persisted SerDes pipeline.
4. If acceptance is enabled and the input starts with the reserved marker but is not a supported, well-formed frame,
   the consumer MUST fail deserialization. It MUST NOT fall back to the external-input codec.

The persisted pipeline receives the invoked execution's input context. Context-dependent stages MAY use metadata
inside their own envelope to identify the producing execution and entity.

### Security requirements

The source frame does not prove that another SDK produced the payload. Any principal that can invoke the function can
supply it.

- A callee MUST require an explicit opt-in before framed input can select the persisted pipeline.
- Operators SHOULD enable acceptance only when every caller authorized to invoke the function may use that pipeline.
- A recognized malformed or unsupported frame MUST fail closed.
- Pipeline stages MUST independently validate their envelopes and external references.

## Filesystem envelope

### Purpose

The filesystem envelope carries the string received by `FileSystemSerDesStage`. It either stores the exact string
inline or identifies a file containing its UTF-8 representation.

The envelope is a JSON object encoded as a Unicode string. JSON object member order and insignificant whitespace are
not significant. Producers SHOULD emit compact JSON.

### Version 1 schemas

An inline envelope contains exactly these six members:

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "ownerDurableExecutionArn": "<producer durable execution ARN>",
  "ownerEntityId": "<producer entity ID>",
  "payloadType": "STRING",
  "payloadDigest": "<lowercase SHA-256 hex>",
  "data": "<serialized stage input>"
}
```

A file envelope without a preview contains exactly these six members:

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "ownerDurableExecutionArn": "<producer durable execution ARN>",
  "ownerEntityId": "<producer entity ID>",
  "payloadType": "STRING",
  "payloadDigest": "<lowercase SHA-256 hex>",
  "file": "<absolute payload path>"
}
```

A file envelope with a preview contains exactly these seven members:

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "ownerDurableExecutionArn": "<producer durable execution ARN>",
  "ownerEntityId": "<producer entity ID>",
  "payloadType": "STRING",
  "payloadDigest": "<lowercase SHA-256 hex>",
  "file": "<absolute payload path>",
  "preview": {}
}
```

Consumers MUST reject extra members and envelopes that do not match exactly one of these schemas.

### Member requirements

| Member | Requirement |
|---|---|
| `__durable_execution_filesystem_serdes` | REQUIRED integer with the exact value `1`. |
| `ownerDurableExecutionArn` | REQUIRED non-empty string identifying the producing durable execution. |
| `ownerEntityId` | REQUIRED non-empty string identifying the producing payload entity. |
| `payloadType` | REQUIRED string. Version 1 supports only `STRING`. |
| `payloadDigest` | REQUIRED 64-character lowercase hexadecimal SHA-256 digest. |
| `data` | REQUIRED string for an inline envelope and forbidden for a file envelope. |
| `file` | REQUIRED string for a file envelope and forbidden for an inline envelope. Producers MUST emit an absolute path. |
| `preview` | OPTIONAL JSON object for a file envelope and forbidden for an inline envelope. |

Exactly one of `data` and `file` MUST be present. `preview` is informational, is not part of the restored payload, and
is not covered by `payloadDigest`.

### Payload bytes and digest

For `payloadType` `STRING`, payload bytes are the UTF-8 encoding of the exact string supplied to the filesystem stage.
No Unicode normalization or JSON canonicalization is applied.

`payloadDigest` is the lowercase hexadecimal SHA-256 digest of those exact bytes.

- For an inline envelope, `data` MUST restore the exact stage-input string. The consumer MUST UTF-8 encode it and
  verify `payloadDigest`.
- For a file envelope, the file MUST contain the exact payload bytes. The consumer MUST verify `payloadDigest` before
  decoding the bytes as UTF-8.

For example, the exact stage-input string below, with no trailing newline:

```json
{"orderId":"123"}
```

has this digest:

```text
379214f2718333da854418b2ced7435430af05f9082d793abd6b7cd9159cdb75
```

### Recognition and error handling

The member name `__durable_execution_filesystem_serdes` reserves the filesystem-envelope namespace.

1. A stage input that is not a JSON object containing that member MUST pass through unchanged.
2. An object containing the member MUST be treated as a filesystem envelope.
3. A recognized envelope with an unsupported version, invalid JSON types, missing or extra members, an invalid digest,
   an invalid owner, or an invalid path MUST fail deserialization.
4. A recognized invalid envelope MUST NOT pass through as ordinary stage data.

Implementations SHOULD also fail malformed JSON that can be identified as an attempt to declare the reserved top-level
member.

### Ownership and cross-execution references

During ordinary checkpoint replay, the declared owner ARN and entity ID MUST match the current SerDes context.

A consumer MAY accept a different owner only at a boundary where a value is expected to cross durable executions:

- an invoked execution's initial input; or
- the result of a chained invoke operation.

The declared owner continues to control path validation. A consumer MUST NOT replace it with the consuming execution's
identity when resolving the file.

### Version 1 path construction

Both producer and consumer MUST be configured with access to the same durable shared filesystem at the same absolute
base path. Lambda ephemeral storage is not compatible with this protocol.

The producing execution directory is derived from `ownerDurableExecutionArn`:

- In `URI` mode, a standard durable execution ARN uses three path segments: function name, execution name, and
  invocation ID. If the ARN cannot be parsed, the complete ARN is encoded as one segment.
- In `HASH` mode, the directory is the lowercase SHA-256 hex digest of the UTF-8 ARN.

URI path segments use RFC 3986 unreserved characters (`A-Z`, `a-z`, `0-9`, `-`, `_`, `.`, and `~`) unchanged. Every
other UTF-8 byte is percent-encoded with uppercase hexadecimal digits.

The version 1 Java producer creates file names using:

```text
<encoded-owner-entity-id>-<payload-digest>-<unique-suffix>.payload
```

In `HASH` mode, `<encoded-owner-entity-id>` is the lowercase SHA-256 hex digest of the UTF-8 entity ID. The unique
suffix does not carry payload semantics.

A consumer:

- MUST normalize the referenced path and ensure it remains beneath the configured base path;
- MUST require the parent directory to equal the directory derived from the declared owner ARN;
- MUST require the file name to contain the encoded owner entity ID and declared payload digest;
- MUST prevent symbolic-link traversal and time-of-check/time-of-use path substitution; and
- MUST read the file without following symbolic links.

Producers MUST publish a new immutable file without replacing an existing payload. Consumers MUST treat the file
reference as a capability and protect it with the same controls as the payload.

### Chained-invoke example

Given the stage input and digest above, an inline filesystem envelope can be framed for chained invoke as:

```text
__durable_execution_chained_invoke_payload:1:{"__durable_execution_filesystem_serdes":1,"ownerDurableExecutionArn":"arn:aws:lambda:us-east-1:123456789012:function:producer:1/durable-execution/example/00000000-0000-0000-0000-000000000000","ownerEntityId":"operation/1/invoke-payload","payloadType":"STRING","payloadDigest":"379214f2718333da854418b2ced7435430af05f9082d793abd6b7cd9159cdb75","data":"{\"orderId\":\"123\"}"}
```

An opted-in callee removes the chained-invoke prefix, passes the remaining JSON string through its persisted pipeline,
and obtains the original JSON string from the filesystem stage before the root value codec deserializes it.

## Cross-SDK interoperability requirements

Two SDKs interoperate only when all applicable layers are compatible:

- Both implement the chained-invoke source-frame producer or consumer behavior required for their direction of use.
- Both configure wire-compatible persisted pipeline stages, and the consumer reverses the producer's transformation
  order during deserialization.
- Both use compatible root value codecs. The filesystem envelope preserves a string; it does not make
  language-specific object encodings portable.
- Both filesystem stages implement this version 1 envelope and path contract.
- Both functions can access the same payload files at the path carried in the envelope.

SDK-specific filesystem envelopes such as unversioned `{"file": ...}` or `{"data": ...}` objects are not version 1
filesystem envelopes and are not interoperable with this format.

Cross-SDK test suites SHOULD include:

- unframed input with callee acceptance both disabled and enabled;
- version 1 framed input;
- unknown and malformed source-frame versions;
- inline and file filesystem envelopes;
- digest, owner, path, and symbolic-link failures;
- `ALWAYS` and `OVERFLOW` storage behavior;
- chained-invoke payload and result boundaries; and
- non-ASCII payload and path values.

## Versioning

The source-frame version and filesystem-envelope version are independent. A future source frame can continue to carry
a version 1 filesystem envelope, and a version 1 source frame can carry a later pipeline format if both applications
configure compatible pipelines.

New incompatible semantics require a new version. Producers MUST NOT emit a version until the target consumer supports
it. Consumers MUST fail closed for recognized unsupported versions.

# Filesystem SerDes wire format

## Status and scope

This document defines the persisted strings produced by Java `FileSystemSerDes`. The format is versioned durable state:
changes must preserve replay of existing checkpoints or introduce an explicit migration boundary.

The internal Lambda durable execution request/response envelope is not covered. It remains encoded by
`DurableInputOutputSerDes`.

## Recognition

A value is a filesystem envelope only when its top-level JSON object contains:

```json
"__durable_execution_filesystem_serdes": 1
```

Unmarked input is passed unchanged to the configured delegate SerDes. Marked malformed input, duplicate fields,
trailing tokens, unsupported versions, and unknown members are rejected.

Text containing the marker inside a JSON string is not recognized as an envelope.

## Version 1 schemas

### Inline

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "data": "<delegate-serialized string>",
  "sha256": "<64 lowercase hexadecimal characters>"
}
```

### File

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "file": "/absolute/path/to/payload.json",
  "sha256": "<64 lowercase hexadecimal characters>"
}
```

### File with preview

```json
{
  "__durable_execution_filesystem_serdes": 1,
  "file": "/absolute/path/to/payload.json",
  "sha256": "<64 lowercase hexadecimal characters>",
  "preview": {
    "id": "order-123",
    "email": "***"
  }
}
```

Exactly one of `data` or `file` is required. `preview` is valid only with `file`. The complete encoded envelope must fit
the configured `checkpointEnvelopeLimitBytes`.

## Payload and digest

The payload is the UTF-8 byte sequence of the delegate SerDes string. `sha256` is the lowercase hexadecimal SHA-256
digest of those bytes.

Deserialization verifies both inline and file payloads before invoking the delegate. Missing, malformed, or mismatched
digests are permanent `SerDesException` failures.

Malformed UTF-8 file content and filesystem read failures are `RetryableSerDesException` failures.

## Path construction

All paths are rooted under the configured absolute base path.

### URI encoding

For a durable execution ARN matching:

```text
arn:<partition>:lambda:<region>:<account>:function:<function>:<qualifier>/durable-execution/<execution>/<invocation>
```

the execution directory is:

```text
<base>/<encoded-function>/<encoded-execution>/<encoded-invocation>
```

Other ARNs are encoded into one directory segment.

The file name is:

```text
<encoded-entity-id>-<payload-digest>-<uuid>.json
```

URI encoding preserves ASCII letters, digits, `-`, `_`, `.`, and `~`; all other UTF-8 bytes are percent encoded.

### Hash encoding

`HASH` replaces the execution ARN and entity ID with lowercase SHA-256 hexadecimal strings. The payload digest and
unique UUID suffix remain in the file name.

## Publication

Files are immutable and published with `CREATE_NEW`. A failed write attempts to delete the partially created file before
returning a retryable failure. Repeated serialization writes a distinct UUID-suffixed file, even for identical payloads.

No hard-link or rename operation is required.

## Filesystem security requirements

The provider must support `SecureDirectoryStream`.

Directory traversal starts at the filesystem root. Every path component is opened relative to an already-held parent
directory with `NOFOLLOW_LINKS`. File reads and writes also use `NOFOLLOW_LINKS`.

The implementation fails closed when:

- the provider lacks `SecureDirectoryStream`;
- the base path, an ancestor, execution directory, or payload file is a symbolic link;
- a path is outside the configured base path;
- the file is missing or unreadable.

Security failures represented by invalid envelope or path metadata are permanent. Filesystem I/O failures are
retryable.

## Cross-execution references

The file path is carried in the persisted envelope. A caller and callee may exchange a filesystem-backed invoke payload
or result only when both can access the same mounted path and use compatible delegate SerDes configuration.

Use `InvokeConfig.payloadSerDes(...)` and `InvokeConfig.serDes(...)` to select filesystem storage independently for the
payload and result boundaries.

## Preview rules

Preview data is informational and is never used for deserialization.

Built-in structured previews support:

- include-all and exclude-all modes;
- include, exclude, and mask selectors;
- field-name matching at any depth;
- exact dotted-path matching;
- a configurable mask string;
- a configurable UTF-8 byte budget, defaulting to 4 KiB.

Object arrays are flattened at the containing path. Scalar arrays are retained. Fields containing dots are omitted
because they are ambiguous with path selectors.

## Versioning

Readers accept version `1` only. Future versions must use a different marker value and must not reinterpret version 1
members.

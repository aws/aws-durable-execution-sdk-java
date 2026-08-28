// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single operation within an execution (step, wait, invoke, callback, or context).
 *
 * <p>Mirrors the JS {@code OperationRecord} interface field-for-field so the emitted wire JSON is identical. A
 * {@code null} field is treated as <em>absent</em> and omitted from the wire map (distinct from an explicit JSON null).
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class OperationRecord {
    private String id;
    private String name;
    private String type;
    private String subType;
    private String parentId;
    private String status;
    private String startTime;
    private String endTime;
    private Long durationMs;
    private Integer attempt;
    private ErrorInfo error;
    private Object result;
    private Boolean truncated;

    public String id() {
        return id;
    }

    public OperationRecord id(String v) {
        this.id = v;
        return this;
    }

    public String name() {
        return name;
    }

    public OperationRecord name(String v) {
        this.name = v;
        return this;
    }

    public String type() {
        return type;
    }

    public OperationRecord type(String v) {
        this.type = v;
        return this;
    }

    public String subType() {
        return subType;
    }

    public OperationRecord subType(String v) {
        this.subType = v;
        return this;
    }

    public String parentId() {
        return parentId;
    }

    public OperationRecord parentId(String v) {
        this.parentId = v;
        return this;
    }

    public String status() {
        return status;
    }

    public OperationRecord status(String v) {
        this.status = v;
        return this;
    }

    public String startTime() {
        return startTime;
    }

    public OperationRecord startTime(String v) {
        this.startTime = v;
        return this;
    }

    public String endTime() {
        return endTime;
    }

    public OperationRecord endTime(String v) {
        this.endTime = v;
        return this;
    }

    public Long durationMs() {
        return durationMs;
    }

    public OperationRecord durationMs(Long v) {
        this.durationMs = v;
        return this;
    }

    public Integer attempt() {
        return attempt;
    }

    public OperationRecord attempt(Integer v) {
        this.attempt = v;
        return this;
    }

    public ErrorInfo error() {
        return error;
    }

    public OperationRecord error(ErrorInfo v) {
        this.error = v;
        return this;
    }

    public Object result() {
        return result;
    }

    public OperationRecord result(Object v) {
        this.result = v;
        return this;
    }

    public Boolean truncated() {
        return truncated;
    }

    public OperationRecord truncated(Boolean v) {
        this.truncated = v;
        return this;
    }

    /** Shallow copy — used by the size limiter, which must not mutate the shared record. */
    public OperationRecord copy() {
        OperationRecord c = new OperationRecord();
        c.id = id;
        c.name = name;
        c.type = type;
        c.subType = subType;
        c.parentId = parentId;
        c.status = status;
        c.startTime = startTime;
        c.endTime = endTime;
        c.durationMs = durationMs;
        c.attempt = attempt;
        c.error = error;
        c.result = result;
        c.truncated = truncated;
        return c;
    }

    /**
     * Deep copy for per-exporter isolation: like {@link #copy()} but the {@code result} payload's mutable container
     * structure is rebuilt so one exporter cannot mutate a later exporter's copy. {@code error} is an immutable
     * {@link ErrorInfo} and is shared safely.
     */
    public OperationRecord deepCopy() {
        OperationRecord c = copy();
        c.result = Json.deepCopyContent(result);
        return c;
    }

    /** Serializes to the camelCase wire map, omitting absent (null) fields, in the JS field order. */
    public Map<String, Object> toWireMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (id != null) {
            data.put("id", id);
        }
        if (name != null) {
            data.put("name", name);
        }
        if (type != null) {
            data.put("type", type);
        }
        if (subType != null) {
            data.put("subType", subType);
        }
        if (parentId != null) {
            data.put("parentId", parentId);
        }
        if (status != null) {
            data.put("status", status);
        }
        if (startTime != null) {
            data.put("startTime", startTime);
        }
        if (endTime != null) {
            data.put("endTime", endTime);
        }
        if (durationMs != null) {
            data.put("durationMs", durationMs);
        }
        if (attempt != null) {
            data.put("attempt", attempt);
        }
        if (error != null) {
            data.put("error", error.toWireMap());
        }
        if (result != null) {
            data.put("result", result);
        }
        if (truncated != null) {
            data.put("truncated", truncated);
        }
        return data;
    }
}

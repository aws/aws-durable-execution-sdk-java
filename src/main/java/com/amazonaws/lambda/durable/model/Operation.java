package com.amazonaws.lambda.durable.model;

/**
 * Represents a single operation in checkpoint log.
 * Start with minimal fields.
 */
public class Operation {
    private final String id;
    private final String name;
    private final String result;
    
    public Operation(String id, String name, String result) {
        this.id = id;
        this.name = name;
        this.result = result;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getResult() { return result; }
}

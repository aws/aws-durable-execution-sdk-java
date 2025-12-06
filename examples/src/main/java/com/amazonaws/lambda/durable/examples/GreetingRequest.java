package com.amazonaws.lambda.durable.examples;

public class GreetingRequest {
    private String name;
    
    public GreetingRequest() {
    }
    
    public GreetingRequest(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name != null ? name : "World";
    }
    
    public void setName(String name) {
        this.name = name;
    }
}

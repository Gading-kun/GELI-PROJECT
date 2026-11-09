package com.geli.warehouse.common.error;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, long id) {
        super(resource + " " + id + " was not found.");
    }
}

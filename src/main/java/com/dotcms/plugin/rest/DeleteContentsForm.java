package com.dotcms.plugin.rest;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class DeleteContentsForm {

    private final List<String> contentletIds;

    @JsonCreator
    public DeleteContentsForm(
            @JsonProperty("contentletIds") final List<String> contentletIds) {
        this.contentletIds = contentletIds;
    }

    public List<String> getContentletIds() {
        return contentletIds;
    }

}

package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlacePhotoResponse(
        String imageUrl,
        String placeId,
        String attribution,
        String attributionUrl
) {
    public PlacePhotoResponse(String imageUrl, String placeId) {
        this(imageUrl, placeId, null, null);
    }
}

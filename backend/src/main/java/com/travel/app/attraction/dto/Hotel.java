package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Hotel(
        @Size(max = 200) String name,
        Double rating,
        Integer userRatingsTotal,
        @Size(max = 500) String address,
        @Size(max = 200) String placeId,
        @Size(max = 500) String attribution,
        @Size(max = 1000) String attributionUrl
) {
    public Hotel(String name, Double rating, Integer userRatingsTotal, String address, String placeId) {
        this(name, rating, userRatingsTotal, address, placeId, null, null);
    }
}

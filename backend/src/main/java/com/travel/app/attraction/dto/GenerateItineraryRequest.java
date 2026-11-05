package com.travel.app.attraction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GenerateItineraryRequest(
        @NotBlank @Size(max = 2000) String message,
        @NotNull
        @Pattern(regexp = "^(walking|driving|transit)$",
                 message = "transportMode 必須為 walking、driving 或 transit")
        String transportMode
) {
}

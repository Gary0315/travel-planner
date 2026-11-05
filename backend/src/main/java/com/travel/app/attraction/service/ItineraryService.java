package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.TripPlan;

public interface ItineraryService {
    TripPlan generate(String message, String transportMode);
}

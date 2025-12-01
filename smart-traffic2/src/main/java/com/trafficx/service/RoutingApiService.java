package com.trafficx.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
// import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.*;

@Service
public class RoutingApiService {

    private final RestTemplate restTemplate;
    private final TrafficApiService trafficApiService;
    
    @Value("${openrouteservice.api.key:}")
    private String orsApiKey;
    
    @Value("${here.api.key:}")
    private String hereApiKey;

    // @Autowired
    public RoutingApiService(RestTemplate restTemplate, TrafficApiService trafficApiService) {
        this.restTemplate = restTemplate;
        this.trafficApiService = trafficApiService;
    }

    /**
     * Get optimal route with traffic consideration
     */
    public RouteResult getOptimalRoute(double startLat, double startLon, double endLat, double endLon, boolean avoidTraffic) {
        RouteResult primaryRoute = getRoute(startLat, startLon, endLat, endLon);
        
        if (avoidTraffic && primaryRoute != null && primaryRoute.getCoordinates() != null) {
            // Analyze traffic on primary route
            List<TrafficApiService.TrafficFlow> routeTraffic = 
                trafficApiService.getRouteTrafficFlow(primaryRoute.getCoordinates());
            
            primaryRoute.setRouteTrafficPoints(routeTraffic); // Store traffic points
            
            int avgCongestion = (int) routeTraffic.stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCongestion)
                .average()
                .orElse(0);
            
            primaryRoute.setAverageCongestion(avgCongestion);
            primaryRoute.setTrafficLevel(getTrafficLevel(avgCongestion));
            
            // If heavy traffic, try to find alternative
            if (avgCongestion > 60) {
                RouteResult alternateRoute = getAlternativeRoute(startLat, startLon, endLat, endLon);
                if (alternateRoute != null) {
                    List<TrafficApiService.TrafficFlow> altTraffic = 
                        trafficApiService.getRouteTrafficFlow(alternateRoute.getCoordinates());
                    
                    alternateRoute.setRouteTrafficPoints(altTraffic);
                    
                    int altCongestion = (int) altTraffic.stream()
                        .mapToInt(TrafficApiService.TrafficFlow::getCongestion)
                        .average()
                        .orElse(0);
                    
                    alternateRoute.setAverageCongestion(altCongestion);
                    alternateRoute.setTrafficLevel(getTrafficLevel(altCongestion));
                    
                    // If alternate is significantly better, suggest it
                    if (altCongestion < avgCongestion - 20) {
                        alternateRoute.setRecommended(true);
                        alternateRoute.setRecommendationReason("Lower traffic congestion - saves approximately " + 
                            Math.round((avgCongestion - altCongestion) / 10.0 * 5) + " minutes");
                        return alternateRoute;
                    }
                }
            }
        }
        
        return primaryRoute;
    }

    /**
     * Get route between two points
     */
    public RouteResult getRoute(double startLat, double startLon, double endLat, double endLon) {
        if (orsApiKey != null && !orsApiKey.isEmpty()) {
            return getOpenRouteServiceRoute(startLat, startLon, endLat, endLon);
        } else if (hereApiKey != null && !hereApiKey.isEmpty()) {
            return getHereRoute(startLat, startLon, endLat, endLon);
        }
        return getDemoRoute(startLat, startLon, endLat, endLon);
    }

    /**
     * Get alternative route (different algorithm or avoiding highways)
     */
    private RouteResult getAlternativeRoute(double startLat, double startLon, double endLat, double endLon) {
        if (orsApiKey != null && !orsApiKey.isEmpty()) {
            return getOpenRouteServiceRoute(startLat, startLon, endLat, endLon, true);
        } else if (hereApiKey != null && !hereApiKey.isEmpty()) {
            return getHereAlternativeRoute(startLat, startLon, endLat, endLon);
        }
        return getDemoAlternativeRoute(startLat, startLon, endLat, endLon);
    }

    private RouteResult getOpenRouteServiceRoute(double startLat, double startLon, double endLat, double endLon) {
        return getOpenRouteServiceRoute(startLat, startLon, endLat, endLon, false);
    }

    private RouteResult getOpenRouteServiceRoute(double startLat, double startLon, double endLat, double endLon, boolean alternative) {
        try {
            String url = "https://api.openrouteservice.org/v2/directions/driving-car/geojson";
            
            JSONObject requestBody = new JSONObject();
            JSONArray coordinates = new JSONArray();
            coordinates.put(new JSONArray().put(startLon).put(startLat));
            coordinates.put(new JSONArray().put(endLon).put(endLat));
            requestBody.put("coordinates", coordinates);
            
            if (alternative) {
                JSONObject options = new JSONObject();
                options.put("avoid_features", new JSONArray().put("highways"));
                requestBody.put("options", options);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", orsApiKey);
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class
            );
            
            return parseRoute(response.getBody());
        } catch (Exception e) {
            System.err.println("Error fetching OpenRouteService route: " + e.getMessage());
            return getDemoRoute(startLat, startLon, endLat, endLon);
        }
    }

    private RouteResult getHereRoute(double startLat, double startLon, double endLat, double endLon) {
        try {
            String url = String.format(
                "https://router.hereapi.com/v8/routes?transportMode=car&origin=%f,%f&destination=%f,%f&return=polyline,summary&apiKey=%s",
                startLat, startLon, endLat, endLon, hereApiKey
            );
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return parseHereRoute(response.getBody());
        } catch (Exception e) {
            System.err.println("Error fetching HERE route: " + e.getMessage());
            return getDemoRoute(startLat, startLon, endLat, endLon);
        }
    }

    private RouteResult getHereAlternativeRoute(double startLat, double startLon, double endLat, double endLon) {
        try {
            String url = String.format(
                "https://router.hereapi.com/v8/routes?transportMode=car&origin=%f,%f&destination=%f,%f&return=polyline,summary&alternatives=1&apiKey=%s",
                startLat, startLon, endLat, endLon, hereApiKey
            );
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject json = new JSONObject(response.getBody());
            
            // Try to get alternative route if available
            if (json.has("routes")) {
                JSONArray routes = json.getJSONArray("routes");
                if (routes.length() > 1) {
                    return parseHereRouteFromArray(routes.getJSONObject(1));
                }
            }
            
            return parseHereRoute(response.getBody());
        } catch (Exception e) {
            System.err.println("Error fetching HERE alternative route: " + e.getMessage());
            return getDemoAlternativeRoute(startLat, startLon, endLat, endLon);
        }
    }

    private RouteResult parseRoute(String responseBody) {
        RouteResult result = new RouteResult();
        JSONObject json = new JSONObject(responseBody);
        
        if (json.has("features") && json.getJSONArray("features").length() > 0) {
            JSONObject feature = json.getJSONArray("features").getJSONObject(0);
            
            if (feature.has("geometry")) {
                JSONObject geometry = feature.getJSONObject("geometry");
                if (geometry.has("coordinates")) {
                    JSONArray coords = geometry.getJSONArray("coordinates");
                    List<double[]> coordinates = new ArrayList<>();
                    
                    for (int i = 0; i < coords.length(); i++) {
                        JSONArray coord = coords.getJSONArray(i);
                        coordinates.add(new double[]{coord.getDouble(1), coord.getDouble(0)});
                    }
                    
                    result.setCoordinates(coordinates);
                }
            }
            
            if (feature.has("properties")) {
                JSONObject props = feature.getJSONObject("properties");
                JSONObject summary = props.getJSONObject("summary");
                
                result.setDistance(summary.getDouble("distance"));
                result.setDuration(summary.getDouble("duration"));
            }
        }
        
        return result;
    }

    private RouteResult parseHereRoute(String responseBody) {
        JSONObject json = new JSONObject(responseBody);
        
        if (json.has("routes") && json.getJSONArray("routes").length() > 0) {
            JSONObject route = json.getJSONArray("routes").getJSONObject(0);
            return parseHereRouteFromArray(route);
        }
        
        return new RouteResult();
    }

    private RouteResult parseHereRouteFromArray(JSONObject route) {
        RouteResult result = new RouteResult();
        
        if (route.has("sections")) {
            JSONArray sections = route.getJSONArray("sections");
            List<double[]> allCoordinates = new ArrayList<>();
            double totalDistance = 0;
            double totalDuration = 0;
            
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.getJSONObject(i);
                
                if (section.has("polyline")) {
                    String polyline = section.getString("polyline");
                    List<double[]> coords = decodeHerePolyline(polyline);
                    allCoordinates.addAll(coords);
                }
                
                if (section.has("summary")) {
                    JSONObject summary = section.getJSONObject("summary");
                    totalDistance += summary.optDouble("length", 0);
                    totalDuration += summary.optDouble("duration", 0);
                }
            }
            
            result.setCoordinates(allCoordinates);
            result.setDistance(totalDistance);
            result.setDuration(totalDuration);
        }
        
        return result;
    }

    private List<double[]> decodeHerePolyline(String encoded) {
        List<double[]> coordinates = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            coordinates.add(new double[]{lat / 1E5, lng / 1E5});
        }

        return coordinates;
    }

    private RouteResult getDemoRoute(double startLat, double startLon, double endLat, double endLon) {
        RouteResult result = new RouteResult();
        
        List<double[]> coordinates = new ArrayList<>();
        int steps = 25;
        
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            
            double midLat = (startLat + endLat) / 2 + (Math.random() - 0.5) * 0.015;
            double midLon = (startLon + endLon) / 2 + (Math.random() - 0.5) * 0.015;
            
            double lat = Math.pow(1-t, 2) * startLat + 2 * (1-t) * t * midLat + Math.pow(t, 2) * endLat;
            double lon = Math.pow(1-t, 2) * startLon + 2 * (1-t) * t * midLon + Math.pow(t, 2) * endLon;
            
            coordinates.add(new double[]{lat, lon});
        }
        
        result.setCoordinates(coordinates);
        
        double distance = calculateDistance(startLat, startLon, endLat, endLon) * 1000;
        result.setDistance(distance);
        result.setDuration(distance / 50 * 3.6);
        
        return result;
    }

    private RouteResult getDemoAlternativeRoute(double startLat, double startLon, double endLat, double endLon) {
        RouteResult result = new RouteResult();
        
        List<double[]> coordinates = new ArrayList<>();
        int steps = 25;
        
        // Create a more curved alternative path
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            
            // Offset the midpoint more for a different route
            double midLat = (startLat + endLat) / 2 + 0.025 * Math.sin(t * Math.PI);
            double midLon = (startLon + endLon) / 2 + 0.025 * Math.cos(t * Math.PI);
            
            double lat = Math.pow(1-t, 2) * startLat + 2 * (1-t) * t * midLat + Math.pow(t, 2) * endLat;
            double lon = Math.pow(1-t, 2) * startLon + 2 * (1-t) * t * midLon + Math.pow(t, 2) * endLon;
            
            coordinates.add(new double[]{lat, lon});
        }
        
        result.setCoordinates(coordinates);
        
        // Alternative route is typically slightly longer
        double distance = calculateDistance(startLat, startLon, endLat, endLon) * 1000 * 1.15;
        result.setDistance(distance);
        result.setDuration(distance / 50 * 3.6);
        
        return result;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private String getTrafficLevel(int congestion) {
        if (congestion >= 70) return "Heavy";
        if (congestion >= 40) return "Moderate";
        if (congestion >= 20) return "Light";
        return "Free Flow";
    }

    public static class RouteResult {
        private List<double[]> coordinates;
        private double distance;
        private double duration;
        private int averageCongestion;
        private String trafficLevel;
        private boolean recommended;
        private String recommendationReason;
        private List<TrafficApiService.TrafficFlow> routeTrafficPoints;

        public List<double[]> getCoordinates() { return coordinates; }
        public void setCoordinates(List<double[]> coordinates) { this.coordinates = coordinates; }
        public double getDistance() { return distance; }
        public void setDistance(double distance) { this.distance = distance; }
        public double getDuration() { return duration; }
        public void setDuration(double duration) { this.duration = duration; }
        public int getAverageCongestion() { return averageCongestion; }
        public void setAverageCongestion(int averageCongestion) { this.averageCongestion = averageCongestion; }
        public String getTrafficLevel() { return trafficLevel; }
        public void setTrafficLevel(String trafficLevel) { this.trafficLevel = trafficLevel; }
        public boolean isRecommended() { return recommended; }
        public void setRecommended(boolean recommended) { this.recommended = recommended; }
        public String getRecommendationReason() { return recommendationReason; }
        public void setRecommendationReason(String reason) { this.recommendationReason = reason; }
        public List<TrafficApiService.TrafficFlow> getRouteTrafficPoints() { return routeTrafficPoints; }
        public void setRouteTrafficPoints(List<TrafficApiService.TrafficFlow> points) { this.routeTrafficPoints = points; }
    }
}
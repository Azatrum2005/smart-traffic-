package com.trafficx.controller;

import com.trafficx.service.TrafficApiService;
import com.trafficx.service.RoutingApiService;
import com.trafficx.service.AIAnalysisService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.*;

@RestController
@RequestMapping("/api/traffic")
public class GlobalTrafficController {

    private final TrafficApiService trafficApiService;
    private final RoutingApiService routingApiService;
    private final AIAnalysisService aiAnalysisService;

    public GlobalTrafficController(TrafficApiService trafficApiService,
                                    RoutingApiService routingApiService,
                                    AIAnalysisService aiAnalysisService) {
        this.trafficApiService = trafficApiService;
        this.routingApiService = routingApiService;
        this.aiAnalysisService = aiAnalysisService;
    }

    /**
     * Get traffic incidents with timestamps
     */
    @GetMapping("/incidents")
    public ResponseEntity<List<TrafficApiService.TrafficIncident>> getIncidents(
            @RequestParam String bbox) {
        List<TrafficApiService.TrafficIncident> incidents = trafficApiService.getTrafficIncidents(bbox);
        return ResponseEntity.ok(incidents);
    }

    /**
     * Get traffic flow for a specific point
     */
    @GetMapping("/flow")
    public ResponseEntity<TrafficApiService.TrafficFlow> getFlow(
            @RequestParam double lat,
            @RequestParam double lon) {
        TrafficApiService.TrafficFlow flow = trafficApiService.getTrafficFlow(lat, lon);
        return ResponseEntity.ok(flow);
    }

    /**
     * Get optimal route with traffic consideration and rerouting
     */
    @GetMapping("/route")
    public ResponseEntity<RoutingApiService.RouteResult> getRoute(
            @RequestParam double startLat,
            @RequestParam double startLon,
            @RequestParam double endLat,
            @RequestParam double endLon,
            @RequestParam(defaultValue = "true") boolean avoidTraffic) {
        
        RoutingApiService.RouteResult route = routingApiService.getOptimalRoute(
            startLat, startLon, endLat, endLon, avoidTraffic);
        return ResponseEntity.ok(route);
    }

    /**
     * Chat with AI about traffic
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        AIAnalysisService.TrafficContext context = new AIAnalysisService.TrafficContext(
            request.getCity(),
            request.getIncidents(),
            request.getFlows()
        );
        
        AIAnalysisService.ChatResponse response = aiAnalysisService.chat(
            request.getSessionId(),
            request.getMessage(),
            context
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", response.getMessage());
        result.put("fromAI", response.isFromAI());
        result.put("timestamp", new Date().toString());
        
        return ResponseEntity.ok(result);
    }

    /**
     * Clear chat history
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<Map<String, String>> clearChat(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        aiAnalysisService.clearConversation(sessionId);
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "cleared");
        return ResponseEntity.ok(response);
    }

    /**
     * Get AI analysis (legacy endpoint)
     */
    @PostMapping("/analysis")
    public ResponseEntity<Map<String, String>> getAnalysis(@RequestBody AnalysisRequest request) {
        String analysis = aiAnalysisService.analyzeTraffic(
            request.getCity(),
            request.getIncidents(),
            request.getFlows()
        );
        
        Map<String, String> response = new HashMap<>();
        response.put("analysis", analysis);
        response.put("city", request.getCity());
        response.put("timestamp", new Date().toString());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get predefined city locations
     */
    @GetMapping("/cities")
    public ResponseEntity<List<CityInfo>> getCities() {
        List<CityInfo> cities = Arrays.asList(
            new CityInfo("New York", 40.7128, -74.0060, "40.4774,-74.2591,41.0074,-73.7004"),
            new CityInfo("London", 51.5074, -0.1278, "51.2867,-0.5103,51.6919,0.3340"),
            new CityInfo("Paris", 48.8566, 2.3522, "48.8155,2.2241,48.9022,2.4699"),
            new CityInfo("Dubai", 25.2048, 55.2708, "25.0657,55.1713,25.3587,55.5472"),
            new CityInfo("Tokyo", 35.6762, 139.6503, "35.5175,139.4976,35.8167,139.9199"),
            new CityInfo("Berlin", 52.5200, 13.4050, "52.3382,13.0883,52.6755,13.7611"),
            new CityInfo("Sydney", -33.8688, 151.2093, "-34.1183,150.5210,-33.5781,151.3430"),
            new CityInfo("Singapore", 1.3521, 103.8198, "1.1304,103.6920,1.4710,104.0120"),
            new CityInfo("Mumbai", 19.0760, 72.8777, "18.8947,72.7760,19.2703,72.9786"),
            new CityInfo("Toronto", 43.6532, -79.3832, "43.5810,-79.6391,43.8554,-79.1168")
        );
        return ResponseEntity.ok(cities);
    }

    /**
     * Geocode location search to coordinates
     */
    @GetMapping("/geocode")
    public ResponseEntity<Map<String, Object>> geocodeLocation(
            @RequestParam String query,
            @RequestParam(required = false) String cityContext) {
        
        try {
            // Use Nominatim (OpenStreetMap) for geocoding - it's free!
            String searchQuery = query;
            if (cityContext != null && !cityContext.isEmpty()) {
                searchQuery = query + ", " + cityContext;
            }
            
            String url = "https://nominatim.openstreetmap.org/search?q=" + 
                        java.net.URLEncoder.encode(searchQuery, "UTF-8") + 
                        "&format=json&limit=1";
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "SmartTrafficX/1.0");
            
            org.springframework.http.HttpEntity<String> entity = 
                new org.springframework.http.HttpEntity<>(headers);
            
            ResponseEntity<String> response = new org.springframework.web.client.RestTemplate()
                .exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
            
            org.json.JSONArray results = new org.json.JSONArray(response.getBody());
            
            Map<String, Object> result = new HashMap<>();
            
            if (results.length() > 0) {
                org.json.JSONObject location = results.getJSONObject(0);
                result.put("latitude", location.getDouble("lat"));
                result.put("longitude", location.getDouble("lon"));
                result.put("displayName", location.getString("display_name"));
                result.put("found", true);
            } else {
                result.put("found", false);
                result.put("message", "Location not found");
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("Error geocoding location: " + e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("found", false);
            error.put("message", "Error searching location");
            return ResponseEntity.ok(error);
        }
    }

    // DTOs
    public static class AnalysisRequest {
        private String city;
        private List<TrafficApiService.TrafficIncident> incidents;
        private List<TrafficApiService.TrafficFlow> flows;

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public List<TrafficApiService.TrafficIncident> getIncidents() { return incidents; }
        public void setIncidents(List<TrafficApiService.TrafficIncident> incidents) { this.incidents = incidents; }
        public List<TrafficApiService.TrafficFlow> getFlows() { return flows; }
        public void setFlows(List<TrafficApiService.TrafficFlow> flows) { this.flows = flows; }
    }

    public static class ChatRequest {
        private String sessionId;
        private String message;
        private String city;
        private List<TrafficApiService.TrafficIncident> incidents;
        private List<TrafficApiService.TrafficFlow> flows;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public List<TrafficApiService.TrafficIncident> getIncidents() { return incidents; }
        public void setIncidents(List<TrafficApiService.TrafficIncident> incidents) { this.incidents = incidents; }
        public List<TrafficApiService.TrafficFlow> getFlows() { return flows; }
        public void setFlows(List<TrafficApiService.TrafficFlow> flows) { this.flows = flows; }
    }

    public static class CityInfo {
        private String name;
        private double latitude;
        private double longitude;
        private String bbox;

        public CityInfo(String name, double latitude, double longitude, String bbox) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bbox = bbox;
        }

        public String getName() { return name; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public String getBbox() { return bbox; }
    }
}
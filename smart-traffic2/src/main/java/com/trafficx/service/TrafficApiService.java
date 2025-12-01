package com.trafficx.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.json.JSONObject;
import org.json.JSONArray;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TrafficApiService {

    private final RestTemplate restTemplate;
    
    @Value("${tomtom.api.key:}")
    private String tomtomApiKey;
    
    @Value("${here.api.key:}")
    private String hereApiKey;

    public TrafficApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Get real-time traffic incidents with timestamps from TomTom API
     */
    public List<TrafficIncident> getTrafficIncidents(String bbox) {
        List<TrafficIncident> incidents = new ArrayList<>();
        
        if (tomtomApiKey != null && !tomtomApiKey.isEmpty()) {
            incidents = getTomTomIncidents(bbox);
        } else if (hereApiKey != null && !hereApiKey.isEmpty()) {
            incidents = getHereIncidents(bbox);
        } else {
            incidents = getDemoIncidents(bbox);
        }
        
        return incidents;
    }

    /**
     * Get traffic incidents from TomTom with full timestamp data
     */
    private List<TrafficIncident> getTomTomIncidents(String bbox) {
        List<TrafficIncident> incidents = new ArrayList<>();
        
        try {
            // Simplified TomTom API call - removed complex fields parameter
            String url = String.format(
                "https://api.tomtom.com/traffic/services/5/incidentDetails?bbox=%s&language=en-US&categoryFilter=0,1,2,3,4,5,6,7,8,9,10,11,14&timeValidityFilter=present&key=%s",
                bbox, tomtomApiKey
            );

            System.out.println("Fetching TomTom incidents from: " + url.replace(tomtomApiKey, "***KEY***"));
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            System.out.println("TomTom Response Status: " + response.getStatusCode());
            
            JSONObject json = new JSONObject(response.getBody());
            
            if (json.has("incidents")) {
                JSONArray incidentsArray = json.getJSONArray("incidents");
                System.out.println("Found " + incidentsArray.length() + " incidents");
                
                for (int i = 0; i < incidentsArray.length(); i++) {
                    JSONObject incident = incidentsArray.getJSONObject(i);
                    TrafficIncident parsed = parseTomTomIncident(incident);
                    if (parsed != null && parsed.getLatitude() != 0 && parsed.getLongitude() != 0) {
                        incidents.add(parsed);
                    }
                }
            } else {
                System.out.println("No incidents found in response");
            }
        } catch (Exception e) {
            System.err.println("Error fetching TomTom traffic incidents: " + e.getMessage());
            e.printStackTrace();
        }

        return incidents;
    }

    /**
     * Get traffic incidents from HERE Maps API
     */
    private List<TrafficIncident> getHereIncidents(String bbox) {
        List<TrafficIncident> incidents = new ArrayList<>();
        
        try {
            // Parse bbox: minLon,minLat,maxLon,maxLat
            String[] parts = bbox.split(",");
            String hereBbox = parts[1] + "," + parts[0] + "," + parts[3] + "," + parts[2]; // HERE uses different order
            
            String url = String.format(
                "https://data.traffic.hereapi.com/v7/incidents?in=bbox:%s&apiKey=%s",
                hereBbox, hereApiKey
            );

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject json = new JSONObject(response.getBody());
            
            if (json.has("results")) {
                JSONArray results = json.getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject incident = results.getJSONObject(i);
                    incidents.add(parseHereIncident(incident));
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching HERE traffic incidents: " + e.getMessage());
        }

        return incidents;
    }

    /**
     * Get traffic flow data with real-time speed and congestion
     */
    public TrafficFlow getTrafficFlow(double lat, double lon) {
        if (tomtomApiKey != null && !tomtomApiKey.isEmpty()) {
            return getTomTomFlow(lat, lon);
        } else if (hereApiKey != null && !hereApiKey.isEmpty()) {
            return getHereFlow(lat, lon);
        }
        return getDemoFlow(lat, lon);
    }

    /**
     * Get traffic flow for multiple points (for route analysis)
     */
    public List<TrafficFlow> getRouteTrafficFlow(List<double[]> routePoints) {
        List<TrafficFlow> flows = new ArrayList<>();
        
        // Sample every 5th point to reduce API calls
        for (int i = 0; i < routePoints.size(); i += 5) {
            double[] point = routePoints.get(i);
            TrafficFlow flow = getTrafficFlow(point[0], point[1]);
            flow.setLatitude(point[0]);
            flow.setLongitude(point[1]);
            flows.add(flow);
        }
        
        return flows;
    }

    private TrafficFlow getTomTomFlow(double lat, double lon) {
        try {
            String url = String.format(
                "https://api.tomtom.com/traffic/services/4/flowSegmentData/relative/10/json?point=%f,%f&key=%s",
                lat, lon, tomtomApiKey
            );

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject json = new JSONObject(response.getBody());
            
            return parseTomTomFlow(json, lat, lon);
        } catch (Exception e) {
            System.err.println("Error fetching TomTom traffic flow: " + e.getMessage());
            return getDemoFlow(lat, lon);
        }
    }

    private TrafficFlow getHereFlow(double lat, double lon) {
        try {
            String url = String.format(
                "https://data.traffic.hereapi.com/v7/flow?in=circle:%f,%f;r=100&apiKey=%s",
                lat, lon, hereApiKey
            );

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject json = new JSONObject(response.getBody());
            
            return parseHereFlow(json, lat, lon);
        } catch (Exception e) {
            System.err.println("Error fetching HERE traffic flow: " + e.getMessage());
            return getDemoFlow(lat, lon);
        }
    }

    private TrafficIncident parseTomTomIncident(JSONObject json) {
        TrafficIncident incident = new TrafficIncident();
        
        try {
            if (json.has("properties")) {
                JSONObject props = json.getJSONObject("properties");
                
                // Get description from events
                if (props.has("events")) {
                    JSONArray events = props.getJSONArray("events");
                    if (events.length() > 0) {
                        JSONObject event = events.getJSONObject(0);
                        incident.setDescription(event.optString("description", "Traffic incident"));
                        incident.setType(event.optString("iconCategory", "UNKNOWN"));
                    }
                } else {
                    incident.setDescription("Traffic incident");
                    incident.setType("UNKNOWN");
                }
                
                incident.setDelay(props.optInt("delay", 0));
                incident.setLength(props.optInt("length", 0));
                incident.setSeverity(props.optInt("magnitudeOfDelay", 1));
                
                // Parse timestamps
                String startTime = props.optString("startTime", null);
                String endTime = props.optString("endTime", null);
                
                if (startTime != null && !startTime.isEmpty()) {
                    incident.setStartTime(parseISOTimestamp(startTime));
                }
                if (endTime != null && !endTime.isEmpty()) {
                    incident.setEndTime(parseISOTimestamp(endTime));
                }
                
                // Get road information
                if (props.has("from")) {
                    incident.setRoadName(props.optString("from", "Unknown Road"));
                } else if (props.has("to")) {
                    incident.setRoadName(props.optString("to", "Unknown Road"));
                }
            }
            
            // Parse geometry
            if (json.has("geometry")) {
                JSONObject geometry = json.getJSONObject("geometry");
                if (geometry.has("coordinates")) {
                    JSONArray coords = geometry.getJSONArray("coordinates");
                    if (coords.length() > 0) {
                        // Check if it's a point or line
                        Object firstCoord = coords.get(0);
                        if (firstCoord instanceof JSONArray) {
                            JSONArray point = coords.getJSONArray(0);
                            incident.setLongitude(point.getDouble(0));
                            incident.setLatitude(point.getDouble(1));
                        } else {
                            // It's directly a coordinate pair
                            incident.setLongitude(coords.getDouble(0));
                            incident.setLatitude(coords.getDouble(1));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing TomTom incident: " + e.getMessage());
            // Return incident with whatever data we managed to parse
        }
        
        return incident;
    }

    private TrafficIncident parseHereIncident(JSONObject json) {
        TrafficIncident incident = new TrafficIncident();
        
        incident.setDescription(json.optString("description", "Traffic incident"));
        incident.setType(json.optString("type", "UNKNOWN"));
        
        // Parse location
        if (json.has("location")) {
            JSONObject location = json.getJSONObject("location");
            if (location.has("shape")) {
                JSONObject shape = location.getJSONObject("shape");
                JSONArray links = shape.optJSONArray("links");
                if (links != null && links.length() > 0) {
                    JSONObject link = links.getJSONObject(0);
                    JSONArray points = link.optJSONArray("points");
                    if (points != null && points.length() > 0) {
                        JSONObject point = points.getJSONObject(0);
                        incident.setLatitude(point.getDouble("lat"));
                        incident.setLongitude(point.getDouble("lng"));
                    }
                }
            }
        }
        
        // Parse timestamps
        if (json.has("startTime")) {
            incident.setStartTime(parseISOTimestamp(json.getString("startTime")));
        }
        if (json.has("endTime")) {
            incident.setEndTime(parseISOTimestamp(json.getString("endTime")));
        }
        
        // Estimate severity based on impact
        if (json.has("impact")) {
            JSONObject impact = json.getJSONObject("impact");
            int criticality = impact.optInt("criticality", 1);
            incident.setSeverity(Math.min(4, criticality));
        }
        
        return incident;
    }

    private TrafficFlow parseTomTomFlow(JSONObject json, double lat, double lon) {
        TrafficFlow flow = new TrafficFlow();
        flow.setLatitude(lat);
        flow.setLongitude(lon);
        
        if (json.has("flowSegmentData")) {
            JSONObject data = json.getJSONObject("flowSegmentData");
            flow.setCurrentSpeed(data.optInt("currentSpeed", 50));
            flow.setFreeFlowSpeed(data.optInt("freeFlowSpeed", 50));
            flow.setConfidence(data.optDouble("confidence", 0.8));
            
            // Calculate congestion percentage
            int congestion = 100 - (int)((flow.getCurrentSpeed() / (double)flow.getFreeFlowSpeed()) * 100);
            flow.setCongestion(Math.max(0, Math.min(100, congestion)));
            
            // Get road name
            if (data.has("frc")) {
                flow.setRoadName(data.optString("frc", "Unknown Road"));
            }
        }
        
        return flow;
    }

    private TrafficFlow parseHereFlow(JSONObject json, double lat, double lon) {
        TrafficFlow flow = new TrafficFlow();
        flow.setLatitude(lat);
        flow.setLongitude(lon);
        
        if (json.has("results")) {
            JSONArray results = json.getJSONArray("results");
            if (results.length() > 0) {
                JSONObject result = results.getJSONObject(0);
                
                if (result.has("currentFlow")) {
                    JSONObject currentFlow = result.getJSONObject("currentFlow");
                    flow.setCurrentSpeed((int) currentFlow.optDouble("speed", 50));
                    flow.setFreeFlowSpeed((int) currentFlow.optDouble("freeFlow", 50));
                    
                    double jamFactor = currentFlow.optDouble("jamFactor", 0);
                    flow.setCongestion((int) (jamFactor * 100));
                    flow.setConfidence(currentFlow.optDouble("confidence", 0.8));
                }
            }
        }
        
        return flow;
    }

    private String parseISOTimestamp(String isoTime) {
        try {
            // Handle both ISO 8601 formats
            if (isoTime == null || isoTime.isEmpty()) {
                return "Unknown";
            }
            
            Instant instant = Instant.parse(isoTime);
            DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern("MMM dd, yyyy HH:mm")
                .withZone(ZoneId.systemDefault());
            return formatter.format(instant);
        } catch (Exception e) {
            System.err.println("Error parsing timestamp: " + isoTime + " - " + e.getMessage());
            return "Unknown";
        }
    }

    private List<TrafficIncident> getDemoIncidents(String bbox) {
        List<TrafficIncident> incidents = new ArrayList<>();
        Random random = new Random();
        
        String[] parts = bbox.split(",");
        if (parts.length == 4) {
            double minLon = Double.parseDouble(parts[0]);
            double minLat = Double.parseDouble(parts[1]);
            double maxLon = Double.parseDouble(parts[2]);
            double maxLat = Double.parseDouble(parts[3]);
            
            int count = 3 + random.nextInt(5);
            for (int i = 0; i < count; i++) {
                TrafficIncident incident = new TrafficIncident();
                incident.setLatitude(minLat + random.nextDouble() * (maxLat - minLat));
                incident.setLongitude(minLon + random.nextDouble() * (maxLon - minLon));
                
                String[] types = {"ACCIDENT", "ROAD_WORK", "CONGESTION", "CLOSED_ROAD", 
                                 "BROKEN_VEHICLE", "WEATHER", "EVENT"};
                incident.setType(types[random.nextInt(types.length)]);
                incident.setDescription(getIncidentDescription(incident.getType()));
                incident.setDelay(random.nextInt(20) + 1);
                incident.setLength(random.nextInt(1500) + 100);
                incident.setSeverity(random.nextInt(4) + 1);
                incident.setRoadName("Highway " + (random.nextInt(50) + 1));
                
                // Generate realistic timestamps
                long now = System.currentTimeMillis();
                long startOffset = random.nextInt(120) * 60000; // 0-2 hours ago
                incident.setStartTime(formatTimestamp(now - startOffset));
                incident.setEndTime(formatTimestamp(now + random.nextInt(180) * 60000)); // 0-3 hours from now
                
                incidents.add(incident);
            }
        }
        
        return incidents;
    }

    private TrafficFlow getDemoFlow(double lat, double lon) {
        Random random = new Random();
        TrafficFlow flow = new TrafficFlow();
        flow.setLatitude(lat);
        flow.setLongitude(lon);
        flow.setCurrentSpeed(25 + random.nextInt(60));
        flow.setFreeFlowSpeed(60 + random.nextInt(40));
        flow.setConfidence(0.7 + random.nextDouble() * 0.3);
        flow.setRoadName("Main Street");
        
        int congestion = 100 - (int)((flow.getCurrentSpeed() / (double)flow.getFreeFlowSpeed()) * 100);
        flow.setCongestion(Math.max(0, Math.min(100, congestion)));
        
        return flow;
    }

    private String getIncidentDescription(String type) {
        Map<String, String> descriptions = Map.of(
            "ACCIDENT", "Multi-vehicle accident",
            "ROAD_WORK", "Road construction in progress",
            "CONGESTION", "Heavy traffic congestion",
            "CLOSED_ROAD", "Road closure due to maintenance",
            "BROKEN_VEHICLE", "Disabled vehicle blocking lane",
            "WEATHER", "Hazardous weather conditions",
            "EVENT", "Special event causing delays"
        );
        return descriptions.getOrDefault(type, "Traffic incident");
    }

    private String formatTimestamp(long millis) {
        Instant instant = Instant.ofEpochMilli(millis);
        DateTimeFormatter formatter = DateTimeFormatter
            .ofPattern("MMM dd, yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
        return formatter.format(instant);
    }

    // Inner classes
    public static class TrafficIncident {
        private double latitude;
        private double longitude;
        private String description;
        private String type;
        private int delay;
        private int length;
        private int severity;
        private String startTime;
        private String endTime;
        private String roadName;

        // Getters and Setters
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getDelay() { return delay; }
        public void setDelay(int delay) { this.delay = delay; }
        public int getLength() { return length; }
        public void setLength(int length) { this.length = length; }
        public int getSeverity() { return severity; }
        public void setSeverity(int severity) { this.severity = severity; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public String getRoadName() { return roadName; }
        public void setRoadName(String roadName) { this.roadName = roadName; }
    }

    public static class TrafficFlow {
        private double latitude;
        private double longitude;
        private int currentSpeed;
        private int freeFlowSpeed;
        private double confidence;
        private int congestion;
        private String roadName;

        // Getters and Setters
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        public int getCurrentSpeed() { return currentSpeed; }
        public void setCurrentSpeed(int currentSpeed) { this.currentSpeed = currentSpeed; }
        public int getFreeFlowSpeed() { return freeFlowSpeed; }
        public void setFreeFlowSpeed(int freeFlowSpeed) { this.freeFlowSpeed = freeFlowSpeed; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public int getCongestion() { return congestion; }
        public void setCongestion(int congestion) { this.congestion = congestion; }
        public String getRoadName() { return roadName; }
        public void setRoadName(String roadName) { this.roadName = roadName; }
    }
}